/**
 * A "What's this?" tooltip (design-delta §2.5; glossary §1). The trigger is a `button` with
 * `aria-describedby` pointing at the tooltip panel. The tooltip opens on hover and on keyboard
 * focus, and closes on Escape. Its title is "What's this? <term>", then the exact glossary text,
 * then the small-grey source line. The term itself is drawn with a dotted underline and a
 * `mdi:help-circle-outline` icon after it.
 *
 * Pass either a known `term` (looked up in TERM_TIPS) or an explicit `entry`. The data lives in
 * termTips.ts so the same wording is used on every page.
 */
import { useId, useState } from 'react';
import { Icon } from '@iconify/react';
import { TERM_TIPS, type TermTipEntry } from './termTips';

export interface TermTipProps {
  /** A key into TERM_TIPS; ignored when `entry` is given. */
  term?: string;
  /** An explicit entry (e.g. with tokens already substituted). */
  entry?: TermTipEntry;
  /** The visible trigger text; defaults to the term. */
  children?: string;
  className?: string;
}

export function TermTip({ term, entry, children, className = '' }: TermTipProps) {
  const resolved = entry ?? (term ? TERM_TIPS[term] : undefined);
  const [open, setOpen] = useState(false);
  const tooltipId = useId();

  if (!resolved) return <span className={className}>{children ?? term}</span>;

  const label = children ?? resolved.term;

  return (
    <span className={`relative inline-flex items-center ${className}`}>
      <button
        type="button"
        aria-describedby={tooltipId}
        aria-expanded={open}
        onMouseEnter={() => setOpen(true)}
        onMouseLeave={() => setOpen(false)}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') setOpen(false);
        }}
        className="inline-flex items-center gap-0.5 text-[13px] text-[rgba(0,0,0,0.88)] underline decoration-dotted underline-offset-2 dark:text-[rgba(255,255,255,0.92)]"
      >
        <span>{label}</span>
        <Icon icon="mdi:help-circle-outline" width={13} aria-hidden="true" className="shrink-0 text-[rgba(0,0,0,0.5)] dark:text-[rgba(255,255,255,0.5)]" />
      </button>
      <span
        id={tooltipId}
        role="tooltip"
        hidden={!open}
        className="absolute left-0 top-full z-10 mt-1 w-64 rounded-md border border-[rgba(0,0,0,0.1)] bg-white p-2 text-[12px] leading-snug shadow-md dark:border-[rgba(255,255,255,0.12)] dark:bg-[#1c1c1c]"
      >
        <span className="block font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">
          What's this? {resolved.term}
        </span>
        <span className="mt-1 block text-[rgba(0,0,0,0.72)] dark:text-[rgba(255,255,255,0.8)]">{resolved.text}</span>
        <span className="mt-1 block text-[11px] text-[rgba(0,0,0,0.5)] dark:text-[rgba(255,255,255,0.5)]">{resolved.source}</span>
      </span>
    </span>
  );
}

export default TermTip;
