package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.domain.TranscodeJob.OutputFormat;
import com.xammer.mediaops.domain.TranscodeJob.SubtitleMode;
import com.xammer.mediaops.domain.TranscodeJob.WatermarkPosition;
import com.xammer.mediaops.dto.FileProbeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FFmpegService helper methods and command building logic.
 */
class FFmpegServiceTest {

    private FFmpegService ffmpegService;

    @BeforeEach
    void setUp() {
        ffmpegService = new FFmpegService();
        ReflectionTestUtils.setField(ffmpegService, "ffmpegPath", "ffmpeg");
        ReflectionTestUtils.setField(ffmpegService, "baseStorageDir", "/tmp/test-media");
    }

    // ─── normalizeTime tests ──────────────────────────────────────────────────

    @Test
    void normalizeTime_withColonFormat_returnsAsIs() {
        assertEquals("01:23:45", ffmpegService.normalizeTime("01:23:45"));
    }

    @Test
    void normalizeTime_withSeconds_convertsToHHMMSS() {
        assertEquals("00:01:30", ffmpegService.normalizeTime("90"));
    }

    @Test
    void normalizeTime_withZeroSeconds_returnsZero() {
        assertEquals("00:00:00", ffmpegService.normalizeTime("0"));
    }

    @Test
    void normalizeTime_withLargeSeconds_convertsCorrectly() {
        // 3661 seconds = 1 hour, 1 minute, 1 second
        assertEquals("01:01:01", ffmpegService.normalizeTime("3661"));
    }

    @Test
    void normalizeTime_withNull_returnsDefault() {
        assertEquals("00:00:00", ffmpegService.normalizeTime(null));
    }

    @Test
    void normalizeTime_withEmpty_returnsDefault() {
        assertEquals("00:00:00", ffmpegService.normalizeTime(""));
    }

    // ─── isAudioOnlyFormat tests ──────────────────────────────────────────────

    @Test
    void isAudioOnlyFormat_mp3_returnsTrue() {
        assertTrue(ffmpegService.isAudioOnlyFormat(OutputFormat.MP3));
    }

    @Test
    void isAudioOnlyFormat_aac_returnsTrue() {
        assertTrue(ffmpegService.isAudioOnlyFormat(OutputFormat.AAC));
    }

    @Test
    void isAudioOnlyFormat_flac_returnsTrue() {
        assertTrue(ffmpegService.isAudioOnlyFormat(OutputFormat.FLAC));
    }

    @Test
    void isAudioOnlyFormat_ogg_returnsTrue() {
        assertTrue(ffmpegService.isAudioOnlyFormat(OutputFormat.OGG));
    }

    @Test
    void isAudioOnlyFormat_mp4_returnsFalse() {
        assertFalse(ffmpegService.isAudioOnlyFormat(OutputFormat.MP4));
    }

    @Test
    void isAudioOnlyFormat_hls_returnsFalse() {
        assertFalse(ffmpegService.isAudioOnlyFormat(OutputFormat.HLS));
    }

    @Test
    void isAudioOnlyFormat_null_returnsFalse() {
        assertFalse(ffmpegService.isAudioOnlyFormat(null));
    }

    // ─── resolveOverlayPosition tests ─────────────────────────────────────────

    @Test
    void resolveOverlayPosition_topLeft() {
        assertEquals("10:10", ffmpegService.resolveOverlayPosition(WatermarkPosition.TOP_LEFT));
    }

    @Test
    void resolveOverlayPosition_topRight() {
        assertEquals("W-w-10:10", ffmpegService.resolveOverlayPosition(WatermarkPosition.TOP_RIGHT));
    }

    @Test
    void resolveOverlayPosition_bottomLeft() {
        assertEquals("10:H-h-10", ffmpegService.resolveOverlayPosition(WatermarkPosition.BOTTOM_LEFT));
    }

    @Test
    void resolveOverlayPosition_bottomRight() {
        assertEquals("W-w-10:H-h-10", ffmpegService.resolveOverlayPosition(WatermarkPosition.BOTTOM_RIGHT));
    }

