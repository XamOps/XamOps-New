package com.xammer.cloud.service;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Collection;

/**
 * Builds Kubernetes clients for customer clusters.
 *
 * <h2>TLS trust (audit finding B7)</h2>
 *
 * Both factory methods used to call {@code trustCerts(true)}, which disables certificate
 * verification for <em>every</em> customer API-server connection. The comments claimed it worked
 * around "PKIX path validation errors", but what it actually did was accept any certificate from
 * any host: an attacker able to intercept the connection could impersonate the API server, read the
 * bearer token in the request and then act as XamOps inside the customer's cluster. EKS CAs are
 * per-cluster and self-signed, so they are never in the JVM truststore — the fix is to trust the
 * cluster's own CA explicitly, not to trust everything.
 *
 * <p>This class is shared by Kubescape, Trivy, Karpenter and OpenCost
 * ({@code CloudK8sSecurityController}, {@code EksDashboardService}) and by the EKS direct path
 * ({@code EksService.getKubernetesClientForCustomerCluster}). Both method signatures are unchanged,
 * so every call site still compiles; what changed is that a connection with no verifiable CA now
 * <strong>fails closed</strong> with {@link TlsTrustException} instead of succeeding insecurely.
 *
 * <p><strong>There is deliberately no trust-all fallback and no system-property escape hatch.</strong>
 * A fallback would make the fix decorative: the insecure path would simply be taken whenever the
 * secure one was inconvenient.
 *
 * <p><strong>Behaviour change to note on release:</strong> a customer who uploaded a kubeconfig with
 * no {@code certificate-authority-data} / {@code certificate-authority} (or one relying on
 * {@code insecure-skip-tls-verify}) will see their Kubescape / Trivy / Karpenter / OpenCost direct
 * path stop with a clear message naming the field to fix, rather than keep working without
 * verification. The EKS path is unaffected: {@code describeCluster().certificateAuthority().data()}
 * always supplies the real CA, which the previous code fetched and then threw away.
 */
