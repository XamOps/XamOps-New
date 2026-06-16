package com.xammer.mediaops.dto;

import com.xammer.mediaops.domain.TranscodeBatch;
import lombok.Data;

import java.time.Instant;

@Data
public class BatchResponse {

    private Long id;
    private Long clientId;
    private String name;
    private int totalJobs;
    private int completedJobs;
    private int failedJobs;
    private TranscodeBatch.BatchStatus status;
    private Instant createdAt;
    private Instant completedAt;
    private int aggregateProgress;

    public static BatchResponse from(TranscodeBatch batch, int aggregateProgress) {
        BatchResponse response = new BatchResponse();
        response.setId(batch.getId());
        response.setClientId(batch.getClientId());
        response.setName(batch.getName());
        response.setTotalJobs(batch.getTotalJobs());
        response.setCompletedJobs(batch.getCompletedJobs());
        response.setFailedJobs(batch.getFailedJobs());
        response.setStatus(batch.getStatus());
        response.setCreatedAt(batch.getCreatedAt());
        response.setCompletedAt(batch.getCompletedAt());
        response.setAggregateProgress(aggregateProgress);
        return response;
    }
}
