package com.xammer.cloud.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Audit finding B7: {@link K8sClientFactory} used to call {@code trustCerts(true)} on both factory
 * methods, so every customer API-server connection accepted any certificate from any host.
 *
 * <p>These tests prove the property that matters rather than the absence of one line: a client built
 * with the <em>correct</em> CA completes the TLS handshake, a client built with a <em>wrong</em> CA
 * does not, and a configuration with <em>no</em> verifiable CA fails closed instead of connecting.
 *
 * <p>Everything runs against a JDK {@link HttpsServer} on {@code 127.0.0.1} with a keypair generated
 * at test time. There is no network egress and no cluster contact. The class skips (JUnit
 * assumption) when {@code keytool} is unavailable, mirroring the opt-in style of
 * {@code PgTestSupport}.
 */
class K8sClientFactoryTlsTest {

    /** Any mention of TLS trust failing, however the HTTP client happens to wrap it. */
    private static final Pattern TLS_FAILURE = Pattern.compile("(?i)certificat|handshake|PKIX|SSL|trust");

    private static final String STORE_PASSWORD = "changeit";
    private static final String BODY = "{\"apiVersion\":\"v1\",\"kind\":\"NamespaceList\",\"items\":[]}";

    @TempDir
    static Path workDir;

    private static HttpsServer server;
    private static String serverUrl;
    /** PEM of the certificate the server actually presents. */
    private static String correctCaPem;
    /** PEM of an unrelated certificate — a CA that must not validate this server. */
    private static String wrongCaPem;
    private static final AtomicInteger handshakesCompleted = new AtomicInteger();

    private final K8sClientFactory factory = new K8sClientFactory();

