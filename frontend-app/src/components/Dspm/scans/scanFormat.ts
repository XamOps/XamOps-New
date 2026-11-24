/**
 * Pure text rules of the Scan Management drawer (design 6.8). The texts and filter codes are the
 * console's (`ScanManagementDrawer-d26ecb8a.js`, read 2026-11-19); the codes match the backend's
 * `PageLabels`.
 */
import type { ScanRun, ScanScheduleTiming } from '../../../types/dspmScans';
import type { SelectFilterOption } from '../datastores/ui/SelectFilter';
import type { StatusTone } from '../management/remediationModel';

/** Environment filter (`platform`) of Scan Scopes, Schedules and Scan History. */
export const PLATFORM_OPTIONS: ReadonlyArray<SelectFilterOption> = [
  { value: 'cloud', label: 'Cloud' },
  { value: 'on_prem', label: 'On-Prem' },
  { value: 'endpoint', label: 'Endpoint' },
];

/** Scan Status filter of Schedules, Scan History and a run's datastores. */
export const RUN_STATUS_OPTIONS: ReadonlyArray<SelectFilterOption> = [
  { value: 'SUCCESS', label: 'Success' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'IN_PROGRESS', label: 'Progress' },
  { value: 'PAUSED', label: 'Paused' },
  { value: 'NOT_STARTED', label: 'Not started' },
];

/** Coverage filter of Schedules and Scan History. */
export const COVERAGE_OPTIONS: ReadonlyArray<SelectFilterOption> = [
  { value: 'SELECTED_DATASTORES', label: 'Selected datastores' },
  { value: 'ALL_DATASTORES', label: 'All datastores' },
];

/** Trigger filter of Scan History. */
export const TRIGGER_OPTIONS: ReadonlyArray<SelectFilterOption> = [
  { value: 'SCHEDULED', label: 'Scheduled' },
  { value: 'IMMEDIATE', label: 'Immediate' },
];

/** User Status filter of the SaaS users table. */
export const USER_STATUS_OPTIONS: ReadonlyArray<SelectFilterOption> = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'SUSPENDED', label: 'Suspended' },
];

/** Slack channel filters, as the console's bundle lists them (single choice here, design 4.5). */
export const CHANNEL_TYPE_OPTIONS: ReadonlyArray<SelectFilterOption> = [
  { value: 'PUBLIC', label: 'Public' },
  { value: 'PRIVATE', label: 'Private' },
];
export const CHANNEL_STATUS_OPTIONS: ReadonlyArray<SelectFilterOption> = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'ARCHIVED', label: 'Archived' },
];
export const CHANNEL_SCAN_OPTIONS: ReadonlyArray<SelectFilterOption> = [
  { value: 'ENABLED', label: 'Enabled' },
  { value: 'DISABLED', label: 'Disabled' },
];
/** The console's New filter has one option and sends `isNew=true`; otherwise nothing. */
export const CHANNEL_NEW_OPTIONS: ReadonlyArray<SelectFilterOption> = [{ value: 'true', label: 'New only' }];

/** A schedule's name; the console's text for an unnamed (legacy) one. */
export const scheduleName = (name: string | null): string => (name !== null && name.trim() !== '' ? name : 'Untitled schedule');

/** The Scan Status cell of a schedule that never ran; null once it has. */
export const lastRunText = (lastRunAt: number | null): string | null => (lastRunAt === null ? 'Not run yet' : null);

/** The Targets cell: the count, "Up to " first when the backend estimates it; "-" when unknown. */
export function targetsText(total: number | null, isEstimate: boolean): string {
  if (total === null) return '-';
  return `${isEstimate ? 'Up to ' : ''}${total.toLocaleString('en-US')}`;
}

const DAY_NAMES: Record<string, string> = {
  MO: 'Mon', TU: 'Tue', WE: 'Wed', TH: 'Thu', FR: 'Fri', SA: 'Sat', SU: 'Sun',
};

/** "MONDAY", "mon" or "MO" → "Mon"; anything else as sent. */
function dayName(day: string): string {
  return DAY_NAMES[day.trim().slice(0, 2).toUpperCase()] ?? day;
}

/**
 * The Frequency cell: "One-time", or "Recurring" with the repeat below ("Every day", "Every Mon, Wed",
 * "Day 5 of every month"); null when the schedule has no timing.
 */
export function frequencyText(timing: ScanScheduleTiming | null): { label: string; detail: string | null } | null {
  if (timing === null) return null;
  if (timing.type === 'once') return { label: 'One-time', detail: null };
  let detail: string | null = null;
  switch (timing.recurrenceType) {
    case 'DAILY':
      detail = 'Every day';
      break;
    case 'WEEKLY':
      detail = timing.repeatOn.length > 0 ? `Every ${timing.repeatOn.map(dayName).join(', ')}` : null;
      break;
    case 'MONTHLY':
      detail = timing.dayOfMonth !== null ? `Day ${timing.dayOfMonth} of every month` : null;
      break;
  }
  return { label: 'Recurring', detail };
}

/** The Progress cell: "<completed> / <total>", the phase below unless COMPLETED, "<n> skipped" when any were. */
export function progressText(run: Pick<ScanRun, 'completedDatastores' | 'totalDatastores' | 'phase' | 'phaseLabel' | 'skippedCount'>): {
  main: string; phase: string | null; skipped: string | null;
} {
  return {
    main: `${run.completedDatastores.toLocaleString('en-US')} / ${run.totalDatastores.toLocaleString('en-US')}`,
    phase: run.phase !== null && run.phase !== 'COMPLETED' ? run.phaseLabel ?? run.phase : null,
    skipped: run.skippedCount > 0 ? `${run.skippedCount.toLocaleString('en-US')} skipped` : null,
  };
}

/** The Scan Status chip's title: "<failed> of <total> failed" when any datastore failed. */
export function failedTitle(run: Pick<ScanRun, 'statusCounts' | 'totalDatastores'>): string | undefined {
  const failed = run.statusCounts.FAILED ?? 0;
  return failed > 0 ? `${failed.toLocaleString('en-US')} of ${run.totalDatastores.toLocaleString('en-US')} failed` : undefined;
}

/** A run's duration: "2h 5m", "4m 10s", "35s"; "-" when unknown. */
export function durationText(seconds: number | null): string {
  if (seconds === null || seconds < 0) return '-';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

/** "SELECTED_DATASTORES" → "Selected Datastores"; null stays null. */
export function prettifyCode(code: string | null): string | null {
  if (code === null || code.trim() === '') return null;
  return code
    .toLowerCase()
    .split(/[_\s]+/)
    .filter(Boolean)
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

const RUN_TONES: Record<string, StatusTone> = {
  SUCCESS: 'success',
  FAILED: 'error',
  IN_PROGRESS: 'processing',
  PAUSED: 'warning',
  NOT_STARTED: 'neutral',
};

/** A run's (or a run datastore's) chip colour. */
export const runStatusTone = (status: string | null): StatusTone => (status !== null ? RUN_TONES[status] : undefined) ?? 'neutral';
