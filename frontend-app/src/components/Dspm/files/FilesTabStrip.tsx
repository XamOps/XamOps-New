import React from 'react';
import { FILES_TABS, type FilesTabSlug } from '../../../constants/xamlensTabs';

interface Props {
  active: FilesTabSlug;
  onSelect: (slug: FilesTabSlug) => void;
}

/** SaaS · Endpoint: the Datastores tab strip's markup without counts. */
const FilesTabStrip: React.FC<Props> = ({ active, onSelect }) => (
  <div role="tablist" aria-label="File sources" className="flex flex-wrap gap-1 border-b border-[rgba(0,0,0,0.08)]">
    {FILES_TABS.map(tab => {
      const selected = tab.slug === active;
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
          {tab.label}
        </button>
      );
    })}
  </div>
);

export default FilesTabStrip;
