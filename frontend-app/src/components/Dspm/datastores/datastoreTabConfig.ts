/**
 * The one configuration of the Datastores tabs: columns, quick filters, More-filters order, KPI cards and
 * the header's "⋯" overflow (NFR-3). Change a tab here and every component follows.
 */

import type { DatastoreTabSlug } from '../../../constants/xamlensTabs';
import type { DatastoreFilterOptions, DatastoreKpis } from '../../../types/dspmDatastores';
import type { DspmTrendPoint } from '../../../types/dspm';
import type { DatastoreFilterState } from './datastoreFilters';

export type ColumnId =
  | 'name' | 'risk' | 'sensitiveData' | 'sourceAccount' | 'scanStatus'
  | 'serialNumber' | 'agentStatus' | 'agentVersion';

/** The backend's sort fields. Source / Account and Scan Status have none, so they are not sortable. */
export type SortField = 'severity' | 'name' | 'sensitive_record_count';

export interface ColumnDef {
  id: ColumnId;
  label: string;
  sortField?: SortField;
}

export type KpiCardId =
  | 'totalSensitiveRecords' | 'assetCount' | 'classified' | 'scheduled' | 'failedStopped' | 'failed' | 'criticalHigh';

export interface KpiCardDef {
  id: KpiCardId;
  label: string;
  tooltip: string;
  badge?: 'percent' | 'delta';
}

export interface DatastoreTabConfig {
  slug: DatastoreTabSlug;
  /** Table order. The first column is always shown; the row ⋮ column follows them and is not listed. */
  columns: ColumnDef[];
  /** Whether rows lead with the (console-only) selection checkbox. */
  selectable: boolean;
  quickFilters: string[];
  /** `fixed`: exactly this order. `dynamic`: this order, then the other returned dimensions in response order. */
  moreFilters: { mode: 'fixed'; order: string[] } | { mode: 'dynamic'; leading: string[] };
  kpis: KpiCardDef[];
  /** Whether the header shows the greyed "⋯" after Manage Scans. */
  headerOverflow: boolean;
}

/** Every filter dimension's label, in the order state-only dimensions are listed in More filters. */
export const DIMENSION_LABELS: Readonly<Record<string, string>> = {
  integration_id: 'Source / Account',
  provider: 'Provider',
  region: 'Region',
  scan_status: 'Scan Status',
  severity: 'Severity',
  sensitive_type: 'Sensitive Type',
  data_element: 'Data Element',
  selected: 'Selected',
  os: 'OS',
  agent_status: 'Agent Status',
  device_type: 'Device Type',
  serial_number: 'Serial Number',
  hostname: 'Hostname',
  user: 'User',
};

export const dimensionLabel = (dimension: string): string => DIMENSION_LABELS[dimension] ?? dimension;

const DATASTORE_COLUMNS: ColumnDef[] = [
  { id: 'name', label: 'Datastore', sortField: 'name' },
  { id: 'risk', label: 'Risk', sortField: 'severity' },
  { id: 'sensitiveData', label: 'Sensitive Data', sortField: 'sensitive_record_count' },
  { id: 'sourceAccount', label: 'Source / Account' },
  { id: 'scanStatus', label: 'Scan Status' },
];

const ENDPOINT_COLUMNS: ColumnDef[] = [
  { id: 'name', label: 'Device', sortField: 'name' },
  { id: 'risk', label: 'Risk', sortField: 'severity' },
  { id: 'sensitiveData', label: 'Sensitive Data', sortField: 'sensitive_record_count' },
  { id: 'serialNumber', label: 'Serial Number' },
  { id: 'agentStatus', label: 'Agent Status' },
  { id: 'agentVersion', label: 'Agent Version' },
];

const DATASTORE_QUICK_FILTERS = ['integration_id', 'provider', 'scan_status'];

const DATASTORE_MORE_FILTERS = [
  'integration_id', 'provider', 'region', 'scan_status', 'severity', 'sensitive_type', 'data_element',
];

// The status cards count datastores, not scans, and the 14 daily readings span 13 days, so no tooltip
// speaks of scans or of "14 days ago".
const RECORDS_CARD: KpiCardDef = {
  id: 'totalSensitiveRecords',
  label: 'Total Sensitive Records',
  tooltip: 'Sensitive records found in these datastores. The badge shows the change across the last 14 daily readings.',
  badge: 'percent',
};

const assetCard = (label: string): KpiCardDef => ({
  id: 'assetCount',
  label,
  tooltip: 'Datastores on this tab. The badge shows the change across the last 14 daily readings.',
  badge: 'delta',
});

const CLASSIFIED_CARD: KpiCardDef = {
  id: 'classified', label: 'Classified', tooltip: 'Datastores whose last scan finished classifying data.',
};
const SCHEDULED_CARD: KpiCardDef = {
  id: 'scheduled', label: 'Scheduled', tooltip: 'Datastores with a scan scheduled.',
};
const FAILED_STOPPED_CARD: KpiCardDef = {
  id: 'failedStopped', label: 'Failed / Stopped', tooltip: 'Datastores whose last scan failed or was stopped.',
};
const FAILED_CARD: KpiCardDef = {
  id: 'failed', label: 'Failed', tooltip: 'Datastores whose last scan failed.',
};
const CRITICAL_HIGH_CARD: KpiCardDef = {
  id: 'criticalHigh', label: 'Critical / High', tooltip: 'Datastores with a Critical or High risk level.',
};

