import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import { describeResource, resourceConsoleLink } from './resourceDisplay';

interface Props {
  resources: string[];
  projectId: string | null;
}

/**
 * How many resources to render before asking. A group can hold hundreds, and mounting all of them
 * for every expanded row rebuilds the performance problem this redesign exists to remove.
 */
const INITIAL_VISIBLE = 25;

const GcpAlertResources: React.FC<Props> = ({ resources, projectId }) => {
  const [visible, setVisible] = useState(INITIAL_VISIBLE);
  const [copied, setCopied] = useState<string | null>(null);

  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(value);
      window.setTimeout(() => setCopied(null), 1500);
    } catch {
      // Clipboard access is denied in some contexts (no permission, insecure origin). The full value
      // is on the title attribute either way, so there is nothing to recover from here.
    }
  };

  if (resources.length === 0) {
    return (
      <p className="text-[12px] text-[rgba(0,0,0,0.4)] italic">
        No resource details were reported for this finding.
      </p>
    );
  }

  const shown = resources.slice(0, visible);
  const remaining = resources.length - shown.length;

  return (
    <div className="space-y-1.5">
      <p className="text-[11px] font-semibold uppercase tracking-[0.06em] text-[rgba(0,0,0,0.4)]">
        Affected {resources.length === 1 ? 'resource' : `resources (${resources.length.toLocaleString()})`}
      </p>

      <ul className="space-y-1">
        {shown.map(resource => {
          const parts = describeResource(resource);
          const link = resourceConsoleLink(resource, projectId);
          return (
            <li
              key={resource}
              className="flex items-center gap-2 py-1 px-2 rounded-md bg-white border border-[rgba(0,0,0,0.06)]"
            >
              <Icon icon="mdi:cube-outline" width={13} className="text-[rgba(0,0,0,0.3)] flex-shrink-0" />

              <span className="min-w-0 flex-1 flex items-baseline gap-2 flex-wrap">
                <span
                  className="text-[12px] font-medium text-[rgba(0,0,0,0.85)] font-mono"
                  title={parts.full}
                >
                  {parts.label}
                </span>
                {parts.version && (
                  <span className="text-[11px] text-[rgba(0,0,0,0.45)] font-mono">{parts.version}</span>
                )}
                {parts.context && (
                  <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{parts.context}</span>
                )}
              </span>

              <button
                onClick={() => copy(resource)}
                title="Copy full resource name"
                aria-label={`Copy full resource name for ${parts.label}`}
                className="flex-shrink-0 w-6 h-6 rounded flex items-center justify-center text-[rgba(0,0,0,0.35)] hover:text-indigo-600 hover:bg-indigo-50 transition-colors"
              >
                <Icon icon={copied === resource ? 'mdi:check' : 'mdi:content-copy'} width={12} />
              </button>

              {link && (
                <a
                  href={link}
                  target="_blank"
                  rel="noopener noreferrer"
                  title="Open in Google Cloud console"
                  aria-label={`Open ${parts.label} in the Google Cloud console`}
                  className="flex-shrink-0 w-6 h-6 rounded flex items-center justify-center text-[rgba(0,0,0,0.35)] hover:text-indigo-600 hover:bg-indigo-50 transition-colors"
                >
                  <Icon icon="mdi:open-in-new" width={12} />
                </a>
              )}
            </li>
          );
        })}
      </ul>

      {remaining > 0 && (
        <button
          onClick={() => setVisible(v => v + 100)}
          className="text-[12px] font-medium text-indigo-600 hover:text-indigo-800 transition-colors"
        >
          Show {Math.min(remaining, 100).toLocaleString()} more
          {remaining > 100 ? ` of ${remaining.toLocaleString()}` : ''}
        </button>
      )}
    </div>
  );
};

export default GcpAlertResources;
