import { useState } from 'react';
import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface ThumbnailConfigProps {
  enabled: boolean;
  intervalSeconds: number | null;
  timestamp: string | null;
  onEnabledChange: (enabled: boolean) => void;
  onIntervalChange: (interval: number | null) => void;
  onTimestampChange: (timestamp: string | null) => void;
}

type ThumbnailMode = 'interval' | 'timestamp';

// ─── Component ────────────────────────────────────────────────────────────────

export function ThumbnailConfig({
  enabled,
  intervalSeconds,
  timestamp,
  onEnabledChange,
  onIntervalChange,
  onTimestampChange,
}: ThumbnailConfigProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const [mode, setMode] = useState<ThumbnailMode>(timestamp ? 'timestamp' : 'interval');

  const handleModeChange = (newMode: ThumbnailMode) => {
    setMode(newMode);
    if (newMode === 'interval') {
      onTimestampChange(null);
      if (!intervalSeconds) onIntervalChange(10);
    } else {
      onIntervalChange(null);
      if (!timestamp) onTimestampChange('00:00:05');
    }
  };

  return (
    <div className="space-y-3">
      {/* Enable toggle */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={() => onEnabledChange(!enabled)}
          className={`relative w-9 h-5 rounded-full transition-colors ${enabled ? 'bg-pink-500' : (dark ? 'bg-[rgba(255,255,255,0.15)]' : 'bg-[rgba(0,0,0,0.15)]')}`}
          aria-label="Enable thumbnail generation"
        >
          <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${enabled ? 'translate-x-4' : 'translate-x-0.5'}`} />
        </button>
        <span className={`text-[12px] ${dark ? 'text-[rgba(255,255,255,0.65)]' : 'text-[rgba(0,0,0,0.65)]'}`}>
          Generate thumbnails from video
        </span>
      </div>

      {enabled && (
        <div className="space-y-3 pl-1">
          {/* Mode selection */}
          <div className={`flex items-center gap-1 rounded-lg p-0.5 w-fit ${dark ? 'bg-[rgba(255,255,255,0.04)]' : 'bg-[rgba(0,0,0,0.04)]'}`}>
            <button
              type="button"
              onClick={() => handleModeChange('interval')}
              className={`px-3 py-1.5 rounded-md text-[11px] font-bold transition-all ${
                mode === 'interval'
                  ? (dark ? 'bg-[#2a2a2a] shadow text-[rgba(255,255,255,0.88)]' : 'bg-white shadow text-[rgba(0,0,0,0.8)]')
                  : (dark ? 'text-[rgba(255,255,255,0.45)] hover:text-[rgba(255,255,255,0.65)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]')
              }`}
            >
              <Icon icon="mdi:timer-outline" className="inline mr-1 text-sm align-text-bottom" />
              Every N seconds
            </button>
            <button
              type="button"
              onClick={() => handleModeChange('timestamp')}
              className={`px-3 py-1.5 rounded-md text-[11px] font-bold transition-all ${
                mode === 'timestamp'
                  ? (dark ? 'bg-[#2a2a2a] shadow text-[rgba(255,255,255,0.88)]' : 'bg-white shadow text-[rgba(0,0,0,0.8)]')
                  : (dark ? 'text-[rgba(255,255,255,0.45)] hover:text-[rgba(255,255,255,0.65)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]')
              }`}
            >
              <Icon icon="mdi:image-outline" className="inline mr-1 text-sm align-text-bottom" />
              Single frame
            </button>
          </div>

          {/* Interval input */}
          {mode === 'interval' && (
            <div className="flex items-center gap-2">
              <label className={`text-[11px] ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>Extract every</label>
              <input
                type="number"
                min={1}
                value={intervalSeconds ?? ''}
                onChange={(e) => {
                  const val = parseInt(e.target.value, 10);
                  onIntervalChange(isNaN(val) || val < 1 ? null : val);
                }}
                className={`w-16 px-2 py-1.5 border rounded-lg text-[12px] text-center focus:ring-2 focus:ring-pink-400 outline-none ${
                  dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white' : 'border-[rgba(0,0,0,0.12)]'
                }`}
                placeholder="10"
              />
              <label className={`text-[11px] ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>seconds</label>
            </div>
          )}

          {/* Timestamp input */}
          {mode === 'timestamp' && (
            <div className="flex items-center gap-2">
              <label className={`text-[11px] ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>At timestamp</label>
              <input
                type="text"
                value={timestamp ?? ''}
                onChange={(e) => onTimestampChange(e.target.value || null)}
                placeholder="HH:MM:SS"
                className={`w-24 px-2 py-1.5 border rounded-lg text-[12px] text-center font-mono focus:ring-2 focus:ring-pink-400 outline-none ${
                  dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white' : 'border-[rgba(0,0,0,0.12)]'
                }`}
              />
              <span className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.35)]' : 'text-[rgba(0,0,0,0.35)]'}`}>e.g. 00:01:30</span>
            </div>
          )}

          {/* Info hint */}
          <p className={`text-[10px] flex items-center gap-1 ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>
            <Icon icon="mdi:information-outline" className="text-xs" />
            {mode === 'interval'
              ? 'Generates JPEG thumbnails at regular intervals throughout the video.'
              : 'Extracts a single poster frame at the specified timestamp.'}
          </p>
        </div>
      )}
    </div>
  );
}