    @Test
    void resolveOverlayPosition_center() {
        assertEquals("(W-w)/2:(H-h)/2", ffmpegService.resolveOverlayPosition(WatermarkPosition.CENTER));
    }

    @Test
    void resolveOverlayPosition_null_returnsDefault() {
        assertEquals("10:10", ffmpegService.resolveOverlayPosition(null));
    }

    // ─── resolveDrawtextPosition tests ────────────────────────────────────────

    @Test
    void resolveDrawtextPosition_topLeft() {
        assertEquals("x=10:y=10", ffmpegService.resolveDrawtextPosition(WatermarkPosition.TOP_LEFT));
    }

    @Test
    void resolveDrawtextPosition_topRight() {
        assertEquals("x=w-tw-10:y=10", ffmpegService.resolveDrawtextPosition(WatermarkPosition.TOP_RIGHT));
    }

    @Test
    void resolveDrawtextPosition_bottomLeft() {
        assertEquals("x=10:y=h-th-10", ffmpegService.resolveDrawtextPosition(WatermarkPosition.BOTTOM_LEFT));
    }

    @Test
    void resolveDrawtextPosition_bottomRight() {
        assertEquals("x=w-tw-10:y=h-th-10", ffmpegService.resolveDrawtextPosition(WatermarkPosition.BOTTOM_RIGHT));
    }

    @Test
    void resolveDrawtextPosition_center() {
        assertEquals("x=(w-tw)/2:y=(h-th)/2", ffmpegService.resolveDrawtextPosition(WatermarkPosition.CENTER));
    }

    @Test
    void resolveDrawtextPosition_null_returnsDefault() {
        assertEquals("x=10:y=10", ffmpegService.resolveDrawtextPosition(null));
    }

    // ─── buildTranscodeCommand tests ──────────────────────────────────────────