    @BeforeAll
    static void startServer() throws Exception {
        Path serverStore = workDir.resolve("server.p12");
        Path otherStore = workDir.resolve("other.p12");
        try {
            generateKeypair(serverStore, "server", "CN=localhost");
            generateKeypair(otherStore, "other", "CN=not-this-server");
            correctCaPem = exportCertificate(serverStore, "server");
            wrongCaPem = exportCertificate(otherStore, "other");
        } catch (Exception keytoolUnavailable) {
            Assumptions.abort("keytool could not generate a test keypair ("
                    + keytoolUnavailable.getMessage() + "); TLS trust test skipped");
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(serverStore)) {
            keyStore.load(in, STORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, STORE_PASSWORD.toCharArray());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        // Reaching a handler at all proves the handshake completed: the JDK server does not
        // dispatch a request until TLS is established.
        server.createContext("/", K8sClientFactoryTlsTest::respond);
        server.start();
        serverUrl = "https://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ createFromComponents

    @Test
    void correctCaCompletesTheHandshake() {
        int before = handshakesCompleted.get();

        try (KubernetesClient client = factory.createFromComponents(serverUrl, base64(correctCaPem), "token")) {
            callApi(client);
        } catch (Exception e) {
            // The stub server is not a real API server, so a parse or protocol error is acceptable.
            // A TLS error is not: that would mean the real CA was rejected.
            assertThat(tlsFailure(e))
                    .as("TLS must succeed against the cluster's real CA, but failed: %s", describe(e))
                    .isFalse();
        }

        assertThat(handshakesCompleted.get())
                .as("the server must have received the request, i.e. TLS verification passed")
                .isGreaterThan(before);
    }

    @Test
    void rawPemCaIsAcceptedAsWellAsBase64() {
        int before = handshakesCompleted.get();

        try (KubernetesClient client = factory.createFromComponents(serverUrl, correctCaPem, "token")) {
            callApi(client);
        } catch (Exception ignored) {
            // Only the handshake is under test here.
        }

        assertThat(handshakesCompleted.get()).isGreaterThan(before);
    }

    @Test
    void wrongCaFailsTheHandshake() {
        int before = handshakesCompleted.get();

        Exception failure = assertThrows(Exception.class, () -> {
            try (KubernetesClient client = factory.createFromComponents(serverUrl, base64(wrongCaPem), "token")) {
                callApi(client);
            }
        });

        assertThat(tlsFailure(failure))
                .as("a certificate signed by an unrelated CA must be rejected, got: %s", describe(failure))
                .isTrue();
        assertThat(handshakesCompleted.get())
                .as("the request must never reach the server when its certificate is untrusted")
                .isEqualTo(before);
    }

    @Test
    void blankCaFailsClosedInsteadOfTrustingEverything() {
        assertThatThrownBy(() -> factory.createFromComponents(serverUrl, null, "token"))
                .isInstanceOf(K8sClientFactory.TlsTrustException.class)
                .hasMessageContaining("No cluster CA certificate");

        assertThatThrownBy(() -> factory.createFromComponents(serverUrl, "   ", "token"))
                .isInstanceOf(K8sClientFactory.TlsTrustException.class);
    }

    @Test
    void caDataThatIsNotACertificateFailsClosed() {
        assertThatThrownBy(() -> factory.createFromComponents(serverUrl, "not-a-certificate", "token"))
                .isInstanceOf(K8sClientFactory.TlsTrustException.class)
                .hasMessageContaining("X.509");
    }

    // ------------------------------------------------------------------ createFromKubeconfig

    @Test
    void kubeconfigWithTheCorrectCaStillWorks() {
        // The blast-radius case: Kubescape, Trivy, Karpenter and OpenCost all reach a cluster
        // through this method, and a well-formed kubeconfig must keep working.
        int before = handshakesCompleted.get();

        try (KubernetesClient client = factory.createFromKubeconfig(kubeconfig(base64(correctCaPem), false))) {
            callApi(client);
        } catch (Exception ignored) {
            // Only the handshake is under test here.
        }

        assertThat(handshakesCompleted.get()).isGreaterThan(before);
    }

    @Test
    void kubeconfigWithNoCaFailsClosed() {
        assertThatThrownBy(() -> factory.createFromKubeconfig(kubeconfig(null, false)))
                .isInstanceOf(K8sClientFactory.TlsTrustException.class)
                .hasMessageContaining("certificate-authority-data");
    }

    @Test
    void kubeconfigAskingToSkipVerificationIsNotAnEscapeHatch() {
        // insecure-skip-tls-verify used to be honoured implicitly, because trustCerts(true) was set
        // unconditionally. It must not be a way back to the old behaviour.
        assertThatThrownBy(() -> factory.createFromKubeconfig(kubeconfig(null, true)))
                .isInstanceOf(K8sClientFactory.TlsTrustException.class)
                .hasMessageContaining("insecure-skip-tls-verify");
    }

    @Test
    void kubeconfigWithUnparseableCaFailsClosed() {
        assertThatThrownBy(() -> factory.createFromKubeconfig(kubeconfig("bm90LWEtY2VydGlmaWNhdGU=", false)))
                .isInstanceOf(K8sClientFactory.TlsTrustException.class)
                .hasMessageContaining("X.509");
    }

    // ------------------------------------------------------------------ CA as a file path

    @Test
    void kubeconfigWithACorrectCaFileStillWorks() throws IOException {
        // kubectl writes certificate-authority (a path) as readily as certificate-authority-data,
        // so this is the second shape Kubescape / Trivy / Karpenter / OpenCost can arrive with.
        Path caFile = Files.writeString(workDir.resolve("ca-correct.pem"), correctCaPem);
        int before = handshakesCompleted.get();

        try (KubernetesClient client = factory.createFromKubeconfig(kubeconfigWithCaFile(caFile.toString()))) {
            callApi(client);
        } catch (Exception ignored) {
            // Only the handshake is under test here.
        }

        assertThat(handshakesCompleted.get()).isGreaterThan(before);
    }

    @Test
    void kubeconfigWithAMissingCaFileFailsClosedNamingTheField() {
        // Fails closed either way — trustCerts is false — but unvalidated it failed inside the HTTP
        // client's truststore construction with a message that named nothing the operator could fix.
        assertThatThrownBy(() -> factory.createFromKubeconfig(
                kubeconfigWithCaFile(workDir.resolve("not-written.pem").toString())))
                .isInstanceOf(K8sClientFactory.TlsTrustException.class)
                .hasMessageContaining("certificate-authority")
                .hasMessageContaining("not a readable file");
    }

    @Test
    void kubeconfigWithAnEmptyCaFileFailsClosed() throws IOException {
        Path caFile = Files.writeString(workDir.resolve("ca-empty.pem"), "   \n");

        assertThatThrownBy(() -> factory.createFromKubeconfig(kubeconfigWithCaFile(caFile.toString())))
                .isInstanceOf(K8sClientFactory.TlsTrustException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void kubeconfigWithACaFileThatIsNotACertificateFailsClosed() throws IOException {
        Path caFile = Files.writeString(workDir.resolve("ca-garbage.pem"), "this is not a certificate\n");

        assertThatThrownBy(() -> factory.createFromKubeconfig(kubeconfigWithCaFile(caFile.toString())))
                .isInstanceOf(K8sClientFactory.TlsTrustException.class)
                .hasMessageContaining("X.509");
    }

    @Test
    void aCaFileIsNotACaFileIfItIsADirectory() {
        assertThatThrownBy(() -> factory.createFromKubeconfig(kubeconfigWithCaFile(workDir.toString())))
                .isInstanceOf(K8sClientFactory.TlsTrustException.class)
                .hasMessageContaining("not a readable file");
    }

    // ------------------------------------------------------------------ helpers

    private static void callApi(KubernetesClient client) {
        client.namespaces().list();
    }

    private static void respond(HttpExchange exchange) throws IOException {
        handshakesCompleted.incrementAndGet();
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String base64(String pem) {
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
    }

    /** A kubeconfig whose cluster entry carries {@code certificate-authority} (a path), not data. */
    private static String kubeconfigWithCaFile(String caFilePath) {
        return clusterEntry("    certificate-authority: " + caFilePath + "\n");
    }

    private static String kubeconfig(String caCertDataBase64, boolean insecureSkipTlsVerify) {
        StringBuilder cluster = new StringBuilder();
        if (caCertDataBase64 != null) {
            cluster.append("    certificate-authority-data: ").append(caCertDataBase64).append('\n');
        }
        if (insecureSkipTlsVerify) {
            cluster.append("    insecure-skip-tls-verify: true\n");
        }
        return clusterEntry(cluster.toString());
    }

    private static String clusterEntry(String caLines) {
        String cluster = "    server: " + serverUrl + "\n" + caLines;
        return """
                apiVersion: v1
                kind: Config
                current-context: test
                clusters:
                - name: test-cluster
                  cluster:
                %s\
                contexts:
                - name: test
                  context:
                    cluster: test-cluster
                    user: test-user
                users:
                - name: test-user
                  user:
                    token: test-token
                """.formatted(cluster);
    }

    /** Every message in the cause and suppressed graph, so the assertion is wrapper-agnostic. */
    private static String describe(Throwable t) {
        StringBuilder text = new StringBuilder();
        for (Throwable node : flatten(t)) {
            text.append(node.getClass().getName()).append(": ").append(node.getMessage()).append('\n');
        }
        return text.toString();
    }

    /** True when the failure is a TLS trust failure: an {@link SSLException}, or a message saying so. */
    private static boolean tlsFailure(Throwable t) {
        return flatten(t).stream().anyMatch(SSLException.class::isInstance)
                || TLS_FAILURE.matcher(describe(t)).find();
    }

    private static List<Throwable> flatten(Throwable root) {
        List<Throwable> all = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        List<Throwable> queue = new ArrayList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Throwable next = queue.remove(0);
            if (next == null || seen.put(next, Boolean.TRUE) != null) {
                continue;
            }
            all.add(next);
            queue.add(next.getCause());
            queue.addAll(List.of(next.getSuppressed()));
        }
        return all;
    }

    private static void generateKeypair(Path keystore, String alias, String dname) throws Exception {
        run(keytool(), "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-storetype", "PKCS12",
                "-keystore", keystore.toString(),
                "-storepass", STORE_PASSWORD,
                "-keypass", STORE_PASSWORD,
                "-dname", dname,
                "-ext", "SAN=dns:localhost,ip:127.0.0.1");
    }

    private static String exportCertificate(Path keystore, String alias) throws Exception {
        String pem = run(keytool(), "-exportcert",
                "-alias", alias,
                "-storetype", "PKCS12",
                "-keystore", keystore.toString(),
                "-storepass", STORE_PASSWORD,
                "-rfc");
        if (!pem.contains("BEGIN CERTIFICATE")) {
            throw new IllegalStateException("keytool did not export a PEM certificate for " + alias);
        }
        return pem;
    }

    private static String keytool() {
        String home = System.getProperty("java.home");
        String binary = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "keytool.exe" : "keytool";
        Path candidate = Path.of(home, "bin", binary);
        return Files.isExecutable(candidate) ? candidate.toString() : binary;
    }

    /** @return the process's stdout; throws when the command is missing or exits non-zero. */
    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).start();
        String stdout;
        try (InputStream in = process.getInputStream()) {
            stdout = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String stderr;
        try (InputStream err = process.getErrorStream()) {
            stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(command[0] + " exited " + exit + ": " + stderr.strip());
        }
        return stdout;
    }
}
