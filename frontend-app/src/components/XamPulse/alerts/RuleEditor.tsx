import React, { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { XpApiError } from '../../../api/xampulseErrors';
import { describeRule, xampulseAlertsApi } from '../../../api/xampulseAlertsApi';
import type { XpAlertRule, XpChannel, XpConditionType } from '../../../types/xampulse';
import { Card, ErrorState, friendlyError } from '../ui/ui';
import { BacktestPanel } from './BacktestPanel';

export function blankRule(accountId: number): XpAlertRule {
  return {
    name: '', enabled: true, severity: 'HIGH', scope: { accountIds: [accountId] },
    condition: { type: 'METRIC_THRESHOLD', metric: 'system.cpu.utilization', agg: 'avg', window: '5m', op: '>', value: 90, for: '10m', noData: 'KEEP' },
    grouping: { by: ['instance_id'], dedupeWindow: '30m' }, routing: { channelIds: [], createTicket: false },
  };
}

const DURATION = /^\d{1,3}[mhd]$/;
const TYPES: [XpConditionType, string][] = [['METRIC_THRESHOLD', 'Metric crosses a threshold'], ['LOG_COUNT', 'Matching log lines'], ['HEARTBEAT_MISSING', 'Host stops reporting'], ['METRIC_ANOMALY', 'Metric leaves its normal band'], ['FORECAST', 'Disk forecast to fill']];

/** Validation messages for a rule. Empty means it can be saved. */
export function validateRule(r: XpAlertRule): string[] {
  const errs: string[] = [];
  const c = r.condition;
  if (!r.name.trim()) errs.push('Give the rule a name.');
  if (!DURATION.test(c.window)) errs.push('Window must look like 5m, 1h or 1d.');
  if (!DURATION.test(c.for) && c.for !== '0m') errs.push('"For" must look like 10m or 0m.');
  if ((c.type === 'METRIC_THRESHOLD' || c.type === 'METRIC_ANOMALY' || c.type === 'FORECAST') && !c.metric) errs.push('Choose a metric.');
  if ((c.type === 'METRIC_THRESHOLD' || c.type === 'LOG_COUNT') && (c.value === undefined || Number.isNaN(c.value))) errs.push('Enter a threshold value.');
  if (c.type === 'LOG_COUNT' && !c.query) errs.push('Enter a log query to count.');
  return errs;
}

interface Props { initial: XpAlertRule; channels: XpChannel[]; onSaved: () => void; onCancel: () => void; onReload: () => void }

export const RuleEditor: React.FC<Props> = ({ initial, channels, onSaved, onCancel, onReload }) => {
  const [rule, setRule] = useState<XpAlertRule>(initial);
  const errs = validateRule(rule);
  const c = rule.condition;
  const setC = (patch: Partial<XpAlertRule['condition']>) => setRule({ ...rule, condition: { ...c, ...patch } });
  const save = useMutation({ mutationFn: () => (rule.id ? xampulseAlertsApi.updateRule(rule) : xampulseAlertsApi.createRule(rule)), onSuccess: onSaved });
  const [ranFor, setRanFor] = useState<string | null>(null);
  const dry = useMutation({ mutationFn: (r: XpAlertRule) => xampulseAlertsApi.preview(r), onMutate: (r) => setRanFor(JSON.stringify(r)) });
  const stale = dry.data !== undefined && ranFor !== JSON.stringify(rule);
  const conflict = save.error instanceof XpApiError && save.error.code === 'XP_STATE_CONFLICT';

  return (
    <Card title={rule.id ? `Edit rule: ${initial.name}` : 'New alert rule'} id="xp-rule">
      <div className="xp-banner xp-banner--ok" style={{ marginBottom: 12 }} role="status" aria-live="polite"><strong>In plain words:</strong> {describeRule(rule)}</div>
      <div className="xp-grid xp-grid--2">
        <div className="xp-field"><label htmlFor="r-name">Name</label><input id="r-name" className="xp-input" value={rule.name} onChange={(e) => setRule({ ...rule, name: e.target.value })} /></div>
        <div className="xp-field"><label htmlFor="r-sev">Severity</label>
          <select id="r-sev" className="xp-select" value={rule.severity} onChange={(e) => setRule({ ...rule, severity: e.target.value as XpAlertRule['severity'] })}>{(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const).map((s) => <option key={s}>{s}</option>)}</select></div>
        <div className="xp-field"><label htmlFor="r-type">When</label>
          <select id="r-type" className="xp-select" value={c.type} onChange={(e) => setC({ type: e.target.value as XpConditionType })}>{TYPES.map(([t, l]) => <option key={t} value={t}>{l}</option>)}</select></div>
        {c.type !== 'LOG_COUNT' && c.type !== 'HEARTBEAT_MISSING' && (
          <div className="xp-field"><label htmlFor="r-metric">Metric</label><input id="r-metric" className="xp-input xp-input--mono" value={c.metric ?? ''} onChange={(e) => setC({ metric: e.target.value })} /></div>
        )}
        {c.type === 'LOG_COUNT' && <div className="xp-field"><label htmlFor="r-query">Log query</label><input id="r-query" className="xp-input xp-input--mono" value={c.query ?? ''} onChange={(e) => setC({ query: e.target.value })} placeholder="severity:error service:api" /></div>}
        {(c.type === 'METRIC_THRESHOLD' || c.type === 'LOG_COUNT') && (
          <>
            {c.type === 'METRIC_THRESHOLD' && <div className="xp-field"><label htmlFor="r-agg">Average or</label>
              <select id="r-agg" className="xp-select" value={c.agg ?? 'avg'} onChange={(e) => setC({ agg: e.target.value as NonNullable<XpAlertRule['condition']['agg']> })}>{(['avg', 'min', 'max', 'sum', 'p95'] as const).map((a) => <option key={a}>{a}</option>)}</select></div>}
            <div className="xp-field"><label htmlFor="r-op">Is</label>
              <select id="r-op" className="xp-select" value={c.op} onChange={(e) => setC({ op: e.target.value as XpAlertRule['condition']['op'] })}>{(['>', '>=', '<', '<='] as const).map((o) => <option key={o}>{o}</option>)}</select></div>
            <div className="xp-field"><label htmlFor="r-val">Value</label><input id="r-val" type="number" className="xp-input" value={c.value ?? ''} onChange={(e) => setC({ value: e.target.value === '' ? undefined : Number(e.target.value) })} /></div>
          </>
        )}
        {c.type === 'FORECAST' && <div className="xp-field"><label htmlFor="r-fd">Days until full</label><input id="r-fd" type="number" className="xp-input" value={c.forecastDays ?? 7} onChange={(e) => setC({ forecastDays: Number(e.target.value) })} /></div>}
        <div className="xp-field"><label htmlFor="r-win">Over a window of</label><input id="r-win" className="xp-input" value={c.window} onChange={(e) => setC({ window: e.target.value })} /></div>
        <div className="xp-field"><label htmlFor="r-for">For at least</label><input id="r-for" className="xp-input" value={c.for} onChange={(e) => setC({ for: e.target.value })} /></div>
        <div className="xp-field"><label htmlFor="r-nd">If data is missing</label>
          <select id="r-nd" className="xp-select" value={c.noData} onChange={(e) => setC({ noData: e.target.value as XpAlertRule['condition']['noData'] })}><option value="KEEP">Keep the current state</option><option value="ALERT">Alert</option><option value="OK">Treat as fine</option></select></div>
        <div className="xp-field"><label htmlFor="r-ch">Notify</label>
          <select id="r-ch" multiple className="xp-select" value={rule.routing.channelIds.map(String)} onChange={(e) => setRule({ ...rule, routing: { ...rule.routing, channelIds: Array.from(e.target.selectedOptions).map((o) => Number(o.value)) } })}>
            {channels.map((ch) => <option key={ch.id} value={ch.id}>{ch.name}</option>)}
          </select>
          {channels.length === 0 && <span className="xp-muted" style={{ fontSize: 12 }}>No channels yet. An admin can add one in the Channels view.</span>}
        </div>
      </div>
      <details className="xp-rule-advanced">
        <summary>Scope, grouping and routing</summary>
        <div className="xp-grid xp-grid--2" style={{ marginTop: 12 }}>
          <div className="xp-field"><label htmlFor="r-instances">Instance IDs (optional)</label><input id="r-instances" className="xp-input xp-input--mono" value={(rule.scope.instanceIds ?? []).join(', ')} onChange={(e) => setRule({ ...rule, scope: { ...rule.scope, instanceIds: e.target.value.split(',').map((value) => value.trim()).filter(Boolean) } })} placeholder="i-0123, i-0456" /></div>
          <div className="xp-field"><label htmlFor="r-tag">Tag matcher (optional)</label><input id="r-tag" className="xp-input xp-input--mono" value={rule.scope.tag ?? ''} onChange={(e) => setRule({ ...rule, scope: { ...rule.scope, tag: e.target.value || undefined } })} placeholder="environment=production" /></div>
          <div className="xp-field"><label htmlFor="r-group">Group by labels</label><input id="r-group" className="xp-input xp-input--mono" value={rule.grouping.by.join(', ')} onChange={(e) => setRule({ ...rule, grouping: { ...rule.grouping, by: e.target.value.split(',').map((value) => value.trim()).filter(Boolean) } })} /></div>
          <div className="xp-field"><label htmlFor="r-dedupe">Repeat window</label><input id="r-dedupe" className="xp-input" value={rule.grouping.dedupeWindow} onChange={(e) => setRule({ ...rule, grouping: { ...rule.grouping, dedupeWindow: e.target.value } })} /></div>
          {/* No "create a ticket" option: the server refuses it until ticketing is built (RuleCompiler), and XamPulse
              never offers what it cannot do. routing.createTicket stays false. */}
          <div className="xp-field"><label htmlFor="r-runbook">Runbook URL (optional)</label><input id="r-runbook" type="url" className="xp-input" value={rule.routing.runbookUrl ?? ''} onChange={(e) => setRule({ ...rule, routing: { ...rule.routing, runbookUrl: e.target.value || undefined } })} placeholder="https://…" /></div>
        </div>
      </details>
      <label style={{ display: 'flex', gap: 8, marginTop: 12 }}><input type="checkbox" checked={rule.enabled} onChange={(e) => setRule({ ...rule, enabled: e.target.checked })} />Rule is enabled</label>
      {errs.length > 0 && <ul className="xp-err" aria-live="polite">{errs.map((e) => <li key={e}>{e}</li>)}</ul>}
      {conflict && (
        <div className="xp-banner xp-banner--warn" role="alert" style={{ marginTop: 12 }}>Someone else changed this rule while you were editing. <button type="button" className="xp-btn" onClick={onReload}>Reload the latest version</button></div>
      )}
      {save.error && !conflict && <div style={{ marginTop: 12 }}><ErrorState error={save.error} compact /></div>}
      <div className="xp-row" style={{ marginTop: 16 }}>
        <button type="button" className="xp-btn xp-btn--primary" disabled={errs.length > 0 || save.isPending} onClick={() => save.mutate()}>{save.isPending ? 'Saving' : 'Save rule'}</button>
        <button type="button" className="xp-btn" disabled={errs.length > 0 || dry.isPending || c.type === 'METRIC_ANOMALY' || c.type === 'FORECAST'} onClick={() => dry.mutate(rule)}
          title={c.type === 'METRIC_ANOMALY' || c.type === 'FORECAST' ? 'Anomaly and forecast rules depend on baselines rebuilt over time and cannot be replayed exactly' : undefined}>
          {dry.isPending ? 'Replaying' : 'Backtest on the last 24 hours'}</button>
        <button type="button" className="xp-btn xp-btn--ghost" onClick={onCancel}>Cancel</button>
      </div>
      {dry.error && <p className="xp-err" role="alert">{friendlyError(dry.error)}</p>}
      {dry.data && (
        <div className={stale ? 'xp-backtest--stale' : undefined}>
          {stale && <p className="xp-banner xp-banner--warn" role="status" style={{ marginTop: 12 }}>The rule changed since this backtest. Run it again to see the new result.</p>}
          <BacktestPanel p={dry.data} />
        </div>
      )}
    </Card>
  );
};
