/**
 * A status chip (design-delta §2.1, §2.2; glossary §2): a tinted pill that always carries its tone's
 * icon and a non-empty label, so status is never colour alone (WCAG 1.4.1). The icon and label are
 * required props; the tone picks the token colour and the tint. An optional second line sits under
 * the label in grey.
 */
import { Icon } from '@iconify/react';
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
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[12px] ${t.text} ${t.chipBg} ${t.chipBorder} ${WEIGHT[weight]} ${className}`}
      data-tone={tone}
      data-k8s-kit=""
    >
      <Icon icon={icon} width={14} aria-hidden="true" className="shrink-0" />
      <span className="inline-flex flex-col leading-tight">
        <span>{label}</span>
        {detail && <span className="font-normal text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">{detail}</span>}
      </span>
    </span>
  );
}

export default StatusChip;
