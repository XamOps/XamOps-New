/**
 * Typed client for the usage time series (FEAT-004), read-only:
 *
 *   GET /api/eks/automation/usage/{cluster}?accountId&region&scope&resource&window[&namespace&kind&name][&from&to&step]
 *   GET /api/eks/automation/usage/{cluster}/sparklines?accountId&region&resource&window=72h
 *
 * The server reads Prometheus in the cluster through the API server's service proxy, with the
 * engine's own pod selection for one workload, so a chart and a suggestion read the same pods. A
 * source failure inside the cluster is a `200` with `unavailable` set; the status mapping is
 * `readInsight`'s.
 */
import { readInsight, isObject } from './insightRead';
import { adaptSparklines, adaptUsage } from './automationInsightsAdapter';
import type {
  InsightReadResult,
  SparklinesResponseWire,
  SparklinesView,
  UsageQuery,
  UsageResource,
  UsageResponseWire,
  UsageView,
} from '../types/automationInsights';

const BASE = '/api/eks/automation/usage';

const seg = (value: string): string => encodeURIComponent(value);

/** An instant as the server wants it: ISO-8601 in UTC, to the second (`+` offsets would need %2B). */
const isoSeconds = (ms: number): string => new Date(Math.floor(ms / 1000) * 1000).toISOString().replace('.000Z', 'Z');

export const usageUrl = (cluster: string, accountId: string, region: string, query: UsageQuery): string => {
  const params = [
    `accountId=${seg(accountId)}`,
    `region=${seg(region)}`,
    `scope=${query.scope}`,
    `resource=${query.resource}`,
    `window=${query.window}`,
  ];
  if (query.scope === 'workload' && query.workload) {
    params.push(`namespace=${seg(query.workload.namespace)}`, `kind=${seg(query.workload.kind)}`, `name=${seg(query.workload.name)}`);
  }
  if (query.window === 'custom' && query.from !== undefined && query.to !== undefined) {
    params.push(`from=${seg(isoSeconds(query.from))}`, `to=${seg(isoSeconds(query.to))}`, `step=${query.step ?? 60}`);
  }
  return `${BASE}/${seg(cluster)}?${params.join('&')}`;
};

export const sparklinesUrl = (cluster: string, accountId: string, region: string, resource: UsageResource): string =>
  `${BASE}/${seg(cluster)}/sparklines?accountId=${seg(accountId)}&region=${seg(region)}&resource=${resource}&window=72h`;

const isUsageWire = (body: unknown): body is UsageResponseWire =>
  isObject(body) && (Array.isArray(body.series) || Array.isArray(body.namespaces) || isObject(body.unavailable));

const isSparklinesWire = (body: unknown): body is SparklinesResponseWire =>
  isObject(body) && (Array.isArray(body.workloads) || isObject(body.unavailable));

/** CPU or memory over a window, for the cluster, every namespace or one workload. */
export const fetchUsage = (
  cluster: string,
  accountId: string,
  region: string,
  query: UsageQuery,
  signal?: AbortSignal,
): Promise<InsightReadResult<UsageView>> =>
  readInsight(usageUrl(cluster, accountId, region, query), { isWire: isUsageWire, adapt: adaptUsage, what: 'The usage feed', signal });

/** One small series pair per analysed workload (the busiest pod's use and request), 72 h at 1 h. */
export const fetchSparklines = (
  cluster: string,
  accountId: string,
  region: string,
  resource: UsageResource,
  signal?: AbortSignal,
): Promise<InsightReadResult<SparklinesView>> =>
  readInsight(sparklinesUrl(cluster, accountId, region, resource), {
    isWire: isSparklinesWire,
    adapt: adaptSparklines,
    what: 'The sparklines feed',
    signal,
  });
