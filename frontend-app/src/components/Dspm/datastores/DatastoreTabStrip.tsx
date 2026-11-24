import React from 'react';
import { DATASTORE_TABS, type DatastoreTabSlug } from '../../../constants/xamlensTabs';

interface Props {
  active: DatastoreTabSlug;
  /** From the latest KPI response, whichever tab it was for; null until the first one arrives. */
  tabCounts: Record<string, number> | null;
  onSelect: (slug: DatastoreTabSlug) => void;
}

/** All · Cloud · SaaS · Endpoint · On-Prem, each with its asset count ("–" until it is known). */
const DatastoreTabStrip: React.FC<Props> = ({ active, tabCounts, onSelect }) => (
  <div role="tablist" aria-label="Datastore categories" className="flex flex-wrap gap-1 border-b border-[rgba(0,0,0,0.08)]">
    {DATASTORE_TABS.map(tab => {
      const selected = tab.slug === active;
      const count = tabCounts?.[tab.apiTab];
      return (
        <button
          key={tab.slug}
          type="button"
          role="tab"
          aria-selected={selected}
          onClick={() => { if (!selected) onSelect(tab.slug); }}
          className={`-mb-px inline-flex items-center gap-1.5 border-b-2 px-3 py-2 text-xs font-semibold transition-colors ${
            selected
              ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
              : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.75)]'
          }`}
        >
          {tab.label}{' '}
          <span className="rounded-full bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[10px] tabular-nums text-[rgba(0,0,0,0.55)]">
            {count === undefined ? '–' : count.toLocaleString('en-US')}
          </span>
        </button>
      );
    })}
  </div>
);

export default DatastoreTabStrip;
