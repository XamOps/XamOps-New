package com.xammer.mediaops.dto;

import lombok.Data;

import java.util.List;

@Data
public class BatchRequest {
    private String name;
    private List<TranscodeJobRequest> jobs;
    private TranscodeJobRequest sharedConfig;
}
