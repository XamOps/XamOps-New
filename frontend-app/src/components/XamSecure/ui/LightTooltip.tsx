import React from 'react';

/**
 * Light-theme tooltip for the Overview Recharts area chart.
 *
 * Recharts passes `active`, `payload` and `label` to whatever is given to a Tooltip's
 * `content` prop; we type them loosely because recharts 3's exported payload typings are
 * awkward to satisfy and this component is purely presentational. White card, mono values,
 * one colored dot per series.
 */
interface TooltipEntry {
  name?: string;
  value?: number | string;
  color?: string;
  dataKey?: string | number;
}

interface LightTooltipProps {
  active?: boolean;
  payload?: TooltipEntry[];
  label?: string | number;
}

const LightTooltip: React.FC<LightTooltipProps> = ({ active, payload, label }) => {
  if (!active || !payload || payload.length === 0) return null;
  return (
    <div className="rounded-lg border border-gray-200 bg-white shadow-md px-3 py-2 min-w-[132px]">
      {label != null && (
        <p className="text-[11px] font-medium text-gray-500 mb-1">{String(label)}</p>
      )}
      <div className="space-y-1">
        {payload.map((entry, i) => (
          <div key={`${entry.dataKey ?? entry.name ?? i}`} className="flex items-center justify-between gap-3">
            <span className="flex items-center gap-1.5 text-[11px] text-gray-500">
              <span
                className="inline-block w-2 h-2 rounded-full flex-shrink-0"
                style={{ backgroundColor: entry.color ?? '#9ca3af' }}
              />
              {entry.name ?? entry.dataKey}
            </span>
            <span className="font-mono text-xs font-semibold text-gray-900 tabular-nums">
              {typeof entry.value === 'number' ? entry.value.toLocaleString() : entry.value}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
};

export default LightTooltip;
