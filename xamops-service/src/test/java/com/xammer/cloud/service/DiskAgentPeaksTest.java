package com.xammer.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the utilization high-water marks that gate a disk shrink.
 *
 * <p>The agent overwrites its mount list every five minutes, so before these peaks existed the
 * rightsizing services could only see the most recent instantaneous reading. Sizing a shrink to
 * that reading is how a filesystem ends up full: a disk sitting at 30% when the agent looked may
 * have been at 90% during the last log rotation. On AWS the shrink is directly executable — it
 * snapshots, stops the instance and swaps the volume — so getting this wrong is expensive to undo.
 */
class DiskAgentPeaksTest {

    private static final long NOW = 1_700_000_000_000L;

    private static Map<String, Object> mount(String device, Object usedPercent) {
        return Map.of("device", device, "usedPercent", usedPercent);
    }

    private static Map<String, Object> mergeOrFail(Map<String, Object> existing,
            List<Map<String, Object>> mounts) {
        Optional<Map<String, Object>> merged = DiskAgentPeaks.merge(existing, mounts, NOW);
        assertThat(merged).isPresent();
        return merged.get();
    }

    @Test
    @DisplayName("the first push seeds the peak, the sample count and the window start")
    void firstPushSeedsEverything() {
        Map<String, Object> peaks = mergeOrFail(null, List.of(mount("/dev/xvdf", 42.0)));

        assertThat(peaks).containsEntry("/dev/xvdf", 42.0);
        assertThat(peaks).containsEntry(DiskAgentPeaks.SAMPLE_COUNT_FIELD, 1L);
        assertThat(peaks).containsEntry(DiskAgentPeaks.WINDOW_START_FIELD, NOW);
    }

    @Test
    @DisplayName("a higher reading raises the peak")
    void higherReadingRaisesThePeak() {
        Map<String, Object> first = mergeOrFail(null, List.of(mount("/dev/xvdf", 30.0)));
        Map<String, Object> second = mergeOrFail(first, List.of(mount("/dev/xvdf", 91.5)));

        assertThat(second).containsEntry("/dev/xvdf", 91.5);
    }

    @Test
    @DisplayName("a lower reading does not lower the peak")
    void lowerReadingDoesNotLowerThePeak() {
        // The whole point: the disk was at 91.5% once, so a shrink must be sized for 91.5% even
        // though every subsequent sample reads 30%.
        Map<String, Object> peaks = mergeOrFail(null, List.of(mount("/dev/xvdf", 91.5)));
        for (int i = 0; i < 10; i++) {
            peaks = mergeOrFail(peaks, List.of(mount("/dev/xvdf", 30.0)));
        }

        assertThat(peaks).containsEntry("/dev/xvdf", 91.5);
        assertThat(peaks).containsEntry(DiskAgentPeaks.SAMPLE_COUNT_FIELD, 11L);
    }

    @Test
    @DisplayName("the window start is not moved by later pushes")
    void windowStartIsStable() {
        Map<String, Object> first = mergeOrFail(null, List.of(mount("/dev/xvdf", 10.0)));
        Optional<Map<String, Object>> second = DiskAgentPeaks.merge(
                first, List.of(mount("/dev/xvdf", 12.0)), NOW + 300_000L);

        assertThat(second).isPresent();
        assertThat(second.get()).containsEntry(DiskAgentPeaks.WINDOW_START_FIELD, NOW);
    }

    @Test
    @DisplayName("each device keeps its own peak")
    void peaksArePerDevice() {
        Map<String, Object> peaks = mergeOrFail(null,
                List.of(mount("/dev/xvdf", 88.0), mount("/dev/xvdg", 12.0)));

        assertThat(peaks).containsEntry("/dev/xvdf", 88.0);
        assertThat(peaks).containsEntry("/dev/xvdg", 12.0);
    }

    @Test
    @DisplayName("the sample count counts pushes, not devices")
    void sampleCountCountsPushesNotDevices() {
        // A four-disk instance must not reach the shrink threshold in a quarter of the time.
        Map<String, Object> peaks = mergeOrFail(null, List.of(
                mount("/dev/xvda", 10.0),
                mount("/dev/xvdb", 20.0),
                mount("/dev/xvdc", 30.0),
                mount("/dev/xvdd", 40.0)));

        assertThat(peaks).containsEntry(DiskAgentPeaks.SAMPLE_COUNT_FIELD, 1L);
    }

