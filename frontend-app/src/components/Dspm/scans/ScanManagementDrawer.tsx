import React, { useCallback, useId, useRef, useState } from 'react';
import { DrawerShell } from '../datastores/drawer/DrawerShell';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import ScanNav, { SCAN_TABS, type ScanTabId } from './ScanNav';
import ScanConfigurationsTab, { type ScanPlatform } from './ScanConfigurationsTab';
import ScanScopesTab from './ScanScopesTab';
import SchedulesTab from './SchedulesTab';
import ScanHistoryTab from './ScanHistoryTab';
import { CloseX } from './ScanParts';

interface Props {
  /** The Scan Configurations toggle's first value: Endpoint from the Endpoint tab, else SaaS. */
  initialPlatform: ScanPlatform;
  /** Must be stable (`useCallback`): the drawer's Escape listener depends on it. */
  onClose: () => void;
}

function panel(id: ScanTabId, initialPlatform: ScanPlatform): React.ReactNode {
  switch (id) {
    case 'configurations': return <ScanConfigurationsTab initialPlatform={initialPlatform} />;
    case 'scopes': return <ScanScopesTab />;
    case 'schedules': return <SchedulesTab />;
    case 'history': return <ScanHistoryTab />;
  }
}

/**
 * Scan Management (design 4.3, 6.8; FR-SM), opened by the Datastores page's "Manage Scans". View only:
 * every create, run, edit, save and export control is the console's and greyed. A tab mounts the first
 * time it is selected and stays mounted, so its filters and page survive switching tabs. Row details
 * open as nested layers over this drawer; Escape closes the newest layer first, then the drawer.
 */
const ScanManagementDrawer: React.FC<Props> = ({ initialPlatform, onClose }) => {
  const closeButton = useRef<HTMLButtonElement>(null);
  const ids = useId();
  const [tab, setTab] = useState<ScanTabId>('configurations');
  const [visited, setVisited] = useState<ReadonlySet<ScanTabId>>(() => new Set<ScanTabId>(['configurations']));
  const select = useCallback((id: ScanTabId) => {
    setTab(id);
    setVisited(previous => (previous.has(id) ? previous : new Set([...previous, id])));
  }, []);

  return (
    <DrawerShell label="Scan Management" onClose={onClose} initialFocus={closeButton} widthClass="lg:w-[72vw]">
      <div className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 pb-3 pt-4">
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-bold text-[rgba(0,0,0,0.88)]">Scan Management</h2>
          <p className="mt-0.5 text-xs text-[rgba(0,0,0,0.5)]">Schedule, run and track scans across Cloud, Endpoint and On-Prem.</p>
        </div>
        <CloseX buttonRef={closeButton} onClose={onClose} />
      </div>
      <div className="flex min-h-0 flex-1">
        <ScanNav active={tab} onSelect={select} idPrefix={ids} />
        <div className="min-w-0 flex-1 overflow-y-auto px-5 py-4">
          {SCAN_TABS.filter(t => visited.has(t.id)).map(t => (
            <div
              key={t.id}
              role="tabpanel"
              id={`${ids}-panel-${t.id}`}
              aria-labelledby={`${ids}-tab-${t.id}`}
              hidden={t.id !== tab}
            >
              {panel(t.id, initialPlatform)}
            </div>
          ))}
        </div>
      </div>
      <div className="flex items-center justify-end gap-3 border-t border-[rgba(0,0,0,0.06)] px-5 py-3">
        <button
          type="button"
          onClick={onClose}
          className="inline-flex items-center rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.03)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]"
        >
          Close
        </button>
        {tab === 'configurations' && <ConsoleOnlyButton variant="primary" placement="top">Save</ConsoleOnlyButton>}
      </div>
    </DrawerShell>
  );
};

export default ScanManagementDrawer;
