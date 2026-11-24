/**
 * Filter state for the Datastores list: pure functions, shared by the list request, the filter-options
 * request and every filter control.
 *
 * Each dimension carries a mode. Include sends its values as `<dim>=a,b`, Exclude as `<dim>_exclude=a,b`,
 * and a dimension is never sent both ways. Both requests build their filter terms with
 * `buildFilterParams`, so the two cannot drift apart: the facet counts are disjunctive only while they
 * see exactly the list's filters.
 *
 * The request limits below mirror the backend's validation (`DatastoreParams`, `DatastoreFilterDimension`)
 * so the UI never builds a request the backend would answer with 400. The backend stays the enforcement
 * point.
 */

import type { DatastoreFilterOption } from '../../../types/dspmDatastores';

export type FilterMode = 'include' | 'exclude';

export interface DimensionSelection {
  mode: FilterMode;
  values: string[];
}

/** Dimension key → its selection. A dimension without values sends nothing, whatever its mode. */
export type DatastoreFilterState = Record<string, DimensionSelection>;

/** Most values one dimension may carry. */
export const MAX_FILTER_VALUES = 50;
/** Longest free-text filter value. */
export const MAX_FREE_TEXT = 128;
/** Longest search text (`q`). */
export const MAX_SEARCH = 200;

export type DimensionValueRule =
  | { kind: 'hex24' }
  | { kind: 'code' }
  | { kind: 'oneOf'; values: ReadonlySet<string> }
  | { kind: 'freeText' };

const HEX24: DimensionValueRule = { kind: 'hex24' };
const CODE: DimensionValueRule = { kind: 'code' };
const FREE_TEXT: DimensionValueRule = { kind: 'freeText' };

/** The value rule of every filter dimension the backend accepts. A dimension missing here cannot be sent. */
export const DIMENSION_VALUE_RULES: Readonly<Record<string, DimensionValueRule>> = {
  integration_id: HEX24,
  provider: CODE,
  region: FREE_TEXT,
  scan_status: { kind: 'oneOf', values: new Set(['NOT_SCANNED', 'CLASSIFIED', 'FAILED', 'STOPPED', 'SCANNING', 'SCHEDULED']) },
  severity: { kind: 'oneOf', values: new Set(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'NONE']) },
  sensitive_type: CODE,
  data_element: FREE_TEXT,
  selected: FREE_TEXT,
  os: CODE,
  agent_status: CODE,
  device_type: FREE_TEXT,
  serial_number: FREE_TEXT,
  hostname: FREE_TEXT,
  user: FREE_TEXT,
  // The Data Lineage page's policy filters (build 4).
  datastoreName: FREE_TEXT,
  label: FREE_TEXT,
};

/** Dimensions whose `<dim>_exclude` twin the backend accepts (exclusion verified against the vendor, A6). */
export const EXCLUDABLE_DIMENSIONS: ReadonlySet<string> = new Set([
  'integration_id', 'provider', 'region', 'scan_status', 'severity', 'sensitive_type', 'data_element', 'agent_status',
  'datastoreName', 'label',
]);

const HEX24_PATTERN = /^[0-9a-fA-F]{24}$/;
const CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/;
/** What Java's `Character.isISOControl` rejects: C0, DEL and C1. */
const CONTROL_CHARACTER = /[\u0000-\u001F\u007F-\u009F]/;
const CONTROL_CHARACTERS = /[\u0000-\u001F\u007F-\u009F]/g;

function matchesRule(rule: DimensionValueRule, value: string): boolean {
  switch (rule.kind) {
    case 'hex24': return HEX24_PATTERN.test(value);
    case 'code': return CODE_PATTERN.test(value);
    case 'oneOf': return rule.values.has(value);
    case 'freeText':
      return value.length >= 1 && value.length <= MAX_FREE_TEXT && !value.includes(',') && !CONTROL_CHARACTER.test(value);
  }
}

/**
 * Whether an option can be sent as a filter value. False when it fails its dimension's rule (a blank
 * value, a comma, more than 128 characters, a lower-case code) or has leading or trailing whitespace,
 * which the backend would trim into a different value. Such options render disabled.
 */
export function isSelectable(dimension: string, option: { value: string }): boolean {
  const rule = DIMENSION_VALUE_RULES[dimension];
  if (!rule) return false;
  if (option.value !== option.value.trim()) return false;
  return matchesRule(rule, option.value);
}

