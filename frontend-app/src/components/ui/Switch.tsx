/**
 * Accessible Switch/Toggle primitive built on react-aria-components.
 * Replaces CSS peer-checked toggle pattern.
 * Provides: switch role, Space key toggle, focus-visible ring,
 * proper aria-checked announcement.
 *
 * Usage:
 *   <AriaSwitch
 *     isSelected={value}
 *     onChange={setValue}
 *     label="Respect RI/SP Commitments"
 *     description="When enabled, only convert to Spot when RI/SP utilization is high"
 *   />
 */

import React from 'react';
import { Switch } from 'react-aria-components';

interface AriaSwitchProps {
  isSelected: boolean;
  onChange: (checked: boolean) => void;
  label: React.ReactNode;
  description?: React.ReactNode;
  isDisabled?: boolean;
  /** Class applied to the outer wrapper div */
  className?: string;
}

export function AriaSwitch({
  isSelected,
  onChange,
  label,
  description,
  isDisabled,
  className = '',
}: AriaSwitchProps) {
  return (
    <Switch
      isSelected={isSelected}
      onChange={onChange}
      isDisabled={isDisabled}
      className={`flex items-center justify-between p-4 bg-gray-50 rounded-lg border border-gray-200 cursor-pointer group outline-none ${className}`}
    >
      {({ isSelected: sel, isFocusVisible }) => (
        <>
          <div className="flex-1 pr-4">
            <div className="text-sm font-semibold text-gray-900">{label}</div>
            {description && (
              <div className="text-xs text-gray-600 mt-1">{description}</div>
            )}
          </div>
          <div className="flex items-center gap-3 shrink-0">
            {/* Track */}
            <div
              className={[
                'relative w-14 h-7 rounded-full transition-colors duration-200',
                sel ? 'bg-blue-600' : 'bg-gray-300',
                isDisabled ? 'opacity-50' : '',
                isFocusVisible ? 'ring-2 ring-blue-400 ring-offset-1' : '',
              ].join(' ')}
            >
              {/* Thumb */}
              <div
                className={[
                  'absolute top-0.5 w-6 h-6 bg-white rounded-full shadow-md transition-all duration-200 border border-gray-200',
                  sel ? 'left-[calc(100%-1.625rem)]' : 'left-0.5',
                ].join(' ')}
              />
            </div>
            <span className="text-sm font-medium text-gray-900 min-w-[52px]">
              {sel ? 'Enabled' : 'Disabled'}
            </span>
          </div>
        </>
      )}
    </Switch>
  );
}
