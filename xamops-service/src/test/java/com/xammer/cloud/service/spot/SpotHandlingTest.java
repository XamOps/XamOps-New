package com.xammer.cloud.service.spot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every row of the design §B4.2 handling table, top to bottom (AC17). */
class SpotHandlingTest {

    private static JsonNode decision(String mode) {
        return SpotFixtures.MAPPER.createObjectNode().put("mode", mode).put("reason", "DRY_RUN=true");
    }

    @Test
    void missingDecisionIsUnknown() {
        for (JsonNode d : new JsonNode[]{null, NullNode.getInstance(), MissingNode.getInstance(),
                SpotFixtures.MAPPER.createObjectNode()}) {
            SpotHandling h = SpotHandling.from(d, true, false, "nth-taint");
            assertThat(h.mode()).isEqualTo("unknown");
            assertThat(h.reason()).isEqualTo("The automation agent didn't report its handling state");
        }
    }

    @Test
    void detectionFailedWithALegacyNoticeIsUnknownNeverNotEligible() {
        for (String mode : new String[]{"live", "simulate", "off"}) {
            SpotHandling h = SpotHandling.from(decision(mode), false, true, "nth-label-legacy");
            assertThat(h.mode()).as(mode).isEqualTo("unknown");
            assertThat(h.reason()).isEqualTo("The automation agent couldn't check drain eligibility this cycle");
        }
    }

    @Test
    void detectionFailedDoesNotAffectATaintNotice() {
        SpotHandling h = SpotHandling.from(decision("live"), false, true, "nth-taint");
        assertThat(h.mode()).isEqualTo("not-eligible");
    }

    @Test
    void anEligibleNoticeTakesTheDecision() {
        for (String mode : new String[]{"live", "simulate", "off"}) {
            SpotHandling h = SpotHandling.from(decision(mode), true, false, "nth-label-legacy");
            assertThat(h.mode()).isEqualTo(mode);
            assertThat(h.reason()).isEqualTo("DRY_RUN=true");
        }
    }

    @Test
    void aNotEligibleNoticeUnderLiveIsNotEligibleWithTheModeInTheReason() {
        SpotHandling h = SpotHandling.from(decision("live"), false, false, "nth-taint");
        assertThat(h.mode()).isEqualTo("not-eligible");
        assertThat(h.reason()).startsWith("live:")
                .isEqualTo("live: XamOps drains only nodes with the legacy NTH label today; this notice came from nth-taint");
    }

    @Test
    void offAndNotEligibleIsStillNotEligible() {
        SpotHandling h = SpotHandling.from(decision("off"), false, false, "nth-taint");
        assertThat(h.mode()).isEqualTo("not-eligible");
        assertThat(h.reason()).startsWith("off:");
    }

    @Test
    void modeIsCaseInsensitive() {
        assertThat(SpotHandling.from(decision("SIMULATE"), true, false, "nth-label-legacy").mode()).isEqualTo("simulate");
        assertThat(SpotHandling.from(decision("Live"), false, false, "nth-taint").reason()).startsWith("live:");
    }

    @Test
    void analyseAndAnythingElseIsUnknown() {
        SpotHandling analyse = SpotHandling.from(decision("analyse"), true, false, "nth-taint");
        assertThat(analyse.mode()).isEqualTo("unknown");
        assertThat(analyse.reason()).isEqualTo("Unrecognized handling mode analyse");
        assertThat(SpotHandling.from(decision("turbo"), false, true, "nth-label-legacy").mode()).isEqualTo("unknown");
    }

    @Test
    void theReasonIsTruncatedTo255() {
        JsonNode d = SpotFixtures.MAPPER.createObjectNode().put("mode", "live").put("reason", "x".repeat(400));
        assertThat(SpotHandling.from(d, true, false, "nth-label-legacy").reason()).hasSize(255);
    }
}
