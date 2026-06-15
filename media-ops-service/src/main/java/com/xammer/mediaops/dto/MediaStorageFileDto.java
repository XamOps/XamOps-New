package com.xammer.mediaops.dto;

import com.xammer.mediaops.domain.MediaStorageFile;
import lombok.Data;

import java.time.Instant;

@Data
public class MediaStorageFileDto {

    private Long id;
    private String name;
    private String fileType;
    private String format;
    private long sizeBytes;
    private Integer durationSeconds;
    private String resolution;
    private String filePath;
    private Long jobId;
    private Instant createdAt;

    public static MediaStorageFileDto from(MediaStorageFile f) {
        MediaStorageFileDto dto = new MediaStorageFileDto();
        dto.id = f.getId();
        dto.name = f.getName();
        dto.fileType = f.getFileType() != null ? f.getFileType().name() : null;
        dto.format = f.getFormat();
        dto.sizeBytes = f.getSizeBytes();
        dto.durationSeconds = f.getDurationSeconds();
        dto.resolution = f.getResolution();
        dto.filePath = f.getFilePath();
        dto.jobId = f.getJobId();
        dto.createdAt = f.getCreatedAt();
        return dto;
    }
}
