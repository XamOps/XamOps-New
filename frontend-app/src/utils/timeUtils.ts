/**
 * Time utility functions for IST (India Standard Time) formatting
 * and SLA tracking calculations.
 *
 * All public-facing timestamps are displayed in IST (Asia/Kolkata, UTC+5:30).
 */

/**
 * Format a date string in IST.
 * - Default: "Mar 3, 2026, 2:45 PM IST"
 * - short=true (today): "2:45 PM IST"
 * - short=true (older): "Mar 3, 2:45 PM IST"
 */
export const formatIST = (dateString: string, opts?: { short?: boolean }): string => {
  if (!dateString) return '';
  const date = new Date(dateString);
  if (isNaN(date.getTime())) return '';

  if (opts?.short) {
    const nowIST = new Date().toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata' });
    const dateIST = date.toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata' });
    const isToday = nowIST === dateIST;

    const timePart = date.toLocaleTimeString('en-IN', {
      timeZone: 'Asia/Kolkata',
      hour: '2-digit',
      minute: '2-digit',
      hour12: true
    }).toUpperCase();

    if (isToday) {
      return `${timePart} IST`;
    }

    const datePart = date.toLocaleDateString('en-US', {
      timeZone: 'Asia/Kolkata',
      month: 'short',
      day: 'numeric'
    });
    return `${datePart}, ${timePart} IST`;
  }

  const formatted = date.toLocaleString('en-US', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: true
  });
  return `${formatted} IST`;
};

// -----------------------------------------------------------
// SLA Tracking
// -----------------------------------------------------------

export interface SlaStatus {
  status: 'ok' | 'warning' | 'breached' | 'paused';
  label: string;
  percent: number;
  /**
   * Milliseconds remaining until breach for a running clock (can be negative once breached).
   * null when the value isn't a live countdown (closed, or paused with no meaningful tick).
   */
  remainingMs: number | null;
  /** True while the stop-the-clock SLA is paused (waiting on the customer). */
  paused: boolean;
}

/** Resolution SLA targets in hours per severity */
const SLA_RESOLUTION_HOURS: Record<string, number> = {
  Critical: 4,
  Urgent: 4,
  High: 24,
  Normal: 48,
  Medium: 48,
  Low: 72,
  Info: 72,
};

/** Resolution SLA target in milliseconds for a severity (defaults to 48h). */
export const slaResolutionMs = (severity: string): number =>
  (SLA_RESOLUTION_HOURS[severity] ?? 48) * 3_600_000;

/**
 * Format a millisecond duration as a clock-style HH:MM:SS string (e.g. "02:59:58").
 * Negative input is clamped to 00:00:00 — callers show a breached label instead.
 */
