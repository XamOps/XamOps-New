package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Covers the savings roll-ups that feed the dashboard KPI.
 *
 * <p>The dashboard adds disk-rightsizing savings on top of the waste total. Unattached
 * volumes appear in both — the waste detector books the full monthly price and the disk
 * scanner books the same amount as {@code DELETE_UNATTACHED} — so counting both inflates the
 * KPI above the reconciled FinOps figure.
 */
@ExtendWith(MockitoExtension.class)
class EbsDiskRightsizingSavingsTest {

    @Mock AwsClientProvider awsClientProvider;
    @Mock com.xammer.cloud.repository.CloudAccountRepository cloudAccountRepository;
    @Mock com.xammer.cloud.repository.DiskPolicyRepository diskPolicyRepository;
    @Mock com.xammer.cloud.repository.EbsVolumeActionRepository ebsVolumeActionRepository;
    @Mock RedisCacheService redisCacheService;
    @Mock EbsShrinkExecutorService shrinkExecutorService;
    @Mock EbsExpandExecutorService expandExecutorService;
    @Mock EbsAsgHelper ebsAsgHelper;
    @Mock PricingService pricingService;

    @InjectMocks EbsDiskRightsizingService service;

    private static Map<String, Object> volume(String id, String recommendation, double savings) {
        return Map.of("volumeId", id, "recommendation", recommendation, "estimatedSavings", savings);
    }

    private void cacheContains(List<Map<String, Object>> volumes) {
        when(redisCacheService.<List<Map<String, Object>>>get(eq("ebs-volumes-123456789012"),
                any(TypeReference.class)))
                .thenReturn(Optional.of(volumes));
    }

    @Test
    @DisplayName("resize-only savings omit DELETE_UNATTACHED, which the waste report already books")
    void resizeOnlyExcludesUnattached() {
        cacheContains(List.of(
                volume("vol-shrink", "SHRINK", 4.00),
                volume("vol-upgrade", "UPGRADE_TYPE", 1.50),
                volume("vol-orphan", "DELETE_UNATTACHED", 9.12),
                volume("vol-fine", "NONE", 0.0)));

        assertThat(service.getResizeOnlyPotentialSavings("123456789012")).isEqualTo(5.50);
    }

    @Test
    @DisplayName("EXPAND raises cost, so it never contributes to a savings total")
    void expandNeverCountsAsSaving() {
        cacheContains(List.of(
                volume("vol-shrink", "SHRINK", 4.00),
                volume("vol-grow", "EXPAND", 0.0)));

        assertThat(service.getResizeOnlyPotentialSavings("123456789012")).isEqualTo(4.00);
    }

    @Test
    @DisplayName("the unreconciled total still includes unattached volumes, so the two differ")
    void totalAndResizeOnlyDiffer() {
        cacheContains(List.of(
                volume("vol-shrink", "SHRINK", 4.00),
                volume("vol-orphan", "DELETE_UNATTACHED", 9.12)));

        assertThat(service.getResizeOnlyPotentialSavings("123456789012")).isEqualTo(4.00);
    }

    @Test
    @DisplayName("explicitly excluded volume ids are dropped from the total")
    void explicitExclusionIsHonoured() {
        cacheContains(List.of(
                volume("vol-shrink", "SHRINK", 4.00),
                volume("vol-orphan", "DELETE_UNATTACHED", 9.12)));

        assertThat(service.getTotalPotentialSavingsExcluding("123456789012", Set.of("vol-orphan")))
                .isEqualTo(4.00);
    }

    @Test
    @DisplayName("a blank or missing account yields zero rather than throwing")
    void missingAccountYieldsZero() {
        assertThat(service.getResizeOnlyPotentialSavings(null)).isZero();
        assertThat(service.getResizeOnlyPotentialSavings("  ")).isZero();
    }

    @Test
    @DisplayName("an empty cache yields zero without a live scan")
    void emptyCacheYieldsZero() {
        when(redisCacheService.<List<Map<String, Object>>>get(eq("ebs-volumes-123456789012"),
                any(TypeReference.class)))
                .thenReturn(Optional.empty());

        assertThat(service.getResizeOnlyPotentialSavings("123456789012")).isZero();
    }
}
