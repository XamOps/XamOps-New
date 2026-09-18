package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SecurityNotificationPreference;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.repository.SecurityNotificationPreferenceRepository;
import com.xammer.cloud.service.security.SecuritySeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The per-client digest severity threshold.
 *
 * <p>Two properties matter beyond the obvious filtering. The default has to admit everything, or
 * deploying this silently stops sending customers the quota warnings they get today. And the
 * result has to come back worst-first, because the email caps its itemised rows and a cap over
 * an unsorted list can bury the one critical finding behind twenty informational ones.
 */
class AlertDigestSeverityGateTest {

    @Mock private SecurityNotificationPreferenceRepository preferenceRepository;

    private AlertDigestSeverityGate gate;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        gate = new AlertDigestSeverityGate(preferenceRepository);

        Client client = mock(Client.class);
        when(client.getId()).thenReturn(7L);
        account = mock(CloudAccount.class);
        when(account.getClient()).thenReturn(client);
    }

    // ── Default behaviour ─────────────────────────────────────────────────────

    @Test
    void noPreferenceRow_admitsEverySeverity() {
        // The deploy-safety property: absence of a row must not mean absence of alerts.
        when(preferenceRepository.findById(7L)).thenReturn(Optional.empty());

        List<AlertDto> kept = gate.notifiable(account, List.of(
                alert("CRITICAL"), alert("WARNING"), alert("OK")));

        assertThat(kept).hasSize(3);
    }

    @Test
    void defaultThresholdIsTheFloorOfTheVocabulary() {
        when(preferenceRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThat(gate.thresholdFor(account)).isEqualTo(SecuritySeverity.INFORMATIONAL);
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    @Test
    void criticalOnlyThreshold_dropsEverythingBelow() {
        // This is the capability that did not exist before: "only email me on critical".
        givenThreshold("CRITICAL");

        List<AlertDto> kept = gate.notifiable(account, List.of(
                alert("CRITICAL"), alert("HIGH"), alert("WARNING"), alert("OK")));

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getSeverity()).isEqualTo(SecuritySeverity.CRITICAL);
    }

    @Test
    void highThreshold_keepsCriticalAndHigh() {
        givenThreshold("HIGH");

        List<AlertDto> kept = gate.notifiable(account, List.of(
                alert("CRITICAL"), alert("HIGH"), alert("WARNING")));

        assertThat(kept).extracting(AlertDto::getSeverity)
                .containsExactly(SecuritySeverity.CRITICAL, SecuritySeverity.HIGH);
    }

    @Test
    void everythingBelowThreshold_returnsEmptySoNoEmailIsSent() {
        givenThreshold("CRITICAL");

        assertThat(gate.notifiable(account, List.of(alert("WARNING"), alert("OK")))).isEmpty();
    }

    @Test
    void quotaWarningSurvivesAMediumThreshold() {
        // WARNING normalises to MEDIUM, so a MEDIUM threshold must still admit quota warnings.
        givenThreshold("MEDIUM");

        assertThat(gate.notifiable(account, List.of(alert("WARNING")))).hasSize(1);
    }

    @Test
    void healthyQuotaIsDroppedByAMediumThreshold() {
        givenThreshold("MEDIUM");

        assertThat(gate.notifiable(account, List.of(alert("OK")))).isEmpty();
    }

    // ── Ordering ──────────────────────────────────────────────────────────────

    @Test
    void resultIsWorstFirst() {
        when(preferenceRepository.findById(7L)).thenReturn(Optional.empty());

        List<AlertDto> kept = gate.notifiable(account, List.of(
                alert("OK"), alert("CRITICAL"), alert("WARNING"), alert("HIGH")));

        assertThat(kept).extracting(AlertDto::getSeverity).containsExactly(
                SecuritySeverity.CRITICAL, SecuritySeverity.HIGH,
                SecuritySeverity.MEDIUM, SecuritySeverity.INFORMATIONAL);
    }

    @Test
    void aCriticalBuriedInManyLowAlertsSortsToTheFront() {
        // The reason sorting belongs here: the email itemises only the first 20.
        when(preferenceRepository.findById(7L)).thenReturn(Optional.empty());
        List<AlertDto> input = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            input.add(alert("OK"));
        }
        input.add(alert("CRITICAL"));

        List<AlertDto> kept = gate.notifiable(account, input);

        assertThat(kept.get(0).getSeverity()).isEqualTo(SecuritySeverity.CRITICAL);
    }

    // ── Failure modes ─────────────────────────────────────────────────────────

    @Test
    void repositoryFailure_allowsEverythingRatherThanSilencingTheAccount() {
        // A database problem must not turn into "this customer stopped hearing about alerts".
        when(preferenceRepository.findById(anyLong()))
                .thenThrow(new RuntimeException("connection pool exhausted"));

        assertThat(gate.thresholdFor(account)).isEqualTo(SecuritySeverity.INFORMATIONAL);
        assertThat(gate.notifiable(account, List.of(alert("OK")))).hasSize(1);
    }

    @Test
    void accountWithoutAClient_fallsBackToTheDefault() {
        CloudAccount orphan = mock(CloudAccount.class);
        when(orphan.getClient()).thenReturn(null);

        assertThat(gate.thresholdFor(orphan)).isEqualTo(SecuritySeverity.INFORMATIONAL);
        verify(preferenceRepository, never()).findById(anyLong());
    }

    @Test
    void nullAccount_doesNotThrow() {
        assertThat(gate.thresholdFor(null)).isEqualTo(SecuritySeverity.INFORMATIONAL);
        assertThat(gate.notifiable(null, List.of(alert("CRITICAL")))).hasSize(1);
    }

    @Test
    void nullAndEmptyAlertLists_returnEmpty() {
        assertThat(gate.notifiable(account, null)).isEmpty();
        assertThat(gate.notifiable(account, List.of())).isEmpty();
    }

    @Test
    void unrecognisedStoredThreshold_isNormalisedRatherThanMatchingNothing() {
        // A threshold persisted as junk must not leave the client with no notifications.
        givenThreshold("not-a-severity");

        assertThat(gate.thresholdFor(account)).isEqualTo(SecuritySeverity.MEDIUM);
    }

    // ── worstOf ───────────────────────────────────────────────────────────────

    @Test
    void worstOfReportsTheTopSeverityPresent() {
        assertThat(AlertDigestSeverityGate.worstOf(List.of(alert("OK"), alert("CRITICAL"))))
                .isEqualTo(SecuritySeverity.CRITICAL);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void givenThreshold(String threshold) {
        SecurityNotificationPreference prefs = SecurityNotificationPreference.defaults(7L);
        prefs.setDigestSeverityThreshold(threshold);
        when(preferenceRepository.findById(7L)).thenReturn(Optional.of(prefs));
    }

    private AlertDto alert(String status) {
        return new AlertDto("id-" + status, "EC2", status + " alert", "desc",
                status, 0, 0, "QUOTA", "us-east-1");
    }
}
