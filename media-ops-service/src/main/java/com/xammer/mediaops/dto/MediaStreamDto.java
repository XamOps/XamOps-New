package com.xammer.mediaops.dto;

import com.xammer.mediaops.domain.MediaStream;
import lombok.Data;

import java.time.Instant;

@Data
public class MediaStreamDto {

    private Long id;
    private String name;
    private String streamKey;
    private String status;
    private String resolution;
    private String bitrate;
    private String rtmpUrl;
    private String hlsUrl;
    private int viewers;
    private long bandwidthBytes;
    private int uptimeSeconds;
    private String errorMessage;
    private Instant createdAt;
    private Instant startedAt;
    private Instant stoppedAt;

    public static MediaStreamDto from(MediaStream s) {
        MediaStreamDto dto = new MediaStreamDto();
        dto.id = s.getId();
        dto.name = s.getName();
        dto.streamKey = s.getStreamKey();
        dto.status = s.getStatus() != null ? s.getStatus().name() : null;
        dto.resolution = s.getResolution();
        dto.bitrate = s.getBitrate();
        // Always rebuild the full RTMP ingest URL so old DB rows get the correct port
        String base = s.getRtmpUrl() != null ? s.getRtmpUrl() : "rtmp://localhost:1935/live";
        if (!base.matches(".*:\\d+/.*")) {
            // missing port — insert 1935 between host and path
            base = base.replaceFirst("(rtmp://[^/]+)(/.*)", "$1:1935$2");
        }
        dto.rtmpUrl = base + "/" + s.getStreamKey();
        dto.hlsUrl = s.getHlsUrl();
        dto.viewers = s.getViewers();
        dto.bandwidthBytes = s.getBandwidthBytes();
        dto.uptimeSeconds = s.getUptimeSeconds();
        dto.errorMessage = s.getErrorMessage();
        dto.createdAt = s.getCreatedAt();
        dto.startedAt = s.getStartedAt();
        dto.stoppedAt = s.getStoppedAt();
        return dto;
    }
}
