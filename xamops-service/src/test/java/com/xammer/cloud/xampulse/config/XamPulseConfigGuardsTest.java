package com.xammer.cloud.xampulse.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Defaults, the demo guard, and the nginx contract for the ingest edge. */
class XamPulseConfigGuardsTest {

    @Test
    void everythingDefaultsToOffAndEmpty() {
        XamPulseProperties p = new XamPulseProperties();
        assertFalse(p.isEnabled());
        assertFalse(p.getDemo().isEnabled());
        assertEquals(List.of("local", "dev"), p.getDemo().getAllowedProfiles());
        assertEquals("", p.getInternalSecret());
        assertEquals("", p.getJwt().getPrivateKeyPem());
        assertEquals("", p.getClickhouse().getReaderPassword());
        assertEquals(30, p.getLimits().getMaxRangeDays());
        assertEquals(1000, p.getLimits().getMaxRows());
        assertEquals(15, p.getLimits().getQueryTimeoutSec());
        assertEquals(3, p.getLimits().getConcurrentQueriesPerTenant());
    }

    @Test
    void applicationPropertiesKeepXamPulseOffAndTakeSecretsOnlyFromTheEnvironment() throws Exception {
        Properties props = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            props.load(in);
        }
        assertEquals("${XAMPULSE_ENABLED:false}", props.getProperty("xampulse.enabled"));
        assertEquals("${XAMPULSE_DEMO_ENABLED:false}", props.getProperty("xampulse.demo.enabled"));
        for (String k : props.stringPropertyNames()) {
            if (k.startsWith("xampulse.") && (k.contains("password") || k.contains("secret") || k.contains("pem"))) {
                assertTrue(props.getProperty(k).matches("\\$\\{XAMPULSE_[A-Z_]+:}"), k + " must come from the environment with an empty default");
            }
        }
    }

    @Test
    void demoFlagIsRefusedUnderProductionLikeProfiles() {
        for (String prof : List.of("prod", "production", "uat", "preprod", "sit", "PROD")) {
            assertNotNull(XamPulseDemoGuard.offendingProfile(true, List.of("x", prof)), prof);
        }
        assertNull(XamPulseDemoGuard.offendingProfile(true, List.of("local")));
        assertNull(XamPulseDemoGuard.offendingProfile(true, List.of("dev")));
        assertNull(XamPulseDemoGuard.offendingProfile(false, List.of("prod")));
        assertNull(XamPulseDemoGuard.offendingProfile(true, List.of()));
    }

    private static boolean demoStartupAllowed(boolean enabled, List<String> active) {
        List<String> allowed = new XamPulseProperties().getDemo().getAllowedProfiles();
        return XamPulseDemoGuard.offendingProfile(enabled, active) == null && XamPulseDemoGuard.allowed(enabled, active, allowed);
    }

    @Test
    void demoFlagNeedsAnAllowedProfile() {
        assertTrue(demoStartupAllowed(true, List.of("local")));
        assertTrue(demoStartupAllowed(true, List.of("dev")));
        assertTrue(demoStartupAllowed(true, List.of("aws", "DEV")));
        for (String prof : List.of("prod", "uat", "preprod", "sit", "staging")) {
            assertFalse(demoStartupAllowed(true, List.of(prof)), prof);
        }
        assertFalse(demoStartupAllowed(true, List.of()), "no profile at all is not an allowed profile");
        assertFalse(demoStartupAllowed(true, List.of("local", "prod")), "a production-like profile still wins");
        // demo off: nothing is checked
        for (String prof : List.of("prod", "staging", "local")) {
            assertTrue(demoStartupAllowed(false, List.of(prof)), prof);
        }
        assertTrue(demoStartupAllowed(false, List.of()));
    }

    @Test
    void passTwoDefaultsStayOffOrSafe() throws Exception {
        XamPulseProperties p = new XamPulseProperties();
        assertFalse(p.getArchive().isEnabled(), "archive stays OFF by default");
        assertTrue(p.getQuota().isEnabled());
        assertEquals(60, p.getQuota().getCacheSeconds());
        assertEquals(300, p.getQuota().getMaxRetryAfterSeconds());
        assertFalse(p.getDemo().isEnabled());
        assertEquals(2000, p.getArchive().getMaxSearchFiles());
        assertEquals(1000, p.getArchive().getMaxSearchRows());
        assertEquals(120, p.getArchive().getSearchTimeoutSec());
        Properties props = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            props.load(in);
        }
        assertEquals("${XAMPULSE_ARCHIVE_ENABLED:false}", props.getProperty("xampulse.archive.enabled"));
        assertEquals("${XAMPULSE_QUOTA_ENABLED:true}", props.getProperty("xampulse.quota.enabled"));
        assertEquals("${XAMPULSE_QUOTA_MAX_RETRY_AFTER_SECONDS:300}", props.getProperty("xampulse.quota.max-retry-after-seconds"));
    }

    // ---- nginx edge contract (api-gateway/nginx-prod.conf) ----

    private static String block(String conf, String header) {
        int i = conf.indexOf(header);
        assertTrue(i >= 0, header + " missing");
        int open = conf.indexOf('{', i);
        int depth = 0;
        for (int k = open; k < conf.length(); k++) {
            if (conf.charAt(k) == '{') {
                depth++;
            } else if (conf.charAt(k) == '}' && --depth == 0) {
                return conf.substring(open, k + 1);
            }
        }
        throw new AssertionError("unterminated block");
    }

    @Test
    void nginxAuthLocationIsInternalAndTheSecretIsNotInTheRepo() throws Exception {
        String conf = Files.readString(Path.of("../api-gateway/nginx-prod.conf"));
        String auth = block(conf, "location = /_xp_auth");
        assertTrue(auth.contains("internal;"), "the auth location must be internal");
        assertTrue(auth.contains("proxy_pass_request_body off;"));
        assertTrue(auth.contains("include /etc/nginx/xampulse-secret.conf;"), "secret comes from an include file kept out of the repo");
        assertTrue(auth.contains("proxy_set_header Authorization \"\";"), "the app's impersonation-JWT chain must not see the agent token");
        assertTrue(auth.contains("X-Xp-Token $http_authorization"));
        String code = conf.lines().filter(l -> !l.trim().startsWith("#")).collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(java.util.regex.Pattern.compile("X-Xp-Internal\\s+\"[^\"]+\"").matcher(code).find(),
                "no literal shared secret in the config");
    }

    @Test
    void nginxIngestLocationOverwritesClientSuppliedTrustHeaders() throws Exception {
        String conf = Files.readString(Path.of("../api-gateway/nginx-prod.conf"));
        String ingest = block(conf, "location /xampulse-ingest/");
        assertTrue(ingest.contains("auth_request /_xp_auth;"));
        assertTrue(ingest.contains("proxy_set_header X-Xp-Tenant $xp_tenant;"));
        assertTrue(ingest.contains("proxy_set_header X-Xp-Instance $xp_instance;"));
        assertTrue(ingest.contains("proxy_set_header X-Xp-Retain-Days $xp_retain;"));
        assertTrue(ingest.contains("proxy_set_header X-Xp-Internal \"\";"), "client-sent internal header must be blanked");
        assertTrue(ingest.contains("proxy_set_header Authorization \"\";"));
        assertTrue(ingest.contains("client_max_body_size 8m;"));
        assertTrue(ingest.indexOf("auth_request /_xp_auth;") < ingest.indexOf("proxy_pass"));
        String ws = block(conf, "location /xampulse-ws/");
        assertTrue(ws.contains("Upgrade $http_upgrade") && ws.contains("proxy_read_timeout 2h;"));
        // The public edge has no route to the internal auth endpoint.
        assertFalse(conf.contains("location /internal/"));
    }
}
