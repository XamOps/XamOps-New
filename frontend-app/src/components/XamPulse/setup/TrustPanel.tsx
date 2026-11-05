import React from 'react';
import type { XpTrustPanel } from '../../../types/xampulse';
import { Card, ErrorState, SkeletonBlock } from '../ui/ui';
import { useTrust } from '../../../hooks/xampulse/useXamPulseQueries';

const List: React.FC<{ title: string; items: string[]; tone?: 'ok' | 'crit' }> = ({ title, items, tone }) => (
  <div>
    <h3 style={{ margin: '0 0 6px', fontSize: 13 }}>{title}</h3>
    <ul style={{ margin: 0, paddingLeft: 0, listStyle: 'none' }}>
      {items.map((i) => <li key={i} style={{ padding: '2px 0' }}><span aria-hidden="true" style={{ color: tone === 'crit' ? 'var(--xp-crit)' : 'var(--xp-ok)' }}>{tone === 'crit' ? '\u2715' : '\u2713'}</span> {i}</li>)}
    </ul>
  </div>
);

export const TrustBody: React.FC<{ t: XpTrustPanel; compact?: boolean }> = ({ t, compact }) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
    <List title="What XamPulse collects" items={t.collected} />
    <List title="What it never collects" items={t.neverCollected} tone="crit" />
    {!compact && (
      <div>
        <h3 style={{ margin: '0 0 6px', fontSize: 13 }}>Sensitive values are masked before storage</h3>
        <ul style={{ margin: 0, paddingLeft: 18 }}>{t.redaction.map((r) => <li key={r.rule}>{r.rule}: <span className="xp-mono">{r.example}</span></li>)}</ul>
      </div>
    )}
    <div>
      <h3 style={{ margin: '0 0 6px', fontSize: 13 }}>Where it lives</h3>
      <p style={{ margin: 0 }}>Recent data for {t.where.hotDays} days in {t.where.hotRegion || 'a region that is not configured yet'}.{t.where.archiveBucket ? ` Older logs in your own bucket ${t.where.archiveBucket} (${t.where.archiveRegion ?? 'region unknown'}).` : ''}{t.where.kms ? ` Encryption: ${t.where.kms}.` : ''}</p>
    </div>
    {!compact && <List title="Who can see it" items={t.access} />}
    {!compact && <List title="Your controls" items={t.controls} />}
    <div className="xp-banner xp-banner--ok"><strong>Connection role:</strong> {t.permissionsDiff.connectionRoleNote}<br /><strong>No remote commands:</strong> {t.permissionsDiff.noRunCommand}</div>
  </div>
);

/** Always visible next to the wizard. */
export const TrustPanel: React.FC<{ accountId: number | undefined; compact?: boolean }> = ({ accountId, compact }) => {
  const q = useTrust(accountId);
  return (
    <Card title="Your data, your rules" id="xp-trust">
      {q.isLoading && <SkeletonBlock height={220} label="Loading trust details" />}
      {q.error && <ErrorState error={q.error} compact onRetry={() => { void q.refetch(); }} />}
      {q.data && <TrustBody t={q.data} compact={compact} />}
    </Card>
  );
};
