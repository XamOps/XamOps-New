import React from 'react';
import type { EndpointFileSensitive } from '../../../../types/dspmFiles';
import { FILE_PAGE_SIZES, usePaged } from '../../dspmShared';
import { abbreviate } from '../datastoreFormat';
import { TH } from '../drawer/TabParts';
import { CategoryChip, EntityChip } from '../ui/Chips';
import { EmptyState } from '../ui/EmptyState';
import { TableFooter } from '../ui/TableFooter';

interface Props {
  /** The drawer's file: the paging resets when it changes. */
  fileId: string;
  sensitives: EndpointFileSensitive[];
}

/**
 * The Endpoint file's Sensitives tab (plan C16): "Entities | Sensitives" from the overview, in API order,
 * each entity coloured by its sensitive label, paged in the browser at 10, 25 or 50 rows. No search and no
 * Columns menu.
 */
const EndpointFileSensitivesTab: React.FC<Props> = ({ fileId, sensitives }) => {
  const paged = usePaged(sensitives, fileId);
  return (
    <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
      <table className="w-full text-left">
        <thead>
          <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
            <th scope="col" className={TH}>Entities</th>
            <th scope="col" className={TH}>Sensitives</th>
          </tr>
        </thead>
        <tbody>
          {sensitives.length === 0 && (
            <tr>
              <td colSpan={2}>
                <EmptyState title="No sensitive data found" body="The last scan of this file matched no data elements." />
              </td>
            </tr>
          )}
          {paged.items.map((sensitive, index) => (
            <tr key={`${index}:${sensitive.dataElement ?? ''}`} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
              <td className="px-3 py-2.5">
                <EntityChip name={sensitive.dataElement ?? ''} categoryLabel={sensitive.categoryLabel} />
              </td>
              <td className="px-3 py-2.5">
                <span className="flex flex-wrap items-center gap-1.5">
                  <span className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={sensitive.count.toLocaleString('en-US')}>
                    {abbreviate(sensitive.count)}
                  </span>
                  <CategoryChip label={sensitive.categoryLabel} />
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <TableFooter
        page={paged.page}
        pageSize={paged.size}
        total={paged.total}
        onPage={paged.setPage}
        onPageSize={paged.setSize}
        pageSizes={FILE_PAGE_SIZES}
      />
    </div>
  );
};

export default EndpointFileSensitivesTab;
