import React, { useState } from 'react';
import { Settings2 } from 'lucide-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { isXpDemo } from '../../api/xampulseApi';
import { xampulseSetupApi } from '../../api/xampulseSetupApi';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useAudit, useInstanceCandidates, useSetupOverview, useSources, useUsage, useXpAccounts, useXpStatus } from '../../hooks/xampulse/useXamPulseQueries';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { Card, EmptyState, ErrorState, KpiTile, SkeletonBlock, StatusPill, friendlyError, PageHead } from '../../components/XamPulse/ui/ui';
import { OnboardingWizard, SnippetBlock } from '../../components/XamPulse/setup/OnboardingWizard';
import { TrustPanel } from '../../components/XamPulse/setup/TrustPanel';
import { DisconnectDialog } from '../../components/XamPulse/setup/DisconnectDialog';
import { useIsXpAdmin } from '../../components/XamPulse/shell/useXamTabAccess';
import { UsagePanel } from './InsightsTab';
import { formatAgo, formatDateTime, formatPct } from '../../utils/xampulse/format';
import type { XpCollectionLevel, XpLogSource, XpSetupOverview, XpSetupPlanResponse } from '../../types/xampulse';

type Sub = 'connect' | 'sources' | 'agents' | 'storage' | 'trust' | 'usage' | 'audit';
const SUBS: [Sub, string][] = [['connect', 'Connect'], ['sources', 'Sources'], ['agents', 'Agents'], ['storage', 'Storage'], ['trust', 'Trust'], ['usage', 'Usage'], ['audit', 'Audit']];
const AGENT_PILL = { ACTIVE: 'ok', PENDING: 'info', STALE: 'warn', REVOKED: 'crit', NONE: 'nodata' } as const;

const ConnectSummary: React.FC<{ o: XpSetupOverview; onChange: () => void; onDisconnect: () => void; admin: boolean }> = ({ o, onChange, onDisconnect, admin }) => (
  <>
    <div className="xp-grid xp-grid--kpi">
      <KpiTile label="Account state" value={<StatusPill health={o.state === 'CONNECTED' ? 'OK' : o.state === 'DEGRADED' ? 'WARN' : 'NO_DATA'} label={o.state.replaceAll('_', ' ').toLowerCase()} />} hint={`${o.homeRegion ? `Home ${o.homeRegion} · ` : ''}last data ${formatAgo(o.lastDataAt)}`} />
      <KpiTile label="Discovered" value={o.instances.total} hint="EC2 instances" />
      <KpiTile label="SSM managed" value={o.instances.ssmManaged} hint="Eligible for managed setup" />
      <KpiTile label="In scope" value={o.instances.inScope} hint="Selected by targeting" />
      <KpiTile label="Enrolled" value={o.instances.enrolled} hint="Agent installed" />
      <KpiTile label="Reporting" status={o.instances.inScope === 0 ? 'NO_DATA' : o.instances.reporting < o.instances.inScope ? 'WARN' : 'OK'} value={o.instances.reporting} hint={o.instances.inScope === 0 ? 'No in-scope instances to measure' : `${formatPct(o.coveragePct)} coverage · ${o.instances.notCovered} not covered`} />
    </div>
    <Card title="Regions" id="xp-regions">
      <div className="xp-table-wrap"><table className="xp-table"><caption className="xp-sr">Regions</caption>
        <thead><tr><th scope="col">Region</th><th scope="col">Stack</th><th scope="col">Targeting</th><th scope="col" className="xp-num">Covered</th><th scope="col" className="xp-num">Reporting</th></tr></thead>
        <tbody>{o.regions.map((r) => <tr key={r.region}><td>{r.region}</td><td><StatusPill health={r.stackState === 'OK' ? 'OK' : r.stackState === 'FAILED' ? 'CRIT' : r.stackState === 'DEPLOYING' ? 'WARN' : 'NO_DATA'} label={r.stackState.toLowerCase()} /></td><td>{r.targetMode.replace('_', ' ').toLowerCase()}</td><td className="xp-num">{r.covered} / {r.candidateInstances}</td><td className="xp-num">{r.reporting}</td></tr>)}</tbody>
      </table></div>
    </Card>
    <div className="xp-row">
      <button type="button" className="xp-btn" onClick={onChange}>Change what is monitored</button>
      <button type="button" className="xp-btn xp-btn--danger" disabled={!admin} onClick={onDisconnect} title={admin ? undefined : 'Only administrators can disconnect'}>Disconnect</button>
    </div>
  </>
);

