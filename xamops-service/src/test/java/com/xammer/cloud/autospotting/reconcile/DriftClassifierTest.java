package com.xammer.cloud.autospotting.reconcile;

import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.DriftStatus;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.core.reconcile.DriftClassifier;
import com.xammer.cloud.service.autospotting.core.reconcile.GoneDetector;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift and GONE classification (design-core.md §5.2, plan-phase-08.md task 8.2, 8.3 verify). One
 * case per DR code; after a fallback entry two sweeps classify NONE; a customer edit during FALLBACK
 * is DR-2; a single NOT_FOUND then FOUND is not GONE (AC-CMP-02 as designed under C-04).
 */
class DriftClassifierTest {

    private final DriftClassifier classifier = new DriftClassifier();
    private final GoneDetector goneDetector = new GoneDetector();

    private static SpotGroup group(GroupState state, ConfigMode mode, String desiredFp, String fallbackFp) {
        SpotGroup g = new SpotGroup();
        g.setId(UUID.randomUUID());
        g.setProvider(Provider.AWS);
        g.setCloudAccountId(1L);
        g.setClientId(1L);
        g.setGroupKey("us-east-1/asg");
        g.setState(state);
        g.setConfigMode(mode);
        g.setDriftStatus(DriftStatus.NONE);
        g.setDesiredFingerprint(desiredFp);
        g.setFallbackFingerprint(fallbackFp);
        g.setStateChangedAt(Instant.now());
        return g;
    }

    private static GroupStatus status(boolean found, String liveFp, boolean foreign) {
        return new GroupStatus(found, liveFp, "{\"h\":true}", "{\"c\":2}", foreign);
    }

