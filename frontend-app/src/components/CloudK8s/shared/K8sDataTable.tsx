/**
 * The CloudK8s table (docs/cloudk8s/ui-revamp-design.md D14, §11.1, §14).
 *
 * Sorting (nulls always last; header buttons with `aria-sort`), search, filter chips, pagination
 * (25/50/100, back to page 1 whenever the search or a filter changes), CSV of the filtered and
 * sorted rows, optional expandable rows (`aria-expanded`), and a `<caption>`. Pagination keeps the
 * DOM bounded for large pod lists; no table or virtualization library is used.
 *
 * Only `low` columns may hide on narrow screens, and the type allows `hideBelowPx` only there, so a
 * column holding a gated control, a status or the row action (always `essential`) never disappears.
 *
 * Dark mode: the `dark:` variants here are for a table inside a v3 `data-k8s-kit` root (Automation ›
 * Activity). Everywhere else on the cluster page the legacy dark CSS in style.css still paints the
 * table, and its `!important` rules win over these for every text, surface, button, input and cell
 * colour. Plain `<div>` borders are the one thing those rules don't set, so those dark borders use
 * the kit-scoped variant `dark:[[data-k8s-kit]_&]:`, which leaves every other page's table as it is.
 */
import { Fragment, useEffect, useId, useMemo, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Icon } from '@iconify/react';
import { downloadCsv, toCsv, type CsvCell } from '../../../utils/csvExport';

export type SortValue = number | string | null;

interface ColumnBase<T> {
  id: string;
  /** Short header text. */
  header: string;
  /** Longer explanation, shown as the header's tooltip. */
  headerTitle?: string;
  cell: (row: T) => ReactNode;
  /** Makes the column sortable; null sorts last in both directions. */
  sortValue?: (row: T) => SortValue;
  /** The CSV value; falls back to `sortValue`. Columns with neither are left out of the CSV. */
  csv?: (row: T) => CsvCell;
  align?: 'left' | 'right' | 'center';
  /** A fixed column width (a CSS length, e.g. "160px"); drives a `<colgroup>` so wide tables don't reflow. */
  width?: string;
}

/** `hideBelowPx` exists only on `low` columns (§11.1). */
type ColumnVisibility =
  | { priority?: 'essential' | 'normal'; hideBelowPx?: never }
  | { priority: 'low'; hideBelowPx?: number };

export type ColumnDef<T> = ColumnBase<T> & ColumnVisibility;

export interface FilterOption {
  value: string;
  label: string;
}

export interface FilterDef<T> {
  id: string;
  /** Names the chip group, e.g. "Type". */
  label: string;
  options: ReadonlyArray<FilterOption>;
  /** Whether a row passes the chosen option; never called for "All". */
  match: (row: T, value: string) => boolean;
  /** The option chosen first; "" (All) by default. */
  defaultValue?: string;
}

export type PageSize = 25 | 50 | 100;
export const PAGE_SIZES: readonly PageSize[] = [25, 50, 100];

export interface SortState {
  columnId: string;
  direction: 'asc' | 'desc';
}

export interface K8sDataTableProps<T> {
  rows: readonly T[];
  columns: ReadonlyArray<ColumnDef<T>>;
  rowKey: (row: T) => string;
  /** The table's accessible name, e.g. "Pods". Visually hidden; panels show their own title. */
  caption: string;
  /** Text a row is searched by; no search box without it. */
  search?: (row: T) => string;
  searchPlaceholder?: string;
  filters?: ReadonlyArray<FilterDef<T>>;
  pageSize?: PageSize;
  initialSort?: SortState;
  /** CSV file name; no export button without it. */
  exportName?: string;
  /** Where the rows come from, shown under the table, e.g. a SourceLabel. */
  source?: ReactNode;
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  /** Shown when there are no rows at all. */
  emptyState?: ReactNode;
  /** Detail shown under a row when it's expanded; rows returning null get no toggle. */
  expandRow?: (row: T) => ReactNode | null;
  /** Names a row for its expand toggle, e.g. the pod name. */
  rowLabel?: (row: T) => string;
  /**
   * Makes the whole row activatable (click + Enter/Space) with a trailing chevron — the v3 way a row
   * opens a DetailDrawer. Additive and off unless passed; independent of `expandRow`.
   */
  onRowActivate?: (row: T) => void;
  /** Bounds the table height and sticks the header while the body scrolls. Off unless passed. */
  stickyHeader?: boolean;
  /** Right-aligns numeric (sortable-by-number) columns that don't set their own `align`. Off unless passed. */
  numericAlignRight?: boolean;
}

