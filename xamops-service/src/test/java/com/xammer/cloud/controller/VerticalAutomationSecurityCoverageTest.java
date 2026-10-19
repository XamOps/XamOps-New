package com.xammer.cloud.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the engine's path is authenticated by {@code SecurityConfig} as it already stands —
 * <strong>without editing it</strong>, which this work is not allowed to do.
 *
 * <p>The assertion is structural rather than a live request: it reads {@code SecurityConfig.java},
 * extracts every declared {@code requestMatchers(...)} group together with the authorization call
 * that terminates it, and checks that <strong>no {@code permitAll} rule matches
 * {@code /api/eks/automation/vertical/**}</strong>. Since the chain ends with
 * {@code anyRequest().authenticated()} and installs
 * {@code defaultAuthenticationEntryPointFor(UNAUTHORIZED, /api/**)}, an unauthenticated call to the
 * engine therefore returns <strong>401</strong> rather than a redirect.
 *
 * <p>Checking it this way is deliberate. A {@code @WebMvcTest} would have to import a security
 * configuration, which means asserting against a copy of the rules rather than the real ones; this
 * reads the file that actually runs in production, so it fails if somebody later adds a
 * {@code permitAll} rule that covers the engine — the only change that could silently publish it.
 */
class VerticalAutomationSecurityCoverageTest {

    private static final Path SECURITY_CONFIG =
            Path.of("src/main/java/com/xammer/cloud/config/SecurityConfig.java");

    /** Paths this feature serves, and one the demo router would have intercepted. */
    private static final List<String> ENGINE_PATHS = List.of(
            "/api/eks/automation/vertical/xamops-test-cluster",
            "/api/eks/automation/vertical/xamops-test-cluster/actions",
            "/api/eks/automation/vertical/c/workloads/team-a/Deployment/api/policy",
            "/api/eks/automation/vertical/c/workloads/team-a/Deployment/api/apply",
            // The read-only usage and HPA history GETs (FEAT-004), on the same catch-all.
            "/api/eks/automation/usage/xamops-test-cluster",
            "/api/eks/automation/usage/xamops-test-cluster/sparklines",
            "/api/eks/automation/hpa/xamops-test-cluster/team-b/php-apache/history",
            // The activity feed and its per-day summary (FEAT-005), read-only, on the same catch-all.
            "/api/eks/automation/activity/xamops-test-cluster",
            "/api/eks/automation/activity/xamops-test-cluster/summary");

    private static final Pattern MATCHER_GROUP = Pattern.compile(
            "\\.requestMatchers\\((.*?)\\)\\s*\\.\\s*(permitAll|authenticated|hasAuthority|hasRole|hasAnyAuthority|denyAll)",
            Pattern.DOTALL);
    private static final Pattern PATH_LITERAL = Pattern.compile("PATH\\.matcher\\(\\s*(?:HttpMethod\\.\\w+\\s*,)?\\s*\"([^\"]+)\"");

    private final AntPathMatcher matcher = new AntPathMatcher();

    @Test
    @DisplayName("no permitAll rule covers the engine's paths, so anyRequest().authenticated() applies")
    void theEnginePathIsNotPubliclyReachable() {
        List<Rule> rules = rules();
        assertThat(rules).as("SecurityConfig's authorization rules must be parseable").isNotEmpty();

        for (String path : ENGINE_PATHS) {
            List<String> permitting = rules.stream()
                    .filter(rule -> "permitAll".equals(rule.authorization()))
                    .flatMap(rule -> rule.patterns().stream())
                    .filter(pattern -> matcher.match(pattern, path))
                    .toList();

            assertThat(permitting)
                    .as("%s must not be reachable without authentication", path)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the chain still ends with anyRequest().authenticated() and a 401 entry point")
    void theCatchAllAndEntryPointAreStillInPlace() {
        String source = read();

        assertThat(source)
                .as("the engine's path relies on the catch-all rather than a rule of its own")
                .contains("anyRequest().authenticated()");
        assertThat(source.replaceAll("\\s+", ""))
                .as("an unauthenticated /api/** call must return 401, not a redirect to a login page")
                .contains("defaultAuthenticationEntryPointFor(newHttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),PATH.matcher(\"/api/**\")");
    }

    @Test
    @DisplayName("the apply path is covered by the catch-all, and SecurityConfig was not edited for it")
    void theApplyPathIsCoveredWithoutEditingSecurityConfig() {
        // Endpoint 3 is the one path here that can change a cluster, so it gets its own assertion
        // rather than relying on being in the list above: no rule of any kind may name it, which is
        // what leaves it on anyRequest().authenticated() with a 401 entry point.
        String applyPath = "/api/eks/automation/vertical/c/workloads/team-a/Deployment/api/apply";
        List<String> naming = rules().stream()
                .flatMap(rule -> rule.patterns().stream())
                .filter(pattern -> matcher.match(pattern, applyPath))
                .toList();

        assertThat(naming)
                .as("no requestMatchers rule may cover the apply endpoint, permitting or otherwise")
                .isEmpty();
        assertThat(read())
                .as("the apply endpoint must not have been given a rule of its own")
                .doesNotContain("automation/vertical");
    }

    @Test
    @DisplayName("the engine does not sit under a prefix another owner already governs")
    void theEngineDoesNotCollideWithAnotherOwnersPrefix() {
        for (String path : ENGINE_PATHS) {
            // /api/eks/autoscaling/** is the Go agent's policy surface, owned by another controller
            // where the UI branch registers its own guard interceptor.
            assertThat(matcher.match("/api/eks/autoscaling/**", path)).isFalse();
            // /api/xamops/k8s/** is intercepted by the frontend's demo router catch-all.
            assertThat(matcher.match("/api/xamops/k8s/**", path)).isFalse();
            assertThat(matcher.match("/api/admin/**", path)).isFalse();
        }
    }

    /** One declared rule: the patterns it matches and the authorization that terminates it. */
    private record Rule(List<String> patterns, String authorization) {
    }

    private List<Rule> rules() {
        String source = stripComments(read());
        List<Rule> rules = new ArrayList<>();
        Matcher groups = MATCHER_GROUP.matcher(source);
        while (groups.find()) {
            List<String> patterns = new ArrayList<>();
            Matcher literals = PATH_LITERAL.matcher(groups.group(1));
            while (literals.find()) {
                patterns.add(literals.group(1));
            }
            if (!patterns.isEmpty()) {
                rules.add(new Rule(patterns, groups.group(2)));
            }
        }
        return rules;
    }

    /** Comments can contain example paths and removed rules; neither is a live authorization rule. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static String read() {
        assertThat(SECURITY_CONFIG).as("SecurityConfig.java must be readable from the module root").exists();
        try {
            return Files.readString(SECURITY_CONFIG, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
