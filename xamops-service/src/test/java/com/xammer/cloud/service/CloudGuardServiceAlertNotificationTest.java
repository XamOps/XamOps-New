package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.SecurityNotificationPreferenceRepository;
import com.xammer.cloud.service.NotificationRecipientResolver;
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
 * Tests the AWS alert notification path end to end, without touching AWS.
 *
 * <p>Uses the two-argument {@code notifyNewAlerts(accountId, alerts)} overload, which takes
 * the alert list from its caller rather than fetching it, so the whole
 * gate → bound → send chain runs against a real {@link AlertNotificationDeduplicator} and a
 * real {@link AlertEmailBuilder} with only Redis and the mail transport mocked.
 *
 * <p>What matters here is the behaviour the customer complained about: a nightly email
 * repeating the same findings, and a 2000-row email on a large account.
 */
class CloudGuardServiceAlertNotificationTest {

    private static final String ACCOUNT_ID = "123456789012";
    private static final String SEEN_KEY = "notif::seen-alerts::aws::" + ACCOUNT_ID;

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private AwsClientProvider awsClientProvider;
    @Mock private RedisCacheService redisCache;
    @Mock private CloudListService cloudListService;
    @Mock private FinOpsService finOpsService;
    @Mock private EmailService emailService;
    @Mock private DashboardDataService dashboardDataService;
    @Mock private AwsSecurityHubService securityHubService;
    @Mock private NotificationRecipientResolver recipientResolver;
    @Mock private SecurityNotificationPreferenceRepository preferenceRepository;

    private CloudGuardService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Real collaborators: the gating and the bounding are what we are testing.
        AlertNotificationDeduplicator deduplicator = new AlertNotificationDeduplicator(redisCache);
        AlertEmailBuilder alertEmailBuilder = new AlertEmailBuilder();
        AlertDigestSeverityGate severityGate = new AlertDigestSeverityGate(preferenceRepository);

        // No preference row, so the permissive default applies and every severity is admitted.
        // The threshold itself is covered by AlertDigestSeverityGateTest.
        when(preferenceRepository.findById(anyLong())).thenReturn(Optional.empty());

        service = new CloudGuardService(
                cloudAccountRepository, awsClientProvider, redisCache, cloudListService,
                finOpsService, emailService, dashboardDataService, securityHubService,
                alertEmailBuilder, recipientResolver, deduplicator, severityGate);

        Client client = mock(Client.class);
        when(client.getId()).thenReturn(1L);

        CloudAccount account = mock(CloudAccount.class);
        when(account.getAccountName()).thenReturn("Acme Corp");
        when(account.getClient()).thenReturn(client);

        when(cloudAccountRepository.findByAwsAccountId(ACCOUNT_ID)).thenReturn(List.of(account));

        // Recipients come from the shared resolver, which also applies per-user email opt-out.
        when(recipientResolver.emailsForAccount(any())).thenReturn(List.of("ops@acme.example"));
    }

    // ── Gating ────────────────────────────────────────────────────────────────

    @Test
    void noNewAlerts_sendsNoEmail() {
        // The daily-repeat complaint: the same findings must go quiet after the first mail.
        AlertDto known = alert("QUOTA", "VPC limit");
        seen(List.of(AlertNotificationDeduplicator.alertKey(known)));

        service.notifyNewAlerts(ACCOUNT_ID, List.of(known));

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void onlyResolutions_sendsNoEmail() {
        AlertDto stillOpen = alert("QUOTA", "VPC limit");
        AlertDto cleared = alert("COST", "EC2 spike");
        seen(List.of(AlertNotificationDeduplicator.alertKey(stillOpen),
                AlertNotificationDeduplicator.alertKey(cleared)));

        service.notifyNewAlerts(ACCOUNT_ID, List.of(stillOpen));

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void aNewAlert_sendsOneEmailNamingTheNewCountAndTheOpenTotal() {
        AlertDto known = alert("QUOTA", "VPC limit");
        AlertDto fresh = alert("SECURITY", "Public bucket");
        seen(List.of(AlertNotificationDeduplicator.alertKey(known)));

        service.notifyNewAlerts(ACCOUNT_ID, List.of(known, fresh));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("ops@acme.example"), subject.capture(), body.capture());

        assertThat(subject.getValue()).contains("1 new alert").contains("Acme Corp").contains("2 open");
        // Only the new finding is itemised; the known one is represented by the total.
        assertThat(body.getValue()).contains("Public bucket").doesNotContain("VPC limit");
    }

    @Test
    void coldStart_sendsABaselineDigestNotNewFindings() {
        noPriorState();

        service.notifyNewAlerts(ACCOUNT_ID, List.of(alert("QUOTA", "VPC limit"), alert("COST", "EC2 spike")));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(anyString(), subject.capture(), anyString());
        assertThat(subject.getValue()).contains("Alert Digest").doesNotContain("new alert");
    }

    @Test
    void everyRunRecordsTheCurrentSet_evenWhenNothingIsSent() {
        // Without this, a resolved alert stays remembered forever and its return is silent.
        AlertDto known = alert("QUOTA", "VPC limit");
        seen(List.of(AlertNotificationDeduplicator.alertKey(known)));

        service.notifyNewAlerts(ACCOUNT_ID, List.of(known));

        verify(redisCache).put(eq(SEEN_KEY), any(), anyInt());
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void noRecipients_doesNotThrow() {
        noPriorState();
        when(recipientResolver.emailsForAccount(any())).thenReturn(List.of());

        service.notifyNewAlerts(ACCOUNT_ID, List.of(alert("QUOTA", "VPC limit")));

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void emptyAlertList_sendsNothing() {
        noPriorState();

        service.notifyNewAlerts(ACCOUNT_ID, List.of());

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    // ── Bounding ──────────────────────────────────────────────────────────────

    @Test
    void twoThousandNewAlerts_producesOneBoundedEmail() {
        // The GCP customer's situation, reproduced on the AWS path: 2000 findings must not
        // become a 2000-row email.
        noPriorState();
        List<AlertDto> many = new java.util.ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            many.add(alert("QUOTA", "finding-" + i));
        }

        service.notifyNewAlerts(ACCOUNT_ID, many);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(anyString(), anyString(), body.capture());

        String html = body.getValue();
        assertThat(html).contains("finding-0");
        assertThat(html).doesNotContain("finding-1999");
        assertThat(html).contains("and 1980 more new alerts");
        assertThat(html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(102_000);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void seen(List<String> keys) {
        when(redisCache.get(eq(SEEN_KEY), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Optional.of(keys));
    }

    @SuppressWarnings("unchecked")
    private void noPriorState() {
        when(redisCache.get(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Optional.empty());
    }

    private AlertDto alert(String type, String name) {
        AlertDto dto = new AlertDto();
        dto.setType(type);
        dto.setName(name);
        dto.setDescription(name + " description");
        dto.setService("EC2");
        dto.setRegion("us-east-1");
        dto.setStatus("WARNING");
        return dto;
    }
}
