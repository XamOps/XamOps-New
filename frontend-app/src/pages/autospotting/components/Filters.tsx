import React from 'react';
import { Icon } from '@iconify/react';
import { CARD, FIELD_LABEL, INPUT } from './theme';

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

const selectClass = `${INPUT} w-full min-w-[8rem] cursor-pointer appearance-none pr-8`;
/** A select without native chrome, with the same chevron the app's other filter bars show. */
const Select: React.FC<React.SelectHTMLAttributes<HTMLSelectElement>> = ({ children, ...props }) => (
  <span className="relative">
    <select {...props} className={selectClass}>
      {children}
    </select>
    <Icon
      icon="mdi:chevron-down"
      width={16}
      aria-hidden="true"
      className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.55)]"
    />
  </span>
);

/** Filters live in the URL query string so a filtered view can be shared (the page owns the sync). */
export const Filters: React.FC<FiltersProps> = ({ values, locations, locationLabel, onChange }) => {
  const set = (patch: Partial<FilterValues>) => onChange({ ...values, ...patch });
  return (
    <div role="search" className={`${CARD} mb-5 flex flex-wrap items-end gap-4 px-4 py-3`}>
      <label className="flex flex-col gap-1">
        <span className={FIELD_LABEL}>{locationLabel}</span>
        <Select value={values.region} onChange={e => set({ region: e.target.value })}>
          <option value="">All</option>
          {locations.map(l => (
            <option key={l} value={l}>
              {l}
            </option>
          ))}
        </Select>
      </label>
      <label className="flex flex-col gap-1">
        <span className={FIELD_LABEL}>State</span>
        <Select value={values.state} onChange={e => set({ state: e.target.value })}>
          <option value="">All</option>
          {STATE_OPTIONS.map(s => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </Select>
      </label>
      <label className="flex flex-col gap-1">
        <span className={FIELD_LABEL}>Eligibility</span>
        <Select value={values.eligibility} onChange={e => set({ eligibility: e.target.value })}>
          <option value="">All</option>
          <option value="eligible">Eligible</option>
          <option value="ineligible">Ineligible</option>
        </Select>
      </label>
      <label className="flex flex-col gap-1 md:ml-auto">
        <span className={FIELD_LABEL}>Search</span>
        <span className="relative">
          <Icon
            icon="mdi:magnify"
            width={14}
            aria-hidden="true"
            className="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.45)]"
          />
          <input
            type="search"
            className={`${INPUT} w-56 pl-7`}
            value={values.q}
            onChange={e => set({ q: e.target.value })}
          />
        </span>
      </label>
    </div>
  );
};

export default Filters;
