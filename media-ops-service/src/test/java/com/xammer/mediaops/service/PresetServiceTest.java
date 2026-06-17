package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodePreset;
import com.xammer.mediaops.dto.PresetRequest;
import com.xammer.mediaops.dto.TranscodeJobRequest;
import com.xammer.mediaops.repository.TranscodePresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PresetServiceTest {

    @Mock
    private TranscodePresetRepository presetRepo;

    private PresetService presetService;

    @BeforeEach
    void setUp() {
        presetService = new PresetService(presetRepo);
    }

    // ========== create ==========

    @Test
    void create_mapsAllFieldsAndSaves() {
        PresetRequest req = new PresetRequest();
        req.setName("My Preset");
        req.setOutputFormat("MP4");
        req.setResolution("1080p");
        req.setBitrate("5000k");
        req.setFps(30);
        req.setCodec("H264");
        req.setAudioBitrate("128k");
        req.setCustomFfmpegParams("-crf 23");

        when(presetRepo.save(any(TranscodePreset.class))).thenAnswer(inv -> {
            TranscodePreset p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        TranscodePreset result = presetService.create(42L, req);

        assertEquals(42L, result.getClientId());
        assertEquals("My Preset", result.getName());
        assertEquals("MP4", result.getOutputFormat());
        assertEquals("1080p", result.getResolution());
        assertEquals("5000k", result.getBitrate());
        assertEquals(30, result.getFps());
        assertEquals("H264", result.getCodec());
        assertEquals("128k", result.getAudioBitrate());
        assertEquals("-crf 23", result.getCustomFfmpegParams());
        assertNotNull(result.getCreatedAt());
        verify(presetRepo).save(any(TranscodePreset.class));
    }

    // ========== listByClient ==========

    @Test
    void listByClient_delegatesToRepository() {
        TranscodePreset p1 = new TranscodePreset();
        p1.setId(1L);
        TranscodePreset p2 = new TranscodePreset();
        p2.setId(2L);

        when(presetRepo.findByClientIdOrderByCreatedAtDesc(42L))
                .thenReturn(Arrays.asList(p1, p2));

        List<TranscodePreset> result = presetService.listByClient(42L);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(2L, result.get(1).getId());
    }

    // ========== update ==========

    @Test
    void update_updatesFieldsAndSetsUpdatedAt() {
        TranscodePreset existing = new TranscodePreset();
        existing.setId(1L);
        existing.setClientId(42L);
        existing.setName("Old Name");

        when(presetRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(presetRepo.save(any(TranscodePreset.class))).thenAnswer(inv -> inv.getArgument(0));

        PresetRequest req = new PresetRequest();
        req.setName("New Name");
        req.setOutputFormat("HLS");
        req.setResolution("720p");
        req.setBitrate("3000k");
        req.setFps(24);
        req.setCodec("H265");
        req.setAudioBitrate("192k");
        req.setCustomFfmpegParams("-preset fast");

        TranscodePreset result = presetService.update(1L, 42L, req);

        assertEquals("New Name", result.getName());
        assertEquals("HLS", result.getOutputFormat());
        assertEquals("720p", result.getResolution());
        assertEquals("3000k", result.getBitrate());
        assertEquals(24, result.getFps());
        assertEquals("H265", result.getCodec());
        assertEquals("192k", result.getAudioBitrate());
        assertEquals("-preset fast", result.getCustomFfmpegParams());
        assertNotNull(result.getUpdatedAt());
    }

    @Test
    void update_presetNotFound_throwsException() {
        when(presetRepo.findById(99L)).thenReturn(Optional.empty());

        PresetRequest req = new PresetRequest();
        req.setName("Name");

        assertThrows(IllegalArgumentException.class,
                () -> presetService.update(99L, 42L, req));
    }

    @Test
    void update_clientIdMismatch_throwsException() {
        TranscodePreset existing = new TranscodePreset();
        existing.setId(1L);
        existing.setClientId(42L);

        when(presetRepo.findById(1L)).thenReturn(Optional.of(existing));

        PresetRequest req = new PresetRequest();
        req.setName("Name");

        assertThrows(IllegalArgumentException.class,
                () -> presetService.update(1L, 999L, req));
    }

    // ========== delete ==========

    @Test
    void delete_deletesPreset() {
        TranscodePreset existing = new TranscodePreset();
        existing.setId(1L);
        existing.setClientId(42L);

        when(presetRepo.findById(1L)).thenReturn(Optional.of(existing));

        presetService.delete(1L, 42L);

        verify(presetRepo).delete(existing);
    }

    @Test
    void delete_presetNotFound_throwsException() {
        when(presetRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> presetService.delete(99L, 42L));
    }

    @Test
    void delete_clientIdMismatch_throwsException() {
        TranscodePreset existing = new TranscodePreset();
        existing.setId(1L);
        existing.setClientId(42L);

        when(presetRepo.findById(1L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> presetService.delete(1L, 999L));
    }

    // ========== applyPreset ==========

    @Test
    void applyPreset_emptyRequest_allFieldsFromPreset() {
        TranscodeJobRequest req = new TranscodeJobRequest();
        req.setOutputFormat(null);
        req.setResolution(null);
        req.setBitrate(null);
        req.setFps(null);
        req.setCodec(null);
        req.setAudioBitrate(null);
        req.setCustomFfmpegParams(null);

        TranscodePreset preset = new TranscodePreset();
        preset.setOutputFormat("MP4");
        preset.setResolution("1080p");
        preset.setBitrate("5000k");
        preset.setFps(30);
        preset.setCodec("H264");
        preset.setAudioBitrate("128k");
        preset.setCustomFfmpegParams("-crf 23");

        TranscodeJobRequest result = presetService.applyPreset(req, preset);

        assertEquals("MP4", result.getOutputFormat());
        assertEquals("1080p", result.getResolution());
        assertEquals("5000k", result.getBitrate());
        assertEquals(30, result.getFps());
        assertEquals("H264", result.getCodec());
        assertEquals("128k", result.getAudioBitrate());
        assertEquals("-crf 23", result.getCustomFfmpegParams());
    }

    @Test
    void applyPreset_explicitValuesOverridePreset() {
        TranscodeJobRequest req = new TranscodeJobRequest();
        req.setOutputFormat("HLS");
        req.setResolution("720p");
        req.setBitrate("3000k");
        req.setFps(24);
        req.setCodec("H265");
        req.setAudioBitrate("192k");
        req.setCustomFfmpegParams("-preset fast");

        TranscodePreset preset = new TranscodePreset();
        preset.setOutputFormat("MP4");
        preset.setResolution("1080p");
        preset.setBitrate("5000k");
        preset.setFps(30);
        preset.setCodec("H264");
        preset.setAudioBitrate("128k");
        preset.setCustomFfmpegParams("-crf 23");

        TranscodeJobRequest result = presetService.applyPreset(req, preset);

        // Explicit values must be preserved
        assertEquals("HLS", result.getOutputFormat());
        assertEquals("720p", result.getResolution());
        assertEquals("3000k", result.getBitrate());
        assertEquals(24, result.getFps());
        assertEquals("H265", result.getCodec());
        assertEquals("192k", result.getAudioBitrate());
        assertEquals("-preset fast", result.getCustomFfmpegParams());
    }

    @Test
    void applyPreset_partialOverride_mixesPresetAndExplicit() {
        TranscodeJobRequest req = new TranscodeJobRequest();
        req.setOutputFormat("HLS");
        req.setResolution(null);
        req.setBitrate(null);
        req.setFps(24);
        req.setCodec(null);
        req.setAudioBitrate(null);
        req.setCustomFfmpegParams(null);

        TranscodePreset preset = new TranscodePreset();
        preset.setOutputFormat("MP4");
        preset.setResolution("1080p");
        preset.setBitrate("5000k");
        preset.setFps(30);
        preset.setCodec("H264");
        preset.setAudioBitrate("128k");
        preset.setCustomFfmpegParams("-crf 23");

        TranscodeJobRequest result = presetService.applyPreset(req, preset);

        // Explicit values preserved
        assertEquals("HLS", result.getOutputFormat());
        assertEquals(24, result.getFps());
        // Preset values filled in where request was null
        assertEquals("1080p", result.getResolution());
        assertEquals("5000k", result.getBitrate());
        assertEquals("H264", result.getCodec());
        assertEquals("128k", result.getAudioBitrate());
        assertEquals("-crf 23", result.getCustomFfmpegParams());
    }

    @Test
    void applyPreset_emptyStringTreatedAsNull() {
        TranscodeJobRequest req = new TranscodeJobRequest();
        req.setOutputFormat("");
        req.setResolution("");
        req.setBitrate("");
        req.setCodec("");
        req.setAudioBitrate("");
        req.setCustomFfmpegParams("");

        TranscodePreset preset = new TranscodePreset();
        preset.setOutputFormat("MP4");
        preset.setResolution("1080p");
        preset.setBitrate("5000k");
        preset.setCodec("H264");
        preset.setAudioBitrate("128k");
        preset.setCustomFfmpegParams("-crf 23");

        TranscodeJobRequest result = presetService.applyPreset(req, preset);

        // Empty strings should be treated as "not set", so preset fills them in
        assertEquals("MP4", result.getOutputFormat());
        assertEquals("1080p", result.getResolution());
        assertEquals("5000k", result.getBitrate());
        assertEquals("H264", result.getCodec());
        assertEquals("128k", result.getAudioBitrate());
        assertEquals("-crf 23", result.getCustomFfmpegParams());
    }

    @Test
    void applyPreset_presetNullFields_requestUnchanged() {
        TranscodeJobRequest req = new TranscodeJobRequest();
        req.setOutputFormat(null);
        req.setResolution(null);

        TranscodePreset preset = new TranscodePreset();
        // All preset fields are null

        TranscodeJobRequest result = presetService.applyPreset(req, preset);

        assertNull(result.getOutputFormat());
        assertNull(result.getResolution());
        assertNull(result.getBitrate());
        assertNull(result.getFps());
        assertNull(result.getCodec());
        assertNull(result.getAudioBitrate());
        assertNull(result.getCustomFfmpegParams());
    }
}
