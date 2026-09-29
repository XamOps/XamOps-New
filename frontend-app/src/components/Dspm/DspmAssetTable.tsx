import React from 'react';
import { Icon } from '@iconify/react';
import type { DspmAssetRow, DspmPage } from '../../types/dspm';
import type { DspmSurface } from '../../hooks/useDspmData';
import { compactCount, formatEpochSeconds } from './dspmFormat';
import {
  DspmCloudMark, DspmProviderIcon, DspmScanStatus, DspmSeverityBadge,
} from './DspmIcons';

/**
 * Asset table for one DSPM surface.
 *
 * Columns differ per surface because the vendor fills different fields per asset type. Measured on the
 * live project: risk score, region and cloud account exist only on datastores; SaaS rows carry
 * last-scanned times; endpoint rows carry neither. A column that would be blank on every row is left out.
 */

type ColumnId =
  | 'asset' | 'severity' | 'riskScore' | 'records' | 'objects' | 'account' | 'scan'
  | 'sensitives' | 'lastScanned';

interface Column {
  id: ColumnId;
  label: string;
  /** Sort field sent to the API. Omitted where the API cannot order by the column. */
  field?: string;
  align?: 'left' | 'right';
}

const COLUMNS: Record<DspmSurface, Column[]> = {
  cloud: [
    { id: 'asset', label: 'Data store', field: 'name' },
    // Not sortable on Cloud: the API's severity sort orders by risk.level, but this column shows
    // sensitives.level (what the console shows), so a "sorted" column would look shuffled.
    { id: 'severity', label: 'Severity' },
    { id: 'riskScore', label: 'Risk score', field: 'risk_score', align: 'right' },
    { id: 'records', label: 'Sensitive records', field: 'sensitive_record_count', align: 'right' },
    // Total objects, not the sensitive subset.
    { id: 'objects', label: 'Objects', align: 'right' },
    { id: 'account', label: 'Cloud account' },
    { id: 'scan', label: 'Scan status', field: 'last_modified_at' },
    { id: 'sensitives', label: 'Sensitives' },
  ],
  saas: [
    { id: 'asset', label: 'Account', field: 'name' },
    { id: 'severity', label: 'Severity', field: 'severity' },
    { id: 'records', label: 'Sensitive records', field: 'sensitive_record_count', align: 'right' },
    { id: 'objects', label: 'Files', align: 'right' },
    { id: 'sensitives', label: 'Sensitives' },
    { id: 'scan', label: 'Scan status' },
    { id: 'lastScanned', label: 'Last scanned' },
  ],
  endpoint: [
    { id: 'asset', label: 'Device', field: 'name' },
    { id: 'severity', label: 'Severity', field: 'severity' },
    { id: 'records', label: 'Sensitive records', field: 'sensitive_record_count', align: 'right' },
    { id: 'objects', label: 'Files', align: 'right' },
    { id: 'sensitives', label: 'Sensitives' },
    { id: 'scan', label: 'Scan status' },
  ],
};

const NOUN: Record<DspmSurface, string> = {
  cloud: 'datastores',
  saas: 'accounts',
  endpoint: 'devices',
};

const Dash: React.FC = () => <span className="text-xs text-[rgba(0,0,0,0.3)]">—</span>;

/**
 * Sensitive record count, or why there isn't one.
 *
 * Unscanned, failed and stopped assets all report zero, and that zero is withheld: it would tell a
 * customer the asset is clean when nobody has looked. A non-zero count from an unfinished scan is shown,
 * as the console shows it — records found before a scan stopped are a real minimum.
 */
const RecordsCell: React.FC<{ row: DspmAssetRow }> = ({ row }) => {
  const { recordCount, known } = row.sensitives;
  if (!known && recordCount > 0) {
    return (
      <span
        className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.88)]"
        title={`${recordCount.toLocaleString()} found — ${row.scan.statusLabel.toLowerCase()}, so this may be incomplete`}
      >
        {compactCount(recordCount)}
      </span>
    );
  }
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
      <span className="text-xs text-[rgba(0,0,0,0.45)]" title="Scanned; no sensitive records found">0</span>
    );
  }
  return (
    <span
      className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.88)]"
      title={recordCount.toLocaleString()}
    >
      {compactCount(recordCount)}
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
        <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)]" title={rest.join(', ')}>
          +{rest.length}
        </span>
      )}
    </span>
  );
};

const AssetCell: React.FC<{ row: DspmAssetRow; surface: DspmSurface }> = ({ row, surface }) => {
  // Every endpoint shares the ENDPOINT_AGENT provider, so its OS subtype picks the icon instead.
  const iconKey = surface === 'endpoint'
    ? (row.subtype ?? row.source.provider)
    : (row.source.provider ?? row.subtype);
  const subline = surface === 'cloud' ? row.subtype : (row.subtypeLabel ?? row.subtype);

  return (
    <div className="flex items-start gap-2">
      <span className="mt-0.5"><DspmProviderIcon provider={iconKey} /></span>
      <div className="min-w-0">
        <div
          className="max-w-[18rem] truncate text-xs font-semibold text-[rgba(0,0,0,0.88)]"
          title={row.name}
        >
          {row.name}
        </div>
        <div className="mt-0.5 flex items-center gap-1.5">
          <span className="text-[10px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
            {subline ?? '—'}
          </span>
          {row.category === 'on_prem' && (
            <span className="rounded bg-[rgba(0,0,0,0.05)] px-1 text-[9px] font-semibold text-[rgba(0,0,0,0.45)]">
              self-hosted
            </span>
          )}
        </div>
      </div>
    </div>
  );
};

