import React, { useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { Icon } from '@iconify/react';
import type { LineageCategory } from '../../../types/dspmLineage';
import type { KeyedResource } from '../../../hooks/useDspmLineage';
import MoreFiltersPanel from '../datastores/MoreFiltersPanel';
import { MAX_SEARCH, normalizeSearch, type DatastoreFilterState } from '../datastores/datastoreFilters';
import type { ErrorSubject } from '../datastores/datastoreErrors';
import { buildLineageLanes, visibleLanes, type LineageEventView, type LineageSubject } from '../datastores/saas/lineageModel';
import { SearchInput } from '../datastores/ui/SearchInput';
import type { LineageNodeView } from './LineageNodeCard';
import LineageTreeCanvas, { type TreeBandView, type TreeRowView } from './LineageTreeCanvas';
import { LINEAGE_FILTER_DIMENSIONS, lineageFilterLabel } from './lineageFilters';
import { PAGE_SIZE, openLevel, type LineageQuery, type OpenLevel, type RowLevel } from './lineageQuery';
import {
  LINEAGE_LOADING_TEXT, NO_EVENT_MATCH_TEXT, NO_LINEAGE_TEXT, PANEL_COPY, rowEmptyText, rowLoadingText,
} from './lineageText';
import type { TreeLevel } from './lineageTree';
import type { LineageData, LineageFilters } from './useLineageData';

/** How long the search box waits after the last keystroke. */
export const SEARCH_DEBOUNCE_MS = 300;

interface Props {
  query: LineageQuery;
  category: LineageCategory;
  data: LineageData;
  filters: LineageFilters;
  /** Drawn inside the full-screen modal: no full-screen button, a taller canvas. */
  fullscreen: boolean;
  toggleRef: RefObject<HTMLButtonElement | null>;
  onToggleFullscreen: () => void;
  onNode: (level: TreeLevel, id: string) => void;
  onPage: (level: RowLevel, direction: 1 | -1) => void;
  onSearch: (level: OpenLevel, text: string) => void;
  onFilters: (level: 'policy' | 'datastore', next: DatastoreFilterState) => void;
  onViewDetails: (event: LineageEventView, anchor: HTMLElement) => void;
}

/** The search as it is sent: normalised and cut to 200 characters. */
const searchKey = (text: string) => (normalizeSearch(text) ?? '').slice(0, MAX_SEARCH);

/** The open level's search box: local text, sent 300 ms after the last change. */
export const LevelSearch: React.FC<{ initial: string; placeholder: string; onSearch: (text: string) => void }> = ({
  initial, placeholder, onSearch,
}) => {
  const [text, setText] = useState(initial);
  const sent = useRef(searchKey(initial));
  const latest = useRef(onSearch);
  useEffect(() => { latest.current = onSearch; });
  useEffect(() => {
    const key = searchKey(text);
    if (key === sent.current) return;
    const timer = setTimeout(() => {
      sent.current = key;
      latest.current(text);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [text]);
  return <SearchInput value={text} onChange={setText} placeholder={placeholder} maxLength={MAX_SEARCH} className="w-64" />;
};

export interface RowSource<R extends { id: string | null }> {
  level: RowLevel;
  resource: KeyedResource<{ rows: R[]; total: number }>;
  /** The rows to draw when they differ from the answer's (searched files). */
  rows?: R[] | null;
  view: (row: R) => LineageNodeView;
  chosenId: string | null;
  page: number;
  q: string;
  filtered: boolean;
  subject?: ErrorSubject;
}

/** One row of the tree: its nodes, or the loading, error or empty message in their place. */
export function rowView<R extends { id: string | null }>(source: RowSource<R>): TreeRowView {
  const { level, resource, view, chosenId, page, q, filtered, subject } = source;
  const base = { level, nodes: [], chosen: null, prev: false, next: false };
  if (resource.error) {
    return { ...base, message: { kind: 'error', error: resource.error, onRetry: resource.reload, subject } };
  }
  const data = resource.data;
  if (data === null) return { ...base, message: { kind: 'loading', text: rowLoadingText(level) } };
  const rows = source.rows ?? data.rows;
  if (rows.length === 0) return { ...base, message: { kind: 'empty', text: rowEmptyText(level, q, filtered) } };
  const nodes = rows.map((row, i) => ({ key: row.id ?? `#${i}`, id: row.id, view: view(row) }));
  return {
    level,
    nodes,
    chosen: nodes.find(n => n.id !== null && n.id === chosenId)?.key ?? null,
    prev: q === '' && page > 0,
    next: q === '' && (page + 1) * PAGE_SIZE < data.total,
    message: null,
  };
}

const hasFilters = (state: DatastoreFilterState) => Object.values(state).some(s => s.values.length > 0);

/**
 * The investigation's panel (build 4 design 6.6): the open level's heading, help and search, "+ More
 * filters" on the policy and datastore levels, the full-screen button, and the tree canvas fed with the
 * rows and the lineage band.
 */
const LineagePanel: React.FC<Props> = ({
  query, category, data, filters, fullscreen, toggleRef, onToggleFullscreen, onNode, onPage, onSearch, onFilters,
  onViewDetails,
}) => {
  const level = openLevel(query) ?? 'policy';
  const copy = PANEL_COPY[level];
  const filterLevel = level === 'policy' || level === 'datastore' ? level : null;

  const options = data.options.data;
  const labelMemory = useMemo(() => {
    const memory = new Map<string, Map<string, string>>();
    for (const dimension of options?.dimensions ?? []) {
      memory.set(dimension.key, new Map(dimension.options.map(o => [o.value, o.label])));
    }
    return memory;
  }, [options]);

  const rows: TreeRowView[] = [{
    level: 'category',
    nodes: [{
      key: category.id,
      id: category.id,
      view: { kind: 'category', name: category.name, count: data.policies.data?.total ?? category.exposures ?? null },
    }],
    chosen: category.id,
    prev: false,
    next: false,
    message: null,
  }];
  rows.push(rowView({
    level: 'policy', resource: data.policies, view: policy => ({ kind: 'policy', policy }),
    chosenId: query.policy, page: query.page.policy, q: query.q.policy, filtered: hasFilters(filters.policy),
  }));
  if (query.policy !== null) {
    rows.push(rowView({
      level: 'datastore', resource: data.datastores, view: datastore => ({ kind: 'datastore', datastore }),
      chosenId: query.datastore, page: query.page.datastore, q: query.q.datastore,
      filtered: hasFilters(filters.datastore),
    }));
  }
  if (query.datastore !== null) {
    rows.push(rowView({
      level: 'file', resource: data.files, rows: data.fileRows, view: file => ({ kind: 'file', file }),
      chosenId: query.file, page: query.page.file, q: query.q.file, filtered: false, subject: 'file',
    }));
  }

  // The band (design 6.7): build 2's lanes for the chosen file, through the non-Gmail path.
  const file = data.fileRows?.find(row => row.id !== null && row.id === query.file) ?? null;
  const subject = useMemo<LineageSubject>(() => ({
    name: file?.name ?? '-', driveType: null, owner: file?.actor ?? null, lastModifiedAt: file?.modifiedAt ?? null,
  }), [file]);
  const lineage = data.lineage;
  const lanes = useMemo(
    () => (lineage.data ? buildLineageLanes(subject, lineage.data) : null),
    [subject, lineage.data],
  );
  const eventQ = query.q.event;
  const band = useMemo<TreeBandView | null>(() => {
    if (query.file === null) return null;
    if (lineage.error) {
      return { kind: 'message', message: { kind: 'error', error: lineage.error, onRetry: lineage.reload, subject: 'file' } };
    }
    if (!lineage.data || !lanes) return { kind: 'message', message: { kind: 'loading', text: LINEAGE_LOADING_TEXT } };
    if (!lineage.data.available || lanes.length === 0) {
      return { kind: 'message', message: { kind: 'empty', text: NO_LINEAGE_TEXT } };
    }
    const shown = visibleLanes(lanes, eventQ);
    if (eventQ !== '' && shown.every(lane => lane.events.length === 0)) {
      return { kind: 'message', message: { kind: 'empty', text: NO_EVENT_MATCH_TEXT } };
    }
    return { kind: 'lanes', lanes: shown };
  }, [query.file, lineage.error, lineage.reload, lineage.data, lanes, eventQ]);

  return (
    <section className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{copy.heading}</h2>
          <p className="mt-0.5 text-xs text-[rgba(0,0,0,0.5)]">{copy.help}</p>
        </div>
        {!fullscreen && (
          <button
            ref={toggleRef}
            type="button"
            aria-label="Full screen"
            title="Full screen"
            onClick={onToggleFullscreen}
            className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.55)] transition-colors hover:bg-[rgba(0,0,0,0.04)]"
          >
            <Icon icon="mdi:fullscreen" width={16} aria-hidden="true" />
          </button>
        )}
      </div>
      <div className="mt-3 flex items-center gap-2">
        <LevelSearch
          key={level}
          initial={query.q[level]}
          placeholder={copy.placeholder}
          onSearch={text => onSearch(level, text)}
        />
        {filterLevel !== null && (
          <>
            <Icon icon="mdi:filter-variant" width={16} className="text-[rgba(0,0,0,0.45)]" aria-hidden="true" />
            <MoreFiltersPanel
              key={filterLevel}
              dimensions={LINEAGE_FILTER_DIMENSIONS[filterLevel]}
              labelFor={lineageFilterLabel}
              options={options}
              optionsLoading={data.options.loading}
              optionsError={data.options.error !== null}
              filters={filters[filterLevel]}
              labelMemory={labelMemory}
              onChange={next => onFilters(filterLevel, next)}
            />
          </>
        )}
      </div>
      <div className="mt-3">
        <LineageTreeCanvas
          rows={rows}
          band={band}
          fullscreen={fullscreen}
          fitKey={level}
          onNode={onNode}
          onPage={onPage}
          onViewDetails={onViewDetails}
        />
      </div>
    </section>
  );
};

export default LineagePanel;
