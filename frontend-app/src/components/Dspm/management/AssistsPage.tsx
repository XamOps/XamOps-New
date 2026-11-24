import React, { useState } from 'react';
import { gooeyToast } from 'goey-toast';
import type { DspmStatus } from '../../../types/dspm';
import type { Assist, AssistList } from '../../../types/dspmPages';
import { pagesApi } from '../../../hooks/useDspmPages';
import { useLazyResource } from '../../../hooks/useDspmDatastoreDrawer';
import DspmNotReady from '../DspmNotReady';
import { usePaged } from '../dspmShared';
import { ErrorNote, LoadingNote, TH, useHiddenColumns } from '../datastores/drawer/TabParts';
import { formatConsoleDay } from '../datastores/saas/saasFormat';
import { DotChip } from '../datastores/ui/Chips';
import { ColumnsMenu } from '../datastores/ui/ColumnsMenu';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import { CopyButton } from '../datastores/ui/CopyButton';
import { EmptyState } from '../datastores/ui/EmptyState';
import { TableFooter } from '../datastores/ui/TableFooter';
import { secretExpiry, secretExpiryText } from './assistFormat';

type ColumnId = 'name' | 'clientId' | 'status' | 'assistType' | 'version' | 'secretExpiry' | 'actions';

/** The console's seven columns; Assist Name is always shown. */
const COLUMNS: ReadonlyArray<{ id: ColumnId; label: string }> = [
  { id: 'name', label: 'Assist Name' },
  { id: 'clientId', label: 'Client ID' },
  { id: 'status', label: 'Status' },
  { id: 'assistType', label: 'Assist Type' },
  { id: 'version', label: 'Version' },
  { id: 'secretExpiry', label: 'Secret Expiry' },
  { id: 'actions', label: 'Actions' },
];

const ASSIST_PAGE_SIZES = [10, 25, 50, 100] as const;

const COPIED_TOAST = 'Client ID Copied Successfully';

const Dash: React.FC = () => <span className="text-[rgba(0,0,0,0.4)]">-</span>;

const ExpiryCell: React.FC<{ seconds: number | null; nowMs: number }> = ({ seconds, nowMs }) => {
  if (seconds === null) return <Dash />;
  const expiry = secretExpiry(seconds, nowMs);
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <span className="text-[rgba(0,0,0,0.75)]">{formatConsoleDay(seconds)}</span>
      {expiry && (
        <span
          className={`rounded border px-1.5 py-0.5 text-[10px] font-semibold ${
            expiry.kind === 'expired'
              ? 'border-rose-200 bg-rose-50 text-rose-700'
              : 'border-amber-200 bg-amber-50 text-amber-700'
          }`}
        >
          {secretExpiryText(expiry)}
        </span>
      )}
    </span>
  );
};

const CELLS: Record<ColumnId, (assist: Assist, nowMs: number) => React.ReactNode> = {
  name: assist => <span className="font-semibold text-[rgba(0,0,0,0.85)]">{assist.name ?? '-'}</span>,
  clientId: assist => assist.clientId === null ? <Dash /> : (
    <span className="flex min-w-0 items-center gap-1">
      <span className="max-w-[14rem] truncate font-mono text-[11px] text-[rgba(0,0,0,0.75)]" title={assist.clientId}>
        {assist.clientId}
      </span>
      <CopyButton text={assist.clientId} label="Copy Client ID" onCopied={() => gooeyToast.success(COPIED_TOAST)} />
    </span>
  ),
  status: assist => assist.statusLabel === null ? <Dash />
    : <DotChip label={assist.statusLabel} dot={assist.statusLabel === 'Active' ? 'bg-emerald-500' : 'bg-rose-500'} />,
  assistType: assist => assist.assistTypeLabel ?? <Dash />,
  version: assist => assist.version ?? <Dash />,
  secretExpiry: (assist, nowMs) => <ExpiryCell seconds={assist.secretExpiresAt} nowMs={nowMs} />,
  actions: () => <ConsoleOnlyButton variant="link" placement="left">Reissue</ConsoleOnlyButton>,
};

const AssistTable: React.FC<{ list: AssistList }> = ({ list }) => {
  const [hidden, toggleColumn] = useHiddenColumns<ColumnId>();
  // Read once: the tags need not tick while the page is open.
  const [nowMs] = useState(() => Date.now());
  const paged = usePaged(list.assists, 'assists');
  const shown = COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));
  return (
    <div className="rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
      <div className="flex items-center px-4 py-3">
        <ColumnsMenu columns={COLUMNS} hidden={hidden} onToggle={toggleColumn} />
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="border-t border-[rgba(0,0,0,0.06)]">
              {shown.map(column => <th key={column.id} className={TH}>{column.label}</th>)}
            </tr>
          </thead>
          <tbody>
            {paged.items.length === 0 && (
              <tr><td colSpan={shown.length}><EmptyState title="No assists found" /></td></tr>
            )}
            {paged.items.map((assist, i) => (
              <tr key={assist.id ?? `assist-${i}`} className="border-t border-[rgba(0,0,0,0.06)]">
                {shown.map(column => (
                  <td key={column.id} className="px-3 py-2.5 text-[rgba(0,0,0,0.75)]">{CELLS[column.id](assist, nowMs)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <TableFooter
        page={paged.page}
        pageSize={paged.size}
        total={paged.total}
        onPage={paged.setPage}
        onPageSize={paged.setSize}
        pageSizes={ASSIST_PAGE_SIZES}
      />
    </div>
  );
};

const ReadyAssists: React.FC = () => {
  const list = useLazyResource(pagesApi.assists, true);
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">Assists</h1>
        <ConsoleOnlyButton variant="primary" placement="left">Create Assist</ConsoleOnlyButton>
      </div>
      {list.error ? <ErrorNote error={list.error} onRetry={list.reload} subject="item" />
        : list.data === null ? <LoadingNote />
          : <AssistTable list={list.data} />}
    </div>
  );
};

interface Props {
  status: DspmStatus;
  onRetry: () => void;
}

/**
 * XamLens Assists (MANAGEMENT; design 6.6): the on-prem connectors of `GET /api/dspm/assists` with the
 * console's seven columns, paged 10 / 25 / 50 / 100 in the browser. The Client ID copies with the
 * console's toast; Create Assist and every Reissue are the console's and greyed.
 */
const AssistsPage: React.FC<Props> = ({ status, onRetry }) => {
  if (!status.ready) return <DspmNotReady status={status} onRetry={onRetry} />;
  return <ReadyAssists />;
};

export default AssistsPage;
