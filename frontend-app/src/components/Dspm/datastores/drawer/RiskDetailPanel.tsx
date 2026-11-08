import React, { useId } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreMisconfiguration } from '../../../../types/dspmDatastores';
import PoweredByProvider from '../../../XamSecure/PoweredByProvider';
import { CodeBlock } from '../markdown/CodeBlock';
import { MarkdownView } from '../markdown/MarkdownView';
import { LevelLabel } from '../ui/Chips';
import { useSidePanel } from './EscapeStack';

/** A card of the panel: its heading, the markdown and any extra content, or "-" when there is neither. */
const Card: React.FC<{ title: string; markdown: string | null; children?: React.ReactNode }> = ({ title, markdown, children }) => {
  const id = useId();
  const hasText = Boolean(markdown?.trim());
  return (
    <section aria-labelledby={id} className="space-y-2 rounded-xl border border-[rgba(0,0,0,0.08)] p-4">
      <h4 id={id} className="text-xs font-semibold text-[rgba(0,0,0,0.85)]">{title}</h4>
      {hasText && <MarkdownView markdown={markdown} />}
      {children}
      {!hasText && !children && <p className="text-xs text-[rgba(0,0,0,0.4)]">-</p>}
    </section>
  );
};

interface Props {
  item: DatastoreMisconfiguration;
  /** The row that opened the panel; focus goes back to it when the panel closes. */
  returnFocus: HTMLElement | null;
  /** Should be stable: the panel is on the drawer's Escape stack under it. */
  onClose: () => void;
}

/**
 * One misconfiguration in full, over the right 60% of the drawer: title, the Upwind badge for Upwind
 * findings, the severity chip, then "Description" and "Remediation" as markdown, the latter followed by
 * the CLI fix as a Bash code block when Matters has one.
 *
 * The panel is positioned against the drawer's `aside` and scrolls on its own. It is on the drawer's
 * Escape stack, so Escape closes it before the drawer; Escape and its X both return focus to the row.
 */
const RiskDetailPanel: React.FC<Props> = ({ item, returnFocus, onClose }) => {
  const { closeButton, close } = useSidePanel(returnFocus, onClose);
  const titleId = useId();

  return (
    <section
      aria-labelledby={titleId}
      className="absolute inset-y-0 right-0 z-40 flex w-[60%] flex-col border-l border-[rgba(0,0,0,0.08)] bg-white shadow-2xl"
    >
      <header className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 py-4">
        <div className="min-w-0 flex-1 space-y-2">
          <h3 id={titleId} className="text-sm font-bold text-[rgba(0,0,0,0.88)]">
            {item.title ?? '-'}
            {item.source === 'UPWIND' && <PoweredByProvider provider="upwind" className="ml-2" />}
          </h3>
          <span className="inline-flex rounded-full border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.02)] px-2 py-0.5">
            <LevelLabel level={item.severity} label={item.severityLabel} />
          </span>
        </div>
        <button
          ref={closeButton}
          type="button"
          onClick={close}
          aria-label="Close details"
          className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
        >
          <Icon icon="mdi:close" width={14} />
        </button>
      </header>
      <div className="flex-1 space-y-3 overflow-y-auto px-5 py-4">
        <Card title="Description" markdown={item.description} />
        <Card title="Remediation" markdown={item.remediation}>
          {item.cli !== null && <CodeBlock language="Bash" code={item.cli} />}
        </Card>
      </div>
    </section>
  );
};

export default RiskDetailPanel;
