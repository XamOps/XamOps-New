import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface AudioConfigProps {
  audioBitrate: string;
  audioSampleRate: number | null;
  audioChannels: number | null;
  isAudioOnlyFormat: boolean;
  onBitrateChange: (bitrate: string) => void;
  onSampleRateChange: (rate: number | null) => void;
  onChannelsChange: (channels: number | null) => void;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const SAMPLE_RATES = [
  { value: 44100, label: '44.1 kHz' },
  { value: 48000, label: '48 kHz' },
  { value: 96000, label: '96 kHz' },
];

const CHANNEL_OPTIONS = [
  { value: 1, label: '1 — Mono' },
  { value: 2, label: '2 — Stereo' },
];

const BITRATE_PRESETS = ['64k', '128k', '192k', '256k', '320k'];

// ─── Component ────────────────────────────────────────────────────────────────

export function AudioConfig({
  audioBitrate, audioSampleRate, audioChannels, isAudioOnlyFormat,
  onBitrateChange, onSampleRateChange, onChannelsChange,
}: AudioConfigProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  if (!isAudioOnlyFormat) return null;

  return (
    <div className="space-y-3">
      {/* Info banner */}
      <div className="flex items-center gap-2 text-[11px] text-blue-600 bg-blue-50 rounded-lg px-2.5 py-1.5">
        <Icon icon="mdi:music-note" className="text-sm flex-shrink-0" />
        Audio-only output detected — configure audio parameters below.
      </div>

      {/* Audio bitrate */}
      <div>
        <label className={`block text-[11px] font-semibold mb-1.5 uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>
          Audio Bitrate
        </label>
        <div className="flex items-center gap-2">
          <input type="text" value={audioBitrate} onChange={(e) => onBitrateChange(e.target.value)}
            placeholder="128k"
            className={`w-24 px-2.5 py-1.5 border rounded-lg text-[12px] focus:ring-2 focus:ring-blue-400 outline-none ${
              dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white placeholder-[rgba(255,255,255,0.25)]' : 'border-[rgba(0,0,0,0.12)] placeholder-[rgba(0,0,0,0.3)]'
            }`} />
          {/* Quick preset buttons */}
          <div className="flex items-center gap-1">
            {BITRATE_PRESETS.map((preset) => (
              <button key={preset} type="button" onClick={() => onBitrateChange(preset)}
                className={`px-2 py-1 rounded-md text-[10px] font-semibold transition-all ${
                  audioBitrate === preset
                    ? 'bg-blue-100 text-blue-700 ring-1 ring-blue-200'
                    : dark ? 'bg-[rgba(255,255,255,0.04)] text-[rgba(255,255,255,0.45)] hover:bg-[rgba(255,255,255,0.08)]' : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] hover:bg-[rgba(0,0,0,0.08)]'
                }`}>{preset}</button>
            ))}
          </div>
        </div>
      </div>

      {/* Sample rate + Channels row */}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={`block text-[11px] font-semibold mb-1.5 uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>
            Sample Rate
          </label>
          <select value={audioSampleRate ?? ''} onChange={(e) => { const val = e.target.value ? parseInt(e.target.value, 10) : null; onSampleRateChange(val); }}
            className={`w-full px-2.5 py-1.5 border rounded-lg text-[12px] focus:ring-2 focus:ring-blue-400 outline-none ${
              dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white' : 'bg-white border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.8)]'
            }`}>
            <option value="">Auto</option>
            {SAMPLE_RATES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
          </select>
        </div>
        <div>
          <label className={`block text-[11px] font-semibold mb-1.5 uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>
            Channels
          </label>
          <select value={audioChannels ?? ''} onChange={(e) => { const val = e.target.value ? parseInt(e.target.value, 10) : null; onChannelsChange(val); }}
            className={`w-full px-2.5 py-1.5 border rounded-lg text-[12px] focus:ring-2 focus:ring-blue-400 outline-none ${
              dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white' : 'bg-white border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.8)]'
            }`}>
            <option value="">Auto</option>
            {CHANNEL_OPTIONS.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </select>
        </div>
      </div>
    </div>
  );
}
