import { useQuery } from '@tanstack/react-query';
import { getConfig, type AutoSpottingConfig } from '../../api/autospottingApi';

export const autoSpottingKeys = {
  all: ['autospotting'] as const,
  config: () => ['autospotting', 'config'] as const,
  groups: (accountId: number | null) => ['autospotting', 'groups', accountId] as const,
  group: (accountId: number | null, groupId: string | null) =>
    ['autospotting', 'group', accountId, groupId] as const,
  analytics: (accountId: number | null, from: string, to: string) =>
    ['autospotting', 'analytics', accountId, from, to] as const,
  audit: (accountId: number | null, groupId: string, from: string, to: string, limit: number) =>
    ['autospotting', 'audit', accountId, groupId, from, to, limit] as const,
  stackCapabilities: (accountId: number | null) => ['autospotting', 'stack-capabilities', accountId] as const,
  regionCoverage: (accountId: number | null) => ['autospotting', 'region-coverage', accountId] as const,
  potential: (accountId: number | null) => ['autospotting', 'potential', accountId] as const,
  ledgerSummary: (accountId: number | null) => ['autospotting', 'ledger-summary', accountId] as const,
  groupSavings: (accountId: number | null, groupId: string | null) =>
    ['autospotting', 'group-savings', accountId, groupId] as const,
  plan: (accountId: number | null, planId: string | null) =>
    ['autospotting', 'plan', accountId, planId] as const,
  operation: (accountId: number | null, operationId: string | null) =>
    ['autospotting', 'operation', accountId, operationId] as const,
};

/** The runtime config: shell flag, dry run, per-provider readiness, engaged switches. */
export function useAutoSpottingConfig() {
  return useQuery<AutoSpottingConfig>({
    queryKey: autoSpottingKeys.config(),
    queryFn: getConfig,
    staleTime: 60_000,
    retry: false,
  });
}
