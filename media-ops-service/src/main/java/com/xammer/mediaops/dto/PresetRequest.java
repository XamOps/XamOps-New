package com.xammer.mediaops.dto;

import lombok.Data;

@Data
public class PresetRequest {
    private String name;
    private String outputFormat;
    private String resolution;
    private String bitrate;
    private Integer fps;
    private String codec;
    private String audioBitrate;
    private String customFfmpegParams;
}
