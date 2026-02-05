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
    <div className="glass-card p-8 rounded-3xl fade-in col-span-full hover-glow">
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center gap-4">
          <div className="w-12 h-12 bg-white/80 rounded-2xl flex items-center justify-center border border-white shadow-sm transition-transform duration-300 hover:rotate-6">
            <i className="fas fa-lightbulb text-2xl text-amber-500"></i>
          </div>
          <div>
            <h3 className="text-2xl font-extrabold text-slate-900 tracking-tight uppercase">Optimization Hub</h3>
            <div className="flex items-center gap-2 mt-0.5">
              <span className="w-2.5 h-2.5 bg-amber-500 rounded-full animate-pulse shadow-[0_0_10px_rgba(245,158,11,0.5)]"></span>
              <span className="text-xs text-slate-400 font-extrabold uppercase tracking-widest italic">Efficiency & Savings</span>
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-8 mb-10">
        <div className="bg-gradient-to-br from-emerald-50/50 to-emerald-100/30 border border-white p-8 rounded-3xl relative overflow-hidden group shadow-sm transition-all duration-500 hover:shadow-xl hover:shadow-emerald-500/10">
          <div className="absolute -top-4 -right-4 p-8 opacity-5 group-hover:scale-110 group-hover:-rotate-12 transition-all duration-700">
            <i className="fas fa-piggy-bank text-9xl text-emerald-600"></i>
          </div>
          <p className="text-[11px] text-emerald-700 font-extrabold uppercase tracking-widest mb-2 flex items-center gap-2">
            <span className="w-1.5 h-1.5 bg-emerald-500 rounded-full"></span>
            Potential Savings
          </p>
          <p className="text-5xl font-extrabold text-emerald-900 metric-value">{formatCurrency(optimization.summary.totalPotentialSavings)}</p>
          <p className="text-[10px] font-extrabold text-emerald-600/60 mt-2 tracking-wider uppercase">Projected Monthly Reduction</p>
        </div>
        <div className="bg-gradient-to-br from-rose-50/50 to-rose-100/30 border border-white p-8 rounded-3xl relative overflow-hidden group shadow-sm transition-all duration-500 hover:shadow-xl hover:shadow-rose-500/10">
          <div className="absolute -top-4 -right-4 p-8 opacity-5 group-hover:scale-110 group-hover:rotate-12 transition-all duration-700">
            <i className="fas fa-exclamation-triangle text-9xl text-rose-600"></i>
          </div>
          <p className="text-[11px] text-rose-700 font-extrabold uppercase tracking-widest mb-2 flex items-center gap-2">
            <span className="w-1.5 h-1.5 bg-rose-500 rounded-full"></span>
            Critical Alerts
          </p>
          <p className="text-5xl font-extrabold text-rose-900 metric-value">{optimization.summary.criticalAlertsCount}</p>
          <p className="text-[10px] font-extrabold text-rose-600/60 mt-2 tracking-wider uppercase">Requires Immediate Attention</p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
        {/* VM Recommendations */}
        {optimization.vmRecommendations && optimization.vmRecommendations.length > 0 && (
          <div className="bg-white/40 backdrop-blur-sm rounded-3xl p-6 border border-white shadow-sm transition-all duration-500 hover:bg-white/60 hover:shadow-xl hover:shadow-amber-500/5">
            <div className="flex items-center justify-between mb-6">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 bg-amber-50 rounded-xl flex items-center justify-center border border-amber-100 shadow-sm transition-transform duration-300 group-hover:rotate-6">
                  <i className="fas fa-server text-amber-600"></i>
                </div>
                <h4 className="font-extrabold text-sm text-slate-800 uppercase tracking-tight">VM Right-Sizing</h4>
              </div>
              <span className="bg-amber-100/50 text-amber-800 text-[10px] font-extrabold px-3 py-1 rounded-full uppercase tracking-tighter border border-amber-100">{optimization.vmRecommendations.length} Found</span>
            </div>
            <div className="space-y-4">
              {optimization.vmRecommendations.slice(0, 3).map((rec, index) => (
                <div key={index} className="flex items-center justify-between p-4 bg-white/50 rounded-2xl border border-white group hover:border-amber-200 transition-all duration-300">
                  <div className="min-w-0 flex-1 mr-4">
                    <p className="font-mono text-[10px] text-slate-400 truncate tracking-tight">{rec.resourceId}</p>
                    <div className="flex items-center gap-3 mt-1.5">
                      <span className="text-[11px] font-bold text-slate-500 bg-slate-100/50 px-2 py-0.5 rounded-lg">{rec.currentType}</span>
                      <i className="fas fa-long-arrow-alt-right text-[10px] text-amber-400"></i>
                      <span className="text-[11px] font-extrabold text-amber-600 bg-amber-50 px-2 py-0.5 rounded-lg">{rec.recommendedType}</span>
                    </div>
                  </div>
                  <div className="text-right">
                    <p className="text-sm font-extrabold text-slate-900 metric-value">{formatCurrency(rec.estimatedMonthlySavings)}</p>
                    <p className="text-[9px] font-extrabold text-slate-400 uppercase tracking-widest mt-0.5">SAVINGS</p>
                  </div>
                </div>
              ))}
            </div>
            {optimization.vmRecommendations.length > 3 && (
              <div className="mt-6 pt-5 border-t border-slate-100/50 text-center">
                <Link to={`/rightsizing.html?accountIds=${selectedAccountIds.join(',')}`} className="text-[11px] font-extrabold uppercase text-amber-600 hover:text-amber-700 tracking-widest transition-all flex items-center justify-center gap-2 group">
                  View Comprehensive Report
                  <i className="fas fa-chevron-right group-hover:translate-x-1 transition-transform"></i>
                </Link>
              </div>
            )}
          </div>
        )}

        {/* Disk Recommendations */}
        {optimization.diskRecommendations && optimization.diskRecommendations.length > 0 && (
          <div className="bg-white/40 backdrop-blur-sm rounded-3xl p-6 border border-white shadow-sm transition-all duration-500 hover:bg-white/60 hover:shadow-xl hover:shadow-indigo-500/5">
            <div className="flex items-center justify-between mb-6">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 bg-indigo-50 rounded-xl flex items-center justify-center border border-indigo-100 shadow-sm transition-transform duration-300 group-hover:rotate-6">
                  <i className="fas fa-hdd text-indigo-600"></i>
                </div>
                <h4 className="font-extrabold text-sm text-slate-800 uppercase tracking-tight">Disk Optimization</h4>
              </div>
              <span className="bg-indigo-100/50 text-indigo-800 text-[10px] font-extrabold px-3 py-1 rounded-full uppercase tracking-tighter border border-indigo-100">{optimization.diskRecommendations.length} Found</span>
            </div>
            <div className="space-y-4">
              {optimization.diskRecommendations.slice(0, 3).map((rec, index) => (
                <div key={index} className="flex items-center justify-between p-4 bg-white/50 rounded-2xl border border-white group hover:border-indigo-200 transition-all duration-300">
                  <div className="min-w-0 flex-1 mr-4">
                    <p className="font-mono text-[10px] text-slate-400 truncate tracking-tight">{rec.resourceId}</p>
                    <div className="flex items-center gap-3 mt-1.5">
                      <span className="text-[11px] font-bold text-slate-500 bg-slate-100/50 px-2 py-0.5 rounded-lg">{rec.currentType}</span>
                      <i className="fas fa-long-arrow-alt-right text-[10px] text-indigo-400"></i>
                      <span className="text-[11px] font-extrabold text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded-lg">{rec.recommendedType}</span>
                    </div>
                  </div>
                  <div className="text-right">
                    <p className="text-sm font-extrabold text-slate-900 metric-value">{formatCurrency(rec.estimatedMonthlySavings)}</p>
                    <p className="text-[9px] font-extrabold text-slate-400 uppercase tracking-widest mt-0.5">SAVINGS</p>
                  </div>
                </div>
              ))}
            </div>
            {optimization.diskRecommendations.length > 3 && (
              <div className="mt-6 pt-5 border-t border-slate-100/50 text-center">
                <Link to={`/rightsizing.html?accountIds=${selectedAccountIds.join(',')}`} className="text-[11px] font-extrabold uppercase text-indigo-600 hover:text-indigo-700 tracking-widest transition-all flex items-center justify-center gap-2 group">
                  View Comprehensive Report
                  <i className="fas fa-chevron-right group-hover:translate-x-1 transition-transform"></i>
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