export const formatHms = (ms: number): string => {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(h)}:${pad(m)}:${pad(s)}`;
};

/**
 * Effective elapsed "awaiting-admin" milliseconds under the stop-the-clock model.
 * elapsed = slaActiveMs + (running ? now - slaRunningSince : 0).
 */
export const slaElapsedMs = (
  slaActiveMs: number,
  slaRunningSince: string | null | undefined,
  now: number = Date.now()
): number => {
  const banked = slaActiveMs || 0;
  if (!slaRunningSince) return banked;
  const since = new Date(slaRunningSince).getTime();
  if (Number.isNaN(since)) return banked;
  return banked + Math.max(0, now - since);
};

/** First-response SLA targets in hours per severity */
const SLA_RESPONSE_HOURS: Record<string, number> = {
  Critical: 1,
  Urgent: 1,
  High: 4,
  Normal: 8,
  Medium: 8,
  Low: 24,
  Info: 24,
};

/** Optional stop-the-clock inputs from the backend (Phase 1). */
export interface SlaClock {
  slaActiveMs?: number | null;
  slaRunningSince?: string | null;
}

/**
 * Get SLA status for the resolution deadline.
 *
 * Stop-the-clock model (preferred): when the ticket carries {@link SlaClock} data
 * (slaActiveMs / slaRunningSince from the backend), elapsed time only counts while the
 * ball is in support's court. A paused clock (slaRunningSince == null on an open ticket)
 * reports status 'paused' and does not drift toward breach.
 *
 * Legacy fallback: if no clock data is supplied, it falls back to the original behaviour
 * of counting wall-clock time from createdAt — so existing callers/tickets still render.
 *
 * @param now injectable clock for live-ticking components; defaults to Date.now().
 */
export const getSlaStatus = (
  createdAt: string,
  severity: string,
  isClosed: boolean,
  closedAt?: string,
  clock?: SlaClock,
  now: number = Date.now()
): SlaStatus => {
  const targetMs = slaResolutionMs(severity);

  const hasClock = clock != null && (clock.slaActiveMs != null || clock.slaRunningSince != null);

  // ---- Closed: SLA is settled; report resolved-within / over regardless of model. ----
  if (isClosed) {
    let elapsedMs: number;
    if (hasClock) {
      // Paused at close, so the banked total is authoritative (slaRunningSince is null).
      elapsedMs = slaElapsedMs(clock!.slaActiveMs || 0, clock!.slaRunningSince, now);
    } else {
      const created = new Date(createdAt).getTime();
      const resolvedTime = closedAt ? new Date(closedAt).getTime() : now;
      elapsedMs = resolvedTime - created;
    }
    if (elapsedMs <= targetMs) {
      return { status: 'ok', label: 'Resolved within SLA', percent: 100, remainingMs: null, paused: false };
    }
    const hoursOver = Math.round((elapsedMs - targetMs) / 3_600_000);
    return { status: 'breached', label: `${hoursOver}h over SLA`, percent: 100, remainingMs: null, paused: false };
  }

  // ---- Open ticket ----
  if (hasClock) {
    const paused = clock!.slaRunningSince == null;
    const elapsedMs = slaElapsedMs(clock!.slaActiveMs || 0, clock!.slaRunningSince, now);
    const remainingMs = targetMs - elapsedMs;
    const percent = Math.min(100, (elapsedMs / targetMs) * 100);

    if (remainingMs <= 0) {
      return { status: 'breached', label: 'Breached', percent: 100, remainingMs, paused: false };
    }
    if (paused) {
      // Clock is frozen — the ball is in the customer's court. Not drifting toward breach.
      return { status: 'paused', label: 'Paused · awaiting customer', percent, remainingMs, paused: true };
    }
    const status: SlaStatus['status'] = percent >= 80 ? 'warning' : 'ok';
    return { status, label: `${formatHms(remainingMs)} remaining`, percent, remainingMs, paused: false };
  }

  // ---- Legacy fallback (no clock data): count wall-clock from createdAt. ----
  const created = new Date(createdAt).getTime();
  const elapsedMs = now - created;
  const remainingMs = targetMs - elapsedMs;
  const percent = Math.min(100, (elapsedMs / targetMs) * 100);
  if (remainingMs <= 0) {
    const hoursOver = Math.abs(Math.round(remainingMs / 3_600_000));
    return { status: 'breached', label: `${hoursOver}h overdue`, percent: 100, remainingMs, paused: false };
  }
  const remainingH = remainingMs / 3_600_000;
  const compact = remainingH < 1 ? Math.round(remainingH * 60) + 'm' : Math.round(remainingH) + 'h';
  if (percent >= 80) {
    return { status: 'warning', label: `${compact} remaining`, percent, remainingMs, paused: false };
  }
  return { status: 'ok', label: `${compact} remaining`, percent, remainingMs, paused: false };
};

/**
 * Get SLA label text for the response (first reply) deadline.
 */
export const getResponseSlaLabel = (
  createdAt: string,
  severity: string,
  chatStartedAt?: string
): string => {
  const responseHours = SLA_RESPONSE_HOURS[severity] ?? 8;
  const created = new Date(createdAt).getTime();

  if (chatStartedAt) {
    const responseTime = new Date(chatStartedAt).getTime();
    const elapsed = (responseTime - created) / (1000 * 60 * 60);
    if (elapsed <= responseHours) return `First reply in ${Math.round(elapsed * 10) / 10}h (within SLA)`;
    return `First reply in ${Math.round(elapsed * 10) / 10}h (SLA breached)`;
  }

  const elapsed = (Date.now() - created) / (1000 * 60 * 60);
  const remaining = responseHours - elapsed;
  if (remaining <= 0) return `Response ${Math.abs(Math.round(remaining))}h overdue`;
  return `Response due in ${Math.round(remaining)}h`;
};
