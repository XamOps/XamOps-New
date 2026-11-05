package com.xammer.cloud.autospotting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the CORS policy the AutoSpotting CSRF/origin filter relies on (plan-phase-09.md task 9.1,
 * design-core.md §11). The custom header is only as strong as the origin list that keeps other
 * origins from sending it with credentials, so this test fails if:
 *
 * <ul>
 *   <li>the global CORS source switches from {@code setAllowedOrigins} to
 *       {@code setAllowedOriginPatterns} (pattern matching would let the inert
 *       {@code https://*.ngrok*} entries match any tunnel page); or</li>
 *   <li>any profile's {@code autospotting.security.allowed-origins} contains a wildcard or a tunnel
 *       domain (the filter's own list must stay explicit).</li>
 * </ul>
 */
class CorsPolicyGuardTest {

    private static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("xamops-service"))
                    && Files.isRegularFile(dir.resolve("xamops-service/pom.xml"))) {
                return dir;
            }
        }
        throw new IllegalStateException("repository root not found above " + Path.of("").toAbsolutePath());
    }

    @Test
    void globalCorsSource_usesExplicitOrigins_notPatterns() throws IOException {
        Path securityConfig = repoRoot().resolve(
                "xamops-service/src/main/java/com/xammer/cloud/config/SecurityConfig.java");
        String source = Files.readString(securityConfig);
        assertThat(source)
                .as("the global CORS source must use setAllowedOrigins, not setAllowedOriginPatterns "
                        + "(design-core.md §11): pattern matching would make the inert ngrok entries live")
                .doesNotContain("setAllowedOriginPatterns");
    }

    @ParameterizedTest
    @ValueSource(strings = { "local", "uat", "preprod", "prod" })
    void profileAllowedOrigins_haveNoWildcardOrTunnel(String profile) throws IOException {
        Path properties = repoRoot().resolve(
                "xamops-service/src/main/resources/application-" + profile + ".properties");
        List<String> origins = readAllowedOrigins(Files.readString(properties));
        assertThat(origins)
                .as("application-%s.properties must declare autospotting.security.allowed-origins", profile)
                .isNotEmpty();
        for (String origin : origins) {
            String lower = origin.toLowerCase(java.util.Locale.ROOT);
            assertThat(lower)
                    .as("allowed origin %s in %s must be an explicit host, no wildcard or tunnel", origin, profile)
                    .doesNotContain("*")
                    .doesNotContain("ngrok");
        }
    }

    /** Reads the comma-separated {@code autospotting.security.allowed-origins} value, or empty. */
    private static List<String> readAllowedOrigins(String properties) {
        for (String line : properties.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.startsWith("autospotting.security.allowed-origins")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String value = trimmed.substring(eq + 1).trim();
            if (value.isEmpty()) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return List.of();
    }
}
