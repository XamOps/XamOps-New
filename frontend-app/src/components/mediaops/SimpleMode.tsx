import { Icon } from '@iconify/react';
import type { S3PickedFile } from './S3PickerModal';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface SimpleModeFormState {
  sourceMode: 'url' | 'upload';
  sourceUrl: string;
  localFile: File | null;
  uploadProgress: 'idle' | 'uploading' | 'done';
  outputFormat: string;
  resolution: string;
  bitrate: string;
  enableAdaptive: boolean;
  s3PickedFile?: S3PickedFile | null;
}

interface SimpleModeProps {
  formState: SimpleModeFormState;
  onFormChange: (updates: Partial<SimpleModeFormState>) => void;
  onSubmit: () => void;
  onCancel: () => void;
  canSubmit: boolean;
  submitLoading: boolean;
  submitError: string | null;
  /** When true, hides action buttons and error — used when embedded inside AdvancedMode */
  hideActions?: boolean;
  /** When provided, shows a "Browse S3" button that calls this to open the S3 picker */
  onBrowseS3?: () => void;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const OUTPUT_FORMATS = ['HLS', 'DASH', 'MP4', 'WEBM', 'MKV', 'MP3', 'AAC', 'FLAC', 'OGG'];
const RESOLUTIONS = [
  { value: '4K', label: '4K (2160p)' },
  { value: '1080p', label: '1080p' },
  { value: '720p', label: '720p' },
  { value: '480p', label: '480p' },
  { value: '360p', label: '360p' },
  { value: 'Original', label: 'Original' },
];

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatBytes(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function SimpleMode({ formState, onFormChange, onSubmit, onCancel, canSubmit, submitLoading, submitError, hideActions, onBrowseS3 }: SimpleModeProps) {
  const { sourceMode, sourceUrl, localFile, uploadProgress, outputFormat, resolution, bitrate, enableAdaptive, s3PickedFile } = formState;
  const isAdaptiveFormat = outputFormat === 'HLS' || outputFormat === 'DASH';

  return (
    <div className="space-y-4">
      {/* Source File Section */}
      <div>
        <div className="flex items-center justify-between mb-1.5">
          <label className="text-[11px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wide">Source</label>
          <div className="flex items-center gap-1 bg-[rgba(0,0,0,0.05)] rounded-lg p-0.5">
            <button onClick={() => onFormChange({ sourceMode: 'url', localFile: null, s3PickedFile: null })}
              className={`px-3 py-1 rounded-md text-[11px] font-bold transition-all ${sourceMode === 'url' && !s3PickedFile ? 'bg-white shadow text-[rgba(0,0,0,0.8)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
              Enter URL
            </button>
            <button onClick={() => onFormChange({ sourceMode: 'upload', sourceUrl: '', s3PickedFile: null })}
              className={`flex items-center gap-1 px-3 py-1 rounded-md text-[11px] font-bold transition-all ${sourceMode === 'upload' ? 'bg-white shadow text-[rgba(0,0,0,0.8)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
              <Icon icon="mdi:upload" className="text-sm" />
              Upload File
            </button>
            {onBrowseS3 && (
              <button onClick={onBrowseS3}
                className={`flex items-center gap-1 px-3 py-1 rounded-md text-[11px] font-bold transition-all ${s3PickedFile ? 'bg-white shadow text-[rgba(0,0,0,0.8)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
                <Icon icon="mdi:bucket-outline" className="text-sm" />
                Browse S3
              </button>
            )}
          </div>
        </div>

        {s3PickedFile ? (
          <div className="flex items-center gap-3 px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl bg-[rgba(0,0,0,0.02)]">
            <div className="w-8 h-8 bg-[rgba(0,0,0,0.05)] rounded-lg flex items-center justify-center flex-shrink-0">
              <Icon icon="mdi:file-video-outline" className="text-[rgba(0,0,0,0.4)] text-base" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate">{s3PickedFile.name}</p>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{s3PickedFile.formattedSize} · {s3PickedFile.bucket}</p>
            </div>
            <button onClick={() => onFormChange({ sourceUrl: '', s3PickedFile: null })}
              className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.06)] transition-colors flex-shrink-0" title="Clear">
              <Icon icon="mdi:close" className="text-sm text-[rgba(0,0,0,0.4)]" />
            </button>
          </div>
        ) : sourceMode === 'url' ? (
          <input value={sourceUrl} onChange={e => onFormChange({ sourceUrl: e.target.value })}
            placeholder="s3://bucket/video.mp4 or https://cdn.example.com/input.mov"
            className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-pink-400 focus:border-pink-400 outline-none placeholder-[rgba(0,0,0,0.3)]" />
        ) : (
          <label className={`flex flex-col items-center justify-center gap-2 w-full border-2 border-dashed rounded-xl cursor-pointer transition-colors px-4 py-6 ${
            localFile ? 'border-pink-300 bg-pink-50' : 'border-[rgba(0,0,0,0.12)] hover:border-pink-300 hover:bg-pink-50/40'
          }`}>
            <input type="file" accept="video/*,audio/*,.mkv,.avi,.mov,.mp4,.webm,.m4v,.flv"
              className="hidden" onChange={e => onFormChange({ localFile: e.target.files?.[0] ?? null })} />
            {localFile ? (
              <div className="flex items-center gap-3 w-full">
                <div className="w-10 h-10 bg-pink-100 rounded-xl flex items-center justify-center flex-shrink-0">
                  <Icon icon="mdi:file-video-outline" className="text-pink-500 text-xl" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] truncate">{localFile.name}</p>
                  <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{formatBytes(localFile.size)}</p>
                </div>
                <button type="button" onClick={e => { e.preventDefault(); onFormChange({ localFile: null }); }}
                  className="p-1.5 rounded-lg hover:bg-pink-100 transition-colors flex-shrink-0">
                  <Icon icon="mdi:close" className="text-sm text-[rgba(0,0,0,0.4)]" />
                </button>
              </div>
            ) : (
              <>
                <Icon icon="mdi:cloud-upload-outline" className="text-3xl text-[rgba(0,0,0,0.2)]" />
                <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.55)]">Click to browse or drag & drop</p>
                <p className="text-[11px] text-[rgba(0,0,0,0.35)]">MP4, MOV, AVI, MKV, WEBM, and more</p>
              </>
            )}
            {uploadProgress === 'uploading' && (
              <div className="w-full mt-1">
                <div className="h-1.5 bg-pink-100 rounded-full overflow-hidden">
                  <div className="h-full bg-pink-500 rounded-full animate-pulse" style={{ width: '60%' }} />
                </div>
                <p className="text-[11px] text-pink-600 font-semibold mt-1 text-center">Uploading…</p>
              </div>
            )}
          </label>
        )}
      </div>

      {/* Output Format + Resolution + Bitrate */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div>
          <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Output Format</label>
          <select value={outputFormat} onChange={e => onFormChange({ outputFormat: e.target.value })}
            className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-pink-400 outline-none bg-white">
            {OUTPUT_FORMATS.map(fmt => <option key={fmt} value={fmt}>{fmt}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Resolution</label>
          <select value={resolution} onChange={e => onFormChange({ resolution: e.target.value })}
            className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-pink-400 outline-none bg-white">
            {RESOLUTIONS.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Bitrate</label>
          <input value={bitrate} onChange={e => onFormChange({ bitrate: e.target.value })}
            placeholder="e.g. 5000k or 8M"
            className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-pink-400 focus:border-pink-400 outline-none placeholder-[rgba(0,0,0,0.3)]" />
        </div>
      </div>

      {/* Adaptive bitrate toggle (for HLS/DASH) */}
      {isAdaptiveFormat && (
        <div className="flex items-center gap-3">
          <button onClick={() => onFormChange({ enableAdaptive: !enableAdaptive })}
            className={`relative w-9 h-5 rounded-full transition-colors ${enableAdaptive ? 'bg-pink-500' : 'bg-[rgba(0,0,0,0.15)]'}`}>
            <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${enableAdaptive ? 'translate-x-4' : 'translate-x-0.5'}`} />
          </button>
          <div>
            <span className="text-[12px] text-[rgba(0,0,0,0.65)]">
              Generate adaptive bitrate ({outputFormat} multi-variant)
            </span>
            {enableAdaptive && (
              <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-0.5">
                Encodes 3 quality levels: 1080p · 720p · 480p — player auto-switches by bandwidth
              </p>
            )}
          </div>
        </div>
      )}

      {/* Error */}
      {!hideActions && submitError && (
        <p className="text-[12px] text-red-600 bg-red-50 rounded-xl px-3 py-2">{submitError}</p>
      )}

      {/* Actions */}
      {!hideActions && (
        <div className="flex items-center gap-3 pt-1">
          <button onClick={onSubmit} disabled={!canSubmit || submitLoading}
            className="flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-pink-500 to-rose-600 text-white text-[12px] font-bold rounded-xl shadow disabled:opacity-50 transition-all hover:scale-[1.02]">
            {submitLoading ? <Icon icon="mdi:loading" className="animate-spin" /> : <Icon icon="mdi:play" />}
            {submitLoading ? (uploadProgress === 'uploading' ? 'Uploading…' : 'Submitting…') : (sourceMode === 'upload' && localFile ? 'Upload & Transcode' : 'Submit Job')}
          </button>
          <button onClick={onCancel}
            className="px-4 py-2.5 text-[12px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors">Cancel</button>
        </div>
      )}
    </div>
  );
}
