/**
 * XamLens page demo data (ROLE_XAMOPS), design 7: the Dashboard.
 *
 * Nothing here is written by hand: the Dashboard is computed from the demo datastores that
 * `xamlensMockData.ts` serves, with the backend's rules (`DashboardMapper`), so every Top Risky row is a
 * demo datastore whose drawer works and every bar's link lands on rows the demo lists.
 */
import type { DatastoreRow } from '../types/dspmDatastores';
import type {
  DashboardEnvironment, DashboardEnvironmentId, DashboardSubCategory, DspmDashboard, TopRiskyTab,
} from '../types/dspmPages';
import { demoDatastoreKpis, demoDatastores } from './xamlensMockData';

const ENVIRONMENTS: DashboardEnvironmentId[] = ['cloud', 'saas', 'endpoint', 'on_prem'];
const TOP_RISKY_TABS: TopRiskyTab[] = ['all', ...ENVIRONMENTS];

/** As `DashboardMapper`: 5 Top Risky rows, at most 20 bars, the console's endpoint bar names. */
const TOP_ROWS = 5;
const MAX_SUB_CATEGORIES = 20;
const OS_BAR_LABELS: Record<string, string> = { MAC: 'Mac', WINDOWS: 'Windows' };

/** A tab's rows in Risk order, as the backend's collector pages them (one page holds the whole demo). */
const tabRows = (tab: TopRiskyTab): DatastoreRow[] =>
  demoDatastores(`/api/dspm/datastores?tab=${tab}&sort=-severity&page=1&pageSize=100`).data;

const records = (row: DatastoreRow) => row.sensitives.recordCount;

/** Bars by provider (cloud, on-prem), drive type (SaaS) or OS (endpoint), most records first. */
function subCategories(id: DashboardEnvironmentId, rows: DatastoreRow[]): DashboardSubCategory[] {
  const bySubtype = id === 'endpoint' || id === 'saas';
  const bars = new Map<string, DashboardSubCategory>();
  for (const row of rows) {
    const key = bySubtype ? row.subtype : row.source.provider;
    if (key === null) continue;
    let bar = bars.get(key);
    if (!bar) {
      const label = id === 'endpoint' ? (OS_BAR_LABELS[key] ?? row.subtypeLabel)
        : id === 'saas' ? row.subtypeLabel
          : row.source.providerLabel;
      bar = { key, label: label ?? key, sensitiveRecords: 0, datastores: 0 };
      bars.set(key, bar);
    }
    bar.sensitiveRecords += records(row);
    bar.datastores += 1;
  }
  return [...bars.values()]
    .sort((a, b) => b.sensitiveRecords - a.sensitiveRecords
      || a.label.localeCompare(b.label, 'en', { sensitivity: 'base' }))
    .slice(0, MAX_SUB_CATEGORIES);
}

function environment(id: DashboardEnvironmentId): DashboardEnvironment {
  const rows = tabRows(id);
  const kpis = demoDatastoreKpis(`/api/dspm/datastores/kpis?tab=${id}`);
  return {
    id,
    datastores: rows.length,
    sensitiveRecords: rows.reduce((sum, row) => sum + records(row), 0),
    recordsTrend: kpis.totalSensitiveRecords.trend,
    connected: rows.length > 0,
    filter: id === 'endpoint' ? 'os' : id === 'saas' ? null : 'provider',
    subCategories: subCategories(id, rows),
    partial: false,
  };
}

/** `GET /api/dspm/dashboard`. */
export function demoDashboard(): DspmDashboard {
  const topRisky = Object.fromEntries(TOP_RISKY_TABS.map(tab =>
    [tab, tabRows(tab).filter(row => records(row) > 0).slice(0, TOP_ROWS)])) as Record<TopRiskyTab, DatastoreRow[]>;
  return { environments: ENVIRONMENTS.map(environment), topRisky };
}
