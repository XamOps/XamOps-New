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

    private static final String UAT_EVENTS_QUEUE =
            "https://sqs.ap-south-1.amazonaws.com/605134457560/xamops-autospotting-events-uat.fifo";
    private static final String PROD_EVENTS_QUEUE =
            "https://sqs.ap-south-1.amazonaws.com/605134457560/xamops-autospotting-events.fifo";

    /** plan-phase-22.md 22.2: the events queue URL is set only in uat and prod; the UAT URL carries the -uat suffix. */
    @Test
    void theEventsQueueUrlIsSetOnlyInUatAndProd() throws Exception {
        String key = "autospotting.aws.events.queue-url";
        assertThat(load("application.properties").getProperty(key)).as("base has no queue url").isNull();
        assertThat(load("application-local.properties").getProperty(key)).as("local has no queue url").isNull();
        assertThat(load("application-preprod.properties").getProperty(key)).as("preprod has no queue url").isNull();
        assertThat(load("application-uat.properties").getProperty(key)).isEqualTo(UAT_EVENTS_QUEUE).endsWith("-uat.fifo");
        assertThat(load("application-prod.properties").getProperty(key)).isEqualTo(PROD_EVENTS_QUEUE);
    }

    /** Record-only lift: events on in uat and prod only, still record-only, and no mutation flag lifted anywhere. */
    @Test
    void theEventsFlagIsLiftedRecordOnlyAndNoMutationFlagIsLifted() throws Exception {
        assertThat(load("application.properties").getProperty("autospotting.aws.events.enabled")).isNull();
        for (String profile : List.of("local", "preprod")) {
            assertThat(load("application-" + profile + ".properties").getProperty("autospotting.aws.events.enabled"))
                    .isIn(null, "false");
        }
        for (String profile : List.of("uat", "prod")) {
            Properties p = load("application-" + profile + ".properties");
            assertThat(p.getProperty("autospotting.aws.events.enabled")).isEqualTo("true");
            assertThat(p.getProperty("autospotting.aws.events.record-only")).isEqualTo("true");
        }
        // The base value (true) is not checked here: src/test/resources/application.properties shadows the main one
        // on the test classpath. AwsEventProperties defaults record-only to true in code.
        for (String profile : List.of("", "-local", "-uat", "-preprod", "-prod")) {
            Properties p = load("application" + profile + ".properties");
            assertThat(p.getProperty("autospotting.aws.v2-mutations-enabled")).as(profile).isIn(null, "false");
            assertThat(p.getProperty("autospotting.aws.conversions-enabled")).as(profile).isIn(null, "false");
        }
    }
}
