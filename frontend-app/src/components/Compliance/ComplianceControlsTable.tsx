import React, { useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { ComplianceControl } from '../../types/compliance';
import FrameworkBadge from './FrameworkBadge';
import {
  BTN_PRIMARY,
  CARD,
  COL_HEADER,
  CONTROLS_GRID,
  deriveTitle,
  severityToken,
  statusToken,
} from './complianceTokens';

interface Props {
  controls: ComplianceControl[];
  onSelect: (control: ComplianceControl) => void;
  onResetFilters: () => void;
}

const PAGE_SIZE_OPTIONS = [25, 50, 100, 200];

/**
 * The controls list, as a grid table matching the BillOps tickets list.
 *
 * Two changes worth naming. The Passed column shows a real number — the data was always
 * fetched and then thrown away, so the column rendered an em dash on every row. And rows
 * are keyed by check id rather than by a truncated description, so the same control stays
 * the same row across scanner releases instead of splitting when a message is reworded.
 */
const ComplianceControlsTable: React.FC<Props> = ({ controls, onSelect, onResetFilters }) => {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);

  const totalPages = Math.max(1, Math.ceil(controls.length / pageSize));
  const currentPage = Math.min(page, totalPages);

  useEffect(() => setPage(1), [controls.length, pageSize]);

  const paged = useMemo(
    () => controls.slice((currentPage - 1) * pageSize, currentPage * pageSize),
    [controls, currentPage, pageSize],
  );

  const rangeStart = controls.length === 0 ? 0 : (currentPage - 1) * pageSize + 1;
  const rangeEnd = Math.min(currentPage * pageSize, controls.length);

  if (controls.length === 0) {
    return (
      <div className={CARD}>
        <div className="flex flex-col items-center justify-center py-16 text-center px-6">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-xl flex items-center justify-center mb-3">
            <Icon icon="mdi:filter-remove-outline" width={26} className="text-[rgba(0,0,0,0.35)]" />
          </div>
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.78)] mb-1">
            No controls match these filters
          </p>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mb-4 max-w-[44ch] leading-relaxed">
            Widen the filters, or switch the status filter to All to see every control the
            scan covered.
          </p>
          <button type="button" onClick={onResetFilters} className={BTN_PRIMARY}>
            <Icon icon="mdi:filter-remove-outline" width={14} />
            Reset filters
          </button>
        </div>
      </div>
    );
  }

  return (
    // No overflow-hidden: it would break the sticky column header.
    <div className={CARD}>
      <div
        className={`${CONTROLS_GRID} sticky top-12 z-[5] px-4 py-2 bg-[#f9fafb]/95 backdrop-blur-sm border-b border-[#e5e7eb] rounded-t-xl`}
      >
        <span className={COL_HEADER}>Control</span>
        <span className={`hidden lg:block ${COL_HEADER}`}>Service</span>
        <span className={`hidden sm:block ${COL_HEADER}`}>Severity</span>
        <span className={COL_HEADER}>Status</span>
        <span className={`hidden lg:block ${COL_HEADER}`}>Fail / Pass</span>
        <span className={`hidden lg:block ${COL_HEADER}`}>Frameworks</span>
        <span aria-hidden="true" />
      </div>

      <div className="divide-y divide-[#e5e7eb]">
        {paged.map((control) => (
          <ControlRow key={control.checkId} control={control} onSelect={onSelect} />
        ))}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-2.5 bg-[#f9fafb] border-t border-[#e5e7eb] rounded-b-xl">
        <span className="text-[11.5px] font-medium text-[rgba(0,0,0,0.5)] tabular-nums">
          Showing {rangeStart}–{rangeEnd} of {controls.length}
        </span>

        <div className="flex items-center gap-3">
          <div className="relative">
            <select
              value={pageSize}
              onChange={(e) => setPageSize(Number(e.target.value))}
              aria-label="Rows per page"
              className="h-7 pl-2 pr-6 rounded-md border border-[#e5e7eb] bg-white text-[11.5px] font-medium text-[rgba(0,0,0,0.6)] appearance-none focus:outline-none focus:border-[rgba(0,0,0,0.4)] cursor-pointer"
            >
              {PAGE_SIZE_OPTIONS.map((n) => (
                <option key={n} value={n}>{n} / page</option>
              ))}
            </select>
            <Icon
              icon="mdi:chevron-down"
              width={12}
              className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)] pointer-events-none"
            />
          </div>

          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => setPage(Math.max(1, currentPage - 1))}
              disabled={currentPage <= 1}
              aria-label="Previous page"
              className="w-7 h-7 flex items-center justify-center rounded-md border border-[#e5e7eb] bg-white text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.4)] disabled:opacity-40 disabled:hover:border-[#e5e7eb] transition-colors"
            >
              <Icon icon="mdi:chevron-left" width={15} />
            </button>
            <span className="text-[11.5px] font-medium text-[rgba(0,0,0,0.55)] px-1 tabular-nums">
              {currentPage} / {totalPages}
            </span>
            <button
              type="button"
              onClick={() => setPage(Math.min(totalPages, currentPage + 1))}
              disabled={currentPage >= totalPages}
              aria-label="Next page"
              className="w-7 h-7 flex items-center justify-center rounded-md border border-[#e5e7eb] bg-white text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.4)] disabled:opacity-40 disabled:hover:border-[#e5e7eb] transition-colors"
            >
              <Icon icon="mdi:chevron-right" width={15} />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

