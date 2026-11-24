import React from 'react';
import { Icon } from '@iconify/react';
import { CodeBlock } from '../../Dspm/datastores/markdown/CodeBlock';
import type { CheckGuide } from './checkGuides';

interface Props {
  /** A live finding's own remediation, from its description. */
  text: string | null;
  /** The check's guide, for the checks XamOps runs itself. */
  guide: CheckGuide | null;
  /** Where AWS documents the fix. Defaults to the guide's. */
  learnMore?: string | null;
  /** A note under the CLI, such as where to get it filled in. */
  hint?: string | null;
}

/**
 * A remediation as the XamLens drawer shows one: the steps, the CLI fix as a Bash block and the
 * "Learn More" link. A live finding's own text comes first; the guide's steps stand in when it has none.
 * "-" when there is nothing at all.
 */
const Remediation: React.FC<Props> = ({ text, guide, learnMore = guide?.learnMore ?? null, hint }) => {
  const hasText = Boolean(text?.trim());
  const steps = hasText ? [] : (guide?.steps ?? []);
  if (!hasText && steps.length === 0 && !guide?.cli && !learnMore) {
    return <p className="text-xs text-[rgba(0,0,0,0.4)]">-</p>;
  }
  return (
    <div className="space-y-2.5">
      {hasText && (
        <p className="whitespace-pre-line break-words text-xs leading-relaxed text-[rgba(0,0,0,0.75)]">{text}</p>
      )}
      {steps.length > 0 && (
        <ol className="list-decimal space-y-1 pl-5 text-xs leading-relaxed text-[rgba(0,0,0,0.75)]">
          {steps.map(step => <li key={step}>{step}</li>)}
        </ol>
      )}
      {guide?.cli && <CodeBlock language="Bash" code={guide.cli} />}
      {hint && <p className="text-[11px] text-[rgba(0,0,0,0.45)]">{hint}</p>}
      {learnMore && (
        <a
          href={learnMore}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 text-xs font-semibold text-sky-700 hover:underline"
        >
          Learn More
          <Icon icon="mdi:open-in-new" width={12} />
        </a>
      )}
    </div>
  );
};

export default Remediation;
