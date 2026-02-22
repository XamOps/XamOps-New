import React from 'react';
import { AzureFinOpsReportData } from '../../types/azureFinops';
import { useTheme } from '../../contexts/ThemeContext';
import clsx from 'clsx';

interface AzureFinOpsKPICardsProps {
  reportData: AzureFinOpsReportData | null;
  isLoading: boolean;
}

const formatCurrency = (value: number): string =>
  value.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

const AzureFinOpsKPICards: React.FC<AzureFinOpsKPICardsProps> = ({ reportData, isLoading }) => {
  const { theme } = useTheme();

  const momChange = reportData?.kpis?.momChange ?? 0;
  const momPositive = momChange > 0;
  const momZero = momChange === 0;

  const cards = [
    {
      label: 'Month-to-Date Spend',
      value: isLoading ? '—' : formatCurrency(reportData?.kpis?.totalSpend ?? 0),
      icon: 'fa-dollar-sign',
      gradient: 'from-blue-500 to-indigo-600',
      sub: 'Current month actual spend',
    },
    {
      label: 'Forecasted Spend',
      value: isLoading ? '—' : formatCurrency(reportData?.kpis?.forecastedSpend ?? 0),
      icon: 'fa-chart-line',
      gradient: 'from-amber-500 to-orange-500',
      sub: 'ML-powered month-end estimate',
    },
    {
      label: 'MoM Change',
      value: isLoading
        ? '—'
        : momZero
        ? '0.0%'
        : `${momPositive ? '+' : ''}${momChange.toFixed(1)}%`,
      icon: momPositive ? 'fa-arrow-trend-up' : 'fa-arrow-trend-down',
      gradient: momPositive ? 'from-red-500 to-rose-600' : 'from-emerald-500 to-green-600',
      sub: 'Compared to last month',
      valueColor: momPositive
        ? 'text-red-600'
        : momZero
        ? undefined
        : 'text-emerald-600',
    },
    {
      label: 'Potential Savings',
      value: isLoading ? '—' : formatCurrency(reportData?.totalPotentialSavings ?? 0),
      icon: 'fa-piggy-bank',
      gradient: 'from-emerald-500 to-teal-600',
      sub: 'Rightsizing + waste opportunities',
    },
  ];

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="glass-card rounded-xl p-5 animate-pulse">
            <div className="h-4 bg-gray-200 rounded w-1/2 mb-3" />
            <div className="h-8 bg-gray-200 rounded w-3/4 mb-2" />
            <div className="h-3 bg-gray-200 rounded w-2/3" />
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {cards.map((card, idx) => (
        <div
          key={idx}
          className={clsx(
            'glass-card rounded-xl p-5 hover-glow transition-all',
            theme === 'dark' ? 'bg-slate-800/50' : ''
          )}
        >
          <div className="flex items-start justify-between mb-3">
            <p className={clsx('text-xs font-medium uppercase tracking-wide', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
              {card.label}
            </p>
            <div className={clsx('w-9 h-9 bg-gradient-to-br rounded-lg flex items-center justify-center shadow', card.gradient)}>
              <i className={clsx('fas text-white text-sm', card.icon)} />
            </div>
          </div>
          <p className={clsx(
            'text-2xl font-extrabold mb-1',
            card.valueColor ?? (theme === 'dark' ? 'text-slate-100' : 'text-gray-900')
          )}>
            {card.value}
          </p>
          <p className={clsx('text-xs', theme === 'dark' ? 'text-slate-500' : 'text-gray-400')}>
            {card.sub}
          </p>
        </div>
      ))}
    </div>
  );
};

export default AzureFinOpsKPICards;
