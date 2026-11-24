import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { xampulseApi, isXpDemo } from '../../../api/xampulseApi';
import type { XpLogRow } from '../../../types/xampulse';
import { Drawer, ErrorState, SeverityBadge, SkeletonBlock } from '../ui/ui';
import { formatDateTime } from '../../../utils/xampulse/format';

interface Props {
  row: XpLogRow;
  onClose: () => void;
  /** Adds a filter, e.g. instance:i-123, to the explorer query. */
  onFilter: (field: string, value: string) => void;
  onShowPattern: (row: XpLogRow) => void;
  permalink: string;
}

export const LogDetailDrawer: React.FC<Props> = ({ row, onClose, onFilter, onShowPattern, permalink }) => {
  const [copied, setCopied] = useState(false);
  const ctxQ = useQuery({ queryKey: ['xp', isXpDemo() ? 'demo' : 'live', 'logctx', row.accountId, row.id], queryFn: () => xampulseApi.logContext(row.accountId, row.id, 20, 20), retry: false });
  const fields: [string, string][] = [
    ['Time', formatDateTime(row.ts)], ['Row ID', row.id], ['Account', String(row.accountId)], ['Host', row.instanceId], ['Service', row.service], ['Source', row.source], ['Region', row.region],
    ...(row.containerId ? ([['Container', row.containerId]] as [string, string][]) : []), ...(row.traceId ? ([['Trace id', row.traceId]] as [string, string][]) : []),
    ...Object.keys(row.attrs).filter((k) => k !== 'xp.demo').map((k): [string, string] => [`attr.${k}`, row.attrs[k]]),
  ];
  const copy = async () => {
    try { await navigator.clipboard.writeText(permalink); setCopied(true); window.setTimeout(() => setCopied(false), 1500); } catch { setCopied(false); }
  };
  return (
    <Drawer title={<div className="xp-row"><SeverityBadge severity={row.severity} /><strong>Log line</strong></div>} onClose={onClose} label="Log line details">
      <pre className="xp-code" aria-label="Raw log line" style={{ maxHeight: 220, whiteSpace: 'pre-wrap' }}>{row.body}</pre>
      <div className="xp-row">
        <button type="button" className="xp-btn" onClick={() => onShowPattern(row)}>Show only this pattern</button>
        <button type="button" className="xp-btn" onClick={() => onFilter('instance_id', row.instanceId)}>Only this host</button>
        <button type="button" className="xp-btn" onClick={() => { void copy(); }}>{copied ? 'Link copied' : 'Copy link'}</button>
      </div>
      <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '6px 16px', margin: 0 }}>
        {fields.map(([k, v]) => (<React.Fragment key={k}><dt className="xp-muted">{k}</dt><dd className="xp-mono" style={{ margin: 0, wordBreak: 'break-all' }}>{v}</dd></React.Fragment>))}
      </dl>
      <section aria-label="Surrounding lines">
        <h3 style={{ margin: '0 0 8px' }}>Around this line</h3>
        {ctxQ.isLoading && <SkeletonBlock height={140} />}
        {ctxQ.error && <ErrorState error={ctxQ.error} compact onRetry={() => { void ctxQ.refetch(); }} />}
        {ctxQ.data && (
          <div className="xp-code" style={{ maxHeight: 320 }}>
            {[...ctxQ.data.before, ctxQ.data.anchor, ...ctxQ.data.after].map((r) => (
              <div key={r.id} style={{ background: r.id === row.id ? 'color-mix(in srgb, var(--xp-accent) 22%, transparent)' : undefined, fontWeight: r.id === row.id ? 700 : 400 }}>
                <span className="xp-muted">{formatDateTime(r.ts)}</span> {r.body}
              </div>
            ))}
          </div>
        )}
      </section>
    </Drawer>
  );
};