@Service
public class K8sClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(K8sClientFactory.class);

    /**
     * Raised when a client cannot be built with verifiable TLS trust.
     *
     * <p>An {@link IllegalStateException} subclass on purpose: {@code GlobalExceptionHandler}
     * already maps that family to a clean error response, so a missing CA surfaces as a message
     * naming the field to fix rather than as a stack trace.
     */
    public static class TlsTrustException extends IllegalStateException {
        public TlsTrustException(String message) {
            super(message);
        }

        public TlsTrustException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Create a Kubernetes client from a kubeconfig YAML string, verifying the API server against
     * the CA the kubeconfig itself declares.
     *
     * @throws TlsTrustException if the kubeconfig declares neither {@code certificate-authority-data}
     *                           nor {@code certificate-authority}; if the declared CA data does not
     *                           parse as an X.509 certificate; or if the declared CA
     *                           <em>file</em> is missing, unreadable, empty or not a certificate.
     *                           There is no insecure fallback.
     */
    public KubernetesClient createFromKubeconfig(String kubeconfigYaml) {
        logger.info("Creating Kubernetes client from kubeconfig");

        Config config;
        try {
            config = Config.fromKubeconfig(kubeconfigYaml);
        } catch (Exception e) {
            logger.error("Failed to parse kubeconfig", e);
            throw new RuntimeException("Could not connect to Kubernetes cluster: " + e.getMessage(), e);
        }

        String caCertData = config.getCaCertData();
        String caCertFile = config.getCaCertFile();
        boolean hasCaData = caCertData != null && !caCertData.isBlank();
        boolean hasCaFile = caCertFile != null && !caCertFile.isBlank();

        if (!hasCaData && !hasCaFile) {
            // Fail closed. Previously this is exactly where trustCerts(true) hid the problem:
            // a kubeconfig with insecure-skip-tls-verify, or with its CA stripped, connected
            // anyway and verified nothing.
            throw new TlsTrustException(
                    "Kubeconfig declares no cluster CA, so the API server's certificate cannot be verified. "
                            + "Set 'certificate-authority-data' (base64 PEM) or 'certificate-authority' "
                            + "(file path) on the cluster entry of the stored kubeconfig "
                            + "(kubernetes_cluster_config.kubeconfig_yaml). "
                            + "'insecure-skip-tls-verify' is not accepted.");
        }

        // Both forms are validated here, and for the same reason: the point of the fix is that a
        // connection XamOps cannot verify stops with a message naming the field to fix. A CA file
        // accepted unchecked would still fail closed, but it would fail inside the HTTP client's
        // truststore construction with an error that names nothing.
        if (hasCaData) {
            requireX509(caCertData, "the kubeconfig's certificate-authority-data");
        } else {
            requireX509(readCaFile(caCertFile), "the kubeconfig's certificate-authority file " + caCertFile);
        }

        // Explicit rather than implied: verification stays on even if the kubeconfig asked for
        // insecure-skip-tls-verify, and hostnames are checked against the certificate's SANs.
        config.setTrustCerts(false);
        config.setDisableHostnameVerification(false);

        KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build();
        logger.info("Kubernetes client created with TLS verification against the kubeconfig's CA");
        return client;
    }

    /**
     * Create a Kubernetes client from individual components, verifying the API server against
     * {@code caCertData}.
     *
     * <p>{@code caCertData} is the cluster's own CA: for EKS, the base64 PEM from
     * {@code describeCluster().certificateAuthority().data()}. Raw PEM is accepted too.
     *
     * @throws TlsTrustException if {@code caCertData} is blank or does not parse as at least one
     *                           X.509 certificate. There is no insecure fallback.
     */
    public KubernetesClient createFromComponents(String apiServer, String caCertData, String token) {
        logger.info("Creating Kubernetes client for API server: {}", apiServer);

        if (caCertData == null || caCertData.isBlank()) {
            throw new TlsTrustException(
                    "No cluster CA certificate was supplied for API server " + apiServer
                            + ", so its certificate cannot be verified. For EKS this is "
                            + "describeCluster().certificateAuthority().data(); an empty value means the "
                            + "cluster description was incomplete and the connection must not be attempted.");
        }
        requireX509(caCertData, "the supplied cluster CA certificate data");

        Config config = new ConfigBuilder()
                .withMasterUrl(apiServer)
                .withCaCertData(caCertData)
                .withOauthToken(token)
                // Explicit: the cluster's real CA is the trust anchor, nothing else.
                .withTrustCerts(false)
                .withDisableHostnameVerification(false)
                .build();

        KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build();
        logger.info("Kubernetes client created with TLS verification against the cluster CA");
        return client;
    }

    /**
     * Reads the file a kubeconfig's {@code certificate-authority} points at.
     *
     * @throws TlsTrustException if it is missing, unreadable, a directory or empty — each of which
     *                           means the API server's certificate cannot be verified
     */
    private static String readCaFile(String caCertFile) {
        Path path;
        try {
            path = Path.of(caCertFile);
        } catch (InvalidPathException e) {
            throw new TlsTrustException(
                    "The kubeconfig's 'certificate-authority' is not a usable file path: " + caCertFile
                            + ". Set 'certificate-authority-data' (base64 PEM) instead.", e);
        }
        if (!Files.isRegularFile(path)) {
            throw new TlsTrustException(
                    "The kubeconfig's 'certificate-authority' points at " + caCertFile
                            + ", which is not a readable file on this server, so the API server's "
                            + "certificate cannot be verified. A stored kubeconfig should carry "
                            + "'certificate-authority-data' (base64 PEM) instead of a path to a file "
                            + "that only exists on the machine the kubeconfig was written on.");
        }
        String contents;
        try {
            contents = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TlsTrustException(
                    "Could not read the kubeconfig's 'certificate-authority' file " + caCertFile + ".", e);
        }
        if (contents.isBlank()) {
            throw new TlsTrustException(
                    "The kubeconfig's 'certificate-authority' file " + caCertFile + " is empty, so the "
                            + "API server's certificate cannot be verified.");
        }
        return contents;
    }

    /**
     * Asserts that {@code caCertData} really is one or more X.509 certificates, so a typo or a
     * truncated value fails here with a clear message instead of surfacing later as an opaque
     * handshake error.
     *
     * <p>Accepts both forms fabric8 accepts: base64-encoded PEM (what the EKS API returns) and raw
     * PEM. The value is passed through unchanged — fabric8's {@code CertUtils} does the same
     * base64-then-raw probe when it reads it.
     */
    private static void requireX509(String caCertData, String fieldDescription) {
        byte[][] candidates = decodeCandidates(caCertData);
        CertificateException lastFailure = null;

        for (byte[] candidate : candidates) {
            if (candidate == null || candidate.length == 0) {
                continue;
            }
            try {
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                Collection<?> certificates = factory.generateCertificates(new ByteArrayInputStream(candidate));
                if (!certificates.isEmpty()) {
                    return;
                }
            } catch (CertificateException e) {
                lastFailure = e;
            }
        }

        throw new TlsTrustException(
                "Could not read an X.509 certificate from " + fieldDescription
                        + ". Expected base64-encoded PEM or raw PEM beginning with '-----BEGIN CERTIFICATE-----'.",
                lastFailure);
    }

    /** The byte sequences {@code caCertData} might represent: base64-decoded first, then literal. */
    private static byte[][] decodeCandidates(String caCertData) {
        String trimmed = caCertData.trim();
        byte[] strictBase64 = tryDecode(trimmed, false);
        byte[] mimeBase64 = tryDecode(trimmed, true);
        return new byte[][] { strictBase64, mimeBase64, trimmed.getBytes(StandardCharsets.UTF_8) };
    }

    private static byte[] tryDecode(String value, boolean mime) {
        try {
            return mime ? Base64.getMimeDecoder().decode(value) : Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }
}
