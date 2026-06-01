import React from 'react';
import { Icon } from '@iconify/react';
import type { GcpFinOpsKPIs } from '../../types/gcpFinops';

interface GcpFinOpsKPISectionProps {
  kpis: GcpFinOpsKPIs;
}

const formatCurrency = (value: number | null | undefined): string =>
  '$' + (value || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

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
  value: string;
  icon: string;
  iconColor: string;
  iconBg: string;
  changePercent?: number | null;
  changeLabel?: string;
  isSavings?: boolean;
}

const KPICard: React.FC<KPICardProps> = ({ label, value, icon, iconColor, iconBg, changePercent, changeLabel, isSavings }) => {
  const split = splitValue(value);
  const hasChange = changePercent != null && !isNaN(changePercent);
  const isGood = isSavings ? true : (changePercent ?? 0) < 0;
  const badgeText = hasChange ? `${(changePercent ?? 0) >= 0 ? '+' : ''}${Math.abs(changePercent ?? 0).toFixed(1)}%` : '';

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
      {CLOUD_SVG}
      <div className="relative z-10">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">
          {label}
        </p>
        <div className="flex items-end justify-between mb-3">
          <p className="leading-8 flex items-baseline gap-0.5">
            <span style={NUM_STYLE}>{split.main}</span>
            {split.cents && <span style={CENTS_STYLE}>{split.cents}</span>}
          </p>
          <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mb-0.5" style={{ backgroundColor: iconBg }}>
            <Icon icon={icon} width={18} color={iconColor} />
          </div>
        </div>
        {hasChange && (
          <div className="flex items-center gap-2">
            <span className={`inline-flex items-center gap-0.5 text-[11px] font-bold ${isGood ? 'text-[#158045]' : 'text-[#DC2626]'}`}>
              <svg className="w-2.5 h-2.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3}
                  d={isGood ? "M13 17h8m0 0v-8m0 8l-8-8-4 4-6-6" : "M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"} />
              </svg>
              {badgeText}
            </span>
            <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{changeLabel}</span>
          </div>
        )}
        {!hasChange && changeLabel && (
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{changeLabel}</span>
        )}
      </div>
    </div>
  );
};

const GcpFinOpsKPISection: React.FC<GcpFinOpsKPISectionProps> = ({ kpis }) => {
  const mtdSpend = kpis.monthToDateSpend || 0;
  const lastMonthSpend = kpis.lastMonthSpend || 0;
  const forecast = kpis.forecastedSpend || 0;
  const potentialSavings = kpis.potentialSavings || 0;

  const mtdChange = lastMonthSpend > 0 ? ((mtdSpend - lastMonthSpend) / lastMonthSpend) * 100 : null;
  const forecastChange = lastMonthSpend > 0 ? ((forecast - lastMonthSpend) / lastMonthSpend) * 100 : null;

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      <KPICard label="Month-to-Date Spend" value={formatCurrency(mtdSpend)} icon="mdi:currency-usd" iconColor="#4285F4" iconBg="#4285F414" changePercent={mtdChange} changeLabel="vs last month" />
      <KPICard label="Forecasted Spend" value={formatCurrency(forecast)} icon="mdi:chart-line" iconColor="#FBBC05" iconBg="#FBBC0514" changePercent={forecastChange} changeLabel="vs last month" />
      <KPICard label="Last Month Spend" value={formatCurrency(lastMonthSpend)} icon="mdi:calendar-month" iconColor="#6B7280" iconBg="#6B728014" changeLabel="prior period" />
      <KPICard label="Potential Savings" value={formatCurrency(potentialSavings)} icon="mdi:piggy-bank" iconColor="#34A853" iconBg="#34A85314" changeLabel="available to reclaim" isSavings />
    </div>
  );
};

export default GcpFinOpsKPISection;
