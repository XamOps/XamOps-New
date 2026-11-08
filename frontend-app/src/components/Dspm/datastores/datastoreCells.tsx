import React from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import { DspmCloudMark, DspmProviderIcon } from '../DspmIcons';
import { CategoryChip } from '../dspmShared';
import type { ColumnId } from './datastoreTabConfig';
import { formatRiskScore, formatScanTime, sensitiveDataText } from './datastoreFormat';
import {
  CLOUD_MARK_PROVIDERS, agentStatusVisual, datastoreIconKey, riskVisual, scanStatusVisual,
} from './datastoreVisuals';
import { LevelLabel } from './ui/Chips';

const MUTED = 'text-[11px] text-[rgba(0,0,0,0.45)]';
const DASH = <span className="text-xs text-[rgba(0,0,0,0.4)]">-</span>;

/** Type icon, name (truncated, full in the tooltip) and the subtype label below. */
const NameCell: React.FC<{ row: DatastoreRow }> = ({ row }) => (
  <div className="flex min-w-0 items-center gap-2.5">
    <DspmProviderIcon provider={datastoreIconKey(row)} size={20} />
    <div className="min-w-0">
      <div className="max-w-[18rem] truncate text-xs font-semibold text-[rgba(0,0,0,0.85)]" title={row.name ?? undefined}>
        {row.name ?? '-'}
      </div>
      {row.subtypeLabel && <div className={MUTED}>{row.subtypeLabel}</div>}
    </div>
  </div>
);

/** "-" without a level; otherwise the score (when there is one) in a box tinted by level, then the level. */
const RiskCell: React.FC<{ row: DatastoreRow }> = ({ row }) => {
  const { score, level, levelLabel } = row.risk;
  if (!level) return DASH;
  return (
    <span className="inline-flex items-center gap-2">
      {score !== null && (
        <span className={`rounded border px-1.5 py-0.5 text-[11px] font-semibold tabular-nums ${riskVisual(level).box}`}>
          {formatRiskScore(score)}
        </span>
      )}
      <LevelLabel level={level} label={levelLabel} />
    </span>
  );
};

/** The record count ("-" only when never scanned), then one chip per sensitive type. */
const SensitiveDataCell: React.FC<{ row: DatastoreRow }> = ({ row }) => {
  const { recordCount, typeLabels } = row.sensitives;
  const text = sensitiveDataText(row.scan.status, recordCount);
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <span
        className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.8)]"
        title={text === '-' ? undefined : recordCount.toLocaleString('en-US')}
      >
        {text}
      </span>
      {text !== '-' && recordCount > 0 && typeLabels.map(label => <CategoryChip key={label} label={label} />)}
    </div>
  );
};

/**
 * The account's icon: the cloud's mark, Google Drive for Google Workspace, else the provider; none for
 * endpoints. Also the drawer's Source/Account icon, which follows the list rule.
 */
export function accountIcon(row: DatastoreRow): React.ReactNode {
  if (row.category === 'endpoint') return null;
  const provider = row.source.provider;
  if (provider && CLOUD_MARK_PROVIDERS.has(provider)) return <DspmCloudMark provider={provider} size={14} />;
  if (provider === 'GOOGLE_WORKSPACE') return <DspmProviderIcon provider="GOOGLE_DRIVE" size={14} />;
  return <DspmProviderIcon provider={provider} size={14} />;
}

/** Account label (full text in the tooltip; on-prem rows carry the datastore name) and the region below. */
const SourceAccountCell: React.FC<{ row: DatastoreRow }> = ({ row }) => (
  <div className="flex min-w-0 items-center gap-2">
    {accountIcon(row)}
    <div className="min-w-0">
      <div className="max-w-[14rem] truncate text-xs text-[rgba(0,0,0,0.75)]" title={row.source.accountLabel ?? undefined}>
        {row.source.accountLabel ?? '-'}
      </div>
      {row.source.region && <div className={MUTED}>{row.source.region}</div>}
    </div>
  </div>
);

/** Status icon and label, then the time the backend picked for the status. */
const ScanStatusCell: React.FC<{ row: DatastoreRow; nowMs: number }> = ({ row, nowMs }) => {
  const visual = scanStatusVisual(row.scan.status);
  const time = formatScanTime(row.scan.displayAt, nowMs);
  return (
    <div>
      <span className="inline-flex items-center gap-1 text-xs text-[rgba(0,0,0,0.75)]">
        <Icon icon={visual.icon} width={14} className={visual.cls} />
        {row.scan.statusLabel ?? '-'}
      </span>
      {time && <div className={MUTED}>{time}</div>}
    </div>
  );
};

const AgentStatusCell: React.FC<{ row: DatastoreRow }> = ({ row }) => {
  const status = row.agent?.status;
  if (!status) return DASH;
  const visual = agentStatusVisual(status);
  return (
    <span className="inline-flex items-center gap-1 text-xs text-[rgba(0,0,0,0.75)]">
      <Icon icon={visual.icon} width={14} className={visual.cls} />
      {row.agent?.statusLabel ?? status}
    </span>
  );
};

const TextCell: React.FC<{ value: string | null | undefined }> = ({ value }) =>
  value ? <span className="text-xs text-[rgba(0,0,0,0.75)]">{value}</span> : DASH;

/** One list cell. `nowMs` anchors the relative scan times. */
export function renderDatastoreCell(column: ColumnId, row: DatastoreRow, nowMs: number): React.ReactNode {
  switch (column) {
    case 'name': return <NameCell row={row} />;
    case 'risk': return <RiskCell row={row} />;
    case 'sensitiveData': return <SensitiveDataCell row={row} />;
    case 'sourceAccount': return <SourceAccountCell row={row} />;
    case 'scanStatus': return <ScanStatusCell row={row} nowMs={nowMs} />;
    case 'serialNumber': return <TextCell value={row.agent?.serialNumber} />;
    case 'agentStatus': return <AgentStatusCell row={row} />;
    case 'agentVersion': return <TextCell value={row.agent?.version} />;
  }
}
