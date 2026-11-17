import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  confirmPlan,
  createPlan,
  getPlan,
  type ConfirmRequest,
  type Plan,
  type PlanRequest,
} from '../../api/autospottingApi';
import { autoSpottingKeys } from './useAutoSpottingConfig';

/** Read one plan. */
export function usePlan(accountId: number | null, planId: string | null) {
  return useQuery<Plan>({
    queryKey: autoSpottingKeys.plan(accountId, planId),
    queryFn: () => getPlan(accountId as number, planId as string),
    enabled: accountId !== null && !!planId,
    retry: false,
  });
}

/** Create a plan. The caller supplies the idempotency key so a retry of one action reuses it. */
export function useCreatePlan(accountId: number | null) {
  return useMutation({
    mutationFn: (v: { request: PlanRequest; idempotencyKey: string; retryDelayMs?: number }) =>
      createPlan(accountId as number, v.request, v.idempotencyKey, { retryDelayMs: v.retryDelayMs }),
  });
}

/** Confirm a plan; refetches the group list so the badge follows the backend state. */
export function useConfirmPlan(accountId: number | null) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (v: {
      planId: string;
      request: ConfirmRequest;
      idempotencyKey: string;
      dryRun?: boolean;
      retryDelayMs?: number;
    }) =>
      confirmPlan(accountId as number, v.planId, v.request, v.idempotencyKey, {
        dryRun: v.dryRun,
        retryDelayMs: v.retryDelayMs,
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: autoSpottingKeys.groups(accountId) });
    },
  });
}
