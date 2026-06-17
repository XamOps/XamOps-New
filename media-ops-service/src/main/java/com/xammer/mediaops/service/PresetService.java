package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodePreset;
import com.xammer.mediaops.dto.PresetRequest;
import com.xammer.mediaops.dto.TranscodeJobRequest;
import com.xammer.mediaops.repository.TranscodePresetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class PresetService {

    private static final Logger log = LoggerFactory.getLogger(PresetService.class);

    private final TranscodePresetRepository presetRepo;

    public PresetService(TranscodePresetRepository presetRepo) {
        this.presetRepo = presetRepo;
    }

    /**
     * Creates a new transcode preset for the given client.
     */
    @Transactional
    public TranscodePreset create(Long clientId, PresetRequest req) {
        TranscodePreset preset = new TranscodePreset();
        preset.setClientId(clientId);
        preset.setName(req.getName());
        preset.setOutputFormat(req.getOutputFormat());
        preset.setResolution(req.getResolution());
        preset.setBitrate(req.getBitrate());
        preset.setFps(req.getFps());
        preset.setCodec(req.getCodec());
        preset.setAudioBitrate(req.getAudioBitrate());
        preset.setCustomFfmpegParams(req.getCustomFfmpegParams());
        preset.setCreatedAt(Instant.now());
        return presetRepo.save(preset);
    }

    /**
     * Lists all presets for a client, ordered by most recently created first.
     */
    public List<TranscodePreset> listByClient(Long clientId) {
        return presetRepo.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    /**
     * Updates an existing preset. Verifies client ownership before modifying.
     *
     * @throws IllegalArgumentException if preset not found or does not belong to the client
     */
    @Transactional
    public TranscodePreset update(Long id, Long clientId, PresetRequest req) {
        TranscodePreset preset = presetRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Preset not found: " + id));

        if (!preset.getClientId().equals(clientId)) {
            throw new IllegalArgumentException("Preset not found: " + id);
        }

        preset.setName(req.getName());
        preset.setOutputFormat(req.getOutputFormat());
        preset.setResolution(req.getResolution());
        preset.setBitrate(req.getBitrate());
        preset.setFps(req.getFps());
        preset.setCodec(req.getCodec());
        preset.setAudioBitrate(req.getAudioBitrate());
        preset.setCustomFfmpegParams(req.getCustomFfmpegParams());
        preset.setUpdatedAt(Instant.now());
        return presetRepo.save(preset);
    }

    /**
     * Deletes a preset. Verifies client ownership before deleting.
     *
     * @throws IllegalArgumentException if preset not found or does not belong to the client
     */
    @Transactional
    public void delete(Long id, Long clientId) {
        TranscodePreset preset = presetRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Preset not found: " + id));

        if (!preset.getClientId().equals(clientId)) {
            throw new IllegalArgumentException("Preset not found: " + id);
        }

        presetRepo.delete(preset);
    }

    /**
     * Applies preset values to a job request. Explicit request values always override preset values.
     * For each field in the preset, if the request's corresponding field is null (or empty for strings),
     * it is populated from the preset.
     */
    public TranscodeJobRequest applyPreset(TranscodeJobRequest req, TranscodePreset preset) {
        if (isNullOrEmpty(req.getOutputFormat()) && preset.getOutputFormat() != null) {
            req.setOutputFormat(preset.getOutputFormat());
        }
        if (isNullOrEmpty(req.getResolution()) && preset.getResolution() != null) {
            req.setResolution(preset.getResolution());
        }
        if (isNullOrEmpty(req.getBitrate()) && preset.getBitrate() != null) {
            req.setBitrate(preset.getBitrate());
        }
        if (req.getFps() == null && preset.getFps() != null) {
            req.setFps(preset.getFps());
        }
        if (isNullOrEmpty(req.getCodec()) && preset.getCodec() != null) {
            req.setCodec(preset.getCodec());
        }
        if (isNullOrEmpty(req.getAudioBitrate()) && preset.getAudioBitrate() != null) {
            req.setAudioBitrate(preset.getAudioBitrate());
        }
        if (isNullOrEmpty(req.getCustomFfmpegParams()) && preset.getCustomFfmpegParams() != null) {
            req.setCustomFfmpegParams(preset.getCustomFfmpegParams());
        }
        return req;
    }

    private boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
