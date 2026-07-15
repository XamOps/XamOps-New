package com.xammer.mediaops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.domain.TranscodeJob.OutputFormat;
import com.xammer.mediaops.domain.TranscodeJob.SubtitleMode;
import com.xammer.mediaops.domain.TranscodeJob.WatermarkPosition;
import com.xammer.mediaops.dto.FileProbeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    @Value("${mediaops.ffprobe.path:#{null}}")
    private String ffprobePath;

    @Value("${mediaops.storage.base-dir:/tmp/xamops-media}")
    private String baseStorageDir;

    @Value("${mediaops.rtmp.base-url:rtmp://localhost/live}")
    private String rtmpBaseUrl;

    @Value("${mediaops.hls.base-url:http://localhost:8084/media}")
    private String hlsBaseUrl;

    // x264/x265 presets a job may select via encodingSpeed. Whitelist — the value
    // lands on the ffmpeg command line, so never pass it through unvalidated.
    private static final Set<String> ALLOWED_PRESETS = Set.of("fast", "veryfast", "superfast", "ultrafast");

    // Live FFmpeg processes by job id, so running jobs can be paused/stopped.
    private final Map<Long, Process> activeProcesses = new ConcurrentHashMap<>();
    // Jobs whose process was killed by an explicit user stop — lets processJobAsync
    // distinguish CANCELLED from FAILED when transcode() returns false.
    private final Set<Long> stopRequested = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        // Resolve ffprobe path: use configured value or derive from ffmpeg path
        if (ffprobePath == null || ffprobePath.isEmpty()) {
            if (ffmpegPath.contains(File.separator)) {
                ffprobePath = ffmpegPath.replace("ffmpeg", "ffprobe");
            } else {
                ffprobePath = "ffprobe";
            }
        }
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

    /**
     * Runs FFprobe to extract file metadata and returns a structured FileProbeResult.
     * Executes: ffprobe -v quiet -print_format json -show_format -show_streams <sourceUrl>
     *
     * @param sourceUrl path or URL of the media file to probe
     * @return FileProbeResult containing video/audio metadata
     * @throws RuntimeException with "probe-failed" message if FFprobe fails
     */
    public FileProbeResult probeFile(String sourceUrl) {
        if (sourceUrl.startsWith("-")) {
            throw new IllegalArgumentException("Invalid source URL");
        }
        List<String> cmd = Arrays.asList(
                ffprobePath, "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams",
                "-protocol_whitelist", "file,https",
                "--", sourceUrl
        );
        log.info("[MediaOps] Probing file: {}", String.join(" ", cmd));

        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("probe-failed: FFprobe timed out after 30 seconds");
            }

            if (process.exitValue() != 0) {
                log.error("[MediaOps] FFprobe failed with exit code {}. Error: {}", process.exitValue(), stderr);
                throw new RuntimeException("probe-failed: " + (stderr.isEmpty() ? "FFprobe exited with code " + process.exitValue() : stderr.trim()));
            }

            return parseProbeOutput(stdout);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[MediaOps] FFprobe execution failed: {}", e.getMessage());
            throw new RuntimeException("probe-failed: " + e.getMessage(), e);
        }
    }

    /**
     * Parses FFprobe JSON output into a FileProbeResult DTO.
     */
    private FileProbeResult parseProbeOutput(String jsonOutput) {
        FileProbeResult result = new FileProbeResult();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonOutput);

            // Parse streams
            JsonNode streams = root.get("streams");
            if (streams != null && streams.isArray()) {
                for (JsonNode stream : streams) {
                    String codecType = stream.has("codec_type") ? stream.get("codec_type").asText() : "";
                    if ("video".equals(codecType) && result.getVideoCodec() == null) {
                        result.setVideoCodec(stream.has("codec_name") ? stream.get("codec_name").asText() : null);
                        int width = stream.has("width") ? stream.get("width").asInt() : 0;
                        int height = stream.has("height") ? stream.get("height").asInt() : 0;
                        if (width > 0 && height > 0) {
                            result.setResolution(width + "x" + height);
                        }
                        if (stream.has("r_frame_rate")) {
                            result.setFrameRate(parseFrameRate(stream.get("r_frame_rate").asText()));
                        }
                    } else if ("audio".equals(codecType) && result.getAudioCodec() == null) {
                        result.setAudioCodec(stream.has("codec_name") ? stream.get("codec_name").asText() : null);
                        if (stream.has("sample_rate")) {
                            result.setAudioSampleRate(Integer.parseInt(stream.get("sample_rate").asText()));
                        }
                        if (stream.has("channels")) {
                            result.setAudioChannels(stream.get("channels").asInt());
                        }
                    }
                }
            }

            // Parse format
            JsonNode format = root.get("format");
            if (format != null) {
                if (format.has("duration")) {
                    double duration = Double.parseDouble(format.get("duration").asText());
                    result.setDurationSeconds((int) Math.round(duration));
                }
                if (format.has("size")) {
                    result.setFileSizeBytes(Long.parseLong(format.get("size").asText()));
                }
                if (format.has("bit_rate")) {
                    result.setBitrate(Long.parseLong(format.get("bit_rate").asText()));
                }
            }

            return result;
        } catch (Exception e) {
            log.error("[MediaOps] Failed to parse FFprobe output: {}", e.getMessage());
            throw new RuntimeException("probe-failed: Failed to parse FFprobe output - " + e.getMessage(), e);
        }
    }

    /**
     * Parses frame rate from FFprobe r_frame_rate format (e.g., "30000/1001" or "25/1").
     */
    private double parseFrameRate(String frameRateStr) {
        if (frameRateStr == null || frameRateStr.isEmpty()) return 0.0;
        if (frameRateStr.contains("/")) {
            String[] parts = frameRateStr.split("/");
            if (parts.length == 2) {
                double numerator = Double.parseDouble(parts[0]);
                double denominator = Double.parseDouble(parts[1]);
                if (denominator != 0) {
                    return numerator / denominator;
                }
            }
        }
        try {
            return Double.parseDouble(frameRateStr);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public boolean transcode(TranscodeJob job, Consumer<Integer> progressCallback) {
        String outputDir = Paths.get(baseStorageDir, "jobs", String.valueOf(job.getId())).toString();
        try { Files.createDirectories(Paths.get(outputDir)); }
        catch (IOException e) { log.error("[MediaOps] Cannot create output dir: {}", e.getMessage()); return false; }

        // For audio-only formats, verify the source actually has an audio stream before attempting
        if (isAudioOnlyFormat(job.getOutputFormat())) {
            try {
                FileProbeResult probe = probeFile(job.getSourceUrl());
                if (probe.getAudioCodec() == null || probe.getAudioCodec().isEmpty()) {
                    String msg = "Source file has no audio stream — cannot extract audio to "
                            + job.getOutputFormat() + ". Use a video-to-video format instead.";
                    log.warn("[MediaOps] Job {} skipped: {}", job.getId(), msg);
                    job.setErrorMessage(msg);
                    return false;
                }
            } catch (Exception e) {
                log.warn("[MediaOps] Job {} probe check failed ({}); proceeding with FFmpeg", job.getId(), e.getMessage());
            }
        }

        List<String> cmd;
        try {
            cmd = buildTranscodeCommand(job, outputDir);
        } catch (RuntimeException e) {
            // e.g. subtitle burn-in requested but ffmpeg lacks libass — surface a clear message
            log.warn("[MediaOps] Job {} could not be built: {}", job.getId(), e.getMessage());
            job.setErrorMessage(e.getMessage());
            return false;
        }
        job.setFfmpegCommand(String.join(" ", cmd));
        log.info("[MediaOps] Transcoding job {}: {}", job.getId(), job.getFfmpegCommand());

        int totalSeconds = job.getDurationSeconds() != null ? job.getDurationSeconds() : 0;
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            activeProcesses.put(job.getId(), process);
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
            if (stopRequested.contains(job.getId())) {
                log.info("[MediaOps] Job {} stopped by user", job.getId());
                job.setErrorMessage("Stopped by user.");
                return false;
            }
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
        } finally {
            activeProcesses.remove(job.getId());
        }
    }

    // ─── Running-job process control (stop / pause / resume) ─────────────────

    /** Kills the running FFmpeg process for a job. Returns false if none is running. */
    public boolean stopJobProcess(long jobId) {
        Process p = activeProcesses.get(jobId);
        if (p == null || !p.isAlive()) return false;
        stopRequested.add(jobId);
        signal(p, "-CONT"); // wake a paused process so its pipes drain and it dies cleanly
        p.destroyForcibly();
        return true;
    }

    /** SIGSTOPs the running FFmpeg process — frees CPU, keeps all encode state. */
    public boolean pauseJobProcess(long jobId) {
        Process p = activeProcesses.get(jobId);
        return p != null && p.isAlive() && signal(p, "-STOP");
    }

    /** SIGCONTs a paused FFmpeg process — encoding resumes where it left off. */
    public boolean resumeJobProcess(long jobId) {
        Process p = activeProcesses.get(jobId);
        return p != null && p.isAlive() && signal(p, "-CONT");
    }

    /** True (once) if the job's process was killed by an explicit user stop. */
    public boolean consumeStopRequested(long jobId) {
        return stopRequested.remove(jobId);
    }

    private boolean signal(Process p, String sig) {
        try {
            Process kill = new ProcessBuilder("kill", sig, String.valueOf(p.pid())).start();
            return kill.waitFor(5, TimeUnit.SECONDS) && kill.exitValue() == 0;
        } catch (Exception e) {
            log.warn("[MediaOps] kill {} pid {} failed: {}", sig, p.pid(), e.getMessage());
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
                "-vf", "scale=" + wh[0] + ":" + wh[1] + ",format=yuv420p",
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

    /**
     * Builds the full FFmpeg transcode command with support for all advanced features:
     * trimming, subtitles, watermarks, codec selection, audio params, encryption, and custom params.
     */
    List<String> buildTranscodeCommand(TranscodeJob job, String outputDir) {
        // For adaptive streaming, delegate to specialized builders (no advanced overlay support)
        if (job.isEnableAdaptive()) {
            if (job.getOutputFormat() == OutputFormat.HLS) {
                return buildAdaptiveHls(job, outputDir);
            } else if (job.getOutputFormat() == OutputFormat.DASH) {
                return buildAdaptiveDash(job, outputDir);
            }
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);

        // Input seeking (before -i for fast seek)
        if (job.getStartTime() != null && !job.getStartTime().isEmpty()) {
            cmd.addAll(Arrays.asList("-ss", normalizeTime(job.getStartTime())));
        }

        // Primary input — reject leading-dash URLs to prevent flag injection
        if (job.getSourceUrl() != null && job.getSourceUrl().startsWith("-")) {
            throw new IllegalArgumentException("Invalid source URL");
        }
        cmd.addAll(Arrays.asList("-i", job.getSourceUrl()));

        // End time (after -i)
        if (job.getEndTime() != null && !job.getEndTime().isEmpty()) {
            cmd.addAll(Arrays.asList("-to", normalizeTime(job.getEndTime())));
        }

        // Watermark image as second input (must be added before filters)
        if (job.getWatermarkImagePath() != null && !job.getWatermarkImagePath().isEmpty()) {
            validateStoragePath(job.getWatermarkImagePath());
            cmd.addAll(Arrays.asList("-i", job.getWatermarkImagePath()));
        }

        // Determine if this is audio-only
        boolean audioOnly = isAudioOnlyFormat(job.getOutputFormat());

        if (audioOnly) {
            // Audio-only: disable video, select audio codec
            cmd.add("-vn");
            applyAudioCodec(cmd, job);
        } else {
            // Video processing: filters, codec, scale
            applyVideoFilters(cmd, job);
            applyVideoCodec(cmd, job);

            // Video bitrate
            String vb = job.getBitrate() != null ? job.getBitrate() : "4500k";
            cmd.addAll(Arrays.asList("-b:v", vb));
        }

        // Audio parameters (bitrate, sample rate, channels)
        applyAudioParams(cmd, job, audioOnly);

        // Format-specific options
        applyFormatOptions(cmd, job, outputDir);

        // Encryption for HLS
        if (job.isEncryptionEnabled() && job.getOutputFormat() == OutputFormat.HLS) {
            applyEncryption(cmd, job, outputDir);
        }

        // Custom FFmpeg parameters (appended before output path)
        if (job.getCustomFfmpegParams() != null && !job.getCustomFfmpegParams().trim().isEmpty()) {
            String[] params = job.getCustomFfmpegParams().trim().split("\\s+");
            cmd.addAll(Arrays.asList(params));
        }

        // Overwrite and output path
        cmd.add("-y");
        cmd.add(resolveOutputPath(job, outputDir));

        return cmd;
    }

    /**
     * Applies video filters including scale, subtitle burn-in, and watermarks.
     * Uses -filter_complex when multiple filter sources are present (e.g., watermark image + subtitle).
     */
    private void applyVideoFilters(List<String> cmd, TranscodeJob job) {
        boolean hasSubtitleBurnIn = job.getSubtitleMode() == SubtitleMode.BURN_IN
                && job.getSubtitlePath() != null && !job.getSubtitlePath().isEmpty();
        boolean hasWatermarkImage = job.getWatermarkImagePath() != null && !job.getWatermarkImagePath().isEmpty();
        if (hasSubtitleBurnIn) { requireSubtitlesFilter(); validateStoragePath(job.getSubtitlePath()); }
        boolean hasWatermarkText = !hasWatermarkImage
                && job.getWatermarkText() != null && !job.getWatermarkText().isEmpty();

        String[] wh = toWH(job.getResolution());
        String scaleFilter = "scale=" + wh[0] + ":" + wh[1];

        // Watermark width: user-configured % of output video width (default 15%), min 60px
        int userWmScale = (job.getWatermarkScale() != null && job.getWatermarkScale() > 0) ? job.getWatermarkScale() : 15;
        int wmWidth = Math.max(60, Integer.parseInt(wh[0].startsWith("-") ? "1920" : wh[0]) * userWmScale / 100);
        String wmScale = "scale=" + wmWidth + ":-2,";

        if (hasWatermarkImage && hasSubtitleBurnIn) {
            // Both watermark image and subtitle: use filter_complex
            String position = resolveOverlayPositionForJob(job);
            String escapedSubPath = escapeFilterPath(job.getSubtitlePath());

            String opacityFilter = "";
            if (job.getWatermarkOpacity() != null) {
                opacityFilter = String.format("format=rgba,colorchannelmixer=aa=%s,", job.getWatermarkOpacity());
            }

            String filterComplex = String.format(
                    "[0:v]%s,subtitles=%s[base];[1:v]%s%s[wm];[base][wm]overlay=%s",
                    scaleFilter, escapedSubPath, wmScale, opacityFilter, position
            );
            cmd.addAll(Arrays.asList("-filter_complex", filterComplex));
        } else if (hasWatermarkImage) {
            // Watermark image overlay only
            String position = resolveOverlayPositionForJob(job);

            String opacityFilter = "";
            if (job.getWatermarkOpacity() != null) {
                opacityFilter = String.format("format=rgba,colorchannelmixer=aa=%s,", job.getWatermarkOpacity());
            }

            String filterComplex = String.format(
                    "[0:v]%s[base];[1:v]%s%s[wm];[base][wm]overlay=%s",
                    scaleFilter, wmScale, opacityFilter, position
            );
            cmd.addAll(Arrays.asList("-filter_complex", filterComplex));
        } else if (hasSubtitleBurnIn && hasWatermarkText) {
            // Subtitle burn-in + text watermark: combine in -vf
            String escapedSubPath = escapeFilterPath(job.getSubtitlePath());
            String drawtextPosition = resolveDrawtextPositionForJob(job);
            String alpha = job.getWatermarkOpacity() != null
                    ? String.format(":alpha=%s", job.getWatermarkOpacity()) : "";

            String vf = String.format("%s,subtitles=%s,drawtext=text='%s':%s:fontsize=24:fontcolor=white%s",
                    scaleFilter, escapedSubPath, escapeDrawtext(job.getWatermarkText()), drawtextPosition, alpha);
            cmd.addAll(Arrays.asList("-vf", vf));
        } else if (hasSubtitleBurnIn) {
            // Subtitle burn-in only
            String escapedSubPath = escapeFilterPath(job.getSubtitlePath());
            String vf = String.format("%s,subtitles=%s", scaleFilter, escapedSubPath);
            cmd.addAll(Arrays.asList("-vf", vf));
        } else if (hasWatermarkText) {
            // Text watermark only
            String drawtextPosition = resolveDrawtextPositionForJob(job);
            String alpha = job.getWatermarkOpacity() != null
                    ? String.format(":alpha=%s", job.getWatermarkOpacity()) : "";

            String vf = String.format("%s,drawtext=text='%s':%s:fontsize=24:fontcolor=white%s",
                    scaleFilter, escapeDrawtext(job.getWatermarkText()), drawtextPosition, alpha);
            cmd.addAll(Arrays.asList("-vf", vf));
        } else {
            // No overlays — just scale
            cmd.addAll(Arrays.asList("-vf", scaleFilter));
        }
    }

    /**
     * Applies video codec selection.
     * Defaults to libx264 for most formats, but WEBM requires VP8/VP9/AV1 —
     * any H.264/H.265 selection is overridden to VP9 for WebM compatibility.
     */
    /**
     * Resolves the encoder preset for a job from its user-selected encoding speed.
     * Falls back to the balanced default (veryfast) for missing/invalid values.
     */
    private String presetFor(TranscodeJob job) {
        String speed = job.getEncodingSpeed();
        if (speed != null) {
            speed = speed.trim().toLowerCase();
            if (ALLOWED_PRESETS.contains(speed)) return speed;
        }
        return "veryfast";
    }

    private void applyVideoCodec(List<String> cmd, TranscodeJob job) {
        String codec = job.getCodec();
        boolean isWebM = job.getOutputFormat() == OutputFormat.WEBM;

        if (codec == null || codec.isEmpty()) {
            // WebM requires VP9 by default; everything else defaults to H264
            codec = isWebM ? "VP9" : "H264";
        } else if (isWebM) {
            String upper = codec.toUpperCase();
            // H.264 and H.265 are incompatible with the WebM container — fall back to VP9
            if (upper.equals("H264") || upper.equals("H265") || upper.equals("HEVC")) {
                codec = "VP9";
            }
        }

        switch (codec.toUpperCase()) {
            case "H265":
            case "HEVC":
                // yuv420p + tag hvc1 so the output is decodable by Apple/QuickTime and most players
                cmd.addAll(Arrays.asList("-c:v", "libx265", "-preset", presetFor(job),
                        "-pix_fmt", "yuv420p", "-tag:v", "hvc1"));
                break;
            case "AV1":
                cmd.addAll(Arrays.asList("-c:v", "libaom-av1"));
                break;
            case "VP9":
            case "VP8":
                cmd.addAll(Arrays.asList("-c:v", "libvpx-vp9"));
                break;
            case "H264":
            default:
                // -pix_fmt yuv420p + High profile = the universally-decodable baseline.
                // Without yuv420p, sources in yuv444p/10-bit produce files many players
                // (QuickTime, Safari, hardware decoders) show as black or audio-only.
                cmd.addAll(Arrays.asList("-c:v", "libx264", "-preset", presetFor(job),
                        "-pix_fmt", "yuv420p", "-profile:v", "high"));
                break;
        }
    }

    /**
     * Applies the appropriate audio codec for audio-only output formats.
     */
    private void applyAudioCodec(List<String> cmd, TranscodeJob job) {
        if (job.getOutputFormat() == null) return;
        switch (job.getOutputFormat()) {
            case MP3:
                cmd.addAll(Arrays.asList("-c:a", "libmp3lame"));
                break;
            case AAC:
                cmd.addAll(Arrays.asList("-c:a", "aac"));
                break;
            case FLAC:
                cmd.addAll(Arrays.asList("-c:a", "flac"));
                break;
            case OGG:
                cmd.addAll(Arrays.asList("-c:a", "libvorbis"));
                break;
            default:
                cmd.addAll(Arrays.asList("-c:a", "aac"));
                break;
        }
    }

    /**
     * Applies audio parameters: bitrate, sample rate, and channel count.
     * For non-audio-only formats, also sets the default audio codec if not already set.
     */
    private void applyAudioParams(List<String> cmd, TranscodeJob job, boolean audioOnly) {
        if (!audioOnly) {
            // Set default audio codec for video formats (aac for most, libopus for webm)
            if (job.getOutputFormat() == OutputFormat.WEBM) {
                cmd.addAll(Arrays.asList("-c:a", "libopus"));
            } else {
                cmd.addAll(Arrays.asList("-c:a", "aac"));
            }
        }

        if (job.getAudioBitrate() != null && !job.getAudioBitrate().isEmpty()) {
            cmd.addAll(Arrays.asList("-b:a", job.getAudioBitrate()));
        } else if (!audioOnly) {
            cmd.addAll(Arrays.asList("-b:a", "128k"));
        }

        if (job.getAudioSampleRate() != null) {
            cmd.addAll(Arrays.asList("-ar", String.valueOf(job.getAudioSampleRate())));
        }

        if (job.getAudioChannels() != null) {
            cmd.addAll(Arrays.asList("-ac", String.valueOf(job.getAudioChannels())));
        }
    }

    /**
     * Applies format-specific output options (HLS segment settings, DASH settings, etc.)
     */
    private void applyFormatOptions(List<String> cmd, TranscodeJob job, String outputDir) {
        switch (job.getOutputFormat()) {
            case HLS:
                cmd.addAll(Arrays.asList("-f", "hls", "-hls_time", "10", "-hls_list_size", "0"));
                break;
            case DASH:
                cmd.addAll(Arrays.asList("-f", "dash", "-seg_duration", "10"));
                break;
            case MP4:
            case MOV:
                // +faststart relocates the moov atom to the front so the file plays
                // immediately when streamed from a URL (otherwise players must download
                // the whole file before starting). Valid only for MP4/MOV containers.
                cmd.addAll(Arrays.asList("-movflags", "+faststart"));
                break;
            default:
                // No special format flags needed for WEBM, MKV, or audio formats
                break;
        }
    }

    /**
     * Applies HLS AES-128 encryption. Generates the key file and key info file,
     * then adds -hls_key_info_file to the command.
     */
    private void applyEncryption(List<String> cmd, TranscodeJob job, String outputDir) {
        String keyPath = job.getEncryptionKeyPath() != null
                ? job.getEncryptionKeyPath()
                : Paths.get(outputDir, "enc.key").toString();
        String keyInfoPath = Paths.get(outputDir, "enc.keyinfo").toString();
        generateEncryptionKey(keyPath, keyInfoPath, job);
        cmd.addAll(Arrays.asList("-hls_key_info_file", keyInfoPath));
    }

    /**
     * Generates AES-128 encryption key and key info file for HLS encryption.
     * The key info file contains:
     *   Line 1: Key URI (for the HLS manifest)
     *   Line 2: Path to the key file
     *   Line 3: IV (optional, auto-generated)
     */
    void generateEncryptionKey(String keyPath, String keyInfoPath, TranscodeJob job) {
        try {
            // Generate 16-byte random key
            byte[] key = new byte[16];
            new SecureRandom().nextBytes(key);
            Files.createDirectories(Paths.get(keyPath).getParent());
            Files.write(Paths.get(keyPath), key);

            // Generate random IV
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            String ivHex = bytesToHex(iv);

            // Determine key URL
            String keyUrl = job.getEncryptionKeyUrl() != null
                    ? job.getEncryptionKeyUrl()
                    : "enc.key"; // relative URL in the HLS manifest

            // Write key info file
            String keyInfoContent = keyUrl + "\n" + keyPath + "\n" + ivHex + "\n";
            Files.write(Paths.get(keyInfoPath), keyInfoContent.getBytes());

            log.info("[MediaOps] Generated encryption key at {} with key info at {}", keyPath, keyInfoPath);
        } catch (IOException e) {
            log.error("[MediaOps] Failed to generate encryption key: {}", e.getMessage());
            throw new RuntimeException("Failed to generate encryption key", e);
        }
    }

    /**
     * Resolves the output file path based on the output format.
     */
    private String resolveOutputPath(TranscodeJob job, String outputDir) {
        switch (job.getOutputFormat()) {
            case HLS:
                return Paths.get(outputDir, "index.m3u8").toString();
            case DASH:
                return Paths.get(outputDir, "manifest.mpd").toString();
            default:
                return Paths.get(outputDir, "output." + job.getOutputFormat().name().toLowerCase()).toString();
        }
    }

    /**
     * Normalizes time input to HH:MM:SS format.
     * Accepts both "HH:MM:SS" format and total seconds as an integer string.
     */
    public String normalizeTime(String time) {
        if (time == null || time.isEmpty()) return "00:00:00";
        if (time.contains(":")) return time;
        int totalSec = Integer.parseInt(time.trim());
        return String.format("%02d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60);
    }

    /**
     * Resolves watermark image overlay position for FFmpeg overlay filter.
     * Returns x:y coordinate expression.
     */
    public String resolveOverlayPosition(WatermarkPosition pos) {
        if (pos == null) return "W-w-10:H-h-10";
        switch (pos) {
            case TOP_LEFT:      return "10:10";
            case TOP_CENTER:    return "(W-w)/2:10";
            case TOP_RIGHT:     return "W-w-10:10";
            case MIDDLE_LEFT:   return "10:(H-h)/2";
            case CENTER:        return "(W-w)/2:(H-h)/2";
            case MIDDLE_RIGHT:  return "W-w-10:(H-h)/2";
            case BOTTOM_LEFT:   return "10:H-h-10";
            case BOTTOM_CENTER: return "(W-w)/2:H-h-10";
            case BOTTOM_RIGHT:  return "W-w-10:H-h-10";
            default:            return "W-w-10:H-h-10";
        }
    }

    /**
     * Resolves watermark text position for FFmpeg drawtext filter.
     * Returns x=...:y=... expression.
     */
    public String resolveDrawtextPosition(WatermarkPosition pos) {
        if (pos == null) return "x=w-tw-10:y=h-th-10";
        switch (pos) {
            case TOP_LEFT:      return "x=10:y=10";
            case TOP_CENTER:    return "x=(w-tw)/2:y=10";
            case TOP_RIGHT:     return "x=w-tw-10:y=10";
            case MIDDLE_LEFT:   return "x=10:y=(h-th)/2";
            case CENTER:        return "x=(w-tw)/2:y=(h-th)/2";
            case MIDDLE_RIGHT:  return "x=w-tw-10:y=(h-th)/2";
            case BOTTOM_LEFT:   return "x=10:y=h-th-10";
            case BOTTOM_CENTER: return "x=(w-tw)/2:y=h-th-10";
            case BOTTOM_RIGHT:  return "x=w-tw-10:y=h-th-10";
            default:            return "x=w-tw-10:y=h-th-10";
        }
    }

    /**
     * Returns overlay x:y expression, preferring free-form x/y percentages over enum preset.
     * x/y are center-based percentages: the center of the watermark is placed at (W*x/100, H*y/100).
     */
    private String resolveOverlayPositionForJob(TranscodeJob job) {
        if (job.getWatermarkX() != null && job.getWatermarkY() != null) {
            // Commas inside the overlay x:y expression must be escaped as "\," —
            // otherwise the filtergraph parser treats them as filter separators
            // (e.g. it tries to load a filter literally named "min(W-w").
            return String.format(
                "max(0\\,min(W-w\\,W*%.4f/100-w/2)):max(0\\,min(H-h\\,H*%.4f/100-h/2))",
                job.getWatermarkX(), job.getWatermarkY()
            );
        }
        return resolveOverlayPosition(job.getWatermarkPosition());
    }

    /**
     * Returns drawtext x=:y= expression, preferring free-form x/y percentages over enum preset.
     */
    private String resolveDrawtextPositionForJob(TranscodeJob job) {
        if (job.getWatermarkX() != null && job.getWatermarkY() != null) {
            return String.format(
                "x='max(0,min(w-tw,w*%.4f/100-tw/2))':y='max(0,min(h-th,h*%.4f/100-th/2))'",
                job.getWatermarkX(), job.getWatermarkY()
            );
        }
        return resolveDrawtextPosition(job.getWatermarkPosition());
    }

    /**
     * Returns true if the output format is audio-only (MP3, AAC, FLAC, OGG).
     */
    public boolean isAudioOnlyFormat(OutputFormat format) {
        if (format == null) return false;
        return format == OutputFormat.MP3 || format == OutputFormat.AAC
                || format == OutputFormat.FLAC || format == OutputFormat.OGG;
    }

    /**
     * Generates thumbnails at the specified interval or a single poster frame.
     * Returns the FFmpeg command as a list of arguments (does not execute).
     */
    public List<String> generateThumbnails(TranscodeJob job, String outputDir) {
        List<String> cmd = new ArrayList<>();
        cmd.addAll(Arrays.asList(ffmpegPath, "-i", job.getSourceUrl()));

        if (job.getThumbnailTimestamp() != null && !job.getThumbnailTimestamp().isEmpty()) {
            cmd.addAll(Arrays.asList("-ss", normalizeTime(job.getThumbnailTimestamp()),
                    "-frames:v", "1",
                    "-y",
                    Paths.get(outputDir, "poster.jpg").toString()));
        } else {
            int interval = job.getThumbnailIntervalSeconds() != null ? job.getThumbnailIntervalSeconds() : 10;
            cmd.addAll(Arrays.asList("-vf",
                    "fps=1/" + interval,
                    "-y",
                    Paths.get(outputDir, "thumb_%04d.jpg").toString()));
        }
        return cmd;
    }

    /**
     * Executes thumbnail generation for a transcode job.
     * Builds the thumbnail command, runs it as a subprocess, collects output .jpg paths,
     * and stores them as a JSON array in job.thumbnailPaths.
     *
     * @param job the transcode job with thumbnail configuration
     * @return list of generated thumbnail file paths, or empty list on failure
     */
    public List<String> executeThumbnailGeneration(TranscodeJob job) {
        String outputDir = Paths.get(baseStorageDir, "jobs", String.valueOf(job.getId()), "thumbnails").toString();
        try {
            Files.createDirectories(Paths.get(outputDir));
        } catch (IOException e) {
            log.error("[MediaOps] Cannot create thumbnail output dir: {}", e.getMessage());
            return Collections.emptyList();
        }

        List<String> cmd = generateThumbnails(job, outputDir);
        log.info("[MediaOps] Generating thumbnails for job {}: {}", job.getId(), String.join(" ", cmd));

        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder processOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processOutput.append(line).append('\n');
                }
            }

            boolean done = process.waitFor(30, TimeUnit.MINUTES);
            if (!done) {
                process.destroyForcibly();
                log.error("[MediaOps] Thumbnail generation timed out for job {}", job.getId());
                return Collections.emptyList();
            }

            if (process.exitValue() != 0) {
                log.error("[MediaOps] Thumbnail generation failed for job {} (exit {}): {}",
                        job.getId(), process.exitValue(), processOutput);
                return Collections.emptyList();
            }

            // Collect generated .jpg files from the output directory
            List<String> thumbnailPaths = new ArrayList<>();
            try (var stream = Files.list(Paths.get(outputDir))) {
                stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jpg"))
                      .sorted()
                      .forEach(p -> thumbnailPaths.add(p.toString()));
            }

            // Store as JSON array in the job entity
            if (!thumbnailPaths.isEmpty()) {
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < thumbnailPaths.size(); i++) {
                    if (i > 0) json.append(",");
                    json.append("\"").append(thumbnailPaths.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                }
                json.append("]");
                job.setThumbnailPaths(json.toString());
            }

            log.info("[MediaOps] Generated {} thumbnails for job {}", thumbnailPaths.size(), job.getId());
            return thumbnailPaths;

        } catch (Exception e) {
            log.error("[MediaOps] Thumbnail generation failed for job {}: {}", job.getId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─── Private utility methods ──────────────────────────────────────────────

    /**
     * Validates that a path is within the permitted storage base directory.
     * Rejects URL-like strings and paths that resolve outside the storage root.
     */
    private void validateStoragePath(String path) {
        if (path == null || path.contains("://")) {
            throw new IllegalArgumentException("Invalid storage path: " + path);
        }
        java.nio.file.Path resolved = Paths.get(path).toAbsolutePath().normalize();
        java.nio.file.Path base = Paths.get(baseStorageDir).toAbsolutePath().normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Path is outside permitted storage directory");
        }
    }

    /**
     * Escapes a file path for use as a filter argument (e.g. subtitles=...).
     * The path is wrapped in single quotes so spaces and most special characters
     * are taken literally by the filtergraph parser; the characters that remain
     * special inside the quotes are then escaped:
     *   '  → '\''  (close quote, literal quote, reopen)
     *   \  → \\
     *   :  → \:     (':' separates filter options, even inside quotes — e.g. Windows C:)
     * Returns the value already wrapped in single quotes.
     */
    private String escapeFilterPath(String path) {
        if (path == null) return "''";
        String escaped = path
                .replace("\\", "\\\\")
                .replace("'", "'\\''")
                .replace(":", "\\:");
        return "'" + escaped + "'";
    }

    /**
     * Escapes text for use in FFmpeg drawtext filter.
     */
    private String escapeDrawtext(String text) {
        if (text == null) return "";
        return text.replace("'", "'\\''")
                   .replace(":", "\\:");
    }

    /**
     * Converts a byte array to a hex string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ─── Adaptive streaming builders ──────────────────────────────────────────

    /** Parses an "HH:MM:SS" / "MM:SS" / "SS" string to seconds; null if blank/invalid. */
    private Double parseTimeSeconds(String t) {
        if (t == null || t.isEmpty()) return null;
        try {
            double s = 0;
            for (String p : t.split(":")) s = s * 60 + Double.parseDouble(p.trim());
            return s;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Adds an output duration limit (-t) for the adaptive path so end-time trimming works.
     * Combined with an input -ss seek, this yields the [start, end] clip.
     */
    private void addOutputDurationLimit(List<String> cmd, TranscodeJob job, Double startSec) {
        Double endSec = parseTimeSeconds(job.getEndTime());
        if (endSec == null) return;
        double dur = endSec - (startSec != null ? Math.max(0, startSec) : 0);
        if (dur > 0) {
            cmd.add("-t");
            cmd.add(String.format(java.util.Locale.US, "%.3f", dur));
        }
    }

    /** Returns ",subtitles=<escaped path>" for burn-in mode, or "" otherwise. */
    private String adaptiveSubtitleChain(TranscodeJob job) {
        boolean burnIn = job.getSubtitleMode() == SubtitleMode.BURN_IN
                && job.getSubtitlePath() != null && !job.getSubtitlePath().isEmpty();
        if (!burnIn) return "";
        requireSubtitlesFilter();
        validateStoragePath(job.getSubtitlePath());
        return ",subtitles=" + escapeFilterPath(job.getSubtitlePath());
    }

    // Whether this ffmpeg build has the libass-backed "subtitles" filter (cached after first probe).
    private volatile Boolean subtitlesFilterAvailable;

    private boolean isSubtitlesFilterAvailable() {
        Boolean cached = subtitlesFilterAvailable;
        if (cached != null) return cached;
        boolean available = false;
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-hide_banner", "-filters")
                    .redirectErrorStream(true).start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.matches(".*\\bsubtitles\\b.*")) { available = true; break; }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.warn("[MediaOps] Could not probe ffmpeg filters: {}", e.getMessage());
        }
        subtitlesFilterAvailable = available;
        return available;
    }

    /** Fails fast with a clear message when subtitle burn-in is requested but libass is missing. */
    private void requireSubtitlesFilter() {
        if (!isSubtitlesFilterAvailable()) {
            throw new IllegalStateException(
                "Subtitle burn-in requires an ffmpeg build with libass (the 'subtitles' filter is missing). "
                + "Install a libass-enabled ffmpeg — e.g. 'brew reinstall ffmpeg' — and retry.");
        }
    }

    private List<String> buildAdaptiveHls(TranscodeJob job, String outputDir) {
        boolean hasAudio = hasAudioStream(job.getSourceUrl());
        boolean hasWatermarkImage = job.getWatermarkImagePath() != null && !job.getWatermarkImagePath().isEmpty();
        boolean hasWatermarkText  = !hasWatermarkImage
                && job.getWatermarkText() != null && !job.getWatermarkText().isEmpty();

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        // Trim: fast input seek to start (end is enforced via -t on the output below)
        Double startSec = parseTimeSeconds(job.getStartTime());
        if (startSec != null && startSec > 0) {
            cmd.add("-ss"); cmd.add(normalizeTime(job.getStartTime()));
        }
        cmd.add("-i"); cmd.add(job.getSourceUrl());
        if (hasWatermarkImage) {
            validateStoragePath(job.getWatermarkImagePath());
            cmd.add("-i"); cmd.add(job.getWatermarkImagePath());
        }
        cmd.add("-y");

        cmd.addAll(Arrays.asList("-filter_complex", buildAdaptiveFilterComplex(job, hasWatermarkImage, hasWatermarkText)));
        cmd.addAll(Arrays.asList(
                // yuv420p + High profile on every variant so the HLS stream is decodable
                // by Safari/iOS and all standards-compliant players, regardless of source.
                // Explicit preset: without one x264 defaults to medium, which is 3-4x
                // slower — prohibitive when encoding three renditions of long videos.
                "-preset", presetFor(job),
                "-map", "[v1out]", "-c:v:0", "libx264", "-profile:v:0", "high", "-pix_fmt:v:0", "yuv420p", "-b:v:0", "4500k",
                "-map", "[v2out]", "-c:v:1", "libx264", "-profile:v:1", "high", "-pix_fmt:v:1", "yuv420p", "-b:v:1", "2500k",
                "-map", "[v3out]", "-c:v:2", "libx264", "-profile:v:2", "high", "-pix_fmt:v:2", "yuv420p", "-b:v:2", "1000k"
        ));
        if (hasAudio) {
            cmd.addAll(Arrays.asList("-map", "0:a", "-map", "0:a", "-map", "0:a",
                    "-c:a", "aac", "-b:a", "128k"));
        }
        addOutputDurationLimit(cmd, job, startSec);
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
        boolean hasWatermarkImage = job.getWatermarkImagePath() != null && !job.getWatermarkImagePath().isEmpty();
        boolean hasWatermarkText  = !hasWatermarkImage
                && job.getWatermarkText() != null && !job.getWatermarkText().isEmpty();

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        // Trim: fast input seek to start (end is enforced via -t on the output below)
        Double startSec = parseTimeSeconds(job.getStartTime());
        if (startSec != null && startSec > 0) {
            cmd.add("-ss"); cmd.add(normalizeTime(job.getStartTime()));
        }
        cmd.add("-i"); cmd.add(job.getSourceUrl());
        if (hasWatermarkImage) {
            validateStoragePath(job.getWatermarkImagePath());
            cmd.add("-i"); cmd.add(job.getWatermarkImagePath());
        }
        cmd.add("-y");

        cmd.addAll(Arrays.asList("-filter_complex", buildAdaptiveFilterComplex(job, hasWatermarkImage, hasWatermarkText)));
        cmd.addAll(Arrays.asList(
                // yuv420p + High profile on every variant for maximum player compatibility.
                // Explicit preset: see buildAdaptiveHls — x264's default medium is 3-4x slower.
                "-preset", presetFor(job),
                "-map", "[v1out]", "-c:v:0", "libx264", "-profile:v:0", "high", "-pix_fmt:v:0", "yuv420p", "-b:v:0", "4500k",
                "-map", "[v2out]", "-c:v:1", "libx264", "-profile:v:1", "high", "-pix_fmt:v:1", "yuv420p", "-b:v:1", "2500k",
                "-map", "[v3out]", "-c:v:2", "libx264", "-profile:v:2", "high", "-pix_fmt:v:2", "yuv420p", "-b:v:2", "1000k"
        ));
        if (hasAudio) {
            cmd.addAll(Arrays.asList("-map", "0:a", "-c:a", "aac", "-b:a", "128k"));
        }
        addOutputDurationLimit(cmd, job, startSec);
        cmd.addAll(Arrays.asList(
                "-f", "dash", "-seg_duration", "10",
                "-use_template", "1", "-use_timeline", "1",
                Paths.get(outputDir, "manifest.mpd").toString()
        ));
        return cmd;
    }

    /**
     * Builds the -filter_complex string for adaptive (multi-variant) encoding.
     * Applies watermark (text or image) consistently across all three quality levels.
     *
     * For text watermark:  scale + drawtext per branch.
     * For image watermark: split watermark input into 3 pads (each pad can only be used once),
     *                      then overlay on each scaled branch.
     * No watermark:        plain split + scale.
     */
    private String buildAdaptiveFilterComplex(TranscodeJob job, boolean hasWatermarkImage, boolean hasWatermarkText) {
        // Subtitle burn-in chain (",subtitles=...") appended after each variant's scale.
        String sub = adaptiveSubtitleChain(job);

        if (hasWatermarkText) {
            String pos   = resolveDrawtextPositionForJob(job);
            String alpha = job.getWatermarkOpacity() != null
                    ? String.format(":alpha=%s", job.getWatermarkOpacity()) : "";
            String dt = String.format("drawtext=text='%s':%s:fontsize=24:fontcolor=white%s",
                    escapeDrawtext(job.getWatermarkText()), pos, alpha);
            return "[0:v]split=3[v1][v2][v3];"
                    + "[v1]scale=1920:1080" + sub + "," + dt + "[v1out];"
                    + "[v2]scale=1280:720"  + sub + "," + dt + "[v2out];"
                    + "[v3]scale=854:480"   + sub + "," + dt + "[v3out]";
        }

        if (hasWatermarkImage) {
            String overlayPos = resolveOverlayPositionForJob(job);
            String opacityChain = (job.getWatermarkOpacity() != null)
                    ? String.format("format=rgba,colorchannelmixer=aa=%s,", job.getWatermarkOpacity()) : "";
            int adpScale = (job.getWatermarkScale() != null && job.getWatermarkScale() > 0) ? job.getWatermarkScale() : 15;
            int wm1920 = Math.max(60, 1920 * adpScale / 100);
            int wm1280 = Math.max(40, 1280 * adpScale / 100);
            int wm854  = Math.max(30,  854 * adpScale / 100);
            return "[0:v]split=3[v1][v2][v3];"
                    + "[1:v]" + opacityChain + "split=3[wm1][wm2][wm3];"
                    + "[wm1]scale=" + wm1920 + ":-2[wm1r];[v1]scale=1920:1080" + sub + "[v1s];[v1s][wm1r]overlay=" + overlayPos + "[v1out];"
                    + "[wm2]scale=" + wm1280 + ":-2[wm2r];[v2]scale=1280:720"  + sub + "[v2s];[v2s][wm2r]overlay="  + overlayPos + "[v2out];"
                    + "[wm3]scale=" + wm854  + ":-2[wm3r];[v3]scale=854:480"   + sub + "[v3s];[v3s][wm3r]overlay="   + overlayPos + "[v3out]";
        }

        // No watermark — plain split + scale (+ optional subtitle burn-in)
        return "[0:v]split=3[v1][v2][v3];"
                + "[v1]scale=1920:1080" + sub + "[v1out];"
                + "[v2]scale=1280:720"  + sub + "[v2out];"
                + "[v3]scale=854:480"   + sub + "[v3out]";
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
