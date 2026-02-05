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
  if (loading) {
    return (
      <div className="lg:col-span-2 chart-container p-6 rounded-xl skeleton" style={{ height: '450px' }}></div>
    );
  }

  return (
    <div className="lg:col-span-2 glass-card chart-container p-6 rounded-xl">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center text-slate-600">
          <i className="fas fa-arrows-alt widget-handle mr-3 text-lg cursor-move opacity-40 hover:opacity-100 transition-opacity"></i>
          <h3 className="text-lg font-black tracking-tight text-slate-800 uppercase italic flex items-center">
            <i className="fas fa-server mr-2 text-purple-500"></i>
            Inventory
          </h3>
        </div>
        <div className="text-[10px] font-black uppercase text-slate-400 tracking-widest bg-slate-100 px-3 py-1 rounded-full border border-slate-200">
          {totalResources} Total
        </div>
      </div>
      <div className="grid grid-cols-1 gap-3 custom-scrollbar pr-2" style={{ maxHeight: '400px', overflowY: 'auto' }}>
        {resources.map(res => (
          <Link
            key={res.type}
            to={`/azure-cloudlist?service=${encodeURIComponent(res.type)}&accountIds=${accountIds.join(',')}&filter=${encodeURIComponent(res.type)}`}
            className="resource-card flex items-center gap-4 p-4 rounded-xl hover:shadow-md transition-all group"
          >
            <div className="resource-icon w-11 h-11 flex-shrink-0 shadow-sm border border-slate-100 group-hover:border-blue-200 transition-colors">
              <img src={res.icon} alt={res.name} className="w-full h-full object-contain" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="font-black text-slate-800 text-sm tracking-tight truncate group-hover:text-blue-600 transition-colors">{res.name}</p>
              <div className="flex items-center gap-1.5 mt-0.5">
                <div className="w-1.5 h-1.5 bg-blue-400 rounded-full"></div>
                <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Active resources</p>
              </div>
            </div>
            <div className="text-right">
              <p className="text-xl font-black text-slate-900 metric-value">{res.count || 0}</p>
              <p className="text-[9px] font-black text-slate-300 uppercase tracking-widest">count</p>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
};

export default AzureInventorySection;