/**
 * Cloud account over region, as the console lays it out.
 *
 * The label comes from the Account filter's options, which the backend resolves from the integration id
 * to e.g. `605134457560 (Xammer-prod)`. Falls back to region alone when no label was resolved.
 */
const AccountCell: React.FC<{ row: DspmAssetRow; label?: string }> = ({ row, label }) => (
  <div className="whitespace-nowrap">
    <div
      className="flex items-center gap-1.5 text-xs text-[rgba(0,0,0,0.7)]"
      title={row.source.account ? `Integration ${row.source.account}` : undefined}
    >
      <DspmCloudMark provider={row.source.provider} />
      {label ?? row.source.region ?? '—'}
    </div>
    {label && row.source.region && (
      <div className="mt-0.5 text-[10px] text-[rgba(0,0,0,0.4)]">{row.source.region}</div>
    )}
    {!label && row.source.providerLabel && (
      <div className="mt-0.5 text-[10px] text-[rgba(0,0,0,0.4)]">{row.source.providerLabel}</div>
    )}
  </div>
);

function renderCell(id: ColumnId, row: DspmAssetRow, surface: DspmSurface,
                    accountLabels: Record<string, string>): React.ReactNode {
  switch (id) {
    case 'asset':
      return <AssetCell row={row} surface={surface} />;
    case 'severity':
      return <DspmSeverityBadge row={row} />;
    case 'riskScore':
      return row.risk.score === null
        ? <Dash />
        : <span className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.75)]">
            {row.risk.score.toFixed(2)}
          </span>;
    case 'records':
      return <RecordsCell row={row} />;
    case 'objects':
      // An unscanned asset reports zero objects for the same reason it reports zero records.
      return !row.sensitives.known && row.sensitives.objectCount === 0
        ? <Dash />
        : <span
            className="text-xs tabular-nums text-[rgba(0,0,0,0.55)]"
            title={row.sensitives.objectCount.toLocaleString()}
          >
            {compactCount(row.sensitives.objectCount)}
          </span>;
    case 'account':
      return (
        <AccountCell
          row={row}
          label={row.source.account ? accountLabels[row.source.account] : undefined}
        />
      );
    case 'scan':
      return <DspmScanStatus status={row.scan.status} label={row.scan.statusLabel} />;
    case 'sensitives':
      return <SensitivesCell row={row} />;
    case 'lastScanned':
      return row.scan.lastScannedAt
        ? <span className="whitespace-nowrap text-xs text-[rgba(0,0,0,0.6)]">
            {formatEpochSeconds(row.scan.lastScannedAt)}
          </span>
        : <Dash />;
    default:
      return null;
  }
}

const HeaderCell: React.FC<{ column: Column; sort: string; onSort: (field: string) => void }> =
  ({ column, sort, onSort }) => {
    const { label, field, align = 'left' } = column;
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
  surface: DspmSurface;
  page: DspmPage<DspmAssetRow> | null;
  loading: boolean;
  sort: string;
  /** Integration id to resolved account label. Only used by the Cloud account column. */
  accountLabels: Record<string, string>;
  onSort: (field: string) => void;
  onSelect: (row: DspmAssetRow) => void;
}

const DspmAssetTable: React.FC<Props> = ({
  surface, page, loading, sort, accountLabels, onSort, onSelect,
}) => {
  const columns = COLUMNS[surface];

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left">
        <thead>
          <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
            {columns.map(column => (
              <HeaderCell key={column.id} column={column} sort={sort} onSort={onSort} />
            ))}
          </tr>
        </thead>
        <tbody>
          {loading && !page && (
            <tr><td colSpan={columns.length} className="px-4 py-12 text-center text-xs text-[rgba(0,0,0,0.4)]">
              Loading {NOUN[surface]}…
            </td></tr>
          )}

          {page && page.data.length === 0 && (
            <tr><td colSpan={columns.length} className="px-4 py-12 text-center">
              <Icon icon="mdi:database-search-outline" width={32} className="mx-auto text-[rgba(0,0,0,0.15)]" />
              <p className="mt-2 text-xs font-semibold text-[rgba(0,0,0,0.6)]">No {NOUN[surface]} match</p>
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
              {columns.map(column => (
                <td
                  key={column.id}
                  className={`px-4 py-3 ${column.align === 'right' ? 'text-right' : ''}`}
                >
                  {renderCell(column.id, row, surface, accountLabels)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default DspmAssetTable;
