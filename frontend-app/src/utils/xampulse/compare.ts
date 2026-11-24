/**
 * "Compare with" for charts: the same window a period, a day or a week earlier, drawn dashed in the same colour.
 * Lives in the URL (?compare=prev|day|week) so a link shows the same comparison. The old ?compare=1 means "previous".
 */
export type CompareMode = 'off' | 'prev' | 'day' | 'week';

export const COMPARE_MODES: [CompareMode, string][] = [['off', 'Off'], ['prev', 'Previous period'], ['day', 'Yesterday'], ['week', 'Last week']];

export function parseCompare(v: string | null): CompareMode {
  if (v === '1' || v === 'prev') return 'prev';
  return v === 'day' || v === 'week' ? v : 'off';
}

const DAY_SEC = 86_400;

/** Seconds back to compare with, or undefined when off. "Previous period" is the length of the range. */
export function compareOffsetSec(mode: CompareMode, fromMs: number, toMs: number): number | undefined {
  if (mode === 'day') return DAY_SEC;
  if (mode === 'week') return 7 * DAY_SEC;
  if (mode === 'prev') return Math.max(60, Math.round((toMs - fromMs) / 1000));
  return undefined;
}

/** How the dashed line is named in a legend: "CPU, last week". */
export function compareLabel(mode: CompareMode): string | null {
  return mode === 'prev' ? 'previous period' : mode === 'day' ? 'yesterday' : mode === 'week' ? 'last week' : null;
}
