/**
 * Accessible Slider primitive built on react-aria-components.
 * Replaces native <input type="range">.
 * Provides: arrow key increment/decrement, Page Up/Down (10-step),
 * Home/End (min/max), aria-valuenow/min/max/valuetext announcements.
 *
 * Usage:
 *   <AriaSlider
 *     label="Utilization Threshold"
 *     value={threshold}
 *     onChange={setThreshold}
 *     minValue={0}
 *     maxValue={100}
 *     step={5}
 *     formatValue={(v) => `${v}%`}
 *     isDisabled={!respectEnabled}
 *   />
 */

import React from 'react';
import { Slider, SliderTrack, SliderThumb, Label } from 'react-aria-components';

interface AriaSliderProps {
  label: string;
  value: number;
  onChange: (value: number) => void;
  minValue?: number;
  maxValue?: number;
  step?: number;
  isDisabled?: boolean;
  formatValue?: (value: number) => string;
  /** Color class for the fill track (default blue-600) */
  fillColor?: string;
  description?: React.ReactNode;
}

export function AriaSlider({
  label,
  value,
  onChange,
  minValue = 0,
  maxValue = 100,
  step = 1,
  isDisabled = false,
  formatValue = (v) => String(v),
  fillColor = '#2563EB',
  description,
}: AriaSliderProps) {
  const pct = ((value - minValue) / (maxValue - minValue)) * 100;

  return (
    <Slider
      value={value}
      onChange={onChange}
      minValue={minValue}
      maxValue={maxValue}
      step={step}
      isDisabled={isDisabled}
      aria-label={label}
      className="w-full"
    >
      <div className="flex items-center justify-between mb-2">
        <Label className="text-sm font-semibold text-gray-900">{label}</Label>
        <span className="text-sm font-bold text-blue-600">{formatValue(value)}</span>
      </div>
      {description && (
        <p className="text-xs text-gray-600 mb-3">{description}</p>
      )}
      <div className="flex items-center gap-3">
        <span className="text-xs text-gray-500 font-medium">{formatValue(minValue)}</span>
        <SliderTrack
          className={[
            'relative flex-1 h-2 rounded-full',
            isDisabled ? 'opacity-50' : '',
          ].join(' ')}
          style={{
            background: isDisabled
              ? '#E5E7EB'
              : `linear-gradient(to right, ${fillColor} 0%, ${fillColor} ${pct}%, #E5E7EB ${pct}%, #E5E7EB 100%)`,
          }}
        >
          <SliderThumb
            className={[
              'w-5 h-5 bg-white rounded-full border-2 shadow-md top-1/2 -translate-y-1/2 outline-none transition-shadow',
              isDisabled ? 'cursor-not-allowed border-gray-300' : 'cursor-pointer border-blue-600',
              'focus-visible:ring-2 focus-visible:ring-blue-400 focus-visible:ring-offset-1',
            ].join(' ')}
          />
        </SliderTrack>
        <span className="text-xs text-gray-500 font-medium">{formatValue(maxValue)}</span>
      </div>
    </Slider>
  );
}
