package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.MediaStream;
import com.xammer.mediaops.dto.MediaStreamRequest;
import com.xammer.mediaops.repository.MediaStreamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MediaStreamService {

    private static final Logger log = LoggerFactory.getLogger(MediaStreamService.class);

    private final MediaStreamRepository streamRepo;
    private final FFmpegService ffmpegService;
    private final Map<Long, Process> activeProcesses = new ConcurrentHashMap<>();

    public MediaStreamService(MediaStreamRepository streamRepo, FFmpegService ffmpegService) {
        this.streamRepo = streamRepo;
        this.ffmpegService = ffmpegService;
    }

    @Transactional
    public MediaStream createStream(Long clientId, MediaStreamRequest req) {
        MediaStream s = new MediaStream();
        s.setClientId(clientId);
        s.setName(req.getName());
        s.setResolution(req.getResolution());
        s.setBitrate(req.getBitrate() != null ? req.getBitrate() : "4500k");
        s.setStreamKey("sk_live_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        s.setRtmpUrl(ffmpegService.getRtmpBaseUrl());
        s.setStatus(MediaStream.StreamStatus.IDLE);
        return streamRepo.save(s);
    }

    @Transactional
    public Optional<MediaStream> startStream(Long id, Long clientId) {
        return streamRepo.findByIdAndClientId(id, clientId).map(s -> {
            if (s.getStatus() == MediaStream.StreamStatus.LIVE) return s;
            String hlsDir = Paths.get(ffmpegService.getBaseStorageDir(), "streams", s.getStreamKey()).toString();
            s.setHlsOutputDir(hlsDir);
            s.setHlsUrl(ffmpegService.getHlsBaseUrl() + "/streams/" + s.getStreamKey() + "/index.m3u8");
            try {
                Process proc = ffmpegService.startLiveStream(s.getStreamKey(), s.getResolution(), s.getBitrate(), hlsDir);
                activeProcesses.put(s.getId(), proc);
                s.setStatus(MediaStream.StreamStatus.LIVE);
                s.setStartedAt(Instant.now());
                s.setErrorMessage(null);
            } catch (IOException e) {
                s.setStatus(MediaStream.StreamStatus.ERROR);
                s.setErrorMessage("Failed to start FFmpeg: " + e.getMessage());
                log.error("[MediaOps] Stream {} start error: {}", id, e.getMessage());
            }
            return streamRepo.save(s);
        });
    }

    @Transactional
    public Optional<MediaStream> stopStream(Long id, Long clientId) {
        return streamRepo.findByIdAndClientId(id, clientId).map(s -> {
            Process proc = activeProcesses.remove(s.getId());
            if (proc != null && proc.isAlive()) proc.destroy();
            s.setStatus(MediaStream.StreamStatus.IDLE);
            s.setStoppedAt(Instant.now());
            if (s.getStartedAt() != null) {
                int uptime = (int)(Instant.now().getEpochSecond() - s.getStartedAt().getEpochSecond());
                s.setUptimeSeconds(s.getUptimeSeconds() + uptime);
            }
            return streamRepo.save(s);
        });
    }

    @Transactional
    public boolean deleteStream(Long id, Long clientId) {
        return streamRepo.findByIdAndClientId(id, clientId).map(s -> {
            Process proc = activeProcesses.remove(id);
            if (proc != null && proc.isAlive()) proc.destroy();
            streamRepo.delete(s);
            return true;
        }).orElse(false);
    }

    public List<MediaStream> listStreams(Long clientId) {
        return streamRepo.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    public Optional<MediaStream> getStream(Long id, Long clientId) {
        return streamRepo.findByIdAndClientId(id, clientId);
    }
}
