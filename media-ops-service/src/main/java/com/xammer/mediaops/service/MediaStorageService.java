package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.MediaStorageFile;
import com.xammer.mediaops.domain.MediaStream;
import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.dto.MediaOpsOverviewDto;
import com.xammer.mediaops.repository.MediaStorageFileRepository;
import com.xammer.mediaops.repository.MediaStreamRepository;
import com.xammer.mediaops.repository.TranscodeJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class MediaStorageService {

    private final MediaStorageFileRepository fileRepo;
    private final TranscodeJobRepository jobRepo;
    private final MediaStreamRepository streamRepo;

    @Value("${mediaops.storage.base-dir:/tmp/xamops-media}")
    private String storageBaseDir;

    public MediaStorageService(MediaStorageFileRepository fileRepo,
                               TranscodeJobRepository jobRepo,
                               MediaStreamRepository streamRepo) {
        this.fileRepo = fileRepo;
        this.jobRepo = jobRepo;
        this.streamRepo = streamRepo;
    }

    public List<MediaStorageFile> listFiles(Long clientId) {
        return fileRepo.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    public List<MediaStorageFile> listFilesByType(Long clientId, MediaStorageFile.FileType type) {
        return fileRepo.findByClientIdAndFileTypeOrderByCreatedAtDesc(clientId, type);
    }

    public Optional<MediaStorageFile> getFile(Long id, Long clientId) {
        return fileRepo.findByIdAndClientId(id, clientId);
    }

    @Transactional
    public boolean deleteFile(Long id, Long clientId) {
        if (fileRepo.findByIdAndClientId(id, clientId).isEmpty()) return false;
        fileRepo.deleteByIdAndClientId(id, clientId);
        return true;
    }

    @Transactional
    public MediaStorageFile uploadFile(Long clientId, MultipartFile file) throws IOException {
        Path clientDir = Paths.get(storageBaseDir, "client_" + clientId);
        Files.createDirectories(clientDir);

        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload");
        String stored = System.currentTimeMillis() + "_" + originalName;
        Path dest = clientDir.resolve(stored);
        file.transferTo(dest.toFile());

        String ext = originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase()
                : "";

        MediaStorageFile record = new MediaStorageFile();
        record.setClientId(clientId);
        record.setName(originalName);
        record.setFileType(detectFileType(ext));
        record.setFormat(ext.isEmpty() ? "UNKNOWN" : ext.toUpperCase());
        record.setSizeBytes(file.getSize());
        record.setFilePath(dest.toString());
        record.setCreatedAt(Instant.now());
        return fileRepo.save(record);
    }

    private MediaStorageFile.FileType detectFileType(String ext) {
        switch (ext) {
            case "mp4": case "mov": case "avi": case "mkv":
            case "wmv": case "flv": case "webm": case "m4v": return MediaStorageFile.FileType.VIDEO;
            case "mp3": case "wav": case "aac": case "flac":
            case "ogg": case "m4a":                          return MediaStorageFile.FileType.AUDIO;
            case "jpg": case "jpeg": case "png": case "gif":
            case "webp": case "bmp":                         return MediaStorageFile.FileType.IMAGE;
            case "m3u8": case "ts":                          return MediaStorageFile.FileType.HLS;
            default:                                         return MediaStorageFile.FileType.VIDEO;
        }
    }

    public long totalStorageBytes(Long clientId) {
        return fileRepo.totalStorageByClientId(clientId);
    }

    public MediaOpsOverviewDto buildOverview(Long clientId) {
        MediaOpsOverviewDto dto = new MediaOpsOverviewDto();
        dto.setLiveStreams(streamRepo.findByClientIdAndStatus(clientId, MediaStream.StreamStatus.LIVE).size());
        dto.setProcessingJobs(jobRepo.countByClientIdAndStatus(clientId, TranscodeJob.JobStatus.PROCESSING));
        dto.setQueuedJobs(jobRepo.countByClientIdAndStatus(clientId, TranscodeJob.JobStatus.QUEUED));
        dto.setCompletedJobs(jobRepo.countByClientIdAndStatus(clientId, TranscodeJob.JobStatus.COMPLETED));
        dto.setFailedJobs(jobRepo.countByClientIdAndStatus(clientId, TranscodeJob.JobStatus.FAILED));
        long viewers = streamRepo.findByClientIdAndStatus(clientId, MediaStream.StreamStatus.LIVE)
                .stream().mapToLong(MediaStream::getViewers).sum();
        dto.setTotalViewers(viewers);
        dto.setStorageUsedBytes(fileRepo.totalStorageByClientId(clientId));
        BigDecimal savings = jobRepo.totalSavingsByClientId(clientId);
        dto.setTotalSavedVsAws(savings != null ? savings : BigDecimal.ZERO);
        return dto;
    }
}
