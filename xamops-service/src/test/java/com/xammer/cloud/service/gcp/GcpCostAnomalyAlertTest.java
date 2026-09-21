package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.NotificationRecipientResolver;
import com.xammer.cloud.permissions.PermissionGapRegistry;
import com.xammer.cloud.service.AlertDigestSeverityGate;
import com.xammer.cloud.service.AlertEmailBuilder;
import com.xammer.cloud.service.AlertNotificationDeduplicator;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.security.SecuritySeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * GCP cost anomaly alerts.
 *
 * <p>These rows used to be built from a month-over-month comparison of the project's whole bill,
 * which produced the report that the Alerts page "makes no sense": four permanent CRITICAL rows
 * on a growing account, each quoting a whole month's spend as the anomaly, the oldest of them
 * four months stale. The alerts now come from the per-service daily detector that was already in
 * the codebase serving the FinOps page.
 */
class GcpCostAnomalyAlertTest {

    private static final String PROJECT = "my-project";

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private GcpClientProvider gcpClientProvider;
    @Mock private RedisCacheService redisCache;
    @Mock private GcpDataService gcpDataService;
    @Mock private GcpCostService gcpCostService;
    @Mock private GcpSecurityService gcpSecurityService;
    @Mock private EmailService emailService;
    @Mock private CachedDataRepository cachedDataRepository;
    @Mock private NotificationRecipientResolver recipientResolver;
    @Mock private AlertNotificationDeduplicator deduplicator;
    @Mock private AlertDigestSeverityGate severityGate;
    @Mock private PermissionGapRegistry permissionGapRegistry;

    private GcpCloudGuardService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        service = new GcpCloudGuardService(
                cloudAccountRepository, gcpClientProvider, redisCache, gcpDataService,
                gcpCostService, gcpSecurityService, emailService, cachedDataRepository,
                new ObjectMapper(), recipientResolver, new AlertEmailBuilder(), deduplicator,
                severityGate, permissionGapRegistry);

        account = new CloudAccount();
        account.setGcpProjectId(PROJECT);

