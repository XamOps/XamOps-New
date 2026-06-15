package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.MediaStream;
import com.xammer.mediaops.repository.MediaStreamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class BrowserStreamHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BrowserStreamHandler.class);

    @Value("${mediaops.storage.base-dir:/tmp/xamops-media}")
    private String baseStorageDir;

    @Value("${mediaops.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${mediaops.hls.base-url:http://localhost:8084/media}")
    private String hlsBaseUrl;

    private final MediaStreamRepository streamRepo;

    private final Map<String, Process>      ffmpegProcesses = new ConcurrentHashMap<>();
    private final Map<String, OutputStream> ffmpegInputs    = new ConcurrentHashMap<>();
    private final Map<String, String>       sessionToKey    = new ConcurrentHashMap<>();

    public BrowserStreamHandler(MediaStreamRepository streamRepo) {
        this.streamRepo = streamRepo;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String streamKey = extractStreamKey(session);
        if (streamKey == null || streamKey.isBlank()) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        sessionToKey.put(session.getId(), streamKey);

        // Prepare HLS output directory
        java.nio.file.Path hlsDir = Paths.get(baseStorageDir, "streams", streamKey);
        Files.createDirectories(hlsDir);

        // FFmpeg: stdin pipe → H.264/AAC → HLS segments
        List<String> cmd = Arrays.asList(
                ffmpegPath, "-y",
                "-i", "pipe:0",
                "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-b:v", "1500k", "-maxrate", "1500k", "-bufsize", "3000k",
                "-c:a", "aac", "-b:a", "128k",
                "-f", "hls",
                "-hls_time", "4",
                "-hls_list_size", "0",
                "-hls_segment_filename", hlsDir.resolve("seg_%03d.ts").toString(),
                hlsDir.resolve("index.m3u8").toString()
        );

        Process ffmpeg = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

        ffmpegProcesses.put(streamKey, ffmpeg);
        ffmpegInputs.put(streamKey, new BufferedOutputStream(ffmpeg.getOutputStream(), 65536));

        // Drain FFmpeg stdout/stderr so it doesn't block
        Thread drain = new Thread(() -> {
            try (InputStream is = ffmpeg.getInputStream()) {
                byte[] buf = new byte[4096];
                int read;
                while ((read = is.read(buf)) != -1) {
                    if (log.isDebugEnabled()) {
                        log.debug("[BrowserStream:{}] ffmpeg: {}", streamKey,
                                new String(buf, 0, read).trim());
                    }
                }
            } catch (IOException ignored) {}
        });
        drain.setDaemon(true);
        drain.start();

        // Mark stream LIVE in DB
        String hlsUrl = hlsBaseUrl + "/streams/" + streamKey + "/index.m3u8";
        streamRepo.findByStreamKey(streamKey).ifPresent(s -> {
            s.setStatus(MediaStream.StreamStatus.LIVE);
            s.setHlsUrl(hlsUrl);
            s.setHlsOutputDir(hlsDir.toString());
            s.setStartedAt(Instant.now());
            s.setErrorMessage(null);
            streamRepo.save(s);
        });

        log.info("[BrowserStream] Stream started: key={}, hlsUrl={}", streamKey, hlsUrl);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String streamKey = sessionToKey.get(session.getId());
        if (streamKey == null) return;

        OutputStream ffmpegIn = ffmpegInputs.get(streamKey);
        if (ffmpegIn == null) return;

        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        try {
            ffmpegIn.write(bytes);
            ffmpegIn.flush();
        } catch (IOException e) {
            log.warn("[BrowserStream] Write to FFmpeg failed for key {}: {}", streamKey, e.getMessage());
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String streamKey = sessionToKey.remove(session.getId());
        if (streamKey == null) return;

        // Close stdin → FFmpeg flushes and exits cleanly
        OutputStream ffmpegIn = ffmpegInputs.remove(streamKey);
        if (ffmpegIn != null) {
            try { ffmpegIn.close(); } catch (IOException ignored) {}
        }

        Process proc = ffmpegProcesses.remove(streamKey);
        if (proc != null) {
            try { proc.waitFor(8, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            proc.destroyForcibly();
        }

        // Mark stream IDLE in DB
        streamRepo.findByStreamKey(streamKey).ifPresent(s -> {
            s.setStatus(MediaStream.StreamStatus.IDLE);
            s.setStoppedAt(Instant.now());
            if (s.getStartedAt() != null) {
                long elapsed = Instant.now().getEpochSecond() - s.getStartedAt().getEpochSecond();
                s.setUptimeSeconds(s.getUptimeSeconds() + (int) elapsed);
            }
            streamRepo.save(s);
        });

        log.info("[BrowserStream] Stream ended: key={}, status={}", streamKey, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.warn("[BrowserStream] Transport error session {}: {}", session.getId(), ex.getMessage());
    }

    private String extractStreamKey(WebSocketSession session) {
        if (session.getUri() == null) return null;
        String path = session.getUri().getPath();
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : null;
    }
}
