import { Icon } from '@iconify/react';
import type { S3PickedFile } from './S3PickerModal';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface SimpleModeFormState {
  sourceMode: 'url' | 'upload';
  sourceUrl: string;
  localFile: File | null;
  uploadProgress: 'idle' | 'uploading' | 'done';
  outputFormats: string[];
  resolutions: string[];
  bitrate: string;
  // x264 preset (fast | veryfast | superfast | ultrafast) — faster trades quality for speed
  encodingSpeed: string;
  enableAdaptive: boolean;
  generateAdaptiveBundle: boolean;
  priorityFormat: string | null;
  /** Sources chosen from the S3 browser. One set of jobs is created per entry. */
  s3PickedFiles?: S3PickedFile[];
}

// Encoding speed choices, from most careful to fastest. `impact` spells out what
// the user gives up — shown under the dropdown so the trade-off is never hidden.
const SPEED_OPTIONS: { value: string; label: string; time: string; impact: string; warn: boolean }[] = [
  {
    value: 'fast', label: 'Best quality', time: '≈2× slower than Balanced', warn: false,
    impact: 'Maximum detail retention at the target bitrate. Pick this for final, published content when encode time doesn’t matter.',
  },
  {
    value: 'veryfast', label: 'Balanced', time: 'recommended default', warn: false,
    impact: 'Near-best quality — the difference from Best is rarely visible without a side-by-side comparison. Good for almost everything.',
  },
  {
    value: 'superfast', label: 'Fast', time: '≈30–40% faster than Balanced', warn: true,
    impact: 'Slightly reduces quality: fine textures and fast-motion scenes (sports, grass, confetti, camera pans) look a bit softer or noisier. Screen recordings, slides and talking-head videos are mostly unaffected.',
  },
  {
    value: 'ultrafast', label: 'Fastest (draft)', time: '≈2× faster than Balanced', warn: true,
    impact: 'Noticeably lowers quality: blocky fast motion, banding in color gradients, softer detail overall. Use only for quick previews and drafts — not for content viewers will keep.',
  },
];

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
  { value: '4K', label: '4K' },
  { value: '1080p', label: '1080p' },
  { value: '720p', label: '720p' },
  { value: '480p', label: '480p' },
  { value: '360p', label: '360p' },
  { value: 'Original', label: 'Original' },
];

