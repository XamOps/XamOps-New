import React from 'react';
import { Icon } from '@iconify/react';
import type { FlexComputeKPIs } from '../../types/flexCompute';

interface FlexComputeKPICardsProps {
  kpis: FlexComputeKPIs;
}

const NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '28px',
  fontWeight: 400,
  lineHeight: '28px',
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

const formatCurrency = (value: number): string =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value);

const formatHours = (value: number): string =>
  `${new Intl.NumberFormat('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 1 }).format(value)} hrs`;

const kpiConfig = [
  { key: 'totalSavings' as const,    label: 'Total Savings',      icon: 'mdi:piggy-bank',       iconColor: '#16A34A', iconBg: '#16A34A14', format: formatCurrency },
  { key: 'awsCost' as const,         label: 'AWS On-Demand Cost', icon: 'mdi:currency-usd',     iconColor: '#3B82F6', iconBg: '#3B82F614', format: formatCurrency },
  { key: 'totalUsageHours' as const, label: 'Total Usage Hours',  icon: 'mdi:clock-outline',    iconColor: '#8B5CF6', iconBg: '#8B5CF614', format: formatHours },
  { key: 'remainingHours' as const,  label: 'Remaining Hours',    icon: 'mdi:timer-sand',       iconColor: '#D97706', iconBg: '#D9770614', format: formatHours },
];

const FlexComputeKPICards: React.FC<FlexComputeKPICardsProps> = ({ kpis }) => {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      {kpiConfig.map((config) => (
        <div key={config.key} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
          {CLOUD_SVG}
          <div className="relative z-10">
            <div className="flex items-start justify-between mb-2">
              <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)]">{config.label}</p>
              <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: config.iconBg }}>
                <Icon icon={config.icon} width={18} color={config.iconColor} />
              </div>
            </div>
            <p style={NUM_STYLE}>{config.format(kpis[config.key])}</p>
          </div>
        </div>
      ))}
    </div>
  );
};

export default FlexComputeKPICards;
