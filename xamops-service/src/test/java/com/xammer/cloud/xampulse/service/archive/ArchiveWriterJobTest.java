package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchiveWriterJobTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:30:00Z");

    static class FakeStore implements ArchiveStore {
        final Map<String, String> texts = new HashMap<>();
        final List<String> fileKeys = new ArrayList<>();
        boolean failFiles;
        public void putFile(XpAccountConfig cfg, String key, Path file) {
            if (failFiles) throw new IllegalStateException("AccessDenied secret-detail");
            fileKeys.add(key);
        }
        public void putText(XpAccountConfig cfg, String key, String text) { texts.put(key, text); }
        public Optional<String> getText(XpAccountConfig cfg, String key) { return Optional.ofNullable(texts.get(key)); }
    }

    final XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
    final XpAccountRegionRepository regions = mock(XpAccountRegionRepository.class);
    final FakeStore store = new FakeStore();
    final TenantPurgeService purge = mock(TenantPurgeService.class);
    final XamPulseProperties props = new XamPulseProperties();
    final List<String> exportCalls = new ArrayList<>();
    XpAccountConfig cfg;
    LogHourExporter exporter;

    @BeforeEach
    void setUp() {
        props.getArchive().setEnabled(true);
        cfg = new XpAccountConfig();
        cfg.setCloudAccountId(7L);
        cfg.setState("CONNECTED");
        cfg.setHotDays(3);
        cfg.setBucketName("my-logs");
        cfg.setArchiveWatermark(Instant.parse("2026-11-02T07:00:00Z"));
        XpAccountRegion r = new XpAccountRegion();
        r.setRegion("ap-south-1");
        when(regions.findByCloudAccountId(7L)).thenReturn(List.of(r));
        exporter = (tenant, region, hour, target) -> {
            exportCalls.add(tenant + "/" + region + "/" + hour);
            Files.writeString(target, "PAR1" + hour);
            return new LogHourExporter.Exported(10, hour.toEpochMilli(), hour.toEpochMilli() + 3_599_000);
        };
    }

    private ArchiveWriterJob job() {
        return new ArchiveWriterJob(configs, regions, mock(CloudAccountRepository.class), exporter, store,
                new ArchiveManifestService(store), purge, mock(SchedulingEnvironmentConfig.class), props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void archivesEveryClosedHourAndAdvancesTheWatermarkOnly() {
        int done = job().runForAccount(cfg, "123456789012");
        // 07:00, 08:00, 09:00 are closed (10:00 is still inside the grace window at 10:30? no: limit is 10:20 -> 10:00 not closed).
        assertEquals(3, done);
        assertEquals(Instant.parse("2026-11-02T10:00:00Z"), cfg.getArchiveWatermark());
        assertEquals(3, store.fileKeys.size());
        assertTrue(store.fileKeys.get(0).contains("hour=07"));
        String manifest = store.texts.get(ArchiveKeyLayout.manifestKey("123456789012", LocalDate.of(2026, 11, 2)));
        assertNotNull(manifest);
        var parsed = ArchiveManifestService.parse(manifest);
        assertEquals(3, parsed.files().size());
        assertEquals(1, parsed.schemaVersion());
        assertEquals(64, parsed.files().get(0).sha256().length());
        assertEquals(10, parsed.files().get(0).rows());
    }

    @Test
    void rerunningTheSameHoursIsIdempotentInTheManifest() {
        job().runForAccount(cfg, "123456789012");
        cfg.setArchiveWatermark(Instant.parse("2026-11-02T07:00:00Z")); // simulate a lost watermark write
        job().runForAccount(cfg, "123456789012");
        var parsed = ArchiveManifestService.parse(store.texts.get(ArchiveKeyLayout.manifestKey("123456789012", LocalDate.of(2026, 11, 2))));
        assertEquals(3, parsed.files().size(), "same keys replace, never duplicate");
        assertEquals(6, store.fileKeys.size());
        assertEquals(3, store.fileKeys.stream().distinct().count(), "deterministic keys");
    }

    @Test
    void emptyHoursStoreNothingButStillAdvance() {
        exporter = (t, r, h, target) -> null;
        assertEquals(3, job().runForAccount(cfg, "123456789012"));
        assertTrue(store.fileKeys.isEmpty());
        assertTrue(store.texts.isEmpty());
    }

    @Test
    void boundedHoursPerRun() {
        props.getArchive().setMaxHoursPerRun(1);
        assertEquals(1, job().runForAccount(cfg, "123456789012"));
        assertEquals(Instant.parse("2026-11-02T08:00:00Z"), cfg.getArchiveWatermark());
    }

    @Test
    void failuresCountStopTheWatermarkAndLeakNothing() {
        store.failFiles = true;
        assertEquals(0, job().runForAccount(cfg, "123456789012"));
        assertEquals(1, cfg.getArchiveFailures());
        assertEquals(Instant.parse("2026-11-02T07:00:00Z"), cfg.getArchiveWatermark());
        assertEquals("IllegalStateException", cfg.getArchiveLastError(), "class name only, never the message");
        assertEquals("CONNECTED", cfg.getState());
    }

    @Test
    void threeFailuresInARowMarkTheAccountDegraded() {
        store.failFiles = true;
        job().runForAccount(cfg, "123456789012");
        job().runForAccount(cfg, "123456789012");
        job().runForAccount(cfg, "123456789012");
        assertEquals(3, cfg.getArchiveFailures());
        assertEquals("DEGRADED", cfg.getState());
        verify(purge, never()).extendHold(any());
    }

    @Test
    void extendHoldIsRequestedWhenUnarchivedDataIsNearExpiry() {
        store.failFiles = true;
        cfg.setArchiveFailures(2);
        cfg.setArchiveWatermark(Instant.parse("2026-10-30T11:00:00Z")); // 71.5 h unarchived with hotDays=3
        job().runForAccount(cfg, "123456789012");
        verify(purge).extendHold(cfg);
    }

    @Test
    void temporaryFilesAreRemoved() throws IOException {
        Path dir = Files.createTempDirectory("xp-archive-test");
        props.getArchive().setTempDir(dir.toString());
        job().runForAccount(cfg, "123456789012");
        try (var s = Files.list(dir)) {
            assertEquals(0, s.count());
        }
        Files.delete(dir);
    }

    @Test
    void exporterReceivesOnlyTheTenantAndHourNeverStorageCredentials() {
        job().runForAccount(cfg, "123456789012");
        assertEquals("7/ap-south-1/2026-11-02T07:00:00Z", exportCalls.get(0));
    }

    // ---------------- failure alert ----------------

    private ArchiveWriterJob jobWith(ArchiveFailureAlerter alerter) {
        return new ArchiveWriterJob(configs, regions, mock(CloudAccountRepository.class), exporter, store,
                new ArchiveManifestService(store), purge, mock(SchedulingEnvironmentConfig.class), props, Clock.fixed(NOW, ZoneOffset.UTC), alerter);
    }

    @Test
    void alerterFiresExactlyOnceAtTheThirdConsecutiveFailure() {
        store.failFiles = true;
        List<Integer> calls = new ArrayList<>();
        ArchiveWriterJob job = jobWith((c, n, e) -> calls.add(n));
        for (int i = 1; i <= 5; i++) {
            job.runForAccount(cfg, "123456789012");
            assertEquals(i >= 3 ? 1 : 0, calls.size(), "after failure " + i);
        }
        assertEquals(List.of(3), calls);
    }

    @Test
    void aSuccessResetsTheStreakAndTheNextStreakAlertsAgain() {
        List<Integer> calls = new ArrayList<>();
        ArchiveWriterJob job = jobWith((c, n, e) -> calls.add(n));
        store.failFiles = true;
        for (int i = 0; i < 3; i++) {
            job.runForAccount(cfg, "123456789012");
        }
        store.failFiles = false;
        job.runForAccount(cfg, "123456789012");
        assertEquals(0, cfg.getArchiveFailures());
        cfg.setArchiveWatermark(Instant.parse("2026-11-02T07:00:00Z"));
        store.failFiles = true;
        for (int i = 0; i < 3; i++) {
            job.runForAccount(cfg, "123456789012");
        }
        assertEquals(List.of(3, 3), calls);
    }

    @Test
    void anAlerterThatThrowsDoesNotStopTheDegradedTransitionOrTheHold() {
        store.failFiles = true;
        cfg.setArchiveFailures(2);
        cfg.setArchiveWatermark(Instant.parse("2026-10-30T11:00:00Z"));
        ArchiveWriterJob job = jobWith((c, n, e) -> { throw new IllegalStateException("alert down"); });
        job.runForAccount(cfg, "123456789012");
        assertEquals("DEGRADED", cfg.getState());
        verify(purge).extendHold(cfg);
    }
}
