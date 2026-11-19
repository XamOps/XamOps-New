import React, { useId } from 'react';

export interface SelectFilterOption {
  value: string;
  label: string;
}

interface SelectFilterProps {
  /** Visible label and the select's accessible name, e.g. "Status". */
  label: string;
  /** The chosen value; '' for All. */
  value: string;
  options: ReadonlyArray<SelectFilterOption>;
  /** '' clears the filter. */
  onChange: (value: string) => void;
}

/**
 * A single-choice server filter (design 4.5): a labelled native `<select>` whose first option, "All"
 * (value ''), sends nothing. Styled as `FilterDropdown`'s trigger, with the darker border while set.
 */
export const SelectFilter: React.FC<SelectFilterProps> = ({ label, value, options, onChange }) => {
  const id = useId();
  return (
    <span className="inline-flex items-center gap-1.5">
      <label htmlFor={id} className="text-xs font-semibold text-[rgba(0,0,0,0.6)]">{label}</label>
      <select
        id={id}
        value={value}
        onChange={e => onChange(e.target.value)}
        className={`rounded-lg border px-2.5 py-1.5 text-xs font-semibold transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)] ${
          value !== ''
            ? 'border-[rgba(0,0,0,0.25)] bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.88)]'
            : 'border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)]'
        }`}
      >
        <option value="">All</option>
        {options.map(option => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </select>
    </span>
  );
};
