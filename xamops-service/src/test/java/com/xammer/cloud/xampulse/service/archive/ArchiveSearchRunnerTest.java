package com.xammer.cloud.xampulse.service.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpArchiveSearchJob;
import com.xammer.cloud.xampulse.dto.XpLogRow;
import com.xammer.cloud.xampulse.dto.XpScope;
import com.xammer.cloud.xampulse.dto.XpSeverity;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ArchiveSearchRunnerTest {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final String AWS = "123456789012";

    private final ArchiveWriterJobTest.FakeStore objects = new ArchiveWriterJobTest.FakeStore();
    private final ArchiveSearchJobStoreTest.MemoryStore memory = new ArchiveSearchJobStoreTest.MemoryStore();
    private final ArchiveSearchJobStore jobs = new ArchiveSearchJobStore(memory, new ObjectMapper().registerModule(new JavaTimeModule()));
    private final XamPulseProperties props = new XamPulseProperties();
    private final AuditService audit = mock(AuditService.class);
    private final List<Runnable> queued = new ArrayList<>();
    private XpAccountConfig cfg;
    private ArchiveSearchService searchService;
    private XpScope scope;

    /** Test double: records what it was asked and lets each test script the outcome. */
    private static final class FakeExecutor implements ArchiveSearchExecutor {
        List<ArchiveManifest.Entry> seenFiles;
        RuntimeException failWith;
        boolean cancelAfterFirstFile;
        Runnable beforeSecondFile = () -> { };
        @Override
        public Result search(XpAccountConfig cfg, String aws, com.xammer.cloud.xampulse.service.query.QueryScope scope,
                             List<ArchiveManifest.Entry> files, com.xammer.cloud.xampulse.service.query.FilterAst filter,
                             int rowLimit, ProgressSink progress, BooleanSupplier cancelled) {
            seenFiles = files;
            if (failWith != null) {
                throw failWith;
            }
            int done = 0;
            for (ArchiveManifest.Entry ignored : files) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                progress.filesDone(++done);
                if (done == 1) {
                    beforeSecondFile.run();
                }
            }
            XpLogRow row = new XpLogRow("r", NOW, 7L, "ap-south-1", "i-1", "svc", "file", XpSeverity.ERROR, "boom", Map.of(), null, null);
            return new Result(List.of(row), false);
        }
    }

    private final FakeExecutor executor = new FakeExecutor();
    private ArchiveSearchRunner runner;

    @BeforeEach
    void setUp() {
        props.getArchive().setEnabled(true);
        cfg = new XpAccountConfig();
        cfg.setCloudAccountId(7L);
        cfg.setBucketName("my-logs");
        searchService = new ArchiveSearchService(new ArchiveManifestService(objects), props);
        ArchiveManifestService manifests = new ArchiveManifestService(objects);
        for (int h : new int[] {1, 2}) {
            String key = ArchiveKeyLayout.logKey(AWS, "ap-south-1", Instant.parse("2026-11-01T0" + h + ":00:00Z"), "n1", 0);
            manifests.addEntry(cfg, AWS, LocalDate.of(2026, 11, 1), new ArchiveManifest.Entry(key, "ap-south-1", 10, 1000,
                    Instant.parse("2026-11-01T0" + h + ":00:00Z").toEpochMilli(), Instant.parse("2026-11-01T0" + h + ":59:00Z").toEpochMilli(),
                    "a".repeat(64), "STANDARD"));
        }
        scope = new XpScope(List.of(7L), null, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-01T23:00:00Z"), null);
        runner = new ArchiveSearchRunner(searchService, executor, jobs, props, audit, queued::add, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private XpArchiveSearchJob current(XpArchiveSearchJob started) {
        return jobs.find(started.id()).orElseThrow().job();
    }

    @Test
    void queuedThenRunningThenDoneWithRowsAndProgress() {
        XpArchiveSearchJob started = runner.start(5L, null, cfg, AWS, scope, "error", 2000);
        assertEquals("QUEUED", started.state());
        assertEquals(2, started.filesTotal());
        assertEquals("QUEUED", current(started).state());
        queued.get(0).run();
        XpArchiveSearchJob done = current(started);
        assertEquals("DONE", done.state());
        assertEquals(2, done.filesDone());
        assertEquals(1, done.rows().size());
        assertNotNull(done.finishedAt());
        assertTrue(jobs.tryLock(7L, "next"), "the per-account lock is released");
        verify(audit).recordForUser(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("archive.search.run"), startsWith("q:len=5"), any());
    }

    @Test
    void theFileListComesFromTheTenantManifestAndStaysUnderItsPrefix() {
        XpArchiveSearchJob started = runner.start(5L, null, cfg, AWS, scope, "", 2000);
        queued.get(0).run();
        assertEquals(2, executor.seenFiles.size());
        for (ArchiveManifest.Entry e : executor.seenFiles) {
            assertTrue(e.key().startsWith("xamops-xampulse/v1/logs/account=" + AWS + "/"), e.key());
        }
        assertEquals("DONE", current(started).state());
    }

    @Test
    void aManifestKeyOutsideThePrefixIsDropped() {
        ArchiveManifestService manifests = new ArchiveManifestService(objects);
        manifests.addEntry(cfg, AWS, LocalDate.of(2026, 11, 1), new ArchiveManifest.Entry("other-prefix/evil.parquet", "ap-south-1", 1, 1,
                Instant.parse("2026-11-01T03:00:00Z").toEpochMilli(), Instant.parse("2026-11-01T03:10:00Z").toEpochMilli(), "b".repeat(64), "STANDARD"));
        runner.start(5L, null, cfg, AWS, scope, "", 1_000_000);
        queued.get(0).run();
        assertEquals(2, executor.seenFiles.size());
        assertTrue(executor.seenFiles.stream().noneMatch(e -> e.key().startsWith("other-prefix")));
    }

    @Test
    void cancelMidRunStopsBetweenFilesAndStaysCancelled() {
        XpArchiveSearchJob started = runner.start(5L, null, cfg, AWS, scope, "", 2000);
        executor.beforeSecondFile = () -> runner.cancel(jobs.find(started.id()).orElseThrow());
        queued.get(0).run();
        XpArchiveSearchJob after = current(started);
        assertEquals("CANCELLED", after.state());
        assertNull(after.rows());
    }

    @Test
    void anExecutorFailureBecomesAStableCodeNotAMessage() {
        executor.failWith = new IllegalStateException("AccessDenied: arn:aws:iam::123456789012:role/secret-role");
        XpArchiveSearchJob started = runner.start(5L, null, cfg, AWS, scope, "", 2000);
        queued.get(0).run();
        XpArchiveSearchJob failed = current(started);
        assertEquals("FAILED", failed.state());
        assertEquals("ARCHIVE_INTERNAL", failed.errorCode());
        assertFalse(failed.toString().contains("secret-role"));

        executor.failWith = new ArchiveSearchExecutor.Failure(ArchiveSearchExecutor.Failure.ACCESS_DENIED);
        XpArchiveSearchJob second = runner.start(5L, null, cfg, AWS, scope, "", 2000);
        queued.get(1).run();
        assertEquals("ARCHIVE_ACCESS_DENIED", current(second).errorCode());
    }

    @Test
    void aSecondJobForTheSameAccountIs409UntilTheFirstEnds() {
        runner.start(5L, null, cfg, AWS, scope, "", 2000);
        XamPulseException e = assertThrows(XamPulseException.class, () -> runner.start(6L, null, cfg, AWS, scope, "", 2000));
        assertEquals("XP_STATE_CONFLICT", e.getCode());
        queued.get(0).run();
        runner.start(6L, null, cfg, AWS, scope, "", 2000);
    }

    @Test
    void aFullQueueIs429AndLeavesNoJobOrLockBehind() {
        ArchiveSearchRunner full = new ArchiveSearchRunner(searchService, executor, jobs, props, audit,
                r -> { throw new RejectedExecutionException(); }, Clock.fixed(NOW, ZoneOffset.UTC));
        XamPulseException e = assertThrows(XamPulseException.class, () -> full.start(5L, null, cfg, AWS, scope, "", 2000));
        assertEquals(429, e.getHttpStatus());
        assertTrue(jobs.tryLock(7L, "x"));
    }

    @Test
    void aGrownEstimateIs409AndNothingIsStarted() {
        XamPulseException e = assertThrows(XamPulseException.class, () -> runner.start(5L, null, cfg, AWS, scope, "", 1500));
        assertEquals("XP_ARCHIVE_ESTIMATE_CHANGED", e.getCode());
        assertTrue(queued.isEmpty());
        assertTrue(jobs.tryLock(7L, "x"));
    }

    @Test
    void tooManyFilesIsRefused() {
        props.getArchive().setMaxSearchFiles(1);
        XamPulseException e = assertThrows(XamPulseException.class, () -> runner.start(5L, null, cfg, AWS, scope, "", 2000));
        assertEquals("XP_INVALID_REQUEST", e.getCode());
    }

    @Test
    void aBadFilterIsRejectedBeforeAnythingIsStored() {
        assertThrows(XamPulseException.class, () -> runner.start(5L, null, cfg, AWS, scope, "severity:(", 2000));
        assertTrue(queued.isEmpty());
    }

    @Test
    void impersonatedRunsAuditTheRealActor() {
        runner.start(5L, 99L, cfg, AWS, scope, "", 2000);
        verify(audit).recordForUser(anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq("archive.search.run"),
                org.mockito.ArgumentMatchers.endsWith(",actor=99"), any());
    }
}
