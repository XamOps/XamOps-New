import React from 'react';

/** An on/off switch with its label on the left ("Show False Positives"). */
export const ToggleSwitch: React.FC<{ label: string; checked: boolean; onChange: (checked: boolean) => void }> = ({
  label, checked, onChange,
}) => (
  <label className="inline-flex cursor-pointer items-center gap-2 text-xs text-[rgba(0,0,0,0.6)]">
    <span>{label}</span>
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className={`relative inline-flex h-4 w-7 flex-shrink-0 items-center rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)] ${
        checked ? 'bg-[rgba(0,0,0,0.88)]' : 'bg-[rgba(0,0,0,0.15)]'
      }`}
    >
      <span className={`inline-block h-3 w-3 rounded-full bg-white shadow transition-transform ${checked ? 'translate-x-3.5' : 'translate-x-0.5'}`} />
    </button>
  </label>
);
