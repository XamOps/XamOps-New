package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.domain.TranscodeJob.JobStatus;
import com.xammer.mediaops.repository.TranscodeJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocalOutputCleanupServiceTest {

    @Mock
    private TranscodeJobRepository jobRepo;

    @Mock
    private S3StorageService s3Storage;

    private LocalOutputCleanupService service;

    @BeforeEach
    void setUp() {
        service = new LocalOutputCleanupService(jobRepo, s3Storage);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "retentionSeconds", 3600L);
    }

    private TranscodeJob job(long id, JobStatus status, String outputPath) {
        TranscodeJob j = new TranscodeJob();
        j.setId(id);
        j.setStatus(status);
        j.setOutputPath(outputPath);
        return j;
    }

    private Path populatedDir(Path base, String name) throws Exception {
        Path dir = base.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("out.mp4"), "data");
        return dir;
    }

    @Test
    void deletesWhenCustomerBucketCopyExists(@TempDir Path tmp) throws Exception {
        Path dir = populatedDir(tmp, "job-1");
        TranscodeJob j = job(1L, JobStatus.COMPLETED, dir.toString());
        j.setOutputBucketPrefix("xamops-mediaops/jobs/1");
        j.setOutputS3ConfigId(42L);
        when(jobRepo.findByStatusAndCompletedAtBefore(eq(JobStatus.COMPLETED), any()))
                .thenReturn(List.of(j));

        service.cleanupCompletedLocalOutputs();

        assertFalse(Files.exists(dir), "local output dir should be deleted");
    }

    @Test
    void deletesWhenXamOpsS3HasKeys(@TempDir Path tmp) throws Exception {
        Path dir = populatedDir(tmp, "job-2");
        TranscodeJob j = job(2L, JobStatus.COMPLETED, dir.toString());
        // No bucket prefix — rely on XamOps S3 keys.
        when(jobRepo.findByStatusAndCompletedAtBefore(eq(JobStatus.COMPLETED), any()))
                .thenReturn(List.of(j));
        when(s3Storage.isEnabled()).thenReturn(true);
        when(s3Storage.listJobKeys(2L)).thenReturn(List.of("media-ops/jobs/2/out.mp4"));

        service.cleanupCompletedLocalOutputs();

        assertFalse(Files.exists(dir), "local output dir should be deleted when S3 has keys");
    }

    @Test
    void skipsWhenNoRemoteCopy(@TempDir Path tmp) throws Exception {
        Path dir = populatedDir(tmp, "job-3");
        TranscodeJob j = job(3L, JobStatus.COMPLETED, dir.toString());
        // No bucket prefix, S3 disabled → no remote copy.
        when(jobRepo.findByStatusAndCompletedAtBefore(eq(JobStatus.COMPLETED), any()))
                .thenReturn(List.of(j));
        when(s3Storage.isEnabled()).thenReturn(false);

        service.cleanupCompletedLocalOutputs();

        assertTrue(Files.exists(dir), "local output dir must survive when it is the only copy");
    }

    @Test
    void processingJobIsNeverSelected() {
        // The query only returns COMPLETED jobs; a PROCESSING job is not passed in and
        // would be skipped by the defensive status guard even if it were.
        when(jobRepo.findByStatusAndCompletedAtBefore(eq(JobStatus.COMPLETED), any()))
                .thenReturn(List.of());

        service.cleanupCompletedLocalOutputs();

        verify(jobRepo).findByStatusAndCompletedAtBefore(eq(JobStatus.COMPLETED), any());
        verifyNoInteractions(s3Storage);
    }

    @Test
    void nullOutputPathIsSafe() {
        TranscodeJob j = job(4L, JobStatus.COMPLETED, null);
        j.setOutputBucketPrefix("xamops-mediaops/jobs/4");
        j.setOutputAccountId("123456789012");
        when(jobRepo.findByStatusAndCompletedAtBefore(eq(JobStatus.COMPLETED), any()))
                .thenReturn(List.of(j));

        assertDoesNotThrow(() -> service.cleanupCompletedLocalOutputs());
    }

    @Test
    void disabledIsNoOp() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.cleanupCompletedLocalOutputs();

        verifyNoInteractions(jobRepo);
        verifyNoInteractions(s3Storage);
    }

    @Test
    void oneFailureDoesNotAbortBatch(@TempDir Path tmp) throws Exception {
        // First job: outputPath points at a plain file (not a directory) with a child
        // path appended, so Files.walk throws — its deletion fails. Second job is valid.
        TranscodeJob bad = job(5L, JobStatus.COMPLETED, tmp.resolve("missing/child").toString());
        bad.setOutputBucketPrefix("xamops-mediaops/jobs/5");
        bad.setOutputS3ConfigId(5L);
        // Make outputPath exist but be un-walkable: a regular file with a sub-path appended.
        Path file = Files.writeString(tmp.resolve("afile"), "x");
        bad.setOutputPath(file.resolve("child").toString());

        Path goodDir = populatedDir(tmp, "job-6");
        TranscodeJob good = job(6L, JobStatus.COMPLETED, goodDir.toString());
        good.setOutputBucketPrefix("xamops-mediaops/jobs/6");
        good.setOutputS3ConfigId(6L);

        when(jobRepo.findByStatusAndCompletedAtBefore(eq(JobStatus.COMPLETED), any()))
                .thenReturn(List.of(bad, good));

        service.cleanupCompletedLocalOutputs();

        assertFalse(Files.exists(goodDir), "second job's dir should still be deleted after first job");
    }
}
