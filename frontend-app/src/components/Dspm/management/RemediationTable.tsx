import React from 'react';
import { Icon } from '@iconify/react';
import type { RemediationItem } from '../../../types/dspmPages';
import { DspmProviderIcon } from '../DspmIcons';
import { formatListDate } from '../datastores/datastoreFormat';
import { TH } from '../datastores/drawer/TabParts';
import { initialOf } from '../datastores/saas/saasFormat';
import { EmptyState } from '../datastores/ui/EmptyState';
import { TONE_CLASSES, approverText, statusTone } from './remediationModel';

export type RemediationColumnId = 'action' | 'object' | 'datastore' | 'initiatedBy' | 'trigger' | 'time' | 'status';

/** The console's seven columns (`constants-a63ffc8b.js`); Action is always shown. */
export const REMEDIATION_COLUMNS: ReadonlyArray<{ id: RemediationColumnId; label: string }> = [
  { id: 'action', label: 'Action' },
  { id: 'object', label: 'File / Object' },
  { id: 'datastore', label: 'Datastore' },
  { id: 'initiatedBy', label: 'Initiated by' },
  { id: 'trigger', label: 'Trigger' },
  { id: 'time', label: 'Time' },
  { id: 'status', label: 'Status' },
];

const Dash: React.FC = () => <span className="text-[rgba(0,0,0,0.4)]">-</span>;

const Muted: React.FC<{ text: string }> = ({ text }) => (
  <span className="block truncate text-[11px] text-[rgba(0,0,0,0.45)]" title={text}>{text}</span>
);

export const ActionLabel: React.FC<{ item: RemediationItem }> = ({ item }) => (
  <span className="flex min-w-0 items-center gap-1.5 font-semibold text-[rgba(0,0,0,0.85)]">
    <Icon icon="mdi:shield-check-outline" width={14} className="flex-shrink-0 text-[rgba(0,0,0,0.5)]" />
    <span className="truncate">{item.actionLabel ?? item.actionType ?? '-'}</span>
  </span>
);

export const StatusChip: React.FC<{ item: RemediationItem }> = ({ item }) => {
  const text = item.statusLabel ?? item.status;
  if (!text) return <Dash />;
  return (
    <span className={`inline-flex w-fit items-center whitespace-nowrap rounded border px-1.5 py-0.5 text-[10px] font-semibold ${TONE_CLASSES[statusTone(item.status)].chip}`}>
      {text}
    </span>
  );
};

/** The line under the status chip: the approvers while waiting, the run time while scheduled. */
const StatusDetail: React.FC<{ item: RemediationItem }> = ({ item }) => {
  if (item.status === 'WAITING_APPROVAL') {
    const text = approverText(item.approvers);
    if (text === null) return null;
    return <span className="block truncate text-[11px] text-[rgba(0,0,0,0.45)]" title={item.approvers.join(', ')}>Approver: {text}</span>;
  }
  if (item.status === 'SCHEDULED' && item.scheduledAt !== null) return <Muted text={`Scheduled ${formatListDate(item.scheduledAt)}`} />;
  return null;
};

export const InitiatorCell: React.FC<{ item: RemediationItem }> = ({ item }) => {
  const who = item.initiatedBy?.name ?? item.initiatedBy?.email ?? null;
  if (who === null) return <Dash />;
  return (
    <span className="flex min-w-0 items-center gap-1.5">
      <span aria-hidden="true" className="flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full bg-[rgba(0,0,0,0.08)] text-[10px] font-semibold text-[rgba(0,0,0,0.6)]">
        {initialOf(who)}
      </span>
      <span className="truncate" title={who}>{who}</span>
    </span>
  );
};

const CELLS: Record<RemediationColumnId, (item: RemediationItem) => React.ReactNode> = {
  action: item => <ActionLabel item={item} />,
  object: item => (
    <span className="block min-w-0 max-w-[18rem]">
      <span className="block truncate text-[rgba(0,0,0,0.8)]" title={item.objectName ?? undefined}>{item.objectName ?? '-'}</span>
      {item.objectPath && <Muted text={item.objectPath} />}
    </span>
  ),
  datastore: item => (
    <span className="flex min-w-0 items-center gap-1.5">
      <DspmProviderIcon provider={item.datastoreType} size={16} />
      <span className="min-w-0">
        <span className="block truncate text-[rgba(0,0,0,0.8)]">{item.datastoreLabel ?? item.datastoreType ?? '-'}</span>
        {item.sourceType && <Muted text={item.sourceType} />}
      </span>
    </span>
  ),
  initiatedBy: item => <InitiatorCell item={item} />,
  trigger: item => item.triggerLabel ?? item.triggerType ?? <Dash />,
  time: item => (item.createdAt === null ? <Dash /> : <span className="whitespace-nowrap">{formatListDate(item.createdAt)}</span>),
  status: item => (
    <span className="flex min-w-0 flex-col gap-0.5">
      <StatusChip item={item} />
      <StatusDetail item={item} />
    </span>
  ),
};

interface Props {
  items: RemediationItem[];
  hidden: ReadonlySet<RemediationColumnId>;
  onOpen: (item: RemediationItem) => void;
}

/** The Activity table (FR-REM-4): one row per run of the current page; a row opens its run drawer. */
const RemediationTable: React.FC<Props> = ({ items, hidden, onOpen }) => {
  const shown = REMEDIATION_COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-t border-[rgba(0,0,0,0.06)]">
            {shown.map(column => <th key={column.id} className={TH}>{column.label}</th>)}
          </tr>
        </thead>
        <tbody>
          {items.length === 0 && (
            <tr><td colSpan={shown.length}><EmptyState title="No remediation activity yet" /></td></tr>
          )}
          {items.map((item, i) => (
            <tr
              key={item.id ?? `run-${i}`}
              tabIndex={0}
              onClick={() => onOpen(item)}
              onKeyDown={e => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  onOpen(item);
                }
              }}
              className="cursor-pointer border-t border-[rgba(0,0,0,0.06)] hover:bg-[rgba(0,0,0,0.02)] focus:outline-none focus-visible:bg-[rgba(0,0,0,0.04)]"
            >
              {shown.map(column => (
                <td key={column.id} className="px-3 py-2.5 align-top text-[rgba(0,0,0,0.75)]">{CELLS[column.id](item)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default RemediationTable;
