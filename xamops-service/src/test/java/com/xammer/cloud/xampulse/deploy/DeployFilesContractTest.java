package com.xammer.cloud.xampulse.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Reads the local dev stack under {@code deploy/xampulse} as text and pins its safety properties. */
class DeployFilesContractTest {
    private static final Path DIR = Path.of("../deploy/xampulse");

    private static String read(String rel) throws IOException {
        return Files.readString(DIR.resolve(rel), StandardCharsets.UTF_8);
    }

    @Test
    void composePublishesEveryPortOnLoopbackOnly() throws IOException {
        String compose = read("docker-compose.yml");
        Matcher m = Pattern.compile("(?m)^\\s*-\\s*\"([^\"]*:\\d+:\\d+)\"\\s*$").matcher(compose);
        int found = 0;
        while (m.find()) {
            found++;
            assertTrue(m.group(1).startsWith("127.0.0.1:"), "port not loopback-bound: " + m.group(1));
        }
        assertEquals(3, found, "8123, 9000 and 4318");
        assertFalse(compose.contains("0.0.0.0:"));
    }

    @Test
    void imagesArePinnedAndOpenSource() throws IOException {
        String compose = read("docker-compose.yml");
        assertTrue(compose.contains("image: clickhouse/clickhouse-server:25."));
        assertTrue(compose.contains("image: otel/opentelemetry-collector-contrib:0."));
        assertFalse(compose.contains(":latest"));
    }

    @Test
    void noRealCredentialsCommitted() throws IOException {
        String compose = read("docker-compose.yml");
        String users = read("clickhouse/users.d/xampulse.xml");
        assertTrue(compose.contains("${XP_CH_WRITER_PASSWORD:?"), "compose must require the env var, not default it");
        assertTrue(users.contains("from_env=\"XP_CH_WRITER_PASSWORD\""));
        assertFalse(Pattern.compile("<password>[^<]+</password>").matcher(users).find());
        String env = read(".env.example");
        assertTrue(env.contains("dev-only"), "example passwords must be obviously dev-only");
        assertTrue(read(".gitignore").contains(".env"));
    }

    @Test
    void clickhouseAcceptsTheTenantSettingPrefix() throws IOException {
        assertTrue(read("clickhouse/config.d/xampulse.xml").contains("<custom_settings_prefixes>SQL_</custom_settings_prefixes>"));
    }

    @Test
    void gatewayStripsClientTrustedAttributesBeforeStampingThemFromHeaders() throws IOException {
        String gw = read("otel-gateway.yaml");
        assertTrue(gw.contains("include_metadata: true"));
        for (String key : new String[]{"xp.tenant_id", "xp.instance_id", "xp.retain_days", "xp.demo"}) {
            assertTrue(gw.contains("delete_key(attributes, \"" + key + "\")"), "not stripped: " + key);
            assertTrue(gw.contains("key: " + key), "not stamped: " + key);
        }
        assertTrue(gw.contains("from_context: x-xp-tenant"));
        assertTrue(gw.contains("create_schema: false"));
        int strip = gw.indexOf("transform/strip_client_xp, resource/stamp_trusted_xp");
        assertTrue(strip > 0, "strip must run before stamp in the pipelines");
        assertFalse(gw.matches("(?s).*password:\\s*[^$\\s].*"), "gateway password must come from the environment");
    }

    @Test
    void edgeProxyClearsTheDemoHeaderOnIngest() throws IOException {
        String nginx = Files.readString(Path.of("../api-gateway/nginx-prod.conf"), StandardCharsets.UTF_8);
        assertTrue(nginx.contains("proxy_set_header X-Xp-Demo \"\";"));
    }

    @Test
    void edgeProxyTurnsAnOverQuotaAuthAnswerIntoA429WithRetryAfter() throws IOException {
        String nginx = Files.readString(Path.of("../api-gateway/nginx-prod.conf"), StandardCharsets.UTF_8);
        assertTrue(nginx.contains("proxy_set_header X-Xp-Auth-Mode nginx;"));
        assertTrue(nginx.contains("error_page 403 = @xp_quota;"));
        int quota = nginx.indexOf("location @xp_quota");
        assertTrue(quota > 0);
        String block = nginx.substring(quota, nginx.indexOf('}', quota));
        assertTrue(block.contains("add_header Retry-After 300 always;"));
        assertTrue(block.contains("return 429;"));
        int auth = nginx.indexOf("location = /_xp_auth");
        String authBlock = nginx.substring(auth, nginx.indexOf('}', auth));
        assertTrue(authBlock.contains("include /etc/nginx/xampulse-secret.conf;"), "the secret include must stay in /_xp_auth");
    }
    @Test
    void demoSeederIsOffByDefaultAndLoopbackOnly() throws IOException {
        String seeder = read("demo/xp_demo_seed.py");
        assertTrue(seeder.contains("XAMPULSE_DEMO_ENABLED"));
        assertTrue(seeder.contains("is_loopback"));
        assertTrue(seeder.contains("demo seeding is OFF"));
        assertTrue(seeder.contains("X-Xp-Demo"));
        assertFalse(read("docker-compose.yml").toLowerCase().contains("demo"), "the stack must never seed demo data by itself");
    }
}
