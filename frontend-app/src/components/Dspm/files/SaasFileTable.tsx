import React from 'react';
import { Icon } from '@iconify/react';
import type { SaasFileListRow } from '../../../types/dspmFiles';
import { TH } from '../datastores/drawer/TabParts';
import { TD } from '../datastores/endpoint/EndpointParts';
import { EmptyState } from '../datastores/ui/EmptyState';
import { SAAS_FILE_COLUMNS, renderSaasFileCell, type SaasFileColumnId } from './saasFileColumns';

export type SaasFileOpenTab = 'sensitives' | 'lineage';

interface Props {
  /** The page on screen; null while the first page of a query loads or after it failed. */
  rows: SaasFileListRow[] | null;
  loading: boolean;
  hidden: ReadonlySet<SaasFileColumnId>;
  /** A search or a filter is applied: decides the empty texts. */
  filtered: boolean;
  /** `opener` is the row, focused again when the drawer closes. */
  onOpen: (row: SaasFileListRow & { id: string }, tab: SaasFileOpenTab, opener: HTMLElement) => void;
}

const FILE_ID = /^[0-9a-fA-F]{24}$/;
const canOpen = (row: SaasFileListRow): row is SaasFileListRow & { id: string } => row.id !== null && FILE_ID.test(row.id);

/** The sources with a hash lineage, whose rows get the lineage icon. */
const LINEAGE_SOURCES: ReadonlySet<string> = new Set(['ONE_DRIVE', 'SHARE_POINT', 'GOOGLE_WORKSPACE']);

/*
 * File Name and the lineage icon stay in place while the other columns scroll, as in the Endpoint File View:
 * both get the row's background and z-10. The name cell is 18rem wide, which is where the icon column starts.
 */
const STICKY_NAME = 'sticky left-0 z-10 w-72 min-w-[18rem] max-w-[18rem]';
const STICKY_ICON = 'sticky left-72 z-10 w-14 min-w-[3.5rem]';
const STICKY_HEAD_BG = 'bg-[#fbfbfb]';

/**
 * The SaaS Files table: the visible columns of "Columns 6", with an unlabelled lineage column after File
 * Name. A row with a 24-hex id is focusable and opens the SaaS file drawer on a click or on Enter pressed on
 * the row itself; a Drive, OneDrive or SharePoint row's lineage icon opens it on Lineage. Other rows are inert.
 */
const SaasFileTable: React.FC<Props> = ({ rows, loading, hidden, filtered, onOpen }) => {
  const columns = SAAS_FILE_COLUMNS.filter((column, index) => index === 0 || !hidden.has(column.id));
  const span = columns.length + 1;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left">
        <thead>
          <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
            {columns.map(column => (column.id === 'name' ? (
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
          {rows !== null && rows.length === 0 && (
            <tr>
              <td colSpan={span}>
                {filtered
                  ? <EmptyState title="No files match these filters" body="Try clearing the search or filters." />
                  : <EmptyState title="No files yet" body="Files appear here once a scan has found them." />}
              </td>
            </tr>
          )}
          {rows?.map((row, index) => {
            const openable = canOpen(row);
            const lineage = openable && row.source !== null && LINEAGE_SOURCES.has(row.source);
            return (
              <tr
                key={row.id ?? `row-${index}`}
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
                {columns.map(column => (column.id === 'name' ? (
                  <React.Fragment key={column.id}>
                    <td className={`${TD} ${STICKY_NAME} bg-white`}>{renderSaasFileCell(column.id, row)}</td>
                    <td className={`${TD} ${STICKY_ICON} bg-white`}>
                      {lineage && (
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
                      )}
                    </td>
                  </React.Fragment>
                ) : (
                  <td key={column.id} className={TD}>{renderSaasFileCell(column.id, row)}</td>
                )))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

export default SaasFileTable;
