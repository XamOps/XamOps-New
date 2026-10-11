/**
 * Any button-like gated control (docs/cloudk8s/ui-revamp-design.md §6.5, §11.1, §14).
 *
 * Kind `commit` (Save, Apply now, a dialog's confirm) reads `allowed`; kind `draft` (Add, Remove,
 * Discard: changes only the local draft) reads `editable`. Blocked, the caller's control keeps
 * focus and hover, carries `aria-disabled="true"` and `aria-describedby` pointing at its reason,
 * sits next to the lock icon, and `onActivate` turns its handler into a no-op. Never `disabled`.
 */
import { useId, type ReactElement } from 'react';
import type { Capability } from '../../../types/k8sAutomation';
import { LockIcon, ReasonText, type ReasonPlacement } from './ReasonText';
import type { ReasonCopyOptions } from './reasonCopy';

/** Tailwind classes for a blocked control: normal outline, reduced fill, readable label. */
export const BLOCKED_CONTROL_CLASS =
  'cursor-not-allowed bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.3)] shadow-none';

export interface GatedRenderProps {
  'aria-disabled'?: 'true';
  'aria-describedby'?: string;
  /** Wrap the control's handler: a no-op while blocked. */
  onActivate: <A extends unknown[]>(fn: (...args: A) => void) => (...args: A) => void;
  blocked: boolean;
  /** Merge into the control's className while blocked. */
  blockedClassName: string;
}

export interface GatedActionProps {
  capability: Capability;
  kind?: 'commit' | 'draft';
  /** Names the popover lock: "Why is <label> unavailable?". */
  label: string;
  children: (props: GatedRenderProps) => ReactElement;
  reasonPlacement?: ReasonPlacement;
  reasonOptions?: ReasonCopyOptions;
  className?: string;
}

const noop = () => {};

export function GatedAction({
  capability, kind = 'commit', label, children, reasonPlacement = 'inline', reasonOptions, className = '',
}: GatedActionProps) {
  const reasonId = useId();
  const permitted = kind === 'draft' ? capability.editable : capability.allowed;

  if (permitted) {
    return children({ onActivate: (fn) => fn, blocked: false, blockedClassName: '' });
  }

  const control = children({
    'aria-disabled': 'true',
    'aria-describedby': reasonId,
    onActivate: () => noop,
    blocked: true,
    blockedClassName: BLOCKED_CONTROL_CLASS,
  });

  if (reasonPlacement === 'popover') {
    return (
      <span className={`inline-flex items-center gap-1 ${className}`} data-gated="blocked">
        {control}
        <ReasonText id={reasonId} capability={capability} label={label} placement="popover" options={reasonOptions} />
      </span>
    );
  }
  return (
    <span className={`inline-flex flex-col items-start ${className}`} data-gated="blocked">
      <span className="inline-flex items-center gap-1.5">
        {control}
        <LockIcon />
      </span>
      <ReasonText id={reasonId} capability={capability} label={label} placement="inline" options={reasonOptions} />
    </span>
  );
}

export default GatedAction;
