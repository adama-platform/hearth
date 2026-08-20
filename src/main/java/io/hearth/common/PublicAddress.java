package io.hearth.common;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Is this host somewhere on the public internet, or somewhere inside?
 *
 * <b>A url a member supplied is an instruction to make a request.</b> There are two places that
 * happens -- the calendar link somebody pastes so the availability grid knows when they are busy,
 * and the push endpoint a browser hands over when it subscribes to notifications -- and they had
 * two different answers to the same question. The calendar side resolved the name and refused every
 * private range; the push side checked that the string started with `https://` and made the request.
 *
 * So the rule lives here once. Both callers ask the same thing, and a third one that appears later
 * has an obvious place to ask it rather than an obvious place to forget.
 *
 * <b>Resolved, not read.</b> A name under somebody's control can point at 127.0.0.1, and a check on
 * the text would never notice. Every address a name resolves to is checked, because a name with two
 * A records is a name where only one of them has to be inside.
 *
 * <b>What this is not.</b> It cannot close the gap between resolving here and the HTTP client
 * resolving again a moment later -- a hostile nameserver with a zero second record can answer
 * differently twice. What actually stops that on both paths is that they are https-only and the
 * certificate has to match the name that was asked for, which an internal service will not have.
 * That is worth writing down because it means **relaxing https-only, or turning off certificate
 * verification, re-opens something this check only appears to close.**
 */
public final class PublicAddress {
  private PublicAddress() {
  }

  /**
   * Is this host *known* to be inside?
   *
   * Only true when the name resolves and something it resolves to is a private range. A name that
   * will not resolve answers false, which is the difference between this and {@link #refuse}: for a
   * url somebody typed, "that does not resolve" is a useful thing to say back immediately, and for a
   * url a browser handed over there is nobody to say it to and a nameserver having a bad minute
   * would silently switch somebody's notifications off. Whatever cannot be resolved cannot be
   * reached either, so nothing is lost by letting the request itself fail.
   */
  public static boolean isPrivate(String host) {
    if (host == null || host.isBlank()) {
      return false;
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(host)) {
        if (isInside(address)) {
          return true;
        }
      }
    } catch (IOException ex) {
      return false;
    }
    return false;
  }

  /** null when the host is fine to ask, or a short reason when it is not */
  public static String refuse(String host) {
    if (host == null || host.isBlank()) {
      return "that address has no host in it";
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (IOException ex) {
      return "that address does not resolve";
    }
    if (addresses.length == 0) {
      return "that address does not resolve";
    }
    for (InetAddress address : addresses) {
      if (isInside(address)) {
        return "that address is on a private network";
      }
    }
    return null;
  }

  /** every range that is not the public internet, including the ones Java has no question for */
  public static boolean isInside(InetAddress address) {
    return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
        || address.isSiteLocalAddress() || address.isMulticastAddress()
        || isUniqueLocal(address) || isCarrierGrade(address);
  }

  /** fc00::/7, which Java does not have a question for */
  private static boolean isUniqueLocal(InetAddress address) {
    byte[] bytes = address.getAddress();
    return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
  }

  /** 100.64.0.0/10: a provider's own space, and just as much "inside" as a private range */
  private static boolean isCarrierGrade(InetAddress address) {
    byte[] bytes = address.getAddress();
    return bytes.length == 4 && (bytes[0] & 0xFF) == 100
        && (bytes[1] & 0xFF) >= 64 && (bytes[1] & 0xFF) <= 127;
  }
}
