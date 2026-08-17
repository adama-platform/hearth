package io.hearth.smtp;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The DNS this validation rests on.
 *
 * An interface first, because every test of SPF, DKIM or DMARC is really a test of what happens
 * when DNS says a particular thing -- and a test that needs the internet to say it is a test that
 * fails on a train. Adama's version is asynchronous with callbacks; this one is deliberately
 * synchronous, because it is called from a worker thread rather than the event loop and a blocking
 * call there is simpler to be sure of than a chain of callbacks.
 *
 * The JDK implementation uses JNDI's DNS provider rather than adding a resolver dependency for
 * three record types. It is not fast, which is why nothing on the request path uses it and why the
 * answers are cached for the life of a message.
 */
public interface SmtpDns {
  /** the TXT records for a name, or an empty array; never null */
  String[] txt(String name);

  /** mail exchangers, hostnames only, most-preferred first */
  String[] mx(String name);

  /** A and AAAA */
  List<InetAddress> addresses(String name);

  /** the standard one, over JNDI */
  class Jdk implements SmtpDns {
    /**
     * Answers are cached for the life of this object, which is one per message.
     *
     * An SPF record with three includes asks the same question repeatedly, and a record that
     * changed halfway through evaluating one message would produce a result that was true of no
     * moment in time. Caching for a message and no longer is the honest scope.
     */
    private final ConcurrentHashMap<String, String[]> txtCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String[]> mxCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<InetAddress>> addressCache =
        new ConcurrentHashMap<>();
    private final int timeoutMillis;

    public Jdk(int timeoutMillis) {
      this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String[] txt(String name) {
      return txtCache.computeIfAbsent(key(name), key -> lookup(key, "TXT"));
    }

    @Override
    public String[] mx(String name) {
      return mxCache.computeIfAbsent(key(name), key -> {
        String[] raw = lookup(key, "MX");
        ArrayList<String> hosts = new ArrayList<>();
        // "10 mail.example.org." -- the preference, a space, the host, usually with a trailing dot
        for (String record : raw) {
          String[] parts = record.trim().split("\\s+");
          String host = parts.length > 1 ? parts[parts.length - 1] : parts[0];
          if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
          }
          if (!host.isEmpty()) {
            hosts.add(host);
          }
        }
        return hosts.toArray(new String[0]);
      });
    }

    @Override
    public List<InetAddress> addresses(String name) {
      return addressCache.computeIfAbsent(key(name), key -> {
        try {
          return List.of(InetAddress.getAllByName(key));
        } catch (Exception ex) {
          return List.of();
        }
      });
    }

    private String[] lookup(String name, String type) {
      Hashtable<String, String> environment = new Hashtable<>();
      environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
      environment.put("java.naming.provider.url", "dns:");
      environment.put("com.sun.jndi.dns.timeout.initial", Integer.toString(timeoutMillis));
      environment.put("com.sun.jndi.dns.timeout.retries", "1");
      try {
        InitialDirContext context = new InitialDirContext(environment);
        try {
          Attributes attributes = context.getAttributes(name, new String[]{type});
          Attribute attribute = attributes.get(type);
          if (attribute == null) {
            return new String[0];
          }
          ArrayList<String> values = new ArrayList<>();
          NamingEnumeration<?> records = attribute.getAll();
          while (records.hasMore()) {
            values.add(unquote(String.valueOf(records.next())));
          }
          return values.toArray(new String[0]);
        } finally {
          context.close();
        }
      } catch (NamingException ex) {
        // No record and a broken resolver look the same here, and the callers treat them
        // differently -- so this returns nothing and the *caller* decides whether nothing means
        // "no policy" or "try again later". Guessing here would turn a temporary DNS problem into
        // a permanent rejection.
        return new String[0];
      } catch (Exception ex) {
        return new String[0];
      }
    }

    /**
     * A long TXT record arrives as several quoted strings that have to be joined.
     *
     * DNS caps a string at 255 bytes, so any real SPF or DKIM key record is split -- and joining
     * them with anything between them, or leaving the quotes on, produces a record that parses to
     * nonsense and a validation that fails for a reason nobody can see.
     */
    static String unquote(String raw) {
      StringBuilder out = new StringBuilder(raw.length());
      boolean quoted = false;
      for (int k = 0; k < raw.length(); k++) {
        char ch = raw.charAt(k);
        if (ch == '"') {
          quoted = !quoted;
          continue;
        }
        if (!quoted && (ch == ' ' || ch == '\t')) {
          // whitespace between two quoted chunks is the join, not part of the value
          boolean between = k > 0 && raw.charAt(k - 1) == '"';
          if (between) {
            continue;
          }
        }
        out.append(ch);
      }
      return out.toString();
    }

    private static String key(String name) {
      String clean = name == null ? "" : name.trim().toLowerCase();
      return clean.endsWith(".") ? clean.substring(0, clean.length() - 1) : clean;
    }
  }
}
