package com.xammer.cloud.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the HTTP CORS allowlist (BUG 1): demo.xamops.com must be an accepted origin so the
 * email/OTP login POST from demo.xamops.com is not rejected as an "Invalid CORS request", while
 * every pre-existing origin (notably live.xamops.com) is preserved unchanged.
 *
 * <p>{@code corsConfigurationSource()} reads no instance field, so the config is instantiated with
 * null constructor args purely to reach that bean method.
 */
class SecurityCorsConfigTest {

    private CorsConfiguration resolveConfig() {
        SecurityConfig config = new SecurityConfig(null, null, null, null, null);
        CorsConfigurationSource source = config.corsConfigurationSource();
        // The bean registers a single CORS configuration under the "/**" pattern.
        return ((UrlBasedCorsConfigurationSource) source).getCorsConfigurations().get("/**");
    }

    @Test
    void allowedOrigins_includeDemoAndLive() {
        CorsConfiguration configuration = resolveConfig();
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins())
                .contains("https://demo.xamops.com")   // BUG 1 fix
                .contains("https://live.xamops.com");   // unchanged
    }

    @Test
    void allowedOrigins_preserveExistingEntries() {
        CorsConfiguration configuration = resolveConfig();
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactlyInAnyOrder(
                "http://localhost:5173",
                "https://live.xamops.com",
                "https://demo.xamops.com",
                "https://prep.xamops.com",
                "https://uat.xamops.com",
                "https://*.ngrok-free.dev",
                "https://*.ngrok.io");
    }
}
