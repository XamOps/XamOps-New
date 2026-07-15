import React, { useState, useCallback, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { SimpleMode, type SimpleModeFormState } from './SimpleMode';
import { PresetSelector, type PresetValues } from './PresetSelector';
import { CodecSelector } from './CodecSelector';
import { PrioritySelector } from './PrioritySelector';
import { SchedulePicker } from './SchedulePicker';
import { TrimConfig } from './TrimConfig';
import { SubtitleConfig, type SubtitleMode } from './SubtitleConfig';
import { WatermarkConfig, type WatermarkType } from './WatermarkConfig';
import { VideoPreviewPanel } from './VideoPreviewPanel';
import { ThumbnailConfig } from './ThumbnailConfig';
import { AudioConfig } from './AudioConfig';
// import { CustomParams } from './CustomParams'; // hidden from UI for now — see below
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
  watermarkX: number;
  watermarkY: number;
  watermarkOpacity: number;
  watermarkScale: number;
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
  onBrowseS3?: () => void;
}

// ─── Layout helpers ───────────────────────────────────────────────────────────

function SectionGroup({ icon, title, first }: { icon: string; title: string; first?: boolean }) {
  return (
    <div className={`flex items-center gap-2 ${first ? 'pt-3' : 'pt-5'} pb-2`}>
      <Icon icon={icon} className="text-blue-400 text-[12px]" />
      <span className="text-[10px] font-bold text-[rgba(0,0,0,0.38)] uppercase tracking-[0.13em]">{title}</span>
      <div className="flex-1 h-px bg-[rgba(0,0,0,0.07)]" />
    </div>
  );
}

function OptionRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-4 py-1.5">
      <span className="text-[12px] text-[rgba(0,0,0,0.45)] w-[76px] shrink-0">{label}</span>
      <div className="flex-1 min-w-0">{children}</div>
    </div>
  );
}

// ─── Audio format check ───────────────────────────────────────────────────────

const AUDIO_ONLY_FORMATS = ['MP3', 'AAC', 'FLAC', 'OGG'];

// ─── Component ────────────────────────────────────────────────────────────────

