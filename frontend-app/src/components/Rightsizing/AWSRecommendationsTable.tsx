import React from 'react';
import { motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import type { AWSRecommendation } from '../../hooks/useRightsizingData';

interface AWSRecommendationsTableProps {
  recommendations: AWSRecommendation[];
  isLoading: boolean;
  canApply?: boolean;
  onApply?: (instanceId: string, targetType: string, region: string) => void;
}

const SERVICE_ICONS: Record<string, { icon: string; color: string; bg: string }> = {
  'EC2':    { icon: 'mdi:server',          color: '#FF9900', bg: '#FF990014' },
  'EBS':    { icon: 'mdi:harddisk',        color: '#4285F4', bg: '#4285F414' },
  'Lambda': { icon: 'mdi:lambda',          color: '#FF9900', bg: '#FF990014' },
};

const AWSRecommendationsTable: React.FC<AWSRecommendationsTableProps> = ({
  recommendations,
  isLoading,
  canApply = false,
  onApply,
}) => {
  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
          <div className="h-2.5 w-48 bg-[rgba(0,0,0,0.06)] rounded" />
        </div>
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="px-5 py-4 border-t border-[rgba(0,0,0,0.04)] animate-pulse flex items-center gap-4">
            <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.06)] flex-shrink-0" />
            <div className="flex-1 space-y-2">
              <div className="h-2.5 w-40 bg-[rgba(0,0,0,0.06)] rounded" />
              <div className="h-2 w-24 bg-[rgba(0,0,0,0.04)] rounded" />
            </div>
            <div className="h-2.5 w-20 bg-[rgba(0,0,0,0.06)] rounded" />
            <div className="h-2.5 w-16 bg-[rgba(0,0,0,0.06)] rounded" />
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden widget-panel-hover">
      {/* Subheader */}
      <div className="flex items-center gap-2 px-5 py-3 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
        <Icon icon="mdi:aws" width={14} color="#FF9900" />
        <span className="text-[11px] text-[rgba(0,0,0,0.4)] italic">Recommendations sourced directly from AWS Cost Optimization Hub</span>
        <span className="ml-auto text-[11px] font-semibold text-[rgba(0,0,0,0.4)]">{recommendations.length} item{recommendations.length !== 1 ? 's' : ''}</span>
      </div>

      {/* Column headers */}
      <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
        <div className="col-span-4">Instance</div>
        <div className="col-span-3">Cost Comparison</div>
        <div className="col-span-2">Savings / mo</div>
        <div className="col-span-2">Reason</div>
        <div className="col-span-1 text-right">Action</div>
      </div>

      {/* Rows */}
      {recommendations.map((rec, idx) => {
        const meta = SERVICE_ICONS[rec.service] ?? { icon: 'mdi:cloud-outline', color: '#6B7280', bg: '#6B728014' };
        const savingsPct = rec.currentMonthlyCost > 0
          ? ((rec.estimatedMonthlySavings / rec.currentMonthlyCost) * 100).toFixed(1)
          : null;

        return (
          <motion.div
            key={`${rec.resourceId}-${idx}`}
            className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]"
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.15, delay: Math.min(idx * 0.04, 0.6), ease: 'easeOut' }}
          >
            {/* Instance */}
            <div className="col-span-4 flex items-center gap-3 min-w-0">
              <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 border border-[rgba(0,0,0,0.08)]" style={{ backgroundColor: meta.bg }}>
                <Icon icon={meta.icon} width={16} color={meta.color} />
              </div>
              <div className="min-w-0">
                <p className="font-semibold text-[rgba(0,0,0,0.88)] truncate">{rec.instanceName || rec.resourceId}</p>
                {rec.instanceName && (
                  <p className="text-[11px] font-mono text-[rgba(0,0,0,0.35)] truncate">{rec.resourceId}</p>
                )}
              </div>
            </div>

            {/* Cost comparison */}
            <div className="col-span-3 flex items-center gap-2">
              <div>
                <p className="font-semibold text-rose-600 text-[12px]">${rec.currentMonthlyCost.toFixed(2)}</p>
                <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{rec.currentType}</p>
              </div>
              <svg className="w-3 h-3 text-[rgba(0,0,0,0.25)] flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
              <div>
                <p className="font-semibold text-emerald-600 text-[12px]">${rec.recommendedMonthlyCost.toFixed(2)}</p>
                <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{rec.recommendedType}</p>
              </div>
            </div>

            {/* Savings */}
            <div className="col-span-2">
              <p className="font-semibold text-emerald-600 text-[13px]">${rec.estimatedMonthlySavings.toFixed(2)}</p>
              {savingsPct && (
                <p className="text-[11px] text-[rgba(0,0,0,0.35)]">{savingsPct}% savings</p>
              )}
            </div>

            {/* Reason */}
            <div className="col-span-2 text-[12px] text-[rgba(0,0,0,0.55)] leading-relaxed pr-2">
              {rec.recommendationReason}
            </div>

            {/* Action */}
            <div className="col-span-1 flex justify-end">
              {rec.service === 'EC2' ? (
                <button
                  onClick={() => onApply && rec.region && onApply(rec.resourceId, rec.recommendedType, rec.region)}
                  disabled={!canApply || !rec.region || !onApply}
                  title={!canApply ? 'Read-write access required' : !rec.region ? 'Region not available' : `Apply: change to ${rec.recommendedType}`}
                  className={`inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold border transition-all active:scale-95 ${
                    canApply && rec.region
                      ? 'border-indigo-200 text-indigo-600 hover:bg-indigo-50 cursor-pointer'
                      : 'border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.25)] cursor-not-allowed'
                  }`}
                >
                  <Icon icon="mdi:magic-staff" width={12} />
                  Apply
                </button>
              ) : (
                <span className="text-[11px] text-[rgba(0,0,0,0.25)] italic">N/A</span>
              )}
            </div>
          </motion.div>
        );
      })}
    </div>
  );
};

export default AWSRecommendationsTable;
