import type { DrawerTabDef } from '../drawer/DrawerTabList';
import { driveIconKey } from '../datastoreVisuals';

export type SaasFileTabId = 'sensitives' | 'details' | 'activity' | 'lineage' | 'copies' | 'related';

const SENSITIVES: DrawerTabDef<SaasFileTabId> = { id: 'sensitives', label: 'Sensitives' };
const DETAILS: DrawerTabDef<SaasFileTabId> = { id: 'details', label: 'Details' };
const ACTIVITY: DrawerTabDef<SaasFileTabId> = { id: 'activity', label: 'File Activity' };
const LINEAGE: DrawerTabDef<SaasFileTabId> = { id: 'lineage', label: 'Lineage' };
const COPIES: DrawerTabDef<SaasFileTabId> = { id: 'copies', label: 'File Copies' };
const RELATED: DrawerTabDef<SaasFileTabId> = { id: 'related', label: 'Related Files' };

const FIVE_TABS: ReadonlyArray<DrawerTabDef<SaasFileTabId>> = [SENSITIVES, DETAILS, ACTIVITY, LINEAGE, RELATED];
const DRIVE_TABS: ReadonlyArray<DrawerTabDef<SaasFileTabId>> = [SENSITIVES, DETAILS, ACTIVITY, LINEAGE, COPIES, RELATED];

/**
 * The SaaS file drawer's tabs, by the datastore's drive type, so the list is right before the file's
 * overview loads and never changes under the user. Gmail (and any non-Drive type): five tabs. Google
 * Drive (`GOOGLE_WORKSPACE`, `GOOGLE_DRIVE`): File Copies before Related Files (D11, A26).
 */
export function saasFileTabs(driveType: string | null): ReadonlyArray<DrawerTabDef<SaasFileTabId>> {
  return driveIconKey(driveType) === 'GOOGLE_DRIVE' ? DRIVE_TABS : FIVE_TABS;
}
