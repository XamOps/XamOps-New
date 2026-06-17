import { useState } from 'react';
import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface CustomParamsProps {
  value: string;
  onChange: (value: string) => void;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function CustomParams({ value, onChange }: CustomParamsProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const [showTooltip, setShowTooltip] = useState(false);
  const charCount = value.length;
  const MAX_CHARS = 500;

  return (
    <div className="space-y-2">
      {/* Header with tooltip */}
      <div className="flex items-center justify-between">
        <div className="relative">
          <button
            type="button"
            onMouseEnter={() => setShowTooltip(true)}
            onMouseLeave={() => setShowTooltip(false)}
            onFocus={() => setShowTooltip(true)}
            onBlur={() => setShowTooltip(false)}
            className={`flex items-center gap-1 text-[11px] transition-colors ${dark ? 'text-[rgba(255,255,255,0.5)] hover:text-[rgba(255,255,255,0.7)]' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.7)]'}`}
            aria-label="Syntax guidance"
          >
            <Icon icon="mdi:help-circle-outline" className="text-sm" />
            <span>Syntax guide</span>
          </button>

          {/* Tooltip */}
          {showTooltip && (
            <div className="absolute z-10 left-0 top-full mt-1 w-72 p-3 bg-[rgba(0,0,0,0.88)] text-white text-[11px] rounded-xl shadow-lg leading-relaxed">
              <p className="font-semibold mb-1">Extra encoding flags appended before output path.</p>
              <p className="text-[rgba(255,255,255,0.7)]">
                Avoid: <code className="bg-[rgba(255,255,255,0.15)] px-1 rounded">; | ` $ &amp; ( ) {'{'} {'}'} [ ] &lt; &gt; !</code>
              </p>
              <p className="text-[rgba(255,255,255,0.5)] mt-1.5">
                Example: <code className="bg-[rgba(255,255,255,0.15)] px-1 rounded">-movflags +faststart -preset slow</code>
              </p>
              <div className="absolute -top-1 left-4 w-2 h-2 bg-[rgba(0,0,0,0.88)] rotate-45" />
            </div>
          )}
        </div>

        {/* Character count */}
        <span className={`text-[10px] ${charCount > MAX_CHARS ? 'text-red-500 font-semibold' : (dark ? 'text-[rgba(255,255,255,0.35)]' : 'text-[rgba(0,0,0,0.35)]')}`}>
          {charCount}/{MAX_CHARS}
        </span>
      </div>

      {/* Textarea */}
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="-movflags +faststart -preset slow"
        rows={3}
        className={`w-full px-3 py-2.5 border rounded-xl text-[12px] font-mono focus:ring-2 focus:ring-pink-400 focus:border-pink-400 outline-none resize-none leading-relaxed ${
          dark
            ? 'bg-[rgba(255,255,255,0.02)] border-[rgba(255,255,255,0.12)] text-white placeholder-[rgba(255,255,255,0.25)]'
            : 'bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.12)] placeholder-[rgba(0,0,0,0.25)]'
        }`}
        spellCheck={false}
      />

      {/* Warning if prohibited chars detected */}
      {/[;|`$&(){}\[\]<>!]/.test(value) && (
        <p className="text-[11px] text-amber-600 bg-amber-50 rounded-lg px-2.5 py-1.5 flex items-center gap-1.5">
          <Icon icon="mdi:alert-outline" className="text-sm flex-shrink-0" />
          Contains prohibited characters that will be rejected by the server.
        </p>
      )}
    </div>
  );
}
