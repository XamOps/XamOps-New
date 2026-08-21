package com.xammer.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.model.DBSnapshot;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the retention guard on the old-manual-snapshot detector.
 *
 * <p>The detector previously flagged every manual snapshot older than 90 days. RDS keeps no
 * automated backups past an instance's lifetime, so the last manual snapshot of a database is
 * frequently the only surviving copy of its data — and it is old precisely because the instance
 * is long gone. Acting on that advice destroys the data with no recovery path.
 */
class DbSnapshotRetentionTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final Instant CUTOFF = NOW.minus(90, ChronoUnit.DAYS);

    private static DBSnapshot snapshot(String id, String instanceId, Instant createdAt) {
        return DBSnapshot.builder()
                .dbSnapshotIdentifier(id)
                .dbInstanceIdentifier(instanceId)
                .snapshotCreateTime(createdAt)
                .allocatedStorage(100)
                .engine("postgres")
                .build();
    }

    private static Instant daysAgo(int days) {
        return NOW.minus(days, ChronoUnit.DAYS);
    }

    private static List<String> idsOf(List<DBSnapshot> snapshots) {
        return snapshots.stream().map(DBSnapshot::dbSnapshotIdentifier).toList();
    }

    @Test
    @DisplayName("the only snapshot of an instance is retained however old it is")
    void soleSnapshotIsNeverDeletable() {
        List<DBSnapshot> manual = List.of(snapshot("snap-1", "orders-db", daysAgo(1200)));

        assertThat(OptimizationService.deletableManualDbSnapshots(manual, CUTOFF)).isEmpty();
    }

    @Test
    @DisplayName("with two old copies the newer one survives")
    void newestCopyIsRetained() {
        List<DBSnapshot> manual = List.of(
                snapshot("snap-old", "orders-db", daysAgo(400)),
                snapshot("snap-newer", "orders-db", daysAgo(120)));

        assertThat(idsOf(OptimizationService.deletableManualDbSnapshots(manual, CUTOFF)))
                .containsExactly("snap-old");
    }

    @Test
    @DisplayName("an old snapshot behind recent copies is deletable")
    void agedSnapshotBehindRecentCopiesIsDeletable() {
        // The retained copy is chosen by recency, not by age band. Two recent snapshots exist,
        // so the ancient one is no longer the last line of defence.
        List<DBSnapshot> manual = List.of(
                snapshot("snap-ancient", "orders-db", daysAgo(900)),
                snapshot("snap-recent", "orders-db", daysAgo(3)),
                snapshot("snap-recent-2", "orders-db", daysAgo(10)));

        assertThat(idsOf(OptimizationService.deletableManualDbSnapshots(manual, CUTOFF)))
                .containsExactly("snap-ancient");
    }

    @Test
    @DisplayName("recent snapshots are never deletable")
    void recentSnapshotsAreNotDeletable() {
        List<DBSnapshot> manual = List.of(
                snapshot("snap-a", "orders-db", daysAgo(2)),
                snapshot("snap-b", "orders-db", daysAgo(30)),
                snapshot("snap-c", "orders-db", daysAgo(89)));

        assertThat(OptimizationService.deletableManualDbSnapshots(manual, CUTOFF)).isEmpty();
    }

    @Test
    @DisplayName("retention is per source instance, not per region")
    void retentionIsScopedToTheSourceInstance() {
        // Three instances each holding one old snapshot must yield nothing. Counting across the
        // region instead would have retained one snapshot and destroyed the other two databases.
        List<DBSnapshot> manual = List.of(
                snapshot("snap-orders", "orders-db", daysAgo(500)),
                snapshot("snap-billing", "billing-db", daysAgo(500)),
                snapshot("snap-audit", "audit-db", daysAgo(500)));

        assertThat(OptimizationService.deletableManualDbSnapshots(manual, CUTOFF)).isEmpty();
    }

    @Test
    @DisplayName("each instance keeps its own newest copy")
    void eachInstanceKeepsItsOwnNewestCopy() {
        List<DBSnapshot> manual = List.of(
                snapshot("orders-old", "orders-db", daysAgo(500)),
                snapshot("orders-newer", "orders-db", daysAgo(300)),
                snapshot("billing-old", "billing-db", daysAgo(500)),
                snapshot("billing-newer", "billing-db", daysAgo(300)));

        assertThat(idsOf(OptimizationService.deletableManualDbSnapshots(manual, CUTOFF)))
                .containsExactlyInAnyOrder("orders-old", "billing-old");
    }

    @Test
    @DisplayName("a snapshot whose source instance is gone forms a group of one")
    void orphanedSnapshotsAreEachRetained() {
        // A blank dbInstanceIdentifier means the instance no longer exists. Grouping these
        // together would have made all but one deletable, which is the worst case: there is no
        // automated backup behind them.
        List<DBSnapshot> manual = List.of(
                snapshot("orphan-1", null, daysAgo(700)),
                snapshot("orphan-2", "", daysAgo(700)),
                snapshot("orphan-3", "   ", daysAgo(700)));

        assertThat(OptimizationService.deletableManualDbSnapshots(manual, CUTOFF)).isEmpty();
    }

    @Test
    @DisplayName("a snapshot of unknown age is never reported as old")
    void unknownCreationTimeIsNotDeletable() {
        List<DBSnapshot> manual = List.of(
                snapshot("snap-known", "orders-db", daysAgo(3)),
                snapshot("snap-known-2", "orders-db", daysAgo(5)),
                snapshot("snap-undated", "orders-db", null));

        // The undated snapshot sorts last, so it is outside the retained window, but an unknown
        // age is not evidence of an old snapshot.
        assertThat(OptimizationService.deletableManualDbSnapshots(manual, CUTOFF)).isEmpty();
    }

    @Test
    @DisplayName("an empty inventory yields no findings")
    void emptyInventory() {
        assertThat(OptimizationService.deletableManualDbSnapshots(List.of(), CUTOFF)).isEmpty();
    }

    @Test
    @DisplayName("exactly one copy is always kept, whatever the group size")
    void oneCopyAlwaysSurvives() {
        List<DBSnapshot> manual = List.of(
                snapshot("snap-1", "orders-db", daysAgo(600)),
                snapshot("snap-2", "orders-db", daysAgo(500)),
                snapshot("snap-3", "orders-db", daysAgo(400)),
                snapshot("snap-4", "orders-db", daysAgo(300)),
                snapshot("snap-5", "orders-db", daysAgo(200)));

        List<DBSnapshot> deletable = OptimizationService.deletableManualDbSnapshots(manual, CUTOFF);

        assertThat(deletable).hasSize(manual.size() - OptimizationService.MIN_RETAINED_DB_SNAPSHOTS);
        assertThat(idsOf(deletable)).doesNotContain("snap-5");
    }
}
