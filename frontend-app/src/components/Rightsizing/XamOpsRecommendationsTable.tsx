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

        // The backend decides applicability: a column may be "Stay ...", unavailable in the
        // region, or a cross-architecture move that cannot be applied in place. Older cached
        // payloads predate these flags, so treat an undefined flag as applicable.
        const intelBlocked = rec.intelApplicable === false;
        const amdBlocked = rec.amdApplicable === false;

        const intelTitle = !canApply
          ? 'Read-write access required'
          : !rec.region
            ? 'Region not available'
            : intelBlocked
              ? rec.intelBlockedReason || 'Not applicable'
              : `Apply Intel: ${intelType}`;
        const amdTitle = !canApply
          ? 'Read-write access required'
          : !rec.region
            ? 'Region not available'
            : amdBlocked
              ? rec.amdBlockedReason || 'Not applicable'
              : `Apply AMD: ${amdType}`;

        const intelEnabled = canApply && !!rec.region && !!onApply && !intelBlocked;
        const amdEnabled = canApply && !!rec.region && !!onApply && !amdBlocked;

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
                {rec.requiresArchitectureMigration && (
                  <span
                    className="mt-0.5 inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-semibold bg-amber-50 text-amber-700 border border-amber-200"
                    title={rec.intelBlockedReason || rec.amdBlockedReason || undefined}
                  >
                    <Icon icon="mdi:alert-outline" width={9} aria-hidden="true" />
                    Needs AMI rebuild
                  </span>
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

            {/* Savings — prefer the live-priced dollar figure over the source sheet's
                percentage-range string, which cannot be summed into a total. */}
            <div className="col-span-1">
              {rec.savingsUnknown ? (
                <span
                  className="text-[11px] font-medium text-[rgba(0,0,0,0.4)]"
                  title="Could not price this option in the instance's region"
                >
                  Unknown
                </span>
              ) : rec.estimatedMonthlySavings ? (
                <>
                  <p className="font-semibold text-emerald-600">
                    ${rec.estimatedMonthlySavings.toFixed(2)}
                    <span className="text-[10px] font-normal text-[rgba(0,0,0,0.4)]">/mo</span>
                  </p>
                  {rec.approxCostSavings && (
                    <p className="text-[10px] text-[rgba(0,0,0,0.35)]">{rec.approxCostSavings}</p>
                  )}
                </>
              ) : (
                <span className="font-semibold text-emerald-600">{rec.approxCostSavings}</span>
              )}
            </div>

            {/* Actions */}
            <div className="col-span-1 flex flex-col items-end gap-1">
              <button
                onClick={() => intelEnabled && onApply && rec.region && onApply(rec.instanceId, intelType, rec.region)}
                disabled={!intelEnabled}
                title={intelTitle}
                aria-label={intelTitle}
                className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-semibold border transition-all active:scale-95 ${
                  intelEnabled
                    ? 'border-blue-200 text-blue-600 hover:bg-blue-50 cursor-pointer'
                    : 'border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.25)] cursor-not-allowed'
                }`}
              >
                <Icon icon="mdi:chip" width={10} />
                Intel
              </button>
              <button
                onClick={() => amdEnabled && onApply && rec.region && onApply(rec.instanceId, amdType, rec.region)}
                disabled={!amdEnabled}
                title={amdTitle}
                aria-label={amdTitle}
                className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-semibold border transition-all active:scale-95 ${
                  amdEnabled
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
