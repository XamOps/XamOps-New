/**
 * The Data Lineage page's icons and colours (build 4 design 6.10). Every icon id is a full string literal,
 * so the offline icon bundle picks it up.
 */

import type { LineageCategoryId } from './lineageQuery';

/** Each category card's icon, in the console's order (FR-S0-2). */
export const CATEGORY_ICONS: Record<LineageCategoryId, string> = {
  SENSITIVE_FINDINGS: 'mdi:fingerprint',
  EXCESSIVE_EXPOSURE: 'mdi:broadcast',
  HIGH_VOLUME_ACCESS: 'mdi:chart-bar',
  STALE_DATA: 'mdi:file-document-multiple-outline',
  RISKY_USER: 'mdi:alert-outline',
  PUBLIC_EXPOSURE: 'mdi:earth',
};

/** The category node's icon. */
export const CATEGORY_NODE_ICON = 'mdi:lock-outline';

/** A policy node's icon by its target service. */
export function policyIcon(service: string | null): string {
  switch (service) {
    case 'ENDPOINT': return 'mdi:devices';
    case 'ONE_DRIVE': return 'mdi:microsoft-onedrive';
    case 'SHARE_POINT': return 'mdi:microsoft-sharepoint';
    default: return 'mdi:shield-outline';
  }
}

/** A datastore node's icon by its service. */
export function datastoreIcon(service: string | null): string {
  switch (service) {
    case 'ENDPOINT': return 'mdi:laptop';
    case 'ONE_DRIVE': return 'mdi:microsoft-onedrive';
    case 'SHARE_POINT': return 'mdi:microsoft-sharepoint';
    default: return 'mdi:database-outline';
  }
}

/** The severity dot's background class. */
export function severityDot(severity: string | null): string {
  switch (severity) {
    case 'CRITICAL': return 'bg-rose-600';
    case 'HIGH': return 'bg-orange-500';
    case 'MEDIUM': return 'bg-amber-500';
    case 'LOW': return 'bg-[rgba(0,0,0,0.35)]';
    default: return 'bg-[rgba(0,0,0,0.2)]';
  }
}