    @Test
    void none_whenLiveMatchesDesired() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        DriftClassifier.Classification c = classifier.classify(g, status(true, "fp-managed", false),
                false, false, false, false, false, false);
        assertThat(c.code()).isEqualTo(DriftClassifier.Code.NONE);
        assertThat(c.materiality()).isEqualTo(DriftStatus.NONE);
        assertThat(c.pausesDrift()).isFalse();
    }

    @Test
    void dr1_capacityBounds_minor_alertsWhenDesiredAtMostOne() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        DriftClassifier.Classification c = classifier.classify(g, status(true, "fp-managed", false),
                false, true, true, false, false, false);
        assertThat(c.code()).isEqualTo(DriftClassifier.Code.DR1_CAPACITY);
        assertThat(c.materiality()).isEqualTo(DriftStatus.MINOR);
        assertThat(c.alert()).isTrue();
        assertThat(c.pausesDrift()).isFalse();
    }

    @Test
    void dr2_managedFieldChanged_material_pausesDrift() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        DriftClassifier.Classification c = classifier.classify(g, status(true, "fp-DIFFERENT", false),
                false, false, false, false, false, false);
        assertThat(c.code()).isEqualTo(DriftClassifier.Code.DR2_MANAGED_FIELD);
        assertThat(c.materiality()).isEqualTo(DriftStatus.MATERIAL);
        assertThat(c.pausesDrift()).isTrue();
        assertThat(c.alert()).isTrue();
    }

    @Test
    void dr3_markerRemoved_minor() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        DriftClassifier.Classification c = classifier.classify(g, status(true, "fp-managed", false),
                false, false, false, true, false, false);
        assertThat(c.code()).isEqualTo(DriftClassifier.Code.DR3_MARKER_REMOVED);
        assertThat(c.materiality()).isEqualTo(DriftStatus.MINOR);
        assertThat(c.pausesDrift()).isFalse();
    }

    @Test
    void dr4_ownedArtifactMissing_material_pausesDrift() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        DriftClassifier.Classification c = classifier.classify(g, status(true, "fp-managed", false),
                false, false, false, false, true, false);
        assertThat(c.code()).isEqualTo(DriftClassifier.Code.DR4_ARTIFACT_MISSING);
        assertThat(c.materiality()).isEqualTo(DriftStatus.MATERIAL);
        assertThat(c.pausesDrift()).isTrue();
    }

    @Test
    void dr5_snapshotTargetMissing_recordedOnGroup_noStateChange() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        DriftClassifier.Classification c = classifier.classify(g, status(true, "fp-managed", false),
                false, false, false, false, false, true);
        assertThat(c.code()).isEqualTo(DriftClassifier.Code.DR5_SNAPSHOT_MISSING);
        assertThat(c.recordOnGroup()).isTrue();
        assertThat(c.pausesDrift()).isFalse();
    }

    @Test
    void dr6_foreignActivity_defers() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        DriftClassifier.Classification c = classifier.classify(g, status(true, "fp-DIFFERENT", true),
                false, false, false, false, false, false);
        assertThat(c.code()).isEqualTo(DriftClassifier.Code.DR6_FOREIGN_CHANGE);
        assertThat(c.defersOps()).isTrue();
        assertThat(c.pausesDrift()).isFalse();
    }

    @Test
    void permissionError_isNeverDrift() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        DriftClassifier.Classification c = classifier.classify(g, status(true, "fp-DIFFERENT", false),
                true, false, false, false, false, false);
        assertThat(c.code()).isEqualTo(DriftClassifier.Code.PERMISSION);
        assertThat(c.alert()).isTrue();
        assertThat(c.pausesDrift()).isFalse();
    }

    @Test
    void afterFallbackEntry_twoSweepsClassifyNone_comparingFallbackFingerprint() {
        // config_mode=FALLBACK: live equals the fallback fingerprint, not the desired one. Comparing
        // against desired would flag DR-2; comparing against fallback is NONE (§5.2). Two sweeps NONE.
        SpotGroup g = group(GroupState.FALLBACK, ConfigMode.FALLBACK, "fp-managed", "fp-fallback");
        GroupStatus live = status(true, "fp-fallback", false);
        assertThat(classifier.classify(g, live, false, false, false, false, false, false).code())
                .isEqualTo(DriftClassifier.Code.NONE);
        assertThat(classifier.classify(g, live, false, false, false, false, false, false).code())
                .isEqualTo(DriftClassifier.Code.NONE);
    }

    @Test
    void customerEditOfManagedFieldDuringFallback_isDr2() {
        // A customer edit changes the live fingerprint away from the fallback fingerprint during
        // FALLBACK: still DR-2 (§5.2).
        SpotGroup g = group(GroupState.FALLBACK, ConfigMode.FALLBACK, "fp-managed", "fp-fallback");
        DriftClassifier.Classification c = classifier.classify(g, status(true, "fp-EDITED", false),
                false, false, false, false, false, false);
        assertThat(c.code()).isEqualTo(DriftClassifier.Code.DR2_MANAGED_FIELD);
        assertThat(c.pausesDrift()).isTrue();
    }

    // ── GONE (DR-7) ──────────────────────────────────────────────────────────

    @Test
    void gone_onlyAfterTwoNotFoundSweeps() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        // First NOT_FOUND: record the marker, not GONE.
        assertThat(goneDetector.check(g, GroupStatus.notFound(), false))
                .isEqualTo(GoneDetector.Verdict.NOT_FOUND_FIRST);
        // The reconciler would have stamped last_error_code=NOT_FOUND_ONCE; simulate it.
        g.setLastErrorCode(GoneDetector.NOT_FOUND_ONCE);
        assertThat(goneDetector.check(g, GroupStatus.notFound(), false)).isEqualTo(GoneDetector.Verdict.GONE);
    }

    @Test
    void singleNotFoundThenFound_isNotGone() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        assertThat(goneDetector.check(g, GroupStatus.notFound(), false))
                .isEqualTo(GoneDetector.Verdict.NOT_FOUND_FIRST);
        g.setLastErrorCode(GoneDetector.NOT_FOUND_ONCE);
        // Then FOUND on the next sweep: clear, never GONE (AC-CMP-02, C-04).
        assertThat(goneDetector.check(g, status(true, "fp-managed", false), false))
                .isEqualTo(GoneDetector.Verdict.FOUND);
    }

    @Test
    void permissionError_isNeverGone() {
        SpotGroup g = group(GroupState.ACTIVE, ConfigMode.MANAGED, "fp-managed", null);
        g.setLastErrorCode(GoneDetector.NOT_FOUND_ONCE);
        assertThat(goneDetector.check(g, null, true)).isEqualTo(GoneDetector.Verdict.PERMISSION);
    }
}
