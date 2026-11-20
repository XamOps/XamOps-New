/**
 * Pure rules of the Remediation › Activity page (design 6.7). The filter options, card rules, approver
 * line and status colours are the console bundle's (the activity filter config in `index-20241445.js`,
 * `index-4ef0511f.js`, `constants-a63ffc8b.js` and `remediation-caa4d6cd.js`, read 2026-11-19); the
 * codes match the backend's `PageLabels`.
 */
import type { RemediationItem } from '../../../types/dspmPages';
import type { SelectFilterOption } from '../datastores/ui/SelectFilter';

/** The Status filter, in the console's order. The filter says "Waiting Approval"; the row chip "Awaiting Approval". */
export const REMEDIATION_STATUS_OPTIONS: ReadonlyArray<SelectFilterOption> = [
  { value: 'WAITING_APPROVAL', label: 'Waiting Approval' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'SCHEDULED', label: 'Scheduled' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'NOOP', label: 'No Change' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'ROLLED_BACK', label: 'Rolled Back' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

/** The Action filter, in the console's order (its filter lists no Delete Email). */
export const REMEDIATION_ACTION_OPTIONS: ReadonlyArray<SelectFilterOption> = [
  { value: 'MAKE_PRIVATE', label: 'Make Private' },
  { value: 'REMOVE_EXTERNAL_ACCESS', label: 'Remove External Access' },
  { value: 'REMOVE_EXTERNAL_ACCESS_BY_TIME', label: 'Remove External Access (timed)' },
  { value: 'REVOKE_RECIPIENT', label: 'Revoke Recipient' },
  { value: 'DOWNGRADE_LINK_SCOPE', label: 'Downgrade Link Scope' },
  { value: 'SET_LINK_EXPIRY', label: 'Set Link Expiry' },
  { value: 'MOVE_TO_QUARANTINE', label: 'Move to Quarantine' },
  { value: 'DELETE_FILE', label: 'Delete File' },
  { value: 'BLOCK_USER_QUERY', label: 'Block User Query' },
];

/** Statuses that can still change: while a shown row has one, the list refreshes (FR-REM-5). */
export const PENDING_STATUSES: ReadonlySet<string> = new Set(['PENDING', 'WAITING_APPROVAL', 'SCHEDULED', 'IN_PROGRESS']);

export const isPending = (item: RemediationItem): boolean => item.status !== null && PENDING_STATUSES.has(item.status);

/** The status line under a row's chip: "Approver: <first> +N" (or the one approver), else null. */
export function approverText(approvers: ReadonlyArray<string>): string | null {
  if (approvers.length === 0) return null;
  return approvers.length > 1 ? `${approvers[0]} +${approvers.length - 1}` : approvers[0];
}

export type RemediationCard = 'total' | 'waitingApproval' | 'inProgressScheduled' | 'completed' | 'failed';

/**
 * The status a card (or a segment of the Total bar) filters by; null when it cannot filter. Total
 * filters nothing. "In progress / scheduled" picks the one of its two statuses that has runs: Scheduled
 * when only scheduled runs exist, In Progress otherwise, and nothing when both have runs, since the
 * status filter takes one value (the console's rule).
 */
export function cardStatus(card: RemediationCard, statusCounts: Readonly<Record<string, number>>): string | null {
  switch (card) {
    case 'total': return null;
    case 'waitingApproval': return 'WAITING_APPROVAL';
    case 'completed': return 'COMPLETED';
    case 'failed': return 'FAILED';
    case 'inProgressScheduled': {
      const inProgress = statusCounts.IN_PROGRESS ?? 0;
      const scheduled = statusCounts.SCHEDULED ?? 0;
      if (inProgress > 0 && scheduled > 0) return null;
      return scheduled > 0 ? 'SCHEDULED' : 'IN_PROGRESS';
    }
  }
}

/** Whether a card shows as the active filter. */
export function cardActive(card: RemediationCard, status: string): boolean {
  switch (card) {
    case 'total': return false;
    case 'waitingApproval': return status === 'WAITING_APPROVAL';
    case 'inProgressScheduled': return status === 'IN_PROGRESS' || status === 'SCHEDULED';
    case 'completed': return status === 'COMPLETED';
    case 'failed': return status === 'FAILED';
  }
}

/** The Status filter after a card click: the card's status, or none when it was already chosen (a toggle). */
export const nextStatusFilter = (current: string, clicked: string): string => (current === clicked ? '' : clicked);

export type StatusTone = 'neutral' | 'warning' | 'purple' | 'processing' | 'success' | 'error';

/** A status's chip colour, as the console's tag colours. */
const STATUS_TONES: Record<string, StatusTone> = {
  PENDING: 'neutral',
  WAITING_APPROVAL: 'warning',
  SCHEDULED: 'purple',
  IN_PROGRESS: 'processing',
  COMPLETED: 'success',
  NOOP: 'neutral',
  FAILED: 'error',
  REJECTED: 'error',
  ROLLED_BACK: 'neutral',
  CANCELLED: 'neutral',
};

export const statusTone = (status: string | null): StatusTone => (status !== null ? STATUS_TONES[status] : undefined) ?? 'neutral';

/** Chip classes and the drawer card's colour bar, per tone. Full literals, so Tailwind generates them. */
export const TONE_CLASSES: Record<StatusTone, { chip: string; bar: string }> = {
  neutral: { chip: 'border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.65)]', bar: 'bg-[rgba(0,0,0,0.25)]' },
  warning: { chip: 'border-amber-200 bg-amber-50 text-amber-700', bar: 'bg-amber-500' },
  purple: { chip: 'border-violet-200 bg-violet-50 text-violet-700', bar: 'bg-violet-500' },
  processing: { chip: 'border-sky-200 bg-sky-50 text-sky-700', bar: 'bg-sky-500' },
  success: { chip: 'border-emerald-200 bg-emerald-50 text-emerald-700', bar: 'bg-emerald-500' },
  error: { chip: 'border-rose-200 bg-rose-50 text-rose-700', bar: 'bg-rose-500' },
};

/** A Path the drawer may open in a new tab: only http and https URLs. */
export const isWebLink = (path: string | null): path is string => path !== null && /^https?:\/\//i.test(path);
