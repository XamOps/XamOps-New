import React from 'react';
import type { AzureCostKPIs } from '../../hooks/useAzureCostData';

interface AzureCostKPICardsProps {
  kpis: AzureCostKPIs;
  isLoading: boolean;
}

const formatCurrency = (value: number): string => {
  return '₹' + (value || 0).toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};

const AzureCostKPICards: React.FC<AzureCostKPICardsProps> = ({ kpis, isLoading }) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-6">
        {[1, 2, 3].map((i) => (
          <div key={i} className="glass-card p-6 animate-pulse">
            <div className="h-4 bg-slate-200 rounded w-1/2 mb-4"></div>
            <div className="h-8 bg-slate-200 rounded w-3/4"></div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-6">
      {/* MTD Spend */}
      <div className="glass-card p-6 relative overflow-hidden hover-glow">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center shadow-sm">
            <i className="fas fa-calendar-day text-white text-sm"></i>
          </div>
          <p className="text-xs font-extrabold text-slate-500 uppercase tracking-widest">Month-to-Date</p>
        </div>
        <p className="text-3xl font-extrabold text-slate-900 tracking-tight">
          {formatCurrency(kpis.mtdSpend)}
        </p>
        <p className="text-xs text-slate-400 mt-1 font-semibold">Current month actual spend</p>
      </div>

      {/* Forecasted Spend */}
      <div className="glass-card p-6 relative overflow-hidden hover-glow">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-violet-600 rounded-xl flex items-center justify-center shadow-sm">
            <i className="fas fa-chart-line text-white text-sm"></i>
          </div>
          <p className="text-xs font-extrabold text-slate-500 uppercase tracking-widest">Forecasted</p>
        </div>
        <p className="text-3xl font-extrabold text-slate-900 tracking-tight">
          {formatCurrency(kpis.forecastedSpend)}
        </p>
        <p className="text-xs text-slate-400 mt-1 font-semibold">Projected end-of-month</p>
      </div>

      {/* Last Month */}
      <div className="glass-card p-6 relative overflow-hidden hover-glow">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 bg-gradient-to-br from-amber-500 to-orange-600 rounded-xl flex items-center justify-center shadow-sm">
            <i className="fas fa-history text-white text-sm"></i>
          </div>
          <p className="text-xs font-extrabold text-slate-500 uppercase tracking-widest">Last Month</p>
        </div>
        <p className="text-3xl font-extrabold text-slate-900 tracking-tight">
          {formatCurrency(kpis.lastMonthSpend)}
        </p>
        <p className="text-xs text-slate-400 mt-1 font-semibold">Previous month actual spend</p>
      </div>
    </div>
  );
};

export default AzureCostKPICards;
