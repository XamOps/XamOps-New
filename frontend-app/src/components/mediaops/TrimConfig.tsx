import { Icon } from '@iconify/react';
import { formatTimeInput } from '../../utils/timeFormat';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface TrimConfigProps {
  startTime: string;
  endTime: string;
  onStartTimeChange: (value: string) => void;
  onEndTimeChange: (value: string) => void;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function TrimConfig({ startTime, endTime, onStartTimeChange, onEndTimeChange }: TrimConfigProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const handleStartChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onStartTimeChange(formatTimeInput(e.target.value));
  };

  const handleEndChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onEndTimeChange(formatTimeInput(e.target.value));
  };

  const hasBothTimes = startTime.length > 0 && endTime.length > 0;

  return (
    <div className="space-y-3">
      {/* Time inputs */}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={`block text-[10px] font-semibold uppercase tracking-wide mb-1 ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>
            Start Time
          </label>
          <div className="relative">
            <Icon
              icon="mdi:play-outline"
              className={`absolute left-2.5 top-1/2 -translate-y-1/2 text-sm ${dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.3)]'}`}
            />
            <input
              value={startTime}
              onChange={handleStartChange}
              placeholder="HH:MM:SS"
              className={`w-full pl-8 pr-3 py-2 border rounded-xl text-[12px] font-mono focus:ring-2 focus:ring-pink-400 focus:border-pink-400 outline-none ${
                dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white placeholder-[rgba(255,255,255,0.25)]' : 'border-[rgba(0,0,0,0.12)] placeholder-[rgba(0,0,0,0.3)]'
              }`}
            />
          </div>
        </div>
        <div>
          <label className={`block text-[10px] font-semibold uppercase tracking-wide mb-1 ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>
            End Time
          </label>
          <div className="relative">
            <Icon
              icon="mdi:stop-outline"
              className={`absolute left-2.5 top-1/2 -translate-y-1/2 text-sm ${dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.3)]'}`}
            />
            <input
              value={endTime}
              onChange={handleEndChange}
              placeholder="HH:MM:SS"
              className={`w-full pl-8 pr-3 py-2 border rounded-xl text-[12px] font-mono focus:ring-2 focus:ring-pink-400 focus:border-pink-400 outline-none ${
                dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white placeholder-[rgba(255,255,255,0.25)]' : 'border-[rgba(0,0,0,0.12)] placeholder-[rgba(0,0,0,0.3)]'
              }`}
            />
          </div>
        </div>
      </div>

      {/* Summary */}
      {hasBothTimes && (
        <div className="flex items-center gap-2 px-3 py-2 bg-pink-50 rounded-lg">
          <Icon icon="mdi:content-cut" className="text-pink-500 text-sm flex-shrink-0" />
          <p className="text-[11px] text-pink-700 font-medium">
            Trims from <span className="font-bold font-mono">{startTime}</span> to{' '}
            <span className="font-bold font-mono">{endTime}</span>
          </p>
        </div>
      )}
    </div>
  );
}
