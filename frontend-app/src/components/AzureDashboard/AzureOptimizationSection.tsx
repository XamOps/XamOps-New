import React from 'react';
import { Link } from 'react-router-dom';
import { AzureOptimizationData } from '../../types/azureDashboard';

interface AzureOptimizationSectionProps {
  optimization: AzureOptimizationData;
  selectedAccountIds: string[];
  isLoading: boolean;
}

const formatCurrency = (num: number): string =>
  '$' + (num != null ? num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '0.00');

const renderEmptyCard = (title: string) => (
  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4">
    <div className="flex items-center justify-between mb-3">
      <div className="flex items-center gap-2">
        <div className="w-6 h-6 rounded-md bg-[rgba(0,0,0,0.04)] flex items-center justify-center flex-shrink-0">
          <svg className="w-3.5 h-3.5 text-[rgba(0,0,0,0.3)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2" />
          </svg>
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

const renderRecsCard = (
  title: string,
  recs: Array<{ resourceId: string; currentType: string; recommendedType: string; estimatedMonthlySavings: number }> | undefined,
  linkPath: string
) => {
  if (!recs || recs.length === 0) return renderEmptyCard(title);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded-md bg-[rgba(0,0,0,0.06)] flex items-center justify-center flex-shrink-0">
            <svg className="w-3.5 h-3.5 text-[rgba(0,0,0,0.5)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
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
          {recs.slice(0, 2).map((rec, idx) => {
            const name = rec.resourceId || 'N/A';
            const change = rec.currentType && rec.recommendedType
              ? `${rec.currentType} → ${rec.recommendedType}`
              : 'See details';
            return (
              <tr key={idx} className="border-b border-[rgba(0,0,0,0.04)] last:border-0">
                <td className="py-2 pr-2 font-mono text-[11px] text-[rgba(0,0,0,0.5)] truncate max-w-[80px]" title={name}>{name}</td>
                <td className="py-2 pr-2">
                  <div className="flex items-center gap-1">
                    <span className="text-[11px] text-[rgba(0,0,0,0.5)] truncate max-w-[80px]">{change}</span>
                  </div>
                </td>
                <td className="py-2 text-right text-[11px] font-semibold text-[#158045]">{formatCurrency(rec.estimatedMonthlySavings)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {recs.length > 2 && (
        <div className="mt-2 pt-2 border-t border-[rgba(0,0,0,0.04)] text-center">
          <Link to={linkPath} className="text-[11px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors">
            +{recs.length - 2} more
          </Link>
        </div>
      )}
    </div>
  );
};

const AzureOptimizationSection: React.FC<AzureOptimizationSectionProps> = ({ optimization, selectedAccountIds, isLoading }) => {
  const totalSavings = optimization?.summary?.totalPotentialSavings || 0;
  const totalRecs =
    (optimization?.vmRecommendations?.length || 0) +
    (optimization?.diskRecommendations?.length || 0) +
    (optimization?.functionRecommendations?.length || 0);

  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5" style={{ minHeight: 320 }}>
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="h-16 bg-[rgba(0,0,0,0.04)] rounded-xl animate-pulse"></div>
            <div className="h-16 bg-[rgba(0,0,0,0.04)] rounded-xl animate-pulse"></div>
          </div>
          <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
            {[1,2,3,4].map(i => <div key={i} className="h-28 bg-[rgba(0,0,0,0.04)] rounded-xl animate-pulse"></div>)}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
      {/* Header */}
      <div className="flex items-center justify-between mb-5">
        <div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Optimization Hub</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse inline-block"></span>
            Azure Efficiency & Savings
          </p>
        </div>
      </div>

      {/* Summary row */}
      <div className="grid grid-cols-2 gap-3 mb-4">
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

        <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl p-3 flex items-center gap-3">
          <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.04)] flex items-center justify-center flex-shrink-0">
            <svg className="w-4 h-4 text-[rgba(0,0,0,0.3)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </div>
          <div>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] font-medium">Recommendations</p>
            <p className="text-[18px] font-extrabold text-[rgba(0,0,0,0.88)] leading-tight" style={{ fontVariantNumeric: 'tabular-nums' }}>
              {totalRecs}<span className="text-[11px] font-normal ml-0.5">items</span>
            </p>
          </div>
        </div>
      </div>

      {/* Sub-cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-3">
        {renderRecsCard('VM Right-Sizing', optimization.vmRecommendations, `/rightsizing?accountIds=${selectedAccountIds.join(',')}`)}
        {renderRecsCard('Disk Optimization', optimization.diskRecommendations, `/rightsizing?accountIds=${selectedAccountIds.join(',')}`)}
        {renderRecsCard('Function Apps', optimization.functionRecommendations, `/rightsizing?accountIds=${selectedAccountIds.join(',')}`)}
        {renderEmptyCard('Cost Anomalies')}
      </div>
    </div>
  );
};

export default AzureOptimizationSection;
