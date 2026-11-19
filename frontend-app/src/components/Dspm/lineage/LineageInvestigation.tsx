import React from 'react';
import { Icon } from '@iconify/react';
import type { LineageCategory } from '../../../types/dspmLineage';
import { EMPTY_QUERY, type LineageQuery } from './lineageQuery';
import { NEW_INVESTIGATION, PAGE_SUBTITLE, PAGE_TITLE } from './lineageText';

interface Props {
  query: LineageQuery;
  /** The chosen category, as step 0 listed it. */
  category: LineageCategory;
  /** Writes the next query to the URL. */
  onQuery: (query: LineageQuery) => void;
  /** A row answered `available: false` (the switch was turned off under the open page). */
  onUnavailable: () => void;
}

/**
 * Steps 1 to 4 (build 4 design 6.5): the header with "New Investigation", which goes back to step 0 (D1).
 * The panel and the tree canvas follow in the next commit.
 */
const LineageInvestigation: React.FC<Props> = ({ onQuery }) => (
  <div>
    <header className="flex items-start justify-between gap-4">
      <div>
        <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">{PAGE_TITLE}</h1>
        <p className="mt-0.5 text-sm text-[rgba(0,0,0,0.5)]">{PAGE_SUBTITLE}</p>
      </div>
      <button
        type="button"
        onClick={() => onQuery(EMPTY_QUERY)}
        className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.75)] transition-colors hover:bg-[rgba(0,0,0,0.03)]"
      >
        <Icon icon="mdi:plus-circle-outline" width={14} aria-hidden="true" />
        {NEW_INVESTIGATION}
      </button>
    </header>
  </div>
);

export default LineageInvestigation;
