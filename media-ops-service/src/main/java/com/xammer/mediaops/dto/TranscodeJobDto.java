package com.xammer.mediaops.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.mediaops.domain.TranscodeJob;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Data
public class TranscodeJobDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    // Filenames of generated thumbnails (e.g. ["thumb_0001.jpg", "poster.jpg"]).
    // The frontend resolves each to /media/jobs/{id}/thumbnails/{filename}.
    private List<String> thumbnailPaths;

    /**
     * Parses the entity's stored JSON array of absolute thumbnail paths into a list
     * of bare filenames the frontend can turn into /media/... URLs. Returns an empty
     * list when no thumbnails were generated or the stored value can't be parsed.
     */
    private static List<String> parseThumbnailFilenames(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            String[] paths = MAPPER.readValue(json, String[].class);
            List<String> names = new ArrayList<>(paths.length);
            for (String p : paths) {
                if (p == null || p.isBlank()) continue;
                String normalized = p.replace('\\', '/');
                int slash = normalized.lastIndexOf('/');
                names.add(slash >= 0 ? normalized.substring(slash + 1) : normalized);
            }
            return names;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

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
        dto.thumbnailPaths = parseThumbnailFilenames(j.getThumbnailPaths());
        return dto;
    }
}
