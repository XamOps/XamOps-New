import React, { useId, useMemo, useRef, useState } from 'react';
import type {
  DataFlowDatabases, DatastoreAccessGraph, DatastoreOverview, DatastoreRisks, DatastoreRow, DatastoreScanHistory,
  DatastoreSensitives,
} from '../../../../types/dspmDatastores';
import {
  datastoreDrawerApi, useLazyResource, type DatastoreDrawerApi, type LazyResource,
} from '../../../../hooks/useDspmDatastoreDrawer';
import { DEFAULT_DRAWER_TAB, drawerTabsFor, type DrawerTabId } from './drawerTabs';
import { DrawerShell } from './DrawerShell';
import { DrawerTabList } from './DrawerTabList';
import { DrawerHeader, DrawerInfoCard } from './DrawerHeader';
import { ResourceView } from './TabParts';
import SensitiveTab from './SensitiveTab';
import InformationTab from './InformationTab';
import RiskTab from './RiskTab';
import DataFlowTab from './DataFlowTab';
import AccessGraphTab from './AccessGraphTab';
import ScanHistoryTab from './ScanHistoryTab';

/** A cloud or on-prem row. The page opens the drawer only for rows whose `sourceId` is 24 hex. */
export type DrawerRow = DatastoreRow & { sourceId: string };

/** What a tab body reads: the row, the fetchers and the drawer's resources, each with its own state. */
interface TabBodyContext {
  api: DatastoreDrawerApi;
  row: DrawerRow;
  overview: LazyResource<DatastoreOverview>;
  sensitives: LazyResource<DatastoreSensitives>;
  risks: LazyResource<DatastoreRisks>;
  dataFlowDatabases: LazyResource<DataFlowDatabases>;
  accessGraph: LazyResource<DatastoreAccessGraph>;
  scanHistory: LazyResource<DatastoreScanHistory>;
}

/** The body of each tab, drawn from the drawer's resources. */
const TAB_BODIES: Record<DrawerTabId, (context: TabBodyContext) => React.ReactNode> = {
  sensitive: ({ api, overview, sensitives }) => (
    <ResourceView resource={sensitives}>
      {data => <SensitiveTab api={api} sensitives={data} overview={overview.data} />}
    </ResourceView>
  ),
  information: ({ overview }) => (
    <ResourceView resource={overview}>{data => <InformationTab overview={data} />}</ResourceView>
  ),
  risk: ({ risks }) => (
    <ResourceView resource={risks}>{data => <RiskTab risks={data} />}</ResourceView>
  ),
  // The tab shows the databases' loading and error states in its right pane, beside the tree.
  dataFlow: ({ api, row, dataFlowDatabases }) => <DataFlowTab api={api} row={row} databases={dataFlowDatabases} />,
  accessGraph: ({ row, accessGraph }) => (
    <ResourceView resource={accessGraph}>{data => <AccessGraphTab graph={data} row={row} />}</ResourceView>
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
 * The portal, overlay, focus and Escape rules are `DrawerShell`'s: Escape closes the topmost layer on
 * the drawer's Escape stack, else the drawer.
 */
const DatastoreDrawer: React.FC<Props> = ({ row, onClose }) => {
  const api = useMemo(() => datastoreDrawerApi(row.sourceId), [row.sourceId]);
  const [tab, setTab] = useState<DrawerTabId>(DEFAULT_DRAWER_TAB);

  const overview = useLazyResource(api.overview, true);
  const scanStatus = useLazyResource(api.scanStatus, true);
  const sensitives = useLazyResource(api.sensitives, true);

  const tabs = drawerTabsFor(row.category, overview.data?.canDiscoverSchema);
  const active = tabs.some(t => t.id === tab) ? tab : DEFAULT_DRAWER_TAB;
  const body = TAB_BODIES[active];

  const risks = useLazyResource(api.risks, active === 'risk');
  const dataFlowDatabases = useLazyResource(api.dataFlowDatabases, active === 'dataFlow');
  const accessGraph = useLazyResource(api.accessGraph, active === 'accessGraph');
  const scanHistory = useLazyResource(api.scanHistory, active === 'scanHistory');

  const closeButton = useRef<HTMLButtonElement>(null);
  const ids = useId();

  return (
    <DrawerShell label={row.name ?? undefined} onClose={onClose} initialFocus={closeButton}>
      <DrawerHeader row={row} overview={overview.data} closeRef={closeButton} onClose={onClose} />
      <div className="flex-1 space-y-4 overflow-y-auto px-5 pb-6">
        <DrawerInfoCard row={row} overview={overview.data} progress={scanStatus.data?.progress ?? null} />

        <DrawerTabList tabs={tabs} active={active} onSelect={setTab} label="Datastore details" idPrefix={ids} />

        <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-${active}`}>
          {body({ api, row, overview, sensitives, risks, dataFlowDatabases, accessGraph, scanHistory })}
        </div>
      </div>
    </DrawerShell>
  );
};

export default DatastoreDrawer;
