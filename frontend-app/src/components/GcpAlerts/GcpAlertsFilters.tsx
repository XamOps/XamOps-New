import React, { useEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { GcpAlertStatus, GcpAlertType } from '../../types/gcpAlerts';

interface Props {
  services: string[];
  selectedService: string;
  setSelectedService: (value: string) => void;
  searchTerm: string;
  setSearchTerm: (value: string) => void;
  activeTypes: GcpAlertType[];
  toggleType: (type: GcpAlertType) => void;
  activeSeverities: GcpAlertStatus[];
  toggleSeverity: (severity: GcpAlertStatus) => void;
  resetFilters: () => void;
  filtersActive: boolean;
  resultCount: number;
  totalCount: number;
}

const TYPE_OPTIONS: { value: GcpAlertType; label: string; dot: string }[] = [
  { value: 'SECURITY', label: 'Security', dot: 'bg-red-500' },
  { value: 'ANOMALY', label: 'Cost', dot: 'bg-amber-500' },
  { value: 'QUOTA', label: 'Quota', dot: 'bg-blue-500' },
];

const SEVERITY_OPTIONS: { value: GcpAlertStatus; label: string; dot: string }[] = [
  { value: 'CRITICAL', label: 'Critical', dot: 'bg-red-500' },
  { value: 'HIGH', label: 'High', dot: 'bg-orange-500' },
  { value: 'WARNING', label: 'Warning', dot: 'bg-amber-500' },
  { value: 'OK', label: 'OK', dot: 'bg-emerald-500' },
];

/**
 * A compact multi-select dropdown, kept inside this file so the filter bar is self-contained.
 *
 * Replaces the toggle-chip rows that read backwards: clicking a chip removed its alerts, so clicking
 * "Critical" hid critical alerts. A checklist in a popover makes the action explicit — a ticked row
 * is included — and it scales to more types or severities without overflowing the bar, which chips
 * do not. Follows the click-outside popover pattern already used elsewhere (e.g. DbAlertPanel).
 */
function MultiSelect<T extends string>({
  label,
  options,
  selected,
  onToggle,
  allLabel,
}: {
  label: string;
  options: { value: T; label: string; dot: string }[];
  selected: T[];
  onToggle: (value: T) => void;
  /** Trigger text when everything is selected — the page treats "all" as "no filter". */
  allLabel: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    function onEsc(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onClickOutside);
    document.addEventListener('keydown', onEsc);
    return () => {
      document.removeEventListener('mousedown', onClickOutside);
      document.removeEventListener('keydown', onEsc);
    };
  }, []);

  const allSelected = selected.length === options.length;
  const summary = allSelected
    ? allLabel
    : selected.length === 0
      ? 'None'
      : selected.length === 1
        ? options.find(o => o.value === selected[0])?.label ?? `${selected.length} selected`
        : `${selected.length} selected`;

  return (
    <div className="flex items-center gap-2" ref={ref}>
      <span className="text-[11px] font-semibold uppercase tracking-[0.06em] text-[rgba(0,0,0,0.35)]">
        {label}
      </span>
      <div className="relative">
        <button
          type="button"
          onClick={() => setOpen(o => !o)}
          aria-haspopup="listbox"
          aria-expanded={open}
          className="flex items-center gap-2 px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] font-medium text-[rgba(0,0,0,0.7)] bg-white hover:border-[rgba(0,0,0,0.25)] focus:outline-none focus:ring-2 focus:ring-indigo-500 min-w-[130px]"
        >
          <span className="truncate">{summary}</span>
          <Icon
            icon="mdi:chevron-down"
            width={16}
            className={`ml-auto text-[rgba(0,0,0,0.4)] transition-transform ${open ? 'rotate-180' : ''}`}
          />
        </button>

        {open && (
          <div
            role="listbox"
            aria-multiselectable="true"
            className="absolute left-0 top-full mt-1.5 z-50 min-w-[180px] bg-white rounded-lg shadow-lg border border-[rgba(0,0,0,0.1)] py-1"
          >
            {options.map(opt => {
              const checked = selected.includes(opt.value);
              return (
                <button
                  key={opt.value}
                  type="button"
                  role="option"
                  aria-selected={checked}
                  onClick={() => onToggle(opt.value)}
                  className="w-full flex items-center gap-2.5 px-3 py-1.5 text-left text-[13px] text-[rgba(0,0,0,0.8)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
                >
                  <span
                    className={`w-4 h-4 flex items-center justify-center rounded border transition-colors ${
                      checked
                        ? 'bg-indigo-600 border-indigo-600'
                        : 'bg-white border-[rgba(0,0,0,0.25)]'
                    }`}
                  >
                    {checked && <Icon icon="mdi:check" width={12} className="text-white" />}
                  </span>
                  <span className={`w-2 h-2 rounded-full ${opt.dot}`} />
                  <span className="font-medium">{opt.label}</span>
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Search and filter controls.
 *
 * The page previously offered one control: a single-select dropdown over `alert.service`. For
 * security rows that field holds the SCC category, so on the project that prompted this work the
 * dropdown had one meaningful option and there was no way at all to find a specific image among
 * thousands of rows. Search is the control that was actually missing.
 */
const GcpAlertsFilters: React.FC<Props> = ({
  services,
  selectedService,
  setSelectedService,
  searchTerm,
  setSearchTerm,
  activeTypes,
  toggleType,
  activeSeverities,
  toggleSeverity,
  resetFilters,
  filtersActive,
  resultCount,
  totalCount,
}) => (
  <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] space-y-3">

    <div className="flex items-center gap-3 flex-wrap">
      {/* Search */}
      <div className="relative flex-1 min-w-[240px]">
        <Icon
          icon="mdi:magnify"
          width={15}
          className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)] pointer-events-none"
        />
        <input
          id="gcp-alerts-search"
          type="search"
          value={searchTerm}
          onChange={e => setSearchTerm(e.target.value)}
          placeholder="Search by resource, category, or description…"
          aria-label="Search alerts by resource, category, or description"
          className="w-full pl-9 pr-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.8)] placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-400"
        />
      </div>

      {/* Service / category */}
      <div className="flex items-center gap-2">
        <label
          htmlFor="gcp-alerts-service"
          className="text-[13px] font-medium text-[rgba(0,0,0,0.6)] whitespace-nowrap"
        >
          Category
        </label>
        <select
          id="gcp-alerts-service"
          value={selectedService}
          onChange={e => setSelectedService(e.target.value)}
          className="px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] font-medium text-[rgba(0,0,0,0.7)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 max-w-[220px]"
        >
          <option value="All">All categories</option>
          {services.map(service => (
            <option key={service} value={service}>{service}</option>
          ))}
        </select>
      </div>
    </div>

    <div className="flex items-center gap-4 flex-wrap">
      {/* Type multi-select */}
      <MultiSelect
        label="Type"
        options={TYPE_OPTIONS}
        selected={activeTypes}
        onToggle={toggleType}
        allLabel="All types"
      />

      {/* Severity multi-select */}
      <MultiSelect
        label="Severity"
        options={SEVERITY_OPTIONS}
        selected={activeSeverities}
        onToggle={toggleSeverity}
        allLabel="All severities"
      />

      <div className="flex items-center gap-2 ml-auto">
        <span
          className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)] px-2 py-0.5 rounded-md"
          aria-live="polite"
        >
          {resultCount === totalCount
            ? `${totalCount.toLocaleString()} ${totalCount === 1 ? 'issue' : 'issues'}`
            : `${resultCount.toLocaleString()} of ${totalCount.toLocaleString()}`}
        </span>
        {filtersActive && (
          <button
            onClick={resetFilters}
            className="flex items-center gap-1 text-[12px] font-medium text-indigo-600 hover:text-indigo-800 transition-colors"
          >
            <Icon icon="mdi:close-circle-outline" width={13} />
            Clear filters
          </button>
        )}
      </div>
    </div>
  </div>
);

export default GcpAlertsFilters;
