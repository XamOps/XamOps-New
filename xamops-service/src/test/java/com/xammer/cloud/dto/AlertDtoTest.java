package com.xammer.cloud.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.security.SecuritySeverity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@link AlertDto} resolves severity.
 *
 * <p>The field exists because {@code status} carries two vocabularies: quota checks put
 * CRITICAL/WARNING/OK in it while Security Hub and GCP Security Command Center put a real
 * finding severity in the same slot. A threshold cannot be written against that, so severity is
 * resolved separately — derived from status when nobody set it, explicit when they did.
 */
class AlertDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Derivation ────────────────────────────────────────────────────────────

    @Test
    void securityFindingStatusIsAlreadyASeverityAndPassesThrough() {
        AlertDto alert = new AlertDto("id", "S3", "Public bucket", "desc",
                "CRITICAL", 0, 0, "SECURITY", "us-east-1");

        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.CRITICAL);
    }

    @Test
    void quotaWarningBecomesMedium() {
        AlertDto alert = new AlertDto("id", "VPC", "VPC limit", "desc",
                "WARNING", 80, 100, "QUOTA", "us-east-1");

        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.MEDIUM);
    }

    @Test
    void healthyQuotaBecomesInformational() {
        // A quota sitting under its limit must not rank alongside a real warning.
        AlertDto alert = new AlertDto("id", "VPC", "VPC limit", "desc",
                "OK", 10, 100, "QUOTA", "us-east-1");

        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.INFORMATIONAL);
    }

    @Test
    void severityIsNeverNull() {
        assertThat(new AlertDto().getSeverity()).isNotNull();
        assertThat(new AlertDto("id", "svc", "n", "d", null, 0, 0, "QUOTA", "r").getSeverity())
                .isNotNull();
    }

    // ── Explicit override ─────────────────────────────────────────────────────

    @Test
    void explicitSeverityWinsOverStatus() {
        // Azure's case: status is a folded three-band display state, the real severity is finer.
        AlertDto alert = new AlertDto("id", "VM", "Alert", "desc",
                "CRITICAL", 0, 0, "ANOMALY", "eastus");
        alert.setSeverity("Error");

        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.HIGH);
        assertThat(alert.getStatus()).isEqualTo("CRITICAL");
    }

    @Test
    void explicitSeverityIsNormalisedOnTheWayIn() {
        // An unrecognised provider value must not be stored verbatim and then match nothing.
        AlertDto alert = new AlertDto();
        alert.setSeverity("sEv-NoNsEnSe");

        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.MEDIUM);
    }

    @Test
    void settingSeverityBackToNullFallsBackToStatus() {
        AlertDto alert = new AlertDto();
        alert.setStatus("CRITICAL");
        alert.setSeverity("LOW");
        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.LOW);

        alert.setSeverity(null);
        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.CRITICAL);
    }

    @Test
    void setterOrderDoesNotMatter() {
        // Deriving inside setStatus would make this order-dependent, and the setter-based build
        // sites in the codebase set status and severity in whichever order reads best.
        AlertDto severityFirst = new AlertDto();
        severityFirst.setSeverity("LOW");
        severityFirst.setStatus("CRITICAL");

        AlertDto statusFirst = new AlertDto();
        statusFirst.setStatus("CRITICAL");
        statusFirst.setSeverity("LOW");

        assertThat(severityFirst.getSeverity()).isEqualTo(SecuritySeverity.LOW);
        assertThat(statusFirst.getSeverity()).isEqualTo(SecuritySeverity.LOW);
    }

    // ── Already-cached payloads ───────────────────────────────────────────────

    @Test
    void alertCachedBeforeSeverityExistedStillResolves() throws Exception {
        // Alert lists are cached in Redis and in CachedData. Rows written before this field
        // existed have no severity key; they must derive from status rather than all arriving in
        // one wrong band the moment the new code reads them.
        String legacyJson = "{\"id\":\"a\",\"service\":\"S3\",\"name\":\"Public bucket\","
                + "\"description\":\"d\",\"status\":\"CRITICAL\",\"usage\":0.0,\"limit\":0.0,"
                + "\"type\":\"SECURITY\",\"region\":\"us-east-1\"}";

        AlertDto alert = mapper.readValue(legacyJson, AlertDto.class);

        assertThat(alert.getSeverity()).isEqualTo(SecuritySeverity.CRITICAL);
    }

    @Test
    void severitySurvivesARoundTrip() throws Exception {
        AlertDto original = new AlertDto("id", "VM", "Alert", "desc",
                "CRITICAL", 0, 0, "ANOMALY", "eastus");
        original.setSeverity("Error");
        original.setCurrencySymbol("₹");

        AlertDto restored = mapper.readValue(mapper.writeValueAsString(original), AlertDto.class);

        assertThat(restored.getSeverity()).isEqualTo(SecuritySeverity.HIGH);
        assertThat(restored.getCurrencySymbol()).isEqualTo("₹");
    }

    @Test
    void currencySymbolIsAbsentUnlessTheFigureIsMoney() {
        AlertDto quota = new AlertDto("id", "VPC", "VPC limit", "desc",
                "WARNING", 80, 100, "QUOTA", "us-east-1");

        assertThat(quota.getCurrencySymbol()).isNull();
    }
}
