import React, { useState, useCallback, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { SimpleMode, type SimpleModeFormState } from './SimpleMode';
import { PresetSelector, type PresetValues } from './PresetSelector';
import { CodecSelector } from './CodecSelector';
import { PrioritySelector } from './PrioritySelector';
import { SchedulePicker } from './SchedulePicker';
import { TrimConfig } from './TrimConfig';
import { SubtitleConfig, type SubtitleMode } from './SubtitleConfig';
import { WatermarkConfig, type WatermarkType, type WatermarkPosition } from './WatermarkConfig';
import { ThumbnailConfig } from './ThumbnailConfig';
import { AudioConfig } from './AudioConfig';
import { CustomParams } from './CustomParams';
import { EncryptionToggle } from './EncryptionToggle';
import { FileProbePanel } from './FileProbePanel';
import { SizeEstimate } from './SizeEstimate';
import { useFileProbe } from '../../hooks/useFileProbe';
import { useSizeEstimate } from '../../hooks/useSizeEstimate';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface AdvancedModeState {
  presetId: number | null;
  codec: string | null;
  priority: 'HIGH' | 'NORMAL' | 'LOW';
  startTime: string;
  endTime: string;
  subtitleFile: File | null;
  subtitleMode: SubtitleMode | null;
  watermarkType: WatermarkType | null;
  watermarkImage: File | null;
  watermarkText: string;
  watermarkPosition: WatermarkPosition;
  watermarkOpacity: number;
  thumbnailEnabled: boolean;
  thumbnailIntervalSeconds: number | null;
  thumbnailTimestamp: string | null;
  audioBitrate: string;
  audioSampleRate: number | null;
  audioChannels: number | null;
  customFfmpegParams: string;
  scheduledAt: string | null;
  encryptionEnabled: boolean;
}

interface AdvancedModeProps {
  formState: SimpleModeFormState;
  onFormChange: (updates: Partial<SimpleModeFormState>) => void;
  onSubmit: (advancedState?: AdvancedModeState) => void;
  onCancel: () => void;
  canSubmit: boolean;
  submitLoading: boolean;
  submitError: string | null;
}

// ─── Section wrapper ──────────────────────────────────────────────────────────

function AdvancedSection({ icon, label, children }: { icon: string; label: string; children?: React.ReactNode }) {
  return (
    <div className="border border-[rgba(0,0,0,0.06)] rounded-xl p-4 bg-[rgba(0,0,0,0.01)]">
      <div className="flex items-center gap-2 mb-2">
        <Icon icon={icon} className="text-pink-500 text-sm" />
        <span className="text-[11px] font-bold text-[rgba(0,0,0,0.6)] uppercase tracking-wide">{label}</span>
      </div>
      {children}
    </div>
  );
}

// ─── Audio format check ───────────────────────────────────────────────────────

const AUDIO_ONLY_FORMATS = ['MP3', 'AAC', 'FLAC', 'OGG'];

// ─── Component ────────────────────────────────────────────────────────────────

export function AdvancedMode({ formState, onFormChange, onSubmit, onCancel, canSubmit, submitLoading, submitError }: AdvancedModeProps) {
  // Advanced state managed locally
  const [advState, setAdvState] = useState<AdvancedModeState>({
    presetId: null,
    codec: null,
    priority: 'NORMAL',
    startTime: '',
    endTime: '',
    subtitleFile: null,
    subtitleMode: null,
    watermarkType: null,
    watermarkImage: null,
    watermarkText: '',
    watermarkPosition: 'BOTTOM_RIGHT',
    watermarkOpacity: 80,
    thumbnailEnabled: false,
    thumbnailIntervalSeconds: 10,
    thumbnailTimestamp: null,
    audioBitrate: '128k',
    audioSampleRate: null,
    audioChannels: null,
    customFfmpegParams: '',
    scheduledAt: null,
    encryptionEnabled: false,
  });

  // File probe hook
  const { probeResult, probing, probeError, probeFile, clearProbe } = useFileProbe();

  // Size estimate hook - derive bitrate from formState or probe
  const parseBitrate = useCallback((bitrateStr: string): number | null => {
    if (!bitrateStr) return null;
    const lower = bitrateStr.toLowerCase().trim();
    const numMatch = lower.match(/^([\d.]+)\s*(k|m|mbps|kbps)?$/);
    if (!numMatch) return null;
    const num = parseFloat(numMatch[1]);
    const unit = numMatch[2] || '';
    if (unit === 'm' || unit === 'mbps') return num * 1_000_000;
    if (unit === 'k' || unit === 'kbps') return num * 1_000;
    // If raw number > 1000, treat as bps; otherwise assume kbps
    return num > 1000 ? num : num * 1_000;
  }, []);

  const videoBitrate = parseBitrate(formState.bitrate) ?? probeResult?.videoBitrate ?? null;
  const audioBitrateForEstimate = parseBitrate(advState.audioBitrate) ?? probeResult?.audioBitrate ?? null;
  const durationSeconds = probeResult?.durationSeconds ?? null;

  const { estimation } = useSizeEstimate({
    videoBitrate,
    audioBitrate: audioBitrateForEstimate,
    durationSeconds,
  });

  // Wire file probe: trigger on source URL blur
  const handleSourceBlur = useCallback(() => {
    if (formState.sourceMode === 'url' && formState.sourceUrl.trim()) {
      probeFile(formState.sourceUrl.trim());
    }
  }, [formState.sourceMode, formState.sourceUrl, probeFile]);

  // Clear probe when source URL changes significantly
  useEffect(() => {
    if (!formState.sourceUrl.trim()) {
      clearProbe();
    }
  }, [formState.sourceUrl, clearProbe]);

  // Helper to update partial advanced state
  const updateAdv = useCallback(<K extends keyof AdvancedModeState>(field: K, value: AdvancedModeState[K]) => {
    setAdvState(prev => ({ ...prev, [field]: value }));
  }, []);

  // Apply preset handler — applies both simple fields and advanced fields
  function handleApplyPreset(values: PresetValues) {
    const updates: Partial<SimpleModeFormState> = {};
    if (values.outputFormat) updates.outputFormat = values.outputFormat;
    if (values.resolution) updates.resolution = values.resolution;
    if (values.bitrate) updates.bitrate = values.bitrate;
    onFormChange(updates);

    // Also apply advanced fields from preset
    if (values.id) updateAdv('presetId', values.id);
    if (values.codec) updateAdv('codec', values.codec);
    if (values.audioBitrate) updateAdv('audioBitrate', values.audioBitrate);
    if (values.customFfmpegParams) updateAdv('customFfmpegParams', values.customFfmpegParams);
  }

  // Submit with advanced state
  const handleSubmit = useCallback(() => {
    onSubmit(advState);
  }, [onSubmit, advState]);

  const isAudioOnlyFormat = AUDIO_ONLY_FORMATS.includes(formState.outputFormat.toUpperCase());

  return (
    <div className="space-y-5">
      {/* Include all SimpleMode fields at the top, with onBlur for probe */}
      <div onBlur={(e) => {
        // Check if blur is from a source URL input
        const target = e.target as HTMLInputElement;
        if (target.tagName === 'INPUT' && target.placeholder?.includes('s3://')) {
          handleSourceBlur();
        }
      }}>
        <SimpleMode
          formState={formState}
          onFormChange={onFormChange}
          onSubmit={handleSubmit}
          onCancel={onCancel}
          canSubmit={canSubmit}
          submitLoading={submitLoading}
          submitError={null}
          hideActions
        />
      </div>

      {/* File Probe Panel - shows between source and settings */}
      <FileProbePanel probeResult={probeResult} probing={probing} probeError={probeError} />

      {/* Divider */}
      <div className="relative py-1">
        <div className="absolute inset-0 flex items-center">
          <div className="w-full border-t border-[rgba(0,0,0,0.06)]" />
        </div>
        <div className="relative flex justify-center">
          <span className="bg-white px-3 text-[10px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)]">Advanced Options</span>
        </div>
      </div>

      {/* Advanced sections grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Preset Selector */}
        <AdvancedSection icon="mdi:content-save-cog-outline" label="Preset">
          <PresetSelector onApplyPreset={handleApplyPreset} />
        </AdvancedSection>

        {/* Codec Selection — hidden for audio-only */}
        {!isAudioOnlyFormat && (
        <AdvancedSection icon="mdi:cog-outline" label="Codec">
          <CodecSelector value={advState.codec} onChange={(codec) => updateAdv('codec', codec)} outputFormat={formState.outputFormat} />
        </AdvancedSection>
        )}

        {/* Priority */}
        <AdvancedSection icon="mdi:sort-variant" label="Priority">
          <PrioritySelector value={advState.priority} onChange={(p) => updateAdv('priority', p)} />
        </AdvancedSection>

        {/* Trimming — video only */}
        {!isAudioOnlyFormat && (
        <AdvancedSection icon="mdi:content-cut" label="Trimming">
          <TrimConfig
            startTime={advState.startTime}
            endTime={advState.endTime}
            onStartTimeChange={(v) => updateAdv('startTime', v)}
            onEndTimeChange={(v) => updateAdv('endTime', v)}
          />
        </AdvancedSection>
        )}

        {/* Subtitles — video only */}
        {!isAudioOnlyFormat && (
        <AdvancedSection icon="mdi:subtitles-outline" label="Subtitles">
          <SubtitleConfig
            subtitleFile={advState.subtitleFile}
            subtitleMode={advState.subtitleMode}
            onFileChange={(f) => updateAdv('subtitleFile', f)}
            onModeChange={(m) => updateAdv('subtitleMode', m)}
          />
        </AdvancedSection>
        )}

        {/* Watermark — video only */}
        {!isAudioOnlyFormat && (
        <AdvancedSection icon="mdi:watermark" label="Watermark">
          <WatermarkConfig
            watermarkType={advState.watermarkType}
            watermarkImage={advState.watermarkImage}
            watermarkText={advState.watermarkText}
            position={advState.watermarkPosition}
            opacity={advState.watermarkOpacity}
            onTypeChange={(t) => updateAdv('watermarkType', t)}
            onImageChange={(f) => updateAdv('watermarkImage', f)}
            onTextChange={(t) => updateAdv('watermarkText', t)}
            onPositionChange={(p) => updateAdv('watermarkPosition', p)}
            onOpacityChange={(o) => updateAdv('watermarkOpacity', o)}
          />
        </AdvancedSection>
        )}

        {/* Thumbnails — video only */}
        {!isAudioOnlyFormat && (
        <AdvancedSection icon="mdi:image-multiple-outline" label="Thumbnails">
          <ThumbnailConfig
            enabled={advState.thumbnailEnabled}
            intervalSeconds={advState.thumbnailIntervalSeconds}
            timestamp={advState.thumbnailTimestamp}
            onEnabledChange={(e) => updateAdv('thumbnailEnabled', e)}
            onIntervalChange={(i) => updateAdv('thumbnailIntervalSeconds', i)}
            onTimestampChange={(t) => updateAdv('thumbnailTimestamp', t)}
          />
        </AdvancedSection>
        )}

        {/* Audio Parameters — shows for audio-only formats */}
        {isAudioOnlyFormat && (
        <AdvancedSection icon="mdi:music-note-outline" label="Audio">
          <AudioConfig
            audioBitrate={advState.audioBitrate}
            audioSampleRate={advState.audioSampleRate}
            audioChannels={advState.audioChannels}
            isAudioOnlyFormat={true}
            onBitrateChange={(b) => updateAdv('audioBitrate', b)}
            onSampleRateChange={(r) => updateAdv('audioSampleRate', r)}
            onChannelsChange={(c) => updateAdv('audioChannels', c)}
          />
        </AdvancedSection>
        )}

        {/* Custom FFmpeg Parameters */}
        <AdvancedSection icon="mdi:console" label="Custom FFmpeg Params">
          <CustomParams
            value={advState.customFfmpegParams}
            onChange={(v) => updateAdv('customFfmpegParams', v)}
          />
        </AdvancedSection>

        {/* Scheduling */}
        <AdvancedSection icon="mdi:calendar-clock" label="Schedule">
          <SchedulePicker
            value={advState.scheduledAt}
            onChange={(s) => updateAdv('scheduledAt', s)}
          />
        </AdvancedSection>

        {/* Encryption — only for HLS */}
        <AdvancedSection icon="mdi:lock-outline" label="Encryption">
          <EncryptionToggle
            enabled={advState.encryptionEnabled}
            outputFormat={formState.outputFormat}
            onChange={(e) => updateAdv('encryptionEnabled', e)}
          />
        </AdvancedSection>
      </div>

      {/* Size Estimate - near the submit button area */}
      <SizeEstimate estimation={estimation} />

      {/* Error + Actions */}
      {submitError && (
        <p className="text-[12px] text-red-600 bg-red-50 rounded-xl px-3 py-2">{submitError}</p>
      )}

      <div className="flex items-center gap-3 pt-1">
        <button onClick={handleSubmit} disabled={!canSubmit || submitLoading}
          className="flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-pink-500 to-rose-600 text-white text-[12px] font-bold rounded-xl shadow disabled:opacity-50 transition-all hover:scale-[1.02]">
          {submitLoading ? <Icon icon="mdi:loading" className="animate-spin" /> : <Icon icon="mdi:play" />}
          {submitLoading ? (formState.uploadProgress === 'uploading' ? 'Uploading…' : 'Submitting…') : (formState.sourceMode === 'upload' && formState.localFile ? 'Upload & Transcode' : 'Submit Job')}
        </button>
        <button onClick={onCancel}
          className="px-4 py-2.5 text-[12px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors">Cancel</button>
      </div>
    </div>
  );
}
