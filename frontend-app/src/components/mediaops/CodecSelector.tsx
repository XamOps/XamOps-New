import { useState, useRef, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

interface CodecSelectorProps {
  value: string | null;
  onChange: (codec: string | null) => void;
  outputFormat: string; // Current output format to filter compatible codecs
  disabled?: boolean;
}

// ─── Compatibility Matrix ─────────────────────────────────────────────────────

// Which codecs are supported by which output formats
const CODEC_FORMAT_COMPAT: Record<string, string[]> = {
  'H264': ['MP4', 'MKV', 'MOV', 'HLS', 'DASH'],
  'H265': ['MP4', 'MKV', 'MOV', 'HLS', 'DASH'],
  'AV1':  ['MP4', 'MKV', 'WEBM'],
  'VP9':  ['WEBM', 'MKV'],
};

// Audio-only formats don't use video codecs
const AUDIO_ONLY_FORMATS = ['MP3', 'AAC', 'FLAC', 'OGG'];

function isCodecCompatible(codec: string | null, format: string): boolean {
  if (!codec) return true; // "Auto" is always valid
  const compatible = CODEC_FORMAT_COMPAT[codec];
  if (!compatible) return true;
  return compatible.includes(format.toUpperCase());
}

// ─── Constants ────────────────────────────────────────────────────────────────

const CODEC_OPTIONS = [
  {
    value: null as string | null,
    label: 'Auto (H.264)',
    hint: 'Best compatibility — works on all devices and browsers',
  },
  {
    value: 'H265' as string | null,
    label: 'H.265 / HEVC',
    hint: 'Better compression — 50% smaller at same quality, slower encode',
  },
  {
    value: 'AV1' as string | null,
    label: 'AV1',
    hint: 'Best quality per bit — very slow encode, modern browser support only',
  },
  {
    value: 'VP9' as string | null,
    label: 'VP9',
    hint: 'Good quality — optimized for WebM/YouTube, moderate speed',
  },
];

// ─── Component ────────────────────────────────────────────────────────────────

export function CodecSelector({ value, onChange, outputFormat, disabled }: CodecSelectorProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const isAudioOnly = AUDIO_ONLY_FORMATS.includes(outputFormat.toUpperCase());
  const effectiveDisabled = disabled || isAudioOnly;

  const selected = CODEC_OPTIONS.find(o => o.value === value) ?? CODEC_OPTIONS[0];

  // Auto-reset codec if it's incompatible with new format
  useEffect(() => {
    if (value && !isCodecCompatible(value, outputFormat)) {
      onChange(null); // Reset to Auto
    }
  }, [outputFormat, value, onChange]);

  // Close dropdown when clicking outside
  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    if (open) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [open]);

  return (
    <div ref={containerRef} className="relative">
      {/* Trigger button */}
      <button
        type="button"
        onClick={() => !effectiveDisabled && setOpen(!open)}
        disabled={effectiveDisabled}
        className={`w-full flex items-center justify-between px-3 py-2.5 border rounded-xl text-[13px] transition-all outline-none ${
          effectiveDisabled
            ? (dark ? 'bg-[rgba(255,255,255,0.03)] border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.35)] cursor-not-allowed' : 'bg-[rgba(0,0,0,0.03)] border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.35)] cursor-not-allowed')
            : open
            ? (dark ? 'border-pink-400 ring-2 ring-pink-400 bg-[#1e1e1e]' : 'border-pink-400 ring-2 ring-pink-400 bg-white')
            : (dark ? 'border-[rgba(255,255,255,0.12)] bg-[#1e1e1e] hover:border-pink-300' : 'border-[rgba(0,0,0,0.12)] bg-white hover:border-pink-300')
        }`}
      >
        <span className={effectiveDisabled ? (dark ? 'text-[rgba(255,255,255,0.35)]' : 'text-[rgba(0,0,0,0.35)]') : (dark ? 'text-[rgba(255,255,255,0.8)] font-medium' : 'text-[rgba(0,0,0,0.8)] font-medium')}>
          {isAudioOnly ? 'N/A (audio-only format)' : selected.label}
        </span>
        <Icon
          icon="mdi:chevron-down"
          className={`text-base transition-transform ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'} ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {/* Hint for audio-only */}
      {isAudioOnly && (
        <p className={`text-[10px] mt-1 flex items-center gap-1 ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>
          <Icon icon="mdi:information-outline" className="text-xs" />
          Video codec not applicable for audio-only formats
        </p>
      )}

      {/* Dropdown */}
      {open && (
        <div className={`absolute z-50 mt-1.5 w-full border rounded-xl shadow-lg overflow-hidden ${
          dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.1)]' : 'bg-white border-[rgba(0,0,0,0.1)]'
        }`}>
          {CODEC_OPTIONS.map((option) => {
            const isSelected = option.value === value;
            const compatible = isCodecCompatible(option.value, outputFormat);
            return (
              <button
                key={option.label}
                type="button"
                disabled={!compatible}
                onClick={() => {
                  if (compatible) {
                    onChange(option.value);
                    setOpen(false);
                  }
                }}
                className={`w-full text-left px-3 py-2.5 transition-colors ${
                  !compatible
                    ? `opacity-40 cursor-not-allowed ${dark ? 'bg-[rgba(255,255,255,0.02)]' : 'bg-[rgba(0,0,0,0.02)]'}`
                    : isSelected
                    ? 'bg-pink-50 border-l-2 border-l-pink-500'
                    : `${dark ? 'hover:bg-[rgba(255,255,255,0.03)]' : 'hover:bg-[rgba(0,0,0,0.03)]'} border-l-2 border-l-transparent`
                }`}
              >
                <div className="flex items-center justify-between">
                  <span className={`text-[13px] font-medium ${
                    !compatible ? (dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.3)]') : isSelected ? 'text-pink-700' : (dark ? 'text-[rgba(255,255,255,0.8)]' : 'text-[rgba(0,0,0,0.8)]')
                  }`}>
                    {option.label}
                  </span>
                  {isSelected && compatible && <Icon icon="mdi:check" className="text-pink-500 text-sm" />}
                  {!compatible && <span className="text-[9px] text-red-400 font-semibold">Not supported</span>}
                </div>
                <p className={`text-[11px] mt-0.5 ${dark ? 'text-[rgba(255,255,255,0.45)]' : 'text-[rgba(0,0,0,0.45)]'}`}>
                  {!compatible
                    ? `Not compatible with ${outputFormat} format`
                    : option.hint}
                </p>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