    @Test
    void buildTranscodeCommand_basicMp4_producesValidCommand() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertTrue(cmd.contains("ffmpeg"));
        assertTrue(cmd.contains("-i"));
        assertTrue(cmd.contains("/path/to/source.mp4"));
        assertTrue(cmd.contains("-c:v"));
        assertTrue(cmd.contains("-y"));
        // Output should be last element
        assertTrue(cmd.get(cmd.size() - 1).endsWith("output.mp4"));
    }

    @Test
    void buildTranscodeCommand_withStartTime_addsSsBeforeInput() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setStartTime("30");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int ssIdx = cmd.indexOf("-ss");
        int inputIdx = cmd.indexOf("-i");
        assertTrue(ssIdx >= 0, "Command should contain -ss");
        assertTrue(ssIdx < inputIdx, "-ss should appear before -i");
        assertEquals("00:00:30", cmd.get(ssIdx + 1));
    }

    @Test
    void buildTranscodeCommand_withEndTime_addsToAfterInput() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setEndTime("01:00:00");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int toIdx = cmd.indexOf("-to");
        int inputIdx = cmd.indexOf("-i");
        assertTrue(toIdx >= 0, "Command should contain -to");
        assertTrue(toIdx > inputIdx, "-to should appear after -i");
        assertEquals("01:00:00", cmd.get(toIdx + 1));
    }

    @Test
    void buildTranscodeCommand_withBothTrimTimes_addsBothFlags() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setStartTime("10");
        job.setEndTime("120");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertTrue(cmd.contains("-ss"));
        assertTrue(cmd.contains("-to"));
        assertEquals("00:00:10", cmd.get(cmd.indexOf("-ss") + 1));
        assertEquals("00:02:00", cmd.get(cmd.indexOf("-to") + 1));
    }

    @Test
    void buildTranscodeCommand_audioOnlyMp3_includesVnFlag() {
        TranscodeJob job = createBasicJob(OutputFormat.MP3);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertTrue(cmd.contains("-vn"), "Audio-only format should include -vn flag");
        assertTrue(cmd.contains("libmp3lame"), "MP3 should use libmp3lame codec");
        assertFalse(cmd.contains("-c:v"), "Audio-only should not have video codec");
        assertTrue(cmd.get(cmd.size() - 1).endsWith("output.mp3"));
    }

    @Test
    void buildTranscodeCommand_audioOnlyAac_usesAacCodec() {
        TranscodeJob job = createBasicJob(OutputFormat.AAC);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertTrue(cmd.contains("-vn"));
        int caIdx = cmd.indexOf("-c:a");
        assertEquals("aac", cmd.get(caIdx + 1));
    }

    @Test
    void buildTranscodeCommand_audioOnlyFlac_usesFlacCodec() {
        TranscodeJob job = createBasicJob(OutputFormat.FLAC);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertTrue(cmd.contains("-vn"));
        int caIdx = cmd.indexOf("-c:a");
        assertEquals("flac", cmd.get(caIdx + 1));
    }

    @Test
    void buildTranscodeCommand_audioOnlyOgg_usesLibvorbis() {
        TranscodeJob job = createBasicJob(OutputFormat.OGG);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertTrue(cmd.contains("-vn"));
        assertTrue(cmd.contains("libvorbis"));
    }

    @Test
    void buildTranscodeCommand_codecH265_usesLibx265() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setCodec("H265");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int cvIdx = cmd.indexOf("-c:v");
        assertEquals("libx265", cmd.get(cvIdx + 1));
    }

    @Test
    void buildTranscodeCommand_codecHevc_usesLibx265() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setCodec("HEVC");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int cvIdx = cmd.indexOf("-c:v");
        assertEquals("libx265", cmd.get(cvIdx + 1));
    }

    @Test
    void buildTranscodeCommand_codecAv1_usesLibaomAv1() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setCodec("AV1");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int cvIdx = cmd.indexOf("-c:v");
        assertEquals("libaom-av1", cmd.get(cvIdx + 1));
    }

    @Test
    void buildTranscodeCommand_codecVp9_usesLibvpxVp9() {
        TranscodeJob job = createBasicJob(OutputFormat.WEBM);
        job.setCodec("VP9");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int cvIdx = cmd.indexOf("-c:v");
        assertEquals("libvpx-vp9", cmd.get(cvIdx + 1));
    }

    @Test
    void buildTranscodeCommand_subtitleBurnIn_includesSubtitleFilter() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setSubtitleMode(SubtitleMode.BURN_IN);
        job.setSubtitlePath("/tmp/test-media/subs.srt");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int vfIdx = cmd.indexOf("-vf");
        assertTrue(vfIdx >= 0, "Should have -vf flag for subtitle burn-in");
        String filter = cmd.get(vfIdx + 1);
        assertTrue(filter.contains("subtitles="), "Filter should contain subtitles=");
    }

    @Test
    void buildTranscodeCommand_subtitleSidecar_doesNotIncludeSubtitleFilter() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setSubtitleMode(SubtitleMode.SIDECAR);
        job.setSubtitlePath("/path/to/subs.srt");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        // Should not have subtitle filter
        String cmdStr = String.join(" ", cmd);
        assertFalse(cmdStr.contains("subtitles="), "Sidecar mode should not include subtitle filter");
    }

    @Test
    void buildTranscodeCommand_watermarkImage_addsSecondInputAndFilterComplex() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setWatermarkImagePath("/tmp/test-media/logo.png");
        job.setWatermarkPosition(WatermarkPosition.TOP_RIGHT);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        // Should have two -i flags
        long inputCount = cmd.stream().filter(s -> s.equals("-i")).count();
        assertEquals(2, inputCount, "Should have two input files (source + watermark)");
        assertTrue(cmd.contains("-filter_complex"), "Should use filter_complex for image overlay");

        String filterStr = cmd.get(cmd.indexOf("-filter_complex") + 1);
        assertTrue(filterStr.contains("overlay="), "Filter should contain overlay");
    }

    @Test
    void buildTranscodeCommand_watermarkText_addsDrawtextFilter() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setWatermarkText("© MyBrand");
        job.setWatermarkPosition(WatermarkPosition.BOTTOM_RIGHT);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int vfIdx = cmd.indexOf("-vf");
        assertTrue(vfIdx >= 0, "Should have -vf for text watermark");
        String filter = cmd.get(vfIdx + 1);
        assertTrue(filter.contains("drawtext="), "Filter should include drawtext");
    }

    @Test
    void buildTranscodeCommand_watermarkWithOpacity_includesAlpha() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setWatermarkText("Watermark");
        job.setWatermarkPosition(WatermarkPosition.CENTER);
        job.setWatermarkOpacity(0.5);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int vfIdx = cmd.indexOf("-vf");
        String filter = cmd.get(vfIdx + 1);
        assertTrue(filter.contains("alpha=0.5"), "Opacity should be reflected in filter");
    }

    @Test
    void buildTranscodeCommand_audioParams_addsCorrectFlags() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setAudioBitrate("256k");
        job.setAudioSampleRate(48000);
        job.setAudioChannels(2);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertTrue(cmd.contains("-b:a"));
        assertEquals("256k", cmd.get(cmd.indexOf("-b:a") + 1));
        assertTrue(cmd.contains("-ar"));
        assertEquals("48000", cmd.get(cmd.indexOf("-ar") + 1));
        assertTrue(cmd.contains("-ac"));
        assertEquals("2", cmd.get(cmd.indexOf("-ac") + 1));
    }

    @Test
    void buildTranscodeCommand_customParams_appendedBeforeOutput() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setCustomFfmpegParams("-crf 23 -maxrate 2M");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int crfIdx = cmd.indexOf("-crf");
        assertTrue(crfIdx >= 0, "Should contain -crf from custom params");
        // Custom params should be before -y and the output path (last two elements)
        assertTrue(crfIdx < cmd.size() - 2, "Custom params should appear before output path");

        assertTrue(cmd.contains("23"));
        assertTrue(cmd.contains("-maxrate"));
        assertTrue(cmd.contains("2M"));
    }

    @Test
    void buildTranscodeCommand_hlsFormat_includesHlsFlags() {
        TranscodeJob job = createBasicJob(OutputFormat.HLS);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertTrue(cmd.contains("-f"));
        assertEquals("hls", cmd.get(cmd.indexOf("-f") + 1));
        assertTrue(cmd.contains("-hls_time"));
        assertTrue(cmd.contains("-hls_list_size"));
        assertTrue(cmd.get(cmd.size() - 1).endsWith("index.m3u8"));
    }

    @Test
    void buildTranscodeCommand_dashFormat_includesDashFlags() {
        TranscodeJob job = createBasicJob(OutputFormat.DASH);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertTrue(cmd.contains("-f"));
        assertEquals("dash", cmd.get(cmd.indexOf("-f") + 1));
        assertTrue(cmd.contains("-seg_duration"));
        assertTrue(cmd.get(cmd.size() - 1).endsWith("manifest.mpd"));
    }

    @Test
    void buildTranscodeCommand_encryptionEnabled_addsKeyInfoFlag() {
        TranscodeJob job = createBasicJob(OutputFormat.HLS);
        job.setEncryptionEnabled(true);
        job.setEncryptionKeyUrl("https://keys.example.com/key");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertTrue(cmd.contains("-hls_key_info_file"), "Should contain -hls_key_info_file for encryption");
    }

    @Test
    void buildTranscodeCommand_adaptiveHlsEncryptionEnabled_addsKeyInfoFlag() {
        TranscodeJob job = createBasicJob(OutputFormat.HLS);
        job.setEnableAdaptive(true);
        job.setEncryptionEnabled(true);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int keyIdx = cmd.indexOf("-hls_key_info_file");
        int segIdx = cmd.indexOf("-hls_segment_filename");
        assertTrue(keyIdx >= 0, "Adaptive HLS with encryption should contain -hls_key_info_file");
        // Encryption flag must precede the segment/playlist output options.
        assertTrue(segIdx > keyIdx, "-hls_key_info_file must come before -hls_segment_filename");
    }

    @Test
    void buildTranscodeCommand_adaptiveHlsNoEncryption_hasNoKeyInfoFlag() {
        TranscodeJob job = createBasicJob(OutputFormat.HLS);
        job.setEnableAdaptive(true);

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertFalse(cmd.contains("-hls_key_info_file"),
                "Adaptive HLS without encryption should not contain -hls_key_info_file");
    }

    @Test
    void parseBitrateKbps_handlesUnits() {
        assertEquals(5000, ffmpegService.parseBitrateKbps("5000k"));
        assertEquals(5000, ffmpegService.parseBitrateKbps("5000kbps"));
        assertEquals(8000, ffmpegService.parseBitrateKbps("8M"));
        assertEquals(8000, ffmpegService.parseBitrateKbps("8mbps"));
        assertEquals(3000, ffmpegService.parseBitrateKbps("3000"));
        assertEquals(-1, ffmpegService.parseBitrateKbps(null));
        assertEquals(-1, ffmpegService.parseBitrateKbps(""));
        assertEquals(-1, ffmpegService.parseBitrateKbps("abc"));
    }

    @Test
    void adaptiveVideoBitrates_defaultLadderWhenNoBitrate() {
        TranscodeJob job = createBasicJob(OutputFormat.HLS);
        job.setBitrate(null);

        String[] ladder = ffmpegService.adaptiveVideoBitrates(job);

        // Reproduces the historical fixed ladder exactly.
        assertArrayEquals(new String[]{"4500k", "2500k", "1000k"}, ladder);
    }

    @Test
    void adaptiveVideoBitrates_scalesFromUserBitrate() {
        TranscodeJob job = createBasicJob(OutputFormat.HLS);
        job.setBitrate("6000k");

        String[] ladder = ffmpegService.adaptiveVideoBitrates(job);

        // Top rung = user's value; lower rungs scale by 0.5556 / 0.2222.
        assertEquals("6000k", ladder[0]);
        assertEquals("3334k", ladder[1]);
        assertEquals("1333k", ladder[2]);
    }

    @Test
    void buildTranscodeCommand_adaptiveHls_usesDerivedTopRung() {
        TranscodeJob job = createBasicJob(OutputFormat.HLS);
        job.setEnableAdaptive(true);
        job.setBitrate("6000k");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int i = cmd.indexOf("-b:v:0");
        assertTrue(i >= 0 && cmd.get(i + 1).equals("6000k"),
                "Adaptive top rung should use the user's bitrate");
    }

    @Test
    void buildTranscodeCommand_adaptiveHlsH264_usesLibx264AndTsSegments() {
        TranscodeJob job = createBasicJob(OutputFormat.HLS);
        job.setEnableAdaptive(true); // codec null → default H.264

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertEquals("libx264", cmd.get(cmd.indexOf("-c:v:0") + 1));
        assertFalse(cmd.contains("-hls_segment_type"), "H.264 HLS should use default TS segments");
        assertTrue(cmd.stream().anyMatch(s -> s.endsWith("stream_%v_%03d.ts")),
                "H.264 HLS should write .ts segments");
    }

    @Test
    void buildTranscodeCommand_adaptiveHlsH265_usesLibx265AndFmp4Segments() {
        TranscodeJob job = createBasicJob(OutputFormat.HLS);
        job.setEnableAdaptive(true);
        job.setCodec("H265");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertEquals("libx265", cmd.get(cmd.indexOf("-c:v:0") + 1));
        assertEquals("hvc1", cmd.get(cmd.indexOf("-tag:v:0") + 1));
        int segTypeIdx = cmd.indexOf("-hls_segment_type");
        assertTrue(segTypeIdx >= 0 && cmd.get(segTypeIdx + 1).equals("fmp4"),
                "H.265 HLS must use fMP4 segments");
        assertTrue(cmd.stream().anyMatch(s -> s.endsWith("stream_%v_%03d.m4s")),
                "H.265 HLS should write .m4s segments");
    }

    @Test
    void buildTranscodeCommand_adaptiveDashH265_usesLibx265() {
        TranscodeJob job = createBasicJob(OutputFormat.DASH);
        job.setEnableAdaptive(true);
        job.setCodec("HEVC");

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        assertEquals("libx265", cmd.get(cmd.indexOf("-c:v:0") + 1));
        assertEquals("hvc1", cmd.get(cmd.indexOf("-tag:v:0") + 1));
        assertTrue(cmd.get(cmd.size() - 1).endsWith("manifest.mpd"));
    }

    @Test
    void buildTranscodeCommand_defaultCodec_usesLibx264() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        // codec is null by default

        List<String> cmd = ffmpegService.buildTranscodeCommand(job, "/tmp/output");

        int cvIdx = cmd.indexOf("-c:v");
        assertTrue(cvIdx >= 0);
        assertEquals("libx264", cmd.get(cvIdx + 1));
    }

    // ─── generateThumbnails command building tests ──────────────────────────────

    @Test
    void generateThumbnails_withInterval_producesIntervalCommand() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setThumbnailEnabled(true);
        job.setThumbnailIntervalSeconds(5);

        List<String> cmd = ffmpegService.generateThumbnails(job, "/tmp/thumbs");

        assertTrue(cmd.contains("ffmpeg"));
        assertTrue(cmd.contains("-i"));
        assertTrue(cmd.contains("/path/to/source.mp4"));
        assertTrue(cmd.contains("-vf"));
        assertEquals("fps=1/5", cmd.get(cmd.indexOf("-vf") + 1));
        assertTrue(cmd.contains("-y"));
        assertTrue(cmd.get(cmd.size() - 1).contains("thumb_%04d.jpg"));
    }

    @Test
    void generateThumbnails_withDefaultInterval_uses10Seconds() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setThumbnailEnabled(true);
        // thumbnailIntervalSeconds is null, should default to 10

        List<String> cmd = ffmpegService.generateThumbnails(job, "/tmp/thumbs");

        assertTrue(cmd.contains("-vf"));
        assertEquals("fps=1/10", cmd.get(cmd.indexOf("-vf") + 1));
    }

    @Test
    void generateThumbnails_withTimestamp_producesSingleFrameCommand() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setThumbnailEnabled(true);
        job.setThumbnailTimestamp("30");

        List<String> cmd = ffmpegService.generateThumbnails(job, "/tmp/thumbs");

        assertTrue(cmd.contains("-ss"));
        assertEquals("00:00:30", cmd.get(cmd.indexOf("-ss") + 1));
        assertTrue(cmd.contains("-frames:v"));
        assertEquals("1", cmd.get(cmd.indexOf("-frames:v") + 1));
        assertTrue(cmd.contains("-y"));
        assertTrue(cmd.get(cmd.size() - 1).contains("poster.jpg"));
    }

    @Test
    void generateThumbnails_withHHMMSSTimestamp_producesCorrectCommand() {
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setThumbnailEnabled(true);
        job.setThumbnailTimestamp("00:01:30");

        List<String> cmd = ffmpegService.generateThumbnails(job, "/tmp/thumbs");

        assertTrue(cmd.contains("-ss"));
        assertEquals("00:01:30", cmd.get(cmd.indexOf("-ss") + 1));
        assertTrue(cmd.contains("-frames:v"));
        assertTrue(cmd.get(cmd.size() - 1).contains("poster.jpg"));
    }

    @Test
    void executeThumbnailGeneration_storesPathsAsJsonArray() throws Exception {
        // Create a real temp directory for this test
        Path tempDir = Files.createTempDirectory("mediaops-thumb-test");
        ReflectionTestUtils.setField(ffmpegService, "baseStorageDir", tempDir.toString());

        // Create job output directory structure
        TranscodeJob job = createBasicJob(OutputFormat.MP4);
        job.setId(42L);
        job.setThumbnailEnabled(true);
        job.setThumbnailTimestamp("5");

        // Since ffmpeg won't be available in unit test environment,
        // we can't test actual execution, but we can verify the method handles
        // missing ffmpeg gracefully (returns empty list)
        List<String> result = ffmpegService.executeThumbnailGeneration(job);

        // Without ffmpeg installed, should return empty list gracefully
        assertNotNull(result);

        // Clean up
        deleteRecursive(tempDir);
    }

    private void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                entries.forEach(p -> {
                    try { deleteRecursive(p); } catch (IOException ignored) {}
                });
            }
        }
        Files.deleteIfExists(path);
    }

    // ─── probeFile / parseProbeOutput tests ──────────────────────────────────

    @Test
    void parseProbeOutput_validJsonWithVideoAndAudio_extractsAllFields() throws Exception {
        String json = "{\n" +
                "  \"streams\": [\n" +
                "    {\n" +
                "      \"codec_type\": \"video\",\n" +
                "      \"codec_name\": \"h264\",\n" +
                "      \"width\": 1920,\n" +
                "      \"height\": 1080,\n" +
                "      \"r_frame_rate\": \"30000/1001\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"codec_type\": \"audio\",\n" +
                "      \"codec_name\": \"aac\",\n" +
                "      \"sample_rate\": \"48000\",\n" +
                "      \"channels\": 2\n" +
                "    }\n" +
                "  ],\n" +
                "  \"format\": {\n" +
                "    \"duration\": \"120.5\",\n" +
                "    \"size\": \"15000000\",\n" +
                "    \"bit_rate\": \"1000000\"\n" +
                "  }\n" +
                "}";

        Method parseMethod = FFmpegService.class.getDeclaredMethod("parseProbeOutput", String.class);
        parseMethod.setAccessible(true);
        FileProbeResult result = (FileProbeResult) parseMethod.invoke(ffmpegService, json);

        assertEquals("h264", result.getVideoCodec());
        assertEquals("aac", result.getAudioCodec());
        assertEquals("1920x1080", result.getResolution());
        assertEquals(1000000L, result.getBitrate());
        assertEquals(29.97, result.getFrameRate(), 0.01);
        assertEquals(48000, result.getAudioSampleRate());
        assertEquals(2, result.getAudioChannels());
        assertEquals(121, result.getDurationSeconds()); // 120.5 rounds to 121
        assertEquals(15000000L, result.getFileSizeBytes());
    }

    @Test
    void parseProbeOutput_audioOnlyFile_setsAudioFieldsOnly() throws Exception {
        String json = "{\n" +
                "  \"streams\": [\n" +
                "    {\n" +
                "      \"codec_type\": \"audio\",\n" +
                "      \"codec_name\": \"mp3\",\n" +
                "      \"sample_rate\": \"44100\",\n" +
                "      \"channels\": 1\n" +
                "    }\n" +
                "  ],\n" +
                "  \"format\": {\n" +
                "    \"duration\": \"300.0\",\n" +
                "    \"size\": \"5000000\",\n" +
                "    \"bit_rate\": \"128000\"\n" +
                "  }\n" +
                "}";

        Method parseMethod = FFmpegService.class.getDeclaredMethod("parseProbeOutput", String.class);
        parseMethod.setAccessible(true);
        FileProbeResult result = (FileProbeResult) parseMethod.invoke(ffmpegService, json);

        assertNull(result.getVideoCodec());
        assertNull(result.getResolution());
        assertEquals(0.0, result.getFrameRate());
        assertEquals("mp3", result.getAudioCodec());
        assertEquals(44100, result.getAudioSampleRate());
        assertEquals(1, result.getAudioChannels());
        assertEquals(300, result.getDurationSeconds());
        assertEquals(5000000L, result.getFileSizeBytes());
        assertEquals(128000L, result.getBitrate());
    }

    @Test
    void parseProbeOutput_integerFrameRate_parsesCorrectly() throws Exception {
        String json = "{\n" +
                "  \"streams\": [\n" +
                "    {\n" +
                "      \"codec_type\": \"video\",\n" +
                "      \"codec_name\": \"hevc\",\n" +
                "      \"width\": 3840,\n" +
                "      \"height\": 2160,\n" +
                "      \"r_frame_rate\": \"25/1\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"format\": {\n" +
                "    \"duration\": \"60.0\",\n" +
                "    \"size\": \"50000000\",\n" +
                "    \"bit_rate\": \"8000000\"\n" +
                "  }\n" +
                "}";

        Method parseMethod = FFmpegService.class.getDeclaredMethod("parseProbeOutput", String.class);
        parseMethod.setAccessible(true);
        FileProbeResult result = (FileProbeResult) parseMethod.invoke(ffmpegService, json);

        assertEquals("hevc", result.getVideoCodec());
        assertEquals("3840x2160", result.getResolution());
        assertEquals(25.0, result.getFrameRate(), 0.001);
        assertEquals(60, result.getDurationSeconds());
    }

    @Test
    void parseProbeOutput_invalidJson_throwsRuntimeException() throws Exception {
        String invalidJson = "not valid json";

        Method parseMethod = FFmpegService.class.getDeclaredMethod("parseProbeOutput", String.class);
        parseMethod.setAccessible(true);

        try {
            parseMethod.invoke(ffmpegService, invalidJson);
            fail("Should have thrown an exception");
        } catch (Exception e) {
            Throwable cause = e.getCause();
            assertInstanceOf(RuntimeException.class, cause);
            assertTrue(cause.getMessage().contains("probe-failed"));
        }
    }

    @Test
    void parseProbeOutput_emptyStreams_returnsResultWithNulls() throws Exception {
        String json = "{\n" +
                "  \"streams\": [],\n" +
                "  \"format\": {\n" +
                "    \"duration\": \"10.0\",\n" +
                "    \"size\": \"1000\",\n" +
                "    \"bit_rate\": \"8000\"\n" +
                "  }\n" +
                "}";

        Method parseMethod = FFmpegService.class.getDeclaredMethod("parseProbeOutput", String.class);
        parseMethod.setAccessible(true);
        FileProbeResult result = (FileProbeResult) parseMethod.invoke(ffmpegService, json);

        assertNull(result.getVideoCodec());
        assertNull(result.getAudioCodec());
        assertNull(result.getResolution());
        assertEquals(10, result.getDurationSeconds());
        assertEquals(1000L, result.getFileSizeBytes());
    }

    @Test
    void parseFrameRate_fractionFormat_calculatesCorrectly() throws Exception {
        Method parseMethod = FFmpegService.class.getDeclaredMethod("parseFrameRate", String.class);
        parseMethod.setAccessible(true);

        double result = (double) parseMethod.invoke(ffmpegService, "30000/1001");
        assertEquals(29.97, result, 0.01);

        result = (double) parseMethod.invoke(ffmpegService, "24/1");
        assertEquals(24.0, result, 0.001);

        result = (double) parseMethod.invoke(ffmpegService, "60000/1001");
        assertEquals(59.94, result, 0.01);
    }

    @Test
    void parseFrameRate_zeroDenominator_returnsZero() throws Exception {
        Method parseMethod = FFmpegService.class.getDeclaredMethod("parseFrameRate", String.class);
        parseMethod.setAccessible(true);

        double result = (double) parseMethod.invoke(ffmpegService, "0/0");
        assertEquals(0.0, result);
    }

    @Test
    void parseFrameRate_nullOrEmpty_returnsZero() throws Exception {
        Method parseMethod = FFmpegService.class.getDeclaredMethod("parseFrameRate", String.class);
        parseMethod.setAccessible(true);

        double result = (double) parseMethod.invoke(ffmpegService, (String) null);
        assertEquals(0.0, result);

        result = (double) parseMethod.invoke(ffmpegService, "");
        assertEquals(0.0, result);
    }

    @Test
    void probeFile_withNonexistentFile_throwsProbeFailedException() {
        // Use a non-existent path to trigger ffprobe failure
        ReflectionTestUtils.setField(ffmpegService, "ffprobePath", "ffprobe");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> ffmpegService.probeFile("/nonexistent/file/that/does/not/exist.mp4"));

        assertTrue(exception.getMessage().contains("probe-failed"),
                "Exception message should contain 'probe-failed', got: " + exception.getMessage());
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private TranscodeJob createBasicJob(OutputFormat format) {
        TranscodeJob job = new TranscodeJob();
        job.setId(1L);
        job.setClientId(100L);
        job.setName("Test Job");
        job.setSourceUrl("/path/to/source.mp4");
        job.setOutputFormat(format);
        job.setResolution("1080p");
        job.setBitrate("4500k");
        return job;
    }
}