        when(gcpCostService.getProjectCurrencyCode(PROJECT)).thenReturn("INR");
    }

    // ── Content ───────────────────────────────────────────────────────────────

    @Test
    void alertReportsTheExcessNotTheTotalSpend() {
        // The old code put the month's entire bill in `usage`, which is what rendered as
        // "66,706,578" on the page. Only the amount above the baseline is unexpected.
        givenAnomalies(anomaly("Compute Engine", 900.0, 300.0, LocalDate.now().minusDays(2)));

        AlertDto alert = firstAlert();

        assertThat(alert.getUsage()).isEqualTo(900.0);
        assertThat(alert.getType()).isEqualTo("ANOMALY");
    }

    @Test
    void descriptionNamesTheServiceTheBaselineAndTheDate() {
        LocalDate when = LocalDate.now().minusDays(3);
        givenAnomalies(anomaly("Compute Engine", 900.0, 300.0, when));

        String description = firstAlert().getDescription();

        assertThat(description)
                .contains("Compute Engine")
                .contains(when.toString())
                .contains("300")   // the baseline it deviated from
                .contains("4.0×"); // (300 + 900) / 300
    }

    @Test
    void currencyComesFromTheProjectNotAHardCodedDollar() {
        // The page printed a "$" in front of this figure regardless of billing currency.
        givenAnomalies(anomaly("BigQuery", 500.0, 200.0, LocalDate.now().minusDays(1)));

        AlertDto alert = firstAlert();

        assertThat(alert.getCurrencySymbol()).isEqualTo("₹");
        assertThat(alert.getDescription()).contains("₹").doesNotContain("$");
    }

    // ── Severity grading ──────────────────────────────────────────────────────

    @Test
    void everyAnomalyIsNoLongerCritical() {
        // A 1.6x blip and a 20x runaway are not the same event.
        givenAnomalies(anomaly("Modest", 60.0, 100.0, LocalDate.now()));

        assertThat(firstAlert().getSeverity()).isEqualTo(SecuritySeverity.MEDIUM);
    }

    @Test
    void aRunawayServiceIsCritical() {
        givenAnomalies(anomaly("Runaway", 900.0, 100.0, LocalDate.now())); // 10x

        AlertDto alert = firstAlert();
        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.CRITICAL);
        assertThat(alert.getStatus()).isEqualTo("CRITICAL");
    }

    @Test
    void aThreeTimesSpikeIsHigh() {
        givenAnomalies(anomaly("Notable", 200.0, 100.0, LocalDate.now())); // 3x

        AlertDto alert = firstAlert();
        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.HIGH);
        // The page's own vocabulary only has CRITICAL/WARNING, so HIGH shows as WARNING.
        assertThat(alert.getStatus()).isEqualTo("WARNING");
    }

    @Test
    void missingBaselineDoesNotClaimAMultiple() {
        // A detector that supplied no baseline must not have one invented for it.
        givenAnomalies(anomaly("Unknown baseline", 500.0, 0.0, LocalDate.now()));

        AlertDto alert = firstAlert();
        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.MEDIUM);
        assertThat(alert.getDescription()).doesNotContain("×");
    }

    // ── Recency ───────────────────────────────────────────────────────────────

    @Test
    void staleAnomaliesAreNotAlertedOn() {
        // The detector looks back 60 days for the FinOps chart. A spike from seven weeks ago is
        // either explained or already paid for; listing it forever is what trains people to
        // ignore the page.
        givenAnomalies(
                anomaly("Recent", 500.0, 100.0, LocalDate.now().minusDays(2)),
                anomaly("Ancient", 500.0, 100.0, LocalDate.now().minusDays(45)));

        List<AlertDto> alerts = service.checkCostAnomalies(account, false).join().alerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getService()).isEqualTo("Recent");
    }

    @Test
    void anomalyRightOnTheBoundaryIsKept() {
        givenAnomalies(anomaly("Edge", 500.0, 100.0, LocalDate.now().minusDays(13)));

        assertThat(service.checkCostAnomalies(account, false).join().alerts()).hasSize(1);
    }

    // ── Failure modes ─────────────────────────────────────────────────────────

    @Test
    void detectorFailureYieldsNoAlertsRatherThanBlowingUpThePage() {
        when(gcpCostService.getCostAnomaliesSync(anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("BigQuery billing export unreadable"));

        assertThat(service.checkCostAnomalies(account, false).join().alerts()).isEmpty();
    }

    @Test
    void noAnomaliesYieldsNoAlerts() {
        givenAnomalies();

        assertThat(service.checkCostAnomalies(account, false).join().alerts()).isEmpty();
    }

    // ── changeRatio contract ──────────────────────────────────────────────────

    @Test
    void changeRatioIsZeroWhenBaselineIsUnknownNotOne() {
        // 1.0 would read as "exactly normal", which is a different claim from "we don't know".
        assertThat(new DashboardData.CostAnomaly("id", "svc", 500.0,
                LocalDate.now(), LocalDate.now()).getChangeRatio()).isZero();
    }

    @Test
    void changeRatioIsTotalOverBaseline() {
        assertThat(new DashboardData.CostAnomaly("id", "svc", 300.0,
                LocalDate.now(), LocalDate.now(), 100.0).getChangeRatio()).isEqualTo(4.0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void givenAnomalies(DashboardData.CostAnomaly... anomalies) {
        when(gcpCostService.getCostAnomaliesSync(anyString(), anyBoolean()))
                .thenReturn(List.of(anomalies));
    }

    private AlertDto firstAlert() {
        List<AlertDto> alerts = service.checkCostAnomalies(account, false).join().alerts();
        assertThat(alerts).isNotEmpty();
        return alerts.get(0);
    }

    private DashboardData.CostAnomaly anomaly(String service, double excess, double baseline,
            LocalDate when) {
        return new DashboardData.CostAnomaly(
                "GCP-ANOMALY-" + service, service, excess, when, when, baseline);
    }
}
