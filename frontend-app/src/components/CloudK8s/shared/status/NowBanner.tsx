/**
 * The "Now" banner (design-delta §4.1; glossary §3): the one plain sentence at the top of every
 * automation page, with a "Now" label and `mdi:record-circle-outline`, and the state chips under it.
 * The sentence itself is built by the pure `nowSentence` builders; this component only renders a
 * computed `NowSentence`, so what it shows is fully testable without the DOM.
 */
import { Icon } from '@iconify/react';
import type { NowSentence } from './nowSentence';
import StatusChip from './StatusChip';

export interface NowBannerProps {
  sentence: NowSentence;
  className?: string;
}

export function NowBanner({ sentence, className = '' }: NowBannerProps) {
  return (
    <div
      className={`rounded-[8px] border border-[rgba(0,0,0,0.08)] bg-white px-4 py-3 dark:border-[rgba(255,255,255,0.1)] dark:bg-[#141414] ${className}`}
      data-now-banner=""
      data-k8s-kit=""
    >
      <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">
        <Icon icon="mdi:record-circle-outline" width={13} aria-hidden="true" className="shrink-0" />
        <span>Now</span>
      </div>
      <p className="mt-1 text-[14px] leading-relaxed text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">
        {sentence.clauses.join(' ')}
      </p>
      {sentence.chips.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {sentence.chips.map((chip, i) => (
            <StatusChip key={i} tone={chip.tone} icon={chip.icon} label={chip.label} />
          ))}
        </div>
      )}
    </div>
  );
}

export default NowBanner;
