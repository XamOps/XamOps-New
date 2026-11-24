import { useInfiniteQuery, useQuery, type UseQueryResult } from '@tanstack/react-query';
import { xampulseApi, XpApiError, isXpDemo } from '../../api/xampulseApi';
import { xampulseSetupApi } from '../../api/xampulseSetupApi';
import { xampulseAlertsApi } from '../../api/xampulseAlertsApi';
import type {
  XpAccountRef, XpAlertRule, XpChange, XpChannel, XpContainerRow, XpContainerSummary, XpForecast, XpHostLimits, XpHostRow, XpHostSummary, XpIncident, XpInsight,
  XpLogFacetsResponse, XpLogHistogramResponse, XpLogPatternsResponse, XpLogSearchResponse, XpMetricCatalogEntry,
  XpMetricQueryRequest, XpMetricQueryResponse, XpOverview, XpPage, XpProcessRow, XpScope, XpSilence, XpStatus,
  XpTrustPanel, XpUsage, XpSetupOverview, XpInstanceCandidate, XpBucketCandidate, XpLogSource, XpAuditEntry, XpEvent, XpInvestigation, XpExposure, XpCostPerf,
} from '../../types/xampulse';

/** Do not retry client errors (4xx); retry transient failures once. */
const retry = (count: number, err: unknown): boolean => !(err instanceof XpApiError && err.status > 0 && err.status < 500) && count < 1;

const base = { retry, refetchOnWindowFocus: false } as const;
const demoKey = (): string => (isXpDemo() ? 'demo' : 'live');

export function useXpStatus(): UseQueryResult<XpStatus, XpApiError> {
  return useQuery<XpStatus, XpApiError>({ ...base, queryKey: ['xp', demoKey(), 'status'], queryFn: () => xampulseApi.status(), staleTime: 60_000 });
}

export function useXpAccounts(): UseQueryResult<XpAccountRef[], XpApiError> {
  return useQuery<XpAccountRef[], XpApiError>({ ...base, queryKey: ['xp', demoKey(), 'accounts'], queryFn: () => xampulseApi.accounts(), staleTime: 30_000 });
}

export function useOverview(scope: XpScope): UseQueryResult<XpOverview, XpApiError> {
  return useQuery<XpOverview, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'overview', scope.accountIds, scope.from, scope.to], enabled: scope.accountIds.length > 0,
    queryFn: () => xampulseApi.overview(scope.accountIds, scope.from, scope.to), refetchInterval: 30_000,
  });
}

export interface HostListParams { q?: string; state?: string; page?: number; size?: number; sort?: string }
export function useHosts(accountIds: number[], params: HostListParams = {}): UseQueryResult<XpPage<XpHostRow>, XpApiError> {
  return useQuery<XpPage<XpHostRow>, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'hosts', accountIds, params], enabled: accountIds.length > 0,
    queryFn: () => xampulseApi.hosts({ accountIds, ...params }), refetchInterval: 30_000,
  });
}

export function useHostSummary(accountId: number | undefined, instanceId: string | undefined, from: string, to: string): UseQueryResult<XpHostSummary, XpApiError> {
  return useQuery<XpHostSummary, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'host', accountId, instanceId, from, to], enabled: accountId !== undefined && !!instanceId,
    queryFn: () => xampulseApi.hostSummary(accountId as number, instanceId as string, from, to),
  });
}

export function useHostLimits(accountId: number | undefined, instanceId: string | undefined, from: string, to: string): UseQueryResult<XpHostLimits, XpApiError> {
  return useQuery<XpHostLimits, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'limits', accountId, instanceId, from, to], enabled: accountId !== undefined && !!instanceId,
    queryFn: () => xampulseApi.hostLimits(accountId as number, instanceId as string, from, to),
  });
}

export function useContainerSummary(accountId: number | undefined, instanceId: string | undefined, name: string | undefined, from: string, to: string): UseQueryResult<XpContainerSummary, XpApiError> {
  return useQuery<XpContainerSummary, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'container', accountId, instanceId, name, from, to], enabled: accountId !== undefined && !!instanceId && !!name,
    queryFn: () => xampulseApi.containerSummary(accountId as number, instanceId as string, name as string, from, to),
  });
}

export function useHostChanges(accountId: number | undefined, instanceId: string | undefined, from: string, to: string): UseQueryResult<XpChange[], XpApiError> {
  return useQuery<XpChange[], XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'changes', accountId, instanceId, from, to], enabled: accountId !== undefined && !!instanceId,
    queryFn: () => xampulseApi.hostChanges(accountId as number, instanceId as string, from, to),
  });
}

