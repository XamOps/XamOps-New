/**
 * A status mark (design-delta §2.1, §2.2; glossary §2): a quiet toned DOT + a non-empty label, so
 * status is never colour alone (WCAG 1.4.1) and never a heavy filled pill. The owner asked for the
 * tinted pills to go across every CloudK8s page; this is the single renderer the direct callers
 * (WorkloadsTable, Activity, VPA, Policies, …) use, so dropping the pill chrome here removes the
 * pills on every table row and inline status at once. The `icon` prop is kept for API
 * compatibility but is no longer rendered as a pill icon — the dot is the one spot of meaning
 * colour. An optional second line sits under the label in muted grey.
 */
import type { Tone } from './statusTones';
import { toneClasses } from './toneClasses';

export interface StatusChipProps {
  tone: Tone;
  /** A bundled `mdi:*` id. Required: a chip is never colour alone. */
  icon: string;
  /** The visible wording. Required and non-empty. */
  label: string;
  /** Optional second line under the label. */
  detail?: string | null;
  /** Weight of the label. Error/attention rows run bolder in some callers. */
  weight?: 'normal' | 'semibold' | 'bold';
  className?: string;
}

const WEIGHT: Record<NonNullable<StatusChipProps['weight']>, string> = {
  normal: 'font-normal',
  semibold: 'font-semibold',
  bold: 'font-bold',
};

export function StatusChip({ tone, icon, label, detail, weight = 'semibold', className = '' }: StatusChipProps) {
  const t = toneClasses(tone);
  // Quiet dot + word — NO rounded-full pill, NO chip background/border tint. The toned dot is the
  // one spot of meaning colour; the label carries meaning without colour. A mandatory
  // aria-hidden <svg> + data-tone keep colourAudit check #3 satisfied. `icon` is intentionally unused.
  void icon;
  return (
    <span
      className={`inline-flex items-center gap-1.5 text-[12px] ${WEIGHT[weight]} ${className}`.trim()}
      data-tone={tone}
      data-k8s-kit=""
    >
      <svg width="9" height="9" viewBox="0 0 9 9" aria-hidden="true" className={`shrink-0 ${t.text}`}>
        <circle cx="4.5" cy="4.5" r="4" fill="currentColor" />
      </svg>
      <span className="inline-flex flex-col leading-tight">
        <span>{label}</span>
        {detail && <span className="font-normal text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">{detail}</span>}
      </span>
    </span>
  );
}

export default StatusChip;
