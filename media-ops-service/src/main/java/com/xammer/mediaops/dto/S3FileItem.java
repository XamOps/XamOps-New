package com.xammer.mediaops.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class S3FileItem {
    private String key;
    private String name;
    private long sizeBytes;
    private String formattedSize;
    private Instant lastModified;
    private String extension;
}