const ControlRow: React.FC<{
  control: ComplianceControl;
  onSelect: (control: ComplianceControl) => void;
}> = ({ control, onSelect }) => {
  const status = statusToken(control.status);
  const severity = severityToken(control.severity);
  const title = deriveTitle(control.title, control.checkId);

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onSelect(control)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onSelect(control);
        }
      }}
      className={`${CONTROLS_GRID} relative px-4 py-3 cursor-pointer group transition-colors hover:bg-[#fafafa] focus:outline-none focus-visible:bg-[#f5f5f5]`}
    >
      {/* Left-edge triage strip. Settled rows fall back to the black hover accent. */}
      <span
        aria-hidden="true"
        className={`absolute left-0 top-0 bottom-0 w-[3px] transition-colors ${
          control.status === 'FAIL' || control.status === 'WAIVED'
            ? status.accent
            : 'bg-transparent group-hover:bg-[rgba(0,0,0,0.88)]'
        }`}
      />

      <div className="min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-[9.5px] font-mono text-[rgba(0,0,0,0.35)] truncate max-w-[34ch]">
            {control.checkId}
          </span>
          {control.newlyFailing && (
            <span className="text-[9px] font-semibold uppercase tracking-widest text-red-600">
              new
            </span>
          )}
          {control.failingForScans >= 3 && !control.newlyFailing && (
            <span
              className="text-[9px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.35)]"
              title={`Failing for ${control.failingForScans} consecutive scans`}
            >
              {control.failingForScans} scans
            </span>
          )}
          {!control.owner && control.status === 'FAIL' && (
            <span
              className="text-[9px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.3)]"
              title="No owner assigned"
            >
              unowned
            </span>
          )}
        </div>
        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate leading-snug">
          {title}
        </p>
        {control.owner && (
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] truncate leading-snug mt-0.5">
            {control.owner.name ?? control.owner.email}
            {control.owner.team ? ` · ${control.owner.team}` : ''}
          </p>
        )}
        {/* Small-screen fallback for the columns hidden below sm */}
        <div className="sm:hidden flex items-center gap-1.5 mt-1">
          <Icon icon={severity.icon} width={11} className={severity.text} />
          <span className={`text-[10px] uppercase tracking-wider ${severity.text}`}>
            {control.severity}
          </span>
        </div>
      </div>

      <span className="hidden lg:inline-flex items-center justify-center px-2 py-0.5 max-w-full border border-[rgba(0,0,0,0.16)] rounded-md text-[10.5px] font-semibold text-[rgba(0,0,0,0.6)] truncate">
        {control.service ?? '—'}
      </span>

      <div className="hidden sm:flex items-center gap-1">
        <Icon icon={severity.icon} width={12} className={severity.text} />
        <span className={`text-[10.5px] uppercase tracking-wide truncate ${severity.text}`}>
          {control.severity}
        </span>
      </div>

      <span
        title={status.description}
        className={`inline-flex items-center gap-1.5 px-2 py-1 rounded-full text-[9.5px] font-semibold uppercase tracking-wider whitespace-nowrap w-fit ${status.pill}`}
      >
        <span
          className={`w-1.5 h-1.5 rounded-full bg-white ${
            control.status === 'FAIL' ? 'animate-pulse' : ''
          }`}
        />
        {status.label}
      </span>

      {/* Fail / Pass resource counts — the Passed side was always an em dash before */}
      <span className="hidden lg:flex items-baseline gap-1 tabular-nums">
        <span
          className={`text-[13px] font-semibold ${
            control.failedResources > 0 ? 'text-red-600' : 'text-[rgba(0,0,0,0.25)]'
          }`}
        >
          {control.failedResources}
        </span>
        <span className="text-[11px] text-[rgba(0,0,0,0.2)]">/</span>
        <span
          className={`text-[12px] font-medium ${
            control.passedResources > 0 ? 'text-emerald-600' : 'text-[rgba(0,0,0,0.25)]'
          }`}
        >
          {control.passedResources}
        </span>
      </span>

      <div className="hidden lg:flex items-center gap-1 min-w-0">
        {control.frameworks.slice(0, 4).map((f) => (
          <FrameworkBadge key={f.framework} framework={f.framework} size={20} />
        ))}
        {control.frameworks.length > 4 && (
          <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.4)] ml-0.5 tabular-nums">
            +{control.frameworks.length - 4}
          </span>
        )}
        {control.frameworks.length === 0 && (
          <span className="text-[10.5px] text-[rgba(0,0,0,0.25)]">unmapped</span>
        )}
      </div>

      <Icon
        icon="mdi:chevron-right"
        width={16}
        className="text-[rgba(0,0,0,0.18)] group-hover:text-[rgba(0,0,0,0.7)] transition-colors"
      />
    </div>
  );
};

export default ComplianceControlsTable;
