import React from 'react';
import { Icon } from '@iconify/react';
import type { EndpointFileRow } from '../../../../types/dspmDrawers';
import { abbreviate, formatBytes, formatConsoleDate } from '../datastoreFormat';
import { TH } from '../drawer/TabParts';
import { DotChip, LabelChips } from '../ui/Chips';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import {
  CATEGORY_DOT, DOCUMENT_CLASS_DOT, ENDPOINT_FILE_COLUMNS, type EndpointFileColumnId,
} from './endpointFileColumns';
import { isRecentlyModified } from './endpointFormat';
import { FileNameCell, NewBadge, PathCell, TD, TagChips } from './EndpointParts';

/** The tab an endpoint file drawer opens on: a row opens Sensitives, its lineage icon Lineage. */
export type EndpointFileOpenTab = 'sensitives' | 'lineage';

/** A row whose id is the vendor's file UUID, so the Endpoint file drawer can open it. */
export type OpenableEndpointFileRow = EndpointFileRow & { id: string };

const FILE_ID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const canOpen = (row: EndpointFileRow): row is OpenableEndpointFileRow => row.id !== null && FILE_ID.test(row.id);

interface Props {
  /** The page on screen; null while the first page of a query loads or after it failed. */
  rows: EndpointFileRow[] | null;
  loading: boolean;
  hidden: ReadonlySet<EndpointFileColumnId>;
  /** The body of an empty page. */
  emptyState: React.ReactNode;
  /**
   * Unset: rows are inert and the lineage icon is the greyed "Open lineage". Set: a row with a UUID id is
   * focusable and opens on a click or on Enter pressed on the row itself, and its lineage icon opens
   * Lineage; other rows are inert with an empty lineage cell. `opener` is the row, focused again when the
   * drawer closes.
   */
  onOpen?: (row: OpenableEndpointFileRow, tab: EndpointFileOpenTab, opener: HTMLElement) => void;
}

/*
 * File Name and the lineage icon stay in place while the other columns scroll (ninth batch): both get
 * the row's background and z-10, so the scrolled cells pass under them. The name cell is 14rem wide
 * (its text truncates inside), which is where the icon column starts.
 */
const STICKY_NAME = 'sticky left-0 z-10 w-56 min-w-[14rem] max-w-[14rem]';
const STICKY_ICON = 'sticky left-56 z-10 w-10';
/** The header row's tint as an opaque colour, so the scrolled header cells do not show through. */
const STICKY_HEAD_BG = 'bg-[#fbfbfb]';

const DASH = <span className="text-[rgba(0,0,0,0.4)]">-</span>;

function renderCell(column: EndpointFileColumnId, row: EndpointFileRow): React.ReactNode {
  switch (column) {
    case 'fileName':
      return (
        <span className="flex min-w-0 max-w-[12.5rem] items-center gap-1.5">
          <FileNameCell name={row.name} extension={row.extension} title={row.path ?? row.name} />
          {isRecentlyModified(row.modifiedAt, Date.now()) && <NewBadge />}
        </span>
      );
    case 'path':
      return <PathCell path={row.path} />;
    case 'user':
      return row.user ?? DASH;
    case 'size':
      return row.sizeBytes === null ? DASH : <span className="whitespace-nowrap">{formatBytes(row.sizeBytes)}</span>;
    case 'sensitives':
      return (
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={row.sensitiveCount.toLocaleString('en-US')}>
            {abbreviate(row.sensitiveCount)}
          </span>
          <LabelChips labels={row.labels} />
        </span>
      );
    case 'category':
      return row.categoryLabel ? <DotChip label={row.categoryLabel} dot={CATEGORY_DOT} /> : DASH;
    case 'documentClass':
      return row.documentClassLabel ? <DotChip label={row.documentClassLabel} dot={DOCUMENT_CLASS_DOT} /> : DASH;
    case 'tags':
      return <TagChips tags={row.tags} />;
    case 'modified':
      return row.modifiedAt === null ? DASH : <span className="whitespace-nowrap">{formatConsoleDate(row.modifiedAt)}</span>;
  }
}

/**
 * The endpoint file table of "Columns 9" (build 2's File View and the Files page's Endpoint tab): the
 * visible columns, with an unlabelled lineage column after File Name that the menu neither counts nor
 * lists. A file modified in the last 48 hours reads "New" after its name.
 */
const EndpointFileTable: React.FC<Props> = ({ rows, loading, hidden, emptyState, onOpen }) => {
  const columns = ENDPOINT_FILE_COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));
  const span = columns.length + 1;

  const lineageCell = (row: EndpointFileRow) => {
    if (!onOpen) {
      return <ConsoleOnlyButton variant="icon" icon="mdi:graph-outline" label="Open lineage" placement="right" />;
    }
    if (!canOpen(row)) return null;
    return (
      <button
        type="button"
        title="View lineage"
        aria-label={`View lineage for ${row.name ?? '-'}`}
        onClick={e => {
          e.stopPropagation();
          const opener = e.currentTarget.closest('tr');
          if (opener) onOpen(row, 'lineage', opener);
        }}
        className="inline-flex rounded-md p-1 text-[rgba(0,0,0,0.55)] transition-colors hover:bg-[rgba(0,0,0,0.05)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]"
      >
        <Icon icon="mdi:graph-outline" width={16} />
      </button>
    );
  };

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left">
        <thead>
          <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
            {columns.map(column => (column.id === 'fileName' ? (
              <React.Fragment key={column.id}>
                <th scope="col" className={`${TH} ${STICKY_NAME} ${STICKY_HEAD_BG}`}>{column.label}</th>
                <th scope="col" aria-label="Lineage" className={`${STICKY_ICON} ${STICKY_HEAD_BG}`} />
              </React.Fragment>
            ) : (
              <th key={column.id} scope="col" className={`${TH} whitespace-nowrap`}>{column.label}</th>
            )))}
          </tr>
        </thead>
        <tbody>
          {rows === null && loading && (
            <tr>
              <td colSpan={span} className="px-4 py-12 text-center text-xs text-[rgba(0,0,0,0.4)]">Loading files…</td>
            </tr>
          )}
          {rows !== null && rows.length === 0 && <tr><td colSpan={span}>{emptyState}</td></tr>}
          {rows?.map((row, index) => {
            const openable = onOpen !== undefined && canOpen(row);
            return (
              <tr
                key={`${index}:${row.id ?? ''}`}
                onClick={openable ? (e => onOpen(row, 'sensitives', e.currentTarget)) : undefined}
                onKeyDown={openable ? (e => {
                  if (e.key === 'Enter' && e.target === e.currentTarget) {
                    e.preventDefault();
                    onOpen(row, 'sensitives', e.currentTarget);
                  }
                }) : undefined}
                tabIndex={openable ? 0 : undefined}
                className={`border-b border-[rgba(0,0,0,0.04)] last:border-b-0 ${
                  openable ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.015)] focus:outline-none focus-visible:bg-[rgba(0,0,0,0.03)]' : ''
                }`}
              >
                {columns.map(column => (column.id === 'fileName' ? (
                  <React.Fragment key={column.id}>
                    <td className={`${TD} ${STICKY_NAME} bg-white`}>{renderCell(column.id, row)}</td>
                    <td className={`${TD} ${STICKY_ICON} bg-white`}>{lineageCell(row)}</td>
                  </React.Fragment>
                ) : (
                  <td key={column.id} className={TD}>{renderCell(column.id, row)}</td>
                )))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

export default EndpointFileTable;
