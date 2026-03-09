import React, { useState } from 'react';
import type { AzureWasteGroup } from '../../types/azureWaste';

interface AzureWasteResourceGroupProps {
  group: AzureWasteGroup;
}

const formatINR = (value: number) =>
  '₹' + value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function getTypeIcon(type: string): { icon: string; colorClass: string; bgClass: string } {
  switch (type) {
    case 'Unattached Disk':
      return { icon: 'fas fa-hdd', colorClass: 'text-blue-600', bgClass: 'bg-blue-100' };
    case 'Stopped VM':
      return { icon: 'fas fa-desktop', colorClass: 'text-purple-600', bgClass: 'bg-purple-100' };
    case 'Deallocated VM':
      return { icon: 'fas fa-power-off', colorClass: 'text-indigo-600', bgClass: 'bg-indigo-100' };
    case 'Orphaned Public IP':
      return { icon: 'fas fa-globe', colorClass: 'text-amber-600', bgClass: 'bg-amber-100' };
    case 'Unattached NIC':
      return { icon: 'fas fa-network-wired', colorClass: 'text-gray-600', bgClass: 'bg-gray-100' };
    default:
      return { icon: 'fas fa-cube', colorClass: 'text-blue-600', bgClass: 'bg-blue-100' };
  }
}

const AzureWasteResourceGroup: React.FC<AzureWasteResourceGroupProps> = ({ group }) => {
  const [isOpen, setIsOpen] = useState(group.open);
  const { icon, colorClass, bgClass } = getTypeIcon(group.type);

  return (
    <div className="glass-card rounded-xl shadow-sm overflow-hidden">
      {/* Group Header */}
      <button
        onClick={() => setIsOpen(prev => !prev)}
        aria-expanded={isOpen}
        aria-controls={`azure-waste-group-${group.type.replace(/\s+/g, '-').toLowerCase()}`}
        className="w-full flex items-center justify-between px-6 py-4 hover:bg-gray-50/50 transition-colors text-left"
      >
        <div className="flex items-center gap-3">
          <div className={`w-8 h-8 rounded-lg ${bgClass} flex items-center justify-center`}>
            <i className={`${icon} ${colorClass} text-sm`}></i>
          </div>
          <span className="font-bold text-gray-800">{group.type}</span>
          <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-gray-100 text-gray-700">
            {group.resources.length} resource{group.resources.length !== 1 ? 's' : ''}
          </span>
        </div>
        <div className="flex items-center gap-4">
          {group.totalSavings > 0 && (
            <span className="text-sm font-bold text-green-700">
              {formatINR(group.totalSavings)}/mo potential savings
            </span>
          )}
          <i className={`fas fa-chevron-${isOpen ? 'up' : 'down'} text-gray-400 text-sm`}></i>
        </div>
      </button>

      {/* Resource Rows */}
      {isOpen && (
        <div id={`azure-waste-group-${group.type.replace(/\s+/g, '-').toLowerCase()}`} className="border-t border-gray-100">
          {/* Table Header */}
          <div className="grid grid-cols-12 gap-2 px-6 py-2 bg-gray-50/50 border-b border-gray-100 text-xs font-semibold text-gray-500 uppercase tracking-wider">
            <div className="col-span-3">Resource Name</div>
            <div className="col-span-2">Region</div>
            <div className="col-span-5">Reason</div>
            <div className="col-span-2 text-right">Savings/mo</div>
          </div>

          {/* Rows */}
          {group.resources.map((resource, idx) => (
            <div
              key={resource.resourceId || idx}
              className={`grid grid-cols-12 gap-2 px-6 py-3 text-sm items-center hover:bg-gray-50/70 transition-colors ${
                idx < group.resources.length - 1 ? 'border-b border-gray-50' : ''
              }`}
            >
              <div className="col-span-3 font-medium text-gray-800 truncate" title={resource.resourceName}>
                {resource.resourceName || '—'}
              </div>
              <div className="col-span-2 text-gray-500">
                {resource.region ? (
                  <span className="inline-flex items-center gap-1">
                    <i className="fas fa-map-marker-alt text-xs text-gray-400"></i>
                    <span className="truncate">{resource.region}</span>
                  </span>
                ) : (
                  '—'
                )}
              </div>
              <div className="col-span-5 text-gray-600 text-xs leading-relaxed">
                {resource.reason}
              </div>
              <div className="col-span-2 text-right">
                {resource.monthlySavings > 0 ? (
                  <span className="font-semibold text-green-700">{formatINR(resource.monthlySavings)}</span>
                ) : (
                  <span className="text-gray-400 text-xs">hygiene</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default AzureWasteResourceGroup;
