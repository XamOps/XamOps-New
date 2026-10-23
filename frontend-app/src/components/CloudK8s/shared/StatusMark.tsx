/**
 * Status as a quiet DOT + label, never a filled tinted pill and never colour alone (CLAUDE.md;
 * WCAG 1.4.1). This is the single place every CloudK8s status mark renders, so routing it through
 * the dot+word treatment (StatusDot) replaces the heavy coloured pills on every table row, the
 * Access card, the How-it-works steps, etc. in one change. It keeps the historical `StatusState`
 * union, `STATUS_ICONS` map and props so every existing caller (OverviewPanel, SummaryPanel,
 * WorkloadsPanel, the table "State"/"Status" columns, …) still compiles unchanged. The optional
 * `detail` (e.g. an age "3 h") renders as muted secondary text after the word.
 */
import { toneClasses } from './status/toneClasses';
import { BODY, SECONDARY } from './typeScale';
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

/**
 * Each status state → its meaning tone, for callers (e.g. OverviewPanel's AccessStatus) that render
 * the state through StatusDot directly and need the tone without the icon/weight. Derived from the
 * same STYLES map so it can never drift from StatusMark's own tone choice.
 */
export const STATUS_TONES: Record<StatusState, Tone> = Object.fromEntries(
  Object.entries(STYLES).map(([state, style]) => [state, style.tone]),
) as Record<StatusState, Tone>;

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
  const t = toneClasses(style.tone);
  const weightClass =
    style.weight === 'bold' ? 'font-bold' : style.weight === 'semibold' ? 'font-semibold' : 'font-normal';
  // Dot + word, no pill fill/tint: the dot is the one spot of meaning colour and the word carries
  // the meaning for anyone who can't see colour. `data-tone` + the mandatory aria-hidden <svg>
  // satisfy colourAudit check #3, exactly like StatusDot.
  return (
    <span
      className={`inline-flex items-center gap-1.5 ${BODY} ${className}`.trim()}
      data-tone={style.tone}
      data-k8s-kit=""
    >
      <svg width="9" height="9" viewBox="0 0 9 9" aria-hidden="true" className={`shrink-0 ${t.text}`}>
        <circle cx="4.5" cy="4.5" r="4" fill="currentColor" />
      </svg>
      <span className={weightClass}>{label}</span>
      {detail ? <span className={SECONDARY}>· {detail}</span> : null}
    </span>
  );
}

export default StatusMark;
