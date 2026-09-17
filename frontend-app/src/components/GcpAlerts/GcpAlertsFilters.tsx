import React from 'react';
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

const TYPE_CHIPS: { type: GcpAlertType; label: string; active: string }[] = [
  { type: 'SECURITY', label: 'Security', active: 'bg-red-50 text-red-700 border-red-300' },
  { type: 'ANOMALY', label: 'Cost', active: 'bg-amber-50 text-amber-700 border-amber-300' },
  { type: 'QUOTA', label: 'Quota', active: 'bg-blue-50 text-blue-700 border-blue-300' },
];

const SEVERITY_CHIPS: { severity: GcpAlertStatus; label: string; active: string }[] = [
  { severity: 'CRITICAL', label: 'Critical', active: 'bg-red-50 text-red-700 border-red-300' },
  { severity: 'HIGH', label: 'High', active: 'bg-orange-50 text-orange-700 border-orange-300' },
  { severity: 'WARNING', label: 'Warning', active: 'bg-amber-50 text-amber-700 border-amber-300' },
  { severity: 'OK', label: 'OK', active: 'bg-emerald-50 text-emerald-700 border-emerald-300' },
];

const INACTIVE_CHIP =
  'bg-white text-[rgba(0,0,0,0.4)] border-[rgba(0,0,0,0.12)] hover:border-[rgba(0,0,0,0.25)]';

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
      {/* Type chips */}
      <div className="flex items-center gap-1.5" role="group" aria-label="Filter by alert type">
        <span className="text-[11px] font-semibold uppercase tracking-[0.06em] text-[rgba(0,0,0,0.35)] mr-0.5">
          Type
        </span>
        {TYPE_CHIPS.map(chip => {
          const on = activeTypes.includes(chip.type);
          return (
            <button
              key={chip.type}
              onClick={() => toggleType(chip.type)}
              aria-pressed={on}
              className={`px-2.5 py-1 rounded-md text-[11px] font-semibold border transition-all ${on ? chip.active : INACTIVE_CHIP}`}
            >
              {chip.label}
            </button>
          );
        })}
      </div>

      {/* Severity chips */}
      <div className="flex items-center gap-1.5" role="group" aria-label="Filter by severity">
        <span className="text-[11px] font-semibold uppercase tracking-[0.06em] text-[rgba(0,0,0,0.35)] mr-0.5">
          Severity
        </span>
        {SEVERITY_CHIPS.map(chip => {
          const on = activeSeverities.includes(chip.severity);
          return (
            <button
              key={chip.severity}
              onClick={() => toggleSeverity(chip.severity)}
              aria-pressed={on}
              className={`px-2.5 py-1 rounded-md text-[11px] font-semibold border transition-all ${on ? chip.active : INACTIVE_CHIP}`}
            >
              {chip.label}
            </button>
          );
        })}
      </div>

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
