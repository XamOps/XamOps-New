import React, { useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { WarFinding } from '../../types/war';
import {
  ATTACK_PATH_ICONS,
  BTN_PRIMARY,
  CARD,
  COL_HEADER,
  FINDINGS_GRID,
  severityToken,
  STATUS_TOKENS,
} from './warTokens';

interface Props {
  findings: WarFinding[];
  onResetFilters: () => void;
}

const PAGE_SIZE_OPTIONS = [15, 25, 50, 100];

/**
 * The 86-control list, as a grid table matching the BillOps tickets list.
 *
 * Rows expand in place rather than navigating, because the evidence is the payload —
 * "MFA is not enforced" is an assertion, whereas naming the four users with console
 * passwords and no MFA device is something an engineer can act on without re-deriving
 * the finding. Paginated because 86 unpaginated rows buries the filter bar.
 */
const WarFindingsTable: React.FC<Props> = ({ findings, onResetFilters }) => {
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(15);

  const totalPages = Math.max(1, Math.ceil(findings.length / pageSize));
  const currentPage = Math.min(page, totalPages);

  // Filters changing underneath a deep page would otherwise leave an empty view.
  useEffect(() => setPage(1), [findings.length, pageSize]);

  const paged = useMemo(
    () => findings.slice((currentPage - 1) * pageSize, currentPage * pageSize),
    [findings, currentPage, pageSize],
  );

  const rangeStart = findings.length === 0 ? 0 : (currentPage - 1) * pageSize + 1;
  const rangeEnd = Math.min(currentPage * pageSize, findings.length);

  if (findings.length === 0) {
    return (
      <div className={CARD}>
        <div className="flex flex-col items-center justify-center py-14 text-center px-6">
          <div className="w-11 h-11 bg-[rgba(0,0,0,0.04)] rounded-xl flex items-center justify-center mb-3">
            <Icon icon="mdi:filter-remove-outline" width={22} className="text-[rgba(0,0,0,0.55)]" />
          </div>
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.78)] mb-1">
            No controls match these filters
          </p>
          <p className="text-[12px] text-[rgba(0,0,0,0.6)] mb-4">
            Widen the filters, or switch the status filter to show all controls.
          </p>
          <button onClick={onResetFilters} className={BTN_PRIMARY}>
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
      {/* Sticky column header, offset by the 48px topbar */}
      <div
        className={`${FINDINGS_GRID} sticky top-12 z-[5] px-4 py-2 bg-[#f9fafb]/95 backdrop-blur-sm border-b border-[#e5e7eb] rounded-t-xl`}
      >
        <span className={COL_HEADER}>Control</span>
        <span className={`hidden lg:block ${COL_HEADER}`}>Family</span>
        <span className={`hidden sm:block ${COL_HEADER}`}>Severity</span>
        <span className={COL_HEADER}>Status</span>
        <span className={`hidden lg:block ${COL_HEADER}`}>Affected</span>
        <span aria-hidden="true" />
      </div>

      <div className="divide-y divide-[#e5e7eb]">
        {paged.map((finding) => (
          <WarFindingRow
            key={finding.controlId}
            finding={finding}
            isExpanded={expandedId === finding.controlId}
            onToggle={() =>
              setExpandedId((prev) => (prev === finding.controlId ? null : finding.controlId))
            }
          />
        ))}
      </div>

      {/* Pagination */}
      <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-2.5 bg-[#f9fafb] border-t border-[#e5e7eb] rounded-b-xl">
        <span className="text-[11.5px] font-medium text-[rgba(0,0,0,0.6)] tabular-nums">
          Showing {rangeStart}–{rangeEnd} of {findings.length}
        </span>

        <div className="flex items-center gap-3">
          <div className="relative">
            <select
              value={pageSize}
              onChange={(e) => setPageSize(Number(e.target.value))}
              aria-label="Rows per page"
              className="h-7 pl-2 pr-6 rounded-md border border-[#e5e7eb] bg-white text-[11.5px] font-medium text-[rgba(0,0,0,0.6)] appearance-none focus:outline-none focus:border-[rgba(0,0,0,0.55)] cursor-pointer"
            >
              {PAGE_SIZE_OPTIONS.map((n) => (
                <option key={n} value={n}>{n} / page</option>
              ))}
            </select>
            <Icon
              icon="mdi:chevron-down"
              width={12}
              className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.55)] pointer-events-none"
            />
          </div>

          <div className="flex items-center gap-1">
            <button
              onClick={() => setPage(Math.max(1, currentPage - 1))}
              disabled={currentPage <= 1}
              aria-label="Previous page"
              className="w-7 h-7 flex items-center justify-center rounded-md border border-[#e5e7eb] bg-white text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] disabled:opacity-40 disabled:hover:border-[#e5e7eb] transition-colors"
            >
              <Icon icon="mdi:chevron-left" width={15} />
            </button>
            <span className="text-[11.5px] font-medium text-[rgba(0,0,0,0.55)] px-1 tabular-nums">
              {currentPage} / {totalPages}
            </span>
            <button
              onClick={() => setPage(Math.min(totalPages, currentPage + 1))}
              disabled={currentPage >= totalPages}
              aria-label="Next page"
              className="w-7 h-7 flex items-center justify-center rounded-md border border-[#e5e7eb] bg-white text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] disabled:opacity-40 disabled:hover:border-[#e5e7eb] transition-colors"
            >
              <Icon icon="mdi:chevron-right" width={15} />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

/** Evidence lines shown before the list collapses behind a toggle. */
const EVIDENCE_PREVIEW = 5;

const WarFindingRow: React.FC<{
  finding: WarFinding;
  isExpanded: boolean;
  onToggle: () => void;
}> = ({ finding, isExpanded, onToggle }) => {
  const [showAllEvidence, setShowAllEvidence] = useState(false);

  const status = STATUS_TOKENS[finding.status];
  const severity = severityToken(finding.severity);

  const evidence = showAllEvidence
    ? finding.evidence
    : finding.evidence.slice(0, EVIDENCE_PREVIEW);
  const hiddenEvidence = finding.evidence.length - EVIDENCE_PREVIEW;

  return (
    <div>
      <div
        role="button"
        tabIndex={0}
        aria-expanded={isExpanded}
        onClick={onToggle}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onToggle();
          }
        }}
        className={`${FINDINGS_GRID} relative px-4 py-3 cursor-pointer group transition-colors hover:bg-[#fafafa] focus:outline-none focus-visible:bg-[#f5f5f5] ${
          isExpanded ? 'bg-[#fafafa]' : ''
        }`}
      >
        {/* Left-edge triage strip. Calm rows fall back to the black hover accent. */}
        <span
          aria-hidden="true"
          className={`absolute left-0 top-0 bottom-0 w-[3px] transition-colors ${
            finding.status === 'FAIL' || finding.status === 'WARN'
              ? status.accent
              : 'bg-transparent group-hover:bg-[rgba(0,0,0,0.88)]'
          }`}
        />

        {/* Control — the row title */}
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-[9.5px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-widest">
              {finding.controlId}
            </span>
            {!finding.automated && (
              <span className="text-[9px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-widest">
                not auto-checked
              </span>
            )}
          </div>
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate leading-snug">
            {finding.title}
          </p>
          {finding.summary && (
            <p className="text-[11.5px] text-[rgba(0,0,0,0.6)] truncate leading-snug mt-0.5">
              {finding.summary}
            </p>
          )}
          {/* Small-screen fallback for the hidden columns */}
          <div className="sm:hidden flex items-center gap-2 mt-1">
            <Icon icon={severity.icon} width={11} className={severity.text} />
            <span className={`text-[10px] uppercase tracking-wider ${severity.text}`}>
              {finding.severity}
            </span>
          </div>
        </div>

        {/* Family — neutral outline pill */}
        <span className="hidden lg:inline-flex items-center justify-center px-2 py-0.5 max-w-full border border-[rgba(0,0,0,0.16)] rounded-md text-[10.5px] font-semibold text-[rgba(0,0,0,0.6)] truncate">
          {finding.category}
        </span>

        {/* Severity — icon + coloured text, no pill, so it never competes with status */}
        <div className="hidden sm:flex items-center gap-1">
          <Icon icon={severity.icon} width={12} className={severity.text} />
          <span className={`text-[10.5px] uppercase tracking-wide truncate ${severity.text}`}>
            {finding.severity}
          </span>
        </div>

        {/* Status — solid pill, the primary anchor */}
        <span
          className={`inline-flex items-center gap-1.5 px-2 py-1 rounded-full text-[9.5px] font-semibold uppercase tracking-wider whitespace-nowrap w-fit ${status.pill}`}
        >
          <span
            className={`w-1.5 h-1.5 rounded-full bg-white ${
              finding.status === 'FAIL' ? 'animate-pulse' : ''
            }`}
          />
          {status.label}
        </span>

        {/* Affected resources */}
        <span className="hidden lg:block text-[11px] font-medium text-[rgba(0,0,0,0.6)] whitespace-nowrap tabular-nums">
          {finding.affectedCount > 0
            ? `${finding.affectedCount}${finding.scannedCount > 0 ? ` / ${finding.scannedCount}` : ''}`
            : '—'}
        </span>

        <Icon
          icon={isExpanded ? 'mdi:chevron-up' : 'mdi:chevron-down'}
          width={16}
          className="text-[rgba(0,0,0,0.45)] group-hover:text-[rgba(0,0,0,0.7)] transition-all"
        />
      </div>

      {isExpanded && (
        <div className="px-4 pb-5 pt-1 bg-[#fafafa] space-y-4">
          <div className="flex items-center gap-1.5 flex-wrap">
            {finding.attackPaths.map((path, i) => (
              <span
                key={path}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-white border border-[rgba(0,0,0,0.1)] text-[10.5px] font-medium text-[rgba(0,0,0,0.6)]"
              >
                <Icon icon={ATTACK_PATH_ICONS[path] ?? 'mdi:shield-outline'} width={11} />
                {finding.attackPathLabels[i]}
              </span>
            ))}
            {finding.region && (
              <span className="text-[10.5px] font-mono text-[rgba(0,0,0,0.55)] ml-1">
                {finding.region}
              </span>
            )}
          </div>

          {finding.evidence.length > 0 && (
            <section>
              <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-widest">
                What we found
              </span>
              <ul className="mt-2 space-y-1">
                {evidence.map((item, i) => (
                  <li
                    key={i}
                    className="text-[11.5px] text-[rgba(0,0,0,0.7)] font-mono bg-white border border-[#e5e7eb] rounded-md px-2.5 py-1.5 leading-relaxed break-words"
                  >
                    {item}
                  </li>
                ))}
              </ul>
              {hiddenEvidence > 0 && (
                <button
                  onClick={() => setShowAllEvidence((v) => !v)}
                  className="mt-2 text-[11.5px] font-medium text-[rgba(0,0,0,0.6)] underline underline-offset-2 hover:text-[rgba(0,0,0,0.88)] transition-colors"
                >
                  {showAllEvidence ? 'Show fewer' : `Show ${hiddenEvidence} more`}
                </button>
              )}
            </section>
          )}

          {finding.status === 'UNKNOWN' && finding.deniedAction && (
            <div className="flex items-start gap-3 px-4 py-3 rounded-xl border bg-[rgba(0,0,0,0.03)] border-[rgba(0,0,0,0.1)]">
              <Icon
                icon="mdi:key-alert-outline"
                width={15}
                className="text-[rgba(0,0,0,0.6)] mt-0.5 shrink-0"
              />
              <div>
                <div className="text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.6)] mb-1">
                  Why this is unverified
                </div>
                <p className="text-[12px] text-[rgba(0,0,0,0.6)] leading-relaxed">
                  The scan could not call{' '}
                  <code className="font-mono text-[11px] bg-white px-1 py-0.5 rounded border border-[#e5e7eb]">
                    {finding.deniedAction}
                  </code>
                  .
                </p>
              </div>
            </div>
          )}

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <section>
              <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-widest">
                How to fix it
              </span>
              <p className="mt-1.5 text-[12.5px] text-[rgba(0,0,0,0.6)] leading-relaxed">
                {finding.description}
              </p>
            </section>

            <section>
              <div className="flex items-center gap-2">
                <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-widest">
                  What it costs if you don&apos;t
                </span>
                <span
                  className={`px-1.5 py-0.5 rounded-md border text-[10px] font-semibold uppercase tracking-wide ${severityToken(finding.impactLevel).badge}`}
                >
                  {finding.impactLevel}
                </span>
              </div>
              <p className="mt-1.5 text-[12.5px] text-[rgba(0,0,0,0.6)] leading-relaxed">
                {finding.businessImpact}
              </p>
            </section>
          </div>

          <div className="flex items-center justify-between gap-4 flex-wrap pt-1 border-t border-[rgba(0,0,0,0.06)]">
            <span className="text-[11px] text-[rgba(0,0,0,0.55)] pt-2">
              <span className="uppercase tracking-widest font-semibold">Services:</span>{' '}
              {finding.tools}
            </span>
            {finding.reference && (
              <a
                href={finding.reference}
                target="_blank"
                rel="noopener noreferrer"
                className="text-[11.5px] font-medium text-[rgba(0,0,0,0.6)] underline underline-offset-2 hover:text-[rgba(0,0,0,0.88)] transition-colors flex items-center gap-1 pt-2"
              >
                AWS documentation
                <Icon icon="mdi:open-in-new" width={11} />
              </a>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default WarFindingsTable;
