import { Icon } from '@iconify/react';
import type { FileProbeResult } from '../../hooks/useFileProbe';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface FileProbePanelProps {
  probeResult: FileProbeResult | null;
  probing: boolean;
  probeError: string | null;
  onRetry?: () => void;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatBitrate(bps: number | null): string {
  if (!bps) return '—';
  if (bps >= 1_000_000) return `${(bps / 1_000_000).toFixed(1)} Mbps`;
  if (bps >= 1_000) return `${(bps / 1_000).toFixed(0)} kbps`;
  return `${bps} bps`;
}

function formatDuration(seconds: number | null): string {
  if (!seconds) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function formatBytes(bytes: number | null): string {
  if (!bytes || bytes === 0) return '—';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function FileProbePanel({ probeResult, probing, probeError, onRetry }: FileProbePanelProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  if (probing) {
    return (
      <div className={`border rounded-xl p-3 ${dark ? 'border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.01)]' : 'border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.01)]'}`}>
        <div className="flex items-center gap-2 mb-2.5">
          <Icon icon="mdi:file-search-outline" className="text-blue-500 text-sm" />
          <span className={`text-[11px] font-bold uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.6)]'}`}>Source File Info</span>
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-5 gap-x-4 gap-y-2">
          {Array.from({ length: 10 }).map((_, i) => (
            <div key={i} className="flex items-start gap-1.5">
              <div className={`w-3 h-3 mt-0.5 rounded animate-pulse flex-shrink-0 ${dark ? 'bg-[rgba(255,255,255,0.06)]' : 'bg-[rgba(0,0,0,0.06)]'}`} />
              <div className="flex-1">
                <div className={`h-2 w-12 rounded animate-pulse mb-1 ${dark ? 'bg-[rgba(255,255,255,0.06)]' : 'bg-[rgba(0,0,0,0.06)]'}`} />
                <div className={`h-3 w-16 rounded animate-pulse ${dark ? 'bg-[rgba(255,255,255,0.08)]' : 'bg-[rgba(0,0,0,0.08)]'}`} />
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (probeError) {
    return (
      <div className="flex items-center gap-2 px-3 py-2.5 bg-amber-50 border border-amber-100 rounded-xl">
        <Icon icon="mdi:alert-outline" className="text-amber-500 text-sm flex-shrink-0" />
        <span className="text-[11px] text-amber-700 font-medium flex-1">Probe failed: {probeError}</span>
        {onRetry && (
          <button type="button" onClick={onRetry}
            className="text-[10px] font-semibold text-amber-600 hover:text-amber-800 px-2 py-1 rounded-lg hover:bg-amber-100 transition-colors flex-shrink-0">
            Retry
          </button>
        )}
      </div>
    );
  }

  if (!probeResult) return null;

  const metadata = [
    { label: 'Video Codec', value: probeResult.videoCodec ?? '—', icon: 'mdi:film-outline' },
    { label: 'Audio Codec', value: probeResult.audioCodec ?? '—', icon: 'mdi:music-note-outline' },
    { label: 'Resolution', value: probeResult.resolution ?? '—', icon: 'mdi:resize' },
    { label: 'Video Bitrate', value: formatBitrate(probeResult.videoBitrate), icon: 'mdi:speedometer' },
    { label: 'Frame Rate', value: probeResult.frameRate ? `${probeResult.frameRate} fps` : '—', icon: 'mdi:camera-burst' },
    { label: 'Audio Bitrate', value: formatBitrate(probeResult.audioBitrate), icon: 'mdi:volume-high' },
    { label: 'Sample Rate', value: probeResult.audioSampleRate ? `${probeResult.audioSampleRate} Hz` : '—', icon: 'mdi:waveform' },
    { label: 'Channels', value: probeResult.audioChannels ? `${probeResult.audioChannels}ch` : '—', icon: 'mdi:surround-sound' },
    { label: 'Duration', value: formatDuration(probeResult.durationSeconds), icon: 'mdi:clock-outline' },
    { label: 'File Size', value: formatBytes(probeResult.fileSize), icon: 'mdi:file-outline' },
  ];

  return (
    <div className={`border rounded-xl p-3 ${dark ? 'border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.01)]' : 'border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.01)]'}`}>
      <div className="flex items-center gap-2 mb-2.5">
        <Icon icon="mdi:file-search-outline" className="text-blue-500 text-sm" />
        <span className={`text-[11px] font-bold uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.6)]'}`}>Source File Info</span>
        {probeResult.format && (
          <span className={`ml-auto text-[10px] font-mono px-1.5 py-0.5 rounded ${dark ? 'text-[rgba(255,255,255,0.4)] bg-[rgba(255,255,255,0.04)]' : 'text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)]'}`}>
            {probeResult.format}
          </span>
        )}
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-5 gap-x-4 gap-y-2">
        {metadata.map((item) => (
          <div key={item.label} className="flex items-start gap-1.5">
            <Icon icon={item.icon} className={`text-xs mt-0.5 flex-shrink-0 ${dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.3)]'}`} />
            <div>
              <p className={`text-[9px] uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>{item.label}</p>
              <p className={`text-[11px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.75)]' : 'text-[rgba(0,0,0,0.75)]'}`}>{item.value}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
