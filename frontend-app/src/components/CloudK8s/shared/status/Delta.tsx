/**
 * Money saved / extra as text (design-delta §2.1: money is the one place colour appears as text,
 * and it carries its own icon). Green `money-saved` for a saving, amber `money-extra` for extra
 * cost. The caller passes the already-formatted amount string (e.g. "$42/mo"); this component only
 * frames and colours it, and always shows the arrow icon so the sign is not carried by colour alone.
 */
import { Icon } from '@iconify/react';

export interface DeltaProps {
  /** 'saved' is green, 'extra' is amber. */
  direction: 'saved' | 'extra';
  /** The formatted money string, e.g. "$42/mo". */
  amount: string;
  /** Optional words after the amount, e.g. "saved" / "more". */
  suffix?: string;
  className?: string;
}

export function Delta({ direction, amount, suffix, className = '' }: DeltaProps) {
  const saved = direction === 'saved';
  return (
    <span
      className={`inline-flex items-center gap-1 text-[13px] font-semibold tabular-nums ${saved ? 'text-money-saved' : 'text-money-extra'} ${className}`}
      data-delta={direction}
    >
      <Icon icon={saved ? 'mdi:trending-down' : 'mdi:trending-up'} width={14} aria-hidden="true" className="shrink-0" />
      <span>{amount}</span>
      {suffix && <span className="font-normal">{suffix}</span>}
    </span>
  );
}

export default Delta;