const SourcesView: React.FC<{ accountId: number; admin: boolean }> = ({ accountId, admin }) => {
  const q = useSources(accountId, admin);
  const client = useQueryClient();
  const [draft, setDraft] = useState<XpLogSource[] | null>(null);
  const [path, setPath] = useState('');
  const save = useMutation({ mutationFn: (s: XpLogSource[]) => xampulseSetupApi.saveSources(accountId, s), onSuccess: () => { setDraft(null); void client.invalidateQueries({ queryKey: ['xp'] }); } });
  if (!admin) return <Card><EmptyState title="Log source configuration is administrator-only" body="Only administrators can read or change source paths and redaction rules." /></Card>;
  return (
    <QueryBoundary query={q} skeleton={<SkeletonBlock height={200} />}>
      {(sources) => {
        const list = draft ?? sources;
        return (
          <Card title="Log sources" id="xp-src" actions={draft && <button type="button" className="xp-btn xp-btn--primary" disabled={save.isPending} onClick={() => save.mutate(draft)}>Save changes</button>}>
            <p className="xp-muted" style={{ marginTop: 0 }}>Only these paths are read. Sensitive values such as tokens and card numbers are masked before storage.</p>
            {list.length === 0 ? <EmptyState title="No sources" body="Add a path to start collecting logs." /> : (
              <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 8 }}>
                {list.map((s, i) => (
                  <li key={`${s.kind}-${i}`} className="xp-lane xp-row">
                    <input type="checkbox" aria-label={`Enable ${s.kind} source ${s.paths.join(', ')}`} checked={s.enabled} disabled={!admin} onChange={(e) => setDraft(list.map((x, j) => (j === i ? { ...x, enabled: e.target.checked } : x)))} />
                    <strong>{s.kind}</strong><span className="xp-mono">{s.paths.join(', ')}</span>{s.parser && <span className="xp-pill xp-pill--info">{s.parser}</span>}{s.redaction && <span className="xp-status-line">{s.redaction.length} redaction rule{s.redaction.length === 1 ? '' : 's'}</span>}
                  </li>
                ))}
              </ul>
            )}
            {admin && (
              <div className="xp-row" style={{ marginTop: 12 }}>
                <label className="xp-sr" htmlFor="src-path">Log path</label>
                <input id="src-path" className="xp-input xp-input--mono" placeholder="/var/log/myapp/*.log" value={path} onChange={(e) => setPath(e.target.value)} style={{ minWidth: 280 }} />
                <button type="button" className="xp-btn" disabled={!/^\/[\w./*-]+$/.test(path)} onClick={() => { setDraft([...list, { kind: 'CUSTOM', paths: [path], enabled: true }]); setPath(''); }}>Add path</button>
              </div>
            )}
            {!admin && <p className="xp-muted">Only administrators can change sources.</p>}
            {save.error && <p className="xp-err" role="alert">{friendlyError(save.error)}</p>}
          </Card>
        );
      }}
    </QueryBoundary>
  );
};

const AgentsView: React.FC<{ accountId: number; admin: boolean }> = ({ accountId, admin }) => {
  const q = useInstanceCandidates(accountId);
  const client = useQueryClient();
  const [term, setTerm] = useState('');
  const level = useMutation({
    mutationFn: ({ id, lvl }: { id: string; lvl: XpCollectionLevel }) => xampulseSetupApi.setCollection(accountId, id, { enabled: true, level: lvl }),
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['xp'] }); },
  });
  return (
    <QueryBoundary query={q} skeleton={<SkeletonBlock height={240} />}>
      {(page) => {
        const items = page.items.filter((item) => !term || `${item.name} ${item.instanceId} ${item.region} ${item.platform ?? ''}`.toLowerCase().includes(term.toLowerCase()));
        return <><div className="xp-row" style={{ marginBottom: 10 }}><label className="xp-sr" htmlFor="xp-agent-search">Search agents</label><input id="xp-agent-search" className="xp-input" value={term} onChange={(event) => setTerm(event.target.value)} placeholder="Search instance, region or platform" /><span className="xp-status-line">{items.length} of {page.total} shown</span></div>
        <div className="xp-table-wrap"><table className="xp-table"><caption className="xp-sr">Agents by instance</caption>
          <thead><tr><th scope="col">Instance</th><th scope="col">Scope and eligibility</th><th scope="col">Agent</th><th scope="col">Version</th><th scope="col">Last seen</th><th scope="col">Collection level</th></tr></thead>
          <tbody>{items.map((i) => (
            <tr key={i.instanceId}>
              <td><span className="xp-cell-name">{i.name}</span><span className="xp-cell-sub">{i.instanceId} · {i.region} · {i.platform ?? 'platform unknown'}</span></td>
              <td>{i.inScope ? 'In scope' : 'Out of scope'}<span className="xp-cell-sub">{i.ssmManaged === true ? 'SSM managed' : i.ssmManaged === false ? 'Not SSM managed' : 'SSM status unknown'}</span></td>
              <td><span className={`xp-pill xp-pill--${AGENT_PILL[i.agent.status]}`}>{i.agent.status.toLowerCase()}</span></td>
              <td>{i.agent.version ?? '\u2014'}</td><td>{formatAgo(i.agent.lastSeenAt)}</td>
              <td>
                <label className="xp-sr" htmlFor={`lvl-${i.instanceId}`}>Collection level for {i.name}</label>
                <select id={`lvl-${i.instanceId}`} className="xp-select" value={i.collectionLevel} disabled={!admin || level.isPending} onChange={(e) => level.mutate({ id: i.instanceId, lvl: e.target.value as XpCollectionLevel })}>
                  <option value="T0">T0 · health only</option><option value="T1">T1 · metrics and logs</option><option value="T2">T2 · adds processes</option>
                </select>
              </td>
            </tr>
          ))}</tbody>
        </table></div></>;
      }}
    </QueryBoundary>
  );
};

const StorageView: React.FC<{ o: XpSetupOverview; plan: XpSetupPlanResponse | null }> = ({ o, plan }) => (
  <>
    <Card title="Where your logs live" id="xp-store">
      <div className="xp-grid xp-grid--2">
        <div className="xp-lane"><strong>Recent (fast search)</strong><p className="xp-muted" style={{ margin: '6px 0 0' }}>Stored by XamPulse so searches are quick. The window is set in your retention choice and shown on the Trust tab.</p></div>
        <div className="xp-lane"><strong>Archive (your S3 bucket)</strong><p className="xp-muted" style={{ margin: '6px 0 0' }}>{o.bucket ? <>Older logs are written to <span className="xp-mono">{o.bucket.name}</span> in {o.bucket.region}. You own the data and the AWS bill for it.</> : 'No archive bucket is configured yet. Finish setup on the Connect tab.'}</p></div>
      </div>
      <div className={`xp-banner xp-banner--${o.archive?.state === 'OK' ? 'ok' : o.archive?.state === 'FAILING' ? 'crit' : 'info'}`} style={{ marginTop: 12 }}>
        <strong>Archive writer: {o.archive?.state?.toLowerCase() ?? 'status unavailable'}.</strong>{' '}
        {o.archive?.state === 'OK' ? `Last successful watermark ${formatAgo(o.archive.watermark)}.` : o.archive?.state === 'FAILING' ? `${o.archive.failures} failure${o.archive.failures === 1 ? '' : 's'}${o.archive.lastError ? ` · ${o.archive.lastError}` : ''}${o.archive.watermark ? ` · last success ${formatAgo(o.archive.watermark)}` : ''}.` : 'Archive storage is off or not configured.'}
      </div>
    </Card>
    {o.bucket?.mode === 'EXISTING' && (
      <Card title="Managed by you" id="xp-managed">
        <p className="xp-muted" style={{ marginTop: 0 }}>Because this is your own bucket, XamPulse does not change its policy or lifecycle. Keep these in place:</p>
        {plan?.preview.customerSnippets ? <SnippetBlock title="Lifecycle rules" text={plan.preview.customerSnippets.lifecycleJson} /> : <p className="xp-muted">Run the Connect step again to see the lifecycle JSON for your retention choice.</p>}
      </Card>
    )}
  </>
);

const AuditView: React.FC<{ accountId: number; admin: boolean }> = ({ accountId, admin }) => {
  const [pageNumber, setPageNumber] = useState(0);
  const q = useAudit(accountId, admin, pageNumber);
  if (!admin) return <Card><p className="xp-muted" style={{ margin: 0 }}>The audit log is visible to administrators.</p></Card>;
  return (
    <QueryBoundary query={q} skeleton={<SkeletonBlock height={200} />}>
      {(page) => page.items.length === 0 ? <Card><EmptyState title="No audit entries yet" /></Card> : <>
        <div className="xp-table-wrap"><table className="xp-table"><caption className="xp-sr">Audit log</caption>
          <thead><tr><th scope="col">When</th><th scope="col">Action</th><th scope="col">Target</th><th scope="col">User</th><th scope="col">Account</th><th scope="col">IP</th></tr></thead>
          <tbody>{page.items.map((a) => <tr key={`${a.ts}-${a.action}`}><td>{formatDateTime(a.ts)}</td><td>{a.action}</td><td>{a.target ?? '—'}</td><td>{a.userId}</td><td>{a.accountId ?? '—'}</td><td className="xp-mono">{a.ip ?? '—'}</td></tr>)}</tbody>
        </table></div>
        <nav className="xp-pagination" aria-label="Audit pages"><button type="button" className="xp-btn" disabled={page.page === 0} onClick={() => setPageNumber(page.page - 1)}>Previous</button><span>Page {page.page + 1} of {Math.max(1, Math.ceil(page.total / page.size))} · {page.total} entries</span><button type="button" className="xp-btn" disabled={(page.page + 1) * page.size >= page.total} onClick={() => setPageNumber(page.page + 1)}>Next</button></nav>
      </>}
    </QueryBoundary>
  );
};

const SetupTab: React.FC = () => {
  const ctx = useViewContext();
  const admin = useIsXpAdmin();
  const accounts = useXpAccounts();
  const account = accounts.data?.find((a) => a.cloudAccountId === ctx.effectiveAccountIds[0]);
  const setup = useSetupOverview(account?.cloudAccountId);
  const client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const requested = params.get('view');
  const sub: Sub = SUBS.some(([id]) => id === requested) ? requested as Sub : 'connect';
  const selectSub = (next: Sub) => setParams((current) => {
    const copy = new URLSearchParams(current);
    copy.set('view', next);
    return copy;
  }, { replace: true });
  const [editing, setEditing] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [plan, setPlan] = useState<XpSetupPlanResponse | null>(null);
  const status = useXpStatus();
  const seed = useMutation({ mutationFn: (id: number) => xampulseSetupApi.seedDemo(id), onSuccess: () => { void client.invalidateQueries({ queryKey: ['xp'] }); } });
  const usage = useUsage(sub === 'usage' ? account?.cloudAccountId : undefined);
  if (!account) return <EmptyState title="Pick an account" body="Choose an AWS account in the bar above." />;
  const needsWizard = editing || (setup.data !== undefined && (setup.data.state === 'NOT_SET_UP' || setup.data.state === 'PLANNED'));

  return (
    <>
      <PageHead icon={Settings2} title="Setup & Storage" sub="Connect AWS accounts, choose what is collected, and decide where logs live and for how long."
        extra={<div className="xp-seg" role="group" aria-label="Setup sections">{SUBS.map(([id, label]) => <button key={id} type="button" aria-pressed={sub === id} onClick={() => selectSub(id)}>{label}</button>)}</div>} />
      {isXpDemo() && <div className="xp-banner" role="note">DEMO: this account shows generated sample data. Deploy links do not create anything.</div>}
      {sub === 'connect' && needsWizard && admin && status.data?.demoAvailable && !isXpDemo() && (
        <div className="xp-banner" role="note">
          <strong>Just exploring?</strong> Load clearly labelled sample data into this account to see XamPulse with six demo hosts. Remove it any time.{' '}
          <button type="button" className="xp-btn" disabled={seed.isPending} onClick={() => seed.mutate(account.cloudAccountId)}>{seed.isPending ? 'Loading' : 'Load demo data'}</button>
          {seed.error && <span className="xp-err" role="alert"> {friendlyError(seed.error)}</span>}
        </div>
      )}
      {sub === 'connect' && (setup.isLoading ? <SkeletonBlock height={300} /> : setup.error ? <ErrorState error={setup.error} onRetry={() => { void setup.refetch(); }} /> : needsWizard
        ? <OnboardingWizard account={account} onPlanned={setPlan} onConnected={() => { void client.invalidateQueries({ queryKey: ['xp'] }); }} />
        : setup.data && <ConnectSummary o={setup.data} admin={admin} onChange={() => setEditing(true)} onDisconnect={() => setDisconnecting(true)} />)}
      {sub === 'sources' && <SourcesView accountId={account.cloudAccountId} admin={admin} />}
      {sub === 'agents' && <AgentsView accountId={account.cloudAccountId} admin={admin} />}
      {sub === 'storage' && (setup.data ? <StorageView o={setup.data} plan={plan} /> : <SkeletonBlock height={200} />)}
      {sub === 'trust' && <TrustPanel accountId={account.cloudAccountId} />}
      {sub === 'usage' && <Card title="Usage" id="xp-use"><QueryBoundary query={usage} skeleton={<SkeletonBlock height={200} />}>{(u) => <UsagePanel u={u} />}</QueryBoundary></Card>}
      {sub === 'audit' && <AuditView accountId={account.cloudAccountId} admin={admin} />}
      {disconnecting && <DisconnectDialog accountId={account.cloudAccountId} onClose={() => setDisconnecting(false)} onDone={() => { void client.invalidateQueries({ queryKey: ['xp'] }); }} />}
    </>
  );
};

export default SetupTab;
