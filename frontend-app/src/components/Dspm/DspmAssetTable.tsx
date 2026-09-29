import React from 'react';
import { Icon } from '@iconify/react';
import type { DspmAssetRow, DspmPage, DspmSeverity } from '../../types/dspm';

/**
 * The Cloud datastore table.
 *
 * Column set follows the vendor console's Cloud screen so a customer moving between the two reads the
 * same asset the same way. Two columns they have are deliberately absent: their internal Datastore ID
 * (the API exposes a derived UUID instead, which would not match) and the friendly Cloud Account name
 * (the API returns only an integration id, so the region is shown instead).
 */

const SEVERITY_STYLES: Record<DspmSeverity, string> = {
  CRITICAL: 'bg-rose-50 border-rose-200 text-rose-700',
  HIGH: 'bg-orange-50 border-orange-200 text-orange-700',
  MEDIUM: 'bg-amber-50 border-amber-200 text-amber-700',
  LOW: 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)]',
  NONE: '',
};

const SeverityCell: React.FC<{ level: DspmSeverity; hasFinding: boolean }> = ({ level, hasFinding }) => {
  // `NONE` means no policy has been evaluated, which is most assets. A dash reads as "nothing to say"
  // where a grey "None" badge would read as "evaluated and clean".
  if (!hasFinding) return <span className="text-xs text-[rgba(0,0,0,0.3)]">—</span>;
  return (
    <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-semibold ${SEVERITY_STYLES[level]}`}>
      {level.charAt(0) + level.slice(1).toLowerCase()}
    </span>
  );
};

/** Dot colours matching the vendor console's scan-status indicator. */
const SCAN_DOT: Record<string, string> = {
  CLASSIFIED: 'bg-emerald-500',
  SCANNING: 'bg-sky-500',
  STOPPED: 'bg-slate-400',
  FAILED: 'bg-rose-500',
  NOT_SCANNED: 'bg-[rgba(0,0,0,0.2)]',
};

const ScanCell: React.FC<{ status: string; label: string }> = ({ status, label }) => (
  <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-xs text-[rgba(0,0,0,0.65)]">
    <span className={`h-1.5 w-1.5 flex-shrink-0 rounded-full ${SCAN_DOT[status] ?? 'bg-[rgba(0,0,0,0.2)]'}`} />
    {label}
  </span>
);

/**
 * Sensitive record count — or an explanation of why there isn't one.
 *
 * The single most important cell on this page. In the live environment 71% of rows report zero records
 * while being unscanned, failed or stopped; only 11% are genuinely scanned and clean. Printing the raw
 * zero would tell a customer their data is clean when nobody has looked at it — and the highest-risk
 * assets in the test project are precisely the ones whose scans were stopped.
 *
 * `known` is computed server-side from the scan status so this rule lives in one place.
 */
const RecordsCell: React.FC<{ row: DspmAssetRow }> = ({ row }) => {
  const { recordCount, known } = row.sensitives;

  if (!known) {
    return (
      <span
        className="text-xs italic text-[rgba(0,0,0,0.35)]"
        title={`${row.scan.statusLabel} — no record count is available for this asset yet`}
      >
        Not available
      </span>
    );
  }
  if (recordCount === 0) {
    return (
      <span className="text-xs text-[rgba(0,0,0,0.45)]" title="Scanned; no sensitive records found">
        None found
      </span>
    );
  }
  return (
    <span className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.88)]">
      {recordCount.toLocaleString()}
    </span>
  );
};

/** Sensitive-data category tags, in the vendor's display wording. */
const SensitivesCell: React.FC<{ row: DspmAssetRow }> = ({ row }) => {
  const labels = row.sensitives.typeLabels;

  if (!labels.length) {
    return (
      <span className="text-xs text-[rgba(0,0,0,0.3)]">
        {row.sensitives.known ? 'No sensitives found' : '—'}
      </span>
    );
  }

  const [first, ...rest] = labels;
  return (
    <span className="inline-flex items-center gap-1 whitespace-nowrap">
      <span className="rounded border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.03)] px-1.5 py-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.65)]">
        {first}
      </span>
      {rest.length > 0 && (
        <span
          className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)]"
          title={rest.join(', ')}
        >
          +{rest.length}
        </span>
      )}
    </span>
  );
};

interface SortableHeaderProps {
  label: string;
  field?: string;
  sort: string;
  align?: 'left' | 'right';
  onSort: (field: string) => void;
}

const HeaderCell: React.FC<SortableHeaderProps> = ({ label, field, sort, align = 'left', onSort }) => {
  const active = field && (sort === field || sort === `-${field}`);
  const descending = field && sort === `-${field}`;

  const content = (
    <span className={`inline-flex items-center gap-1 ${align === 'right' ? 'justify-end' : ''}`}>
      {label}
      {field && (
        <Icon
          icon={active ? (descending ? 'mdi:arrow-down' : 'mdi:arrow-up') : 'mdi:arrow-up-down'}
          width={10}
          className={active ? 'text-[rgba(0,0,0,0.6)]' : 'text-[rgba(0,0,0,0.2)]'}
        />
      )}
    </span>
  );

  return (
    <th
      className={`whitespace-nowrap px-4 py-2.5 text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)] ${align === 'right' ? 'text-right' : 'text-left'}`}
    >
      {field ? (
        <button type="button" onClick={() => onSort(field)} className="hover:text-[rgba(0,0,0,0.7)]">
          {content}
        </button>
      ) : content}
    </th>
  );
};

interface Props {
  page: DspmPage<DspmAssetRow> | null;
  loading: boolean;
  sort: string;
  onSort: (field: string) => void;
  onSelect: (row: DspmAssetRow) => void;
}

const DspmAssetTable: React.FC<Props> = ({ page, loading, sort, onSort, onSelect }) => (
  <div className="overflow-x-auto">
    <table className="w-full text-left">
      <thead>
        <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
          <HeaderCell label="Data store" field="name" sort={sort} onSort={onSort} />
          <HeaderCell label="Severity" field="severity" sort={sort} onSort={onSort} />
          <HeaderCell label="Risk score" field="risk_score" sort={sort} align="right" onSort={onSort} />
          <HeaderCell
            label="Sensitive records" field="sensitive_record_count" sort={sort}
            align="right" onSort={onSort}
          />
          {/* Total objects, not the sensitive subset — the vendor's own field name is object_count and it
              counts everything discovered. Mislabelling it "sensitive objects" would inflate the figure
              by orders of magnitude. */}
          <HeaderCell label="Objects" sort={sort} align="right" onSort={onSort} />
          <HeaderCell label="Location" sort={sort} onSort={onSort} />
          <HeaderCell label="Scan status" field="last_modified_at" sort={sort} onSort={onSort} />
          <HeaderCell label="Sensitives" sort={sort} onSort={onSort} />
        </tr>
      </thead>
      <tbody>
        {loading && !page && (
          <tr><td colSpan={8} className="px-4 py-12 text-center text-xs text-[rgba(0,0,0,0.4)]">
            Loading datastores…
          </td></tr>
        )}

        {page && page.data.length === 0 && (
          <tr><td colSpan={8} className="px-4 py-12 text-center">
            <Icon icon="mdi:database-search-outline" width={32} className="mx-auto text-[rgba(0,0,0,0.15)]" />
            <p className="mt-2 text-xs font-semibold text-[rgba(0,0,0,0.6)]">No datastores match</p>
            <p className="mt-1 text-xs text-[rgba(0,0,0,0.4)]">
              Try clearing a filter or widening your search.
            </p>
          </td></tr>
        )}

        {page?.data.map(row => (
          <tr
            key={row.id}
            onClick={() => onSelect(row)}
            className="cursor-pointer border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.015)]"
          >
            <td className="px-4 py-3">
              <div className="max-w-[18rem] truncate text-xs font-semibold text-[rgba(0,0,0,0.88)]" title={row.name}>
                {row.name}
              </div>
              <div className="mt-0.5 flex items-center gap-1.5">
                <span className="text-[10px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
                  {row.subtypeLabel ?? row.subtype ?? '—'}
                </span>
                {row.category === 'on_prem' && (
                  <span className="rounded bg-[rgba(0,0,0,0.05)] px-1 text-[9px] font-semibold text-[rgba(0,0,0,0.45)]">
                    self-hosted
                  </span>
                )}
              </div>
            </td>

            <td className="px-4 py-3">
              <SeverityCell level={row.risk.level} hasFinding={row.risk.hasFinding} />
            </td>

            <td className="px-4 py-3 text-right">
              {row.risk.score === null
                ? <span className="text-xs text-[rgba(0,0,0,0.3)]">—</span>
                : <span className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.75)]">
                    {row.risk.score.toFixed(2)}
                  </span>}
            </td>

            <td className="px-4 py-3 text-right"><RecordsCell row={row} /></td>

            <td className="px-4 py-3 text-right">
              <span className="text-xs tabular-nums text-[rgba(0,0,0,0.55)]">
                {row.sensitives.objectCount.toLocaleString()}
              </span>
            </td>

            <td className="px-4 py-3">
              <span className="whitespace-nowrap text-xs text-[rgba(0,0,0,0.6)]">
                {row.source.region ?? '—'}
              </span>
            </td>

            <td className="px-4 py-3">
              <ScanCell status={row.scan.status} label={row.scan.statusLabel} />
            </td>

            <td className="px-4 py-3"><SensitivesCell row={row} /></td>
          </tr>
        ))}
      </tbody>
    </table>
  </div>
);

export default DspmAssetTable;
