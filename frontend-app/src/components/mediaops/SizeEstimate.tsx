import { Icon } from '@iconify/react';
import { formatSizeRange } from '../../utils/sizeFormat';
import type { SizeEstimation } from '../../hooks/useSizeEstimate';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface SizeEstimateProps {
  estimation: SizeEstimation | null;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function SizeEstimate({ estimation }: SizeEstimateProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  if (!estimation) {
    return (
      <div className={`flex items-center gap-2 px-3 py-2 border rounded-xl ${
        dark ? 'bg-[rgba(255,255,255,0.02)] border-[rgba(255,255,255,0.06)]' : 'bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.06)]'
      }`}>
        <Icon icon="mdi:harddisk" className={`text-sm flex-shrink-0 ${dark ? 'text-[rgba(255,255,255,0.25)]' : 'text-[rgba(0,0,0,0.25)]'}`} />
        <span className={`text-[11px] font-medium ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>
          Configure settings to see estimate
        </span>
      </div>
    );
  }

  const rangeText = formatSizeRange(estimation.lowerBound, estimation.upperBound);

  return (
    <div className="flex items-center gap-2 px-3 py-2 bg-violet-50 border border-violet-100 rounded-xl">
      <Icon icon="mdi:harddisk" className="text-violet-500 text-sm flex-shrink-0" />
      <div className="flex items-center gap-1.5 text-[11px]">
        <span className="text-violet-700 font-medium">Estimated output:</span>
        <span className="font-bold text-violet-800">{rangeText}</span>
      </div>
    </div>
  );
}
