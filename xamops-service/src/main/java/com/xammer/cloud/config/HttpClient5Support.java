package com.xammer.cloud.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

/**
 * Connection managers for the Apache HttpClient 5 based {@code RestTemplate}s.
 *
 * <p>Spring Framework 7 dropped HttpClient 4 support and removed
 * {@code HttpComponentsClientHttpRequestFactory.setConnectTimeout}. The connect
 * timeout is a property of the connection manager in HttpClient 5, so it has to be
 * supplied when the client is built rather than set on the request factory. Read and
 * connection-request timeouts still live on the factory.
 */
public final class HttpClient5Support {

    /** Pool defaults for clients that do not size their own pool. */
    public static final int DEFAULT_MAX_TOTAL = 25;
    public static final int DEFAULT_MAX_PER_ROUTE = 5;

    private HttpClient5Support() {
    }

    public static PoolingHttpClientConnectionManager connectionManager(long connectTimeoutMillis) {
        return connectionManager(DEFAULT_MAX_TOTAL, DEFAULT_MAX_PER_ROUTE, connectTimeoutMillis);
    }

    public static PoolingHttpClientConnectionManager connectionManager(
            int maxTotal, int maxPerRoute, long connectTimeoutMillis) {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .setDefaultConnectionConfig(connectionConfig(connectTimeoutMillis))
                .build();
    }

    /**
     * Connection manager that accepts any server certificate and skips hostname
     * verification.
     *
     * <p>NOTE: this reproduces the pre-upgrade behaviour exactly. It is not safe — it
     * removes TLS authentication for these calls — but tightening it is a behaviour
     * change for the CloudFront/CloudiDR and LLM provider integrations and belongs in
     * its own change, not in a framework upgrade.
     */
    public static PoolingHttpClientConnectionManager trustAllConnectionManager(
            int maxTotal, int maxPerRoute, long connectTimeoutMillis)
            throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial((chain, authType) -> true)
                .build();
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext, NoopHostnameVerifier.INSTANCE))
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .setDefaultConnectionConfig(connectionConfig(connectTimeoutMillis))
                .build();
    }

    private static ConnectionConfig connectionConfig(long connectTimeoutMillis) {
        return ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMillis))
                .build();
    }
}