export function useHostExposure(accountId: number | undefined, instanceId: string | undefined): UseQueryResult<XpExposure, XpApiError> {
  return useQuery<XpExposure, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'exposure', accountId, instanceId], enabled: accountId !== undefined && !!instanceId,
    queryFn: () => xampulseApi.hostExposure(accountId as number, instanceId as string), staleTime: 60_000,
  });
}

export function useHostCost(accountId: number | undefined, instanceId: string | undefined, from: string, to: string): UseQueryResult<XpCostPerf, XpApiError> {
  return useQuery<XpCostPerf, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'cost', accountId, instanceId, from, to], enabled: accountId !== undefined && !!instanceId,
    queryFn: () => xampulseApi.hostCost(accountId as number, instanceId as string, from, to), staleTime: 60_000,
  });
}

/** Investigate runs only when asked for (the section is open): it reads several sources at once. */
export function useInvestigation(accountId: number | undefined, instanceId: string | undefined, from: string, to: string, enabled: boolean): UseQueryResult<XpInvestigation, XpApiError> {
  return useQuery<XpInvestigation, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'investigate', accountId, instanceId, from, to], enabled: enabled && accountId !== undefined && !!instanceId,
    queryFn: () => xampulseApi.investigate(accountId as number, instanceId as string, from, to), staleTime: 60_000,
  });
}

export function useHostProcesses(accountId: number | undefined, instanceId: string | undefined, at?: string): UseQueryResult<XpProcessRow[], XpApiError> {
  return useQuery<XpProcessRow[], XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'procs', accountId, instanceId, at ?? 'latest'], enabled: accountId !== undefined && !!instanceId,
    queryFn: () => xampulseApi.hostProcesses(accountId as number, instanceId as string, at),
  });
}

export interface ContainerListParams { q?: string; page?: number; size?: number; sort?: string }
export function useContainers(accountIds: number[], params: ContainerListParams = {}): UseQueryResult<XpPage<XpContainerRow>, XpApiError> {
  return useQuery<XpPage<XpContainerRow>, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'containers', accountIds, params], enabled: accountIds.length > 0, queryFn: () => xampulseApi.containers({ accountIds, ...params }),
  });
}

export function useEvents(accountIds: number[], from: string, to: string): UseQueryResult<XpEvent[], XpApiError> {
  return useQuery<XpEvent[], XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'events', accountIds, from, to], enabled: accountIds.length > 0, queryFn: () => xampulseApi.events(accountIds, from, to),
  });
}

// ---- logs ----
export function useLogSearch(scope: XpScope, query: string, enabled: boolean) {
  return useInfiniteQuery<XpLogSearchResponse, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'logs', scope, query], enabled: enabled && scope.accountIds.length > 0,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ signal, pageParam }) => xampulseApi.logSearch({ scope, query, limit: 500, sort: 'TS_DESC', cursor: pageParam as string | undefined }, signal),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}

export function useLogHistogram(scope: XpScope, query: string, enabled: boolean): UseQueryResult<XpLogHistogramResponse, XpApiError> {
  return useQuery<XpLogHistogramResponse, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'hist', scope, query], enabled: enabled && scope.accountIds.length > 0,
    queryFn: ({ signal }) => xampulseApi.logHistogram({ scope, query, buckets: 80 }, signal),
  });
}

export const FACET_FIELDS = ['severity', 'service', 'instance_id', 'region', 'source'];

export function useLogFacets(scope: XpScope, query: string, enabled: boolean): UseQueryResult<XpLogFacetsResponse, XpApiError> {
  return useQuery<XpLogFacetsResponse, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'facets', scope, query], enabled: enabled && scope.accountIds.length > 0,
    queryFn: ({ signal }) => xampulseApi.logFacets({ scope, query, fields: FACET_FIELDS, topN: 8 }, signal),
  });
}

export function useLogPatterns(scope: XpScope, query: string, enabled: boolean): UseQueryResult<XpLogPatternsResponse, XpApiError> {
  return useQuery<XpLogPatternsResponse, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'patterns', scope, query], enabled: enabled && scope.accountIds.length > 0,
    queryFn: ({ signal }) => xampulseApi.logPatterns({ scope, query, compareToPrevious: true, limit: 30 }, signal),
  });
}

// ---- metrics ----
export function useMetricQuery(req: XpMetricQueryRequest | null): UseQueryResult<XpMetricQueryResponse, XpApiError> {
  return useQuery<XpMetricQueryResponse, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'metrics', req], enabled: req !== null && req.scope.accountIds.length > 0 && req.series.length > 0,
    queryFn: ({ signal }) => xampulseApi.metricQuery(req as XpMetricQueryRequest, signal),
  });
}

