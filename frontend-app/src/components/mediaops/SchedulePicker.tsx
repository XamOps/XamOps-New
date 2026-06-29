import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

interface SchedulePickerProps {
  value: string | null; // ISO datetime string or null
  onChange: (scheduledAt: string | null) => void;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Converts ISO datetime to the format expected by datetime-local input (YYYY-MM-DDTHH:mm).
 */
function toDatetimeLocalValue(iso: string | null): string {
  if (!iso) return '';
  try {
    const date = new Date(iso);
    // Format as YYYY-MM-DDTHH:mm for datetime-local input
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    return `${year}-${month}-${day}T${hours}:${minutes}`;
  } catch {
    return '';
  }
}

/**
 * Converts datetime-local input value to ISO string.
 */
function fromDatetimeLocalValue(localValue: string): string {
  if (!localValue) return '';
  return new Date(localValue).toISOString();
}

/**
 * Formats an ISO datetime for display.
 */
function formatScheduleDisplay(iso: string): string {
  try {
    const date = new Date(iso);
    return date.toLocaleString(undefined, {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

// ─── Component ────────────────────────────────────────────────────────────────

export function SchedulePicker({ value, onChange }: SchedulePickerProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const hasSchedule = !!value;

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const localValue = e.target.value;
    if (!localValue) {
      onChange(null);
    } else {
      onChange(fromDatetimeLocalValue(localValue));
    }
  };

  const handleClear = () => {
    onChange(null);
  };

  // Minimum datetime = now (prevents selecting past times)
  const now = new Date();
  const minDatetime = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}T${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;

  return (
    <div className="space-y-2">
      {/* Date-time input */}
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <input
            type="datetime-local"
            value={toDatetimeLocalValue(value)}
            onChange={handleChange}
            min={minDatetime}
            className={`w-full px-3 py-2.5 border rounded-xl text-[13px] focus:ring-2 focus:ring-blue-500/25 focus:border-blue-500 outline-none ${
              dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white' : 'bg-white border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.8)]'
            }`}
          />
        </div>
        {hasSchedule && (
          <button
            type="button"
            onClick={handleClear}
            className={`flex items-center gap-1 px-3 py-2.5 text-[12px] font-semibold rounded-xl transition-colors border ${
              dark
                ? 'text-[rgba(255,255,255,0.5)] hover:text-red-400 hover:bg-red-900/30 border-[rgba(255,255,255,0.08)]'
                : 'text-[rgba(0,0,0,0.5)] hover:text-red-600 hover:bg-red-50 border-[rgba(0,0,0,0.08)]'
            }`}
          >
            <Icon icon="mdi:close" className="text-sm" />
            Clear
          </button>
        )}
      </div>

      {/* Status indicator */}
      <div className="flex items-center gap-1.5">
        {hasSchedule ? (
          <>
            <Icon icon="mdi:calendar-clock" className="text-blue-500 text-sm" />
            <span className={`text-[11px] ${dark ? 'text-[rgba(255,255,255,0.55)]' : 'text-[rgba(0,0,0,0.55)]'}`}>
              Scheduled for <span className="font-semibold text-blue-600">{formatScheduleDisplay(value!)}</span>
            </span>
          </>
        ) : (
          <>
            <Icon icon="mdi:play-circle-outline" className="text-green-500 text-sm" />
            <span className={`text-[11px] ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>Runs immediately</span>
          </>
        )}
      </div>
    </div>
  );
}
