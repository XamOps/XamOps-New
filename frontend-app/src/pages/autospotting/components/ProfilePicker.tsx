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
    <legend className="mb-1 text-[13px] font-medium text-[rgba(0,0,0,0.88)]">Profile</legend>
    {PROFILES.map(p => {
      const est = estimates.find(e => e.profile.toLowerCase() === p.id);
      const blocked = isProfileBlocked(est);
      const inputId = `as-profile-${p.id}`;
      return (
        <div
          key={p.id}
          className={`flex items-start gap-2.5 rounded-xl border px-3 py-2.5 text-[13px] transition-colors ${
            selected === p.id ? 'border-indigo-300 bg-indigo-50/60' : 'border-[rgba(0,0,0,0.1)]'
          }`}
        >
          <input
            id={inputId}
            type="radio"
            name="as-profile"
            value={p.id}
            checked={selected === p.id}
            disabled={disabled || blocked}
            aria-describedby={blocked ? `${inputId}-reason` : undefined}
            onChange={() => onSelect(p.id)}
            className="mt-0.5 h-4 w-4 shrink-0 accent-indigo-600"
          />
          <label htmlFor={inputId} className={`flex-1 ${blocked ? 'text-[rgba(0,0,0,0.5)]' : 'cursor-pointer text-[rgba(0,0,0,0.88)]'}`}>
            <span className="font-semibold">{p.label}</span>
            <span className="ml-2 font-medium">
              {blocked || !est ? null : est.monthlySavings === null ? NOT_PRICED : (
                <MoneyFigure amount={est.monthlySavings} label="Estimate" perMonth />
              )}
            </span>
            {blocked && (
              <span id={`${inputId}-reason`} className="mt-0.5 block text-[11px] text-[rgba(0,0,0,0.6)]">
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
