/**
 * GCP FinOps KPI Section Component
 *
 * Displays key performance indicators for GCP spend
 */

import React, { useMemo } from 'react';
import type { GcpFinOpsKPIs } from '../../types/gcpFinops';

interface GcpFinOpsKPISectionProps {
  kpis: GcpFinOpsKPIs;
}

interface KPICard {
  label: string;
  value: string;
  icon: string;
  color: { bg: string; text: string };
  trendIcon: string;
  trendColor: string;
  trendText: string;
}

const formatCurrency = (value: number | null | undefined): string => {
  if (value === null || value === undefined || isNaN(value)) return '₹0.00';
  return (value || 0).toLocaleString('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};

const GcpFinOpsKPISection: React.FC<GcpFinOpsKPISectionProps> = ({ kpis }) => {
  const kpiCards = useMemo((): KPICard[] => {
    const mtdSpend = kpis.monthToDateSpend || 0;
    const lastMonthSpend = kpis.lastMonthSpend || 0;
    const forecast = kpis.forecastedSpend || 0;
    const potentialSavings = kpis.potentialSavings || 0;

    const mtdTrend = lastMonthSpend > 0 ? ((mtdSpend - lastMonthSpend) / lastMonthSpend) * 100 : 0;
    const forecastTrend = lastMonthSpend > 0 ? ((forecast - lastMonthSpend) / lastMonthSpend) * 100 : 0;

    return [
      {
        label: 'Month-to-Date Spend',
        value: formatCurrency(mtdSpend),
        icon: 'fa-indian-rupee-sign',
        color: { bg: 'bg-blue-100', text: 'text-blue-500' },
        trendIcon: mtdTrend >= 0 ? 'fa-arrow-up' : 'fa-arrow-down',
        trendColor: mtdTrend >= 0 ? 'text-red-500' : 'text-green-500',
        trendText: lastMonthSpend > 0 ? `${Math.abs(mtdTrend).toFixed(1)}% vs last month` : '',
      },
      {
        label: 'Forecasted Spend',
        value: formatCurrency(forecast),
        icon: 'fa-chart-line',
        color: { bg: 'bg-yellow-100', text: 'text-yellow-500' },
        trendIcon: forecastTrend >= 0 ? 'fa-arrow-up' : 'fa-arrow-down',
        trendColor: forecastTrend >= 0 ? 'text-red-500' : 'text-green-500',
        trendText: lastMonthSpend > 0 ? `${Math.abs(forecastTrend).toFixed(1)}% vs last month` : '',
      },
      {
        label: 'Last Month Spend',
        value: formatCurrency(lastMonthSpend),
        icon: 'fa-calendar-alt',
        color: { bg: 'bg-gray-100', text: 'text-gray-500' },
        trendIcon: '',
        trendColor: '',
        trendText: '',
      },
      {
        label: 'Potential Savings',
        value: formatCurrency(potentialSavings),
        icon: 'fa-piggy-bank',
        color: { bg: 'bg-green-100', text: 'text-green-500' },
        trendIcon: '',
        trendColor: '',
        trendText: '',
      },
    ];
  }, [kpis]);

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
      {kpiCards.map((kpi, index) => (
        <div
          key={index}
          className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-6 shadow-sm hover:shadow-xl hover:-translate-y-1 transition-all duration-300 relative overflow-hidden group fade-in"
        >
          <div className="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-blue-500 to-green-500 transform scale-x-0 group-hover:scale-x-100 transition-transform duration-300 origin-left"></div>
          <div className="flex justify-between items-start mb-4">
            <h3 className="text-gray-600 text-sm font-medium">{kpi.label}</h3>
            <div className={`p-3 rounded-xl ${kpi.color.bg}`}>
              <i className={`fas ${kpi.icon} text-xl ${kpi.color.text}`}></i>
            </div>
          </div>
          <p className="text-3xl font-bold text-gray-900 mb-2">{kpi.value}</p>
          {kpi.trendText && (
            <div
              className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${
                kpi.trendColor.includes('red')
                  ? 'bg-red-50 text-red-600 border border-red-100'
                  : 'bg-green-50 text-green-600 border border-green-100'
              }`}
            >
              <i className={`fas ${kpi.trendIcon} mr-1`}></i>
              <span>{kpi.trendText}</span>
            </div>
          )}
        </div>
      ))}
    </div>
  );
};

export default GcpFinOpsKPISection;
