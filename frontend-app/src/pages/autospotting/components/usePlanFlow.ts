import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AutoSpottingApiError,
  newIdempotencyKey,
  parsePlanBody,
  parseWarningIds,
  type GroupRow,
  type Plan,
  type PlanBodyDoc,
} from '../../../api/autospottingApi';
import { useConfirmPlan, useCreatePlan } from '../../../hooks/autospotting/usePlan';
import { classifyConfirmError, reasonForError, type ConfirmOutcome } from './reasons';

export interface PlanFlowOptions {
  accountId: number | null;
  row: GroupRow;
  kind: string;
  /** Whether the kind takes a profile (ENABLE, PROFILE_CHANGE). */
  usesProfile: boolean;
  /** GCP only: the edited rolling-update fields, sent with a MIG_POLICY_UPDATE or CONFIG_APPLY request. */
  policyEdit?: Record<string, unknown>;
  /** Test hook: no delay between network retries. */
  retryDelayMs?: number;
}

/**
 * The create / re-plan / confirm flow shared by the plan and revert dialogs (design-ux-ops.md §3).
 * One idempotency key per user action: a retry of the same request reuses it, a changed request gets
 * a new one.
 */
export function usePlanFlow({ accountId, row, kind, usesProfile, policyEdit, retryDelayMs }: PlanFlowOptions) {
  const create = useCreatePlan(accountId);
  const confirmMutation = useConfirmPlan(accountId);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [profile, setProfile] = useState<string | null>(usesProfile ? 'conservative' : null);
  const [createError, setCreateError] = useState<unknown>(null);
  const [creating, setCreating] = useState(false);
  const [acked, setAcked] = useState<string[]>([]);
  const [fullSpot, setFullSpot] = useState(false);
  const [reason, setReason] = useState('');
  const [outcome, setOutcome] = useState<ConfirmOutcome | null>(null);
  const [confirming, setConfirming] = useState(false);
  const createKey = useRef<{ sig: string; key: string } | null>(null);
  const confirmKey = useRef<{ sig: string; key: string } | null>(null);
  const started = useRef(false);

  const body: PlanBodyDoc | null = useMemo(() => (plan ? parsePlanBody(plan) : null), [plan]);
  const warningIds = useMemo(() => (plan ? parseWarningIds(plan) : []), [plan]);

  const startPlan = useCallback(
    async (profileId: string | null) => {
      setCreating(true);
      setCreateError(null);
      setOutcome(null);
      setAcked([]);
      setFullSpot(false);
      const request = {
        kind,
        ...(row.id ? { groupId: row.id } : { groupRef: row.groupKey }),
        ...(profileId ? { profileId } : {}),
        ...(policyEdit ? { policyEdit } : {}),
      };
      const sig = JSON.stringify(request);
      if (!createKey.current || createKey.current.sig !== sig) {
        createKey.current = { sig, key: newIdempotencyKey() };
      }
      try {
        const created = await create.mutateAsync({ request, idempotencyKey: createKey.current.key, retryDelayMs });
        setPlan(created);
        setProfile(profileId);
      } catch (e) {
        setCreateError(e);
      } finally {
        setCreating(false);
      }
    },
    [create, kind, row.id, row.groupKey, policyEdit, retryDelayMs],
  );

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void startPlan(usesProfile ? 'conservative' : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Choosing a profile creates a superseding plan (the old one becomes SUPERSEDED). */
  const selectProfile = useCallback((profileId: string) => startPlan(profileId), [startPlan]);

  /** Re-plan after PLAN_STALE or expiry: keeps the chosen profile and clears acknowledgments. */
  const refreshPlan = useCallback(() => startPlan(profile), [startPlan, profile]);

  const toggleAck = useCallback((id: string) => {
    setAcked(prev => (prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]));
  }, []);

  const confirm = useCallback(async (): Promise<string | null> => {
    if (!plan) return null;
    const request = {
      planVersion: plan.version,
      acknowledgedWarningIds: acked,
      fullSpotOptIn: fullSpot,
      ...(reason.trim() ? { reason: reason.trim() } : {}),
    };
    const sig = `${plan.planId}:${JSON.stringify(request)}`;
    if (!confirmKey.current || confirmKey.current.sig !== sig) {
      confirmKey.current = { sig, key: newIdempotencyKey() };
    }
    setConfirming(true);
    setOutcome(null);
    try {
      const res = await confirmMutation.mutateAsync({
        planId: plan.planId,
        request,
        idempotencyKey: confirmKey.current.key,
        dryRun: plan.dryRun,
        retryDelayMs,
      });
      return res.operationId;
    } catch (e) {
      setOutcome(classifyConfirmError(e, row.provider));
      return null;
    } finally {
      setConfirming(false);
    }
  }, [plan, acked, fullSpot, reason, confirmMutation, retryDelayMs, row.provider]);

  const createErrorCode = createError instanceof AutoSpottingApiError ? createError.code : null;
  const createErrorText = createError ? reasonForError(createError, row.provider) : null;

  return {
    plan,
    body,
    warningIds,
    profile,
    creating,
    createError,
    createErrorCode,
    createErrorText,
    acked,
    fullSpot,
    setFullSpot,
    reason,
    setReason,
    outcome,
    confirming,
    startPlan,
    selectProfile,
    refreshPlan,
    toggleAck,
    confirm,
  };
}
