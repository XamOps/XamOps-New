package com.xammer.cloud.service.autospotting.aws.events;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** The environment guard (FR-SPN-10, AC-SPN-11, AC-AWS-07): a non-prod profile never consumes a production queue. */
class AwsEventPropertiesTest {

    private static final String BASE = "https://sqs.ap-south-1.amazonaws.com/605134457560/";
    private static final String PROD = BASE + "xamops-autospotting-events.fifo";
    private static final String UAT = BASE + "xamops-autospotting-events-uat.fifo";
    private static final String LEGACY = BASE + "autospotting-events.fifo";

    private static AwsEventProperties props(boolean prod, String url) {
        return new AwsEventProperties(prod, true, url, true);
    }

    @Test
    void aNonProdProfileIsRefusedTheProductionQueue() {
        assertThat(props(false, PROD).refusal()).hasValueSatisfying(r -> assertThat(r).contains("production queue"));
        assertThat(props(false, PROD).namesProductionQueue()).isTrue();
    }

    @Test
    void aNonProdProfileIsRefusedTheLegacyQueueToo() {
        assertThat(props(false, LEGACY).refusal()).isPresent();
    }

    @Test
    void uatMayConsumeItsOwnSuffixedQueue() {
        assertThat(props(false, UAT).refusal()).isEmpty();
        assertThat(props(false, UAT).suffix()).contains("-uat");
    }

    @Test
    void prodMayConsumeTheUnsuffixedQueueWithAnEmptySuffix() {
        assertThat(props(true, PROD).refusal()).isEmpty();
        assertThat(props(true, PROD).suffix()).contains("");
    }

    @Test
    void aBlankUrlIsRefusedSoNothingPollsByDefault() {
        assertThat(props(true, "").refusal()).hasValue("queue-url is blank");
        assertThat(props(false, null).refusal()).hasValue("queue-url is blank");
    }

    @Test
    void aQueueThatIsNotOursIsRefused() {
        assertThat(props(true, BASE + "some-other.fifo").refusal()).isPresent();
        assertThat(props(true, BASE + "xamops-inventory-events.fifo").refusal()).isPresent();
    }

    @Test
    void theProdProfileIsReadFromTheEnvironment() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        MockEnvironment uat = new MockEnvironment();
        uat.setActiveProfiles("uat");
        MockEnvironment none = new MockEnvironment();
        assertThat(new AwsEventProperties(prod, true, PROD, true).refusal()).isEmpty();
        assertThat(new AwsEventProperties(uat, true, PROD, true).refusal()).isPresent();
        assertThat(new AwsEventProperties(none, true, PROD, true).refusal()).isPresent();
    }

    @Test
    void recordOnlyAndEnabledAreReadAsGiven() {
        AwsEventProperties p = new AwsEventProperties(true, false, PROD, true);
        assertThat(p.enabled()).isFalse();
        assertThat(p.recordOnly()).isTrue();
    }
}
