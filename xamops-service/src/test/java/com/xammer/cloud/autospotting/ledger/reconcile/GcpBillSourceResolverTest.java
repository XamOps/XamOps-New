package com.xammer.cloud.autospotting.ledger.reconcile;

import com.xammer.cloud.domain.BillingSource;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.ledger.calc.GcpSudConfig;
import com.xammer.cloud.service.autospotting.ledger.reconcile.GcpBillSourceResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PD-06, GR-11: the bill source is the read-side rule; a CSP dataset project stays MEASURED_ONLY, CSP_DATASET_PENDING. */
class GcpBillSourceResolverTest {
    private static CloudAccount account(BillingSource source, boolean cspManaged) {
        CloudAccount a = new CloudAccount();
        a.setBillingSource(source);
        a.setCspManaged(cspManaged);
        return a;
    }

    @Test
    void ownAndXammerExportReadTheExportTable() {
        for (BillingSource s : new BillingSource[] {BillingSource.OWN_EXPORT, BillingSource.XAMMER_EXPORT}) {
            var r = GcpBillSourceResolver.resolve(account(s, false));
            assertThat(r.readsExport()).as(s.name()).isTrue();
            assertThat(r.measuredOnly()).isFalse();
            assertThat(r.reason()).isNull();
        }
    }

    @Test
    void cspDatasetIsMeasuredOnlyWithCspDatasetPending() {
        var r = GcpBillSourceResolver.resolve(account(BillingSource.XAMMER_CSP_DATASET, false));
        assertThat(r.readsExport()).isFalse();
        assertThat(r.measuredOnly()).isTrue();
        assertThat(r.reason()).isEqualTo("CSP_DATASET_PENDING");
    }

    @Test
    void aNullColumnFallsBackToCspManaged_theSameRuleAsResolveBillingSource() {
        assertThat(GcpBillSourceResolver.resolve(account(null, true)).reason()).isEqualTo("CSP_DATASET_PENDING");
        assertThat(GcpBillSourceResolver.resolve(account(null, false)).source()).isEqualTo(BillingSource.OWN_EXPORT);
        assertThat(GcpBillSourceResolver.resolve((String) null, true).source()).isEqualTo(BillingSource.XAMMER_CSP_DATASET);
        assertThat(GcpBillSourceResolver.resolve("XAMMER_EXPORT", true).measuredOnly()).isFalse();
        // Both paths agree for every combination.
        for (BillingSource s : new BillingSource[] {null, BillingSource.OWN_EXPORT, BillingSource.XAMMER_EXPORT,
                BillingSource.XAMMER_CSP_DATASET}) {
            for (boolean csp : new boolean[] {false, true}) {
                assertThat(GcpBillSourceResolver.resolve(s == null ? null : s.name(), csp))
                        .isEqualTo(GcpBillSourceResolver.resolve(account(s, csp)));
            }
        }
    }

    @Test
    void sudConfigCarriesTheDesignTiersAndNoEntryForUnlistedSeries() {
        var n2 = GcpSudConfig.v1().forSeries("n2").orElseThrow();
        assertThat(n2.maxRate()).isEqualByComparingTo("0.20");
        assertThat(n2.tiers()).extracting(Object::toString).containsExactly("1", "0.866667", "0.733333", "0.6");
        assertThat(GcpSudConfig.v1().forSeries("e2").orElseThrow().maxRate().signum()).isZero();
        assertThat(GcpSudConfig.v1().forSeries("m2")).isEmpty();
    }
}
