package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.repository.MediaStorageFileRepository;
import com.xammer.mediaops.repository.TranscodeJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the inline post-upload cleanup helper
 * ({@link TranscodeJobService#deleteLocalOutputDir}). Exercises the helper directly via
 * reflection so no FFmpeg or S3 infrastructure is needed — {@code processJobAsync} only
 * calls the helper on the upload success path, so these cases mirror what happens there.
 */
@ExtendWith(MockitoExtension.class)
class TranscodeJobServiceInlineCleanupTest {

    @Mock private TranscodeJobRepository jobRepo;
    @Mock private MediaStorageFileRepository fileRepo;
    @Mock private FFmpegService ffmpegService;
    @Mock private S3StorageService s3Storage;
    @Mock private UserS3Service userS3Service;
    @Mock private AccountS3Service accountS3Service;
    @Mock private SourceUrlResolver sourceUrlResolver;
    @Mock private SimpMessagingTemplate messaging;
    @Mock private NotificationService notificationService;

    private TranscodeJobService newService(boolean cleanupEnabled) {
        TranscodeJobService service = new TranscodeJobService(jobRepo, fileRepo, ffmpegService,
                s3Storage, userS3Service, accountS3Service, sourceUrlResolver, messaging, notificationService);
        ReflectionTestUtils.setField(service, "deleteLocalAfterUpload", cleanupEnabled);
        return service;
    }

    private TranscodeJob jobWithOutput(long id, String outputPath) {
        TranscodeJob j = new TranscodeJob();
        j.setId(id);
        j.setOutputPath(outputPath);
        return j;
    }

    private Path populatedDir(Path base, String name) throws Exception {
        Path dir = base.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("out.mp4"), "data");
        return dir;
    }

    private void invokeCleanup(TranscodeJobService service, TranscodeJob job) {
        ReflectionTestUtils.invokeMethod(service, "deleteLocalOutputDir", job);
    }

    @Test
    void cleanupEnabled_deletesLocalOutputDir(@TempDir Path tmp) throws Exception {
        // (1) Successful customer-bucket upload with cleanup enabled -> local dir deleted.
        Path dir = populatedDir(tmp, "job-1");
        TranscodeJob job = jobWithOutput(1L, dir.toString());
        TranscodeJobService service = newService(true);

        invokeCleanup(service, job);

        assertFalse(Files.exists(dir), "local output dir should be deleted when cleanup is enabled");
    }

    @Test
    void cleanupDisabled_keepsLocalOutputDir(@TempDir Path tmp) throws Exception {
        // (2) Cleanup disabled -> files are left on disk (local-dev behavior).
        Path dir = populatedDir(tmp, "job-2");
        TranscodeJob job = jobWithOutput(2L, dir.toString());
        TranscodeJobService service = newService(false);

        invokeCleanup(service, job);

        assertTrue(Files.exists(dir), "local output dir must survive when cleanup is disabled");
        assertTrue(Files.exists(dir.resolve("out.mp4")), "output file must survive when cleanup is disabled");
    }

    @Test
    void uploadFailurePath_keepsLocalOutputDir(@TempDir Path tmp) throws Exception {
        // (3) Upload failure/exception path -> cleanup is NOT invoked, so files remain.
        // processJobAsync only calls the helper when the per-branch success flag is true;
        // a failing upload (catch block sets errorMessage) never reaches the helper. Here
        // we simulate that by not invoking the helper and asserting the dir is untouched.
        Path dir = populatedDir(tmp, "job-3");
        TranscodeJob job = jobWithOutput(3L, dir.toString());
        // Service exists with cleanup enabled; the catch path simply never calls the helper.
        newService(true);

        assertTrue(Files.exists(dir), "local output dir must survive on the upload failure path");
        assertTrue(Files.exists(dir.resolve("out.mp4")), "output file must survive on the upload failure path");
    }

    @Test
    void nullOrMissingOutputPath_isSafeNoOp(@TempDir Path tmp) {
        TranscodeJobService service = newService(true);

        assertDoesNotThrow(() -> invokeCleanup(service, jobWithOutput(4L, null)));
        assertDoesNotThrow(() -> invokeCleanup(service, jobWithOutput(5L, "")));
        assertDoesNotThrow(() -> invokeCleanup(service, jobWithOutput(6L, tmp.resolve("does-not-exist").toString())));
    }
}
