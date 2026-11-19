import React, { useCallback, useId, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { EndpointOverview } from '../../../../types/dspmDrawers';
import { useLazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { endpointDrawerApi } from '../../../../hooks/useDspmEndpointDrawer';
import { DspmProviderIcon } from '../../DspmIcons';
import { formatConsoleDate } from '../datastoreFormat';
import { datastoreIconKey } from '../datastoreVisuals';
import type { DrawerRow } from '../drawer/DatastoreDrawer';
import { DrawerShell } from '../drawer/DrawerShell';
import { DrawerTabList, type DrawerTabDef } from '../drawer/DrawerTabList';
import { InfoStrip, type InfoStripItem } from '../drawer/InfoStrip';
import EndpointFileDrawer from './EndpointFileDrawer';
import type { EndpointFileOpenTab, OpenableEndpointFileRow } from './EndpointFileTable';
import EndpointInformationTab from './EndpointInformationTab';
import EndpointScanHistoryTab from './EndpointScanHistoryTab';
import EndpointSensitivesTab from './EndpointSensitivesTab';

type EndpointTabId = 'sensitives' | 'information' | 'scanHistory';

const TABS: ReadonlyArray<DrawerTabDef<EndpointTabId>> = [
  { id: 'sensitives', label: 'Sensitives' },
  { id: 'information', label: 'Information' },
  { id: 'scanHistory', label: 'Scan History' },
];

const timeText = (seconds: number | null | undefined): string =>
  seconds === null || seconds === undefined ? '-' : formatConsoleDate(seconds);

/** The strip under the title, from the overview; the device number falls back to the row's while it loads or after it failed. */
function stripItems(row: DrawerRow, overview: EndpointOverview | null): InfoStripItem[] {
  return [
    { label: 'Device Number', value: overview?.serialNumber ?? row.agent?.serialNumber ?? '-' },
    { label: 'Last Scan', value: timeText(overview?.lastScanAt) },
    { label: 'Last Access', value: timeText(overview?.lastAccessAt) },
  ];
}

export interface EndpointDrawerProps {
  /** An endpoint row of the list; the page opens the drawer only for rows whose `sourceId` is 24 hex. */
  row: DrawerRow;
  /** Must be stable: the drawer's Escape listener depends on it. */
  onClose: () => void;
}

/** The File View row whose Endpoint file drawer is open, the tab it opened on, and the row to focus after. */
interface OpenFile {
  row: OpenableEndpointFileRow;
  tab: EndpointFileOpenTab;
  opener: HTMLElement;
}

/**
 * The Endpoint drawer (design 6.7, FR-EP): a device with its OS icon and name, the strip (Device Number,
 * Last Scan, Last Access) and the Sensitives, Information and Scan History tabs. Read-only: there is no
 * Start Scan.
 *
 * Data: the overview and the filter options load on open, the summary on the first visit to
 * Information, the scans inside their tab; Entity View, File View and the expansions load their own
 * pages. Each has its own loading and error state, so a failed overview leaves the strip on the row's
 * data and every other part working.
 *
 * A File View row opens the Endpoint file drawer over this one (design 6.10, O6). It joins this drawer's
 * Escape stack, so Escape closes it first and focus returns to the row.
 */
const EndpointDrawer: React.FC<EndpointDrawerProps> = ({ row, onClose }) => {
  const api = useMemo(() => endpointDrawerApi(row.sourceId), [row.sourceId]);
  const [tab, setTab] = useState<EndpointTabId>('sensitives');
  const [openFile, setOpenFile] = useState<OpenFile | null>(null);
  const openFileRow = useCallback((file: OpenableEndpointFileRow, fileTab: EndpointFileOpenTab, opener: HTMLElement) => {
    setOpenFile({ row: file, tab: fileTab, opener });
  }, []);
  // Stable, as `useSidePanel` requires.
  const closeFile = useCallback(() => setOpenFile(null), []);

  const overview = useLazyResource(api.overview, true);
  const filterOptions = useLazyResource(api.filterOptions, true);
  const summary = useLazyResource(api.summary, tab === 'information');

  const closeButton = useRef<HTMLButtonElement>(null);
  const ids = useId();

  return (
    <DrawerShell label={row.name ?? undefined} onClose={onClose} initialFocus={closeButton}>
      <div className="flex items-start gap-3 px-5 pb-3 pt-4">
        <span className="mt-0.5"><DspmProviderIcon provider={datastoreIconKey(row)} size={22} /></span>
        <h2 className="min-w-0 flex-1 truncate text-base font-bold text-[rgba(0,0,0,0.88)]" title={row.name ?? undefined}>
          {row.name ?? '-'}
        </h2>
        <button
          ref={closeButton}
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
        >
          <Icon icon="mdi:close" width={14} />
        </button>
      </div>
      <div className="flex-1 space-y-4 overflow-y-auto px-5 pb-6">
        <InfoStrip items={stripItems(row, overview.data)} />

        <DrawerTabList tabs={TABS} active={tab} onSelect={setTab} label="Datastore details" idPrefix={ids} />

        <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-${tab}`}>
          {tab === 'sensitives' && (
            <EndpointSensitivesTab api={api} filterOptions={filterOptions} onOpenFile={openFileRow} />
          )}
          {tab === 'information' && <EndpointInformationTab overview={overview} summary={summary} />}
          {tab === 'scanHistory' && <EndpointScanHistoryTab api={api} />}
        </div>
      </div>
      {openFile && (
        <EndpointFileDrawer
          key={openFile.row.id}
          fileId={openFile.row.id}
          fileName={openFile.row.name ?? '-'}
          extension={openFile.row.extension}
          modifiedAt={openFile.row.modifiedAt}
          scannedAt={openFile.row.scannedAt}
          initialTab={openFile.tab}
          returnFocus={openFile.opener}
          onClose={closeFile}
        />
      )}
    </DrawerShell>
  );
};

export default EndpointDrawer;
