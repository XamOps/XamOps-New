package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExposureCategoryTest {

    @Test
    @DisplayName("six categories in the console's order with its card titles")
    void orderAndTitles() {
        assertThat(Arrays.stream(ExposureCategory.values()).map(Enum::name)).containsExactly(
                "SENSITIVE_FINDINGS", "EXCESSIVE_EXPOSURE", "HIGH_VOLUME_ACCESS", "STALE_DATA", "RISKY_USER",
                "PUBLIC_EXPOSURE");
        assertThat(Arrays.stream(ExposureCategory.values()).map(ExposureCategory::title)).containsExactly(
                "Sensitive Findings", "Excessive Exposure", "High Volume Access", "Stale Data", "Risky User",
                "Public Exposure");
    }

    @Test
    @DisplayName("fromParam takes the exact name only, else a 400 on category")
    void fromParam() {
        assertThat(ExposureCategory.fromParam("STALE_DATA")).isEqualTo(ExposureCategory.STALE_DATA);
        for (String bad : Arrays.asList("stale_data", " STALE_DATA", "DATA_RESIDENCY", "", null)) {
            assertThatThrownBy(() -> ExposureCategory.fromParam(bad))
                    .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                        assertThat(e.parameter()).isEqualTo("category");
                        assertThat(e.getMessage()).isEqualTo("category must be one of the exposure categories");
                    });
        }
    }

    @Test
    @DisplayName("fromVendorId ignores case and spaces, and drops DATA_RESIDENCY and unknown ids")
    void fromVendorId() {
        assertThat(ExposureCategory.fromVendorId(" risky_user ")).contains(ExposureCategory.RISKY_USER);
        assertThat(ExposureCategory.fromVendorId("Public_Exposure")).contains(ExposureCategory.PUBLIC_EXPOSURE);
        for (String other : Arrays.asList("DATA_RESIDENCY", "RISKY USER", "", null)) {
            assertThat(ExposureCategory.fromVendorId(other)).isEmpty();
        }
        assertThat(List.of(ExposureCategory.values())).hasSize(6);
    }
}
