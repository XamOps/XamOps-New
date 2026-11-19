import React, { useEffect, useMemo, useState } from 'react';
import type { DspmError } from '../../../../types/dspm';
import type { EndpointMetric } from '../../../../types/dspmDrawers';
import type { EndpointDrawerApi, EndpointFilterParams } from '../../../../hooks/useDspmEndpointDrawer';
import { toDspmError } from '../../../../hooks/useDspmDatastores';
import { usePaged } from '../../dspmShared';
import { abbreviate } from '../datastoreFormat';
import { ErrorNote, ExpandButton, LoadingNote, TH } from '../drawer/TabParts';
import { GreyTag } from '../saas/SaasParts';
import { CategoryChip, EntityChip } from '../ui/Chips';
import { TableFooter } from '../ui/TableFooter';
import EndpointClassFiles from './EndpointClassFiles';
import EndpointOccurrenceTable from './EndpointOccurrenceTable';
import { endpointFilterValueAllowed } from './endpointFormat';
import { NoEntitiesState, NoMatchesState } from './EndpointParts';

interface Props {
  api: EndpointDrawerApi;
  /** Memoised by the caller: a new object means new filters and a new request. */
  filters: EndpointFilterParams;
  showFalsePositives: boolean;
  search: string;
}

const documentClassAllowed = endpointFilterValueAllowed('documentClass');

/**
 * Entity View (FR-EP-5): "Entities / Document Class | Sensitives" from `/endpoint/sensitives`, requested
 * again whenever the filters or Show False Positives change, count descending as returned. The search
 * filters the rows in the browser; paging is client-side (Rows Per Page 10) and returns to page 1 on any
 * filter, switch or search change.
 *
 * One row is open at a time, and it closes on any filter, switch or page change. An entity opens its
 * occurrences; a document class opens its files, but only when its name is a value the filters accept:
 * the expansion sends it through the comma-split `documentClass` filter, so a name with a comma or edge
 * spaces would return other classes' files without an error (GAP-21). Such a row has no chevron.
 */
const EndpointEntityView: React.FC<Props> = ({ api, filters, showFalsePositives, search }) => {
  const [metrics, setMetrics] = useState<EndpointMetric[] | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let current = true;
    setMetrics(null);
    setError(null);
    api.sensitives(filters, showFalsePositives).then(
      next => { if (current) setMetrics(next.metrics); },
      err => { if (current) setError(toDspmError(err)); },
    );
    return () => { current = false; };
  }, [api, filters, showFalsePositives, attempt]);

  const needle = search.trim().toLowerCase();
  const visible = useMemo(
    () => (metrics ?? []).filter(metric => !needle || metric.name.toLowerCase().includes(needle)),
    [metrics, needle],
  );
  // The request behind the rows: a new one returns to page 1 and closes the open row.
  const requestKey = useMemo(() => `${JSON.stringify(filters)}|${showFalsePositives}`, [filters, showFalsePositives]);
  const paged = usePaged(visible, `${requestKey}|${needle}`);
  const [expanded, setExpanded] = useState<{ key: string; at: string } | null>(null);
  const at = `${requestKey}|${paged.page}`;
  const openKey = expanded?.at === at ? expanded.key : null;

  return (
    <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
      <table className="w-full text-left">
        <thead>
          <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
            <th scope="col" className={TH}>Entities / Document Class</th>
            <th scope="col" className={TH}>Sensitives</th>
          </tr>
        </thead>
        <tbody>
          {error && (
            <tr>
              <td colSpan={2} className="px-3 py-2"><ErrorNote error={error} onRetry={() => setAttempt(n => n + 1)} /></td>
            </tr>
          )}
          {!error && metrics === null && (
            <tr><td colSpan={2}><LoadingNote /></td></tr>
          )}
          {metrics !== null && visible.length === 0 && (
            <tr><td colSpan={2}>{needle && metrics.length > 0 ? <NoMatchesState /> : <NoEntitiesState />}</td></tr>
          )}
          {metrics !== null && paged.items.map((metric, index) => {
            const documentClass = metric.kind === 'DOCUMENT_CLASS';
            const key = `${metric.kind}:${metric.name}`;
            const expandable = !documentClass || documentClassAllowed(metric.name);
            const open = expandable && openKey === key;
            const toggle = () => setExpanded(open ? null : { key, at });
            return (
              <React.Fragment key={`${index}:${key}`}>
                <tr
                  onClick={expandable ? toggle : undefined}
                  className={`border-b border-[rgba(0,0,0,0.04)] ${
                    expandable ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.02)]' : ''
                  } ${open ? 'bg-[rgba(0,0,0,0.03)]' : ''}`}
                >
                  <td className="px-3 py-2.5">
                    <span className="flex min-w-0 items-center gap-2">
                      {expandable
                        ? <ExpandButton expanded={open} name={metric.name} onToggle={toggle} />
                        : <span aria-hidden="true" className="w-[18px] flex-shrink-0" />}
                      {documentClass
                        ? <GreyTag>{metric.name}</GreyTag>
                        : <EntityChip name={metric.name} categoryLabel={metric.categoryLabel} />}
                    </span>
                  </td>
                  <td className="px-3 py-2.5">
                    <span className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={metric.count.toLocaleString('en-US')}>
                        {abbreviate(metric.count)}
                      </span>
                      {/* Document categories are another vocabulary than the sensitive ones: neutral grey (A23). */}
                      <CategoryChip label={metric.categoryLabel} neutral={documentClass} />
                    </span>
                  </td>
                </tr>
                {open && (
                  <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                    {/* max-w-0: the nested table's width stays out of the column sizing, so it scrolls in its own box. */}
                    <td colSpan={2} className="max-w-0 px-4 py-3">
                      {documentClass
                        ? <EndpointClassFiles api={api} documentClass={metric.name} />
                        : <EndpointOccurrenceTable api={api} entityName={metric.name} showFalsePositives={showFalsePositives} />}
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
      {metrics !== null && (
        <TableFooter
          page={paged.page}
          pageSize={paged.size}
          total={paged.total}
          onPage={paged.setPage}
          onPageSize={paged.setSize}
        />
      )}
    </div>
  );
};

export default EndpointEntityView;
