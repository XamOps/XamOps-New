package com.xammer.cloud.service;

import com.xammer.cloud.dto.AlertDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AlertNotificationDeduplicator.
 *
 * Tests the fingerprint-based deduplication logic for AWS, GCP, Azure digest alerts
 * and the threshold-based deduplication for budget alerts.
 * RedisCacheService is mocked so no Redis is needed.
 */
class AlertNotificationDeduplicatorTest {

    @Mock
    private RedisCacheService redisCache;

    private AlertNotificationDeduplicator dedup;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        dedup = new AlertNotificationDeduplicator(redisCache);
    }

    // ── AWS digest deduplication ──────────────────────────────────────────────

    @Test
    void awsDigest_sameFingerprint_returnsAlreadySent() {
        when(redisCache.get("notif::aws-digest-fp::acct-1", String.class))
                .thenReturn(Optional.of("abc12345"));

        assertThat(dedup.awsDigestAlreadySentForContent("acct-1", "abc12345")).isTrue();
    }

    @Test
    void awsDigest_differentFingerprint_returnsNotSent() {
        when(redisCache.get("notif::aws-digest-fp::acct-1", String.class))
                .thenReturn(Optional.of("oldfingerprint"));

        assertThat(dedup.awsDigestAlreadySentForContent("acct-1", "newfingerprint")).isFalse();
    }

    @Test
    void awsDigest_noKeyInRedis_returnsNotSent() {
        when(redisCache.get("notif::aws-digest-fp::acct-1", String.class))
                .thenReturn(Optional.empty());

        assertThat(dedup.awsDigestAlreadySentForContent("acct-1", "anyfingerprint")).isFalse();
    }

    @Test
    void markAwsDigestSent_storesWithOneYearTtl() {
        dedup.markAwsDigestSent("acct-1", "abc12345def67890");

        ArgumentCaptor<Integer> ttlCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(redisCache).put(eq("notif::aws-digest-fp::acct-1"), eq("abc12345def67890"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(365 * 24 * 60);
    }

    // ── GCP digest deduplication ──────────────────────────────────────────────

    @Test
    void gcpDigest_sameFingerprint_returnsAlreadySent() {
        when(redisCache.get("notif::gcp-digest-fp::my-project", String.class))
                .thenReturn(Optional.of("fp-gcp-001"));

        assertThat(dedup.gcpDigestAlreadySentForContent("my-project", "fp-gcp-001")).isTrue();
    }

    @Test
    void gcpDigest_noKey_returnsNotSent() {
        when(redisCache.get(anyString(), eq(String.class))).thenReturn(Optional.empty());

        assertThat(dedup.gcpDigestAlreadySentForContent("my-project", "fp-gcp-001")).isFalse();
    }

    @Test
    void markGcpDigestSent_storesCorrectKey() {
        dedup.markGcpDigestSent("my-project", "fp-gcp-001");

        verify(redisCache).put(eq("notif::gcp-digest-fp::my-project"), eq("fp-gcp-001"), eq(365 * 24 * 60));
    }

    // ── Azure digest deduplication ────────────────────────────────────────────

    @Test
    void azureDigest_sameFingerprint_returnsAlreadySent() {
        when(redisCache.get("notif::azure-digest-fp::sub-123", String.class))
                .thenReturn(Optional.of("fp-azure-001"));

        assertThat(dedup.azureDigestAlreadySentForContent("sub-123", "fp-azure-001")).isTrue();
    }

    @Test
    void markAzureDigestSent_storesCorrectKey() {
        dedup.markAzureDigestSent("sub-123", "fp-azure-001");

        verify(redisCache).put(eq("notif::azure-digest-fp::sub-123"), eq("fp-azure-001"), eq(365 * 24 * 60));
    }

    // ── Budget alert deduplication ────────────────────────────────────────────

    @Test
    void budgetAlert_keyExists_returnsAlreadySent() {
        when(redisCache.hasKey(anyString())).thenReturn(true);

        assertThat(dedup.budgetAlertAlreadySent("acct-1", "my-budget", 80)).isTrue();
    }

    @Test
    void budgetAlert_keyMissing_returnsNotSent() {
        when(redisCache.hasKey(anyString())).thenReturn(false);

        assertThat(dedup.budgetAlertAlreadySent("acct-1", "my-budget", 80)).isFalse();
    }

    @Test
    void markBudgetAlertSent_callsSetFlagWithPositiveTtl() {
        dedup.markBudgetAlertSent("acct-1", "my-budget", 80);

        ArgumentCaptor<Integer> ttlCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(redisCache).setFlag(anyString(), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isPositive();
    }

    @Test
    void budgetKey_sanitizesSpecialCharsInName() {
        // A budget name with spaces/dots should be stored with underscores
        dedup.markBudgetAlertSent("acct-1", "my budget.2026", 50);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisCache).setFlag(keyCaptor.capture(), anyInt());
        String key = keyCaptor.getValue();
        assertThat(key).contains("my_budget_2026");
        assertThat(key).doesNotContain(" ").doesNotContain(".");
    }

    @Test
    void budgetKey_includesThresholdAndYearMonth() {
        dedup.markBudgetAlertSent("acct-1", "prod-budget", 100);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisCache).setFlag(keyCaptor.capture(), anyInt());
        String key = keyCaptor.getValue();
        assertThat(key).contains("::100::");
        assertThat(key).contains("notif::budget::acct-1");
    }

    // ── computeFingerprint (static) ───────────────────────────────────────────

    @Test
    void fingerprint_sameAlerts_sameResult() {
        List<AlertDto> alerts = List.of(
                alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE"),
                alert("COST",  "EC2 spike",  "EC2", "us-east-1", "ACTIVE"));

        String fp1 = AlertNotificationDeduplicator.computeFingerprint(alerts);
        String fp2 = AlertNotificationDeduplicator.computeFingerprint(alerts);

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    void fingerprint_isOrderIndependent() {
        AlertDto a = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        AlertDto b = alert("COST",  "EC2 spike",  "EC2", "eu-west-1", "ACTIVE");

        String fp1 = AlertNotificationDeduplicator.computeFingerprint(List.of(a, b));
        String fp2 = AlertNotificationDeduplicator.computeFingerprint(List.of(b, a));

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    void fingerprint_differentStatus_differentResult() {
        AlertDto active  = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        AlertDto resolved = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "RESOLVED");

        String fp1 = AlertNotificationDeduplicator.computeFingerprint(List.of(active));
        String fp2 = AlertNotificationDeduplicator.computeFingerprint(List.of(resolved));

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void fingerprint_emptyList_returnsStableNonEmptyString() {
        String fp = AlertNotificationDeduplicator.computeFingerprint(List.of());

        assertThat(fp).isNotBlank();
        // Calling twice should be identical
        assertThat(fp).isEqualTo(AlertNotificationDeduplicator.computeFingerprint(List.of()));
    }

    @Test
    void fingerprint_differentRegion_differentResult() {
        AlertDto usEast = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        AlertDto euWest = alert("QUOTA", "VPC limit", "VPC", "eu-west-1", "ACTIVE");

        assertThat(AlertNotificationDeduplicator.computeFingerprint(List.of(usEast)))
                .isNotEqualTo(AlertNotificationDeduplicator.computeFingerprint(List.of(euWest)));
    }

    @Test
    void fingerprint_nullFields_handledGracefully() {
        AlertDto nullRegion = new AlertDto();
        nullRegion.setType("QUOTA");
        nullRegion.setName("test");
        // region, service, status are null

        String fp = AlertNotificationDeduplicator.computeFingerprint(List.of(nullRegion));
        assertThat(fp).isNotBlank();
    }

    @Test
    void fingerprint_isSha256Length() {
        AlertDto a = alert("QUOTA", "name", "VPC", "us-east-1", "ACTIVE");
        String fp = AlertNotificationDeduplicator.computeFingerprint(List.of(a));
        assertThat(fp).hasSize(64);  // SHA-256 hex = 64 chars
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private AlertDto alert(String type, String name, String service, String region, String status) {
        AlertDto dto = new AlertDto();
        dto.setType(type);
        dto.setName(name);
        dto.setService(service);
        dto.setRegion(region);
        dto.setStatus(status);
        return dto;
    }
}
