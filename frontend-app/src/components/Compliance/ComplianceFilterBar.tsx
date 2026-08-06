import React from 'react';
import { Icon } from '@iconify/react';
import type { ComplianceFilters, StatusFilter } from '../../hooks/useComplianceOps';
import { CARD, INPUT_CLS, SELECT_CLS, frameworkName } from './complianceTokens';

interface Props {
  filters: ComplianceFilters;
  setFilter: <K extends keyof ComplianceFilters>(key: K, value: ComplianceFilters[K]) => void;
  resetFilters: () => void;
  services: string[];
  frameworkOptions: Array<{ key: string; raw: string }>;
  resultCount: number;
  totalCount: number;
}

/**
 * One label-less h-8 strip, matching the BillOps tickets filter row.
 *
 * The status control is segmented rather than a select because it is the filter people
 * actually reach for, and "Open" is the default: the page's job is to show outstanding
 * work, not to present 400 controls with no ordering. Everything else is a select,
 * because those are occasional refinements and should not compete for space.
 */
const ComplianceFilterBar: React.FC<Props> = ({
  filters, setFilter, resetFilters, services, frameworkOptions, resultCount, totalCount,
}) => {
  const statusOptions: Array<{ key: StatusFilter; label: string; title: string }> = [
    { key: 'OPEN', label: 'Open', title: 'Failing and not-evaluated controls' },
    { key: 'FAIL', label: 'Failing', title: 'Controls with at least one failing resource' },
    { key: 'PASS', label: 'Passing', title: 'Controls where every resource passed' },
    { key: 'MANUAL', label: 'Not evaluated', title: 'The scanner could not decide these' },
    { key: 'WAIVED', label: 'Waived', title: 'Failing but covered by an approved exception' },
    { key: 'All', label: 'All', title: 'Every control in the scan' },
  ];

  const isFiltered = filters.severity !== 'All'
    || filters.framework !== 'All'
    || filters.service !== 'All'
    || filters.search.trim() !== ''
    || filters.unownedOnly
    || filters.status !== 'OPEN';

  return (
    <div className={`${CARD} p-2 flex flex-wrap items-center gap-2`}>
      {/* Segmented status control: grey inset track, white raised thumb */}
      <div className="flex items-center p-0.5 rounded-lg bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)]">
        {statusOptions.map((opt) => (
          <button
            key={opt.key}
            type="button"
            onClick={() => setFilter('status', opt.key)}
            aria-pressed={filters.status === opt.key}
            title={opt.title}
            className={`h-7 px-2.5 rounded-md text-[11.5px] font-medium whitespace-nowrap transition-all ${
              filters.status === opt.key
                ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-[0_1px_2px_rgba(0,0,0,0.08)]'
                : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'
            }`}
          >
            {opt.label}
          </button>
        ))}
      </div>

      <div className="relative">
        <Icon
          icon="mdi:magnify"
          width={14}
          className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] pointer-events-none"
        />
        <input
          type="text"
          value={filters.search}
          onChange={(e) => setFilter('search', e.target.value)}
          placeholder="Search check id, title, clause…"
          aria-label="Search controls"
          className={`${INPUT_CLS} w-52`}
        />
      </div>

      <Select
        value={filters.severity}
        onChange={(v) => setFilter('severity', v)}
        ariaLabel="Filter by severity"
        options={[
          { value: 'All', label: 'All severities' },
          { value: 'critical', label: 'Critical' },
          { value: 'high', label: 'High' },
          { value: 'medium', label: 'Medium' },
          { value: 'low', label: 'Low' },
        ]}
      />

      {frameworkOptions.length > 0 && (
        <Select
          value={filters.framework}
          onChange={(v) => setFilter('framework', v)}
          ariaLabel="Filter by framework"
          options={[
            { value: 'All', label: 'All frameworks' },
            ...frameworkOptions.map((f) => ({ value: f.key, label: frameworkName(f.raw) })),
          ]}
        />
      )}

      {services.length > 0 && (
        <Select
          value={filters.service}
          onChange={(v) => setFilter('service', v)}
          ariaLabel="Filter by service"
          options={[
            { value: 'All', label: 'All services' },
            ...services.map((s) => ({ value: s, label: s })),
          ]}
        />
      )}

      <button
        type="button"
        onClick={() => setFilter('unownedOnly', !filters.unownedOnly)}
        aria-pressed={filters.unownedOnly}
        title="Only controls with nobody assigned"
        className={`h-8 flex items-center gap-1.5 px-2.5 rounded-lg border text-[11.5px] font-medium transition-colors ${
          filters.unownedOnly
            ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.88)] text-white'
            : 'border-[#e5e7eb] bg-white text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.4)]'
        }`}
      >
        <Icon icon="mdi:account-question-outline" width={13} />
        Unowned
      </button>

      <div className="ml-auto flex items-center gap-3">
        <span className="text-[11.5px] font-medium text-[rgba(0,0,0,0.4)] tabular-nums">
          {resultCount.toLocaleString()} of {totalCount.toLocaleString()} controls
        </span>
        {isFiltered && (
          <button
            type="button"
            onClick={resetFilters}
            className="h-8 flex items-center gap-1.5 px-2.5 rounded-lg text-[11.5px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.03)] transition-colors"
          >
            <Icon icon="mdi:filter-remove-outline" width={13} />
            Reset
          </button>
        )}
      </div>
    </div>
  );
};

/** appearance-none select with a manual chevron, so it matches the house controls. */
const Select: React.FC<{
  value: string;
  onChange: (value: string) => void;
  ariaLabel: string;
  options: Array<{ value: string; label: string }>;
}> = ({ value, onChange, ariaLabel, options }) => (
  <div className="relative">
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      aria-label={ariaLabel}
      className={SELECT_CLS}
    >
      {options.map((opt) => (
        <option key={opt.value} value={opt.value}>{opt.label}</option>
      ))}
    </select>
    <Icon
      icon="mdi:chevron-down"
      width={13}
      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)] pointer-events-none"
    />
  </div>
);

export default ComplianceFilterBar;
