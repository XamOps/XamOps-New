import React, { useMemo } from 'react';
import type { SaasFileCopies, SaasFileOverview } from '../../../../types/dspmDrawers';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { DspmProviderIcon } from '../../DspmIcons';
import { usePaged } from '../../dspmShared';
import { sourceIconKey } from '../datastoreVisuals';
import { EmptyState } from '../ui/EmptyState';
import { TableFooter } from '../ui/TableFooter';
import { ResourceView, TH } from '../drawer/TabParts';
import { CONTENT_HASH_VERIFIED, relatedFileRows, relatedFilesHeading, type RelatedFileRow } from './relatedFiles';
import { GreyTag } from './SaasParts';

const HEADERS = ['Source', 'File', 'Where', 'Confidence', 'Why'];
const TD = 'px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]';

const RelatedTable: React.FC<{ rows: RelatedFileRow[] }> = ({ rows }) => {
  const paged = usePaged(rows, '');
  if (rows.length === 0) {
    return (
      <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
        <EmptyState title="No related files found in other sources." />
      </div>
    );
  }
  return (
    <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
      <div className="overflow-x-auto">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
              {HEADERS.map(header => <th key={header} scope="col" className={TH}>{header}</th>)}
            </tr>
          </thead>
          <tbody>
            {paged.items.map(({ copy, samePerson, why }) => (
              <tr key={copy.key} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                <td className={TD}>
                  <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
                    <DspmProviderIcon provider={sourceIconKey(copy)} size={16} />
                    {copy.sourceLabel ?? '-'}
                  </span>
                </td>
                <td className={TD}>
                  <span className="block max-w-[14rem] truncate font-semibold text-[rgba(0,0,0,0.85)]" title={copy.fileName ?? undefined}>
                    {copy.fileName ?? '-'}
                  </span>
                </td>
                <td className={TD}>
                  <span className="block">{copy.ownerName ?? '-'}</span>
                  {copy.ownerEmail && <span className="block text-[11px] text-[rgba(0,0,0,0.45)]">{copy.ownerEmail}</span>}
                  {samePerson && <span className="mt-1 block"><GreyTag>Same person</GreyTag></span>}
                </td>
                <td className={TD}>
                  <span className="whitespace-nowrap rounded border border-emerald-200 bg-emerald-50 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-700">
                    {CONTENT_HASH_VERIFIED}
                  </span>
                </td>
                <td className={TD}>
                  <span className="block max-w-[14rem] truncate" title={why}>{why}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <TableFooter page={paged.page} pageSize={paged.size} total={paged.total} onPage={paged.setPage} onPageSize={paged.setSize} />
    </div>
  );
};

interface Props {
  copies: LazyResource<SaasFileCopies>;
  /** Null while the overview loads or after it failed: no e-mail in the header, no "Same person". */
  overview: SaasFileOverview | null;
  datastoreName: string;
}

/**
 * The Related Files tab (FR-SFILE-11, D7): the file's content-hash copies, from the copies answer File
 * Copies also reads, as Source | File | Where | Confidence | Why. Matters returns no confidence or match
 * reason (GAP-61), so every row reads "Content hash verified" and the reason is derived (`relatedFiles.ts`).
 */
const SaasRelatedFilesTab: React.FC<Props> = ({ copies, overview, datastoreName }) => {
  const owner = overview?.owner ?? null;
  const heading = relatedFilesHeading(datastoreName, owner);
  const rows = useMemo(() => (copies.data ? relatedFileRows(copies.data, owner) : []), [copies.data, owner]);
  return (
    <div className="space-y-3">
      <p className="text-xs text-[rgba(0,0,0,0.7)]">
        Related files for <strong className="font-semibold text-[rgba(0,0,0,0.88)]">{heading.name}</strong>
        {heading.email && <> — {heading.email}</>}
        <span className="text-[rgba(0,0,0,0.45)]"> (matched by file owner)</span>
      </p>
      <ResourceView resource={copies} subject="file">
        {() => <RelatedTable rows={rows} />}
      </ResourceView>
    </div>
  );
};

export default SaasRelatedFilesTab;
