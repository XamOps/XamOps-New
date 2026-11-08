import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import ReactDOM from 'react-dom';
import type {
  DatastoreOverview, DatastoreRow, DatastoreScanHistory, DatastoreSensitives,
} from '../../../../types/dspmDatastores';
import {
  datastoreDrawerApi, useLazyResource, type DatastoreDrawerApi, type LazyResource,
} from '../../../../hooks/useDspmDatastoreDrawer';
import { DEFAULT_DRAWER_TAB, drawerTabsFor, type DrawerTabId } from './drawerTabs';
import { EscapeStack, useEscapeStack } from './EscapeStack';
import { DrawerHeader, DrawerInfoCard } from './DrawerHeader';
import { ResourceView } from './TabParts';
import SensitiveTab from './SensitiveTab';
import InformationTab from './InformationTab';
import ScanHistoryTab from './ScanHistoryTab';

/** A cloud or on-prem row. The page opens the drawer only for rows whose `sourceId` is 24 hex. */
export type DrawerRow = DatastoreRow & { sourceId: string };

/** What a tab body reads: the fetchers and the drawer's resources, each with its own state. */
interface TabBodyContext {
  api: DatastoreDrawerApi;
  overview: LazyResource<DatastoreOverview>;
  sensitives: LazyResource<DatastoreSensitives>;
  scanHistory: LazyResource<DatastoreScanHistory>;
}

/** The body of each tab, drawn from the drawer's resources. */
const TAB_BODIES: Partial<Record<DrawerTabId, (context: TabBodyContext) => React.ReactNode>> = {
  sensitive: ({ api, overview, sensitives }) => (
    <ResourceView resource={sensitives}>
      {data => <SensitiveTab api={api} sensitives={data} overview={overview.data} />}
    </ResourceView>
  ),
  information: ({ overview }) => (
    <ResourceView resource={overview}>{data => <InformationTab overview={data} />}</ResourceView>
  ),
  scanHistory: ({ scanHistory }) => (
    <ResourceView resource={scanHistory}>{data => <ScanHistoryTab history={data} />}</ResourceView>
  ),
};

interface Props {
  row: DrawerRow;
  onClose: () => void;
}

/**
 * The cloud / on-prem datastore drawer (design 6.7): a dialog over the right 55% of the page, with the
 * header and info card, then the tabs of `drawerTabsFor`.
 *
 * Data: the overview, scan status and sensitives load in parallel on open (the header, the info card and
 * the default tab need them); every other tab loads the first time it is shown and keeps its data.
 * Each tab has its own loading and error state, so one failing request never blanks another tab, and a
 * failed overview leaves the header on the row's data.
 *
 * Escape is read on `window`, so it works wherever focus is (also after the focused control unmounted
 * or became disabled): it closes the topmost layer on the `EscapeStack`, else the drawer. Controls that
 * must win (an open popover, a visible console-only tooltip) stop the key before it gets there.
 */
const DatastoreDrawer: React.FC<Props> = ({ row, onClose }) => {
  const api = useMemo(() => datastoreDrawerApi(row.sourceId), [row.sourceId]);
  const [tab, setTab] = useState<DrawerTabId>(DEFAULT_DRAWER_TAB);

  const overview = useLazyResource(api.overview, true);
  const scanStatus = useLazyResource(api.scanStatus, true);
  const sensitives = useLazyResource(api.sensitives, true);

  const tabs = drawerTabsFor(row.category, overview.data?.canDiscoverSchema)
    // Risk, Data Flow and Access Graph bodies arrive in the next commit
    .filter(t => TAB_BODIES[t.id] !== undefined);
  const active = tabs.some(t => t.id === tab) ? tab : DEFAULT_DRAWER_TAB;
  const body = TAB_BODIES[active];

  const scanHistory = useLazyResource(api.scanHistory, active === 'scanHistory');

  const aside = useRef<HTMLElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const escapeStack = useEscapeStack(aside);
  const ids = useId();

  useEffect(() => { closeButton.current?.focus(); }, []);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (!escapeStack.closeTopmost()) onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [escapeStack, onClose]);

  return ReactDOM.createPortal(
    <>
      <div className="fixed inset-0 z-40 bg-[rgba(0,0,0,0.25)]" onClick={onClose} aria-hidden="true" />
      <aside
        ref={aside}
        role="dialog"
        aria-modal="true"
        aria-label={row.name ?? undefined}
        tabIndex={-1}
        className="fixed inset-y-0 right-0 z-50 flex w-full flex-col bg-white shadow-2xl focus:outline-none lg:w-[55vw]"
      >
        <EscapeStack stack={escapeStack}>
          <DrawerHeader row={row} overview={overview.data} closeRef={closeButton} onClose={onClose} />
          <div className="flex-1 space-y-4 overflow-y-auto px-5 pb-6">
            <DrawerInfoCard row={row} overview={overview.data} progress={scanStatus.data?.progress ?? null} />

            <div role="tablist" aria-label="Datastore details" className="flex gap-1 border-b border-[rgba(0,0,0,0.08)]">
              {tabs.map(t => (
                <button
                  key={t.id}
                  id={`${ids}-tab-${t.id}`}
                  type="button"
                  role="tab"
                  aria-selected={t.id === active}
                  aria-controls={`${ids}-panel`}
                  onClick={() => setTab(t.id)}
                  className={`-mb-px border-b-2 px-3 py-2.5 text-xs font-semibold transition-colors ${
                    t.id === active
                      ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
                      : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>

            <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-${active}`}>
              {body?.({ api, overview, sensitives, scanHistory })}
            </div>
          </div>
        </EscapeStack>
      </aside>
    </>,
    document.body,
  );
};

export default DatastoreDrawer;
