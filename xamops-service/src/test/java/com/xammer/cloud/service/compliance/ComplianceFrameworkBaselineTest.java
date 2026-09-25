package com.xammer.cloud.service.compliance;

import com.xammer.cloud.service.security.ProwlerFrameworks;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The framework clause denominator.
 *
 * <p>
 * The point of this table is to let a card say "43 of 62 clauses" instead of "43 clauses mapped",
 * so the tests that matter most are the ones about <em>not</em> answering: an unknown framework, an
 * unknown provider, or a version that does not match the one on record must all produce null. A
 * missing denominator degrades a card to its old behaviour, whereas a wrong one is a false
 * statement about audit scope.
 */
class ComplianceFrameworkBaselineTest {

    // ── Known frameworks resolve ─────────────────────────────────────────────

    @Test
    void resolvesTheVersionEachProviderActuallyScans() {
        // AWS is pinned to CIS 3.0, GCP to CIS 2.0, Azure to CIS 3.0 — three different counts for
        // the same family, which is exactly why the lookup is provider-scoped.
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "CIS")).isEqualTo(62);
        assertThat(ComplianceFrameworkBaseline.requirementCount("GCP", "CIS")).isEqualTo(84);
        assertThat(ComplianceFrameworkBaseline.requirementCount("Azure", "CIS")).isEqualTo(159);
    }

    @Test
    void handlesTheVersionedNamesTheScannerReports() {
        // Findings label mappings like "CIS-3.0", not like the --compliance id "cis_3.0_aws".
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "CIS-3.0")).isEqualTo(62);
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "ISO-27001")).isEqualTo(79);
        assertThat(ComplianceFrameworkBaseline.requirementCount("GCP", "ISO-27001")).isEqualTo(92);
    }

    @Test
    void distinguishesPciVersionsAcrossProviders() {
        // AWS scans PCI 3.2.1 while GCP and Azure scan PCI 4.0. Collapsing these would put a
        // 147-requirement denominator under a 1672-requirement framework.
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "PCI-DSS")).isEqualTo(147);
        assertThat(ComplianceFrameworkBaseline.requirementCount("GCP", "PCI-DSS")).isEqualTo(1672);
        assertThat(ComplianceFrameworkBaseline.requirementCount("Azure", "PCI-DSS")).isEqualTo(1669);
    }

    @Test
    void isCaseInsensitiveOnProvider() {
        assertThat(ComplianceFrameworkBaseline.requirementCount("aws", "SOC2")).isEqualTo(27);
        assertThat(ComplianceFrameworkBaseline.requirementCount("AZURE", "SOC2")).isEqualTo(26);
    }

    @Test
    void picksFsbpBeforeAnyOtherFamily() {
        // "aws_foundational_security_best_practices_aws" contains no CIS/SOC/PCI token, but the
        // ordering still has to be asserted: a later broadening of another pattern could steal it.
        assertThat(ComplianceFrameworkBaseline.family(
                "aws_foundational_security_best_practices_aws")).isEqualTo("FSBP");
        assertThat(ComplianceFrameworkBaseline.requirementCount(
                "AWS", "AWS-Foundational-Security-Best-Practices")).isEqualTo(286);
    }

    @Test
    void separatesNist800FromNistCsf() {
        assertThat(ComplianceFrameworkBaseline.family("NIST-800-53")).isEqualTo("NIST800");
        assertThat(ComplianceFrameworkBaseline.family("NIST-CSF-1.1")).isEqualTo("NIST_CSF");
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "NIST-CSF")).isEqualTo(56);
        // NIST 800-53 is not in any provider's scan list, so it has no denominator to offer.
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "NIST-800-53")).isNull();
    }

    // ── Everything uncertain returns null ────────────────────────────────────

    @Test
    void returnsNullForAFrameworkTheProviderDoesNotScan() {
        // HIPAA is scanned on GCP and Azure but not on AWS.
        assertThat(ComplianceFrameworkBaseline.requirementCount("GCP", "HIPAA")).isEqualTo(23);
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "HIPAA")).isNull();
    }

    @Test
    void returnsNullWhenTheReportedVersionContradictsTheOneOnRecord() {
        // A Prowler upgrade moving AWS from CIS 3.0 to 4.0 must blank the denominator rather than
        // keep reporting 62 requirements against a different benchmark.
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "CIS-4.0")).isNull();
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "PCI-DSS-4.0")).isNull();
        assertThat(ComplianceFrameworkBaseline.requirementCount("GCP", "PCI-DSS-3.2.1")).isNull();
    }

    @Test
    void returnsNullForUnknownProvidersFrameworksAndNulls() {
        assertThat(ComplianceFrameworkBaseline.requirementCount("ORACLE", "CIS")).isNull();
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "GDPR")).isNull();
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "MITRE")).isNull();
        assertThat(ComplianceFrameworkBaseline.requirementCount(null, "CIS")).isNull();
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", null)).isNull();
        assertThat(ComplianceFrameworkBaseline.requirementCount("AWS", "")).isNull();
    }

    @Test
    void recognisesNoFamilyInAnUnrelatedString() {
        assertThat(ComplianceFrameworkBaseline.family("Best Practices")).isNull();
        assertThat(ComplianceFrameworkBaseline.family("")).isNull();
        assertThat(ComplianceFrameworkBaseline.family(null)).isNull();
    }

    // ── Kept in step with the scan configuration ──────────────────────────────

    @Test
    void coversEveryFrameworkEachProviderIsActuallyAskedToScan() {
        assertEveryFrameworkHasABaseline("AWS", ProwlerFrameworks.AWS);
        assertEveryFrameworkHasABaseline("GCP", ProwlerFrameworks.GCP);
        assertEveryFrameworkHasABaseline("Azure", ProwlerFrameworks.AZURE);
    }

    /**
     * Fails when a framework is added to {@link ProwlerFrameworks} without a requirement count,
     * which is the drift that would otherwise show up as a card silently losing its denominator.
     */
    private void assertEveryFrameworkHasABaseline(String provider, String[] frameworkIds) {
        for (String id : frameworkIds) {
            assertThat(ComplianceFrameworkBaseline.requirementCount(provider, id))
                    .as("no requirement count on record for %s framework '%s'", provider, id)
                    .isNotNull()
                    .isPositive();
        }
    }

    @Test
    void reportsWhichProwlerFrameworkAMappingResolvedTo() {
        assertThat(ComplianceFrameworkBaseline.resolvedFrameworkId("AWS", "CIS-3.0"))
                .isEqualTo("cis_3.0_aws");
        assertThat(ComplianceFrameworkBaseline.resolvedFrameworkId("Azure", "PCI-DSS"))
                .isEqualTo("pci_4.0_azure");
        assertThat(ComplianceFrameworkBaseline.resolvedFrameworkId("AWS", "GDPR")).isNull();
    }
}
