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
  status: 'ok' | 'warning' | 'breached';
  label: string;
  percent: number;
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

/**
 * Get SLA status for the resolution deadline.
 * Uses closedAt if ticket is closed, otherwise uses current time.
 */
export const getSlaStatus = (
  createdAt: string,
  severity: string,
  isClosed: boolean,
  closedAt?: string
): SlaStatus => {
  const resolutionHours = SLA_RESOLUTION_HOURS[severity] ?? 48;
  const created = new Date(createdAt).getTime();
  const resolvedTime = isClosed && closedAt ? new Date(closedAt).getTime() : Date.now();
  const elapsedHours = (resolvedTime - created) / (1000 * 60 * 60);
  const percent = Math.min(100, (elapsedHours / resolutionHours) * 100);

  if (isClosed) {
    if (elapsedHours <= resolutionHours) {
      return { status: 'ok', label: 'Resolved within SLA', percent: 100 };
    }
    const hoursOver = Math.round(elapsedHours - resolutionHours);
    return { status: 'breached', label: `${hoursOver}h over SLA`, percent: 100 };
  }

  const remaining = resolutionHours - elapsedHours;
  if (remaining <= 0) {
    const hoursOver = Math.abs(Math.round(remaining));
    return { status: 'breached', label: `${hoursOver}h overdue`, percent: 100 };
  }
  if (percent >= 80) {
    const mins = remaining < 1 ? Math.round(remaining * 60) + 'm' : Math.round(remaining) + 'h';
    return { status: 'warning', label: `${mins} remaining`, percent };
  }
  const hrs = remaining < 1 ? Math.round(remaining * 60) + 'm' : Math.round(remaining) + 'h';
  return { status: 'ok', label: `${hrs} remaining`, percent };
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