export const DATASTORE_TAB_CONFIG: Readonly<Record<DatastoreTabSlug, DatastoreTabConfig>> = {
  all: {
    slug: 'all',
    columns: DATASTORE_COLUMNS,
    selectable: false,
    quickFilters: DATASTORE_QUICK_FILTERS,
    moreFilters: { mode: 'fixed', order: DATASTORE_MORE_FILTERS },
    kpis: [RECORDS_CARD, assetCard('Total Assets'), CLASSIFIED_CARD, SCHEDULED_CARD, FAILED_STOPPED_CARD, CRITICAL_HIGH_CARD],
    headerOverflow: false,
  },
  cloud: {
    slug: 'cloud',
    columns: DATASTORE_COLUMNS,
    selectable: false,
    quickFilters: DATASTORE_QUICK_FILTERS,
    moreFilters: { mode: 'fixed', order: DATASTORE_MORE_FILTERS },
    kpis: [RECORDS_CARD, assetCard('Total Cloud Datastores'), CLASSIFIED_CARD, SCHEDULED_CARD, FAILED_STOPPED_CARD, CRITICAL_HIGH_CARD],
    headerOverflow: false,
  },
  saas: {
    slug: 'saas',
    columns: DATASTORE_COLUMNS,
    selectable: false,
    quickFilters: DATASTORE_QUICK_FILTERS,
    moreFilters: { mode: 'fixed', order: [...DATASTORE_MORE_FILTERS, 'selected'] },
    // "Failed", not "Failed / Stopped", and no Scheduled card: as the console's SaaS tab.
    kpis: [RECORDS_CARD, assetCard('Total SaaS Assets'), CLASSIFIED_CARD, FAILED_CARD, CRITICAL_HIGH_CARD],
    headerOverflow: false,
  },
  endpoint: {
    slug: 'endpoint',
    columns: ENDPOINT_COLUMNS,
    selectable: true,
    quickFilters: ['provider', 'scan_status', 'os', 'agent_status'],
    moreFilters: {
      mode: 'dynamic',
      leading: ['provider', 'scan_status', 'os', 'agent_status', 'severity', 'sensitive_type', 'data_element'],
    },
    kpis: [RECORDS_CARD, assetCard('Total Endpoints'), CLASSIFIED_CARD, FAILED_STOPPED_CARD, CRITICAL_HIGH_CARD],
    headerOverflow: true,
  },
  'on-prem': {
    slug: 'on-prem',
    columns: DATASTORE_COLUMNS,
    selectable: false,
    quickFilters: DATASTORE_QUICK_FILTERS,
    moreFilters: { mode: 'fixed', order: DATASTORE_MORE_FILTERS },
    kpis: [RECORDS_CARD, assetCard('Total On-Prem Datastores'), CLASSIFIED_CARD, SCHEDULED_CARD, FAILED_STOPPED_CARD, CRITICAL_HIGH_CARD],
    headerOverflow: true,
  },
};

/**
 * The dimensions More filters lists, in order.
 *
 * A dimension is listed when the filter-options response has at least one option for it, or when the
 * filter state holds values for it. The second half keeps a dimension whose every value is excluded (the
 * vendor then returns no options for it), so its values can still be un-excluded. Order: the tab's fixed
 * or leading order; on a dynamic tab the other returned dimensions in response order; then the
 * dimensions only the state holds, in `DIMENSION_LABELS` order.
 */
export function moreFiltersDimensions(
  config: DatastoreTabConfig,
  options: DatastoreFilterOptions | null,
  state: DatastoreFilterState,
): string[] {
  const returned = new Set(
    (options?.dimensions ?? []).filter(d => d.options.length > 0).map(d => d.key),
  );
  const held = (dimension: string) => (state[dimension]?.values.length ?? 0) > 0;
  const listed: string[] = [];
  const add = (dimension: string) => { if (!listed.includes(dimension)) listed.push(dimension); };

  const order = config.moreFilters.mode === 'fixed' ? config.moreFilters.order : config.moreFilters.leading;
  for (const dimension of order) {
    if (returned.has(dimension) || held(dimension)) add(dimension);
  }
  if (config.moreFilters.mode === 'dynamic') {
    for (const dimension of options?.dimensions ?? []) {
      if (dimension.options.length > 0) add(dimension.key);
    }
  }
  const labelOrder = Object.keys(DIMENSION_LABELS);
  const rank = (dimension: string) => {
    const index = labelOrder.indexOf(dimension);
    return index < 0 ? labelOrder.length : index;
  };
  Object.keys(state)
    .filter(dimension => held(dimension) && !listed.includes(dimension))
    .sort((a, b) => rank(a) - rank(b))
    .forEach(add);
  return listed;
}

/** A KPI card's value. Failed / Stopped is the sum of both; the SaaS "Failed" card is failures only. */
export function kpiCardValue(card: KpiCardId, kpis: DatastoreKpis): number {
  switch (card) {
    case 'totalSensitiveRecords': return kpis.totalSensitiveRecords.value;
    case 'assetCount': return kpis.assetCount.value;
    case 'classified': return kpis.classified;
    case 'scheduled': return kpis.scheduled;
    case 'failedStopped': return kpis.failed + kpis.stopped;
    case 'failed': return kpis.failed;
    case 'criticalHigh': return kpis.criticalHigh;
  }
}

/** The trend behind a card's badge; only the first two cards have one. */
export function kpiCardTrend(card: KpiCardId, kpis: DatastoreKpis): DspmTrendPoint[] | null {
  if (card === 'totalSensitiveRecords') return kpis.totalSensitiveRecords.trend;
  if (card === 'assetCount') return kpis.assetCount.trend;
  return null;
}
