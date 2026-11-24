/**
 * The Endpoint file drawer's tabs and Details rows (design 6.8 as changed by plan C17). Pure.
 */

import type { EndpointFileOverview } from '../../../../types/dspmFiles';
import { formatBytes, formatConsoleDate } from '../datastoreFormat';
import type { DrawerTabDef } from '../drawer/DrawerTabList';

export type EndpointFileTabId = 'sensitives' | 'details' | 'activity' | 'lineage' | 'copies' | 'related';

/** Always all six, in the console's order. */
export const ENDPOINT_FILE_TABS: ReadonlyArray<DrawerTabDef<EndpointFileTabId>> = [
  { id: 'sensitives', label: 'Sensitives' },
  { id: 'details', label: 'Details' },
  { id: 'activity', label: 'File Activity' },
  { id: 'lineage', label: 'Lineage' },
  { id: 'copies', label: 'File Copies' },
  { id: 'related', label: 'Related Files' },
];

/** One Details row; `copyText` adds a copy button. */
export interface EndpointFileDetail {
  label: string;
  value: string;
  copyText?: string;
}

const timeText = (seconds: number | null) => (seconds === null ? '-' : formatConsoleDate(seconds));

/**
 * The Details rows: File Path (with copy), File Size, File Created, Last Accessed, Last Modified and Last
 * Scanned (the list row's scan time), then Matters Tags only when the file has tags. Dates read
 * "DD MMM YYYY, hh:mm A"; a missing value reads "-".
 */
export function endpointFileDetails(overview: EndpointFileOverview, scannedAt: number | null): EndpointFileDetail[] {
  const rows: EndpointFileDetail[] = [
    overview.path ? { label: 'File Path', value: overview.path, copyText: overview.path } : { label: 'File Path', value: '-' },
    { label: 'File Size', value: overview.sizeBytes === null ? '-' : formatBytes(overview.sizeBytes) },
    { label: 'File Created', value: timeText(overview.fileCreatedAt) },
    { label: 'Last Accessed', value: timeText(overview.accessedAt) },
    { label: 'Last Modified', value: timeText(overview.modifiedAt) },
    { label: 'Last Scanned', value: timeText(scannedAt) },
  ];
  if (overview.tags.length > 0) rows.push({ label: 'Matters Tags', value: overview.tags.join(', ') });
  return rows;
}