export function AdvancedMode({ formState, onFormChange, onSubmit, onCancel, canSubmit, submitLoading, submitError, onBrowseS3 }: AdvancedModeProps) {
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
    watermarkX: 85,
    watermarkY: 85,
    watermarkOpacity: 80,
    watermarkScale: 15,
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
    if (values.outputFormat) updates.outputFormats = [values.outputFormat];
    if (values.resolution) updates.resolutions = [values.resolution];
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

  const isAudioOnlyFormat = formState.outputFormats.every(f => AUDIO_ONLY_FORMATS.includes(f.toUpperCase()));

  // Convert between the HH:MM:SS trim state and seconds for the visual trim track
  const hmsToSeconds = (str: string): number | null => {
    if (!str) return null;
    const parts = str.split(':').map(p => parseInt(p, 10));
    if (parts.some(isNaN)) return null;
    return parts.reduce((acc, p) => acc * 60 + p, 0);
  };
  const secondsToHms = (s: number): string => {
    const t = Math.max(0, Math.round(s));
    const hh = Math.floor(t / 3600), mm = Math.floor((t % 3600) / 60), ss = t % 60;
    return [hh, mm, ss].map(n => String(n).padStart(2, '0')).join(':');
  };
  const handleTrimChange = (startSec: number, endSec: number) => {
    updateAdv('startTime', startSec <= 0 ? '' : secondsToHms(startSec));
    updateAdv('endTime', secondsToHms(endSec));
  };

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
          onBrowseS3={onBrowseS3}
        />
      </div>

      {/* File Probe Panel - shows between source and settings */}
      <FileProbePanel probeResult={probeResult} probing={probing} probeError={probeError} />

      {/* Video Preview + Live Watermark — shown when a video source is available */}
      {!isAudioOnlyFormat && (
        formState.sourceMode === 'upload' && formState.localFile
          ? (
            <VideoPreviewPanel
              sourceFile={formState.localFile}
              sourceUrl=""
              sourceMode="upload"
              watermarkType={advState.watermarkType}
              watermarkImage={advState.watermarkImage}
              watermarkText={advState.watermarkText}
              watermarkX={advState.watermarkX}
              watermarkY={advState.watermarkY}
              watermarkOpacity={advState.watermarkOpacity}
              watermarkScale={advState.watermarkScale}
              onPositionChange={(x, y) => { updateAdv('watermarkX', x); updateAdv('watermarkY', y); }}
              onScaleChange={(s) => updateAdv('watermarkScale', s)}
              trimStartSec={hmsToSeconds(advState.startTime)}
              trimEndSec={hmsToSeconds(advState.endTime)}
              onTrimChange={handleTrimChange}
            />
          )
          : formState.sourceMode === 'url' && formState.sourceUrl.trim() ? (
            <VideoPreviewPanel
              sourceFile={null}
              sourceUrl={formState.sourceUrl.trim()}
              sourceMode="url"
              watermarkType={advState.watermarkType}
              watermarkImage={advState.watermarkImage}
              watermarkText={advState.watermarkText}
              watermarkX={advState.watermarkX}
              watermarkY={advState.watermarkY}
              watermarkOpacity={advState.watermarkOpacity}
              watermarkScale={advState.watermarkScale}
              onPositionChange={(x, y) => { updateAdv('watermarkX', x); updateAdv('watermarkY', y); }}
              onScaleChange={(s) => updateAdv('watermarkScale', s)}
              trimStartSec={hmsToSeconds(advState.startTime)}
              trimEndSec={hmsToSeconds(advState.endTime)}
              onTrimChange={handleTrimChange}
            />
          ) : null
      )}

      {/* Divider */}
      <div className="relative py-1">
        <div className="absolute inset-0 flex items-center">
          <div className="w-full border-t border-[rgba(0,0,0,0.06)]" />
        </div>
        <div className="relative flex justify-center">
          <span className="bg-white px-3 text-[10px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)]">Advanced Options</span>
        </div>
      </div>

      {/* Advanced Options Panel */}
      <div className="rounded-xl border border-[rgba(0,0,0,0.07)] bg-white px-4 pb-5">

        {/* ── Encoding ── */}
        <SectionGroup icon="mdi:cog-outline" title="Encoding" first />
        <OptionRow label="Preset">
          <PresetSelector onApplyPreset={handleApplyPreset} />
        </OptionRow>
        {!isAudioOnlyFormat && (
          <OptionRow label="Codec">
            <CodecSelector value={advState.codec} onChange={(codec) => updateAdv('codec', codec)} outputFormat={formState.outputFormats[0] ?? 'HLS'} />
          </OptionRow>
        )}
        <OptionRow label="Priority">
          <PrioritySelector value={advState.priority} onChange={(p) => updateAdv('priority', p)} />
        </OptionRow>

        {/* ── Timing ── video only */}
        {!isAudioOnlyFormat && (
          <>
            <SectionGroup icon="mdi:content-cut" title="Timing" />
            <TrimConfig
              startTime={advState.startTime}
              endTime={advState.endTime}
              onStartTimeChange={(v) => updateAdv('startTime', v)}
              onEndTimeChange={(v) => updateAdv('endTime', v)}
            />
            <OptionRow label="Schedule">
              <SchedulePicker value={advState.scheduledAt} onChange={(s) => updateAdv('scheduledAt', s)} />
            </OptionRow>
          </>
        )}

        {/* ── Audio ── audio-only formats */}
        {isAudioOnlyFormat && (
          <>
            <SectionGroup icon="mdi:music-note-outline" title="Audio" />
            <AudioConfig
              audioBitrate={advState.audioBitrate}
              audioSampleRate={advState.audioSampleRate}
              audioChannels={advState.audioChannels}
              isAudioOnlyFormat
              onBitrateChange={(b) => updateAdv('audioBitrate', b)}
              onSampleRateChange={(r) => updateAdv('audioSampleRate', r)}
              onChannelsChange={(c) => updateAdv('audioChannels', c)}
            />
          </>
        )}

        {/* ── Watermark ── video only */}
        {!isAudioOnlyFormat && (
          <>
            <SectionGroup icon="mdi:watermark" title="Watermark" />
            <WatermarkConfig
              watermarkType={advState.watermarkType}
              watermarkImage={advState.watermarkImage}
              watermarkText={advState.watermarkText}
              watermarkX={advState.watermarkX}
              watermarkY={advState.watermarkY}
              opacity={advState.watermarkOpacity}
              scale={advState.watermarkScale}
              onTypeChange={(t) => updateAdv('watermarkType', t)}
              onImageChange={(f) => updateAdv('watermarkImage', f)}
              onTextChange={(t) => updateAdv('watermarkText', t)}
              onPositionChange={(x, y) => { updateAdv('watermarkX', x); updateAdv('watermarkY', y); }}
              onOpacityChange={(o) => updateAdv('watermarkOpacity', o)}
              onScaleChange={(s) => updateAdv('watermarkScale', s)}
            />
          </>
        )}

        {/* ── Subtitles ── video only */}
        {!isAudioOnlyFormat && (
          <>
            <SectionGroup icon="mdi:subtitles-outline" title="Subtitles" />
            <SubtitleConfig
              subtitleFile={advState.subtitleFile}
              subtitleMode={advState.subtitleMode}
              onFileChange={(f) => updateAdv('subtitleFile', f)}
              onModeChange={(m) => updateAdv('subtitleMode', m)}
            />
          </>
        )}

        {/* ── Output ── video only */}
        {!isAudioOnlyFormat && (
          <>
            <SectionGroup icon="mdi:image-multiple-outline" title="Output" />
            <ThumbnailConfig
              enabled={advState.thumbnailEnabled}
              intervalSeconds={advState.thumbnailIntervalSeconds}
              timestamp={advState.thumbnailTimestamp}
              onEnabledChange={(e) => updateAdv('thumbnailEnabled', e)}
              onIntervalChange={(i) => updateAdv('thumbnailIntervalSeconds', i)}
              onTimestampChange={(t) => updateAdv('thumbnailTimestamp', t)}
            />
            <OptionRow label="Encryption">
              <EncryptionToggle
                enabled={advState.encryptionEnabled}
                outputFormat={formState.outputFormats[0] ?? 'HLS'}
                onChange={(e) => updateAdv('encryptionEnabled', e)}
              />
            </OptionRow>
          </>
        )}

        {/* ── Custom params ── */}
        {/* Hidden from the UI for now — advanced FFmpeg flags are a power-user
            feature and are also ignored for adaptive jobs. Revisit later.
        <SectionGroup icon="mdi:console" title="Custom Params" />
        <CustomParams
          value={advState.customFfmpegParams}
          onChange={(v) => updateAdv('customFfmpegParams', v)}
        />
        */}
      </div>

      {/* Size Estimate - near the submit button area */}
      <SizeEstimate estimation={estimation} />

      {/* Error + Actions */}
      {submitError && (
        <p className="text-[12px] text-red-600 bg-red-50 rounded-xl px-3 py-2">{submitError}</p>
      )}

      <div className="flex items-center gap-3 pt-1">
        <button onClick={handleSubmit} disabled={!canSubmit || submitLoading}
          className="flex items-center gap-2 px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-[12px] font-bold rounded-xl shadow-sm disabled:opacity-50 transition-colors">
          {submitLoading ? <Icon icon="mdi:loading" className="animate-spin" /> : <Icon icon="mdi:play" />}
          {submitLoading ? (formState.uploadProgress === 'uploading' ? 'Uploading…' : 'Submitting…') : (formState.sourceMode === 'upload' && formState.localFile ? 'Upload & Transcode' : 'Submit Job')}
        </button>
        <button onClick={onCancel}
          className="px-4 py-2.5 text-[12px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors">Cancel</button>
      </div>
    </div>
  );
}
