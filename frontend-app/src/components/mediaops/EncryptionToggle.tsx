import { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface EncryptionToggleProps {
  enabled: boolean;
  outputFormat: string;
  onChange: (enabled: boolean) => void;
}

// Only HLS supports AES-128 segment encryption. Every other output format
// (MP4, MOV, WEBM, DASH, and the audio-only formats) has no equivalent.
const ENCRYPTION_SUPPORTED_FORMATS = ['HLS'];

// ─── Component ────────────────────────────────────────────────────────────────

export function EncryptionToggle({ enabled, outputFormat, onChange }: EncryptionToggleProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const format = (outputFormat || '').toUpperCase();
  const isHLS = ENCRYPTION_SUPPORTED_FORMATS.includes(format);

  // Auto-disable when the selected format doesn't support encryption. Without this,
  // switching from HLS (encryption on) to e.g. MP4 would leave encryptionEnabled=true
  // in the form state and submit it to the backend even though it can't be applied.
  useEffect(() => {
    if (!isHLS && enabled) onChange(false);
  }, [isHLS, enabled, onChange]);

  return (
    <div className="space-y-2">
      {/* Toggle row */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={() => isHLS && onChange(!enabled)}
          disabled={!isHLS}
          className={`relative w-9 h-5 rounded-full transition-colors ${
            !isHLS
              ? `${dark ? 'bg-[rgba(255,255,255,0.08)]' : 'bg-[rgba(0,0,0,0.08)]'} cursor-not-allowed`
              : enabled
                ? 'bg-blue-500'
                : dark ? 'bg-[rgba(255,255,255,0.15)]' : 'bg-[rgba(0,0,0,0.15)]'
          }`}
          aria-label="Enable AES-128 encryption"
        >
          <span
            className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${
              enabled && isHLS ? 'translate-x-4' : 'translate-x-0.5'
            }`}
          />
        </button>
        <div className="flex items-center gap-1.5">
          <Icon
            icon={isHLS ? 'mdi:lock-outline' : 'mdi:lock-off-outline'}
            className={`text-sm ${isHLS ? (dark ? 'text-[rgba(255,255,255,0.55)]' : 'text-[rgba(0,0,0,0.55)]') : (dark ? 'text-[rgba(255,255,255,0.25)]' : 'text-[rgba(0,0,0,0.25)]')}`}
          />
          <span className={`text-[12px] ${isHLS ? (dark ? 'text-[rgba(255,255,255,0.65)]' : 'text-[rgba(0,0,0,0.65)]') : (dark ? 'text-[rgba(255,255,255,0.35)]' : 'text-[rgba(0,0,0,0.35)]')}`}>
            AES-128 Encryption
          </span>
        </div>
      </div>

      {/* Status messages */}
      {!isHLS && (
        <p className={`text-[11px] flex items-center gap-1.5 pl-12 ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>
          <Icon icon="mdi:information-outline" className="text-xs flex-shrink-0" />
          {format
            ? `Encryption is not available for ${format} — only HLS supports AES-128`
            : 'Only available for HLS output format'}
        </p>
      )}

      {isHLS && enabled && (
        <p className="text-[11px] text-emerald-600 bg-emerald-50 rounded-lg px-2.5 py-1.5 flex items-center gap-1.5">
          <Icon icon="mdi:shield-check-outline" className="text-sm flex-shrink-0" />
          AES-128 encryption will be applied to HLS segments
        </p>
      )}
    </div>
  );
}
