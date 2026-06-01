import React from 'react';
import type { OptimizationData } from '../../types/liveDashboard';

interface OptimizationSectionProps {
  optimization: OptimizationData;
  isLoading: boolean;
  isMultiAccount: boolean;
  selectedAccountIds?: string[];
}

interface RecommendationItem {
  resourceId?: string;
  instanceId?: string;
  volumeId?: string;
  functionName?: string;
  currentType?: string;
  instanceType?: string;
  volumeType?: string;
  recommendedType?: string;
  recommendedMemory?: number;
  estimatedMonthlySavings?: number;
  estimatedSavings?: number;
}

interface AnomalyItem {
  service?: string;
  month?: string;
  startDate?: string;
  unexpectedSpend?: number;
  currentCost?: number;
}

// Shared icon renderer — keeps SVGs in one place
const OptIcon: React.FC<{ name: string; className?: string }> = ({ name, className = 'w-3.5 h-3.5' }) => {
  if (name === 'fa-server') return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2" />
    </svg>
  );
  if (name === 'fa-hdd') return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7v10c0 2 1 3 3 3h10c2 0 3-1 3-3V7c0-2-1-3-3-3H7c-2 0-3 1-3 3zM9 12h6" />
    </svg>
  );
  if (name === 'fa-bolt') return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
    </svg>
  );
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
    </svg>
  );
};

