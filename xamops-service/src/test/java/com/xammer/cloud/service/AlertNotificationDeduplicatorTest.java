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

    // ── detectNewAlerts / markSeen — per-alert tracking ───────────────────────
    //
    // These replace the set-level fingerprint. The fingerprint changed whenever membership
    // changed in *either* direction, so resolving an alert re-sent the whole digest, and on
    // a 2000-alert account any single change re-mailed all 2000. The tests below pin the two
    // properties that fixes: only additions are reported, and removals are silent.

    private static final String AWS_SEEN_KEY = "notif::seen-alerts::aws::acct-1";

    @SuppressWarnings("unchecked")
    private void seenInRedis(String key, List<String> keys) {
        when(redisCache.get(eq(key), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Optional.of(keys));
    }

    @SuppressWarnings("unchecked")
    private void noSeenStateInRedis() {
        when(redisCache.get(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Optional.empty());
    }

    @Test
    void detectNewAlerts_noPriorState_isColdStartBaselineNotNewFindings() {
        noSeenStateInRedis();
        List<AlertDto> current = List.of(
                alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE"),
                alert("COST", "EC2 spike", "EC2", "us-east-1", "ACTIVE"));

        AlertNotificationDeduplicator.AlertDelta delta = dedup.detectNewAlerts("aws", "acct-1", current);

        assertThat(delta.coldStart()).isTrue();
        assertThat(delta.newAlerts()).hasSize(2);
        assertThat(delta.totalOpen()).isEqualTo(2);
        assertThat(delta.worthSending()).isTrue();
    }

    @Test
    void detectNewAlerts_everythingAlreadySeen_sendsNothing() {
        AlertDto a = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        AlertDto b = alert("COST", "EC2 spike", "EC2", "us-east-1", "ACTIVE");
        seenInRedis(AWS_SEEN_KEY, List.of(
                AlertNotificationDeduplicator.alertKey(a),
                AlertNotificationDeduplicator.alertKey(b)));

        AlertNotificationDeduplicator.AlertDelta delta = dedup.detectNewAlerts("aws", "acct-1", List.of(a, b));

        assertThat(delta.worthSending()).isFalse();
        assertThat(delta.newAlerts()).isEmpty();
        assertThat(delta.coldStart()).isFalse();
        assertThat(delta.totalOpen()).isEqualTo(2);
    }

    @Test
    void detectNewAlerts_onlyTheAddedAlertIsReported() {
        AlertDto known = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        AlertDto fresh = alert("SECURITY", "Public bucket", "S3", "us-east-1", "CRITICAL");
        seenInRedis(AWS_SEEN_KEY, List.of(AlertNotificationDeduplicator.alertKey(known)));

        AlertNotificationDeduplicator.AlertDelta delta =
                dedup.detectNewAlerts("aws", "acct-1", List.of(known, fresh));

        assertThat(delta.newAlerts()).hasSize(1);
        assertThat(delta.newAlerts().get(0).getName()).isEqualTo("Public bucket");
        // The email still reports the whole open set as a figure.
        assertThat(delta.totalOpen()).isEqualTo(2);
    }

    @Test
    void detectNewAlerts_resolutionOnly_sendsNothing() {
        // This is the regression the old fingerprint had: the account got *healthier*
        // and that generated an alert email.
        AlertDto stillOpen = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        AlertDto resolved = alert("COST", "EC2 spike", "EC2", "us-east-1", "ACTIVE");
        seenInRedis(AWS_SEEN_KEY, List.of(
                AlertNotificationDeduplicator.alertKey(stillOpen),
                AlertNotificationDeduplicator.alertKey(resolved)));

        AlertNotificationDeduplicator.AlertDelta delta =
                dedup.detectNewAlerts("aws", "acct-1", List.of(stillOpen));

        assertThat(delta.worthSending()).isFalse();
        assertThat(delta.newAlerts()).isEmpty();
        assertThat(delta.totalOpen()).isEqualTo(1);
    }

    @Test
    void detectNewAlerts_allAlertsResolved_sendsNothing() {
        seenInRedis(AWS_SEEN_KEY, List.of(
                AlertNotificationDeduplicator.alertKey(alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE"))));

        AlertNotificationDeduplicator.AlertDelta delta = dedup.detectNewAlerts("aws", "acct-1", List.of());

        assertThat(delta.worthSending()).isFalse();
        assertThat(delta.totalOpen()).isZero();
    }

    @Test
    void detectNewAlerts_usageDrift_isNotANewAlert() {
        // A quota alert creeping from 76% to 77% is the same finding. If usage were part of
        // the identity, a busy account would mail every night forever.
        AlertDto before = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        before.setUsage(76.0);
        before.setLimit(100.0);
        seenInRedis(AWS_SEEN_KEY, List.of(AlertNotificationDeduplicator.alertKey(before)));

        AlertDto after = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        after.setUsage(77.0);
        after.setLimit(100.0);

        AlertNotificationDeduplicator.AlertDelta delta = dedup.detectNewAlerts("aws", "acct-1", List.of(after));

        assertThat(delta.newAlerts()).isEmpty();
        assertThat(delta.worthSending()).isFalse();
    }

    @Test
    void detectNewAlerts_alertThatClearedAndReturned_countsAsNewAgain() {
        // markSeen is called even on silent runs, so the resolved alert was forgotten.
        // Its return is genuinely news.
        AlertDto returning = alert("COST", "EC2 spike", "EC2", "us-east-1", "ACTIVE");
        seenInRedis(AWS_SEEN_KEY, List.of(
                AlertNotificationDeduplicator.alertKey(alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE"))));

        AlertNotificationDeduplicator.AlertDelta delta =
                dedup.detectNewAlerts("aws", "acct-1", List.of(returning));

        assertThat(delta.newAlerts()).hasSize(1);
        assertThat(delta.newAlerts().get(0).getName()).isEqualTo("EC2 spike");
    }

    @Test
    void detectNewAlerts_nullList_treatedAsEmpty() {
        noSeenStateInRedis();

        AlertNotificationDeduplicator.AlertDelta delta = dedup.detectNewAlerts("aws", "acct-1", null);

        assertThat(delta.newAlerts()).isEmpty();
        assertThat(delta.totalOpen()).isZero();
        assertThat(delta.worthSending()).isFalse();
    }

    @Test
    void detectNewAlerts_isPerProviderAndPerAccount() {
        noSeenStateInRedis();
        dedup.detectNewAlerts("gcp", "my-project", List.of());

        verify(redisCache).get(eq("notif::seen-alerts::gcp::my-project"),
                any(com.fasterxml.jackson.core.type.TypeReference.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void markSeen_storesAlertKeysWithOneYearTtl() {
        dedup.markSeen("aws", "acct-1", List.of(
                alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE"),
                alert("COST", "EC2 spike", "EC2", "us-east-1", "ACTIVE")));

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisCache).put(eq(AWS_SEEN_KEY), keysCaptor.capture(), eq(365 * 24 * 60));
        assertThat(keysCaptor.getValue()).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void markSeen_deduplicatesIdenticalAlerts() {
        AlertDto a = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        AlertDto sameAgain = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");

        dedup.markSeen("aws", "acct-1", List.of(a, sameAgain));

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisCache).put(eq(AWS_SEEN_KEY), keysCaptor.capture(), anyInt());
        assertThat(keysCaptor.getValue()).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void markSeen_emptyList_clearsTheRememberedSet() {
        dedup.markSeen("aws", "acct-1", List.of());

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisCache).put(eq(AWS_SEEN_KEY), keysCaptor.capture(), anyInt());
        assertThat(keysCaptor.getValue()).isEmpty();
    }

    @Test
    void clearSeen_evictsTheAccountsKey() {
        dedup.clearSeen("azure", "sub-123");

        verify(redisCache).evict("notif::seen-alerts::azure::sub-123");
    }

    @Test
    void alertKey_matchesFingerprintFields_ignoringUsageAndLimit() {
        AlertDto low = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        low.setUsage(10.0);
        AlertDto high = alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE");
        high.setUsage(99.0);

        assertThat(AlertNotificationDeduplicator.alertKey(low))
                .isEqualTo(AlertNotificationDeduplicator.alertKey(high));
    }

    @Test
    void alertKey_differentRegion_isADifferentAlert() {
        assertThat(AlertNotificationDeduplicator.alertKey(
                alert("QUOTA", "VPC limit", "VPC", "us-east-1", "ACTIVE")))
                .isNotEqualTo(AlertNotificationDeduplicator.alertKey(
                        alert("QUOTA", "VPC limit", "VPC", "eu-west-1", "ACTIVE")));
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
