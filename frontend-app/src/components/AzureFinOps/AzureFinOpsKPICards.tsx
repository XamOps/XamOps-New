import React, { useRef, useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import { AzureFinOpsReportData } from '../../types/azureFinops';

interface AzureFinOpsKPICardsProps {
  reportData: AzureFinOpsReportData | null;
  isLoading: boolean;
}

const formatCurrency = (value: number): string =>
  '₹' + (value || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

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
  /**
   * Null means the backend could not measure this figure.
   *
   * Rendering null as 0 is what made a subscription with no ingested billing data look like a
   * subscription that had spent nothing, so this card shows an explicit unavailable state instead.
   */
  rawValue: number | null;
  icon: string;
  iconColor: string;
  iconBg: string;
  changePercent?: number | null;
  changeLabel?: string;
  isSavings?: boolean;
  index: number;
}

const UNAVAILABLE_STYLE: React.CSSProperties = {
  fontFamily: "'Inter', sans-serif",
  fontSize: '15px',
  fontWeight: 500,
  lineHeight: '32px',
  color: 'rgba(0,0,0,0.45)',
};

const KPICard: React.FC<KPICardProps> = ({ label, rawValue, icon, iconColor, iconBg, changePercent, changeLabel, isSavings, index }) => {
  const isUnavailable = rawValue == null;
  const numericValue = rawValue ?? 0;
  const animRef = useRef<number | undefined>(undefined);
  const prevRawValue = useRef(numericValue);
  const [animCount, setAnimCount] = useState(numericValue);

  useEffect(() => {
    if (numericValue === prevRawValue.current) return;
    const prev = prevRawValue.current;
    prevRawValue.current = numericValue;
    if (animRef.current) cancelAnimationFrame(animRef.current);
    if (numericValue === 0) { setAnimCount(0); return; }
    const duration = 1050;
    const startTime = performance.now();
    const step = (now: number) => {
      const t = Math.min((now - startTime) / duration, 1);
      const eased = 1 - Math.pow(1 - t, 3);
      setAnimCount(prev + eased * (numericValue - prev));
      if (t < 1) animRef.current = requestAnimationFrame(step);
      else setAnimCount(numericValue);
    };
    animRef.current = requestAnimationFrame(step);
    return () => { if (animRef.current) cancelAnimationFrame(animRef.current); };
  }, [numericValue]);

  const split = splitValue(formatCurrency(animCount));
  const hasChange = !isUnavailable && changePercent != null && !isNaN(changePercent)
    && isFinite(changePercent) && changePercent !== 0;
  const isGood = isSavings ? true : (changePercent ?? 0) < 0;
  const badgeText = hasChange ? `${(changePercent ?? 0) >= 0 ? '+' : ''}${Math.abs(changePercent ?? 0).toFixed(1)}%` : '';

  return (
    <div
      className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift"
      style={{ animation: `fadeInUp 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${0.05 + index * 0.09}s both` }}
    >
      {CLOUD_SVG}
      <div className="relative z-10">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">{label}</p>
        <div className="flex items-end justify-between mb-3">
          {isUnavailable ? (
            <p className="leading-8" style={UNAVAILABLE_STYLE}>Not available</p>
          ) : (
            <p className="leading-8 flex items-baseline gap-0.5">
              <span style={NUM_STYLE}>{split.main}</span>
              {split.cents && <span style={CENTS_STYLE}>{split.cents}</span>}
            </p>
          )}
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

/* MoM Change card — shows a % string, not a currency. No count-up needed, just stagger + hover. */
interface KPICardRawProps {
  label: string;
  displayValue: string;
  valueColor?: string;
  icon: string;
  iconColor: string;
  iconBg: string;
  changeLabel?: string;
  index: number;
}

const KPICardRaw: React.FC<KPICardRawProps> = ({ label, displayValue, valueColor, icon, iconColor, iconBg, changeLabel, index }) => (
  <div
    className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift"
    style={{ animation: `fadeInUp 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${0.05 + index * 0.09}s both` }}
  >
    {CLOUD_SVG}
    <div className="relative z-10">
      <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">{label}</p>
      <div className="flex items-end justify-between mb-3">
        <p className="leading-8" style={{ ...NUM_STYLE, color: valueColor || 'rgb(10,10,10)' }}>{displayValue}</p>
        <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mb-0.5" style={{ backgroundColor: iconBg }}>
          <Icon icon={icon} width={18} color={iconColor} />
        </div>
      </div>
      {changeLabel && <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{changeLabel}</span>}
    </div>
  </div>
);

const AzureFinOpsKPICards: React.FC<AzureFinOpsKPICardsProps> = ({ reportData, isLoading }) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-[110px] animate-pulse">
            <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
            <div className="h-7 w-28 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
            <div className="h-2 w-16 bg-[rgba(0,0,0,0.04)] rounded" />
          </div>
        ))}
      </div>
    );
  }

  // Deliberately not defaulted to 0. The backend sends null when the billing ingestion has not
  // produced a figure yet, and "we don't know" must not render as "zero".
  const totalSpend = reportData?.kpis?.totalSpend ?? null;
  const forecastedSpend = reportData?.kpis?.forecastedSpend ?? null;
  const momChange = reportData?.kpis?.momChange ?? null;
  const totalPotentialSavings = reportData?.totalPotentialSavings ?? 0;
  const kpisUnavailableReason = reportData?.kpisUnavailableReason ?? null;

  const momUnknown = momChange == null;
  const momDisplay = momUnknown
    ? '—'
    : `${momChange >= 0 ? '+' : ''}${momChange.toFixed(1)}%`;
  const momColor = momUnknown
    ? 'rgba(0,0,0,0.45)'
    : momChange === 0 ? 'rgb(10,10,10)' : momChange > 0 ? '#DC2626' : '#16A34A';

  return (
    <>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <KPICard    index={0} label="Month-to-Date Spend" rawValue={totalSpend}           icon="mdi:currency-usd" iconColor="#0078D4" iconBg="#0078D414" changeLabel="current month actual" />
        <KPICard    index={1} label="Forecasted Spend"     rawValue={forecastedSpend}      icon="mdi:chart-line"   iconColor="#D97706" iconBg="#FFB90014" changeLabel="ML-powered estimate" />
        <KPICardRaw index={2} label="MoM Change" displayValue={momDisplay} icon={momUnknown ? 'mdi:help-circle-outline' : momChange >= 0 ? 'mdi:trending-up' : 'mdi:trending-down'} iconColor={momUnknown || momChange === 0 ? '#6B7280' : momChange > 0 ? '#DC2626' : '#16A34A'} iconBg={momUnknown || momChange === 0 ? 'rgba(0,0,0,0.04)' : momChange > 0 ? '#FEE2E214' : '#DCFCE714'} valueColor={momColor} changeLabel={momUnknown ? 'not enough history' : 'vs last month'} />
        <KPICard    index={3} label="Potential Savings"    rawValue={totalPotentialSavings} icon="mdi:piggy-bank"  iconColor="#16A34A" iconBg="#16A34A14" changeLabel="available to reclaim" isSavings />
      </div>

      {kpisUnavailableReason && (
        <div className="mt-3 flex items-start gap-2 rounded-lg border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.02)] px-3 py-2">
          <Icon icon="mdi:information-outline" width={15} className="mt-0.5 flex-shrink-0" color="rgba(0,0,0,0.45)" />
          <p className="text-[11px] leading-[16px] text-[rgba(0,0,0,0.55)]">{kpisUnavailableReason}</p>
        </div>
      )}
    </>
  );
};

export default AzureFinOpsKPICards;
