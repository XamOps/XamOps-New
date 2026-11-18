package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.aws.AwsSpotFailureMatcher.FailureClass;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launch-failure matcher table (design-aws.md §6.2, plan-phase-20.md task 20.1): one recorded-message fixture
 * per matcher under {@code autospotting/aws/activities/}, an unknown message is OTHER, and an On-Demand failure,
 * floor or not, is never a Spot failure (AC-EVT-03).
 */
class AwsLaunchFailureClassifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIXTURES = List.of("spot-no-capacity", "spot-max-price",
            "spot-insufficient-capacity", "spot-count-exceeded", "spot-vcpu-quota",
            "on-demand-insufficient-capacity", "other-unknown");

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = AwsLaunchFailureClassifierTest.class
                .getResourceAsStream("/autospotting/aws/activities/" + name + ".json")) {
            assertThat(in).as("fixture " + name).isNotNull();
            return JSON.readTree(in);
        }
    }

    @Test
    void everyRecordedMessageClassifiesAsItsFixtureSays() throws IOException {
        for (String name : FIXTURES) {
            JsonNode f = fixture(name);
            AwsLaunchFailureClassifier.Result r = AwsLaunchFailureClassifier.classify(f.get("statusMessage").asText());
            assertThat(r.failureClass()).as(name).isEqualTo(FailureClass.valueOf(f.get("failureClass").asText()));
            assertThat(r.matcherId()).as(name).isEqualTo(f.get("matcherId").isNull() ? null : f.get("matcherId").asText());
        }
    }

    @Test
    void everyMatcherIdHasAFixture() throws IOException {
        Set<String> covered = new HashSet<>();
        for (String name : FIXTURES) {
            JsonNode f = fixture(name);
            if (!f.get("matcherId").isNull()) {
                covered.add(f.get("matcherId").asText());
            }
        }
        assertThat(covered).containsExactlyInAnyOrderElementsOf(AwsLaunchFailureClassifier.MATCHER_IDS);
    }

    @Test
    void insufficientCapacityNeedsASpotMarkerToCountAsSpot() {
        assertThat(AwsLaunchFailureClassifier.classify("InsufficientInstanceCapacity in us-east-1a").failureClass())
                .isEqualTo(FailureClass.ON_DEMAND);
        assertThat(AwsLaunchFailureClassifier.classify("Spot: InsufficientInstanceCapacity").failureClass())
                .isEqualTo(FailureClass.SPOT_CAPACITY);
    }

    @Test
    void anOnDemandFailureIsNeverASpotFailure() throws IOException {
        AwsLaunchFailureClassifier.Result r = AwsLaunchFailureClassifier
                .classify(fixture("on-demand-insufficient-capacity").get("statusMessage").asText());
        assertThat(r.spot()).isFalse();
        assertThat(r.quota()).isFalse();
    }

    @Test
    void anUnknownOrEmptyMessageIsOtherAndNeverSpot() {
        assertThat(AwsLaunchFailureClassifier.classify("Something new from AWS").failureClass())
                .isEqualTo(FailureClass.OTHER);
        assertThat(AwsLaunchFailureClassifier.classify(null).spot()).isFalse();
        assertThat(AwsLaunchFailureClassifier.classify("").failureClass()).isEqualTo(FailureClass.OTHER);
    }

    @Test
    void theOldMatcherEntryPointAgrees() {
        assertThat(AwsSpotFailureMatcher.classify("There is no Spot capacity available", null))
                .isEqualTo(FailureClass.SPOT_CAPACITY);
        assertThat(AwsSpotFailureMatcher.TABLE_VERSION).isEqualTo(AwsLaunchFailureClassifier.TABLE_VERSION);
    }
}
