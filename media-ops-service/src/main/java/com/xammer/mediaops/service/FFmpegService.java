package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodeJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FFmpegService {

    private static final Logger log = LoggerFactory.getLogger(FFmpegService.class);
    private static final Pattern TIME_PATTERN     = Pattern.compile("time=(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d+)");
    private static final Pattern DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d+)");

    @Value("${mediaops.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${mediaops.storage.base-dir:/tmp/xamops-media}")
    private String baseStorageDir;

    @Value("${mediaops.rtmp.base-url:rtmp://localhost/live}")
    private String rtmpBaseUrl;

    @Value("${mediaops.hls.base-url:http://localhost:8084/media}")
    private String hlsBaseUrl;

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(baseStorageDir, "jobs"));
            Files.createDirectories(Paths.get(baseStorageDir, "streams"));
            log.info("[MediaOps] Storage ready: {}", baseStorageDir);
        } catch (IOException e) {
            log.warn("[MediaOps] Could not create storage dirs: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-version").redirectErrorStream(true).start();
            p.waitFor(5, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public int probeDurationSeconds(String sourceUrl) {
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-i", sourceUrl)
                    .redirectErrorStream(true).start();
            String out = readAll(p.getInputStream());
            p.waitFor(30, TimeUnit.SECONDS);
            Matcher m = DURATION_PATTERN.matcher(out);
            if (m.find()) {
                return Integer.parseInt(m.group(1)) * 3600
                     + Integer.parseInt(m.group(2)) * 60
                     + Integer.parseInt(m.group(3));
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public boolean hasAudioStream(String sourceUrl) {
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-i", sourceUrl)
                    .redirectErrorStream(true).start();
            String out = readAll(p.getInputStream());
            p.waitFor(15, TimeUnit.SECONDS);
            return out.contains("Audio:");
        } catch (Exception ignored) {}
        return false;
    }

    public boolean transcode(TranscodeJob job, Consumer<Integer> progressCallback) {
        String outputDir = Paths.get(baseStorageDir, "jobs", String.valueOf(job.getId())).toString();
        try { Files.createDirectories(Paths.get(outputDir)); }
        catch (IOException e) { log.error("[MediaOps] Cannot create output dir: {}", e.getMessage()); return false; }

        List<String> cmd = buildTranscodeCommand(job, outputDir);
        job.setFfmpegCommand(String.join(" ", cmd));
        log.info("[MediaOps] Transcoding job {}: {}", job.getId(), job.getFfmpegCommand());

        int totalSeconds = job.getDurationSeconds() != null ? job.getDurationSeconds() : 0;
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder ffmpegOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ffmpegOutput.append(line).append('\n');
                    if (totalSeconds > 0) {
                        Matcher m = TIME_PATTERN.matcher(line);
                        if (m.find()) {
                            int elapsed = Integer.parseInt(m.group(1)) * 3600
                                        + Integer.parseInt(m.group(2)) * 60
                                        + Integer.parseInt(m.group(3));
                            progressCallback.accept(Math.min(99, elapsed * 100 / totalSeconds));
                        }
                    }
                }
            }
            boolean done = process.waitFor(6, TimeUnit.HOURS);
            if (!done) {
                process.destroyForcibly();
                job.setErrorMessage("FFmpeg timed out after 6 hours.");
                return false;
            }
            if (process.exitValue() != 0) {
                log.error("[MediaOps] FFmpeg job {} exited with code {}. Output:\n{}",
                        job.getId(), process.exitValue(), ffmpegOutput);
                job.setErrorMessage(extractErrorSummary(ffmpegOutput.toString(), process.exitValue()));
                return false;
            }
            job.setOutputPath(outputDir);
            progressCallback.accept(100);
            return true;
        } catch (Exception e) {
            log.error("[MediaOps] Transcode job {} failed: {}", job.getId(), e.getMessage());
            job.setErrorMessage("Exception: " + e.getMessage());
            return false;
        }
    }

    public Process startLiveStream(String streamKey, String resolution, String bitrate, String hlsOutputDir)
            throws IOException {
        Files.createDirectories(Paths.get(hlsOutputDir));
        String[] wh = toWH(resolution);
        List<String> cmd = Arrays.asList(
                ffmpegPath, "-i", rtmpBaseUrl + "/" + streamKey,
                "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
                "-vf", "scale=" + wh[0] + ":" + wh[1],
                "-b:v", bitrate, "-c:a", "aac", "-b:a", "128k",
                "-f", "hls", "-hls_time", "4", "-hls_list_size", "0",
                Paths.get(hlsOutputDir, "index.m3u8").toString()
        );
        log.info("[MediaOps] Starting stream {}: {}", streamKey, String.join(" ", cmd));
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    public String getRtmpBaseUrl()    { return rtmpBaseUrl; }
    public String getHlsBaseUrl()     { return hlsBaseUrl; }
    public String getBaseStorageDir() { return baseStorageDir; }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private List<String> buildTranscodeCommand(TranscodeJob job, String outputDir) {
        List<String> cmd = new ArrayList<>(Arrays.asList(ffmpegPath, "-i", job.getSourceUrl(), "-y"));
        String[] wh = toWH(job.getResolution());
        String vb = job.getBitrate() != null ? job.getBitrate() : "4500k";

        switch (job.getOutputFormat()) {
            case HLS:
                if (job.isEnableAdaptive()) {
                    return buildAdaptiveHls(job, outputDir);
                }
                cmd.addAll(Arrays.asList(
                        "-c:v", "libx264", "-preset", "fast",
                        "-vf", "scale=" + wh[0] + ":" + wh[1],
                        "-b:v", vb, "-c:a", "aac", "-b:a", "128k",
                        "-f", "hls", "-hls_time", "10", "-hls_list_size", "0",
                        Paths.get(outputDir, "index.m3u8").toString()));
                break;
            case DASH:
                if (job.isEnableAdaptive()) {
                    return buildAdaptiveDash(job, outputDir);
                }
                cmd.addAll(Arrays.asList(
                        "-c:v", "libx264", "-preset", "fast",
                        "-vf", "scale=" + wh[0] + ":" + wh[1],
                        "-b:v", vb, "-c:a", "aac", "-b:a", "128k",
                        "-f", "dash", "-seg_duration", "10",
                        Paths.get(outputDir, "manifest.mpd").toString()));
                break;
            case WEBM:
                cmd.addAll(Arrays.asList(
                        "-c:v", "libvpx-vp9",
                        "-vf", "scale=" + wh[0] + ":" + wh[1],
                        "-b:v", vb, "-c:a", "libopus", "-b:a", "128k",
                        Paths.get(outputDir, "output.webm").toString()));
                break;
            default:
                cmd.addAll(Arrays.asList(
                        "-c:v", "libx264", "-preset", "fast",
                        "-vf", "scale=" + wh[0] + ":" + wh[1],
                        "-b:v", vb, "-c:a", "aac", "-b:a", "128k",
                        Paths.get(outputDir, "output." + job.getOutputFormat().name().toLowerCase()).toString()));
        }
        return cmd;
    }

    private List<String> buildAdaptiveHls(TranscodeJob job, String outputDir) {
        boolean hasAudio = hasAudioStream(job.getSourceUrl());
        List<String> cmd = new ArrayList<>(Arrays.asList(
                ffmpegPath, "-i", job.getSourceUrl(), "-y",
                "-filter_complex",
                "[0:v]split=3[v1][v2][v3];" +
                "[v1]scale=1920:1080[v1out];" +
                "[v2]scale=1280:720[v2out];" +
                "[v3]scale=854:480[v3out]",
                "-map", "[v1out]", "-c:v:0", "libx264", "-b:v:0", "4500k",
                "-map", "[v2out]", "-c:v:1", "libx264", "-b:v:1", "2500k",
                "-map", "[v3out]", "-c:v:2", "libx264", "-b:v:2", "1000k"
        ));
        if (hasAudio) {
            cmd.addAll(Arrays.asList("-map", "0:a", "-map", "0:a", "-map", "0:a",
                    "-c:a", "aac", "-b:a", "128k"));
        }
        cmd.addAll(Arrays.asList(
                "-var_stream_map", hasAudio ? "v:0,a:0 v:1,a:1 v:2,a:2" : "v:0 v:1 v:2",
                "-master_pl_name", "master.m3u8",
                "-f", "hls", "-hls_time", "10", "-hls_list_size", "0",
                "-hls_segment_filename", Paths.get(outputDir, "stream_%v_%03d.ts").toString(),
                Paths.get(outputDir, "stream_%v.m3u8").toString()
        ));
        return cmd;
    }

    private List<String> buildAdaptiveDash(TranscodeJob job, String outputDir) {
        boolean hasAudio = hasAudioStream(job.getSourceUrl());
        List<String> cmd = new ArrayList<>(Arrays.asList(
                ffmpegPath, "-i", job.getSourceUrl(), "-y",
                "-filter_complex",
                "[0:v]split=3[v1][v2][v3];" +
                "[v1]scale=1920:1080[v1out];" +
                "[v2]scale=1280:720[v2out];" +
                "[v3]scale=854:480[v3out]",
                "-map", "[v1out]", "-c:v:0", "libx264", "-b:v:0", "4500k",
                "-map", "[v2out]", "-c:v:1", "libx264", "-b:v:1", "2500k",
                "-map", "[v3out]", "-c:v:2", "libx264", "-b:v:2", "1000k"
        ));
        if (hasAudio) {
            cmd.addAll(Arrays.asList("-map", "0:a", "-c:a", "aac", "-b:a", "128k"));
        }
        cmd.addAll(Arrays.asList(
                "-f", "dash", "-seg_duration", "10",
                "-use_template", "1", "-use_timeline", "1",
                Paths.get(outputDir, "manifest.mpd").toString()
        ));
        return cmd;
    }

    private String[] toWH(String res) {
        if (res == null) return new String[]{"1920", "1080"};
        switch (res) {
            case "4K": case "4K (2160p)": return new String[]{"3840", "2160"};
            case "1440p":                 return new String[]{"2560", "1440"};
            case "1080p":                 return new String[]{"1920", "1080"};
            case "720p":                  return new String[]{"1280", "720"};
            case "480p":                  return new String[]{"854",  "480"};
            case "360p":                  return new String[]{"640",  "360"};
            default:                      return new String[]{"-2",   "1080"};
        }
    }

    private String extractErrorSummary(String output, int exitCode) {
        String[] lines = output.trim().split("\\n");
        // Prefer lines that contain recognisable error keywords
        List<String> errorLines = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String lo = t.toLowerCase();
            if (lo.contains("error") || lo.contains("invalid") || lo.contains("no such")
                    || lo.contains("not found") || lo.contains("unable")) {
                errorLines.add(t);
                if (errorLines.size() == 3) break;
            }
        }
        if (errorLines.isEmpty()) {
            // Fall back to last 2 non-empty lines
            for (int i = lines.length - 1; i >= 0 && errorLines.size() < 2; i--) {
                String t = lines[i].trim();
                if (!t.isEmpty()) errorLines.add(0, t);
            }
        }
        String detail = String.join(" | ", errorLines);
        return "FFmpeg exit code " + exitCode + (detail.isEmpty() ? "" : ": " + detail);
    }

    private String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
