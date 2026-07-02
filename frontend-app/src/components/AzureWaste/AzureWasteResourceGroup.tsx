import React, { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { Icon } from '@iconify/react';
import type { AzureWasteGroup } from '../../types/azureWaste';

interface AzureWasteResourceGroupProps {
  group: AzureWasteGroup;
}

const TYPE_ICONS: Record<string, { icon: string; color: string; bg: string }> = {
  'Unattached Disk':       { icon: 'mdi:harddisk',         color: '#3B82F6', bg: '#3B82F614' },
  'Stopped VM':            { icon: 'mdi:monitor-off',       color: '#8B5CF6', bg: '#8B5CF614' },
  'Deallocated VM':        { icon: 'mdi:power-off',         color: '#6366F1', bg: '#6366F114' },
  'Orphaned Public IP':    { icon: 'mdi:ip-network',        color: '#F59E0B', bg: '#F59E0B14' },
  'Unattached NIC':        { icon: 'mdi:ethernet',          color: '#6B7280', bg: '#6B728014' },
  'Old Snapshot':          { icon: 'mdi:camera-burst',      color: '#0EA5E9', bg: '#0EA5E914' },
  'Empty Load Balancer':   { icon: 'mdi:scale-balance',     color: '#EC4899', bg: '#EC489914' },
  'Unused NSG':            { icon: 'mdi:shield-off-outline', color: '#64748B', bg: '#64748B14' },
  'Empty Availability Set':{ icon: 'mdi:server-network-off', color: '#94A3B8', bg: '#94A3B814' },
  'Orphaned Route Table':  { icon: 'mdi:routes',            color: '#A855F7', bg: '#A855F714' },
  'Idle Running VM':       { icon: 'mdi:sleep',             color: '#EF4444', bg: '#EF444414' },
  'Idle SQL Database':     { icon: 'mdi:database-clock',     color: '#F97316', bg: '#F9731614' },
  'Empty App Service Plan':{ icon: 'mdi:web-off',           color: '#14B8A6', bg: '#14B8A614' },
  'Idle Storage Account':  { icon: 'mdi:database-off',       color: '#0891B2', bg: '#0891B214' },
  'Idle Cosmos DB':        { icon: 'mdi:earth-off',          color: '#7C3AED', bg: '#7C3AED14' },
  'Empty Resource Group':  { icon: 'mdi:folder-remove-outline', color: '#9CA3AF', bg: '#9CA3AF14' },
};

const AzureWasteResourceGroup: React.FC<AzureWasteResourceGroupProps> = ({ group }) => {
  const [isOpen, setIsOpen] = useState(group.open ?? true);
  const panelRef = useRef<HTMLDivElement>(null);
  const isFirstEffect = useRef(true);
  const [rowKey, setRowKey] = useState(0);

  const meta = TYPE_ICONS[group.type] ?? { icon: 'mdi:cube-outline', color: '#3B82F6', bg: '#3B82F614' };

  // Set initial hidden state before first paint (only when starting closed)
  useLayoutEffect(() => {
    const panel = panelRef.current;
    if (!panel || isOpen) return;
    panel.style.maxHeight = '0px';
    panel.style.overflow = 'hidden';
    panel.style.opacity = '0';
  }, []);

  useEffect(() => {
    // Skip the initial mount call — useLayoutEffect already set correct initial state
    if (isFirstEffect.current) {
      isFirstEffect.current = false;
      return;
    }
    const panel = panelRef.current;
    if (!panel) return;

    panel.style.transition = 'none';

    if (isOpen) {
      panel.style.overflow = 'hidden';
      panel.style.maxHeight = 'none';
      const h = panel.scrollHeight;
      panel.style.maxHeight = '0px';
      panel.style.opacity = '0';
      panel.getBoundingClientRect();
      panel.style.transition = 'max-height 0.32s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.22s ease';
      panel.style.maxHeight = `${h}px`;
      panel.style.opacity = '1';
      setRowKey(k => k + 1);

      const t = setTimeout(() => {
        if (panelRef.current) {
          panelRef.current.style.maxHeight = 'none';
          panelRef.current.style.overflow = 'visible';
        }
      }, 360);
      return () => clearTimeout(t);
    } else {
      const h = panel.scrollHeight;
      panel.style.overflow = 'hidden';
      panel.style.maxHeight = `${h}px`;
      panel.style.opacity = '1';
      panel.getBoundingClientRect();
      panel.style.transition = 'max-height 0.28s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.2s ease';
      panel.style.maxHeight = '0px';
      panel.style.opacity = '0';
    }
  }, [isOpen]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden widget-panel-hover">
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
              ₹{group.totalSavings.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}/mo
            </span>
          )}
          <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-rose-50 text-rose-600 border border-rose-100">
            {group.resources.length}
          </span>
        </div>
      </div>

      {/* Resource panel — always rendered, animated via JS */}
      <div ref={panelRef} className="border-t border-[rgba(0,0,0,0.06)]">
        <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
          <div className="col-span-4">Resource Name</div>
          <div className="col-span-2">Region</div>
          <div className="col-span-4">Reason</div>
          <div className="col-span-2 text-right">Savings/mo</div>
        </div>

        {group.resources.map((resource, idx) => (
          <div
            key={`${resource.resourceId || idx}-${rowKey}`}
            className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]"
            style={{ animation: `fadeInUp 0.3s ease ${0.05 + idx * 0.04}s both` }}
          >
            <div className="col-span-4 font-semibold text-[rgba(0,0,0,0.88)] truncate" title={resource.resourceName}>
              {resource.resourceName || '—'}
            </div>
            <div className="col-span-2 text-[rgba(0,0,0,0.55)]">{resource.region || '—'}</div>
            <div className="col-span-4 text-[rgba(0,0,0,0.55)] text-[12px] leading-relaxed">{resource.reason}</div>
            <div className="col-span-2 text-right">
              {resource.monthlySavings > 0 ? (
                <span className="font-semibold text-rose-600">
                  ₹{resource.monthlySavings.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                </span>
              ) : (
                <span className="text-[rgba(0,0,0,0.3)] text-[11px]">hygiene</span>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default AzureWasteResourceGroup;
