/**
 * Status as icon + label + weight, never colour alone (docs/cloudk8s/ui-revamp-design.md §14, WCAG 1.4.1).
 */
import { Icon } from '@iconify/react';

export type StatusState = 'healthy' | 'attention' | 'error' | 'not-set-up' | 'unknown' | 'simulated' | 'unverified';

interface StatusStyle {
  icon: string;
  className: string;
}

/** Icon ids are string literals so the offline bundle picks them up. */
const STYLES: Record<StatusState, StatusStyle> = {
  healthy: { icon: 'mdi:check-circle', className: 'font-semibold text-[rgba(0,0,0,0.88)]' },
  attention: { icon: 'mdi:clock-alert-outline', className: 'font-semibold text-[rgba(0,0,0,0.88)]' },
  error: {
    icon: 'mdi:alert-octagon',
    className: 'font-bold text-[rgba(0,0,0,0.88)] border border-black rounded px-1',
  },
  'not-set-up': { icon: 'mdi:circle-off-outline', className: 'font-normal text-[rgba(0,0,0,0.6)]' },
  unknown: { icon: 'mdi:help-circle-outline', className: 'font-normal text-[rgba(0,0,0,0.6)]' },
  simulated: { icon: 'mdi:flask-outline', className: 'font-semibold text-[rgba(0,0,0,0.88)]' },
  unverified: { icon: 'mdi:progress-question', className: 'font-normal text-[rgba(0,0,0,0.6)]' },
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
    <span className={`inline-flex items-center gap-1 text-[12px] ${style.className} ${className}`} data-status={state}>
      <Icon icon={style.icon} width={14} aria-hidden="true" className="shrink-0" />
      <span>{label}</span>
      {detail && <span className="font-normal text-[rgba(0,0,0,0.6)]">· {detail}</span>}
    </span>
  );
}

export default StatusMark;
