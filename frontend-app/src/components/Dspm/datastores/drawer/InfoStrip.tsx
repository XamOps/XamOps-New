import React from 'react';

export interface InfoStripItem {
  label: string;
  value: React.ReactNode;
}

/**
 * The label-over-value card under a drawer's title (design 6.2), styled like the datastore drawer's info
 * card: a small grey label over a semibold value. A text or number value is truncated with its full text
 * in a `title`; a node value (an icon and a label) brings its own truncation and title.
 */
export const InfoStrip: React.FC<{ items: ReadonlyArray<InfoStripItem> }> = ({ items }) => (
  <div className="flex flex-wrap items-center gap-x-8 gap-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.015)] px-4 py-3">
    {items.map(item => (
      <div key={item.label} className="min-w-0">
        <div className="text-[10px] text-[rgba(0,0,0,0.45)]">{item.label}</div>
        <div className="mt-1 flex min-w-0 max-w-[16rem] items-center gap-1.5 text-xs font-semibold text-[rgba(0,0,0,0.8)]">
          {typeof item.value === 'string' || typeof item.value === 'number'
            ? <span className="truncate" title={String(item.value)}>{item.value}</span>
            : item.value}
        </div>
      </div>
    ))}
  </div>
);