    @Test
    @DisplayName("a push with nothing usable is not recorded")
    void unusablePushIsNotRecorded() {
        // Counting these would let an instance whose agent reports garbage accumulate enough
        // "observations" to unlock a shrink it has no evidence for.
        assertThat(DiskAgentPeaks.merge(null, List.of(), NOW)).isEmpty();
        assertThat(DiskAgentPeaks.merge(null, null, NOW)).isEmpty();
        assertThat(DiskAgentPeaks.merge(null, List.of(Map.of("mountPoint", "/")), NOW)).isEmpty();
        assertThat(DiskAgentPeaks.merge(null, List.of(Map.of("device", "", "usedPercent", 5.0)), NOW))
                .isEmpty();
        assertThat(DiskAgentPeaks.merge(null,
                List.of(Map.of("device", "/dev/xvdf", "usedPercent", "not-a-number")), NOW)).isEmpty();
    }

    @Test
    @DisplayName("a non-finite reading is ignored without discarding the push")
    void nonFiniteReadingsAreIgnored() {
        Map<String, Object> peaks = mergeOrFail(null, List.of(
                Map.of("device", "/dev/broken", "usedPercent", Double.NaN),
                mount("/dev/xvdf", 44.0)));

        assertThat(peaks).doesNotContainKey("/dev/broken");
        assertThat(peaks).containsEntry("/dev/xvdf", 44.0);
        assertThat(peaks).containsEntry(DiskAgentPeaks.SAMPLE_COUNT_FIELD, 1L);
    }

    @Test
    @DisplayName("integer readings from JSON are accepted")
    void integerReadingsAreAccepted() {
        // Jackson deserialises a whole number as Integer, so the merge must not assume Double.
        Map<String, Object> peaks = mergeOrFail(null, List.of(mount("/dev/xvdf", 55)));

        assertThat(((Number) peaks.get("/dev/xvdf")).doubleValue()).isEqualTo(55.0);
    }

    @Test
    @DisplayName("the caller's stored map is not mutated")
    void mergeDoesNotMutateItsInput() {
        Map<String, Object> first = mergeOrFail(null, List.of(mount("/dev/xvdf", 20.0)));
        Map<String, Object> snapshot = Map.copyOf(first);

        mergeOrFail(first, List.of(mount("/dev/xvdf", 80.0)));

        assertThat(first).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("provider prefixes keep the keys apart")
    void keysAreNamespacedByProvider() {
        String aws = DiskAgentPeaks.keyFor("ebs-disk-agent:", "111122223333", "i-abc");
        String gcp = DiskAgentPeaks.keyFor("gcp-disk-agent:", "111122223333", "i-abc");
        String azure = DiskAgentPeaks.keyFor("azure-disk-agent:", "111122223333", "i-abc");

        assertThat(List.of(aws, gcp, azure)).doesNotHaveDuplicates();
        assertThat(aws).startsWith(DiskAgentPeaks.KEY_PREFIX);
    }

    @Test
    @DisplayName("a day of five-minute pushes clears the shrink threshold")
    void aDayOfObservationsUnlocksAShrink() {
        // 288 pushes at five-minute intervals is 24 hours — long enough to have seen a nightly
        // batch window. Documented here so the constant is not quietly lowered.
        assertThat(DiskAgentPeaks.MIN_OBSERVATIONS_FOR_SHRINK).isEqualTo(288);

        Map<String, Object> peaks = null;
        for (int i = 0; i < DiskAgentPeaks.MIN_OBSERVATIONS_FOR_SHRINK; i++) {
            peaks = mergeOrFail(peaks, List.of(mount("/dev/xvdf", 15.0)));
        }

        assertThat(((Number) peaks.get(DiskAgentPeaks.SAMPLE_COUNT_FIELD)).longValue())
                .isEqualTo(DiskAgentPeaks.MIN_OBSERVATIONS_FOR_SHRINK);
    }

    @Test
    @DisplayName("the peak record outlives the mount snapshot")
    void ttlOutlivesTheMountSnapshot() {
        // The mount list expires after 6 hours. A peak window shorter than the observation
        // requirement could never be satisfied.
        assertThat(DiskAgentPeaks.TTL_MINUTES).isGreaterThan(6 * 60);
    }
}
