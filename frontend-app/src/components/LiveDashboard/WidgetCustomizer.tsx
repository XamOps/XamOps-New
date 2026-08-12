import React, { useEffect, useRef } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';

export interface WidgetConfig {
  id: string;
  label: string;
  icon: string;
  description: string;
}

/**
 * Widget catalog.
 *
 * `costByRegion` and `costSection` are off by default: the first duplicates the
 * Top Services region toggle (same cost-by-region endpoint), and the second
 * restates the cost trend plus a billing table that /cost renders with filters.
 * They stay listed here so anyone who wants them back can switch them on.
 */
export const ALL_WIDGETS: WidgetConfig[] = [
  { id: 'kpi',          label: 'KPI Cards',          icon: 'mdi:view-dashboard-outline',    description: 'Pick from cost, alerts & resource metrics' },
  { id: 'summaryCards', label: 'Summary Cards',      icon: 'mdi:cards-outline',             description: 'Alerts, Security, Waste, Rightsizing' },
  { id: 'costTrend',    label: 'Cost Trend',         icon: 'mdi:chart-areaspline',          description: 'Spend history with forecast & budget' },
  { id: 'inventory',    label: 'Resource Inventory', icon: 'mdi:server-outline',            description: 'EC2, S3, RDS resource counts' },
  { id: 'topServices',  label: 'Top Services',       icon: 'mdi:chart-bar',                 description: 'Cost breakdown by service or region' },
  { id: 'compliance',   label: 'Compliance Posture', icon: 'mdi:clipboard-check-outline',   description: 'Control score, trend & top risks' },
  { id: 'optimization', label: 'Optimization Hub',   icon: 'mdi:lightning-bolt-outline',    description: 'Rightsizing & waste recommendations' },
  { id: 'costByRegion', label: 'Cost by Region',     icon: 'mdi:earth',                     description: 'Duplicate of Top Services region view' },
  { id: 'costSection',  label: 'Cost Breakdown',     icon: 'mdi:chart-box-outline',         description: '6-month stacked chart + billing table' },
];

interface WidgetCustomizerProps {
  open: boolean;
  visibleWidgets: Record<string, boolean>;
  onToggle: (id: string) => void;
  onClose: () => void;
}

const WidgetCustomizer: React.FC<WidgetCustomizerProps> = ({
  open, visibleWidgets, onToggle, onClose,
}) => {
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) onClose();
    };
    setTimeout(() => document.addEventListener('mousedown', handler), 0);
    return () => document.removeEventListener('mousedown', handler);
  }, [open, onClose]);

  /**
   * Only offer widgets the calling page actually renders.
   *
   * ALL_WIDGETS is shared with the GCP and Azure dashboards, which render a
   * smaller set (no compliance, cost breakdown, cost-by-region or optimization
   * panel). Listing the full catalog there produced toggles that flipped state
   * and changed nothing on screen. Each page declares its widgets as the keys of
   * its own DEFAULT_VISIBLE, so intersecting with those keys scopes the panel
   * correctly without every page needing to pass a second list.
   */
  const widgets = ALL_WIDGETS.filter(w => w.id in visibleWidgets);
  const visibleCount = widgets.filter(w => visibleWidgets[w.id] !== false).length;

  return ReactDOM.createPortal(
    <>
      {/* Backdrop */}
      <div className={`fixed inset-0 z-40 bg-black/10 transition-opacity duration-200 ${open ? 'opacity-100' : 'opacity-0 pointer-events-none'}`} />

      {/* Slide-in panel */}
      <div ref={panelRef}
        className={`fixed top-0 right-0 h-full z-50 w-[300px] bg-white border-l border-[rgba(0,0,0,0.1)] shadow-2xl
          flex flex-col transition-transform duration-300 ease-out
          ${open ? 'translate-x-0' : 'translate-x-full'}`}>

        {/* Header */}
        <div className="flex items-center justify-between px-5 pt-5 pb-4 border-b border-[rgba(0,0,0,0.07)]">
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Customize Dashboard</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">{visibleCount} of {widgets.length} widgets visible</p>
          </div>
          <button onClick={onClose}
            className="w-7 h-7 rounded-lg flex items-center justify-center hover:bg-[rgba(0,0,0,0.06)] transition-colors text-[rgba(0,0,0,0.4)]">
            <Icon icon="mdi:close" width={16} />
          </button>
        </div>

        {/* Widget list */}
        <div className="flex-1 overflow-y-auto py-3 px-3 space-y-1">
          {widgets.map(w => {
            const visible = visibleWidgets[w.id] !== false;
            return (
              <button key={w.id} onClick={() => onToggle(w.id)}
                className={`w-full flex items-center gap-3 px-3 py-3 rounded-xl transition-all text-left
                  ${visible ? 'bg-[rgba(0,0,0,0.03)]' : 'opacity-50 hover:opacity-70'}
                  hover:bg-[rgba(0,0,0,0.05)]`}>
                <div className={`w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0 transition-colors
                  ${visible ? 'bg-[rgba(0,0,0,0.07)]' : 'bg-[rgba(0,0,0,0.04)]'}`}>
                  <Icon icon={w.icon} width={17} color={visible ? 'rgba(0,0,0,0.65)' : 'rgba(0,0,0,0.3)'} />
                </div>
                <div className="flex-1 min-w-0">
                  <p className={`text-[13px] font-semibold truncate ${visible ? 'text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.4)]'}`}>
                    {w.label}
                  </p>
                  <p className="text-[11px] text-[rgba(0,0,0,0.4)] truncate">{w.description}</p>
                </div>

                {/* Toggle pill */}
                <div className={`w-9 h-5 rounded-full flex-shrink-0 relative transition-colors duration-200
                  ${visible ? 'bg-[rgba(0,0,0,0.88)]' : 'bg-[rgba(0,0,0,0.15)]'}`}>
                  <span className={`absolute top-0.5 w-4 h-4 bg-white rounded-full shadow-sm transition-all duration-200
                    ${visible ? 'left-[18px]' : 'left-0.5'}`} />
                </div>
              </button>
            );
          })}
        </div>

        {/* Footer */}
        <div className="px-5 py-4 border-t border-[rgba(0,0,0,0.07)]">
          <p className="text-[10.5px] text-[rgba(0,0,0,0.35)] text-center leading-relaxed">
            Widget preferences are saved automatically and persist across sessions.
          </p>
        </div>
      </div>
    </>,
    document.body
  );
};

export default WidgetCustomizer;
