import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface EncryptionToggleProps {
  enabled: boolean;
  outputFormat: string;
  onChange: (enabled: boolean) => void;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function EncryptionToggle({ enabled, outputFormat, onChange }: EncryptionToggleProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const isHLS = outputFormat.toUpperCase() === 'HLS';

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
                ? 'bg-pink-500'
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
          Only available for HLS output format
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
