/**
 * Accessible RadioGroup primitive built on react-aria-components.
 * Provides: radiogroup role, arrow key navigation between options,
 * proper focus management, aria-labelledby.
 *
 * Usage:
 *   <AriaRadioGroup
 *     label="Configuration Mode"
 *     value={mode}
 *     onChange={setMode}
 *     options={[
 *       { value: 'auto',   label: 'Auto',   description: 'Let us choose' },
 *       { value: 'manual', label: 'Manual', description: 'Pick yourself' },
 *     ]}
 *   />
 */

import React from 'react';
import { RadioGroup, Radio, Label } from 'react-aria-components';

interface RadioOption {
  value: string;
  label: React.ReactNode;
  description?: React.ReactNode;
  disabled?: boolean;
}

interface AriaRadioGroupProps {
  label?: React.ReactNode;
  value: string;
  onChange: (value: string) => void;
  options: RadioOption[];
  /** Layout direction */
  orientation?: 'horizontal' | 'vertical';
  className?: string;
}

export function AriaRadioGroup({
  label,
  value,
  onChange,
  options,
  orientation = 'vertical',
  className = '',
}: AriaRadioGroupProps) {
  return (
    <RadioGroup
      value={value}
      onChange={onChange}
      orientation={orientation}
      className={`flex flex-col gap-1 ${className}`}
    >
      {label && (
        <Label className="text-sm font-semibold text-gray-700 mb-2">{label}</Label>
      )}
      <div className={orientation === 'horizontal' ? 'flex flex-wrap gap-3' : 'flex flex-col gap-2'}>
        {options.map((opt) => (
          <Radio
            key={opt.value}
            value={opt.value}
            isDisabled={opt.disabled}
            className={({ isSelected, isFocusVisible }) =>
              [
                'flex items-start gap-3 p-3 rounded-xl border cursor-pointer transition-all outline-none',
                isSelected
                  ? 'border-indigo-500 bg-indigo-50'
                  : 'border-gray-200 bg-white hover:border-gray-300 hover:bg-gray-50',
                isFocusVisible ? 'ring-2 ring-indigo-500 ring-offset-1' : '',
                opt.disabled ? 'opacity-50 cursor-not-allowed' : '',
              ].join(' ')
            }
          >
            {({ isSelected }) => (
              <>
                {/* Custom radio circle */}
                <div
                  className={[
                    'w-4 h-4 rounded-full border-2 flex items-center justify-center mt-0.5 shrink-0 transition-colors',
                    isSelected ? 'border-indigo-600 bg-indigo-600' : 'border-gray-300 bg-white',
                  ].join(' ')}
                >
                  {isSelected && <div className="w-1.5 h-1.5 rounded-full bg-white" />}
                </div>
                <div>
                  <div className="text-sm font-semibold text-gray-900">{opt.label}</div>
                  {opt.description && (
                    <div className="text-xs text-gray-500 mt-0.5">{opt.description}</div>
                  )}
                </div>
              </>
            )}
          </Radio>
        ))}
      </div>
    </RadioGroup>
  );
}
