package com.xammer.cloud.autospotting.profile;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-SPN-11 / XC-11 (plan-phase-05.md task 5.2): no profile except prod sets
 * {@code autospotting.enabled=true}, and no non-prod profile names the prod SQS queue URL. Reads the
 * real {@code application*.properties} on the classpath, so a future edit that re-adds the flag or
 * the queue to a non-prod profile fails this test.
 */
class ProfileDefaultsTest {

    private static final String PROD_QUEUE =
            "https://sqs.ap-south-1.amazonaws.com/605134457560/autospotting-events.fifo";

    private static final List<String> NON_PROD_PROFILES = List.of("local", "uat", "preprod");

    private Properties load(String resource) throws Exception {
        ClassPathResource r = new ClassPathResource(resource);
        return r.exists() ? PropertiesLoaderUtils.loadProperties(r) : new Properties();
    }

    @Test
    void onlyProdEnablesTheGlobalFlag() throws Exception {
        // Base must not set it (it moved to prod).
        assertThat(load("application.properties").getProperty("autospotting.enabled"))
                .as("base profile must not set autospotting.enabled").isNull();

        for (String profile : NON_PROD_PROFILES) {
            String value = load("application-" + profile + ".properties").getProperty("autospotting.enabled");
            assertThat(value)
                    .as("profile %s must not set autospotting.enabled=true", profile)
                    .isIn(null, "false");
        }

        // Prod sets it true.
        assertThat(load("application-prod.properties").getProperty("autospotting.enabled"))
                .as("prod enables the global flag").isEqualTo("true");
    }

    @Test
    void noNonProdProfileNamesTheProdQueue() throws Exception {
        assertThat(load("application.properties").getProperty("autospotting.sqs.queue-url"))
                .as("base profile must not name the prod queue").isNull();

        for (String profile : NON_PROD_PROFILES) {
            String value = load("application-" + profile + ".properties").getProperty("autospotting.sqs.queue-url");
            assertThat(value)
                    .as("profile %s must not name the prod queue", profile)
                    .isNull();
        }

        assertThat(load("application-prod.properties").getProperty("autospotting.sqs.queue-url"))
                .as("prod names the queue").isEqualTo(PROD_QUEUE);
    }
}
