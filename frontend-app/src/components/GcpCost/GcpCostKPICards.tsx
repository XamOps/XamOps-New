import React, { useMemo } from 'react';
import type { GcpCostDataItem } from '../../types/gcpCost';

interface GcpCostKPICardsProps {
  costData: GcpCostDataItem[];
  isLoading: boolean;
}

const GcpCostKPICards: React.FC<GcpCostKPICardsProps> = ({ costData, isLoading }) => {
  const formatCurrency = (value: number): string =>
    '$' + (value || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  const formatSplit = (str: string) => {
    const dot = str.lastIndexOf('.');
    return dot === -1 ? { main: str, cents: '' } : { main: str.slice(0, dot), cents: str.slice(dot) };
  };

  const metrics = useMemo(() => {
    const totalCost = costData.reduce((sum, item) => sum + item.amount, 0);
    const sortedData = [...costData].sort((a, b) => b.amount - a.amount);
    const topSpender = sortedData.length > 0 ? sortedData[0] : { name: 'N/A', amount: 0 };
    return { totalCost, categoriesCount: costData.length, topSpender };
  }, [costData]);

  const CloudSVG = () => (
    <div className="absolute top-0 right-0 w-full h-full pointer-events-none opacity-[0.07]">
      <svg className="absolute -top-8 -right-12 w-32 h-32" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
        <path d="M140 70c0-15-12-27-27-27-3 0-6 1-9 2-6-14-20-24-36-24-17 0-31 10-36 24-3-1-6-2-9-2-15 0-27 12-27 27s12 27 27 27h117c15 0 27-12 27-27z"/>
      </svg>
    </div>
  );

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
        {[1, 2, 3].map(i => (
          <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-[110px] animate-pulse">
            <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] rounded mb-3"></div>
            <div className="h-7 w-28 bg-[rgba(0,0,0,0.06)] rounded mb-3"></div>
            <div className="h-2 w-16 bg-[rgba(0,0,0,0.04)] rounded"></div>
          </div>
        ))}
      </div>
    );
  }

  const totalSplit = formatSplit(formatCurrency(metrics.totalCost));
  const topSplit = formatSplit(formatCurrency(metrics.topSpender.amount));

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">

      {/* Total Cost */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
        <CloudSVG />
        <div className="relative z-10">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)] mb-2">Total Cost</p>
          <p className="leading-8 mb-3 flex items-baseline gap-0.5">
            <span style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
              {totalSplit.main}
            </span>
            {totalSplit.cents && (
              <span style={{ fontFamily: "'Inter',sans-serif", fontSize: '14px', fontWeight: 400, color: 'rgb(10,10,10)' }}>
                {totalSplit.cents}
              </span>
            )}
          </p>
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">Last 90 days</span>
        </div>
      </div>

      {/* Categories */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
        <CloudSVG />
        <div className="relative z-10">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)] mb-2">Categories</p>
          <p className="leading-8 mb-3">
            <span style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
              {metrics.categoriesCount}
            </span>
          </p>
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">Grouped by service</span>
        </div>
      </div>

      {/* Top Spender */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
        <CloudSVG />
        <div className="relative z-10">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)] mb-2">Top Spender</p>
          <p className="leading-8 mb-3 flex items-baseline gap-0.5">
            <span style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
              {topSplit.main}
            </span>
            {topSplit.cents && (
              <span style={{ fontFamily: "'Inter',sans-serif", fontSize: '14px', fontWeight: 400, color: 'rgb(10,10,10)' }}>
                {topSplit.cents}
              </span>
            )}
          </p>
          <span className="text-[11px] text-[rgba(0,0,0,0.35)] truncate block">{metrics.topSpender.name}</span>
        </div>
      </div>

    </div>
  );
};

export default GcpCostKPICards;
