package io.hearth.certs;

import io.hearth.common.Verbose;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.Mapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which certificate to present, per hostname.
 *
 * The client says which host it wants during the TLS handshake (SNI), and this answers with that
 * domain's certificate. That is the whole reason one process can serve several communities on one
 * address: without SNI, a certificate would have to cover every domain the server has, which would
 * mean re-issuing all of them whenever a new one is added.
 *
 * It is a live map rather than a snapshot handed to Netty at boot. Certificates are renewed by a
 * background thread months after startup, and a handler holding a fixed context would keep
 * presenting the expired one until somebody restarted the server -- which is exactly the outage
 * automatic renewal exists to prevent. {@link #reload} is called when a renewal lands.
 *
 * **The fallback is deliberately a self-signed certificate, not a refusal.** A domain with no
 * certificate yet still completes a handshake, and the browser says "not secure". The alternative --
 * dropping the connection -- looks identical to a firewall problem, and people spend hours on that.
 * A browser warning names the actual problem in the first sentence. It is generated in memory at
 * startup and never written to disk.
 */
public class TlsContexts implements Mapping<String, SslContext> {
  private static final Logger LOG = LoggerFactory.getLogger(TlsContexts.class);

  private final CertStore store;
  private final Verbose verbose;
  private final boolean http2;
  private final Map<String, SslContext> contexts = new ConcurrentHashMap<>();
  private volatile SslContext fallback;

  public TlsContexts(CertStore store, Verbose verbose) {
    this(store, verbose, true);
  }

  public TlsContexts(CertStore store, Verbose verbose, boolean http2) {
    this.store = store;
    this.verbose = verbose;
    this.http2 = http2;
  }

  public boolean http2() {
    return http2;
  }

  /**
   * Offer HTTP/2, and fall back without complaint.
   *
   * ALPN is negotiated inside the handshake, so this is the only place the protocol can be agreed.
   * NO_ADVERTISE and ACCEPT mean a client that does not understand the extension simply gets
   * HTTP/1.1 rather than a failed connection, which is what "enabling http/2" has to mean on a
   * server that still has to talk to everything else.
   */
  private void withAlpn(SslContextBuilder builder) {
    if (!http2) {
      return;
    }
    builder.applicationProtocolConfig(new ApplicationProtocolConfig(
        ApplicationProtocolConfig.Protocol.ALPN,
        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
        ApplicationProtocolNames.HTTP_2,
        ApplicationProtocolNames.HTTP_1_1));
  }

  /**
   * Load every certificate in the cache.
   *
   * Returns how many were usable. A bundle that will not load is skipped with a complaint rather
   * than being fatal -- one broken file must not take down TLS for the domains whose files are fine.
   */
  public int reload() {
    int loaded = 0;
    for (CertStore.Held held : store.all()) {
      try {
        SslContext context = build(held.domain());
        if (context != null) {
          contexts.put(held.domain().toLowerCase(Locale.ROOT), context);
          loaded++;
        }
      } catch (Exception ex) {
        LOG.warn("tls-load-failed for {}", held.domain(), ex);
        verbose.say("tls: could not load the certificate for " + held.domain() + ": " + ex.getMessage());
      }
    }
    verbose.say("tls: holding " + loaded + " certificate(s)");
    return loaded;
  }

  /** load one domain, for when a single renewal lands */
  public boolean reload(String domain) {
    try {
      SslContext context = build(domain);
      if (context == null) {
        return false;
      }
      contexts.put(domain.toLowerCase(Locale.ROOT), context);
      verbose.say("tls: now presenting the new certificate for " + domain);
      return true;
    } catch (Exception ex) {
      LOG.warn("tls-load-failed for {}", domain, ex);
      verbose.say("tls: could not load the new certificate for " + domain + ": " + ex.getMessage());
      return false;
    }
  }

  private SslContext build(String domain) throws Exception {
    File bundle = store.bundleFile(domain);
    String chainPem;
    String keyPem;
    if (bundle.isFile()) {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bundle);
      chainPem = node.path("cert").asText("");
      keyPem = node.path("key").asText("");
    } else {
      return null;
    }
    if (chainPem.isBlank() || keyPem.isBlank()) {
      return null;
    }
    SslContextBuilder builder = SslContextBuilder.forServer(
        new ByteArrayInputStream(chainPem.getBytes(StandardCharsets.UTF_8)),
        new ByteArrayInputStream(keyPem.getBytes(StandardCharsets.UTF_8)));
    withAlpn(builder);
    return builder.build();
  }

  /**
   * The context for a hostname, or the fallback.
   *
   * Netty calls this on the handshake, so it must not do I/O -- which is why the contexts are built
   * ahead of time and this is a map lookup.
   */
  @Override
  public SslContext map(String hostname) {
    if (hostname != null) {
      SslContext exact = contexts.get(hostname.toLowerCase(Locale.ROOT));
      if (exact != null) {
        return exact;
      }
    }
    verbose.detail(() -> "tls: no certificate for " + hostname + ", presenting the fallback");
    return fallback();
  }

  /** the self-signed certificate presented for anything we do not hold one for */
  public synchronized SslContext fallback() {
    if (fallback == null) {
      try {
        SelfSignedCertificate self = new SelfSignedCertificate("hearth.invalid");
        SslContextBuilder builder = SslContextBuilder.forServer(self.certificate(), self.privateKey());
        withAlpn(builder);
        fallback = builder.build();
        // netty writes these to temp files; nothing here should outlive the process
        self.delete();
      } catch (Exception ex) {
        LOG.error("tls-fallback-failed", ex);
        throw new IllegalStateException("could not build a fallback TLS context", ex);
      }
    }
    return fallback;
  }

  public int size() {
    return contexts.size();
  }

  public boolean has(String domain) {
    return domain != null && contexts.containsKey(domain.toLowerCase(Locale.ROOT));
  }

  public List<String> domains() {
    return contexts.keySet().stream().sorted().toList();
  }

  /** for the boot report: what is actually presentable right now */
  public String describe() {
    if (contexts.isEmpty()) {
      return "no certificates yet; every host gets a self-signed one until the first order lands";
    }
    return contexts.size() + " certificate(s): " + String.join(", ", domains());
  }
}
