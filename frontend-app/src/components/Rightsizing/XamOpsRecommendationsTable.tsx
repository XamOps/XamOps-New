import React from 'react';
import { motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import type { XamOpsRecommendation } from '../../hooks/useRightsizingData';

interface XamOpsRecommendationsTableProps {
  recommendations: XamOpsRecommendation[];
  isLoading: boolean;
  canApply?: boolean;
  onApply?: (instanceId: string, targetType: string, region: string) => void;
}

const extractInstanceType = (recommendation: string): string =>
  recommendation.split('(')[0].trim();

const extractUtilization = (recommendation: string): string => {
  const match = recommendation.match(/\((.*?)\)/);
  return match ? match[0] : '';
};

const XamOpsRecommendationsTable: React.FC<XamOpsRecommendationsTableProps> = ({
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
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden widget-panel-hover">
      {/* Subheader */}
      <div className="flex items-center gap-2 px-5 py-3 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
        <Icon icon="mdi:lightning-bolt" width={14} color="#6366F1" />
        <span className="text-[11px] text-[rgba(0,0,0,0.4)] italic">Proprietary XamAi analysis — cross-architecture Intel &amp; AMD recommendations</span>
        <span className="ml-auto text-[11px] font-semibold text-[rgba(0,0,0,0.4)]">{recommendations.length} item{recommendations.length !== 1 ? 's' : ''}</span>
      </div>

      {/* Column headers */}
      <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
        <div className="col-span-3">Instance</div>
        <div className="col-span-1">Current</div>
        <div className="col-span-1">Load</div>
        <div className="col-span-2">Intel Rec</div>
        <div className="col-span-2">AMD Rec</div>
        <div className="col-span-1">Max Util</div>
        <div className="col-span-1">Savings</div>
        <div className="col-span-1 text-right">Action</div>
      </div>

      {/* Rows */}
      {recommendations.map((rec, idx) => {
        const intelType = extractInstanceType(rec.intelRecommendation);
        const amdType = extractInstanceType(rec.amdRecommendation);
        const intelUtil = extractUtilization(rec.intelRecommendation);
        const amdUtil = extractUtilization(rec.amdRecommendation);

        return (
          <motion.div
            key={`${rec.instanceId || rec.currentInstance}-${idx}`}
            className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[12px]"
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.15, delay: Math.min(idx * 0.04, 0.6), ease: 'easeOut' }}
          >
            {/* Instance */}
            <div className="col-span-3 flex items-center gap-2.5 min-w-0">
              <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 border border-[rgba(0,0,0,0.08)]" style={{ backgroundColor: '#6366F114' }}>
                <Icon icon="mdi:server" width={14} color="#6366F1" />
              </div>
              <div className="min-w-0">
                <p className="font-semibold text-[rgba(0,0,0,0.88)] truncate text-[13px]">{rec.instanceName || rec.instanceId || 'N/A'}</p>
                {rec.instanceName && (
                  <p className="text-[10px] font-mono text-[rgba(0,0,0,0.35)] truncate">{rec.instanceId}</p>
                )}
              </div>
            </div>

            {/* Current instance */}
            <div className="col-span-1">
              <p className="font-mono font-semibold text-[rgba(0,0,0,0.88)] text-[11px]">{rec.currentInstance}</p>
              {rec.currentUtilization && (
                <p className="text-[10px] text-indigo-500 font-medium">{rec.currentUtilization}</p>
              )}
            </div>

            {/* Load range */}
            <div className="col-span-1 text-[rgba(0,0,0,0.55)]">{rec.loadRange}</div>

            {/* Intel rec */}
            <div className="col-span-2">
              <p className="font-mono font-medium text-[rgba(0,0,0,0.8)] text-[11px]">{intelType}</p>
              {intelUtil && <p className="text-[10px] text-[rgba(0,0,0,0.35)]">{intelUtil}</p>}
            </div>

            {/* AMD rec */}
            <div className="col-span-2">
              <p className="font-mono font-medium text-[rgba(0,0,0,0.8)] text-[11px]">{amdType}</p>
              {amdUtil && <p className="text-[10px] text-[rgba(0,0,0,0.35)]">{amdUtil}</p>}
            </div>

            {/* Projected max util */}
            <div className="col-span-1 text-[rgba(0,0,0,0.55)]">{rec.projectedMaxUtil}</div>

            {/* Savings */}
            <div className="col-span-1 font-semibold text-emerald-600">{rec.approxCostSavings}</div>

            {/* Actions */}
            <div className="col-span-1 flex flex-col items-end gap-1">
              <button
                onClick={() => onApply && rec.region && onApply(rec.instanceId, intelType, rec.region)}
                disabled={!canApply || !rec.region || !onApply}
                title={!canApply ? 'Read-write access required' : !rec.region ? 'Region not available' : `Apply Intel: ${intelType}`}
                className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-semibold border transition-all active:scale-95 ${
                  canApply && rec.region
                    ? 'border-blue-200 text-blue-600 hover:bg-blue-50 cursor-pointer'
                    : 'border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.25)] cursor-not-allowed'
                }`}
              >
                <Icon icon="mdi:chip" width={10} />
                Intel
              </button>
              <button
                onClick={() => onApply && rec.region && onApply(rec.instanceId, amdType, rec.region)}
                disabled={!canApply || !rec.region || !onApply}
                title={!canApply ? 'Read-write access required' : !rec.region ? 'Region not available' : `Apply AMD: ${amdType}`}
                className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-semibold border transition-all active:scale-95 ${
                  canApply && rec.region
                    ? 'border-purple-200 text-purple-600 hover:bg-purple-50 cursor-pointer'
                    : 'border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.25)] cursor-not-allowed'
                }`}
              >
                <Icon icon="mdi:chip" width={10} />
                AMD
              </button>
            </div>
          </motion.div>
        );
      })}
    </div>
  );
};

export default XamOpsRecommendationsTable;
