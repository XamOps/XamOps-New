import React, { useEffect, useState } from 'react';
import { Bell } from 'lucide-react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { describeRule, xampulseAlertsApi, type IncidentVerb } from '../../api/xampulseAlertsApi';
import { isXpDemo } from '../../api/xampulseApi';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useChannels, useIncidents, useRules, useSilences } from '../../hooks/xampulse/useXamPulseQueries';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { Card, EmptyState, ErrorState, SkeletonBlock, friendlyError, TonePill, PageHead } from '../../components/XamPulse/ui/ui';
import { RuleEditor, blankRule } from '../../components/XamPulse/alerts/RuleEditor';
import { useIsXpAdmin } from '../../components/XamPulse/shell/useXamTabAccess';
import { xamPulseHostPath } from '../../constants/xampulseTabs';
import { formatAgo, formatDateTime } from '../../utils/xampulse/format';
import type { XpAlertRule, XpChannelInput, XpIncident } from '../../types/xampulse';

type Sub = 'rules' | 'incidents' | 'silences' | 'channels';
const SUBS: [Sub, string][] = [['incidents', 'Incidents'], ['rules', 'Rules'], ['silences', 'Silences'], ['channels', 'Channels']];
const SEV_PILL: Record<XpIncident['severity'], string> = { CRITICAL: 'crit', HIGH: 'crit', MEDIUM: 'warn', LOW: 'info' };

const useRefresh = () => {
  const client = useQueryClient();
  return () => client.invalidateQueries({ queryKey: ['xp', isXpDemo() ? 'demo' : 'live'] });
};

const IncidentsView: React.FC = () => {
  const ctx = useViewContext();
  const q = useIncidents(ctx.effectiveAccountIds);
  const refresh = useRefresh();
  const [params] = useSearchParams();
  const [open, setOpen] = useState<number | null>(() => {
    const value = Number(params.get('incident'));
    return Number.isInteger(value) && value > 0 ? value : null;
  });
  const [comments, setComments] = useState<Record<number, string>>({});
  const comment = (id: number) => comments[id] ?? '';
  const act = useMutation({
    mutationFn: ({ id, verb }: { id: number; verb: IncidentVerb }) =>
      xampulseAlertsApi.incidentAction(id, verb, verb === 'snooze' ? { snoozeUntil: new Date(Date.now() + 3_600_000).toISOString() } : verb === 'comment' ? { comment: comment(id) } : {}),
    onSuccess: (_result, variables) => { setComments((current) => ({ ...current, [variables.id]: '' })); void refresh(); },
  });
  return (
    <QueryBoundary query={q} skeleton={<SkeletonBlock height={200} />}>
      {(page) => page.items.length === 0 ? <Card><EmptyState title="No incidents" body="Related alerts are grouped into incidents. Nothing is open right now." /></Card> : (
        <div className="xp-grid">
          {act.error && <ErrorState error={act.error} compact />}
          {page.items.map((i) => (
            <Card key={i.id}>
              <div className="xp-row">
                <TonePill tone={SEV_PILL[i.severity] as 'crit' | 'warn' | 'info'} live={i.state === 'OPEN' && (i.severity === 'CRITICAL' || i.severity === 'HIGH')}>{i.severity}</TonePill>
                <span className="xp-item__title">{i.title}</span>
                <span className="xp-pill xp-pill--nodata">{i.state}</span>
                <span className="xp-spacer" />
                <span className="xp-item__meta">opened <span className="xp-mono">{formatAgo(i.openedAt)}</span></span>
              </div>
              <p className="xp-item__body">{i.summary}</p>
              <div className="xp-location">Account {i.accountId} · {i.instanceIds.length} impacted host{i.instanceIds.length === 1 ? '' : 's'} · {i.ruleIds.length} rule{i.ruleIds.length === 1 ? '' : 's'}{i.ackBy ? ` · acknowledged by ${i.ackBy}` : ''}{i.resolvedAt ? ` · resolved ${formatAgo(i.resolvedAt)}` : ''}</div>
              {i.instanceIds.length > 0 && <div className="xp-item__actions">{i.instanceIds.map((id) => <Link key={id} className="xp-btn xp-btn--sm" to={ctx.link(xamPulseHostPath(id, i.accountId))}>{id}</Link>)}</div>}
              <div className="xp-row">
                <button type="button" className="xp-btn" disabled={act.isPending || i.state !== 'OPEN'} onClick={() => act.mutate({ id: i.id, verb: 'ack' })}>Acknowledge</button>
                <button type="button" className="xp-btn" disabled={act.isPending || i.state === 'RESOLVED'} onClick={() => act.mutate({ id: i.id, verb: 'snooze' })}>Snooze 1h</button>
                <button type="button" className="xp-btn" disabled={act.isPending || i.state === 'RESOLVED'} onClick={() => act.mutate({ id: i.id, verb: 'resolve' })}>Resolve</button>
                <button type="button" className="xp-btn xp-btn--ghost" aria-expanded={open === i.id} onClick={() => setOpen(open === i.id ? null : i.id)}>Timeline</button>
              </div>
              {open === i.id && (
                <div style={{ marginTop: 12 }}>
                  <ul className="xp-timeline">{i.timeline.map((t) => <li key={`${t.ts}-${t.text}`}><span className="xp-timeline__ic" aria-hidden="true"><span className="xp-dot" /></span><span><span className="xp-eyebrow">{t.kind}</span><br />{t.text}</span><span className="xp-timeline__meta">{formatDateTime(t.ts)}</span></li>)}</ul>
                  <div className="xp-row" style={{ marginTop: 8 }}>
                    <label className="xp-sr" htmlFor={`xp-cm-${i.id}`}>Comment</label>
                    <input id={`xp-cm-${i.id}`} className="xp-input" placeholder="Add a comment" value={comment(i.id)} onChange={(e) => setComments((current) => ({ ...current, [i.id]: e.target.value }))} style={{ flex: 1 }} />
                    <button type="button" className="xp-btn" disabled={!comment(i.id).trim() || act.isPending} onClick={() => act.mutate({ id: i.id, verb: 'comment' })}>Post</button>
                  </div>
                </div>
              )}
            </Card>
          ))}
        </div>
      )}
    </QueryBoundary>
  );
};

