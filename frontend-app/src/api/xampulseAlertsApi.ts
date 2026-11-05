import { xpGet, xpPost, xpRequest } from './xampulseApi';
import type {
  XpAlertPreview, XpAlertRule, XpChannel, XpChannelInput, XpIncident, XpIncidentAction, XpPage, XpSilence,
} from '../types/xampulse';

export type IncidentVerb = 'ack' | 'snooze' | 'resolve' | 'comment';

export const xampulseAlertsApi = {
  rules: (accountIds: number[]) => xpGet<XpAlertRule[]>('/alerts/rules', { accountIds }),
  createRule: (r: XpAlertRule) => xpPost<XpAlertRule>('/alerts/rules', r),
  updateRule: (r: XpAlertRule) => xpRequest<XpAlertRule>('PUT', `/alerts/rules/${r.id}`, { body: r }),
  deleteRule: (id: number) => xpRequest<Record<string, never>>('DELETE', `/alerts/rules/${id}`),
  preview: (r: XpAlertRule) => xpPost<XpAlertPreview>('/alerts/rules/preview', r),
  incidents: (accountIds: number[], state?: string) => xpGet<XpPage<XpIncident>>('/alerts/incidents', { accountIds, state }),
  incidentAction: (id: number, verb: IncidentVerb, a: XpIncidentAction) => xpPost<XpIncident>(`/alerts/incidents/${id}/${verb}`, a),
  silences: (accountIds: number[]) => xpGet<XpSilence[]>('/alerts/silences', { accountIds }),
  createSilence: (s: XpSilence) => xpPost<XpSilence>('/alerts/silences', s),
  deleteSilence: (id: number) => xpRequest<Record<string, never>>('DELETE', `/alerts/silences/${id}`),
  channels: (accountId: number) => xpGet<XpChannel[]>('/alerts/channels', { accountId }),
  createChannel: (c: XpChannelInput) => xpPost<XpChannel>('/alerts/channels', c),
  deleteChannel: (id: number) => xpRequest<Record<string, never>>('DELETE', `/alerts/channels/${id}`),
  testChannel: (id: number) => xpPost<Record<string, never>>(`/alerts/channels/${id}/test`, {}),
};

/** Plain-language sentence for a rule, e.g. "Alert HIGH when average cpu > 90% for 10m on 2 accounts". */
export function describeRule(r: XpAlertRule): string {
  const c = r.condition;
  const scope = r.scope.tag ? `hosts tagged ${r.scope.tag}` : r.scope.instanceIds?.length ? `${r.scope.instanceIds.length} selected host(s)` : 'all hosts';
  const dur = c.for && c.for !== '0m' ? ` for ${c.for}` : '';
  switch (c.type) {
    case 'METRIC_THRESHOLD':
      return `Alert ${r.severity} when ${c.agg ?? 'avg'} ${c.metric ?? 'metric'} is ${c.op} ${c.value ?? '?'} over ${c.window}${dur} on ${scope}`;
    case 'LOG_COUNT':
      return `Alert ${r.severity} when matching logs${c.query ? ` (${c.query})` : ''} number ${c.op} ${c.value ?? '?'} in ${c.window}${dur} on ${scope}`;
    case 'HEARTBEAT_MISSING':
      return `Alert ${r.severity} when ${scope} stop reporting for ${c.window}`;
    case 'METRIC_ANOMALY':
      return `Alert ${r.severity} when ${c.metric ?? 'metric'} leaves its normal band over ${c.window}${dur} on ${scope}`;
    case 'FORECAST':
      return `Alert ${r.severity} when ${c.metric ?? 'a filesystem'} is forecast to be full within ${c.forecastDays ?? 7} days on ${scope}`;
  }
}
