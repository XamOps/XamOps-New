import React, { useCallback, useEffect } from 'react';
import { useLocation, useSearchParams } from 'react-router-dom';
import { inPageState } from '../../../utils/inPageNavigation';
import type { DspmStatus } from '../../../types/dspm';
import { lineageApi, useKeyedResource } from '../../../hooks/useDspmLineage';
import DspmNotReady from '../DspmNotReady';
import { ErrorNote, LoadingNote } from '../datastores/drawer/TabParts';
import LineageCategoryPicker from './LineageCategoryPicker';
import LineageInvestigation from './LineageInvestigation';
import {
  EMPTY_QUERY, isLineageCategoryId, parseLineageQuery, toSearchParams, type LineageCategoryId, type LineageQuery,
} from './lineageQuery';

interface Props {
  status: DspmStatus;
  onRetry: () => void;
}

const noop = () => {};

/**
 * The Data Lineage page (build 4 design 6.4): step 0's category cards, then the investigation for the
 * category in the URL. The URL is the state (`lineageQuery.ts`); it is rewritten with `replace` whenever
 * it is not canonical, and stripped while the exposure routes are switched off (FR-SW-3), so a deep link
 * then shows step 0 greyed and costs no request but the categories one.
 */
const DataLineagePage: React.FC<Props> = ({ status, onRetry }) => {
  const categories = useKeyedResource(status.ready ? 'categories' : null, lineageApi.categories);
  const [params, setParams] = useSearchParams();
  const query = parseLineageQuery(params);

  // In-page navigations keep the layout's page key (utils/inPageNavigation), so a step change re-renders
  // the page in place: without it App.tsx remounts the page and drops the filters, the open More filters
  // panel and full screen on every click.
  const location = useLocation();
  const go = useCallback(
    (next: LineageQuery) => setParams(toSearchParams(next), { replace: true, state: inPageState(location) }),
    [setParams, location],
  );

  const data = categories.data;
  const off = data !== null && !data.available;
  const known = data?.available ? data.categories.filter(c => isLineageCategoryId(c.id)) : [];

  // A passive effect: the router subscribes to history in its own layout effect, which runs after this
  // component's on mount, so a navigation from a mount-time layout effect would be lost.
  useEffect(() => {
    const next = off ? EMPTY_QUERY : parseLineageQuery(params);
    if (toSearchParams(next).toString() !== params.toString()) go(next);
  }, [params, off, go]);

  // A single category opens at once (FR-S0-5).
  const only = known.length === 1 ? (known[0].id as LineageCategoryId) : null;
  useEffect(() => {
    if (only !== null && query.category === null) go({ ...EMPTY_QUERY, category: only });
  }, [only, query.category, go]);

  if (!status.ready) return <DspmNotReady status={status} onRetry={onRetry} />;
  if (categories.error) return <ErrorNote error={categories.error} onRetry={categories.reload} />;
  if (data === null) return <LoadingNote />;

  if (!data.available) {
    return <LineageCategoryPicker categories={data.categories} available={false} onChoose={noop} />;
  }

  const category = query.category === null ? undefined : known.find(c => c.id === query.category);
  if (!category) {
    return (
      <LineageCategoryPicker
        categories={known}
        available
        onChoose={id => go({ ...EMPTY_QUERY, category: id })}
      />
    );
  }

  return (
    <LineageInvestigation
      key={category.id}
      query={query}
      category={category}
      onQuery={go}
      onUnavailable={() => { categories.reload(); go(EMPTY_QUERY); }}
    />
  );
};

export default DataLineagePage;
