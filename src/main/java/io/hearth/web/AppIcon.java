package io.hearth.web;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * The app icon, drawn at request time rather than kept as a file.
 *
 * <b>This exists because a data: URI is not installable.</b> The manifest used to point its icons
 * at the same inline SVG the favicon uses, which is correct by the specification and refused in
 * practice: Chrome downloads manifest icons and will not install an app whose icons it cannot
 * fetch from a URL, and iOS wants a PNG `apple-touch-icon` before it will put anything on a home
 * screen. So the app was, quietly, not installable at all -- the manifest was there, the worker was
 * there, and the button never appeared.
 *
 * <b>Drawn rather than stored, which keeps invariant 18 intact.</b> There is still no image file
 * anywhere: two circles and a tick, in the community's own accent colour, rendered into a PNG in
 * memory and cached. A community that changes its colours gets an icon that changes with them,
 * which a checked-in file could never do.
 *
 * <b>Anything that goes wrong here is a missing icon, never a failed page.</b> Image encoding is
 * the one thing in this server that depends on a part of the JDK a stripped runtime might not
 * carry, so every failure path answers null and the caller answers 404.
 */
public final class AppIcon {
  /** the sizes a browser actually asks for: 192 for a home screen, 512 for a splash */
  public static final int SMALL = 192;
  public static final int LARGE = 512;

  private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

  private AppIcon() {
  }

  /**
   * One icon.
   *
   * @param maskable draw the mark small inside the square, so a phone can crop it into whatever
   *     shape it uses without cutting the tick in half. A maskable icon that fills its canvas is
   *     an icon Android turns into a circle with the corners of the picture missing.
   */
  public static byte[] png(int size, String accent, boolean maskable) {
    String key = size + ":" + accent + ":" + maskable;
    byte[] cached = CACHE.get(key);
    if (cached != null) {
      return cached;
    }
    byte[] made = draw(size, accent, maskable);
    if (made != null) {
      if (CACHE.size() > 64) {
        CACHE.clear();
      }
      CACHE.put(key, made);
    }
    return made;
  }

  private static byte[] draw(int size, String accent, boolean maskable) {
    try {
      // headless, because this is a server: without it a machine with no display refuses to make
      // an image at all
      System.setProperty("java.awt.headless", "true");
      BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
      Graphics2D canvas = image.createGraphics();
      canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      canvas.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
          RenderingHints.VALUE_STROKE_PURE);

      Color colour = colourOf(accent);
      double inset = maskable ? size * 0.18 : 0;
      double diameter = size - inset * 2;
      if (maskable) {
        // the safe zone is a circle 80% across the icon; everything outside it may be cropped, so
        // the background fills the whole square and the mark stays well inside
        canvas.setColor(colour);
        canvas.fillRect(0, 0, size, size);
        canvas.setColor(Color.WHITE);
        canvas.fill(new Ellipse2D.Double(inset, inset, diameter, diameter));
        canvas.setColor(colour);
        double ring = diameter * 0.10;
        canvas.fill(new Ellipse2D.Double(inset + ring, inset + ring,
            diameter - ring * 2, diameter - ring * 2));
      } else {
        canvas.setColor(colour);
        canvas.fill(new Ellipse2D.Double(0, 0, size, size));
      }

      // the same tick the favicon draws, at the same proportions
      double centre = size / 2.0;
      double reach = (maskable ? diameter : size) * 0.30;
      canvas.setColor(Color.WHITE);
      canvas.setStroke(new java.awt.BasicStroke((float) (size * 0.09),
          java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
      java.awt.geom.Path2D tick = new java.awt.geom.Path2D.Double();
      tick.moveTo(centre - reach, centre + reach * 0.08);
      tick.lineTo(centre - reach * 0.22, centre + reach * 0.62);
      tick.lineTo(centre + reach, centre - reach * 0.55);
      canvas.draw(tick);
      canvas.dispose();

      ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
      if (!javax.imageio.ImageIO.write(image, "png", out)) {
        return null;
      }
      return out.toByteArray();
    } catch (IOException | RuntimeException | LinkageError ex) {
      // a runtime without java.desktop, or a headless failure: no icon, and the manifest still
      // lists the SVG so the site keeps its favicon
      return null;
    }
  }

  /** a hex colour from the theme, or the default when it is not one */
  static Color colourOf(String accent) {
    try {
      String value = accent == null ? "" : accent.trim();
      if (value.startsWith("#")) {
        value = value.substring(1);
      }
      if (value.length() == 3) {
        value = "" + value.charAt(0) + value.charAt(0) + value.charAt(1) + value.charAt(1)
            + value.charAt(2) + value.charAt(2);
      }
      if (value.length() != 6) {
        return new Color(0x2f5cff);
      }
      return new Color(Integer.parseInt(value, 16));
    } catch (NumberFormatException ex) {
      return new Color(0x2f5cff);
    }
  }
}