const RulesView: React.FC<{ initialPrefill: Partial<XpAlertRule['condition']> | null; startNew: boolean }> = ({ initialPrefill, startNew }) => {
  const ctx = useViewContext();
  const rules = useRules(ctx.effectiveAccountIds);
  const refresh = useRefresh();
  const accountId = ctx.effectiveAccountIds[0] ?? 0;
  const [editing, setEditing] = useState<XpAlertRule | null>(() => (initialPrefill || startNew ? { ...blankRule(accountId), condition: { ...blankRule(accountId).condition, ...(initialPrefill ?? {}) } } : null));
  const channels = useChannels(ctx.effectiveAccountIds[0], editing !== null);
  const toggle = useMutation({ mutationFn: (r: XpAlertRule) => xampulseAlertsApi.updateRule({ ...r, enabled: !r.enabled }), onSuccess: () => { void refresh(); } });
  const del = useMutation({ mutationFn: (id: number) => xampulseAlertsApi.deleteRule(id), onSuccess: () => { void refresh(); } });
  return (
    <>
      {editing && <RuleEditor initial={editing} channels={channels.data ?? []} onCancel={() => setEditing(null)} onSaved={() => { setEditing(null); void refresh(); }} onReload={() => { void rules.refetch().then((r) => { const fresh = r.data?.find((x) => x.id === editing.id); setEditing(fresh ?? null); }); }} />}
      <QueryBoundary query={rules} skeleton={<SkeletonBlock height={200} />}>
        {(list) => list.length === 0 ? <Card><EmptyState title="No alert rules yet" body="Start with a rule for high CPU or a host that stops reporting." action={<button type="button" className="xp-btn xp-btn--primary" onClick={() => setEditing(blankRule(accountId))}>Create a rule</button>} /></Card> : (
          <div className="xp-table-wrap"><table className="xp-table">
            <caption className="xp-sr">Alert rules</caption>
            <thead><tr><th scope="col">Rule</th><th scope="col">What it does</th><th scope="col">Severity</th><th scope="col">Enabled</th><th scope="col"><span className="xp-sr">Actions</span></th></tr></thead>
            <tbody>{list.map((r) => (
              <tr key={r.id}>
                <td><span className="xp-cell-name">{r.name}</span></td>
                <td style={{ whiteSpace: 'normal', maxWidth: 520, color: 'var(--xp-text2)', padding: '10px 12px' }}>{describeRule(r)}</td>
                <td><TonePill tone={r.severity === 'CRITICAL' || r.severity === 'HIGH' ? 'crit' : r.severity === 'MEDIUM' ? 'warn' : 'info'}>{r.severity}</TonePill></td>
                <td><button type="button" role="switch" aria-checked={r.enabled} className="xp-switch" disabled={toggle.isPending} onClick={() => toggle.mutate(r)}><span className="xp-sr">{r.enabled ? 'On' : 'Off'}</span></button></td>
                <td><div className="xp-row"><button type="button" className="xp-btn xp-btn--sm" onClick={() => setEditing(r)}>Edit</button><button type="button" className="xp-btn xp-btn--sm xp-btn--danger" onClick={() => { if (r.id !== undefined && window.confirm(`Delete "${r.name}"?`)) del.mutate(r.id); }}>Delete</button></div></td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </QueryBoundary>
      {!editing && <div><button type="button" className="xp-btn xp-btn--primary" onClick={() => setEditing(blankRule(accountId))}>New rule</button></div>}
    </>
  );
};

const SilencesView: React.FC = () => {
  const ctx = useViewContext();
  const q = useSilences(ctx.effectiveAccountIds);
  const refresh = useRefresh();
  const [reason, setReason] = useState('');
  const [hours, setHours] = useState(2);
  const [hosts, setHosts] = useState('');
  const create = useMutation({
    mutationFn: () => xampulseAlertsApi.createSilence({
      accountId: ctx.effectiveAccountIds[0] ?? 0, reason: reason.trim(), startsAt: new Date().toISOString(), endsAt: new Date(Date.now() + hours * 3_600_000).toISOString(),
      matcher: hosts.trim() ? { instanceIds: hosts.split(',').map((s) => s.trim()).filter(Boolean) } : {},
    }),
    onSuccess: () => { setReason(''); setHosts(''); void refresh(); },
  });
  const del = useMutation({ mutationFn: (id: number) => xampulseAlertsApi.deleteSilence(id), onSuccess: () => { void refresh(); } });
  return (
    <>
      <Card title="Silence alerts for a while" id="xp-sil">
        <div className="xp-row">
          <div className="xp-field"><label htmlFor="s-reason">Reason</label><input id="s-reason" className="xp-input" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Planned maintenance" /></div>
          <div className="xp-field"><label htmlFor="s-hours">Hours</label><input id="s-hours" type="number" min={1} max={168} className="xp-input" value={hours} onChange={(e) => setHours(Math.max(1, Math.min(168, Number(e.target.value) || 1)))} style={{ width: 90 }} /></div>
          <div className="xp-field"><label htmlFor="s-hosts">Hosts (optional, comma separated ids)</label><input id="s-hosts" className="xp-input xp-input--mono" value={hosts} onChange={(e) => setHosts(e.target.value)} placeholder="all hosts" /></div>
          <button type="button" className="xp-btn xp-btn--primary" disabled={!reason.trim() || create.isPending} onClick={() => create.mutate()} style={{ alignSelf: 'flex-end' }}>Silence</button>
        </div>
        {create.error && <p className="xp-err" role="alert">{friendlyError(create.error)}</p>}
      </Card>
      <QueryBoundary query={q} skeleton={<SkeletonBlock height={120} />}>
        {(list) => list.length === 0 ? <p className="xp-muted">No active silences.</p> : (
          <div className="xp-table-wrap"><table className="xp-table"><caption className="xp-sr">Silences</caption>
            <thead><tr><th scope="col">Reason</th><th scope="col">Scope</th><th scope="col">Starts</th><th scope="col">Ends</th><th scope="col"><span className="xp-sr">Actions</span></th></tr></thead>
            <tbody>{list.map((s) => <tr key={s.id}><td>{s.reason}</td><td>{s.matcher.instanceIds?.length ? `${s.matcher.instanceIds.length} host(s)` : s.matcher.ruleIds?.length ? `${s.matcher.ruleIds.length} rule(s)` : s.matcher.severity?.length ? s.matcher.severity.join(', ') : 'All alerts'}<span className="xp-cell-sub">Account {s.accountId}</span></td><td className="xp-mono">{formatDateTime(s.startsAt)}</td><td className="xp-mono">{formatDateTime(s.endsAt)}</td><td><button type="button" className="xp-btn xp-btn--danger" onClick={() => { if (s.id !== undefined) del.mutate(s.id); }}>End</button></td></tr>)}</tbody>
          </table></div>
        )}
      </QueryBoundary>
    </>
  );
};

const ChannelsView: React.FC<{ admin: boolean }> = ({ admin }) => {
  const ctx = useViewContext();
  const q = useChannels(ctx.effectiveAccountIds[0], admin);
  const refresh = useRefresh();
  const [form, setForm] = useState<{ kind: XpChannelInput['kind']; name: string; value: string }>({ kind: 'SLACK', name: '', value: '' });
  const [tested, setTested] = useState<number | null>(null);
  const create = useMutation({
    mutationFn: () => xampulseAlertsApi.createChannel({ accountId: ctx.effectiveAccountIds[0] ?? 0, kind: form.kind, name: form.name.trim(), config: form.kind === 'EMAIL' ? { to: form.value.split(',').map((s) => s.trim()).filter(Boolean) } : { url: form.value.trim() } }),
    onSuccess: () => { setForm({ ...form, name: '', value: '' }); void refresh(); },
  });
  const test = useMutation({ mutationFn: (id: number) => xampulseAlertsApi.testChannel(id), onSuccess: (_d, id) => setTested(id) });
  const del = useMutation({ mutationFn: (id: number) => xampulseAlertsApi.deleteChannel(id), onSuccess: () => { void refresh(); } });
  if (!admin) return <Card><EmptyState title="Notification channels are administrator-only" body="Destinations are write-only and masked. Ask an administrator to review or change them." /></Card>;
  return (
    <>
      {!admin && <div className="xp-banner xp-banner--warn" role="note">Only administrators can add or change notification channels. You can see which ones exist.</div>}
      {isXpDemo() && <div className="xp-banner" role="note">DEMO: channels in demo mode never send real notifications.</div>}
      <QueryBoundary query={q} skeleton={<SkeletonBlock height={120} />}>
        {(list) => list.length === 0 ? <p className="xp-muted">No channels yet.</p> : (
          <div className="xp-table-wrap"><table className="xp-table"><caption className="xp-sr">Notification channels</caption>
            <thead><tr><th scope="col">Name</th><th scope="col">Type</th><th scope="col">Destination (masked)</th><th scope="col"><span className="xp-sr">Actions</span></th></tr></thead>
            <tbody>{list.map((c) => (
              <tr key={c.id}><td className="xp-cell-name">{c.name}</td><td><span className="xp-pill xp-pill--nodata">{c.kind}</span></td><td className="xp-mono">{c.summary}</td>
                <td>{admin && <div className="xp-row"><button type="button" className="xp-btn" onClick={() => test.mutate(c.id)} disabled={test.isPending}>Send test</button><button type="button" className="xp-btn xp-btn--danger" onClick={() => del.mutate(c.id)}>Delete</button>{tested === c.id && <span className="xp-pill xp-pill--ok" role="status">Test sent</span>}</div>}</td></tr>
            ))}</tbody>
          </table></div>
        )}
      </QueryBoundary>
      {admin && (
        <Card title="Add a channel" id="xp-chan">
          <div className="xp-row">
            <div className="xp-field"><label htmlFor="c-kind">Type</label><select id="c-kind" className="xp-select" value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value as XpChannelInput['kind'], value: '' })}>{(['SLACK', 'TEAMS', 'WEBHOOK', 'EMAIL'] as const).map((k) => <option key={k}>{k}</option>)}</select></div>
            <div className="xp-field"><label htmlFor="c-name">Name</label><input id="c-name" className="xp-input" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></div>
            <div className="xp-field" style={{ flex: 1 }}><label htmlFor="c-val">{form.kind === 'EMAIL' ? 'Email addresses' : 'Webhook URL (https)'}</label>
              <input id="c-val" className="xp-input xp-input--mono" type={form.kind === 'EMAIL' ? 'text' : 'password'} autoComplete="off" value={form.value} onChange={(e) => setForm({ ...form, value: e.target.value })} placeholder={form.kind === 'EMAIL' ? 'oncall@example.com' : 'https://hooks.example.com/...'} />
              <span className="xp-muted" style={{ fontSize: 12 }}>Saved addresses are write-only. After saving you will only see a masked version.</span></div>
            <button type="button" className="xp-btn xp-btn--primary" style={{ alignSelf: 'flex-end' }} disabled={!form.name.trim() || !form.value.trim() || create.isPending} onClick={() => create.mutate()}>Add</button>
          </div>
          {create.error && <p className="xp-err" role="alert">{friendlyError(create.error)}</p>}
        </Card>
      )}
    </>
  );
};

const AlertsTab: React.FC = () => {
  const [params, setParams] = useSearchParams();
  const admin = useIsXpAdmin();
  const location = useLocation();
  const navigate = useNavigate();
  const state = (location.state ?? null) as { prefill?: { metric?: string; agg?: XpAlertRule['condition']['agg'] }; newRule?: boolean } | null;
  const requested = params.get('view');
  const hasRuleIntent = !!(state?.prefill || state?.newRule);
  const sub: Sub = hasRuleIntent ? 'rules' : requested === 'rules' || requested === 'silences' || requested === 'channels' ? requested : 'incidents';
  useEffect(() => {
    if (!hasRuleIntent) return;
    const next = new URLSearchParams(params);
    next.set('view', 'rules');
    navigate({ pathname: location.pathname, search: `?${next.toString()}`, hash: location.hash }, { replace: true, state: null });
  }, [hasRuleIntent, location.hash, location.pathname, navigate, params]);
  const selectSub = (next: Sub) => setParams((current) => { const copy = new URLSearchParams(current); copy.set('view', next); if (next !== 'incidents') copy.delete('incident'); return copy; }, { replace: true });
  return (
    <>
      <PageHead icon={Bell} title="Alerts" sub="Incidents are the operational queue; rules, silences and channels control what happens next."
        extra={<div className="xp-seg" role="group" aria-label="Alerts view">{SUBS.map(([id, label]) => <button key={id} type="button" aria-pressed={sub === id} onClick={() => selectSub(id)}>{label}</button>)}</div>} />
      {isXpDemo() && sub !== 'channels' && <div className="xp-banner" role="note">DEMO: alerts created in demo mode never send external notifications.</div>}
      {sub === 'rules' && <RulesView initialPrefill={state?.prefill ?? null} startNew={!!state?.newRule} />}
      {sub === 'incidents' && <IncidentsView />}
      {sub === 'silences' && <SilencesView />}
      {sub === 'channels' && <ChannelsView admin={admin} />}
    </>
  );
};

export default AlertsTab;
