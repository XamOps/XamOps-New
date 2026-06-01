import React from 'react';
import { Icon } from '@iconify/react';
import type { SpotCosts, SpotSavings } from '../../hooks/useSpotAutomationData';

interface SpotAutomationKPIsProps {
  costs: SpotCosts;
  savings: SpotSavings;
  enabledASGsCount: number;
  totalASGsCount: number;
  enabledRegionsCount: number;
  currency?: string;
}

const NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '22px',
  fontWeight: 400,
  lineHeight: '22px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
};

const CLOUD_SVG = (
  <div className="absolute top-0 right-0 w-full h-full pointer-events-none opacity-[0.07]">
    <svg className="absolute -top-8 -right-12 w-32 h-32" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
      <path d="M140 70c0-15-12-27-27-27-3 0-6 1-9 2-6-14-20-24-36-24-17 0-31 10-36 24-3-1-6-2-9-2-15 0-27 12-27 27s12 27 27 27h117c15 0 27-12 27-27z"/>
    </svg>
  </div>
);

const formatCurrency = (value: number, currency = 'USD') =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value);

const SpotAutomationKPIs: React.FC<SpotAutomationKPIsProps> = ({
  costs,
  savings,
  enabledASGsCount,
  totalASGsCount,
  enabledRegionsCount,
  currency = 'USD',
}) => {
  const kpis = [
    {
      label: 'Current Cost',
      value: formatCurrency(costs.currentMonthlyCost, currency),
      sub: `${formatCurrency(costs.currentHourly, currency)}/hr`,
      icon: 'mdi:currency-usd',
      iconColor: '#8B5CF6',
      iconBg: '#8B5CF614',
    },
    {
      label: 'Actual Savings',
      value: formatCurrency(savings.actualMonthlySavings, currency),
      sub: 'Last 30 days',
      icon: 'mdi:piggy-bank-outline',
      iconColor: '#16A34A',
      iconBg: '#16A34A14',
    },
    {
      label: 'Potential Savings',
      value: formatCurrency(savings.potentialMonthlySavings, currency),
      sub: 'If all ASGs enabled',
      icon: 'mdi:trending-up',
      iconColor: '#3B82F6',
      iconBg: '#3B82F614',
    },
    {
      label: 'Auto Scaling Groups',
      value: `${enabledASGsCount}/${totalASGsCount}`,
      sub: enabledRegionsCount > 0
        ? `Across ${enabledRegionsCount} region${enabledRegionsCount > 1 ? 's' : ''}`
        : 'No regions configured',
      icon: 'mdi:layers-outline',
      iconColor: '#6366F1',
      iconBg: '#6366F114',
    },
  ];

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      {kpis.map((kpi) => (
        <div key={kpi.label} className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-3.5 relative overflow-hidden">
          {CLOUD_SVG}
          <div className="relative z-10 flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: kpi.iconBg }}>
              <Icon icon={kpi.icon} width={15} color={kpi.iconColor} />
            </div>
            <div className="min-w-0">
              <p className="text-[10px] font-bold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.45)] leading-none mb-1">{kpi.label}</p>
              <p style={NUM_STYLE}>{kpi.value}</p>
              <p className="text-[10px] text-[rgba(0,0,0,0.35)] mt-0.5 leading-none">{kpi.sub}</p>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
};

export default SpotAutomationKPIs;
