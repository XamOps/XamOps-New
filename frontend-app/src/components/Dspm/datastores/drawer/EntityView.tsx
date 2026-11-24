import React, { useMemo, useState } from 'react';
import type { DatastoreSensitiveEntity } from '../../../../types/dspmDatastores';
import type { DatastoreDrawerApi } from '../../../../hooks/useDspmDatastoreDrawer';
import { usePaged } from '../../dspmShared';
import { abbreviate } from '../datastoreFormat';
import { CategoryChip, EntityChip } from '../ui/Chips';
import { TableFooter } from '../ui/TableFooter';
import OccurrenceTable from './OccurrenceTable';
import { EmptyTableRow, ExpandButton, TH } from './TabParts';

interface Props {
  api: DatastoreDrawerApi;
  /** As returned: count descending, the blank entity last. */
  entities: DatastoreSensitiveEntity[];
  search: string;
  /** Which occurrence columns to show (the view toggle's predicate). */
  database: boolean;
  showFalsePositives: boolean;
}

/**
 * Entity View: "Entities | Sensitives", paged in the browser (Rows Per Page 10). A row expands into the
 * entity's occurrences, one entity at a time. The vendor's blank entity shows "-" and its count, with no
 * chip and no chevron, and does not expand.
 *
 * The occurrence table is keyed by entity and switch, so collapsing the row, changing Show False
 * Positives or filtering the entity out of view discards its page state and its samples.
 */
const EntityView: React.FC<Props> = ({ api, entities, search, database, showFalsePositives }) => {
  const needle = search.trim().toLowerCase();
  const visible = useMemo(
    () => (needle ? entities.filter(entity => entity.name.toLowerCase().includes(needle)) : entities),
    [entities, needle],
  );
  const paged = usePaged(visible, needle);
  const [expanded, setExpanded] = useState<string | null>(null);

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
          {visible.length === 0 && <EmptyTableRow colSpan={2} searching={needle !== '' && entities.length > 0} />}
          {paged.items.map((entity, index) => {
            const expandable = !entity.blank;
            const open = expandable && expanded === entity.name;
            const toggle = () => setExpanded(open ? null : entity.name);
            return (
              <React.Fragment key={`${index}:${entity.name}`}>
                <tr
                  onClick={expandable ? toggle : undefined}
                  className={`border-b border-[rgba(0,0,0,0.04)] ${
                    expandable ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.02)]' : ''
                  } ${open ? 'bg-[rgba(0,0,0,0.03)]' : ''}`}
                >
                  <td className="px-3 py-2.5">
                    {entity.blank ? (
                      <span className="pl-6 text-xs text-[rgba(0,0,0,0.4)]">-</span>
                    ) : (
                      <span className="flex min-w-0 items-center gap-2">
                        <ExpandButton expanded={open} name={entity.name} onToggle={toggle} />
                        <EntityChip name={entity.name} categoryLabel={entity.categoryLabel} />
                      </span>
                    )}
                  </td>
                  <td className="px-3 py-2.5">
                    <span className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={entity.count.toLocaleString('en-US')}>
                        {abbreviate(entity.count)}
                      </span>
                      {!entity.blank && <CategoryChip label={entity.categoryLabel} />}
                    </span>
                  </td>
                </tr>
                {open && (
                  <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                    <td colSpan={2} className="px-4 py-3">
                      <OccurrenceTable
                        key={`${entity.name}|${showFalsePositives}`}
                        api={api}
                        entityName={entity.name}
                        showFalsePositives={showFalsePositives}
                        database={database}
                      />
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
      <TableFooter
        page={paged.page}
        pageSize={paged.size}
        total={paged.total}
        onPage={paged.setPage}
        onPageSize={paged.setSize}
      />
    </div>
  );
};

export default EntityView;