const OptimizationSection: React.FC<OptimizationSectionProps> = ({
  optimization,
  isLoading,
  isMultiAccount,
  selectedAccountIds = [],
}) => {
  const formatCurrency = (num: number): string =>
    '$' + (num == null ? '0.00' : num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }));

  // Empty sub-card (optimized state)
  const renderEmptyCard = (title: string, icon: string) => (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded-md bg-[rgba(0,0,0,0.04)] flex items-center justify-center flex-shrink-0">
            <OptIcon name={icon} className="w-3.5 h-3.5 text-[rgba(0,0,0,0.3)]" />
          </div>
          <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.88)] truncate">{title}</span>
        </div>
        <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.04)] px-1.5 py-0.5 rounded-md">0</span>
      </div>
      <div className="text-center py-5">
        <svg className="w-5 h-5 text-[#1BE68B] mx-auto mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        <p className="text-[11px] font-medium text-[rgba(0,0,0,0.45)] uppercase tracking-wide">Optimized</p>
        <p className="text-[10px] text-[rgba(0,0,0,0.3)] mt-0.5">No pending actions</p>
      </div>
    </div>
  );

  // Recommendation sub-card
  const renderRecsCard = (
    title: string,
    recs: RecommendationItem[] | undefined,
    icon: string,
    linkPath: string
  ) => {
    if (!recs || recs.length === 0) return renderEmptyCard(title, icon);

    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <div className="w-6 h-6 rounded-md bg-[rgba(0,0,0,0.06)] flex items-center justify-center flex-shrink-0">
              <OptIcon name={icon} className="w-3.5 h-3.5 text-[rgba(0,0,0,0.5)]" />
            </div>
            <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.88)] truncate">{title}</span>
          </div>
          <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 rounded-md">{recs.length}</span>
        </div>

        <table className="w-full">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)]">
              <th className="pb-1.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Resource</th>
              <th className="pb-1.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Rec.</th>
              <th className="pb-1.5 text-right text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Savings</th>
            </tr>
          </thead>
          <tbody>
            {recs.slice(0, 2).map((rec) => {
              const resourceId = rec.resourceId || rec.instanceId || rec.volumeId || rec.functionName || '-';
              const currentType = rec.currentType || rec.instanceType || rec.volumeType || '-';
              const recommendedType = rec.recommendedType || (rec.recommendedMemory ? `${rec.recommendedMemory}MB` : '-');
              const savings = rec.estimatedMonthlySavings || rec.estimatedSavings || 0;
              return (
                <tr key={`${resourceId}-${savings}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-0">
                  <td className="py-2 pr-2 font-mono text-[11px] text-[rgba(0,0,0,0.5)] truncate max-w-[80px]" title={resourceId}>{resourceId}</td>
                  <td className="py-2 pr-2">
                    <div className="flex items-center gap-1">
                      <span className="text-[11px] text-[rgba(0,0,0,0.5)] truncate max-w-[50px]">{currentType}</span>
                      <svg className="w-2.5 h-2.5 text-[rgba(0,0,0,0.25)] flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M14 5l7 7m0 0l-7 7m7-7H3" />
                      </svg>
                      <span className="text-[11px] font-medium text-[rgba(0,0,0,0.7)] truncate max-w-[50px]">{recommendedType}</span>
                    </div>
                  </td>
                  <td className="py-2 text-right text-[11px] font-semibold text-[#158045]">{formatCurrency(savings)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>

        {recs.length > 2 && (
          <div className="mt-2 pt-2 border-t border-[rgba(0,0,0,0.04)] text-center">
            <a
              href={`${linkPath.replace('.html', '')}?accountIds=${selectedAccountIds.join(',')}`}
              className="text-[11px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
            >
              +{recs.length - 2} more
            </a>
          </div>
        )}
      </div>
    );
  };

  // Anomalies sub-card
  const renderAnomaliesCard = (anomalies: AnomalyItem[] | undefined) => {
    if (!anomalies || anomalies.length === 0) {
      return renderEmptyCard('Cost Anomalies', 'fa-alert');
    }

    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <div className="w-6 h-6 rounded-md bg-[#FEE2E2] flex items-center justify-center flex-shrink-0">
              <OptIcon name="fa-alert" className="w-3.5 h-3.5 text-[#DC2626]" />
            </div>
            <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.88)] truncate">Cost Anomalies</span>
          </div>
          <span className="text-[11px] font-semibold text-[#DC2626] bg-[#FEE2E2] px-1.5 py-0.5 rounded-md">{anomalies.length}</span>
        </div>

        <table className="w-full">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)]">
              <th className="pb-1.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Service</th>
              <th className="pb-1.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Date</th>
              <th className="pb-1.5 text-right text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Spend</th>
            </tr>
          </thead>
          <tbody>
            {anomalies.slice(0, 2).map((a) => (
              <tr key={`${a.service || a.month}-${a.startDate}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-0">
                <td className="py-2 pr-2 text-[11px] text-[rgba(0,0,0,0.7)] truncate max-w-[100px]">{a.service || a.month || '-'}</td>
                <td className="py-2 pr-2 text-[11px] text-[rgba(0,0,0,0.45)] whitespace-nowrap">{a.startDate || '-'}</td>
                <td className="py-2 text-right text-[11px] font-semibold text-[#DC2626]">{formatCurrency(a.unexpectedSpend || a.currentCost || 0)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        {anomalies.length > 2 && (
          <div className="mt-2 pt-2 border-t border-[rgba(0,0,0,0.04)] text-center">
            <a
              href={`/alerts?accountIds=${selectedAccountIds.join(',')}`}
              className="text-[11px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
            >
              +{anomalies.length - 2} more
            </a>
          </div>
        )}
      </div>
    );
  };

  const summary = optimization?.summary || { totalPotentialSavings: 0, criticalAlerts: 0, totalRecommendations: 0 };
  const totalSavings = summary.totalPotentialSavings || 0;
  const criticalAlerts = summary.criticalAlerts || 0;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
      {/* Section header */}
      <div className="flex items-center justify-between mb-5">
        <div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Optimization Hub</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse inline-block"></span>
            Strategic Insights
          </p>
        </div>
      </div>

      {/* Loading */}
      {isLoading && (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="h-16 bg-[rgba(0,0,0,0.04)] rounded-xl animate-pulse"></div>
            <div className="h-16 bg-[rgba(0,0,0,0.04)] rounded-xl animate-pulse"></div>
          </div>
          <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
            {[1,2,3,4].map(i => <div key={i} className="h-28 bg-[rgba(0,0,0,0.04)] rounded-xl animate-pulse"></div>)}
          </div>
        </div>
      )}

      {!isLoading && (
        <>
          {isMultiAccount ? (
            <div className="border border-[rgba(0,0,0,0.12)] rounded-xl p-6 text-center">
              <p className="text-[13px] font-medium text-[rgba(0,0,0,0.5)]">Select a single account to view detailed recommendations.</p>
            </div>
          ) : (
            <>
              {/* Summary row */}
              <div className="grid grid-cols-2 gap-3 mb-4">
                {/* Savings */}
                <div className="bg-[#F0FDF4] border border-[#D1FAE1] rounded-xl p-3 flex items-center gap-3">
                  <div className="w-8 h-8 rounded-lg bg-[#1BE68B]/20 flex items-center justify-center flex-shrink-0">
                    <svg className="w-4 h-4 text-[#158045]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                    </svg>
                  </div>
                  <div>
                    <p className="text-[11px] text-[#158045] font-medium">Potential Savings</p>
                    <p className="text-[18px] font-extrabold text-[#158045] leading-tight" style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {formatCurrency(totalSavings)}<span className="text-[11px] font-normal ml-0.5">/mo</span>
                    </p>
                  </div>
                </div>

                {/* Critical alerts */}
                <div className={`border rounded-xl p-3 flex items-center gap-3 ${
                  criticalAlerts > 0
                    ? 'bg-[#FEF2F2] border-[#FECACA]'
                    : 'bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.06)]'
                }`}>
                  <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${
                    criticalAlerts > 0 ? 'bg-[#FEE2E2]' : 'bg-[rgba(0,0,0,0.04)]'
                  }`}>
                    <svg className={`w-4 h-4 ${criticalAlerts > 0 ? 'text-[#DC2626]' : 'text-[rgba(0,0,0,0.3)]'}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                    </svg>
                  </div>
                  <div>
                    <p className={`text-[11px] font-medium ${criticalAlerts > 0 ? 'text-[#DC2626]' : 'text-[rgba(0,0,0,0.4)]'}`}>Critical Alerts</p>
                    <p className={`text-[18px] font-extrabold leading-tight ${criticalAlerts > 0 ? 'text-[#DC2626]' : 'text-[rgba(0,0,0,0.3)]'}`} style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {criticalAlerts}<span className="text-[11px] font-normal ml-0.5">items</span>
                    </p>
                  </div>
                </div>
              </div>

              {/* Recommendation sub-cards */}
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-3">
                {renderRecsCard('EC2 Right-Sizing', optimization?.ec2Recommendations, 'fa-server', '/rightsizing.html')}
                {renderRecsCard('EBS Optimization', optimization?.ebsRecommendations, 'fa-hdd', '/rightsizing.html')}
                {renderRecsCard('Lambda Optimization', optimization?.lambdaRecommendations, 'fa-bolt', '/rightsizing.html')}
                {renderAnomaliesCard(optimization?.anomalies)}
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
};

export default OptimizationSection;
