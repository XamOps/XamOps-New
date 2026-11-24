package com.xammer.cloud.autospotting.list;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.autospotting.v2.GroupRowDto;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** design-ux-ops.md §9.8 (AC-CMP-06): {@code savingsUnknown} is "Not priced", never 0. */
class AutoSpottingGroupDtoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EligibilityResult CLEAN = new EligibilityResult(List.of(), List.of(), List.of());

    private static GroupRowDto row(Double savings, boolean applicable, EligibilityResult e) {
        return GroupRowDto.of(UUID.randomUUID(), "AWS", "ap-south-1/web", "ap-south-1", "web", "UNMANAGED", false, false,
                4, 2, 8, "m5.large", e, savings, applicable);
    }

    @Test
    void unpricedIsNotPricedNeverZero() throws Exception {
        GroupRowDto unpriced = row(null, true, CLEAN);
        assertThat(unpriced.savingsUnknown()).isTrue();
        assertThat(unpriced.monthlySavings()).isNull();
        assertThat(unpriced.savingsLabel()).isEqualTo("Not priced");
        JsonNode json = JSON.readTree(JSON.writeValueAsString(unpriced));
        assertThat(json.get("savingsUnknown").asBoolean()).isTrue();
        assertThat(json.get("monthlySavings").isNull()).isTrue();
        assertThat(json.toString()).doesNotContain("\"monthlySavings\":0");
    }

    @Test
    void aPricedFigureIsAnEstimate() {
        GroupRowDto priced = row(48.18, true, CLEAN);
        assertThat(priced.savingsUnknown()).isFalse();
        assertThat(priced.monthlySavings()).isEqualTo(48.18);
        assertThat(priced.savingsLabel()).isEqualTo("Estimate");
    }

    @Test
    void aRealZeroIsAZeroNotUnknown() {
        GroupRowDto zero = row(0.0, true, CLEAN);
        assertThat(zero.savingsUnknown()).isFalse();
        assertThat(zero.monthlySavings()).isEqualTo(0.0);
    }

    @Test
    void nothingToShowIsNeitherUnknownNorAFigure() {
        GroupRowDto none = row(null, false, CLEAN);
        assertThat(none.savingsUnknown()).isFalse();
        assertThat(none.monthlySavings()).isNull();
        assertThat(none.savingsLabel()).isNull();
    }

    @Test
    void aBlockerDisablesEnableWithItsReason() {
        EligibilityResult blocked = new EligibilityResult(List.of(
                EligibilityResult.Check.of("b-EC2_HEALTH_WITH_LB", "EC2_HEALTH_WITH_LB", "Switch to ELB health checks."),
                EligibilityResult.Check.failed("b-CHECK_FAILED-X", "x", ErrorKind.PERMISSION)), List.of(), List.of());
        GroupRowDto r = row(null, false, blocked);
        assertThat(r.enableAllowed()).isFalse();
        assertThat(r.enableBlockedBy()).isEqualTo("EC2_HEALTH_WITH_LB");
        assertThat(r.blockers()).extracting(GroupRowDto.CheckDto::errorKind).containsExactly(null, "PERMISSION");
    }

    @Test
    void onlyDeferredChecksStillShowEnable() {
        EligibilityResult deferred = new EligibilityResult(List.of(), List.of(),
                List.of(EligibilityResult.Check.of("d-PERMISSION_MISSING", "PERMISSION_MISSING", "Checked at plan.")));
        GroupRowDto r = row(null, true, deferred);
        assertThat(r.enableAllowed()).isTrue();
        assertThat(r.enableBlockedBy()).isNull();
        assertThat(r.deferred()).hasSize(1);
    }
}
