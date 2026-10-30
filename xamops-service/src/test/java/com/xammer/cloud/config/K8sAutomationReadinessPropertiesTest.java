package com.xammer.cloud.config;

import com.xammer.cloud.exception.K8sAutomationReason;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readiness binds from {@code xamops.k8s.automation.readiness.*}, defaults every key to not
 * ready, and gives every key its §6.7 reason and message.
 */
class K8sAutomationReadinessPropertiesTest {

    private static final String PREFIX = "xamops.k8s.automation.readiness";

    private static K8sAutomationReadinessProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bindOrCreate(PREFIX, K8sAutomationReadinessProperties.class);
    }

    @Test
    void absentKeysBindFalse() {
        K8sAutomationReadinessProperties p = bind(Map.of());
        assertThat(p.asMap()).hasSize(10).allSatisfy((key, ready) -> assertThat(ready).as(key.key()).isFalse());
    }

    @Test
    void verticalRightsizingTrueBinds() {
        K8sAutomationReadinessProperties p = bind(Map.of(PREFIX + ".vertical-rightsizing", "true"));
        assertThat(p.isVerticalRightsizing()).isTrue();
        assertThat(p.isReady("vertical-rightsizing")).isTrue();
        assertThat(p.isReady(K8sReadinessKey.EVICTOR_LIVE)).isFalse();
    }

    @Test
    void everyKebabKeyBindsToItsOwnFlag() {
        for (K8sReadinessKey key : K8sReadinessKey.values()) {
            K8sAutomationReadinessProperties p = bind(Map.of(PREFIX + "." + key.key(), "true"));
            p.asMap().forEach((k, ready) -> assertThat(ready).as(key.key() + " -> " + k.key()).isEqualTo(k == key));
        }
    }

    @Test
    void unknownKeyIsNeverReady() {
        assertThat(bind(Map.of()).isReady("go-rightsizer")).isFalse();
    }

    @Test
    void everyKeyHasAReasonAndMessageInPropertyOrder() {
        assertThat(K8sReadinessKey.values()).extracting(K8sReadinessKey::key).containsExactly(
                "vertical-rightsizing", "hpa-apply", "vpa-apply", "evictor-live", "rebalancer",
                "node-optimizer-actions", "scale-out", "node-templates", "spot-interruption-actions",
                "consolidation-actions");
        for (K8sReadinessKey key : K8sReadinessKey.values()) {
            assertThat(key.message()).as(key.key()).isNotBlank();
            assertThat(key.reason()).isEqualTo(key == K8sReadinessKey.EVICTOR_LIVE
                    ? K8sAutomationReason.ANALYSE_ONLY : K8sAutomationReason.CONTROLLER_NOT_READY);
        }
        assertThat(K8sReadinessKey.REBALANCER.message())
                .isEqualTo("Rebalancing stays gated pending the benefit guard and a soak window; "
                        + "the ledger already prevents repeated drains of the same nodes.");
    }

    @Test
    void applicationPropertiesShipsEveryKeyAsFalse() throws Exception {
        // The test classpath shadows application.properties, so read the shipped file by path
        // (surefire runs with the module directory as the working directory).
        Properties props = new Properties();
        try (InputStream in = java.nio.file.Files.newInputStream(
                java.nio.file.Path.of("src/main/resources/application.properties"))) {
            props.load(in);
        }
        List<String> keys = java.util.Arrays.stream(K8sReadinessKey.values()).map(K8sReadinessKey::key).toList();
        for (String key : keys) {
            assertThat(props.getProperty(PREFIX + "." + key)).as(key).isEqualTo("false");
        }
    }
    @Test
    void onlyUatMayOverrideSpotInterruptionActions() throws Exception {
        // UAT is the ONLY profile allowed to turn spot-interruption-actions on, mirroring how
        // vertical-rightsizing/hpa are uat-only (WorkloadAutoscalingPropertiesTest). The base
        // application.properties default stays false; every OTHER profile must not declare the key at
        // all, so this test still FAILS if the key appears true anywhere but uat. The runtime gate is
        // unchanged: handleSpotInterruptions still needs node-optimizer-actions + the policy + this key.
        String key = PREFIX + ".spot-interruption-actions";
        String uat = "application-uat.properties";
        java.nio.file.Path dir = java.nio.file.Path.of("src/main/resources");
        List<java.nio.file.Path> files;
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
            files = s.filter(f -> f.getFileName().toString().matches("application.*\\.properties")).toList();
        }
        assertThat(files).contains(dir.resolve("application.properties"));
        for (java.nio.file.Path f : files) {
            Properties props = new Properties();
            try (InputStream in = java.nio.file.Files.newInputStream(f)) {
                props.load(in);
            }
            String name = f.getFileName().toString();
            if (name.equals("application.properties")) {
                assertThat(props.getProperty(key)).as(f.toString()).isEqualTo("false");
            } else if (name.equals(uat)) {
                // uat may declare it; if present it must be exactly "true" (never a stray value).
                if (props.containsKey(key)) {
                    assertThat(props.getProperty(key)).as(f.toString()).isEqualTo("true");
                }
            } else {
                assertThat(props.containsKey(key)).as(f + " must not override " + key).isFalse();
            }
        }
    }

    @Test
    void vpaApplyIsAbsentOrFalseInEveryProfileIncludingUat() throws Exception {
        // Design-review NIT 3: the VPA apply path ships inert this task — vpa-apply is false even in
        // UAT (unlike hpa-apply/vertical-rightsizing/spot-interruption-actions, which UAT turns on).
        // This is STRICTER than onlyUatMayOverrideSpotInterruptionActions: NO profile, UAT included,
        // may declare vpa-apply=true. The test fails if any profile sets it true.
        String key = PREFIX + ".vpa-apply";
        java.nio.file.Path dir = java.nio.file.Path.of("src/main/resources");
        List<java.nio.file.Path> files;
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
            files = s.filter(f -> f.getFileName().toString().matches("application.*\\.properties")).toList();
        }
        assertThat(files).contains(dir.resolve("application.properties"), dir.resolve("application-uat.properties"));
        for (java.nio.file.Path f : files) {
            Properties props = new Properties();
            try (InputStream in = java.nio.file.Files.newInputStream(f)) {
                props.load(in);
            }
            if (props.containsKey(key)) {
                assertThat(props.getProperty(key)).as(f + " must keep vpa-apply false").isEqualTo("false");
            }
        }
    }
}
