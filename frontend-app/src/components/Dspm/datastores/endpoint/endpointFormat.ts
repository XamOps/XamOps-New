/**
 * Pure rules of the Endpoint drawer (design 6.7): text formatting and the filter rules its dropdowns,
 * "+ More filters" and document-class rows share.
 */

import type { EndpointFilterParams } from '../../../../hooks/useDspmEndpointDrawer';
import { MAX_FREE_TEXT, type DatastoreFilterState } from '../datastoreFilters';

/** The drawer's filter dimensions, in the order "+ More filters" lists them (A13 [O3]). */
export const ENDPOINT_FILTER_DIMENSIONS = ['sensitives', 'documentClass', 'category', 'dataElement'] as const;

export type EndpointFilterDimension = (typeof ENDPOINT_FILTER_DIMENSIONS)[number];

/** No filter: what a document class's file list sends besides its own class. */
export const EMPTY_FILTER_PARAMS: EndpointFilterParams = { sensitives: [], documentClass: [], category: [], dataElement: [] };

/** How recent a file's last modification is for its "New" tag. */
export const NEW_FILE_HOURS = 48;

/**
 * The console's "New" rule: the file was last modified 0 to 48 hours before `nowMs`. `modifiedAt` is in
 * epoch seconds; a missing time, or one in the future, is not new.
 */
export function isRecentlyModified(modifiedAt: number | null, nowMs: number): boolean {
  if (modifiedAt === null) return false;
  const ageMs = nowMs - modifiedAt * 1000;
  return ageMs >= 0 && ageMs <= NEW_FILE_HOURS * 3_600_000;
}

/** "EMAIL ADDRESS" → "Email address": the first letter upper case, the rest lower case. */
export function sentenceCase(text: string): string {
  const lower = text.toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/**
 * CPU or Memory Utilization: a whole percent while the agent is online (0.2546 → "25%"), else "-",
 * because an offline agent's last reading is stale (A17, GAP-48).
 */
export function utilizationText(value: number | null, online: boolean): string {
  return online && value !== null && Number.isFinite(value) ? `${Math.round(value * 100)}%` : '-';
}

const SENSITIVE_CODE = /^[A-Z][A-Z0-9_]{0,63}$/;
/** What Java's `Character.isISOControl` rejects: C0, DEL and C1. */
const CONTROL_CHARACTER = /[\u0000-\u001F\u007F-\u009F]/;

/**
 * Whether a value of this dimension can be sent as a filter: no surrounding whitespace, 1 to 128
 * characters, no comma and no control character, and for `sensitives` an upper-case code. The drawer
 * joins values with commas and the backend splits them there and trims them (design 5.3), so a value
 * failing this would reach Matters as other values. Such options render disabled (build 1's DA7 rule),
 * and a document-class row with such a name does not expand.
 */
export const endpointFilterValueAllowed = (dimension: string) => (value: string): boolean => {
  if (value !== value.trim()) return false;
  if (value.length < 1 || value.length > MAX_FREE_TEXT) return false;
  if (value.includes(',') || CONTROL_CHARACTER.test(value)) return false;
  return dimension !== 'sensitives' || SENSITIVE_CODE.test(value);
};

const includeValues = (state: DatastoreFilterState, dimension: EndpointFilterDimension): string[] => {
  const selection = state[dimension];
  return selection && selection.mode === 'include' ? [...selection.values] : [];
};

/** The filter state as the fetchers send it: the include values of the four dimensions. */
export function filterParamsOf(state: DatastoreFilterState): EndpointFilterParams {
  return {
    sensitives: includeValues(state, 'sensitives'),
    documentClass: includeValues(state, 'documentClass'),
    category: includeValues(state, 'category'),
    dataElement: includeValues(state, 'dataElement'),
  };
}
