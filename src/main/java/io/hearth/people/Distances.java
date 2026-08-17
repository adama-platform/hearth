package io.hearth.people;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How far people would have to come, as a shape rather than a list.
 *
 * <b>This is the only thing a private address is ever allowed to become.</b> One distance per
 * person, counted into a bucket with everybody else's, and then the distances are thrown away. No
 * names, no order, no "who is furthest", no map. A screen that answered "who lives near the hall"
 * would be a screen people stop giving their address to, and the address is only worth having
 * because everybody gives it.
 *
 * <b>Buckets, not an average.</b> The average of "twenty people within a mile and one person four
 * counties away" is a number that describes nobody and moves a lot. What a planner needs to see is
 * the shape: whether a venue puts most of the community within a walk, or splits them into a near
 * half and a far half. That is a decision a chart makes obvious and a mean actively hides.
 *
 * <b>Precise and rough are counted together and reported apart.</b> Somebody who gave a town is
 * placed at its centre, which is right to within a mile or two and wrong to pretend otherwise --
 * so the summary says how many of the numbers came from a doorstep. Leaving the rough ones out
 * entirely would be worse: they are usually the majority, and a chart built from the eight people
 * who typed a street address is a chart about eight people.
 *
 * <b>Nobody who said nothing is invented.</b> Unlike the availability grid, which counts silence
 * from a stated assumption, there is no sane default for where somebody lives -- so they are
 * counted as *not placed* and the number is printed beside the chart. A planner reading "31 of 48
 * placed" knows what they are looking at; one reading a chart of 48 with 17 of them guessed does
 * not.
 */
public final class Distances {
  /** the earth, near enough for a question about whether to book a hall */
  private static final double EARTH_KM = 6371.0088;
  private static final double MILES_PER_KM = 0.621371;

  /** the far edge of each bucket, in kilometres */
  private static final double[] METRIC = {1, 3, 5, 10, 25, 50};
  /** and in miles, which are not the same shape: a mile is a walk and three kilometres is not */
  private static final double[] IMPERIAL = {1, 2, 5, 10, 25, 50};

  private Distances() {
  }

  /**
   * Great-circle distance in kilometres.
   *
   * Haversine rather than anything cleverer. The error against a proper ellipsoid is a fraction of
   * a percent, which is nothing next to the error introduced by placing somebody at the centre of
   * their town -- and this decides which side of a bucket boundary a person falls on, not where to
   * land an aeroplane.
   */
  public static double kilometres(double fromLat, double fromLon, double toLat, double toLon) {
    double dLat = Math.toRadians(toLat - fromLat);
    double dLon = Math.toRadians(toLon - fromLon);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat))
        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * EARTH_KM * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  /** one row of the chart */
  public record Bucket(String label, int count, int share) {
  }

  /** everything the screen needs, and nothing that could identify anybody */
  public record Travel(List<Bucket> buckets, int placed, int precise, int members, int median,
                       String unit, boolean any) {
    /** how many said nothing at all about where they are */
    public int unplaced() {
      return Math.max(0, members - placed);
    }
  }

  /**
   * Build the chart.
   *
   * @param points user id to {latitude, longitude, 1 if precise}, exactly as
   *     {@code PeopleStore.points()} returns it -- which carries no address and no name, because
   *     that is the widest view of this data that exists anywhere.
   * @param members how many people are in the community, so the chart can say who is missing from
   *     it rather than quietly describing a subset as everybody.
   */
  public static Travel from(Map<Long, double[]> points, double lat, double lon, int members,
                            boolean imperial) {
    double[] edges = imperial ? IMPERIAL : METRIC;
    String unit = imperial ? "mi" : "km";
    int[] counts = new int[edges.length + 1];
    ArrayList<Double> all = new ArrayList<>();
    int precise = 0;
    for (double[] point : points.values()) {
      double km = kilometres(point[0], point[1], lat, lon);
      double distance = imperial ? km * MILES_PER_KM : km;
      all.add(distance);
      if (point.length > 2 && point[2] > 0) {
        precise++;
      }
      int bucket = edges.length;
      for (int k = 0; k < edges.length; k++) {
        if (distance < edges[k]) {
          bucket = k;
          break;
        }
      }
      counts[bucket]++;
    }

    int placed = all.size();
    int most = 0;
    for (int count : counts) {
      most = Math.max(most, count);
    }
    ArrayList<Bucket> buckets = new ArrayList<>();
    for (int k = 0; k < counts.length; k++) {
      String label = k == 0 ? "under " + trim(edges[0]) + " " + unit
          : k == edges.length ? "over " + trim(edges[edges.length - 1]) + " " + unit
              : trim(edges[k - 1]) + "-" + trim(edges[k]) + " " + unit;
      // the bar is against the biggest bucket rather than the total: a chart where every bar is
      // 12% of the width says nothing, and this one is read for its shape
      buckets.add(new Bucket(label, counts[k], most == 0 ? 0 : counts[k] * 100 / most));
    }

    all.sort(Double::compare);
    int median = all.isEmpty() ? 0 : (int) Math.round(all.get(all.size() / 2));
    return new Travel(buckets, placed, precise, members, median, unit, placed > 0);
  }

  /** a mustache-friendly view of the same thing */
  public static Map<String, Object> model(Travel travel) {
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Bucket bucket : travel.buckets()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("label", bucket.label());
      row.put("count", bucket.count());
      row.put("share", bucket.share());
      row.put("any", bucket.count() > 0);
      rows.add(row);
    }
    out.put("buckets", rows);
    out.put("placed", travel.placed());
    out.put("precise", travel.precise());
    out.put("rough", travel.placed() - travel.precise());
    out.put("members", travel.members());
    out.put("unplaced", travel.unplaced());
    out.put("median", travel.median());
    out.put("unit", travel.unit());
    out.put("any", travel.any());
    return out;
  }

  private static String trim(double value) {
    return value == Math.rint(value) ? Integer.toString((int) value) : Double.toString(value);
  }
}
