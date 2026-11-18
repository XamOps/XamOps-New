import React, { useCallback, useId, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { SaasDrawerHeader, SaasFileRow, SaasRecordsView } from '../../../../types/dspmDrawers';
import { useLazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { saasDrawerApi } from '../../../../hooks/useDspmSaasDrawer';
import { DspmProviderIcon } from '../../DspmIcons';
import { accountIcon } from '../datastoreCells';
import { datastoreIconKey, driveIconKey, saasScanStateVisual } from '../datastoreVisuals';
import type { DrawerRow } from '../drawer/DatastoreDrawer';
import { DrawerShell } from '../drawer/DrawerShell';
import { DrawerTabList, type DrawerTabDef } from '../drawer/DrawerTabList';
import { InfoStrip, type InfoStripItem } from '../drawer/InfoStrip';
import { ResourceView } from '../drawer/TabParts';
import SaasFileDrawer from './SaasFileDrawer';
import type { SaasFileOpenTab } from './SaasFileList';
import SaasInformationTab from './SaasInformationTab';
import SaasSensitivesTab from './SaasSensitivesTab';

type SaasTabId = 'sensitives' | 'information';

const TABS: ReadonlyArray<DrawerTabDef<SaasTabId>> = [
  { id: 'sensitives', label: 'Sensitives' },
  { id: 'information', label: 'Information' },
];

/** The file the nested file drawer shows: its row (with an id), the tab it opens on and the row element. */
interface OpenFile {
  file: SaasFileRow & { id: string };
  tab: SaasFileOpenTab;
  opener: HTMLElement;
}

/** A Backfill or Incremental Scan state: its icon and label, "-" without a state. */
const ScanStateChip: React.FC<{ state: string | null; label: string | null }> = ({ state, label }) => {
  if (!state) return <span>-</span>;
  const visual = saasScanStateVisual(state);
  return (
    <span
      data-scan-state={state}
      className="inline-flex items-center gap-1 rounded-full border border-[rgba(0,0,0,0.1)] bg-white px-2 py-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.75)]"
    >
      <Icon icon={visual.icon} width={13} className={visual.cls} />
      {label ?? state}
    </span>
  );
};

/** The strip under the title, from the header with the row as fallback (a failed header leaves the row's data). */
function stripItems(row: DrawerRow, header: SaasDrawerHeader | null): InfoStripItem[] {
  const driveIcon = driveIconKey(header?.driveType ?? row.subtype);
  const assetType = header?.driveTypeLabel ?? row.subtypeLabel ?? '-';
  const account = header?.accountName ?? row.source.accountLabel ?? '-';
  return [
    {
      label: 'Asset Type',
      value: (
        <>
          {driveIcon && <DspmProviderIcon provider={driveIcon} size={14} />}
          <span className="truncate" title={assetType}>{assetType}</span>
        </>
      ),
    },
    {
      label: 'Source/Account',
      value: (
        <>
          {accountIcon(row)}
          <span className="truncate" title={account}>{account}</span>
        </>
      ),
    },
    { label: 'Backfill Scan', value: <ScanStateChip state={header?.backfillState ?? null} label={header?.backfillLabel ?? null} /> },
    {
      label: 'Incremental Scan',
      value: <ScanStateChip state={header?.incrementalState ?? null} label={header?.incrementalLabel ?? null} />,
    },
  ];
}

export interface SaasDrawerProps {
  /** A SaaS row of the list; the page opens the drawer only for rows whose `sourceId` is 24 hex. */
  row: DrawerRow;
  /** Must be stable: the drawer's Escape listener depends on it. */
  onClose: () => void;
}

/**
 * The SaaS datastore drawer (design 6.5, FR-SAAS): a Gmail mailbox or a Google Drive, with its strip
 * (Asset Type, Source/Account, Backfill Scan, Incremental Scan) and the Sensitives and Information tabs.
 *
 * Data: the header and the data elements load on open, the document classes on the first switch to
 * them, the scan summary on the first visit to Information; each keeps its data. A file row of an
 * expanded Sensitives row opens the SaaS file drawer over this one, which stays mounted underneath (its
 * view, expanded row, page and search survive); closing the file drawer returns focus to that row.
 */
const SaasDrawer: React.FC<SaasDrawerProps> = ({ row, onClose }) => {
  const api = useMemo(() => saasDrawerApi(row.sourceId), [row.sourceId]);
  const [tab, setTab] = useState<SaasTabId>('sensitives');
  const [view, setView] = useState<SaasRecordsView>('data_elements');

  const header = useLazyResource(api.header, true);
  const dataElements = useLazyResource(() => api.records('data_elements'), true);
  const documentClasses = useLazyResource(
    () => api.records('document_classes'),
    tab === 'sensitives' && view === 'document_classes',
  );
  const scanSummary = useLazyResource(api.scanSummary, tab === 'information');

  const [openFile, setOpenFile] = useState<OpenFile | null>(null);
  const onOpenFile = useCallback((file: SaasFileRow, fileTab: SaasFileOpenTab, opener: HTMLElement) => {
    const id = file.id;
    if (id) setOpenFile({ file: { ...file, id }, tab: fileTab, opener });
  }, []);
  // Stable, as the nested drawer's Escape-stack entry requires.
  const closeFile = useCallback(() => setOpenFile(null), []);

  const closeButton = useRef<HTMLButtonElement>(null);
  const ids = useId();

  return (
    <DrawerShell label={row.name ?? undefined} onClose={onClose} initialFocus={closeButton}>
      <div className="flex items-start gap-3 px-5 pb-3 pt-4">
        <span className="mt-0.5"><DspmProviderIcon provider={datastoreIconKey(row)} size={22} /></span>
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-base font-bold text-[rgba(0,0,0,0.88)]" title={row.name ?? undefined}>{row.name ?? '-'}</h2>
          {row.subtypeLabel && <p className="mt-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.45)]">{row.subtypeLabel}</p>}
        </div>
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
        <InfoStrip items={stripItems(row, header.data)} />

        <DrawerTabList tabs={TABS} active={tab} onSelect={setTab} label="Datastore details" idPrefix={ids} />

        <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-${tab}`}>
          {tab === 'sensitives' ? (
            <SaasSensitivesTab
              api={api}
              view={view}
              onView={setView}
              records={view === 'data_elements' ? dataElements : documentClasses}
              onOpenFile={onOpenFile}
            />
          ) : (
            <ResourceView resource={scanSummary}>{data => <SaasInformationTab summary={data} />}</ResourceView>
          )}
        </div>
      </div>

      {openFile && (
        <SaasFileDrawer
          key={openFile.file.id}
          fileId={openFile.file.id}
          fileName={openFile.file.name}
          datastoreName={row.name ?? '-'}
          driveType={row.subtype}
          initialTab={openFile.tab}
          returnFocus={openFile.opener}
          onClose={closeFile}
        />
      )}
    </DrawerShell>
  );
};

export default SaasDrawer;
