package com.xammer.mediaops.dto;

import lombok.Data;

@Data
public class FileProbeResult {
    private String videoCodec;
    private String audioCodec;
    private String resolution;
    private long bitrate;
    private double frameRate;
    private int audioSampleRate;
    private int audioChannels;
    private int durationSeconds;
    private long fileSizeBytes;
}