/** Search text as sent: control characters become spaces, then trimmed. Null when blank (no `q`). */
export function normalizeSearch(text: string): string | null {
  const normalized = text.replace(CONTROL_CHARACTERS, ' ').trim();
  return normalized === '' ? null : normalized;
}

/** `[param, value]` pairs: `[dim, 'a,b']` in Include mode, `[dim_exclude, 'a,b']` in Exclude mode. */
export function buildFilterParams(state: DatastoreFilterState): Array<[string, string]> {
  const params: Array<[string, string]> = [];
  for (const [dimension, selection] of Object.entries(state)) {
    if (selection.values.length === 0) continue;
    params.push([selection.mode === 'exclude' ? `${dimension}_exclude` : dimension, selection.values.join(',')]);
  }
  return params;
}

/** Adds or removes one value. Adding a 51st value is refused: the state comes back unchanged. */
export function toggleValue(state: DatastoreFilterState, dimension: string, value: string): DatastoreFilterState {
  const current = state[dimension];
  const values = current?.values ?? [];
  if (values.includes(value)) {
    return { ...state, [dimension]: { mode: current?.mode ?? 'include', values: values.filter(v => v !== value) } };
  }
  if (values.length >= MAX_FILTER_VALUES) return state;
  return { ...state, [dimension]: { mode: current?.mode ?? 'include', values: [...values, value] } };
}

/**
 * Switches a dimension between Include and Exclude, keeping its values: they move to the other parameter.
 * Exclude is refused on a dimension outside `EXCLUDABLE_DIMENSIONS`.
 */
export function setMode(state: DatastoreFilterState, dimension: string, mode: FilterMode): DatastoreFilterState {
  if (mode === 'exclude' && !EXCLUDABLE_DIMENSIONS.has(dimension)) return state;
  const current = state[dimension];
  if ((current?.mode ?? 'include') === mode) return state;
  return { ...state, [dimension]: { mode, values: current?.values ?? [] } };
}

/**
 * "Select all" over the options currently listed: adds the selectable, unchecked ones in list order until
 * the dimension holds `MAX_FILTER_VALUES`. `stoppedShort` is true when the limit left some of them out.
 */
export function selectAllVisible(
  state: DatastoreFilterState,
  dimension: string,
  visibleOptions: ReadonlyArray<{ value: string }>,
  /** Which options may be chosen; a local filter, whose values are never sent, accepts every one. */
  canSelect: (option: { value: string }) => boolean = option => isSelectable(dimension, option),
): { state: DatastoreFilterState; stoppedShort: boolean } {
  const current = state[dimension];
  const values = [...(current?.values ?? [])];
  let stoppedShort = false;
  for (const option of visibleOptions) {
    if (!canSelect(option) || values.includes(option.value)) continue;
    if (values.length >= MAX_FILTER_VALUES) {
      stoppedShort = true;
      break;
    }
    values.push(option.value);
  }
  if (values.length === (current?.values.length ?? 0)) return { state, stoppedShort };
  return { state: { ...state, [dimension]: { mode: current?.mode ?? 'include', values } }, stoppedShort };
}

/** Drops a dimension's values and mode. */
export function clearDimension(state: DatastoreFilterState, dimension: string): DatastoreFilterState {
  if (!(dimension in state)) return state;
  const next = { ...state };
  delete next[dimension];
  return next;
}

/** One row of a filter's option list. `count` is null when the vendor sent none or the value is remembered. */
export interface ListedOption {
  value: string;
  label: string;
  count: number | null;
  checked: boolean;
}

/**
 * The options to render for one dimension: the response's options, then every selected value the
 * response left out, appended checked with its remembered label and no count.
 *
 * In Exclude mode the vendor drops the excluded values from the dimension's own options, so without this
 * an excluded value could never be un-excluded.
 */
export function mergeSelectedOptions(
  options: ReadonlyArray<DatastoreFilterOption>,
  selection: DimensionSelection | undefined,
  labelMemory: ReadonlyMap<string, string>,
): ListedOption[] {
  const selected = new Set(selection?.values ?? []);
  const listed: ListedOption[] = options.map(option => ({
    value: option.value, label: option.label, count: option.count, checked: selected.has(option.value),
  }));
  const present = new Set(options.map(option => option.value));
  for (const value of selection?.values ?? []) {
    if (!present.has(value)) listed.push({ value, label: labelMemory.get(value) ?? value, count: null, checked: true });
  }
  return listed;
}
