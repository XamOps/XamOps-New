import React from 'react';
import { Icon } from '@iconify/react';
import type { AzureSecureScoreControl } from '../../types/azureSecurity';

interface AzureSecurityScoreControlsProps {
  controls: AzureSecureScoreControl[];
  isLoading: boolean;
}

/** Color for the completion bar based on how complete a control is. */
const barColor = (pct: number): string => {
  if (pct >= 100) return '#10b981'; // emerald — fully remediated
  if (pct >= 50) return '#f59e0b';  // amber — partial
  return '#ef4444';                  // red — needs attention
};

const SkeletonRow = () => (
  <tr className="border-b border-gray-100 animate-pulse">
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-48" /></td>
    <td className="px-4 py-4"><div className="h-2 bg-gray-200 rounded w-32" /></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-16" /></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-20" /></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-16" /></td>
  </tr>
);

const AzureSecurityScoreControls: React.FC<AzureSecurityScoreControlsProps> = ({
  controls,
  isLoading,
}) => {
  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-100">
          <div className="h-4 bg-gray-200 rounded w-40 animate-pulse" />
        </div>
        <table className="w-full text-sm">
          <tbody><SkeletonRow /><SkeletonRow /><SkeletonRow /><SkeletonRow /></tbody>
        </table>
      </div>
    );
  }

  if (controls.length === 0) {
    return (
      <div className="text-center py-16 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
        <div className="w-14 h-14 bg-blue-50 border border-blue-100 rounded-xl flex items-center justify-center mx-auto mb-3">
          <Icon icon="mdi:gauge" width={28} color="#3b82f6" />
        </div>
        <h3 className="text-base font-bold text-gray-700 mb-1">No Score Controls</h3>
        <p className="text-sm text-gray-500">
          Secure score controls are unavailable. Enable Microsoft Defender for Cloud to populate this view.
        </p>
      </div>
    );
  }

  const totalRecoverable = controls.reduce((sum, c) => sum + c.recoverableScore, 0);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-5 py-3.5 border-b border-gray-100 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
            <Icon icon="mdi:gauge" width={16} color="#6366f1" />
          </div>
          <div>
            <h3 className="text-sm font-bold text-gray-800">Secure Score Controls</h3>
            <p className="text-xs text-gray-500">Controls ranked by recoverable points</p>
          </div>
        </div>
        {totalRecoverable > 0 && (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-semibold bg-amber-50 text-amber-700 border border-amber-200">
            <Icon icon="mdi:trending-up" width={14} />
            +{totalRecoverable.toFixed(1)} pts recoverable
          </span>
        )}
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-100">
            <tr>
              <th className="px-4 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Control</th>
              <th className="px-4 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Completion</th>
              <th className="px-4 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Score</th>
              <th className="px-4 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Unhealthy</th>
              <th className="px-4 py-2.5 text-right text-xs font-bold uppercase tracking-wider text-gray-500">Recoverable</th>
            </tr>
          </thead>
          <tbody>
            {controls.map((c, idx) => {
              const pct = Math.min(100, Math.max(0, c.percentage));
              const color = barColor(pct);
              return (
                <tr key={c.controlName || idx} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-3">
                    <span className="text-gray-800 font-medium text-xs block max-w-[260px] truncate" title={c.controlName}>
                      {c.controlName || '—'}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2 min-w-[140px]">
                      <div className="flex-1 h-1.5 rounded-full bg-gray-100 overflow-hidden">
                        <div className="h-full rounded-full transition-all" style={{ width: `${pct}%`, backgroundColor: color }} />
                      </div>
                      <span className="text-xs font-semibold tabular-nums text-gray-600 w-9 text-right">{pct.toFixed(0)}%</span>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <span className="text-xs font-semibold tabular-nums text-gray-700">
                      {c.currentScore.toFixed(1)}<span className="text-gray-400"> / {c.maxScore.toFixed(0)}</span>
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    {c.unhealthyResourceCount > 0 ? (
                      <span className="inline-flex items-center gap-1 text-xs font-semibold text-red-600">
                        <span className="w-1.5 h-1.5 rounded-full bg-red-500" />
                        {c.unhealthyResourceCount}
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-600">
                        <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                        0
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-right">
                    {c.recoverableScore > 0 ? (
                      <span className="text-xs font-bold tabular-nums text-amber-600">+{c.recoverableScore.toFixed(1)}</span>
                    ) : (
                      <span className="text-xs font-semibold text-emerald-600">Done</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default AzureSecurityScoreControls;
