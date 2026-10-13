/**
 * Derives what trial enforcement would do with a tenant, mirroring
 * xamops-service TrialService.effectiveStatus (lines 70-83).
 *
 * The list endpoint reads the client columns raw and never runs effectiveStatus, so a row
 * can arrive as ACTIVE with an end date already in the past. Reproducing the precedence here
 * is the only way the screen tells an operator the same thing the filter would.
 *
 * Clock caveat: `daysRemaining` is computed on the server and is the authoritative countdown.
 * `trialEndAt` is a zone-less LocalDateTime.toString(), so parsing it reads the instant in
 * browser-local time; it is used for display and for the rule-3 lapsed check only.
 */

import type { TrialAdminRow, TrialVerdict } from '../types/billopsAdminTrials';

/**
 * Parses the backend's zone-less date-time string, e.g. "2026-10-09T12:23:57.50031".
 * Returns null for null input or anything Date cannot read, so a malformed value degrades
 * to "no end date" rather than reading as 1970 (which rule 3 would call expired).
 */
export const parseServerDateTime = (value: string | null | undefined): Date | null => {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
};

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

export const deriveTrialVerdict = (row: TrialAdminRow, now: Date = new Date()): TrialVerdict => {
  const converted = row.trialStatus === 'CONVERTED';

  // 1. Not trial-gated at all. Deliberately never quotes daysRemaining: the endpoint computes
  //    it from trial_end_at without looking at plan_type, so a converted tenant can still
  //    carry a positive number that would read as a live trial.
  if (row.planType !== 'TRIAL') {
    return {
      state: 'NONE',
      blocked: false,
      converted,
      label: converted ? 'Paid — converted from trial' : 'Paid — not trial-gated',
    };
  }

  // 2. An explicit EXPIRED status outranks a future end date — the filter checks the status
  //    first, so a row extended in the DB without clearing the status is still blocked.
  if (row.trialStatus === 'EXPIRED') {
    return {
      state: 'EXPIRED',
      blocked: true,
      converted: false,
      label: 'EXPIRED — tenant blocked (402)',
    };
  }

  const endAt = parseServerDateTime(row.trialEndAt);

  // 3. ACTIVE on paper, lapsed in fact. The interval is floored to whole days, so a trial
  //    that ended hours ago reads as 0 — the mirror image of rule 5's "less than a day left",
  //    and exactly the row an operator opens this screen to rescue.
  if (endAt && endAt.getTime() < now.getTime()) {
    const lapsedDays = Math.floor((now.getTime() - endAt.getTime()) / 86_400_000);
    return {
      state: 'EXPIRED',
      blocked: true,
      converted: false,
      label: lapsedDays > 0
        ? `EXPIRED — lapsed ${plural(lapsedDays, 'day')} ago`
        : 'EXPIRED — lapsed less than a day ago',
    };
  }

  // 4. A trial with no end date is never gated.
  if (!endAt) {
    return { state: 'ACTIVE', blocked: false, converted: false, label: 'Active — no end date set' };
  }

  // 5. Live trial. daysRemaining is truncated with toDays() server-side, so 0 means
  //    "ends within the day", not "ended".
  return {
    state: 'ACTIVE',
    blocked: false,
    converted: false,
    label: row.daysRemaining > 0
      ? `Active — ${plural(row.daysRemaining, 'day')} left`
      : 'Active — less than a day left',
  };
};

export default deriveTrialVerdict;
