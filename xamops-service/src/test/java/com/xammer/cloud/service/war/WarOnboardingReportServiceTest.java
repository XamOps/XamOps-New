package com.xammer.cloud.service.war;

import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WarOnboardingReportService}.
 *
 * <p>
 * Focused on the send/skip decision, which is where the product rules live: a clean
 * account gets silence, a blocked scan still gets told, and nothing sends twice.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarOnboardingReportServiceTest {

    private static final Long ACCOUNT_ID = 42L;

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private UserRepository userRepository;
    @Mock private CachedDataRepository cachedDataRepository;
    @Mock private WarScanDispatcher scanDispatcher;
    @Mock private WarEmailBuilder emailBuilder;
    @Mock private EmailService emailService;

    private WarOnboardingReportService service;

    @BeforeEach
    void setUp() {
        service = new WarOnboardingReportService(cloudAccountRepository, userRepository,
                cachedDataRepository, scanDispatcher, emailBuilder, emailService);
        // @Value is not processed outside a Spring context.
        ReflectionTestUtils.setField(service, "scanTimeoutMinutes", 1L);

        when(cloudAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(userRepository.findByClientId(7L)).thenReturn(List.of(user("ops@acme.com")));
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());
        when(emailBuilder.subject(any(), anyString())).thenReturn("subject");
        when(emailBuilder.build(any(), anyString())).thenReturn("<html></html>");
    }

    // ── Nothing to report ───────────────────────────────────────────────────

    @Test
    void cleanAccount_sendsNothing() {
        stubScan(report(true, 0, 0));

        service.deliverReport(ACCOUNT_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void cleanAccount_doesNotRecordASendMarker() {
        stubScan(report(true, 0, 0));

        service.deliverReport(ACCOUNT_ID);

        // No email went out, so nothing may be suppressed on the next connect.
        verify(cachedDataRepository, never()).save(any());
    }

    @Test
    void unverifiedControlsAloneAreNotFindings() {
        // 30 UNKNOWN but still assessable: we looked and confirmed nothing exposed.
        WarReport report = report(true, 0, 0);
        report.setStatusCounts(Map.of("PASS", 40, "FAIL", 0, "WARN", 0, "UNKNOWN", 30, "MANUAL", 16));
        stubScan(report);

        service.deliverReport(ACCOUNT_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    // ── Something to report ─────────────────────────────────────────────────

    @Test
    void oneExposedControl_sends() {
        stubScan(report(true, 1, 0));

        service.deliverReport(ACCOUNT_ID);

        verify(emailService).sendHtmlEmail(eq("ops@acme.com"), eq("subject"), eq("<html></html>"));
    }

    @Test
    void weakenedOnly_stillCountsAsAFinding() {
        stubScan(report(true, 0, 3));

        service.deliverReport(ACCOUNT_ID);

        verify(emailService, times(1)).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void blockedScan_sendsNothing() {
        // An incomplete scan has no reliable picture to report, even though it has
        // findings-shaped fields. Its forced score of 0 must never reach a customer.
        stubScan(report(false, 0, 0));

        service.deliverReport(ACCOUNT_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(cachedDataRepository, never()).save(any());
    }

    @Test
    void incompleteScanSendsNothingEvenWhenItFoundExposures() {
        // Two real failures, but below the coverage gate — too small a sample to rate
        // the account, so it is not sent as a report.
        stubScan(report(false, 2, 1));

        service.deliverReport(ACCOUNT_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void successfulSend_recordsTheMarker() {
        stubScan(report(true, 2, 1));

        service.deliverReport(ACCOUNT_ID);

        verify(cachedDataRepository).save(any(CachedData.class));
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    @Test
    void alreadySentWithinTheWindow_skips() {
        when(cachedDataRepository.findById("WAR_ONBOARDING_EMAIL::" + ACCOUNT_ID))
                .thenReturn(Optional.of(new CachedData(
                        "WAR_ONBOARDING_EMAIL::" + ACCOUNT_ID, "{}", LocalDateTime.now().minusHours(2))));
        stubScan(report(true, 5, 5));

        service.deliverReport(ACCOUNT_ID);

        verify(scanDispatcher, never()).scan(any(), anyBoolean());
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void anOldMarkerDoesNotSuppressANewSend() {
        when(cachedDataRepository.findById("WAR_ONBOARDING_EMAIL::" + ACCOUNT_ID))
                .thenReturn(Optional.of(new CachedData(
                        "WAR_ONBOARDING_EMAIL::" + ACCOUNT_ID, "{}", LocalDateTime.now().minusDays(3))));
        stubScan(report(true, 5, 5));

        service.deliverReport(ACCOUNT_ID);

        verify(emailService, times(1)).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void accountNotConnected_neverScans() {
        CloudAccount pending = account();
        pending.setStatus("PENDING");
        when(cloudAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(pending));

        service.deliverReport(ACCOUNT_ID);

        verify(scanDispatcher, never()).scan(any(), anyBoolean());
    }

    @Test
    void noRecipients_neverScans() {
        when(userRepository.findByClientId(7L)).thenReturn(List.of());

        service.deliverReport(ACCOUNT_ID);

        verify(scanDispatcher, never()).scan(any(), anyBoolean());
    }

    @Test
    void unsupportedProvider_isSkippedQuietly() {
        when(scanDispatcher.scan(any(), anyBoolean())).thenReturn(
                CompletableFuture.failedFuture(new UnsupportedOperationException("no baseline")));

        service.deliverReport(ACCOUNT_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void aFailedScanSendsNothingAndDoesNotThrow() {
        when(scanDispatcher.scan(any(), anyBoolean())).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("boom")));

        service.deliverReport(ACCOUNT_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(cachedDataRepository, never()).save(any());
    }

    @Test
    void aScanThatNeverReturnsSendsNothing() {
        // Timeout shortened to 0 so this exercises the real timeout branch instantly
        // instead of waiting out the 12-minute production budget.
        ReflectionTestUtils.setField(service, "scanTimeoutMinutes", 0L);
        when(scanDispatcher.scan(any(), anyBoolean())).thenReturn(new CompletableFuture<>());

        service.deliverReport(ACCOUNT_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(cachedDataRepository, never()).save(any());
    }

    @Test
    void aScanReturningNullSendsNothing() {
        when(scanDispatcher.scan(any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.deliverReport(ACCOUNT_ID);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private void stubScan(WarReport report) {
        when(scanDispatcher.scan(any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(report));
    }

    private CloudAccount account() {
        Client client = new Client();
        client.setId(7L);

        CloudAccount account = new CloudAccount();
        account.setId(ACCOUNT_ID);
        account.setAccountName("Acme Prod");
        account.setProvider("AWS");
        account.setAwsAccountId("123456789012");
        account.setStatus("CONNECTED");
        account.setClient(client);
        return account;
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private WarReport report(boolean assessable, int failed, int warned) {
        return WarReport.builder()
                .accountId("123456789012")
                .accountName("Acme Prod")
                .provider("AWS")
                .generatedAt("2026-08-10 12:30 IST")
                .warScore(assessable ? 88 : 0)
                .riskLevel(assessable ? "LOW" : "UNKNOWN")
                .totalControls(86)
                .verifiedControls(assessable ? 70 : 0)
                .assessable(assessable)
                .blockedReason(assessable ? null : "ExpiredToken")
                .statusCounts(Map.of(
                        "PASS", 70 - failed - warned,
                        "FAIL", failed,
                        "WARN", warned,
                        "UNKNOWN", assessable ? 0 : 72,
                        "MANUAL", 16))
                .severityCounts(Map.of("Critical", 0, "High", 0, "Medium", 0, "Low", 0))
                .attackPaths(List.of())
                .categories(List.of())
                .findings(List.of())
                .topRisks(List.of())
                .deniedActions(List.of())
                .blockedActions(List.of())
                .scanWarnings(List.of())
                .build();
    }
}
