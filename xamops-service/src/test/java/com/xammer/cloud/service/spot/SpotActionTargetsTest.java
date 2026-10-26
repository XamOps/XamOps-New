package com.xammer.cloud.service.spot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpotActionTargetsTest {

    @Test
    void oneNode() {
        assertThat(SpotActionTargets.targetNodes("node/ip-10-0-1-5")).containsExactly("ip-10-0-1-5");
    }

    @Test
    void aNodeSet() {
        assertThat(SpotActionTargets.targetNodes("nodes/a,b")).containsExactly("a", "b");
    }

    @Test
    void emptyAndBlankPartsAreDropped() {
        assertThat(SpotActionTargets.targetNodes("nodes/a,, ,b ")).containsExactly("a", "b");
        assertThat(SpotActionTargets.targetNodes("nodes/")).isEmpty();
    }

    @Test
    void unknownPrefixAndNullHaveNoNodes() {
        assertThat(SpotActionTargets.targetNodes("deployment/web")).isEmpty();
        assertThat(SpotActionTargets.targetNodes("")).isEmpty();
        assertThat(SpotActionTargets.targetNodes(null)).isEmpty();
    }
}
