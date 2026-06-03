import React, { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { Icon } from '@iconify/react';
import type { GcpWasteResource } from '../../types/gcpWaste';

interface GcpWasteResourceGroupProps {
  type: string;
  resources: GcpWasteResource[];
  isOpen: boolean;
  onToggle: () => void;
}

const GCP_TYPE_ICONS: Record<string, { icon: string; color: string; bg: string }> = {
  'Idle VM':              { icon: 'mdi:server-off',        color: '#EA4335', bg: '#EA433514' },
  'Unattached Disk':      { icon: 'mdi:harddisk',          color: '#4285F4', bg: '#4285F414' },
  'Idle Load Balancer':   { icon: 'mdi:scale-balance',     color: '#FBBC05', bg: '#FBBC0514' },
  'Unused IP':            { icon: 'mdi:ip-network',        color: '#F59E0B', bg: '#F59E0B14' },
  'Idle SQL Instance':    { icon: 'mdi:database-off',      color: '#34A853', bg: '#34A85314' },
  'Oversized VM':         { icon: 'mdi:server',            color: '#8B5CF6', bg: '#8B5CF614' },
};

const GcpWasteResourceGroup: React.FC<GcpWasteResourceGroupProps> = ({ type, resources, isOpen, onToggle }) => {
  const panelRef = useRef<HTMLDivElement>(null);
  const isFirstEffect = useRef(true);
  const [rowKey, setRowKey] = useState(0);

  const totalSavings = resources.reduce((sum, r) => sum + (r.monthlySavings || 0), 0);
  const meta = GCP_TYPE_ICONS[type] ?? { icon: 'mdi:cloud-outline', color: '#4285F4', bg: '#4285F414' };

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
        onClick={onToggle}
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
            <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{type}</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{resources.length} resource{resources.length !== 1 ? 's' : ''}</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          {totalSavings > 0 && (
            <span className="text-[12px] font-semibold text-rose-600">
              ₹{totalSavings.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}/mo
            </span>
          )}
          <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-rose-50 text-rose-600 border border-rose-100">
            {resources.length}
          </span>
        </div>
      </div>

      {/* Resource panel — always rendered, animated via JS */}
      <div ref={panelRef} className="border-t border-[rgba(0,0,0,0.06)]">
        <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
          <div className="col-span-5">Resource Name</div>
          <div className="col-span-3">Location</div>
          <div className="col-span-2">Savings/mo</div>
          <div className="col-span-2 text-right">Actions</div>
        </div>

        {resources.map((resource, idx) => (
          <div
            key={`${resource.resourceName}-${idx}-${rowKey}`}
            className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]"
            style={{ animation: `fadeInUp 0.3s ease ${0.05 + idx * 0.04}s both` }}
          >
            <div className="col-span-5 font-semibold text-[rgba(0,0,0,0.88)] truncate">{resource.resourceName}</div>
            <div className="col-span-3 text-[rgba(0,0,0,0.55)]">{resource.location || 'N/A'}</div>
            <div className="col-span-2 font-semibold text-rose-600">
              ₹{(resource.monthlySavings || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            </div>
            <div className="col-span-2 flex justify-end">
              <button
                onClick={() => console.log('Delete:', resource.resourceName)}
                className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold text-rose-600 border border-rose-200 hover:bg-rose-50 transition-colors"
              >
                <Icon icon="mdi:trash-can-outline" width={13} />
                Delete
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default GcpWasteResourceGroup;
