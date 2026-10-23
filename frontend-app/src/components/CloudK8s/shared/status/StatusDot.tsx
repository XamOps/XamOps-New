/**
 * StatusDot (FEAT-001): the smallest status primitive — a tiny toned dot beside a visible status
 * word. No pill, no chip tint; the dot is the one spot of meaning colour and the word carries the
 * meaning for anyone who can't see the colour.
 *
 * The root carries `data-tone={tone}` and `data-k8s-kit=""`, the dot is a MANDATORY
 * `aria-hidden="true"` <svg> circle filled with `currentColor` in the tone's `text-status-*` class,
 * and the label is a visible BODY word. This mirrors colourAudit check #3: every `[data-tone]`
 * element has an `svg[aria-hidden="true"]` and non-empty text.
 */
import type { Tone } from './statusTones';
import { toneClasses } from './toneClasses';
import { BODY } from '../typeScale';

export interface StatusDotProps {
  tone: Tone;
  /** The visible status word, e.g. "Running", "Failed". Required so colour never stands alone. */
  label: string;
  /**
   * Optional secondary text after the word, in the muted chrome grey (never a meaning colour): an
   * age ("3 h"), a count, or a one-line qualifier. The dot stays the one spot of meaning colour.
   */
  detail?: string | null;
  className?: string;
}

export function StatusDot({ tone, label, detail, className = '' }: StatusDotProps) {
  const t = toneClasses(tone);
  return (
    <span
      className={`inline-flex items-center gap-1.5 ${BODY} ${className}`.trim()}
      data-tone={tone}
      data-k8s-kit=""
    >
      <svg width="9" height="9" viewBox="0 0 9 9" aria-hidden="true" className={`shrink-0 ${t.text}`}>
        <circle cx="4.5" cy="4.5" r="4" fill="currentColor" />
      </svg>
      <span>{label}</span>
      {detail && <span className="text-[color:var(--chrome-muted)]">{detail}</span>}
    </span>
  );
}

export default StatusDot;
