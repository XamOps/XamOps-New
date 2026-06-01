import React from 'react';
import { Icon } from '@iconify/react';
import { RispCoverageData } from '../../hooks/useRispCoverage';

interface RispCoverageKPIsProps {
  data: RispCoverageData;
  accountId: string | null;
}

const RispCoverageKPIs: React.FC<RispCoverageKPIsProps> = ({ data, accountId }) => {
  const formatTimeAgo = (dateString: string | null): string => {
    if (!dateString) return 'Never';

    const date = new Date(dateString);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffHours = Math.floor(diffMs / (1000 * 60 * 60));

    if (diffHours < 1) return 'Just now';
    if (diffHours === 1) return '1 hour ago';
    if (diffHours < 24) return `${diffHours} hours ago`;

    const diffDays = Math.floor(diffHours / 24);
    if (diffDays === 1) return '1 day ago';
    return `${diffDays} days ago`;
  };

  const commitmentWaste = 100 - data.totalRiUtilizationPercent;
  const shouldUseSpot = data.totalRiUtilizationPercent >= 90 && data.totalSpUtilizationPercent >= 90;

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-6">
      {/* Last Checked */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-[rgba(0,0,0,0.06)] hover:shadow-md transition-shadow relative overflow-hidden">
        <div className="text-center">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] mb-2">Last Checked</p>
          <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{formatTimeAgo(data.collectedAt)}</span>
          <p className="text-xs text-[rgba(0,0,0,0.4)] mt-1">Account: {accountId || 'N/A'}</p>
        </div>
        <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
          <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
        </svg>
      </div>

      {/* RI Coverage */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-[rgba(0,0,0,0.06)] hover:shadow-md transition-shadow relative overflow-hidden">
        <div className="text-center">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] mb-2">RI Coverage</p>
          <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', fontVariantNumeric: 'tabular-nums' }} className={data.totalRiUtilizationPercent >= 70 ? 'text-emerald-600' : 'text-red-500'}>
            {data.totalRiUtilizationPercent.toFixed(1)}%
          </span>
          <p className="text-xs text-[rgba(0,0,0,0.4)] mt-1">Utilization: 0.0%</p>
        </div>
        <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
          <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
        </svg>
      </div>

      {/* SP Coverage */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-[rgba(0,0,0,0.06)] hover:shadow-md transition-shadow relative overflow-hidden">
        <div className="text-center">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] mb-2">SP Coverage</p>
          <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', fontVariantNumeric: 'tabular-nums' }} className={data.totalSpUtilizationPercent >= 70 ? 'text-emerald-600' : 'text-red-500'}>
            {data.totalSpUtilizationPercent.toFixed(1)}%
          </span>
          <p className="text-xs text-[rgba(0,0,0,0.4)] mt-1">Utilization: 0.0%</p>
        </div>
        <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
          <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
        </svg>
      </div>

      {/* Commitment Waste */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-[rgba(0,0,0,0.06)] relative overflow-hidden hover:shadow-md transition-shadow">
        <div className="absolute top-0 right-0 p-2 opacity-10">
          <Icon icon="mdi:trash-can-outline" className="text-[rgba(0,0,0,0.5)]" width={60} />
        </div>
        <div className="text-center relative z-10">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] mb-2">Commitment Waste</p>
          <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', fontVariantNumeric: 'tabular-nums' }} className={commitmentWaste > 10 ? 'text-red-500' : 'text-[rgba(0,0,0,0.7)]'}>
            {commitmentWaste.toFixed(1)}%
          </span>
          <p className="text-xs text-[rgba(0,0,0,0.4)] mt-1">Unused RIs</p>

          <div className="w-full bg-[rgba(0,0,0,0.06)] rounded-full h-1.5 mt-3">
            <div
              className={`h-1.5 rounded-full transition-all duration-500 ${commitmentWaste > 10 ? 'bg-red-500' : 'bg-emerald-500'}`}
              style={{ width: `${Math.min(commitmentWaste, 100)}%` }}
            ></div>
          </div>
        </div>
        <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
          <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
        </svg>
      </div>

      {/* AutoSpotting Strategy */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-[rgba(0,0,0,0.06)] hover:shadow-md transition-shadow relative overflow-hidden">
        <div className="text-center">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] mb-2">AutoSpotting Strategy</p>
          <h3 className={`text-2xl font-bold ${shouldUseSpot ? 'text-emerald-500' : 'text-orange-500'}`}>
            {shouldUseSpot ? 'Use Spot' : 'Use On-Demand'}
          </h3>
          <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-2 leading-tight">
            {shouldUseSpot
              ? 'All commitments fully utilized - will use Spot for maximum savings'
              : 'Commitments available - will use On-Demand to maximize RI/SP utilization'}
          </p>
        </div>
        <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
          <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
        </svg>
      </div>
    </div>
  );
};

export default RispCoverageKPIs;
