/**
 * A switch that saves when toggled, such as Node automation on/off; reads `allowed`
 * (docs/cloudk8s/ui-revamp-design.md §6.5, §11.1, §14).
 *
 * Allowed: the react-aria `Switch`. Blocked (or `pending`): a replica, `<button role="switch">` with
 * `aria-checked`, `aria-disabled="true"`, `aria-describedby` and `aria-label`, drawn like the switch
 * with the lock icon; click and Space do nothing. RAC's `isReadOnly` isn't used because it emits
 * `aria-readonly`, not `aria-disabled`.
 */
import { useId, type KeyboardEvent, type MouseEvent } from 'react';
import { Switch } from 'react-aria-components';
import type { Capability } from '../../../types/k8sAutomation';
import { LockIcon, ReasonText, type ReasonPlacement } from './ReasonText';

export const SAVING_TEXT = 'Saving…';

export interface GatedSwitchProps {
  capability: Capability;
  label: string;
  isSelected: boolean;
  /** Called only when allowed; the caller confirms if needed, then saves. */
  onChange: (next: boolean) => void;
  /** While saving: blocked, described by "Saving…". */
  pending?: boolean;
  reasonPlacement?: ReasonPlacement;
  className?: string;
}

const PENDING_CAPABILITY: Capability = { allowed: false, editable: false, gate: null, also: [] };

export function SwitchTrack({ isSelected, blocked }: { isSelected: boolean; blocked: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full border transition-colors ${
        blocked
          ? 'border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.08)]'
          : isSelected
            ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.88)]'
            : 'border-[rgba(0,0,0,0.3)] bg-white'
      }`}
    >
      <span
        className={`inline-block h-3.5 w-3.5 rounded-full transition-transform ${
          isSelected ? 'translate-x-[18px]' : 'translate-x-[2px]'
        } ${blocked ? 'bg-[rgba(0,0,0,0.45)]' : isSelected ? 'bg-white' : 'bg-[rgba(0,0,0,0.55)]'}`}
      />
    </span>
  );
}

const focusRing = 'outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

export function GatedSwitch({
  capability, label, isSelected, onChange, pending = false, reasonPlacement = 'inline', className = '',
}: GatedSwitchProps) {
  const reasonId = useId();

  if (capability.allowed && !pending) {
    return (
      <Switch
        isSelected={isSelected}
        onChange={onChange}
        className={`group inline-flex cursor-pointer items-center gap-2 rounded-md text-[13px] text-[rgba(0,0,0,0.88)] ${focusRing} ${className}`}
      >
        <SwitchTrack isSelected={isSelected} blocked={false} />
        <span>{label}</span>
      </Switch>
    );
  }

  const swallow = (event: MouseEvent | KeyboardEvent) => {
    if ('key' in event && event.key !== ' ' && event.key !== 'Enter') return;
    event.preventDefault();
  };

  return (
    <span className={`inline-flex flex-col items-start ${className}`} data-gated="blocked">
      <span className="inline-flex items-center gap-2">
        <button
          type="button"
          role="switch"
          aria-checked={isSelected}
          aria-disabled="true"
          aria-describedby={reasonId}
          aria-label={label}
          onClick={swallow}
          onKeyDown={swallow}
          className={`inline-flex cursor-not-allowed items-center gap-2 rounded-md bg-transparent p-0 text-[13px] text-[rgba(0,0,0,0.6)] ${focusRing}`}
        >
          <SwitchTrack isSelected={isSelected} blocked />
          <span aria-hidden="true">{label}</span>
        </button>
        {reasonPlacement === 'inline' && <LockIcon />}
        {reasonPlacement === 'popover' && (
          <ReasonText id={reasonId} capability={pending ? PENDING_CAPABILITY : capability} label={label}
            placement="popover" override={pending ? SAVING_TEXT : undefined} />
        )}
      </span>
      {reasonPlacement === 'inline' && (
        <ReasonText id={reasonId} capability={pending ? PENDING_CAPABILITY : capability} label={label}
          placement="inline" override={pending ? SAVING_TEXT : undefined} />
      )}
    </span>
  );
}

export default GatedSwitch;
