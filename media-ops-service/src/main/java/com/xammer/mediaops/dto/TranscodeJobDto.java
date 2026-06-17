package com.xammer.mediaops.dto;

import com.xammer.mediaops.domain.TranscodeJob;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class TranscodeJobDto {

    private Long id;
    private String name;
    private String sourceUrl;
    private String outputFormat;
    private String resolution;
    private String bitrate;
    private Integer fps;
    private boolean enableAdaptive;
    private String status;
    private int progress;
    private String inputFormat;
    private Long inputSizeBytes;
    private Long outputSizeBytes;
    private Integer durationSeconds;
    private String outputPath;
    private String hlsUrl;
    private String dashUrl;
    private String videoUrl;
    private String ffmpegCommand;
    private String errorMessage;
    private BigDecimal awsEquivalentCost;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;

    public static TranscodeJobDto from(TranscodeJob j) {
        TranscodeJobDto dto = new TranscodeJobDto();
        dto.id = j.getId();
        dto.name = j.getName();
        dto.sourceUrl = j.getSourceUrl();
        dto.outputFormat = j.getOutputFormat() != null ? j.getOutputFormat().name() : null;
        dto.resolution = j.getResolution();
        dto.bitrate = j.getBitrate();
        dto.fps = j.getFps();
        dto.enableAdaptive = j.isEnableAdaptive();
        dto.status = j.getStatus() != null ? j.getStatus().name() : null;
        dto.progress = j.getProgress();
        dto.inputFormat = j.getInputFormat();
        dto.inputSizeBytes = j.getInputSizeBytes();
        dto.outputSizeBytes = j.getOutputSizeBytes();
        dto.durationSeconds = j.getDurationSeconds();
        dto.outputPath = j.getOutputPath();
        if (j.getOutputPath() != null || j.getOutputUrl() != null) {
            // S3 URL takes priority; fall back to local /media/ path
            String base = j.getOutputUrl() != null
                    ? j.getOutputUrl()
                    : "/media/jobs/" + j.getId();
            if (j.getOutputFormat() == TranscodeJob.OutputFormat.HLS) {
                dto.hlsUrl = base + (j.isEnableAdaptive() ? "/master.m3u8" : "/index.m3u8");
            } else if (j.getOutputFormat() == TranscodeJob.OutputFormat.DASH) {
                dto.dashUrl = base + "/manifest.mpd";
            } else {
                // MP4, WEBM, MKV, MOV — single output file playable natively in <video>
                String ext = j.getOutputFormat().name().toLowerCase();
                dto.videoUrl = base + "/output." + ext;
            }
        }
        dto.ffmpegCommand = j.getFfmpegCommand();
        dto.errorMessage = j.getErrorMessage();
        dto.awsEquivalentCost = j.getAwsEquivalentCost();
        dto.createdAt = j.getCreatedAt();
        dto.startedAt = j.getStartedAt();
        dto.completedAt = j.getCompletedAt();
        return dto;
    }
}
