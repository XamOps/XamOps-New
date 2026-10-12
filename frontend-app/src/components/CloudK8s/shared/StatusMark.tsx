/**
 * Status as icon + label, never colour alone (CLAUDE.md; WCAG 1.4.1). A thin wrapper over the new
 * StatusChip (components/CloudK8s/shared/status/StatusChip.tsx): it keeps the historical
 * `StatusState` union, `STATUS_ICONS` map and props so every existing caller (OverviewPanel,
 * SummaryPanel, WorkloadsPanel, …) still compiles, and routes the colour through StatusChip's tone
 * so there is a single place that pairs a status token with an icon and a label.
 */
import StatusChip from './status/StatusChip';
import type { Tone } from './status/statusTones';

export type StatusState = 'healthy' | 'attention' | 'error' | 'not-set-up' | 'unknown' | 'simulated' | 'unverified';

interface StateStyle {
  tone: Tone;
  icon: string;
  weight: 'normal' | 'semibold' | 'bold';
}

/** Icon ids are string literals so the offline bundle picks them up. Unchanged from before the wrapper. */
const STYLES: Record<StatusState, StateStyle> = {
  healthy: { tone: 'good', icon: 'mdi:check-circle', weight: 'semibold' },
  attention: { tone: 'warn', icon: 'mdi:clock-alert-outline', weight: 'semibold' },
  error: { tone: 'bad', icon: 'mdi:alert-octagon', weight: 'bold' },
  'not-set-up': { tone: 'off', icon: 'mdi:circle-off-outline', weight: 'normal' },
  unknown: { tone: 'off', icon: 'mdi:help-circle-outline', weight: 'normal' },
  simulated: { tone: 'info', icon: 'mdi:flask-outline', weight: 'semibold' },
  unverified: { tone: 'off', icon: 'mdi:progress-question', weight: 'normal' },
};

export const STATUS_ICONS: Record<StatusState, string> = Object.fromEntries(
  Object.entries(STYLES).map(([state, style]) => [state, style.icon]),
) as Record<StatusState, string>;

export interface StatusMarkProps {
  state: StatusState;
  /** Short label, e.g. "Connected", "Stale", "Not installed". */
  label: string;
  /** Secondary text after the label, e.g. an age: "3 h". */
  detail?: string | null;
  className?: string;
}

export function StatusMark({ state, label, detail, className = '' }: StatusMarkProps) {
  const style = STYLES[state];
  return (
    <StatusChip
      tone={style.tone}
      icon={style.icon}
      label={label}
      detail={detail ?? undefined}
      weight={style.weight}
      className={className}
    />
  );
}

export default StatusMark;
