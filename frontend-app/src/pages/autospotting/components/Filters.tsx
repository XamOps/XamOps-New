import React from 'react';

export interface FilterValues {
  region: string;
  state: string;
  eligibility: string;
  q: string;
}

export const EMPTY_FILTERS: FilterValues = { region: '', state: '', eligibility: '', q: '' };

export const STATE_OPTIONS = [
  'UNMANAGED',
  'PLANNED',
  'APPLYING',
  'ACTIVE',
  'DEGRADED',
  'FALLBACK',
  'PAUSED',
  'REVERTING',
  'REVERTED',
  'FAILED',
  'LEGACY_UNCONFIRMED',
  'GONE',
];

interface FiltersProps {
  values: FilterValues;
  locations: string[];
  locationLabel: string;
  onChange: (next: FilterValues) => void;
}

const selectClass = 'rounded border border-gray-400 bg-white px-2 py-1 text-sm text-black';

/** Filters live in the URL query string so a filtered view can be shared (the page owns the sync). */
export const Filters: React.FC<FiltersProps> = ({ values, locations, locationLabel, onChange }) => {
  const set = (patch: Partial<FilterValues>) => onChange({ ...values, ...patch });
  return (
    <div role="search" className="flex flex-wrap items-end gap-3 text-sm text-black">
      <label className="flex flex-col">
        <span className="text-gray-600">{locationLabel}</span>
        <select className={selectClass} value={values.region} onChange={e => set({ region: e.target.value })}>
          <option value="">All</option>
          {locations.map(l => (
            <option key={l} value={l}>
              {l}
            </option>
          ))}
        </select>
      </label>
      <label className="flex flex-col">
        <span className="text-gray-600">State</span>
        <select className={selectClass} value={values.state} onChange={e => set({ state: e.target.value })}>
          <option value="">All</option>
          {STATE_OPTIONS.map(s => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </label>
      <label className="flex flex-col">
        <span className="text-gray-600">Eligibility</span>
        <select className={selectClass} value={values.eligibility} onChange={e => set({ eligibility: e.target.value })}>
          <option value="">All</option>
          <option value="eligible">Eligible</option>
          <option value="ineligible">Ineligible</option>
        </select>
      </label>
      <label className="flex flex-col">
        <span className="text-gray-600">Search</span>
        <input
          type="search"
          className={selectClass}
          value={values.q}
          onChange={e => set({ q: e.target.value })}
        />
      </label>
    </div>
  );
};

export default Filters;
