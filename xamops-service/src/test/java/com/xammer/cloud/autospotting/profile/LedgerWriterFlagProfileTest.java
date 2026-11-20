package com.xammer.cloud.autospotting.profile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 rollout gate (plan-phase-17.md 17.5, shadow measurement): {@code autospotting.ledger.aws.writer-enabled}
 * is {@code true} in prod only, and an explicit {@code false} in the base file and in local, uat and
 * preprod. Nothing else in the ledger is lifted: the GCP writer, billing and the legacy
 * {@code savings-ledger} gates stay false in every profile. Reads the shipped {@code application*.properties}
 * under src/main/resources, so an edit that lifts a second flag, or drops the explicit false, fails here.
 */
class LedgerWriterFlagProfileTest {

    private static final String AWS_WRITER = "autospotting.ledger.aws.writer-enabled";
    private static final String GCP_WRITER = "autospotting.ledger.gcp.writer-enabled";
    private static final List<String> ALL_PROFILES = List.of("base", "local", "uat", "preprod", "prod");
    private static final List<String> OTHER_FLAGS = List.of(
            "autospotting.ledger.billing-enabled",
            "autospotting.aws.savings-ledger.enabled",
            "autospotting.gcp.savings-ledger.enabled",
            "autospotting.aws.conversions-enabled",
            "autospotting.gcp.conversions-enabled",
            "autospotting.aws.v2-mutations-enabled",
            "autospotting.gcp.v2-mutations-enabled");

    private static Properties load(String profile) throws Exception {
        String file = "base".equals(profile) ? "application.properties" : "application-" + profile + ".properties";
        // The shipped files, read from src/main/resources: the test classpath has its own application.properties.
        java.nio.file.Path path = java.nio.file.Path.of("src/main/resources", file);
        assertThat(path).as(file + " exists").exists();
        Properties p = new Properties();
        try (java.io.Reader in = java.nio.file.Files.newBufferedReader(path)) {
            p.load(in);
        }
        return p;
    }

    @Test
    void awsWriterIsTrueInProdOnly_andExplicitlyFalseEverywhereElse() throws Exception {
        assertThat(load("prod").getProperty(AWS_WRITER)).as("prod lifts the AWS writer").isEqualTo("true");
        for (String profile : List.of("base", "local", "uat", "preprod")) {
            assertThat(load(profile).getProperty(AWS_WRITER))
                    .as("profile %s declares the AWS writer explicitly false", profile)
                    .isEqualTo("false");
        }
    }

    @Test
    void gcpWriterIsTrueInProdOnly_andExplicitlyFalseEverywhereElse() throws Exception {
        assertThat(load("prod").getProperty(GCP_WRITER)).as("prod lifts the GCP writer").isEqualTo("true");
        for (String profile : List.of("base", "local", "uat", "preprod")) {
            assertThat(load(profile).getProperty(GCP_WRITER))
                    .as("profile %s declares the GCP writer explicitly false", profile).isEqualTo("false");
        }
    }

    @Test
    void noOtherLedgerOrConversionFlagIsLifted_inAnyProfile() throws Exception {
        for (String profile : ALL_PROFILES) {
            Properties p = load(profile);
            for (String flag : OTHER_FLAGS) {
                assertThat(p.getProperty(flag)).as("%s in profile %s", flag, profile).isIn(null, "false");
            }
        }
    }
}
