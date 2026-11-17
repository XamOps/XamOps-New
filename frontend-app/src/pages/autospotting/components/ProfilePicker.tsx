import React from 'react';
import type { PlanEstimate } from '../../../api/autospottingApi';
import { MoneyFigure } from './MoneyLabel';
import { NOT_PRICED } from '../../../utils/formatMoney';

export const PROFILES = [
  { id: 'conservative', label: 'Conservative' },
  { id: 'balanced', label: 'Balanced' },
  { id: 'aggressive', label: 'Aggressive' },
] as const;

/**
 * A profile the backend blocked for this group size (PROFILE_NO_SPOT, GR-09). The plan body carries a
 * blocked profile as an estimate of exactly 0 with no price sources; a priced profile with nothing to
 * save is not blocked, because it has price sources.
 */
export function isProfileBlocked(e: PlanEstimate | undefined): boolean {
  return !!e && e.monthlySavings === 0 && Array.isArray(e.priceSources) && e.priceSources.length === 0;
}

export function blockedReason(desired: number | null | undefined): string {
  const size = desired === null || desired === undefined ? 'its current size' : `the current size of ${desired}`;
  return `At ${size}, this profile would keep every instance On-Demand. Choose a more aggressive profile.`;
}

interface ProfilePickerProps {
  estimates: PlanEstimate[];
  selected: string | null;
  desired: number | null | undefined;
  disabled?: boolean;
  onSelect: (profileId: string) => void;
}

export const ProfilePicker: React.FC<ProfilePickerProps> = ({ estimates, selected, desired, disabled, onSelect }) => (
  <fieldset className="space-y-2">
    <legend className="mb-1 text-sm font-medium text-black">Profile</legend>
    {PROFILES.map(p => {
      const est = estimates.find(e => e.profile.toLowerCase() === p.id);
      const blocked = isProfileBlocked(est);
      const inputId = `as-profile-${p.id}`;
      return (
        <div key={p.id} className="flex items-start gap-2 text-sm">
          <input
            id={inputId}
            type="radio"
            name="as-profile"
            value={p.id}
            checked={selected === p.id}
            disabled={disabled || blocked}
            aria-describedby={blocked ? `${inputId}-reason` : undefined}
            onChange={() => onSelect(p.id)}
            className="mt-1"
          />
          <label htmlFor={inputId} className={blocked ? 'text-gray-400' : 'text-black'}>
            <span className="font-medium">{p.label}</span>
            <span className="ml-2">
              {blocked || !est ? null : est.monthlySavings === null ? NOT_PRICED : (
                <MoneyFigure amount={est.monthlySavings} label="Estimate" perMonth />
              )}
            </span>
            {blocked && (
              <span id={`${inputId}-reason`} className="block text-xs text-gray-600">
                {blockedReason(desired)}
              </span>
            )}
          </label>
        </div>
      );
    })}
  </fieldset>
);

export default ProfilePicker;