const AUDIO_ONLY_FORMATS = ['MP3', 'AAC', 'FLAC', 'OGG'];

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
  const { sourceMode, sourceUrl, localFile, uploadProgress, outputFormats, resolutions, bitrate, encodingSpeed, enableAdaptive, generateAdaptiveBundle, s3PickedFiles, priorityFormat } = formState;
  const pickedFiles = s3PickedFiles ?? [];

  const hasAdaptiveFormat = outputFormats.some(f => f === 'HLS' || f === 'DASH');
  // adaptiveActive = the adaptive toggle is meaningfully on (HLS/DASH selected AND toggle enabled)
  const adaptiveActive = enableAdaptive && hasAdaptiveFormat;
  const multiFormat = outputFormats.length > 1;
  const multiResolution = resolutions.length > 1;
  const willCreateMultiple = !adaptiveActive && (multiFormat || multiResolution);
  const adaptiveBundleFormats = (!adaptiveActive && multiResolution && generateAdaptiveBundle)
    ? outputFormats.filter(f => f === 'HLS' || f === 'DASH')
    : [];
  const baseJobs = willCreateMultiple ? outputFormats.length * resolutions.length : 1;
  const totalJobs = baseJobs + adaptiveBundleFormats.length;
  const isAllAudio = outputFormats.every(f => AUDIO_ONLY_FORMATS.includes(f));

  const toggleFormat = (fmt: string) => {
    const isSelected = outputFormats.includes(fmt);
    if (isSelected && outputFormats.length === 1) return;
    const next = isSelected ? outputFormats.filter(f => f !== fmt) : [...outputFormats, fmt];
    const updates: Partial<SimpleModeFormState> = { outputFormats: next };
    if (priorityFormat === fmt) updates.priorityFormat = null;
    onFormChange(updates);
  };

  const toggleResolution = (res: string) => {
    const isSelected = resolutions.includes(res);
    if (isSelected && resolutions.length === 1) return;
    const next = isSelected ? resolutions.filter(r => r !== res) : [...resolutions, res];
    onFormChange({ resolutions: next });
  };

  return (
    <div className="space-y-4">
      {/* Source File Section */}
      <div>
        <div className="flex items-center justify-between mb-1.5">
          <label className="text-[11px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wide">Source</label>
          <div className="flex items-center gap-1 bg-[rgba(0,0,0,0.05)] rounded-lg p-0.5">
            <button onClick={() => onFormChange({ sourceMode: 'url', localFile: null, s3PickedFiles: [] })}
              className={`px-3 py-1 rounded-md text-[11px] font-bold transition-all ${sourceMode === 'url' && pickedFiles.length === 0 ? 'bg-white shadow text-[rgba(0,0,0,0.8)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
              Enter URL
            </button>
            <button onClick={() => onFormChange({ sourceMode: 'upload', sourceUrl: '', s3PickedFiles: [] })}
              className={`flex items-center gap-1 px-3 py-1 rounded-md text-[11px] font-bold transition-all ${sourceMode === 'upload' ? 'bg-white shadow text-[rgba(0,0,0,0.8)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
              <Icon icon="mdi:upload" className="text-sm" />
              Upload File
            </button>
            {onBrowseS3 && (
              <button onClick={onBrowseS3}
                className={`flex items-center gap-1 px-3 py-1 rounded-md text-[11px] font-bold transition-all ${pickedFiles.length > 0 ? 'bg-white shadow text-[rgba(0,0,0,0.8)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
                <Icon icon="mdi:bucket-outline" className="text-sm" />
                Browse S3
              </button>
            )}
          </div>
        </div>

        {pickedFiles.length > 0 ? (
          <div className="space-y-1.5">
            {pickedFiles.map(file => (
              <div key={`${file.bucket}/${file.key}`}
                className="flex items-center gap-3 px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl bg-[rgba(0,0,0,0.02)]">
                <div className="w-8 h-8 bg-[rgba(0,0,0,0.05)] rounded-lg flex items-center justify-center flex-shrink-0">
                  <Icon icon="mdi:file-video-outline" className="text-[rgba(0,0,0,0.4)] text-base" />
                </div>
                <div className="flex-1 min-w-0">
                  {/* title carries the full key: these names are frequently wider than the row. */}
                  <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate" title={file.key}>{file.name}</p>
                  <p className="text-[11px] text-[rgba(0,0,0,0.4)] truncate" title={file.key}>
                    {file.formattedSize} · {file.bucket}/{file.key}
                  </p>
                </div>
                <button
                  onClick={() => onFormChange({ s3PickedFiles: pickedFiles.filter(f => f.key !== file.key || f.bucket !== file.bucket) })}
                  className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.06)] transition-colors flex-shrink-0" title="Remove">
                  <Icon icon="mdi:close" className="text-sm text-[rgba(0,0,0,0.4)]" />
                </button>
              </div>
            ))}
            {pickedFiles.length > 1 && (
              <p className="text-[11px] text-[rgba(0,0,0,0.45)] pt-0.5">
                <Icon icon="mdi:information-outline" className="inline text-[13px] mr-1 -mt-0.5" />
                Every selected format and resolution is applied to all {pickedFiles.length} videos.
              </p>
            )}
          </div>
        ) : sourceMode === 'url' ? (
          <input value={sourceUrl} onChange={e => onFormChange({ sourceUrl: e.target.value })}
            placeholder="s3://bucket/video.mp4 or https://cdn.example.com/input.mov"
            className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-blue-500/25 focus:border-blue-500 outline-none placeholder-[rgba(0,0,0,0.3)]" />
        ) : (
          <label className={`flex flex-col items-center justify-center gap-2 w-full border-2 border-dashed rounded-xl cursor-pointer transition-colors px-4 py-6 ${
            localFile ? 'border-blue-300 bg-blue-50' : 'border-[rgba(0,0,0,0.12)] hover:border-blue-300 hover:bg-blue-50/40'
          }`}>
            <input type="file" accept="video/*,audio/*,.mkv,.avi,.mov,.mp4,.webm,.m4v,.flv"
              className="hidden" onChange={e => onFormChange({ localFile: e.target.files?.[0] ?? null })} />
            {localFile ? (
              <div className="flex items-center gap-3 w-full">
                <div className="w-10 h-10 bg-blue-100 rounded-xl flex items-center justify-center flex-shrink-0">
                  <Icon icon="mdi:file-video-outline" className="text-blue-500 text-xl" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] truncate">{localFile.name}</p>
                  <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{formatBytes(localFile.size)}</p>
                </div>
                <button type="button" onClick={e => { e.preventDefault(); onFormChange({ localFile: null }); }}
                  className="p-1.5 rounded-lg hover:bg-blue-100 transition-colors flex-shrink-0">
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
                <div className="h-1.5 bg-blue-100 rounded-full overflow-hidden">
                  <div className="h-full bg-blue-500 rounded-full animate-pulse" style={{ width: '60%' }} />
                </div>
                <p className="text-[11px] text-blue-600 font-semibold mt-1 text-center">Uploading…</p>
              </div>
            )}
          </label>
        )}
      </div>

      {/* Output Format — multi-select chips */}
      <div>
        <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-2 uppercase tracking-wide">
          Output Format
          <span className="ml-1.5 normal-case font-normal text-[rgba(0,0,0,0.3)]">— select one or more</span>
        </label>
        <div className="flex flex-wrap gap-1.5">
          {OUTPUT_FORMATS.map(fmt => {
            const active = outputFormats.includes(fmt);
            return (
              <button key={fmt} type="button" onClick={() => toggleFormat(fmt)}
                className={`px-3 py-1.5 rounded-lg text-[12px] font-semibold border transition-all duration-150 active:scale-[0.96] ${
                  active
                    ? 'bg-blue-500 text-white border-blue-500 shadow-[0_2px_6px_-1px_rgba(37,99,235,0.4)]'
                    : 'bg-white text-[rgba(0,0,0,0.55)] border-[rgba(0,0,0,0.12)] hover:border-blue-300 hover:text-blue-600 hover:bg-blue-50/40'
                }`}>
                {fmt}
              </button>
            );
          })}
        </div>
      </div>

      {/* Resolution — hidden when adaptive is on WITH an adaptive format selected, or audio-only */}
      {!isAllAudio && !(enableAdaptive && hasAdaptiveFormat) && (
        <div>
          <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-2 uppercase tracking-wide">
            Resolution
            <span className="ml-1.5 normal-case font-normal text-[rgba(0,0,0,0.3)]">— select one or more</span>
          </label>
          <div className="flex flex-wrap gap-1.5">
            {RESOLUTIONS.map(r => {
              const active = resolutions.includes(r.value);
              return (
                <button key={r.value} type="button" onClick={() => toggleResolution(r.value)}
                  className={`px-3 py-1.5 rounded-lg text-[12px] font-semibold border transition-all duration-150 active:scale-[0.96] ${
                    active
                      ? 'bg-blue-500 text-white border-blue-500 shadow-[0_2px_6px_-1px_rgba(37,99,235,0.4)]'
                      : 'bg-white text-[rgba(0,0,0,0.55)] border-[rgba(0,0,0,0.12)] hover:border-blue-300 hover:text-blue-600 hover:bg-blue-50/40'
                  }`}>
                  {r.label}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {/* Bitrate */}
      <div>
        <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Bitrate</label>
        <input value={bitrate} onChange={e => onFormChange({ bitrate: e.target.value })}
          placeholder="e.g. 5000k or 8M"
          className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-blue-500/25 focus:border-blue-500 outline-none placeholder-[rgba(0,0,0,0.3)]" />
      </div>

      {/* Encoding speed — quality/speed trade-off, impact spelled out below */}
      <div>
        <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Encoding speed</label>
        <select value={encodingSpeed} onChange={e => onFormChange({ encodingSpeed: e.target.value })}
          className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] bg-white focus:ring-2 focus:ring-blue-500/25 focus:border-blue-500 outline-none">
          {SPEED_OPTIONS.map(o => (
            <option key={o.value} value={o.value}>{o.label} — {o.time}</option>
          ))}
        </select>
        {(() => {
          const sel = SPEED_OPTIONS.find(o => o.value === encodingSpeed) ?? SPEED_OPTIONS[1];
          return (
            <div className={`mt-2 flex items-start gap-2 px-3 py-2 rounded-lg border text-[11px] leading-relaxed ${
              sel.warn
                ? 'bg-amber-50 border-amber-200 text-amber-800'
                : 'bg-[rgba(0,0,0,0.03)] border-[rgba(0,0,0,0.07)] text-[rgba(0,0,0,0.55)]'
            }`}>
              <Icon icon={sel.warn ? 'mdi:alert-outline' : 'mdi:information-outline'}
                className={`text-sm mt-0.5 flex-shrink-0 ${sel.warn ? 'text-amber-500' : 'text-[rgba(0,0,0,0.35)]'}`} />
              <span>
                {sel.warn && <strong>Affects quality. </strong>}
                {sel.impact}
              </span>
            </div>
          );
        })()}
      </div>

      {/* Adaptive bitrate toggle (only relevant for HLS/DASH) */}
      {hasAdaptiveFormat && (
        <div className="flex items-center gap-3">
          <button type="button" onClick={() => onFormChange({ enableAdaptive: !enableAdaptive })}
            className={`relative w-9 h-5 rounded-full transition-colors flex-shrink-0 ${enableAdaptive ? 'bg-blue-500' : 'bg-[rgba(0,0,0,0.15)]'}`}>
            <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${enableAdaptive ? 'translate-x-4' : 'translate-x-0.5'}`} />
          </button>
          <div>
            <span className="text-[12px] text-[rgba(0,0,0,0.65)]">
              Generate adaptive bitrate ({outputFormats.filter(f => f === 'HLS' || f === 'DASH').join('/')} multi-variant)
            </span>
            {enableAdaptive && (
              <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-0.5">
                Always encodes 3 fixed levels: <strong>1080p · 720p · 480p</strong> — resolution selector is not needed. Player auto-switches by bandwidth.
              </p>
            )}
          </div>
        </div>
      )}

      {/* Adaptive bundle toggle — visible when multiple resolutions + HLS/DASH + adaptive OFF */}
      {hasAdaptiveFormat && !adaptiveActive && multiResolution && (
        <div className="flex items-start gap-3">
          <button type="button" onClick={() => onFormChange({ generateAdaptiveBundle: !generateAdaptiveBundle })}
            className={`relative w-9 h-5 rounded-full transition-colors flex-shrink-0 mt-0.5 ${generateAdaptiveBundle ? 'bg-violet-500' : 'bg-[rgba(0,0,0,0.15)]'}`}>
            <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${generateAdaptiveBundle ? 'translate-x-4' : 'translate-x-0.5'}`} />
          </button>
          <div>
            <span className="text-[12px] text-[rgba(0,0,0,0.65)]">
              Also generate adaptive bundle per streaming format
            </span>
            <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-0.5">
              In addition to individual files, creates a combined <strong>master.m3u8 / MPD</strong> for each
              {outputFormats.filter(f => f === 'HLS' || f === 'DASH').map(f => ` ${f}`).join(' &')} format
              — player auto-switches quality. WEBM / MKV / MP4 have no adaptive format.
            </p>
          </div>
        </div>
      )}

      {/* Priority format selector (when multiple formats selected and adaptive off) */}
      {multiFormat && !adaptiveActive && (
        <div className="p-3 bg-amber-50 border border-amber-100 rounded-xl space-y-2">
          <label className="block text-[11px] font-semibold text-amber-700 uppercase tracking-wide">
            Priority Format
            <span className="ml-1.5 normal-case font-normal text-amber-500">— which to process first</span>
          </label>
          <div className="flex flex-wrap gap-1.5">
            {outputFormats.map(fmt => (
              <button key={fmt} type="button"
                onClick={() => onFormChange({ priorityFormat: priorityFormat === fmt ? null : fmt })}
                className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-semibold border transition-all duration-150 active:scale-[0.96] ${
                  priorityFormat === fmt
                    ? 'bg-amber-500 text-white border-amber-500 shadow-sm'
                    : 'bg-white text-[rgba(0,0,0,0.55)] border-amber-200 hover:border-amber-400 hover:text-amber-600'
                }`}>
                {priorityFormat === fmt && <Icon icon="mdi:lightning-bolt" className="text-xs" />}
                {fmt}
              </button>
            ))}
          </div>
          <p className="text-[10px] text-amber-600">
            {priorityFormat
              ? `${priorityFormat} jobs will be submitted with HIGH priority and processed first`
              : 'No priority set — all jobs submitted with NORMAL priority'}
          </p>
        </div>
      )}

      {/* Job count summary (when creating multiple jobs) */}
      {willCreateMultiple && (
        <div className="flex items-start gap-2.5 p-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl">
          <Icon icon="mdi:information-outline" className="text-[rgba(0,0,0,0.4)] text-base mt-0.5 flex-shrink-0" />
          <div>
            <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.75)]">
              Will create {totalJobs} transcode job{totalJobs > 1 ? 's' : ''}
            </p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">
              {outputFormats.flatMap(fmt =>
                (isAllAudio ? ['—'] : resolutions).map(res => `${fmt}${isAllAudio ? '' : ` @ ${res}`}`)
              ).join(', ')}
              {adaptiveBundleFormats.length > 0 && (
                <span className="text-violet-500 font-semibold">
                  {' + '}
                  {adaptiveBundleFormats.map(f => `${f} Adaptive Bundle`).join(', ')}
                </span>
              )}
            </p>
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
            className="flex items-center gap-2 px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-[12px] font-bold rounded-xl shadow-sm disabled:opacity-50 transition-colors">
            {submitLoading ? <Icon icon="mdi:loading" className="animate-spin" /> : <Icon icon="mdi:play" />}
            {submitLoading
              ? (uploadProgress === 'uploading' ? 'Uploading…' : 'Submitting…')
              : (sourceMode === 'upload' && localFile ? 'Upload & Transcode' : totalJobs > 1 ? `Submit ${totalJobs} Jobs` : 'Submit Job')}
          </button>
          <button onClick={onCancel}
            className="px-4 py-2.5 text-[12px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors">Cancel</button>
        </div>
      )}
    </div>
  );
}
