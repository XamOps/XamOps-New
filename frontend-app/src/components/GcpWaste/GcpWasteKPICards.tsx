import React from 'react';
import { Icon } from '@iconify/react';
import type { GcpWasteResource } from '../../types/gcpWaste';

interface GcpWasteKPICardsProps {
  wasteItems: GcpWasteResource[];
  isLoading: boolean;
}

const NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '32px',
  fontWeight: 400,
  lineHeight: '32px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
};

const CENTS_STYLE: React.CSSProperties = {
  fontFamily: "'Inter', sans-serif",
  fontSize: '14px',
  fontWeight: 400,
  lineHeight: '20px',
  color: 'rgb(10,10,10)',
};

const CLOUD_SVG = (
  <div className="absolute top-0 right-0 w-full h-full pointer-events-none opacity-[0.07]">
    <svg className="absolute -top-8 -right-12 w-32 h-32" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
      <path d="M140 70c0-15-12-27-27-27-3 0-6 1-9 2-6-14-20-24-36-24-17 0-31 10-36 24-3-1-6-2-9-2-15 0-27 12-27 27s12 27 27 27h117c15 0 27-12 27-27z"/>
    </svg>
    <svg className="absolute top-16 right-2 w-24 h-24" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
      <path d="M120 60c0-10-8-18-18-18-2 0-4 0-5 1-4-8-13-14-23-14s-19 6-23 14c-1-1-3-1-5-1-10 0-18 8-18 18s8 18 18 18h74c10 0 18-8 18-18z"/>
    </svg>
  </div>
);

const splitValue = (str: string) => {
  const dot = str.lastIndexOf('.');
  return dot === -1 ? { main: str, cents: '' } : { main: str.slice(0, dot), cents: str.slice(dot) };
};

const GcpWasteKPICards: React.FC<GcpWasteKPICardsProps> = ({ wasteItems, isLoading }) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-[110px] animate-pulse">
            <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
            <div className="h-7 w-28 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
            <div className="h-2 w-16 bg-[rgba(0,0,0,0.04)] rounded" />
          </div>
        ))}
      </div>
    );
  }

  const totalSavings = wasteItems.reduce((sum, item) => sum + (item.monthlySavings || 0), 0);
  const totalCount = wasteItems.length;

  const topCategory = (() => {
    if (!wasteItems.length) return '—';
    const groups: Record<string, number> = {};
    for (const item of wasteItems) {
      const t = item.type || 'Other';
      groups[t] = (groups[t] || 0) + (item.monthlySavings || 0);
    }
    return Object.entries(groups).sort((a, b) => b[1] - a[1])[0]?.[0] ?? '—';
  })();

  const savingsStr = `$${totalSavings.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  const savingsSplit = splitValue(savingsStr);

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
      {/* Potential Savings */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
        {CLOUD_SVG}
        <div className="relative z-10">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">Potential Savings</p>
          <div className="flex items-end justify-between mb-3">
            <p className="leading-8 flex items-baseline gap-0.5">
              <span style={NUM_STYLE}>{savingsSplit.main}</span>
              {savingsSplit.cents && <span style={CENTS_STYLE}>{savingsSplit.cents}</span>}
            </p>
            <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mb-0.5" style={{ backgroundColor: '#16A34A14' }}>
              <Icon icon="mdi:piggy-bank" width={18} color="#16A34A" />
            </div>
          </div>
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">per month if cleaned up</span>
        </div>
      </div>

      {/* Wasted Resources */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
        {CLOUD_SVG}
        <div className="relative z-10">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">Wasted Resources</p>
          <div className="flex items-end justify-between mb-3">
            <p className="leading-8">
              <span style={NUM_STYLE}>{totalCount}</span>
            </p>
            <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mb-0.5" style={{ backgroundColor: '#DC262614' }}>
              <Icon icon="mdi:alert-circle-outline" width={18} color="#DC2626" />
            </div>
          </div>
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">items detected</span>
        </div>
      </div>

      {/* Top Waste Category */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
        {CLOUD_SVG}
        <div className="relative z-10">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">Top Waste Category</p>
          <div className="flex items-end justify-between mb-3">
            <p className="leading-8 truncate pr-2" style={{ ...NUM_STYLE, fontSize: topCategory.length > 14 ? '18px' : '32px' }}>{topCategory}</p>
            <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mb-0.5" style={{ backgroundColor: '#F5922814' }}>
              <Icon icon="mdi:fire" width={18} color="#F59228" />
            </div>
          </div>
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">highest savings category</span>
        </div>
      </div>
    </div>
  );
};

export default GcpWasteKPICards;
