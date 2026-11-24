import { slaResolutionMs } from './timeUtils';

/**
 * Works out WHEN a ticket's resolution SLA was breached (epoch ms), or null if it has not been
 * breached or the moment cannot be determined reliably.
 *
 * The backend stores no breach timestamp, only the stop-the-clock state (slaActiveMs /
 * slaRunningSince), so the moment is derived:
 *   1. Exact: if the clock is running and the banked time is still under target, the breach is
 *      slaRunningSince + (target - banked).
 *   2. Replay: otherwise the breach happened in an earlier running interval, so the clock is
 *      replayed from the reply history (non-internal admin reply pauses, customer reply resumes).
 *      The replay is accepted only if it ends in the same state the backend recorded, so a
 *      timeline that cannot be reconstructed yields null instead of a wrong time.
 *
 * Used by the BillOps admin view only.
 */

export interface BreachTicket {
  createdAt: string;
  severity: string;
  status?: string;
  closedAt?: string | null;
  slaActiveMs?: number | null;
  slaRunningSince?: string | null;
}

export interface BreachReply {
  createdAt?: string | null;
  admin?: boolean;
  isInternal?: boolean;
}

const TOLERANCE_MS = 60_000;

const toMs = (v?: string | null): number => (v ? new Date(v).getTime() : NaN);

function replay(
  startRunning: boolean,
  created: number,
  target: number,
  events: { t: number; admin: boolean }[],
  ticket: BreachTicket,
  now: number
): number | null | undefined {
  let running = startRunning;
  let runStart = created;
  let banked = 0;
  let breachAt: number | null = null;

  const bank = (until: number) => {
    const delta = Math.max(0, until - runStart);
    if (breachAt == null && banked + delta > target) breachAt = runStart + (target - banked);
    banked += delta;
    running = false;
  };

  for (const e of events) {
    if (e.admin) {
      if (running) bank(e.t);
    } else if (!running) {
      running = true;
      runStart = e.t;
    }
  }

  const actualBanked = ticket.slaActiveMs || 0;
  const actualSince = toMs(ticket.slaRunningSince);
  const isClosed = ticket.status === 'CLOSED';

  if (isClosed) {
    if (running) bank(toMs(ticket.closedAt) || now);
    if (Math.abs(banked - actualBanked) > TOLERANCE_MS) return undefined;
  } else if (Number.isNaN(actualSince)) {
    if (running) return undefined;
    if (Math.abs(banked - actualBanked) > TOLERANCE_MS) return undefined;
  } else {
    if (!running) return undefined;
    if (Math.abs(banked - actualBanked) > TOLERANCE_MS) return undefined;
    if (Math.abs(runStart - actualSince) > TOLERANCE_MS) return undefined;
    if (breachAt == null && banked + (now - runStart) > target) breachAt = runStart + (target - banked);
  }
  return breachAt;
}

export function computeSlaBreachAt(
  ticket: BreachTicket,
  replies: BreachReply[] | undefined,
  now: number = Date.now()
): number | null {
  if (ticket.slaActiveMs == null && ticket.slaRunningSince == null) return null;
  const target = slaResolutionMs(ticket.severity);
  const banked = ticket.slaActiveMs || 0;
  const since = toMs(ticket.slaRunningSince);
  const running = !Number.isNaN(since) && ticket.status !== 'CLOSED';

  // 1. Exact: breach falls inside the interval that is running right now.
  if (running && banked < target) {
    const at = since + (target - banked);
    return at <= now ? at : null;
  }
  // Under target and not running: never breached.
  if (!running && banked < target) return null;

  // 2. Breach happened in an earlier interval: replay the reply history.
  const created = toMs(ticket.createdAt);
  if (Number.isNaN(created)) return null;
  const events = (replies ?? [])
    .map((r) => ({ t: toMs(r.createdAt), admin: !!r.admin, internal: !!r.isInternal }))
    .filter((e) => !Number.isNaN(e.t) && !(e.admin && e.internal))
    .sort((a, b) => a.t - b.t)
    .map((e) => ({ t: e.t, admin: e.admin }));

  // Web tickets start running; admin-raised tickets start paused. Whichever replay ends in the
  // recorded state is the right one.
  for (const startRunning of [true, false]) {
    const result = replay(startRunning, created, target, events, ticket, now);
    if (result !== undefined) return result;
  }
  return null;
}

/** Index of the first reply sent after the breach; replies.length if the breach is after them all. */
export function breachInsertIndex(breachAt: number, replies: BreachReply[]): number {
  for (let i = 0; i < replies.length; i++) {
    const t = toMs(replies[i].createdAt);
    if (!Number.isNaN(t) && t > breachAt) return i;
  }
  return replies.length;
}
