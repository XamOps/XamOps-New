import React from 'react';
import { Link } from 'react-router-dom';
import { AzureOptimizationData } from '../../types/azureDashboard';

interface AzureOptimizationSectionProps {
  optimization: AzureOptimizationData;
  selectedAccountIds: string[];
  isLoading: boolean;
}

const AzureOptimizationSection: React.FC<AzureOptimizationSectionProps> = ({ optimization, selectedAccountIds, isLoading }) => {
  const formatCurrency = (num: number): string => {
    return '$' + (num != null ? num.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }) : '0.00');
  };

  if (isLoading) {
    return (
      <div className="chart-container p-6 rounded-xl skeleton" style={{ height: '400px' }}></div>
    );
  }

  return (
    <div className="glass-card chart-container p-6 rounded-xl fade-in col-span-full">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center text-slate-600">
          <i className="fas fa-arrows-alt widget-handle mr-3 text-lg cursor-move opacity-40 hover:opacity-100 transition-opacity"></i>
          <h3 className="text-lg font-black tracking-tight text-slate-800 uppercase italic flex items-center">
            <i className="fas fa-lightbulb mr-2 text-yellow-500"></i>
            Optimization Hub
          </h3>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-8">
        <div className="bg-gradient-to-br from-emerald-50 to-emerald-100/50 border border-emerald-100 p-6 rounded-2xl relative overflow-hidden group">
          <div className="absolute top-0 right-0 p-4 opacity-10 group-hover:scale-110 transition-transform">
            <i className="fas fa-piggy-bank text-6xl text-emerald-600"></i>
          </div>
          <p className="text-[10px] text-emerald-700 font-black uppercase tracking-widest mb-1">Potential Savings</p>
          <p className="text-4xl font-black text-emerald-900 metric-value">{formatCurrency(optimization.summary.totalPotentialSavings)}</p>
          <p className="text-[11px] font-bold text-emerald-600/70 mt-1">ESTIMATED MONTHLY REDUCTION</p>
        </div>
        <div className="bg-gradient-to-br from-rose-50 to-rose-100/50 border border-rose-100 p-6 rounded-2xl relative overflow-hidden group">
          <div className="absolute top-0 right-0 p-4 opacity-10 group-hover:scale-110 transition-transform">
            <i className="fas fa-exclamation-triangle text-6xl text-rose-600"></i>
          </div>
          <p className="text-[10px] text-rose-700 font-black uppercase tracking-widest mb-1">Critical Alerts</p>
          <p className="text-4xl font-black text-rose-900 metric-value">{optimization.summary.criticalAlertsCount}</p>
          <p className="text-[11px] font-bold text-rose-600/70 mt-1">ITEMS REQUIRING IMMEDIATE ATTENTION</p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* VM Recommendations */}
        {optimization.vmRecommendations && optimization.vmRecommendations.length > 0 && (
          <div className="glass-card p-6 border-t-4 border-amber-500">
            <div className="flex items-center justify-between mb-4">
              <h4 className="font-black text-sm text-slate-800 uppercase tracking-tight flex items-center">
                <i className="fas fa-server mr-2 text-amber-500"></i>
                VM Right-Sizing
              </h4>
              <span className="bg-amber-100 text-amber-800 text-[9px] font-black px-2 py-0.5 rounded-full uppercase tracking-tighter">{optimization.vmRecommendations.length} Found</span>
            </div>
            <div className="space-y-3">
              {optimization.vmRecommendations.slice(0, 3).map((rec, index) => (
                <div key={index} className="flex items-center justify-between p-3 bg-slate-50/50 rounded-xl border border-slate-100 group hover:border-amber-200 transition-colors">
                  <div className="min-w-0 flex-1 mr-4">
                    <p className="font-mono text-[10px] text-slate-400 truncate">{rec.resourceId}</p>
                    <div className="flex items-center gap-2 mt-1">
                      <span className="text-[11px] font-bold text-slate-600">{rec.currentType}</span>
                      <i className="fas fa-chevron-right text-[10px] text-slate-300"></i>
                      <span className="text-[11px] font-black text-amber-600">{rec.recommendedType}</span>
                    </div>
                  </div>
                  <div className="text-right">
                    <p className="text-sm font-black text-slate-900 metric-value">{formatCurrency(rec.estimatedMonthlySavings)}</p>
                    <p className="text-[9px] font-bold text-slate-400 uppercase tracking-tighter">SAVINGS</p>
                  </div>
                </div>
              ))}
            </div>
            {optimization.vmRecommendations.length > 3 && (
              <div className="mt-4 pt-3 border-t border-slate-100 text-center">
                <Link to={`/rightsizing.html?accountIds=${selectedAccountIds.join(',')}`} className="text-[10px] font-black uppercase text-amber-600 hover:text-amber-700 tracking-widest transition-colors flex items-center justify-center gap-2">
                  View All Recommendations
                  <i className="fas fa-arrow-right"></i>
                </Link>
              </div>
            )}
          </div>
        )}

        {/* Disk Recommendations */}
        {optimization.diskRecommendations && optimization.diskRecommendations.length > 0 && (
          <div className="glass-card p-6 border-t-4 border-indigo-500">
            <div className="flex items-center justify-between mb-4">
              <h4 className="font-black text-sm text-slate-800 uppercase tracking-tight flex items-center">
                <i className="fas fa-hdd mr-2 text-indigo-500"></i>
                Disk Optimization
              </h4>
              <span className="bg-indigo-100 text-indigo-800 text-[9px] font-black px-2 py-0.5 rounded-full uppercase tracking-tighter">{optimization.diskRecommendations.length} Found</span>
            </div>
            <div className="space-y-3">
              {optimization.diskRecommendations.slice(0, 3).map((rec, index) => (
                <div key={index} className="flex items-center justify-between p-3 bg-slate-50/50 rounded-xl border border-slate-100 group hover:border-indigo-200 transition-colors">
                  <div className="min-w-0 flex-1 mr-4">
                    <p className="font-mono text-[10px] text-slate-400 truncate">{rec.resourceId}</p>
                    <div className="flex items-center gap-2 mt-1">
                      <span className="text-[11px] font-bold text-slate-600">{rec.currentType}</span>
                      <i className="fas fa-chevron-right text-[10px] text-slate-300"></i>
                      <span className="text-[11px] font-black text-indigo-600">{rec.recommendedType}</span>
                    </div>
                  </div>
                  <div className="text-right">
                    <p className="text-sm font-black text-slate-900 metric-value">{formatCurrency(rec.estimatedMonthlySavings)}</p>
                    <p className="text-[9px] font-bold text-slate-400 uppercase tracking-tighter">SAVINGS</p>
                  </div>
                </div>
              ))}
            </div>
            {optimization.diskRecommendations.length > 3 && (
              <div className="mt-4 pt-3 border-t border-slate-100 text-center">
                <Link to={`/rightsizing.html?accountIds=${selectedAccountIds.join(',')}`} className="text-[10px] font-black uppercase text-indigo-600 hover:text-indigo-700 tracking-widest transition-colors flex items-center justify-center gap-2">
                  View All Recommendations
                  <i className="fas fa-arrow-right"></i>
                </Link>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default AzureOptimizationSection;
