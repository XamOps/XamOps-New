import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export type SubtitleMode = 'BURN_IN' | 'SIDECAR';

export interface SubtitleConfigProps {
  subtitleFile: File | null;
  subtitleMode: SubtitleMode | null;
  onFileChange: (file: File | null) => void;
  onModeChange: (mode: SubtitleMode | null) => void;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatBytes(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function SubtitleConfig({ subtitleFile, subtitleMode, onFileChange, onModeChange }: SubtitleConfigProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  return (
    <div className="space-y-3">
      {/* File drop zone */}
      <label
        className={`flex flex-col items-center justify-center gap-2 w-full border-2 border-dashed rounded-xl cursor-pointer transition-colors px-4 py-4 ${
          subtitleFile
            ? 'border-blue-300 bg-blue-50'
            : dark ? 'border-[rgba(255,255,255,0.12)] hover:border-blue-300 hover:bg-blue-50/10' : 'border-[rgba(0,0,0,0.12)] hover:border-blue-300 hover:bg-blue-50/40'
        }`}
      >
        <input
          type="file"
          accept=".srt,.vtt"
          className="hidden"
          onChange={(e) => onFileChange(e.target.files?.[0] ?? null)}
        />
        {subtitleFile ? (
          <div className="flex items-center gap-3 w-full">
            <div className="w-9 h-9 bg-blue-100 rounded-lg flex items-center justify-center flex-shrink-0">
              <Icon icon="mdi:subtitles-outline" className="text-blue-500 text-lg" />
            </div>
            <div className="flex-1 min-w-0">
              <p className={`text-[12px] font-bold truncate ${dark ? 'text-[rgba(255,255,255,0.88)]' : 'text-[rgba(0,0,0,0.88)]'}`}>{subtitleFile.name}</p>
              <p className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>{formatBytes(subtitleFile.size)}</p>
            </div>
            <button
              type="button"
              onClick={(e) => {
                e.preventDefault();
                onFileChange(null);
                onModeChange(null);
              }}
              className="p-1.5 rounded-lg hover:bg-blue-100 transition-colors flex-shrink-0"
            >
              <Icon icon="mdi:close" className={`text-sm ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`} />
            </button>
          </div>
        ) : (
          <>
            <Icon icon="mdi:file-upload-outline" className={`text-2xl ${dark ? 'text-[rgba(255,255,255,0.2)]' : 'text-[rgba(0,0,0,0.2)]'}`} />
            <p className={`text-[12px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.55)]' : 'text-[rgba(0,0,0,0.55)]'}`}>Upload subtitle file</p>
            <p className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.35)]' : 'text-[rgba(0,0,0,0.35)]'}`}>SRT or VTT formats only</p>
          </>
        )}
      </label>

      {/* Mode selection — only visible when file is selected */}
      {subtitleFile && (
        <div className="space-y-2">
          <p className={`text-[10px] font-semibold uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>Subtitle Mode</p>
          <div className="flex flex-col gap-1.5">
            <label className="flex items-center gap-2.5 cursor-pointer group">
              <input
                type="radio"
                name="subtitleMode"
                checked={subtitleMode === 'BURN_IN'}
                onChange={() => onModeChange('BURN_IN')}
                className={`w-3.5 h-3.5 text-blue-500 focus:ring-blue-400 ${dark ? 'border-[rgba(255,255,255,0.2)]' : 'border-[rgba(0,0,0,0.2)]'}`}
              />
              <div>
                <span className={`text-[12px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.75)] group-hover:text-[rgba(255,255,255,0.9)]' : 'text-[rgba(0,0,0,0.75)] group-hover:text-[rgba(0,0,0,0.9)]'}`}>
                  Burn-in (embed in video)
                </span>
                <p className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>Text rendered directly onto video frames</p>
              </div>
            </label>
            <label className="flex items-center gap-2.5 cursor-pointer group">
              <input
                type="radio"
                name="subtitleMode"
                checked={subtitleMode === 'SIDECAR'}
                onChange={() => onModeChange('SIDECAR')}
                className={`w-3.5 h-3.5 text-blue-500 focus:ring-blue-400 ${dark ? 'border-[rgba(255,255,255,0.2)]' : 'border-[rgba(0,0,0,0.2)]'}`}
              />
              <div>
                <span className={`text-[12px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.75)] group-hover:text-[rgba(255,255,255,0.9)]' : 'text-[rgba(0,0,0,0.75)] group-hover:text-[rgba(0,0,0,0.9)]'}`}>
                  Sidecar (separate file)
                </span>
                <p className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>Subtitle file kept alongside output</p>
              </div>
            </label>
          </div>
        </div>
      )}
    </div>
  );
}
