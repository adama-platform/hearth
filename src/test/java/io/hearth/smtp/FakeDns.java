package io.hearth.smtp;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A nameserver that says exactly what a test tells it to.
 *
 * Every test of SPF, DKIM or DMARC is really a test of what happens when DNS says a particular
 * thing, and one that needs the real internet to say it is a test that fails on a train and passes
 * for the wrong reason on a bad day. Nothing in this package's tests touches the network.
 */
public class FakeDns implements SmtpDns {
  private final Map<String, List<String>> txt = new LinkedHashMap<>();
  private final Map<String, List<String>> mx = new LinkedHashMap<>();
  private final Map<String, List<InetAddress>> addresses = new LinkedHashMap<>();
  /** every question asked, so a test can prove the lookup budget was respected */
  public final List<String> asked = new ArrayList<>();

  public FakeDns txt(String name, String... records) {
    txt.computeIfAbsent(key(name), key -> new ArrayList<>()).addAll(List.of(records));
    return this;
  }

  public FakeDns mx(String name, String... hosts) {
    mx.computeIfAbsent(key(name), key -> new ArrayList<>()).addAll(List.of(hosts));
    return this;
  }

  public FakeDns address(String name, String... ips) {
    List<InetAddress> list = addresses.computeIfAbsent(key(name), key -> new ArrayList<>());
    for (String ip : ips) {
      try {
        list.add(InetAddress.getByName(ip));
      } catch (Exception ex) {
        throw new IllegalArgumentException(ip, ex);
      }
    }
    return this;
  }

  @Override
  public String[] txt(String name) {
    asked.add("TXT " + key(name));
    return txt.getOrDefault(key(name), List.of()).toArray(new String[0]);
  }

  @Override
  public String[] mx(String name) {
    asked.add("MX " + key(name));
    return mx.getOrDefault(key(name), List.of()).toArray(new String[0]);
  }

  @Override
  public List<InetAddress> addresses(String name) {
    asked.add("A " + key(name));
    return addresses.getOrDefault(key(name), List.of());
  }

  public int lookups() {
    return asked.size();
  }

  private static String key(String name) {
    String clean = name == null ? "" : name.trim().toLowerCase();
    return clean.endsWith(".") ? clean.substring(0, clean.length() - 1) : clean;
  }
}
