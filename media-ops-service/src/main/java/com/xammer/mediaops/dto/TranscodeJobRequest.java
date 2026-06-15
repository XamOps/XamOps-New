package com.xammer.mediaops.dto;

import lombok.Data;

@Data
public class TranscodeJobRequest {
    private String sourceUrl;
    private String outputFormat = "HLS";
    private String resolution = "1080p";
    private String bitrate;
    private Integer fps;
    private boolean enableAdaptive = false;
}
