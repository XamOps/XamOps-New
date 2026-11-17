import type { DatastoreCategory } from '../../../../types/dspmDatastores';

export type DrawerTabId = 'sensitive' | 'information' | 'risk' | 'dataFlow' | 'accessGraph' | 'scanHistory';

export interface DrawerTab {
  id: DrawerTabId;
  label: string;
}

/** The tab a drawer opens on. */
export const DEFAULT_DRAWER_TAB: DrawerTabId = 'sensitive';

const SENSITIVE: DrawerTab = { id: 'sensitive', label: 'Sensitive' };
const INFORMATION: DrawerTab = { id: 'information', label: 'Information' };
const RISK: DrawerTab = { id: 'risk', label: 'Risk' };
const DATA_FLOW: DrawerTab = { id: 'dataFlow', label: 'Data Flow' };
const ACCESS_GRAPH: DrawerTab = { id: 'accessGraph', label: 'Access Graph' };
const SCAN_HISTORY: DrawerTab = { id: 'scanHistory', label: 'Scan History' };

/**
 * A datastore drawer's tabs, in the console's order (A14): Sensitive, Information, then Risk for cloud
 * datastores only, then Data Flow whenever the overview says the schema can be discovered, then Access
 * Graph and Scan History. A cloud database therefore gets both Risk and Data Flow, and an on-prem
 * database gets Data Flow without Risk (its misconfiguration list is empty in the console).
 *
 * `canDiscoverSchema` is unknown (null or undefined) until the overview arrives, or when it failed; it
 * then counts as false, so Data Flow appears once the overview confirms it.
 */
export function drawerTabsFor(
  category: DatastoreCategory | null,
  canDiscoverSchema: boolean | null | undefined,
): DrawerTab[] {
  const tabs = [SENSITIVE, INFORMATION];
  if (category === 'cloud') tabs.push(RISK);
  if (canDiscoverSchema === true) tabs.push(DATA_FLOW);
  tabs.push(ACCESS_GRAPH, SCAN_HISTORY);
  return tabs;
}