const focusRing = 'outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';
const buttonClass = `inline-flex items-center gap-1.5 rounded-md border border-[rgba(0,0,0,0.2)] bg-white px-2.5 py-1 text-[12px] font-semibold text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] aria-disabled:cursor-default aria-disabled:opacity-50 dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.8)] dark:hover:bg-[rgba(255,255,255,0.06)] ${focusRing}`;
/** The table's outer box: a white card, a dark one inside the kit. */
const boxClass = 'rounded-lg border border-[rgba(0,0,0,0.12)] bg-white dark:bg-[#141414] dark:[[data-k8s-kit]_&]:border-[rgba(255,255,255,0.1)]';
const MUTED_TEXT = 'text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]';
const INK_TEXT = 'text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]';
const ALIGN = { left: 'text-left', right: 'text-right', center: 'text-center' } as const;

function useViewportWidth(): number {
  const [width, setWidth] = useState(() => (typeof window === 'undefined' ? 1440 : window.innerWidth));
  useEffect(() => {
    const onResize = () => setWidth(window.innerWidth);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);
  return width;
}

/** Ascending order with nulls last; numbers before strings; strings in natural order. */
export function compareSortValues(a: SortValue, b: SortValue): number {
  if (a === null && b === null) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  if (typeof a === 'number') return -1;
  if (typeof b === 'number') return 1;
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
}

const NO_FILTERS: ReadonlyArray<never> = [];

export function K8sDataTable<T>({
  rows, columns, rowKey, caption, search, searchPlaceholder, filters = NO_FILTERS, pageSize: initialPageSize = 25,
  initialSort, exportName, source, loading = false, error = null, onRetry, emptyState, expandRow, rowLabel,
  onRowActivate, stickyHeader = false, numericAlignRight = false,
}: K8sDataTableProps<T>) {
  const id = useId();
  const width = useViewportWidth();
  const [query, setQuery] = useState('');
  const [chosen, setChosen] = useState<Record<string, string>>(
    () => Object.fromEntries(filters.map((f) => [f.id, f.defaultValue ?? ''])),
  );
  const [sort, setSort] = useState<SortState | null>(initialSort ?? null);
  const [pageSize, setPageSize] = useState<PageSize>(initialPageSize);
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());

  const visible = useMemo(
    () => columns.filter((c) => !(c.priority === 'low' && c.hideBelowPx !== undefined && width < c.hideBelowPx)),
    [columns, width],
  );

  // With `numericAlignRight`, a column that sorts by a number (sampled from the first row) and sets
  // no explicit `align` right-aligns; strings and explicitly-aligned columns are untouched.
  const alignOf = (c: ColumnDef<T>): 'left' | 'right' | 'center' => {
    if (c.align) return c.align;
    if (numericAlignRight && c.sortValue && rows.length > 0 && typeof c.sortValue(rows[0]) === 'number') return 'right';
    return 'left';
  };
  const hasFixedWidths = visible.some((c) => c.width !== undefined);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows.filter((row) => {
      if (q && search && !search(row).toLowerCase().includes(q)) return false;
      return filters.every((f) => !chosen[f.id] || f.match(row, chosen[f.id]));
    });
  }, [rows, query, search, filters, chosen]);

  const sorted = useMemo(() => {
    const column = sort ? columns.find((c) => c.id === sort.columnId) : undefined;
    if (!sort || !column?.sortValue) return filtered;
    const value = column.sortValue;
    const sign = sort.direction === 'asc' ? 1 : -1;
    return filtered
      .map((row, index) => ({ row, index, v: value(row) }))
      .sort((a, b) => {
        // Nulls last whichever way the column sorts.
        if (a.v === null || b.v === null) return compareSortValues(a.v, b.v) || a.index - b.index;
        return sign * compareSortValues(a.v, b.v) || a.index - b.index;
      })
      .map((entry) => entry.row);
  }, [filtered, sort, columns]);

  const pageCount = Math.max(1, Math.ceil(sorted.length / pageSize));
  const current = Math.min(page, pageCount - 1);
  const pageRows = sorted.slice(current * pageSize, current * pageSize + pageSize);

  // Page 1 whenever what is shown changes underneath the pager.
  const resetPage = () => setPage(0);

  const toggleSort = (columnId: string) => {
    setSort((prev) => (prev?.columnId === columnId
      ? { columnId, direction: prev.direction === 'asc' ? 'desc' : 'asc' }
      : { columnId, direction: 'asc' }));
    resetPage();
  };

  const exportCsv = () => {
    const exportable = columns.filter((c) => c.csv || c.sortValue);
    const csvRows = sorted.map((row) => exportable.map((c) => (c.csv ? c.csv(row) : c.sortValue!(row))));
    downloadCsv(exportName ?? caption, toCsv(exportable.map((c) => c.header), csvRows));
  };

  const clearAll = () => {
    setQuery('');
    setChosen(Object.fromEntries(filters.map((f) => [f.id, ''])));
    resetPage();
  };

  const colSpan = visible.length + (expandRow ? 1 : 0) + (onRowActivate ? 1 : 0);
  const hasToolbar = Boolean(search || filters.length > 0 || exportName);

  if (error) {
    return (
      <div role="alert" className="flex items-start gap-3 rounded-lg border border-[rgba(0,0,0,0.88)] bg-white p-4 dark:bg-[#141414] dark:[[data-k8s-kit]_&]:border-[rgba(255,255,255,0.88)]">
        <Icon icon="mdi:alert-octagon" width={18} aria-hidden="true" className={`mt-0.5 shrink-0 ${INK_TEXT}`} />
        <div className="min-w-0 flex-1 text-[13px]">
          <p className={`font-semibold ${INK_TEXT}`}>{`Couldn't load ${caption.toLowerCase()}`}</p>
          <p className={`mt-0.5 break-words ${MUTED_TEXT}`}>{error}</p>
          {onRetry && (
            <button type="button" onClick={onRetry} className={`mt-2 ${buttonClass}`}>
              <Icon icon="mdi:refresh" width={14} aria-hidden="true" />
              Retry
            </button>
          )}
        </div>
      </div>
    );
  }

  if (loading && rows.length === 0) {
    return (
      <div aria-busy="true" className={`space-y-2 p-4 ${boxClass}`}>
        <span className="sr-only">{`Loading ${caption.toLowerCase()}…`}</span>
        {[0, 1, 2, 3].map((i) => <div key={i} className="h-6 animate-pulse rounded bg-[rgba(0,0,0,0.06)] dark:bg-[rgba(255,255,255,0.06)]" />)}
      </div>
    );
  }

  if (rows.length === 0) {
    return (
      <div className={boxClass}>
        {emptyState ?? <p className={`px-4 py-8 text-center text-[13px] ${MUTED_TEXT}`}>Nothing to show.</p>}
        {source && <div className="border-t border-[rgba(0,0,0,0.08)] px-4 py-2 dark:[[data-k8s-kit]_&]:border-[rgba(255,255,255,0.08)]">{source}</div>}
      </div>
    );
  }

  return (
    <div className={boxClass}>
      {hasToolbar && (
        <div className="flex flex-wrap items-center gap-3 border-b border-[rgba(0,0,0,0.08)] px-4 py-2.5 dark:[[data-k8s-kit]_&]:border-[rgba(255,255,255,0.08)]">
          {search && (
            <div className="relative flex min-w-[220px] flex-1 items-center">
              <Icon icon="mdi:magnify" width={16} aria-hidden="true" className={`pointer-events-none absolute left-2.5 ${MUTED_TEXT}`} />
              <input
                type="search"
                aria-label={`Search ${caption.toLowerCase()}`}
                value={query}
                onChange={(e) => { setQuery(e.target.value); resetPage(); }}
                placeholder={searchPlaceholder ?? 'Search'}
                className={`w-full rounded-md border border-[rgba(0,0,0,0.2)] bg-white py-1.5 pl-8 pr-2 text-[13px] text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.6)] dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.92)] dark:placeholder:text-[rgba(255,255,255,0.5)] ${focusRing}`}
              />
            </div>
          )}
          {filters.map((f) => (
            <div key={f.id} role="group" aria-label={f.label} className="flex flex-wrap items-center gap-1">
              <span className={`mr-1 text-[12px] font-medium ${MUTED_TEXT}`} aria-hidden="true">{f.label}</span>
              {[{ value: '', label: 'All' }, ...f.options].map((o) => {
                const pressed = (chosen[f.id] ?? '') === o.value;
                return (
                  <button
                    key={o.value || 'all'}
                    type="button"
                    aria-pressed={pressed}
                    onClick={() => { setChosen((prev) => ({ ...prev, [f.id]: o.value })); resetPage(); }}
                    className={`rounded-full border px-2.5 py-0.5 text-[12px] ${focusRing} ${pressed
                      ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.88)] font-semibold text-white dark:border-[rgba(255,255,255,0.92)] dark:bg-[rgba(255,255,255,0.92)] dark:text-black'
                      : 'border-[rgba(0,0,0,0.2)] bg-white font-medium text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.8)] dark:hover:bg-[rgba(255,255,255,0.06)]'}`}
                  >
                    {o.label}
                  </button>
                );
              })}
            </div>
          ))}
          {exportName && (
            <button type="button" onClick={exportCsv} className={`ml-auto ${buttonClass}`}>
              <Icon icon="mdi:download" width={14} aria-hidden="true" />
              Export CSV
            </button>
          )}
        </div>
      )}

      <div className={stickyHeader ? 'max-h-[70vh] overflow-auto' : 'overflow-x-auto'}>
        <table className={`w-full border-collapse text-[13px] ${hasFixedWidths ? 'table-fixed' : ''}`.trim()}>
          <caption className="sr-only">{caption}</caption>
          {hasFixedWidths && (
            <colgroup>
              {expandRow && <col style={{ width: '2.25rem' }} />}
              {visible.map((c) => <col key={c.id} style={c.width ? { width: c.width } : undefined} />)}
              {onRowActivate && <col style={{ width: '2.5rem' }} />}
            </colgroup>
          )}
          <thead className={stickyHeader ? 'sticky top-0 z-10' : undefined}>
            <tr className="border-b border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.02)] dark:border-[rgba(255,255,255,0.1)] dark:bg-[rgba(255,255,255,0.03)]">
              {expandRow && (
                <th scope="col" className="w-9 px-2 py-2"><span className="sr-only">Details</span></th>
              )}
              {visible.map((c) => {
                const active = sort?.columnId === c.id;
                const ariaSort = c.sortValue ? (active ? (sort!.direction === 'asc' ? 'ascending' : 'descending') : 'none') : undefined;
                return (
                  <th
                    key={c.id}
                    scope="col"
                    aria-sort={ariaSort}
                    title={c.headerTitle}
                    className={`whitespace-nowrap px-3 py-2 text-[12px] font-semibold ${MUTED_TEXT} ${ALIGN[alignOf(c)]}`}
                  >
                    {c.sortValue ? (
                      <button
                        type="button"
                        onClick={() => toggleSort(c.id)}
                        className={`inline-flex items-center gap-1 rounded hover:text-[rgba(0,0,0,0.88)] dark:hover:text-[rgba(255,255,255,0.92)] ${focusRing} ${active ? INK_TEXT : ''}`}
                      >
                        {c.header}
                        {active && sort!.direction === 'asc' && <Icon icon="mdi:arrow-up" width={13} aria-hidden="true" />}
                        {active && sort!.direction === 'desc' && <Icon icon="mdi:arrow-down" width={13} aria-hidden="true" />}
                        {!active && <Icon icon="mdi:swap-vertical" width={13} aria-hidden="true" className="opacity-60" />}
                      </button>
                    ) : c.header}
                  </th>
                );
              })}
              {onRowActivate && (
                <th scope="col" className="w-10 px-2 py-2"><span className="sr-only">Open</span></th>
              )}
            </tr>
          </thead>
          <tbody>
            {pageRows.length === 0 && (
              <tr>
                <td colSpan={colSpan} className={`px-4 py-8 text-center text-[13px] ${MUTED_TEXT}`}>
                  <p>No rows match the search or filters.</p>
                  <button type="button" onClick={clearAll} className={`mt-2 ${buttonClass}`}>Clear search and filters</button>
                </td>
              </tr>
            )}
            {pageRows.map((row) => {
              const key = rowKey(row);
              const detail = expandRow ? expandRow(row) : null;
              const isOpen = expanded.has(key);
              const detailId = `${id}-detail-${key}`;
              const activate = onRowActivate ? () => onRowActivate(row) : undefined;
              return (
                <Fragment key={key}>
                  <tr
                    className={`border-b border-[rgba(0,0,0,0.06)] align-top hover:bg-[rgba(0,0,0,0.02)] dark:border-[rgba(255,255,255,0.08)] dark:hover:bg-[rgba(255,255,255,0.03)] ${activate ? `cursor-pointer ${focusRing}` : ''}`.trim()}
                    {...(activate
                      ? {
                        role: 'button' as const,
                        tabIndex: 0,
                        'aria-label': rowLabel ? rowLabel(row) : key,
                        onClick: activate,
                        onKeyDown: (e: KeyboardEvent) => {
                          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); activate(); }
                        },
                      }
                      : {})}
                  >
                    {expandRow && (
                      <td className="px-2 py-2">
                        {detail !== null && (
                          <button
                            type="button"
                            aria-expanded={isOpen}
                            aria-controls={isOpen ? detailId : undefined}
                            aria-label={`${isOpen ? 'Hide' : 'Show'} details for ${rowLabel ? rowLabel(row) : key}`}
                            onClick={(e) => {
                              e.stopPropagation();
                              setExpanded((prev) => {
                                const next = new Set(prev);
                                if (next.has(key)) next.delete(key);
                                else next.add(key);
                                return next;
                              });
                            }}
                            className={`flex h-6 w-6 items-center justify-center rounded text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.06)] hover:text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.6)] dark:hover:bg-[rgba(255,255,255,0.06)] dark:hover:text-[rgba(255,255,255,0.92)] ${focusRing}`}
                          >
                            <Icon icon={isOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={16} aria-hidden="true" />
                          </button>
                        )}
                      </td>
                    )}
                    {visible.map((c) => (
                      <td key={c.id} className={`px-3 py-2 ${INK_TEXT} ${ALIGN[alignOf(c)]}`}>{c.cell(row)}</td>
                    ))}
                    {onRowActivate && (
                      <td className="px-2 py-2 text-right align-middle">
                        <Icon icon="mdi:chevron-right" width={16} aria-hidden="true" className={`inline-block ${MUTED_TEXT}`} />
                      </td>
                    )}
                  </tr>
                  {isOpen && detail !== null && (
                    <tr id={detailId} className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)] dark:border-[rgba(255,255,255,0.08)] dark:bg-[rgba(255,255,255,0.03)]">
                      <td colSpan={colSpan} className="px-4 py-3">{detail}</td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className={`flex flex-wrap items-center gap-3 border-t border-[rgba(0,0,0,0.08)] px-4 py-2 text-[12px] dark:[[data-k8s-kit]_&]:border-[rgba(255,255,255,0.08)] ${MUTED_TEXT}`}>
        <span aria-live="polite">
          {sorted.length === 0
            ? `0 of ${rows.length}`
            : `${current * pageSize + 1}–${Math.min(sorted.length, (current + 1) * pageSize)} of ${sorted.length}`}
          {sorted.length !== rows.length && ` (filtered from ${rows.length})`}
        </span>
        <span className="flex items-center gap-1.5">
          <label htmlFor={`${id}-page-size`}>Rows per page</label>
          <select
            id={`${id}-page-size`}
            value={pageSize}
            onChange={(e) => { setPageSize(Number(e.target.value) as PageSize); resetPage(); }}
            className={`rounded border border-[rgba(0,0,0,0.2)] bg-white px-1.5 py-0.5 text-[12px] text-[rgba(0,0,0,0.88)] dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.92)] ${focusRing}`}
          >
            {PAGE_SIZES.map((size) => <option key={size} value={size}>{size}</option>)}
          </select>
        </span>
        <div className="flex items-center gap-1">
          <button
            type="button"
            aria-label="Previous page"
            aria-disabled={current === 0 || undefined}
            onClick={() => { if (current > 0) setPage(current - 1); }}
            className={buttonClass}
          >
            <Icon icon="mdi:chevron-left" width={14} aria-hidden="true" />
          </button>
          <span>{`Page ${current + 1} of ${pageCount}`}</span>
          <button
            type="button"
            aria-label="Next page"
            aria-disabled={current >= pageCount - 1 || undefined}
            onClick={() => { if (current < pageCount - 1) setPage(current + 1); }}
            className={buttonClass}
          >
            <Icon icon="mdi:chevron-right" width={14} aria-hidden="true" />
          </button>
        </div>
        {source && <div className="ml-auto">{source}</div>}
      </div>
    </div>
  );
}

export default K8sDataTable;
