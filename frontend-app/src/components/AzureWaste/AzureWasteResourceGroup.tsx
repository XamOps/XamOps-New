import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { AzureWasteGroup } from '../../types/azureWaste';

interface AzureWasteResourceGroupProps {
  group: AzureWasteGroup;
}

const TYPE_ICONS: Record<string, { icon: string; color: string; bg: string }> = {
  'Unattached Disk':   { icon: 'mdi:harddisk',        color: '#3B82F6', bg: '#3B82F614' },
  'Stopped VM':        { icon: 'mdi:monitor-off',      color: '#8B5CF6', bg: '#8B5CF614' },
  'Deallocated VM':    { icon: 'mdi:power-off',        color: '#6366F1', bg: '#6366F114' },
  'Orphaned Public IP':{ icon: 'mdi:ip-network',       color: '#F59E0B', bg: '#F59E0B14' },
  'Unattached NIC':    { icon: 'mdi:ethernet',         color: '#6B7280', bg: '#6B728014' },
};

const AzureWasteResourceGroup: React.FC<AzureWasteResourceGroupProps> = ({ group }) => {
  const [isOpen, setIsOpen] = useState(group.open ?? true);
  const meta = TYPE_ICONS[group.type] ?? { icon: 'mdi:cube-outline', color: '#3B82F6', bg: '#3B82F614' };

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      {/* Group header */}
      <div
        onClick={() => setIsOpen(p => !p)}
        className="flex items-center justify-between px-5 py-4 cursor-pointer hover:bg-[rgba(0,0,0,0.01)] transition-colors"
      >
        <div className="flex items-center gap-3">
          <svg
            className={`w-3.5 h-3.5 text-[rgba(0,0,0,0.3)] flex-shrink-0 transition-transform duration-200 ${isOpen ? 'rotate-90' : ''}`}
            fill="none" stroke="currentColor" viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
          </svg>
          <div className="w-8 h-8 rounded-lg flex items-center justify-center border border-[rgba(0,0,0,0.08)]" style={{ backgroundColor: meta.bg }}>
            <Icon icon={meta.icon} width={16} color={meta.color} />
          </div>
          <div>
            <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{group.type}</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{group.resources.length} resource{group.resources.length !== 1 ? 's' : ''}</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          {group.totalSavings > 0 && (
            <span className="text-[12px] font-semibold text-rose-600">
              ${group.totalSavings.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}/mo
            </span>
          )}
          <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-rose-50 text-rose-600 border border-rose-100">
            {group.resources.length}
          </span>
        </div>
      </div>

      {/* Resource table */}
      {isOpen && (
        <div className="border-t border-[rgba(0,0,0,0.06)]">
          <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
            <div className="col-span-4">Resource Name</div>
            <div className="col-span-2">Region</div>
            <div className="col-span-4">Reason</div>
            <div className="col-span-2 text-right">Savings/mo</div>
          </div>

          {group.resources.map((resource, idx) => (
            <div
              key={resource.resourceId || idx}
              className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]"
            >
              <div className="col-span-4 font-semibold text-[rgba(0,0,0,0.88)] truncate" title={resource.resourceName}>
                {resource.resourceName || '—'}
              </div>
              <div className="col-span-2 text-[rgba(0,0,0,0.55)]">{resource.region || '—'}</div>
              <div className="col-span-4 text-[rgba(0,0,0,0.55)] text-[12px] leading-relaxed">{resource.reason}</div>
              <div className="col-span-2 text-right">
                {resource.monthlySavings > 0 ? (
                  <span className="font-semibold text-rose-600">
                    ${resource.monthlySavings.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                  </span>
                ) : (
                  <span className="text-[rgba(0,0,0,0.3)] text-[11px]">hygiene</span>
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
