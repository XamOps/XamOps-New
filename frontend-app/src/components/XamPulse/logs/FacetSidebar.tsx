import React from 'react';
import type { XpLogFacetsResponse } from '../../../types/xampulse';
import { formatCount } from '../../../utils/xampulse/format';
import { SkeletonBlock } from '../ui/ui';

const TITLES: Record<string, string> = { severity: 'Severity', service: 'Service', instance_id: 'Host', region: 'Region', source: 'Source' };

export const FacetSidebar: React.FC<{ data: XpLogFacetsResponse | undefined; loading: boolean; onInclude: (field: string, value: string) => void; onExclude: (field: string, value: string) => void }> = ({ data, loading, onInclude, onExclude }) => {
  if (loading && !data) return <SkeletonBlock height={320} label="Loading facets" />;
  return (
    <aside aria-label="Facets">
      {(data?.facets ?? []).map((f) => {
        const top = Math.max(1, ...f.values.map((v) => v.count));
        return (
        <section key={f.field} className="xp-facet-group" aria-label={TITLES[f.field] ?? f.field}>
          <h3>{TITLES[f.field] ?? f.field}</h3>
          {f.values.length === 0 ? <span className="xp-muted" style={{ fontSize: 12, padding: '0 6px' }}>No values</span> : (
            <ul className="xp-facet" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
              {f.values.map((v) => (
                <li key={v.value} className="xp-row" style={{ gap: 2, flexWrap: 'nowrap' }}>
                  <button type="button" onClick={() => onInclude(f.field, v.value)} title={`Only show ${f.field}:${v.value}`} aria-label={`Include ${TITLES[f.field] ?? f.field} ${v.value}, ${v.count} lines`}>
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{v.value}</span><span className="xp-facet__count">{formatCount(v.count)}</span>
                    <span className="xp-facet__fill" aria-hidden="true" style={{ width: `${(v.count / top) * 100}%` }} />
                  </button>
                  <button type="button" className="xp-facet__x" style={{ width: 22, flex: 'none', justifyContent: 'center', color: 'var(--xp-muted)' }} onClick={() => onExclude(f.field, v.value)} aria-label={`Exclude ${TITLES[f.field] ?? f.field} ${v.value}`} title="Exclude">{'\u2212'}</button>
                </li>
              ))}
            </ul>
          )}
        </section>
        );
      })}
      {data?.approx && <p className="xp-muted" style={{ fontSize: 12, margin: 0 }}>Counts are approximate for this range.</p>}
    </aside>
  );
};
