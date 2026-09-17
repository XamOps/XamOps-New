/**
 * SlaCountdown
 *
 * Live, per-second resolution-SLA indicator for the ticket DETAIL pages.
 *
 * Uses the backend stop-the-clock values (slaActiveMs + slaRunningSince):
 *  - running  → counts down HH:MM:SS toward the severity target (green, then amber ≥80%)
 *  - paused   → freezes and shows "Paused · awaiting customer" (the ball is on the customer)
 *  - breached → static "Breached" (red)
 *  - closed   → static "Resolved within SLA" / "Xh over SLA"
 *
 * The per-second tick is intentionally confined to the detail page (one ticket on screen).
 * The admin list deliberately does NOT tick — it renders the badge from getSlaStatus once
 * per data refresh — to avoid re-rendering every row every second.
 */

import React from 'react';
import { getSlaStatus, formatHms, type SlaStatus } from '../../utils/timeUtils';

interface SlaCountdownProps {
  createdAt: string;
  severity: string;
  isClosed: boolean;
  closedAt?: string;
  slaActiveMs?: number;
  slaRunningSince?: string | null;
}

/** Re-renders once per second while mounted so a running clock ticks live. */
const useSecondTick = (active: boolean): void => {
  const [, force] = React.useState(0);
  React.useEffect(() => {
    if (!active) return;
    const id = setInterval(() => force((n) => (n + 1) % 1_000_000), 1000);
    return () => clearInterval(id);
  }, [active]);
};

const STATUS_TEXT: Record<SlaStatus['status'], string> = {
  ok: 'On Track',
  warning: 'At Risk',
  breached: 'Breached',
  paused: 'Paused',
};

/**
 * Hook the detail pages use: it ticks once per second while the clock is running and returns
 * the current stop-the-clock {@link SlaStatus} plus a ready-to-render big label (a live
 * HH:MM:SS countdown when running, or the status label when paused/breached/closed).
 * Pages render their own panel markup so they keep their existing styling.
 */
export const useSlaCountdown = (props: SlaCountdownProps): { sla: SlaStatus; bigLabel: string } => {
  const running = !props.isClosed && props.slaRunningSince != null;
  useSecondTick(running);

  const sla = getSlaStatus(
    props.createdAt,
    props.severity,
    props.isClosed,
    props.closedAt,
    { slaActiveMs: props.slaActiveMs, slaRunningSince: props.slaRunningSince }
  );

  // Running (ok/warning): live countdown. Paused: the SAME HH:MM:SS but frozen — the clock is
  // stopped while awaiting the customer, so the remaining budget doesn't move. Breached/closed
  // keep their descriptive labels.
  let bigLabel: string;
  if ((sla.status === 'ok' || sla.status === 'warning') && sla.remainingMs != null) {
    bigLabel = `${formatHms(sla.remainingMs)} remaining`;
  } else if (sla.status === 'paused' && sla.remainingMs != null) {
    bigLabel = `${formatHms(sla.remainingMs)} remaining · paused`;
  } else {
    bigLabel = sla.label;
  }

  return { sla, bigLabel };
};

export { STATUS_TEXT as SLA_STATUS_TEXT };
export type { SlaCountdownProps };
export default useSlaCountdown;
