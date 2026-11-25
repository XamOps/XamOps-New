package com.xammer.cloud.xampulse.service.alert.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.alert.OutboundUrlGuard;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Guarded JSON POST: https only, no redirects, 5 s timeouts, response capped at 64 KB. The connection's DNS
 * resolution is the SSRF guard itself, so the address that is validated is the address that is connected to
 * (DNS-rebinding safe), while SNI and certificate checks still use the hostname.
 */
@Component("xpOutboundHttp")
public class OutboundHttp {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Timeout TIMEOUT = Timeout.ofSeconds(5);

    private final OutboundUrlGuard guard;
    private final ObjectMapper mapper;
    private final CloseableHttpClient client;

    @Autowired
    public OutboundHttp(OutboundUrlGuard guard, ObjectMapper objectMapper) {
        this(guard, objectMapper, guardedResolver(guard));
    }

    /** For tests: supply the DNS resolver the connection manager uses. */
    OutboundHttp(OutboundUrlGuard guard, ObjectMapper objectMapper, DnsResolver dnsResolver) {
        this.guard = guard;
        this.mapper = objectMapper;
        PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(cm)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(TIMEOUT)
                        .setConnectionRequestTimeout(TIMEOUT)
                        .setResponseTimeout(TIMEOUT)
                        .build())
                .build();
    }

    /** The guard as an Apache DNS resolver: one resolution, only validated addresses come back. */
    static DnsResolver guardedResolver(OutboundUrlGuard guard) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try {
                    List<InetAddress> ok = guard.resolveAllowed(host);
                    return ok.toArray(new InetAddress[0]);
                } catch (XamPulseException e) {
                    throw new UnknownHostException("Destination host is not allowed");
                }
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    public void postJson(String url, Object payload) throws IOException, InterruptedException {
        URI uri = guard.validateSyntax(url); // DNS is checked by the connection's resolver, once
        HttpPost post = new HttpPost(uri);
        post.setEntity(new StringEntity(mapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));
        client.execute(post, res -> {
            if (res.getEntity() != null) {
                try (InputStream in = res.getEntity().getContent()) {
                    in.readNBytes(MAX_RESPONSE_BYTES); // drain a bounded amount, ignore the content
                }
            }
            if (res.getCode() / 100 != 2) {
                throw new IOException("Destination answered HTTP " + res.getCode());
            }
            return null;
        });
    }
}
