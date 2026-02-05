import React from 'react';
import { Link } from 'react-router-dom';
import { AzureResourceType } from '../../types/azureDashboard';

interface AzureInventorySectionProps {
  resources: AzureResourceType[];
  totalResources: number;
  loading: boolean;
  accountIds: string[];
}

const AzureInventorySection: React.FC<AzureInventorySectionProps> = ({ resources, totalResources, loading, accountIds }) => {
  return (
    <div className="lg:col-span-2 glass-card p-6 relative overflow-hidden h-full hover-glow">
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 bg-white/80 rounded-2xl flex items-center justify-center border border-white shadow-sm transition-transform duration-300 hover:rotate-6">
            <svg className="w-7 h-7 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 012 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
            </svg>
          </div>
          <div>
            <h3 className="text-xl font-extrabold text-slate-900 tracking-tight uppercase px-1">Resource Inventory</h3>
            <div className="flex items-center gap-2 mt-0.5 px-1">
              <span className="w-2 h-2 bg-blue-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(59,130,246,0.5)]"></span>
              <span className="text-[11px] text-slate-400 font-extrabold uppercase tracking-widest italic">Live Assets</span>
            </div>
          </div>
        </div>
        <div className="text-sm text-slate-500 bg-white/60 backdrop-blur-sm px-3 py-1.5 rounded-xl border border-white shadow-sm">
          <span className="font-extrabold mr-1 text-slate-900">{totalResources}</span>
          <span className="text-[10px] uppercase font-bold text-slate-400 tracking-wider">Total</span>
        </div>
      </div>

      {/* Inventory Skeleton */}
      {loading && (
        <div className="space-y-4 py-2 px-1">
          {[1, 2, 3, 4, 5].map((i) => (
            <div key={i} className="h-16 w-full skeleton rounded-xl"></div>
          ))}
        </div>
      )}

      {/* Inventory Grid */}
      {!loading && (
        <div
          className="flex flex-col gap-3 overflow-y-auto pb-2 custom-scrollbar fade-in px-1"
          style={{ maxHeight: '420px' }}
        >
          {resources.length > 0 ? (
            resources.map((res) => (
              <Link
                key={res.type}
                to={`/azure-cloudlist?service=${encodeURIComponent(res.type)}&accountIds=${accountIds.join(',')}&filter=${encodeURIComponent(res.type)}`}
                className="group p-4 rounded-2xl border border-slate-100 bg-white/40 hover:bg-white hover:border-blue-200 hover:shadow-xl hover:shadow-blue-500/10 cursor-pointer transition-all duration-500 flex items-center justify-between backdrop-blur-sm"
              >
                <div className="flex items-center space-x-4">
                  <div className="w-12 h-12 bg-white rounded-2xl flex items-center justify-center shadow-sm group-hover:scale-110 group-hover:rotate-6 transition-all duration-500 border border-slate-50">
                    <img src={res.icon} alt={res.name} className="w-8 h-8 object-contain drop-shadow-md" onError={(e) => (e.currentTarget.style.display = 'none')} />
                  </div>
                  <div>
                    <p className="text-[10px] font-extrabold text-slate-400 uppercase tracking-widest leading-tight mb-1">{res.name}</p>
                    <div className="flex items-center">
                      <span className="text-[11px] text-blue-600 font-extrabold uppercase tracking-tight">Active Nodes</span>
                      <i className="fas fa-arrow-right ml-2 text-[8px] text-blue-400 opacity-0 group-hover:opacity-100 group-hover:translate-x-1 transition-all duration-300"></i>
                    </div>
                  </div>
                </div>
                <div className="text-right">
                  <span className="text-3xl font-extrabold text-slate-900 tracking-tight metric-value">{res.count || 0}</span>
                </div>
              </Link>
            ))
          ) : (
            <div className="col-span-full py-12 text-center">
              <div className="w-16 h-16 bg-gray-50 rounded-full flex items-center justify-center mx-auto mb-4 border border-gray-100">
                <svg className="w-8 h-8 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
                </svg>
              </div>
              <p className="text-gray-500 font-bold text-xs uppercase tracking-widest">No Active Resources</p>
              <p className="text-[10px] text-gray-400 mt-1">Found in this account</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default AzureInventorySection;
