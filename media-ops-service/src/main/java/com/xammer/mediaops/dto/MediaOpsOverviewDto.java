package com.xammer.mediaops.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MediaOpsOverviewDto {
    private long liveStreams;
    private long processingJobs;
    private long queuedJobs;
    private long completedJobs;
    private long failedJobs;
    private long totalViewers;
    private long storageUsedBytes;
    private BigDecimal totalSavedVsAws;
}
