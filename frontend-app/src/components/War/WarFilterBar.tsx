import React from 'react';
import { Icon } from '@iconify/react';
import type { WarReport } from '../../types/war';
import type { WarFilters } from '../../hooks/useWarData';
import { SELECT_CLS } from './warTokens';

interface Props {
  report: WarReport;
  filters: WarFilters;
  resultCount: number;
  onChange: <K extends keyof WarFilters>(key: K, value: WarFilters[K]) => void;
  onReset: () => void;
}

/**
 * The three states worth a one-click switch. The long tail (protected, manual, not in
 * use) stays in the select, because a segmented control with eight options is a select
 * wearing a costume.
 */
const STATUS_SEGMENTS: Array<{ value: WarFilters['status']; label: string; icon: string }> = [
  { value: 'OPEN', label: 'Open', icon: 'mdi:shield-alert-outline' },
  { value: 'FAIL', label: 'Exposed', icon: 'mdi:shield-off-outline' },
  { value: 'All', label: 'All', icon: 'mdi:format-list-bulleted' },
];

const STATUS_OPTIONS: Array<{ value: WarFilters['status']; label: string }> = [
  { value: 'OPEN', label: 'Open (exposed, weakened, unverified)' },
  { value: 'All', label: 'All controls' },
  { value: 'FAIL', label: 'Exposed only' },
  { value: 'WARN', label: 'Weakened only' },
  { value: 'UNKNOWN', label: 'Unverified only' },
  { value: 'PASS', label: 'Protected only' },
  { value: 'MANUAL', label: 'Manual review only' },
  { value: 'NOT_APPLICABLE', label: 'Not in use' },
];

const WarFilterBar: React.FC<Props> = ({
  report, filters, resultCount, onChange, onReset,
}) => {
  const severities = Array.from(
    new Set(report.findings.map((f) => f.severity).filter(Boolean)),
  );
  const isFiltered = filters.attackPath !== 'All'
    || filters.category !== 'All'
    || filters.severity !== 'All'
    || filters.status !== 'OPEN'
    || filters.search.trim() !== '';

  const activePathLabel = report.attackPaths.find((p) => p.path === filters.attackPath)?.label;

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2 p-2 bg-white border border-[#e5e7eb] rounded-xl">
        {/* Search */}
        <div className="relative flex-1 min-w-[200px]">
          <Icon
            icon="mdi:magnify"
            width={14}
            className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.55)] pointer-events-none"
          />
          <input
            type="text"
            value={filters.search}
            onChange={(e) => onChange('search', e.target.value)}
            placeholder="Search controls, findings and evidence"
            aria-label="Search controls"
            className="w-full h-8 pl-8 pr-8 rounded-lg border border-[#e5e7eb] bg-[#fafafa] text-[12px] text-[rgba(0,0,0,0.8)] placeholder:text-[rgba(0,0,0,0.55)] focus:outline-none focus:bg-white focus:border-[rgba(0,0,0,0.55)] transition-colors"
          />
          {filters.search && (
            <button
              onClick={() => onChange('search', '')}
              aria-label="Clear search"
              className="absolute right-2 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.55)] hover:text-[rgba(0,0,0,0.7)] transition-colors"
            >
              <Icon icon="mdi:close-circle" width={14} />
            </button>
          )}
        </div>

        {/* Status — segmented control for the common cases */}
        <div className="h-8 flex items-center p-0.5 rounded-lg bg-[rgba(0,0,0,0.05)] border border-[#e5e7eb]">
          {STATUS_SEGMENTS.map((segment) => (
            <button
              key={segment.value}
              onClick={() => onChange('status', segment.value)}
              aria-pressed={filters.status === segment.value}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                filters.status === segment.value
                  ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                  : 'text-[rgba(0,0,0,0.6)] hover:text-[rgba(0,0,0,0.7)]'
              }`}
            >
              <Icon icon={segment.icon} width={12} />
              {segment.label}
            </button>
          ))}
        </div>

        {/* Status — full list */}
        <div className="relative">
          <select
            value={filters.status}
            onChange={(e) => onChange('status', e.target.value as WarFilters['status'])}
            aria-label="Filter by status"
            className={SELECT_CLS}
          >
            {STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
          <Icon
            icon="mdi:chevron-down"
            width={13}
            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.55)] pointer-events-none"
          />
        </div>

        {/* Family */}
        <div className="relative">
          <select
            value={filters.category}
            onChange={(e) => onChange('category', e.target.value)}
            aria-label="Filter by control family"
            className={SELECT_CLS}
          >
            <option value="All">All families</option>
            {report.categories.map((c) => (
              <option key={c.category} value={c.category}>
                {c.category} ({c.failed + c.warned + c.unknown})
              </option>
            ))}
          </select>
          <Icon
            icon="mdi:chevron-down"
            width={13}
            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.55)] pointer-events-none"
          />
        </div>

        {/* Severity */}
        <div className="relative">
          <select
            value={filters.severity}
            onChange={(e) => onChange('severity', e.target.value)}
            aria-label="Filter by severity"
            className={SELECT_CLS}
          >
            <option value="All">All severities</option>
            {severities.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
          <Icon
            icon="mdi:chevron-down"
            width={13}
            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.55)] pointer-events-none"
          />
        </div>

        <span className="text-[11.5px] font-medium text-[rgba(0,0,0,0.6)] whitespace-nowrap tabular-nums ml-auto pr-1">
          {resultCount} of {report.totalControls}
        </span>
      </div>

      {/* Active attack-path chip, set by clicking a card above */}
      {filters.attackPath !== 'All' && (
        <div className="flex items-center gap-2">
          <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-widest">
            Attack path
          </span>
          <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-white border border-[rgba(0,0,0,0.16)] text-[10.5px] font-semibold text-[rgba(0,0,0,0.6)]">
            {activePathLabel ?? filters.attackPath}
            <button
              onClick={() => onChange('attackPath', 'All')}
              aria-label="Clear attack path filter"
              className="text-[rgba(0,0,0,0.55)] hover:text-[rgba(0,0,0,0.7)] transition-colors"
            >
              <Icon icon="mdi:close" width={12} />
            </button>
          </span>
          {isFiltered && (
            <button
              onClick={onReset}
              className="text-[11.5px] font-medium text-[rgba(0,0,0,0.6)] underline underline-offset-2 hover:text-[rgba(0,0,0,0.88)] transition-colors ml-auto"
            >
              Reset filters
            </button>
          )}
        </div>
      )}
    </div>
  );
};

export default WarFilterBar;