export function useMetricCatalog(accountId: number | undefined, q: string): UseQueryResult<XpMetricCatalogEntry[], XpApiError> {
  return useQuery<XpMetricCatalogEntry[], XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'catalog', accountId, q], enabled: accountId !== undefined, staleTime: 5 * 60_000,
    queryFn: () => xampulseApi.metricCatalog(accountId as number, q),
  });
}

// ---- insights ----
export function useInsights(scope: XpScope): UseQueryResult<XpInsight[], XpApiError> {
  return useQuery<XpInsight[], XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'insights', scope.accountIds, scope.from, scope.to], enabled: scope.accountIds.length > 0,
    queryFn: () => xampulseApi.insights(scope.accountIds, scope.from, scope.to),
  });
}

export function useForecasts(accountId: number | undefined): UseQueryResult<XpForecast[], XpApiError> {
  return useQuery<XpForecast[], XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'forecasts', accountId], enabled: accountId !== undefined, queryFn: () => xampulseApi.forecasts(accountId as number),
  });
}

export function useUsage(accountId: number | undefined): UseQueryResult<XpUsage, XpApiError> {
  return useQuery<XpUsage, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'usage', accountId], enabled: accountId !== undefined, queryFn: () => xampulseApi.usage(accountId as number),
  });
}

export function useTrust(accountId: number | undefined): UseQueryResult<XpTrustPanel, XpApiError> {
  return useQuery<XpTrustPanel, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'trust', accountId], enabled: accountId !== undefined, queryFn: () => xampulseApi.trust(accountId as number),
  });
}

// ---- alerts ----
export function useRules(accountIds: number[]): UseQueryResult<XpAlertRule[], XpApiError> {
  return useQuery<XpAlertRule[], XpApiError>({ ...base, queryKey: ['xp', demoKey(), 'rules', accountIds], enabled: accountIds.length > 0, queryFn: () => xampulseAlertsApi.rules(accountIds) });
}
export function useIncidents(accountIds: number[]): UseQueryResult<XpPage<XpIncident>, XpApiError> {
  return useQuery<XpPage<XpIncident>, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'incidents', accountIds], enabled: accountIds.length > 0, queryFn: () => xampulseAlertsApi.incidents(accountIds), refetchInterval: 30_000,
  });
}
export function useSilences(accountIds: number[]): UseQueryResult<XpSilence[], XpApiError> {
  return useQuery<XpSilence[], XpApiError>({ ...base, queryKey: ['xp', demoKey(), 'silences', accountIds], enabled: accountIds.length > 0, queryFn: () => xampulseAlertsApi.silences(accountIds) });
}
export function useChannels(accountId: number | undefined, enabled: boolean): UseQueryResult<XpChannel[], XpApiError> {
  return useQuery<XpChannel[], XpApiError>({ ...base, queryKey: ['xp', demoKey(), 'channels', accountId], enabled: enabled && accountId !== undefined, queryFn: () => xampulseAlertsApi.channels(accountId as number) });
}

// ---- setup ----
export function useSetupOverview(accountId: number | undefined): UseQueryResult<XpSetupOverview, XpApiError> {
  return useQuery<XpSetupOverview, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'setup', accountId], enabled: accountId !== undefined, queryFn: () => xampulseSetupApi.overview(accountId as number),
  });
}
export function useInstanceCandidates(accountId: number | undefined): UseQueryResult<XpPage<XpInstanceCandidate>, XpApiError> {
  return useQuery<XpPage<XpInstanceCandidate>, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'candidates', accountId], enabled: accountId !== undefined, queryFn: () => xampulseSetupApi.instances(accountId as number, { size: 500 }),
  });
}
export function useBuckets(accountId: number | undefined, enabled: boolean): UseQueryResult<XpBucketCandidate[], XpApiError> {
  return useQuery<XpBucketCandidate[], XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'buckets', accountId], enabled: enabled && accountId !== undefined, queryFn: () => xampulseSetupApi.buckets(accountId as number),
  });
}
export function useSources(accountId: number | undefined, enabled: boolean): UseQueryResult<XpLogSource[], XpApiError> {
  return useQuery<XpLogSource[], XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'sources', accountId], enabled: enabled && accountId !== undefined, queryFn: () => xampulseSetupApi.sources(accountId as number),
  });
}
export function useAudit(accountId: number | undefined, enabled: boolean, page = 0): UseQueryResult<XpPage<XpAuditEntry>, XpApiError> {
  return useQuery<XpPage<XpAuditEntry>, XpApiError>({
    ...base, queryKey: ['xp', demoKey(), 'audit', accountId, page], enabled: enabled && accountId !== undefined, queryFn: () => xampulseSetupApi.audit(accountId as number, page),
  });
}
