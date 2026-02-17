/**
 * FlexCompute KPI Cards Component
 *
 * Displays 4 key metrics: Total Savings, Total Cost, Usage Hours, Remaining Hours
 */

import React from 'react';
import type { FlexComputeKPIs } from '../../types/flexCompute';

interface FlexComputeKPICardsProps {
  kpis: FlexComputeKPIs;
}

const formatCurrency = (value: number): string => {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value);
};

const formatHours = (value: number): string => {
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 1,
  }).format(value);
};

const kpiConfig = [
  {
    key: 'totalSavings' as const,
    label: 'Total Savings',
    icon: 'fas fa-piggy-bank',
    gradient: 'from-green-500 to-emerald-600',
    bgColor: 'bg-green-50',
    textColor: 'text-green-700',
    format: formatCurrency,
  },
  {
    key: 'awsCost' as const,
    label: 'AWS On-Demand Cost',
    icon: 'fas fa-dollar-sign',
    gradient: 'from-blue-500 to-indigo-600',
    bgColor: 'bg-blue-50',
    textColor: 'text-blue-700',
    format: formatCurrency,
  },
  {
    key: 'totalUsageHours' as const,
    label: 'Total Usage Hours',
    icon: 'fas fa-clock',
    gradient: 'from-purple-500 to-violet-600',
    bgColor: 'bg-purple-50',
    textColor: 'text-purple-700',
    format: (v: number) => `${formatHours(v)} hrs`,
  },
  {
    key: 'remainingHours' as const,
    label: 'Remaining Hours',
    icon: 'fas fa-hourglass-half',
    gradient: 'from-amber-500 to-orange-600',
    bgColor: 'bg-amber-50',
    textColor: 'text-amber-700',
    format: (v: number) => `${formatHours(v)} hrs`,
  },
];

const FlexComputeKPICards: React.FC<FlexComputeKPICardsProps> = ({ kpis }) => {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
      {kpiConfig.map((config) => (
        <div
          key={config.key}
          className="bg-white rounded-2xl shadow-sm border border-gray-200 p-6 hover:shadow-md transition-shadow"
        >
          <div className="flex items-center justify-between mb-4">
            <span className={`text-xs font-semibold uppercase tracking-wide ${config.textColor}`}>
              {config.label}
            </span>
            <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${config.gradient} flex items-center justify-center shadow-sm`}>
              <i className={`${config.icon} text-white text-sm`}></i>
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">
            {config.format(kpis[config.key])}
          </p>
        </div>
      ))}
    </div>
  );
};

export default FlexComputeKPICards;
