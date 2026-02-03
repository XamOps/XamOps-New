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
    <div className="lg:col-span-2 chart-container p-6 rounded-xl">
      <div className="flex items-center justify-between mb-6">
        <h3 className="text-lg font-bold text-gray-800 flex items-center">
          <i className="fas fa-arrows-alt widget-handle mr-3 text-gray-400"></i>
          <i className="fas fa-server mr-2 text-purple-500"></i>
          Resource Inventory
        </h3>
        <div className="text-sm text-gray-500">
          <i className="fas fa-sync-alt"></i>
        </div>
      </div>
      <div className="grid grid-cols-1 gap-3 custom-scrollbar" style={{ maxHeight: '400px', overflowY: 'auto' }}>
        {resources.map(res => (
          <Link
            key={res.type}
            to={`/azure-cloudlist?service=${encodeURIComponent(res.type)}&accountIds=${accountIds.join(',')}&filter=${encodeURIComponent(res.type)}`}
            className="resource-card flex items-center gap-4 p-4 rounded-xl hover:shadow-md transition-all group"
          >
            <div className="resource-icon w-10 h-10 flex-shrink-0">
              <img src={res.icon} alt={res.name} className="w-full h-full rounded-xl object-cover" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="font-semibold text-gray-800 truncate">{res.name}</p>
              <p className="text-sm text-gray-500">Active resources</p>
            </div>
            <div className="text-right">
              <p className="text-xl font-bold text-gray-900">{res.count || 0}</p>
              <p className="text-xs text-gray-500">total</p>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
};

export default AzureInventorySection;
