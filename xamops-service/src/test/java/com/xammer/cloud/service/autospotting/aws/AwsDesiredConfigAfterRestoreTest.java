package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.aws.steps.RestoreShapeStep;
import com.xammer.cloud.service.autospotting.spi.PlanOptions;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** D-23b-01 review #5: what a finished restore program leaves in desired_config, decided by the steps that ran. */
class AwsDesiredConfigAfterRestoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> RESTORED = Set.of("VERIFY_REVERT_PRECONDITIONS", RestoreShapeStep.NAME);
    private static final Set<String> DRAINED = Set.of("VERIFY_REVERT_PRECONDITIONS", "DRAIN_SPOT");
    private static final String FULL = "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"overrides\":[\"t3.micro\",\"t3a.micro\"],"
            + "\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200},"
            + "\"options\":{\"allowBurstable\":true,\"burstableAdded\":[\"t3a.micro\"],\"burstableRemoved\":[\"t3a.small\"]}}";

    private final AwsSpotProvider provider =
            new AwsConversionFixture(FakeAws.asg("web-asg").build(), true).provider;

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    @Test
    void aRestoreDropsTheOwnedPolicyAndBothBurstableMarkersButKeepsTheChoice() throws Exception {
        JsonNode out = json(provider.desiredConfigAfterRestore(FULL, RESTORED));
        assertThat(out.has(AwsMaintenancePolicy.KEY)).isFalse();
        assertThat(out.path("options").has("burstableAdded")).isFalse();
        assertThat(out.path("options").has("burstableRemoved")).isFalse();
        assertThat(out.path("options").path("allowBurstable").asBoolean()).as("the customer's choice stays").isTrue();
        assertThat(out.path("overrides").size()).as("the rest of desired_config is untouched").isEqualTo(2);
        assertThat(out.has(PlanOptions.CONFIG_ON_GROUP)).isFalse();
    }

    @Test
    void onlyBurstableRemovedIsDroppedToo() throws Exception {
        String in = "{\"options\":{\"allowBurstable\":false,\"burstableRemoved\":[\"t3a.micro\"]}}";
        JsonNode out = json(provider.desiredConfigAfterRestore(in, RESTORED));
        assertThat(out.path("options").has("burstableRemoved")).isFalse();
        assertThat(out.path("options").path("allowBurstable").isBoolean()).isTrue();
    }

    @Test
    void aCustomerPolicyWithoutTheOwnedMarkerIsLeftAlone() {
        String in = "{\"instanceMaintenancePolicy\":{\"min\":90,\"max\":120}}";
        assertThat(provider.desiredConfigAfterRestore(in, RESTORED)).isEqualTo(in);
    }

    @Test
    void aDrainOnlyRevertKeepsEveryMarkerAndRecordsThatTheConfigurationIsStillOnTheGroup() throws Exception {
        JsonNode out = json(provider.desiredConfigAfterRestore(FULL, DRAINED));
        assertThat(AwsMaintenancePolicy.owns(out)).isTrue();
        assertThat(out.path("options").path("burstableAdded").toString()).isEqualTo("[\"t3a.micro\"]");
        assertThat(out.path(PlanOptions.CONFIG_ON_GROUP).asBoolean()).isTrue();
        String again = provider.desiredConfigAfterRestore(out.toString(), DRAINED);
        assertThat(again).as("idempotent").isEqualTo(out.toString());
    }

    @Test
    void aRestoreAfterADrainOnlyRevertClearsTheFlagWithTheMarkers() throws Exception {
        String drained = provider.desiredConfigAfterRestore(FULL, DRAINED);
        JsonNode out = json(provider.desiredConfigAfterRestore(drained, RESTORED));
        assertThat(out.has(PlanOptions.CONFIG_ON_GROUP)).isFalse();
        assertThat(AwsMaintenancePolicy.owns(out)).isFalse();
    }

    @Test
    void missingOptionsNullBlankMalformedAndNonObjectInputsAreSafe() throws Exception {
        String noOptions = "{\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}}";
        assertThat(json(provider.desiredConfigAfterRestore(noOptions, RESTORED)).has(AwsMaintenancePolicy.KEY)).isFalse();
        assertThat(provider.desiredConfigAfterRestore(null, RESTORED)).isNull();
        assertThat(provider.desiredConfigAfterRestore("  ", RESTORED)).isEqualTo("  ");
        assertThat(provider.desiredConfigAfterRestore("{not json", RESTORED)).isEqualTo("{not json");
        assertThat(provider.desiredConfigAfterRestore("[1,2]", RESTORED)).isEqualTo("[1,2]");
        assertThat(provider.desiredConfigAfterRestore("{}", RESTORED)).isEqualTo("{}");
        assertThat(json(provider.desiredConfigAfterRestore(FULL, null)).path(PlanOptions.CONFIG_ON_GROUP).asBoolean())
                .as("no step information means nothing was restored").isTrue();
    }
}
