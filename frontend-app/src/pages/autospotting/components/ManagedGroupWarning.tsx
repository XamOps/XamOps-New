import React, { useId } from 'react';
import { StateBadge } from './StateBadge';

interface ManagedGroupWarningProps {
  /** The group's AutoSpotting state, as the server reported it. */
  state: string;
  /** True for APPLYING or REVERTING: deleting is refused until the operation finishes. */
  busy: boolean;
  acknowledged: boolean;
  onAcknowledgedChange: (value: boolean) => void;
}

/**
 * The warning the waste delete dialog shows for an Auto Scaling Group AutoSpotting manages (design-core.md §7.6,
 * FR-CMP-02): the state badge, a "Revert first" link, and the acknowledgment the Delete button waits for. Built
 * from inline elements so it can sit inside the dialog's description paragraph.
 */
export const ManagedGroupWarning: React.FC<ManagedGroupWarningProps> = ({
  state,
  busy,
  acknowledged,
  onAcknowledgedChange,
}) => {
  const id = useId();
  return (
    <span role="group" aria-label="AutoSpotting warning" className="mt-3 block rounded-xl border border-amber-200 bg-amber-50 p-3 text-amber-900">
      <span className="block font-semibold">AutoSpotting manages this group</span>
      <span className="mt-1 flex items-center gap-2">
        <span>State</span>
        <StateBadge row={{ state: state as never, enableAllowed: false, externalSpot: false }} />
      </span>
      {busy ? (
        <span className="mt-2 block">AutoSpotting is changing this group. Try again when it finishes.</span>
      ) : (
        <>
          <span className="mt-2 block">
            Deleting it ends AutoSpotting management and its revert path. The original configuration can&apos;t be
            restored afterwards.
          </span>
          <a href="/autospotting" className="mt-2 inline-block font-semibold text-indigo-700 underline">
            Revert first
          </a>
          <span className="mt-2 flex items-start gap-2">
            <input
              id={id}
              type="checkbox"
              className="mt-0.5 h-4 w-4 shrink-0 accent-indigo-600"
              checked={acknowledged}
              onChange={e => onAcknowledgedChange(e.target.checked)}
            />
            <label htmlFor={id}>I understand AutoSpotting will stop managing this group.</label>
          </span>
        </>
      )}
    </span>
  );
};

export default ManagedGroupWarning;
