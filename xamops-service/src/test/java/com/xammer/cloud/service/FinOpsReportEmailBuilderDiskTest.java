package com.xammer.cloud.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinOpsReportEmailBuilderDiskTest {

    private FinOpsReportEmailBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new FinOpsReportEmailBuilder();
    }

    @Test
    void buildDiskHealthSection_returnsEmptyStringForEmptyList() {
        assertThat(builder.buildDiskHealthSection(List.of())).isEmpty();
        assertThat(builder.buildDiskHealthSection(null)).isEmpty();
    }

    @Test
    void buildDiskHealthSection_containsVolumeIdInOutput() {
        Map<String, Object> vol = new HashMap<>();
        vol.put("volumeId", "vol-0947baf695d10391e");
        vol.put("region", "ap-south-1");
        vol.put("sizeGb", 30);
        vol.put("usedPercent", 92.5);
        vol.put("freeGb", 2.25);
        vol.put("recommendation", "EXPAND");

        String html = builder.buildDiskHealthSection(List.of(vol));

        assertThat(html).contains("vol-0947baf695d10391e");
        assertThat(html).contains("92.5%");
        assertThat(html).contains("Near Full");
        assertThat(html).contains("Expand");
    }

    @Test
    void buildDiskHealthSection_showsUnderutilisedLabelForLowUsage() {
        Map<String, Object> vol = new HashMap<>();
        vol.put("volumeId", "vol-underutil");
        vol.put("region", "ap-south-2");
        vol.put("sizeGb", 50);
        vol.put("usedPercent", 10.0);
        vol.put("freeGb", 45.0);
        vol.put("recommendation", "SHRINK");

        String html = builder.buildDiskHealthSection(List.of(vol));

        assertThat(html).contains("Underutilized");
        assertThat(html).contains("Shrink");
        assertThat(html).contains("10.0%");
    }
}
