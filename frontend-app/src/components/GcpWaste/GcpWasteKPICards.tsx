import React, { useRef, useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import type { GcpWasteResource } from '../../types/gcpWaste';

interface GcpWasteKPICardsProps {
  wasteItems: GcpWasteResource[];
  isLoading: boolean;
}

const formatCurrency = (value: number): string =>
  '₹' + value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const splitValue = (str: string) => {
  const dot = str.lastIndexOf('.');
  return dot === -1 ? { main: str, cents: '' } : { main: str.slice(0, dot), cents: str.slice(dot) };
};

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

interface KPICardProps {
  label: string;
  rawValue: number;
  subtitle: string;
  icon: string;
  iconColor: string;
  iconBg: string;
  index: number;
  isCount?: boolean;
}

const KPICard: React.FC<KPICardProps> = ({ label, rawValue, subtitle, icon, iconColor, iconBg, index, isCount }) => {
  const animRef = useRef<number | undefined>(undefined);
  const prevRawValue = useRef(rawValue);
  const [animCount, setAnimCount] = useState(rawValue);

  useEffect(() => {
    if (rawValue === prevRawValue.current) return;
    const prev = prevRawValue.current;
    prevRawValue.current = rawValue;
    if (animRef.current) cancelAnimationFrame(animRef.current);
    if (rawValue === 0) { setAnimCount(0); return; }
    const duration = 1050;
    const startTime = performance.now();
    const step = (now: number) => {
      const t = Math.min((now - startTime) / duration, 1);
      const eased = 1 - Math.pow(1 - t, 3);
      setAnimCount(prev + eased * (rawValue - prev));
      if (t < 1) animRef.current = requestAnimationFrame(step);
      else setAnimCount(rawValue);
    };
    animRef.current = requestAnimationFrame(step);
    return () => { if (animRef.current) cancelAnimationFrame(animRef.current); };
  }, [rawValue]);

  const split = isCount
    ? { main: String(Math.round(animCount)), cents: '' }
    : splitValue(formatCurrency(animCount));

  return (
    <div
      className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift"
      style={{ animation: `fadeInUp 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${0.05 + index * 0.09}s both` }}
    >
      {CLOUD_SVG}
      <div className="relative z-10">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">{label}</p>
        <div className="flex items-end justify-between mb-3">
          <p className="leading-8 flex items-baseline gap-0.5">
            <span style={NUM_STYLE}>{split.main}</span>
            {split.cents && <span style={CENTS_STYLE}>{split.cents}</span>}
          </p>
          <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mb-0.5" style={{ backgroundColor: iconBg }}>
            <Icon icon={icon} width={18} color={iconColor} />
          </div>
        </div>
        <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{subtitle}</span>
      </div>
    </div>
  );
};

interface KPICardTextProps {
  label: string;
  displayValue: string;
  subtitle: string;
  icon: string;
  iconColor: string;
  iconBg: string;
  index: number;
}

const KPICardText: React.FC<KPICardTextProps> = ({ label, displayValue, subtitle, icon, iconColor, iconBg, index }) => (
  <div
    className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift"
    style={{ animation: `fadeInUp 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${0.05 + index * 0.09}s both` }}
  >
    {CLOUD_SVG}
    <div className="relative z-10">
      <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">{label}</p>
      <div className="flex items-end justify-between mb-3">
        <p
          className="leading-8 truncate pr-2"
          style={{ ...NUM_STYLE, fontSize: displayValue.length > 14 ? '18px' : '32px' }}
        >
          {displayValue}
        </p>
        <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mb-0.5" style={{ backgroundColor: iconBg }}>
          <Icon icon={icon} width={18} color={iconColor} />
        </div>
      </div>
      <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{subtitle}</span>
    </div>
  </div>
);

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

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
      <KPICard
        index={0}
        label="Potential Savings"
        rawValue={totalSavings}
        subtitle="per month if cleaned up"
        icon="mdi:piggy-bank"
        iconColor="#16A34A"
        iconBg="#16A34A14"
      />
      <KPICard
        index={1}
        label="Wasted Resources"
        rawValue={totalCount}
        subtitle="items detected"
        icon="mdi:alert-circle-outline"
        iconColor="#DC2626"
        iconBg="#DC262614"
        isCount
      />
      <KPICardText
        index={2}
        label="Top Waste Category"
        displayValue={topCategory}
        subtitle="highest savings category"
        icon="mdi:fire"
        iconColor="#F59228"
        iconBg="#F5922814"
      />
    </div>
  );
};

export default GcpWasteKPICards;
