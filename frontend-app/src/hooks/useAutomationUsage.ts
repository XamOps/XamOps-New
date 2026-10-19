/**
 * The usage time series (FEAT-004) for one chart: CPU or memory over a window, for the cluster,
 * every namespace or one workload, and the Workloads table's sparklines. One GET per query; no
 * polling; the read in flight is aborted when the query changes or the page unmounts.
 */
import { fetchSparklines, fetchUsage, sparklinesUrl, usageUrl } from '../api/automationUsageApi';
import type { SparklinesView, UsageQuery, UsageResource, UsageView } from '../types/automationInsights';
import { useInsightRead, type InsightReadState } from './useInsightRead';

export interface ClusterArgs {
  clusterName: string | null;
  accountId: string | null;
  region: string | null;
}

const ready = (args: ClusterArgs): args is { clusterName: string; accountId: string; region: string } =>
  !!args.clusterName && !!args.accountId && !!args.region;

/** `query` null reads nothing (the chart isn't shown, or what it needs isn't known yet). */
export function useAutomationUsage(args: ClusterArgs, query: UsageQuery | null): InsightReadState<UsageView> {
  const key = query && ready(args) ? usageUrl(args.clusterName, args.accountId, args.region, query) : null;
  return useInsightRead(key, (signal) => fetchUsage(args.clusterName!, args.accountId!, args.region!, query!, signal));
}

/** `resource` null reads nothing. */
export function useAutomationSparklines(args: ClusterArgs, resource: UsageResource | null): InsightReadState<SparklinesView> {
  const key = resource && ready(args) ? sparklinesUrl(args.clusterName, args.accountId, args.region, resource) : null;
  return useInsightRead(key, (signal) => fetchSparklines(args.clusterName!, args.accountId!, args.region!, resource!, signal));
}

export default useAutomationUsage;
