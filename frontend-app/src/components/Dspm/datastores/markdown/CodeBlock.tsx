import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import { CopyButton } from '../ui/CopyButton';

interface Props {
  /** The label in the header: the fence's language, "Text" when it has none, or "Bash" for a CLI fix. */
  language: string;
  code: string;
}

/**
 * A code block as the console shows it: a header with the language label, Copy and an expand toggle,
 * then the code in monospace. Collapsed, the body is at most `max-h-48` and scrolls; expanded, it shows
 * in full. The code is React text, so markup inside it (`<b>`, `<bucket_name>`) shows literally.
 */
export const CodeBlock: React.FC<Props> = ({ language, code }) => {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="overflow-hidden rounded-lg border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.03)]">
      <div className="flex items-center gap-1 border-b border-[rgba(0,0,0,0.08)] py-1 pl-3 pr-1">
        <span className="flex-1 text-[11px] font-semibold text-[rgba(0,0,0,0.55)]">{language}</span>
        <CopyButton text={code} label="Copy code" />
        <button
          type="button"
          onClick={() => setExpanded(open => !open)}
          aria-expanded={expanded}
          aria-label={expanded ? 'Collapse code' : 'Expand code'}
          title={expanded ? 'Collapse code' : 'Expand code'}
          className="inline-flex items-center rounded-md p-1 text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.8)]"
        >
          <Icon icon={expanded ? 'mdi:arrow-collapse' : 'mdi:arrow-expand'} width={14} />
        </button>
      </div>
      <pre
        className={`overflow-auto whitespace-pre p-3 font-mono text-[11px] leading-relaxed text-[rgba(0,0,0,0.8)] ${
          expanded ? '' : 'max-h-48'
        }`}
      >
        <code>{code}</code>
      </pre>
    </div>
  );
};
