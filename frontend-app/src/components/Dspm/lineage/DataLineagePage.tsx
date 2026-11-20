import React, { useCallback, useEffect } from 'react';
import { useLocation, useSearchParams } from 'react-router-dom';
import { inPageState } from '../../../utils/inPageNavigation';
import type { DspmStatus } from '../../../types/dspm';
import { lineageApi, useKeyedResource } from '../../../hooks/useDspmLineage';
import DspmNotReady from '../DspmNotReady';
import { ErrorNote, LoadingNote } from '../datastores/drawer/TabParts';
import LineageCategoryPicker from './LineageCategoryPicker';
import LineageInvestigation from './LineageInvestigation';
import PublicLineageInvestigation from './PublicLineageInvestigation';
import {
  EMPTY_QUERY, isLineageCategoryId, parseLineageQuery, toSearchParams, type LineageCategoryId, type LineageQuery,
} from './lineageQuery';

interface Props {
  status: DspmStatus;
  onRetry: () => void;
}

/**
 * The Data Lineage page (build 4 design 6.4, build 7 design 6.2). While the exposure routes are switched
 * on: step 0's category cards, then the investigation for the category in the URL, whose URL state
 * (`lineageQuery.ts`) is rewritten with `replace` whenever it is not canonical. While they are switched
 * off: the public path (`PublicLineageInvestigation`), which opens on the datastore step under the project
 * root and owns its own URL state (`publicLineageQuery.ts`); build 4's parser leaves the URL alone then
 * and while the categories load, so a public deep link survives.
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
  const known = data?.available ? data.categories.filter(c => isLineageCategoryId(c.id)) : [];

  // A passive effect: the router subscribes to history in its own layout effect, which runs after this
  // component's on mount, so a navigation from a mount-time layout effect would be lost.
  useEffect(() => {
    if (data === null || !data.available) return;
    const next = parseLineageQuery(params);
    if (toSearchParams(next).toString() !== params.toString()) go(next);
  }, [params, data, go]);

  // A single category opens at once (FR-S0-5).
  const only = known.length === 1 ? (known[0].id as LineageCategoryId) : null;
  useEffect(() => {
    if (only !== null && query.category === null) go({ ...EMPTY_QUERY, category: only });
  }, [only, query.category, go]);

  if (!status.ready) return <DspmNotReady status={status} onRetry={onRetry} />;
  if (categories.error) return <ErrorNote error={categories.error} onRetry={categories.reload} />;
  if (data === null) return <LoadingNote />;

  if (!data.available) return <PublicLineageInvestigation projectName={status.projectName} />;

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
