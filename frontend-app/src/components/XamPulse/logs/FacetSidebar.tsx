import React from 'react';
import type { XpLogFacetsResponse } from '../../../types/xampulse';
import { formatCount } from '../../../utils/xampulse/format';
import { Card, SkeletonBlock } from '../ui/ui';

const TITLES: Record<string, string> = { severity: 'Severity', service: 'Service', instance_id: 'Host', source: 'Source' };

export const FacetSidebar: React.FC<{ data: XpLogFacetsResponse | undefined; loading: boolean; onInclude: (field: string, value: string) => void; onExclude: (field: string, value: string) => void }> = ({ data, loading, onInclude, onExclude }) => {
  if (loading && !data) return <SkeletonBlock height={320} label="Loading facets" />;
  return (
    <aside aria-label="Facets" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {(data?.facets ?? []).map((f) => (
        <Card key={f.field} title={TITLES[f.field] ?? f.field}>
          {f.values.length === 0 ? <span className="xp-muted">No values</span> : (
            <ul className="xp-facet" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
              {f.values.map((v) => (
                <li key={v.value} className="xp-row" style={{ gap: 2, flexWrap: 'nowrap' }}>
                  <button type="button" onClick={() => onInclude(f.field, v.value)} title={`Only show ${f.field}:${v.value}`} aria-label={`Include ${TITLES[f.field] ?? f.field} ${v.value}, ${v.count} lines`}>
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{v.value}</span><span className="xp-muted">{formatCount(v.count)}</span>
                  </button>
                  <button type="button" style={{ width: 24, flex: 'none', color: 'var(--xp-muted)' }} onClick={() => onExclude(f.field, v.value)} aria-label={`Exclude ${TITLES[f.field] ?? f.field} ${v.value}`} title="Exclude">{'\u2212'}</button>
                </li>
              ))}
            </ul>
          )}
        </Card>
      ))}
      {data?.approx && <p className="xp-muted" style={{ fontSize: 12, margin: 0 }}>Counts are approximate for this range.</p>}
    </aside>
  );
};
