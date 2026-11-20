import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';

/**
 * Copies `text` to the clipboard and shows a check mark for 1.5 s. `onCopied` runs after a successful
 * write (a toast, for example), never after a failed one.
 */
export const CopyButton: React.FC<{ text: string; label?: string; onCopied?: () => void }> = ({
  text, label = 'Copy', onCopied,
}) => {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1500);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      onCopied?.();
    } catch {
      // No clipboard (an insecure context or a denied permission): the button simply does not confirm.
    }
  };

  return (
    <button
      type="button"
      onClick={() => { void copy(); }}
      aria-label={copied ? 'Copied' : label}
      title={copied ? 'Copied' : label}
      className="inline-flex items-center rounded-md p-1 text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.8)]"
    >
      <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={14} />
    </button>
  );
};
