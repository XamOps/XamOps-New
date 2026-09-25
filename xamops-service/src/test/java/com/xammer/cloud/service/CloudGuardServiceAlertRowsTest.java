package com.xammer.cloud.service;

import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.AlertErrorDto;
import com.xammer.cloud.dto.DashboardData;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.ec2.model.ServiceManaged;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the AWS alert list that are decided without calling AWS: which cost anomalies are
 * still alerts and how severe they are, how NAT gateways are counted against their per-zone
 * limit, and how a failed source is reported.
 */
class CloudGuardServiceAlertRowsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    // ── Cost anomalies ────────────────────────────────────────────────────────

    @Test
    void anomalyThatEndedWeeksAgo_isNotAnAlert() {
        DashboardData.CostAnomaly old = anomaly("a-old", "Amazon EC2", 500, 100,
                TODAY.minusDays(40), TODAY.minusDays(20));

        assertThat(CloudGuardService.anomalyAlerts(List.of(old), TODAY)).isEmpty();
    }

    @Test
    void anomalyThatStartedLongAgoButIsStillRunning_isAnAlert() {
        // Judged by its end date: a spike that began a month ago and ran until yesterday is current.
        DashboardData.CostAnomaly running = anomaly("a-run", "Amazon EC2", 500, 100,
                TODAY.minusDays(30), TODAY.minusDays(1));

        assertThat(CloudGuardService.anomalyAlerts(List.of(running), TODAY)).hasSize(1);
    }

    @Test
    void severityFollowsTheSizeOfTheSpike() {
        // Change ratio = (expected + unexpected) / expected.
        AlertDto sixfold = only(anomaly("a1", "S3", 500, 100, TODAY.minusDays(2), TODAY.minusDays(1)));
        AlertDto threefold = only(anomaly("a2", "S3", 200, 100, TODAY.minusDays(2), TODAY.minusDays(1)));
        AlertDto slight = only(anomaly("a3", "S3", 20, 100, TODAY.minusDays(2), TODAY.minusDays(1)));

        assertThat(sixfold.getSeverity()).isEqualTo("CRITICAL");
        assertThat(sixfold.getStatus()).isEqualTo("CRITICAL");
        assertThat(threefold.getSeverity()).isEqualTo("HIGH");
        assertThat(threefold.getStatus()).isEqualTo("WARNING");
        assertThat(slight.getSeverity()).isEqualTo("MEDIUM");
    }

    @Test
    void anomalyWithNoExpectedSpend_isMediumNotCritical() {
        AlertDto unknown = only(anomaly("a4", "S3", 5000, 0, TODAY.minusDays(1), TODAY.minusDays(1)));

        assertThat(unknown.getSeverity()).isEqualTo("MEDIUM");
        assertThat(unknown.getDescription()).contains("unexpected spend of $5,000.00");
    }

    @Test
    void descriptionStatesWhatTheSpendWasMeasuredAgainst() {
        AlertDto alert = only(anomaly("a5", "Amazon RDS", 400, 100, TODAY.minusDays(3), TODAY.minusDays(1)));

        assertThat(alert.getDescription())
                .contains("Amazon RDS")
                .contains("$400.00 more than the expected $100.00")
                .contains("5.0× normal");
        assertThat(alert.getCurrencySymbol()).isEqualTo("$");
    }

    @Test
    void twoSpikesOnOneService_areTwoAlertsToTheDigest() {
        // The digest de-duplicates on type, name, service, region and status. With a constant name
        // the second spike on a service matched the first and was never emailed.
        List<AlertDto> alerts = CloudGuardService.anomalyAlerts(List.of(
                anomaly("a6", "Amazon EC2", 600, 100, TODAY.minusDays(9), TODAY.minusDays(8)),
                anomaly("a7", "Amazon EC2", 600, 100, TODAY.minusDays(2), TODAY.minusDays(1))), TODAY);

        assertThat(alerts).hasSize(2);
        assertThat(AlertNotificationDeduplicator.alertKey(alerts.get(0)))
                .isNotEqualTo(AlertNotificationDeduplicator.alertKey(alerts.get(1)));
    }

    @Test
    void spikeOfAFewCents_isNotAnAlert() {
        // Real rows from the xammer-local account: CloudWatch Events +$0.01, AWS Lambda +$0.06.
        List<AlertDto> alerts = CloudGuardService.anomalyAlerts(List.of(
                anomaly("a8", "CloudWatch Events", 0.01, 0, TODAY.minusDays(7), TODAY.minusDays(7)),
                anomaly("a9", "AWS Lambda", 0.06, 0, TODAY.minusDays(6), TODAY.minusDays(1))), TODAY);

        assertThat(alerts).isEmpty();
    }

    @Test
    void smallSpikeWithALargeMultiple_isMediumNotCritical() {
        // $5 over an expected $0.50 is elevenfold, but five dollars is not an emergency.
        AlertDto alert = only(anomaly("a10", "Amazon S3", 5, 0.5, TODAY.minusDays(2), TODAY.minusDays(1)));

        assertThat(alert.getSeverity()).isEqualTo("MEDIUM");
        assertThat(alert.getStatus()).isEqualTo("WARNING");
    }

    @Test
    void nullAnomalyList_isNoAlerts() {
        assertThat(CloudGuardService.anomalyAlerts(null, TODAY)).isEmpty();
    }

    // ── NAT gateways ─────────────────────────────────────────────────────────

    @Test
    void natGatewaysAreCountedPerZone_notPerRegion() {
        // Two per zone across three zones is 6 in the region but at most 2 in any zone — well
        // inside a per-zone limit of 5, and previously reported as 6 of 5.
        List<NatGateway> gateways = List.of(
                nat("subnet-a1"), nat("subnet-a2"),
                nat("subnet-b1"), nat("subnet-b2"),
                nat("subnet-c1"), nat("subnet-c2"));
        Map<String, String> azBySubnet = Map.of(
                "subnet-a1", "us-east-1a", "subnet-a2", "us-east-1a",
                "subnet-b1", "us-east-1b", "subnet-b2", "us-east-1b",
                "subnet-c1", "us-east-1c", "subnet-c2", "us-east-1c");

        Map<String, Long> counts = CloudGuardService.natCountsByAz(gateways, azBySubnet);

        assertThat(counts).containsExactly(
                Map.entry("us-east-1a", 2L), Map.entry("us-east-1b", 2L), Map.entry("us-east-1c", 2L));
    }

    // ── Elastic IPs ──────────────────────────────────────────────────────────

    @Test
    void serviceManagedElasticIps_doNotCountAgainstTheQuota() {
        // xammer-local ap-south-1: 13 addresses, 8 of them allocated by ALBs, limit 5. Counting all
        // 13 reported 260%, which AWS cannot produce; the addresses that count are the other 5.
        List<Address> addresses = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            addresses.add(Address.builder().serviceManaged(ServiceManaged.ALB).build());
        }
        for (int i = 0; i < 5; i++) {
            addresses.add(Address.builder().build());
        }

        assertThat(CloudGuardService.countedAgainstQuota(addresses)).isEqualTo(5);
    }

    // ── Source errors ────────────────────────────────────────────────────────

    @Test
    void accessDenied_isReportedAsAPermissionProblem() {
        AlertErrorDto error = CloudGuardService.sourceError("Security Hub", new CompletionException(
                new RuntimeException("User is not authorized to perform: securityhub:GetFindings (AccessDenied)")));

        assertThat(error.getService()).isEqualTo("Security Hub");
        assertThat(error.getErrorType()).isEqualTo("PERMISSION_DENIED");
        assertThat(error.getMessage()).contains("not allowed").contains("securityhub:GetFindings");
    }

    @Test
    void expiredCredentials_areCalledThatNotAPermissionProblem() {
        // Today's failure on xammer-local: every check failed with this STS error.
        AlertErrorDto error = CloudGuardService.sourceError("Cost anomalies", new CompletionException(
                new RuntimeException("The security token included in the request is expired (Service: Sts, Status Code: 403)")));

        assertThat(error.getErrorType()).isEqualTo("CREDENTIALS_EXPIRED");
        assertThat(error.getMessage()).contains("have expired");
    }

    @Test
    void otherFailures_areApiErrorsWithTheUnderlyingMessage() {
        AlertErrorDto error = CloudGuardService.sourceError("VPC quotas",
                new IllegalStateException("VPC quotas could not be read in any region: connection reset"));

        assertThat(error.getErrorType()).isEqualTo("API_ERROR");
        assertThat(error.getMessage()).contains("connection reset");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DashboardData.CostAnomaly anomaly(String id, String service, double unexpected,
            double expected, LocalDate start, LocalDate end) {
        return new DashboardData.CostAnomaly(id, service, unexpected, start, end, expected);
    }

    private static AlertDto only(DashboardData.CostAnomaly anomaly) {
        List<AlertDto> alerts = CloudGuardService.anomalyAlerts(List.of(anomaly), TODAY);
        assertThat(alerts).hasSize(1);
        return alerts.get(0);
    }

    private static NatGateway nat(String subnetId) {
        return NatGateway.builder().subnetId(subnetId).build();
    }
}
