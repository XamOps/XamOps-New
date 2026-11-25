/**
 * The Data Lineage page's copy and formatters (build 4 design 6.10, FR-INV-2, FR-INV-10). Pure.
 */

import type { OpenLevel, RowLevel } from './lineageQuery';

export const PICKER_TITLE = 'Data lineage';
/** The console's wording, kept verbatim (FR-S0-1). */
export const PICKER_SUBTITLE = 'Start by choosing a exposure category';
export const NO_CATEGORIES_TEXT = 'No exposure categories are available for OneDrive, SharePoint or Endpoint yet.';

export const PAGE_TITLE = 'Data Lineage';
export const PAGE_SUBTITLE = 'Trace file history, access, and sharing across your environment';
export const NEW_INVESTIGATION = 'New Investigation';
/** The root node's name when the project has none recorded (the console's own fallback). */
export const ROOT_FALLBACK_NAME = 'Project';

/** The panel's heading, help text and search placeholder per open level (FR-INV-2). */
export const PANEL_COPY: Record<OpenLevel, { heading: string; help: string; placeholder: string }> = {
  policy: { heading: 'Choose a policy', help: 'Select a policy to view the datastores it affects.', placeholder: 'Search policies' },
  datastore: { heading: 'Choose a datastore', help: 'Select a datastore to view the affected files it contains.', placeholder: 'Search datastores' },
  file: { heading: 'Choose a file', help: 'Select a file to trace its complete data lineage.', placeholder: 'Search files' },
  event: { heading: 'File lineage', help: 'Trace where this file came from.', placeholder: 'Search events' },
};

const PLURAL: Record<RowLevel, string> = { policy: 'policies', datastore: 'datastores', file: 'files' };

/** A row's plural noun ("policies"), for pager labels and messages. */
export const rowPlural = (level: RowLevel): string => PLURAL[level];

/** A row's loading message. */
export const rowLoadingText = (level: RowLevel): string => `Loading ${PLURAL[level]}…`;
export const LINEAGE_LOADING_TEXT = 'Loading lineage…';
export const NO_LINEAGE_TEXT = 'No lineage recorded for this file yet.';
export const NO_EVENT_MATCH_TEXT = 'Nothing matches this search.';

/**
 * The text of an empty row: the search's text when searching, the filters' when filtered, else the
 * level's own ("No exposures found for this category." for policies, D4).
 */
export function rowEmptyText(level: RowLevel, q: string, filtered: boolean): string {
  if (q !== '') return `No ${PLURAL[level]} match “${q}”.`;
  if (filtered) return `No ${PLURAL[level]} match the applied filters.`;
  switch (level) {
    case 'policy': return 'No exposures found for this category.';
    case 'datastore': return 'No datastores found.';
    case 'file': return 'No files found.';
  }
}

/** One decimal, a trailing ".0" dropped. */
const oneDecimal = (value: number) => String(Math.round(value * 10) / 10);

/** "999", "1k", "1.2k", "999.9k", "1m", "1.2m" (FR-S0-4). */
export function compactCount(n: number): string {
  if (n < 1_000) return n.toLocaleString('en-US');
  if (n < 999_950) return `${oneDecimal(Math.round(n / 100) / 10)}k`;
  return `${oneDecimal(Math.round(n / 100_000) / 10)}m`;
}

/** "1 exposure", "0 exposures", "1.2k exposures". */
export const exposureCountText = (n: number): string => countText(n, 'exposure');

/** "1 datastore", "3 datastores", "1 file", "10 files". */
export function countText(n: number, noun: string): string {
  return `${compactCount(n)} ${n === 1 ? noun : `${noun}s`}`;
}

const NODE_DAY = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
const EVENT_DAY = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
const pad = (n: number) => String(n).padStart(2, '0');

/** A file node's date, "6 Apr 2026", in the viewer's time zone. */
export function formatNodeDay(seconds: number): string {
  return NODE_DAY.format(new Date(seconds * 1000));
}

/** The event drawer's time, "06 Apr 2026 • 11:52" (24 h), in the viewer's time zone. */
export function formatEventTime(seconds: number): string {
  const date = new Date(seconds * 1000);
  return `${EVENT_DAY.format(date)} • ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
