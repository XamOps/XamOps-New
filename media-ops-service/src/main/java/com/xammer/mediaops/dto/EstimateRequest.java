package com.xammer.mediaops.dto;

import lombok.Data;

@Data
public class EstimateRequest {
    private long videoBitrate;
    private long audioBitrate;
    private int durationSeconds;
}
