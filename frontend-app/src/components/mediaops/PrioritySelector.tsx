import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

interface PrioritySelectorProps {
  value: 'HIGH' | 'NORMAL' | 'LOW';
  onChange: (priority: 'HIGH' | 'NORMAL' | 'LOW') => void;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const PRIORITY_OPTIONS: {
  value: 'HIGH' | 'NORMAL' | 'LOW';
  label: string;
  icon: string;
  activeClasses: string;
}[] = [
  {
    value: 'HIGH',
    label: 'High',
    icon: 'mdi:arrow-up-bold',
    activeClasses: 'bg-red-50 border-red-300 text-red-700 shadow-sm',
  },
  {
    value: 'NORMAL',
    label: 'Normal',
    icon: 'mdi:minus',
    activeClasses: 'bg-blue-50 border-blue-300 text-blue-700 shadow-sm',
  },
  {
    value: 'LOW',
    label: 'Low',
    icon: 'mdi:arrow-down-bold',
    activeClasses: 'bg-gray-100 border-gray-300 text-gray-600 shadow-sm',
  },
];

// ─── Component ────────────────────────────────────────────────────────────────

export function PrioritySelector({ value, onChange }: PrioritySelectorProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  return (
    <div className={`flex items-center gap-1.5 rounded-xl p-1 ${dark ? 'bg-[rgba(255,255,255,0.03)]' : 'bg-[rgba(0,0,0,0.03)]'}`}>
      {PRIORITY_OPTIONS.map((option) => {
        const isActive = value === option.value;
        return (
          <button
            key={option.value}
            type="button"
            onClick={() => onChange(option.value)}
            className={`flex items-center gap-1.5 px-3 py-2 rounded-lg text-[12px] font-bold transition-all flex-1 justify-center border ${
              isActive
                ? option.activeClasses
                : `border-transparent ${dark ? 'text-[rgba(255,255,255,0.4)] hover:text-[rgba(255,255,255,0.65)] hover:bg-[rgba(255,255,255,0.04)]' : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.04)]'}`
            }`}
          >
            <Icon icon={option.icon} className="text-sm" />
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
