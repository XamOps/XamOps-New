package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** Design 5.3: the Slack channels query; skip is a page index (page - 1, A11). */
class SaasChannelQueryTest {

    @Test
    @DisplayName("limit, skip = page - 1, then search, channelType, channelStatus, scanState, isNew when set")
    void vendorParams() {
        assertThat(new SaasChannelQuery(null, null, null, null, null, 1, 10).toVendorParams()).containsExactly(
                entry("limit", List.of("10")),
                entry("skip", List.of("0")));
        assertThat(new SaasChannelQuery("general", "PRIVATE", "ARCHIVED", "ENABLED", false, 4, 20).toVendorParams())
                .containsExactly(
                        entry("limit", List.of("20")),
                        entry("skip", List.of("3")),
                        entry("search", List.of("general")),
                        entry("channelType", List.of("PRIVATE")),
                        entry("channelStatus", List.of("ARCHIVED")),
                        entry("scanState", List.of("ENABLED")),
                        entry("isNew", List.of("false")));
    }

    @Test
    @DisplayName("the fragment changes with each field")
    void canonicalFragment() {
        String base = new SaasChannelQuery(null, null, null, null, null, 1, 10).canonicalFragment();
        assertThat(base).isEqualTo("limit=10&skip=0");
        assertThat(new SaasChannelQuery(null, null, null, null, true, 1, 10).canonicalFragment())
                .isEqualTo("limit=10&skip=0&isNew=true");
        assertThat(new SaasChannelQuery("x", null, null, null, null, 1, 10).canonicalFragment()).isNotEqualTo(base);
        assertThat(new SaasChannelQuery(null, "PUBLIC", null, null, null, 1, 10).canonicalFragment())
                .isNotEqualTo(base);
        assertThat(new SaasChannelQuery(null, null, "ACTIVE", null, null, 1, 10).canonicalFragment())
                .isNotEqualTo(base);
        assertThat(new SaasChannelQuery(null, null, null, "DISABLED", null, 1, 10).canonicalFragment())
                .isNotEqualTo(base);
        assertThat(new SaasChannelQuery(null, null, null, null, null, 2, 10).canonicalFragment()).isNotEqualTo(base);
    }
}
