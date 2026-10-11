/**
 * The CloudK8s capability model (docs/cloudk8s/ui-revamp-design.md §6.3, §6.4, §11.2).
 *
 * `K8sCapabilitiesProvider` loads `GET /api/xamops/k8s/capabilities` for one account; every
 * automation control asks `can(actionId, ctx)` for a {@link Capability}. The decision itself is the
 * pure {@link computeCapability}, so it is tested without a provider.
 *
 * The UI can only ever be stricter than the server: the server enforces the same write gates on
 * `/api/eks/autoscaling/**`, and greying a control out is presentation, not security.
 */
import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { getCapabilities, isK8sApiError, type K8sApiError } from '../api/k8sAutomationApi';
import { verticalGates, type VerticalGateInput } from '../api/verticalRightsizerAdapter';
import { isDemoMode } from '../demo/demoConfig';
import {
  isNoUserPermissionDetail,
  isReasonCode,
  stripCodePrefix,
  type ActionId,
  type CapabilitiesCluster,
  type CapabilitiesDto,
  type Capability,
  type Gate,
  type ReadinessKey,
  type ReasonCode,
} from '../types/k8sAutomation';

export type CapabilitiesStatus = 'loading' | 'ready' | 'error';

// ── Pure model ───────────────────────────────────────────────────────────────

/** §6.4: lower ranks are harder to remove and win. DEMO is handled separately. */
const RANK: Record<ReasonCode, number> = {
  PROVIDER_UNSUPPORTED: 1,
  CONTROLLER_NOT_READY: 2,
  ANALYSE_ONLY: 2,
  GITOPS_MANAGED: 2,
  NO_USER_PERMISSION: 3,
  READ_ONLY_CONNECTION: 4,
  CONNECTION_NOT_VERIFIED: 4.5,
  UNKNOWN: 5,
  AGENT_NOT_INSTALLED: 6,
  NO_METRICS_SOURCE: 6,
  INSUFFICIENT_HISTORY: 7,
  // Never compete (§6.4): locked guardrails are static text, AWS permission gaps gate data views.
  GUARDRAIL_LOCKED: 8,
  MISSING_AWS_PERMISSION: 8,
  // Never primary: they only explain the direct path under EXECUTION_PATH (§6.1).
  CLUSTER_ACCESS_NOT_CONFIGURED: 9,
  CLUSTER_UNREACHABLE: 9,
  DEMO: 10,
};

/** While capabilities load, everything is blocked without a reason line (a skeleton). */
export const LOADING_CAPABILITY: Capability = Object.freeze({ allowed: false, editable: false, gate: null, also: [] }) as Capability;

/**
 * Orders gates by §6.4, de-duplicates them by reason code (the first occurrence and its message
 * win), and derives `allowed` and `editable`. DEMO is primary only when it is the only reason;
 * otherwise it goes last in `also`. `editable` holds only when there is no gate or DEMO is the
 * only one.
 */
export function rankGates(gates: Gate[]): Capability {
  const ordered = gates
    .map((gate, index) => ({ gate, index }))
    .sort((a, b) => RANK[a.gate.reason] - RANK[b.gate.reason] || a.index - b.index)
    .map((entry) => entry.gate);
  const seen = new Set<ReasonCode>();
  const unique: Gate[] = [];
  for (const gate of ordered) {
    if (!seen.has(gate.reason)) {
      seen.add(gate.reason);
      unique.push(gate);
    }
  }
  if (unique.length === 0) return { allowed: true, editable: true, gate: null, also: [] };
  const demo = unique.find((g) => g.reason === 'DEMO');
  const rest = unique.filter((g) => g.reason !== 'DEMO');
  if (rest.length === 0) return { allowed: false, editable: true, gate: demo ?? null, also: [] };
  return {
    allowed: false,
    editable: false,
    gate: rest[0],
    also: demo ? [...rest.slice(1), demo] : rest.slice(1),
  };
}

/** The readiness key behind each action that has one (§6.3). */
const READINESS_BY_ACTION: Partial<Record<ActionId, ReadinessKey>> = {
  'vertical.policy.recommend': 'vertical-rightsizing',
  'vertical.policy.auto': 'vertical-rightsizing',
  'vertical.method.persist': 'vertical-rightsizing',
  'vertical.apply': 'vertical-rightsizing',
  'evictor.live': 'evictor-live',
  'nodeOptimizer.actions': 'node-optimizer-actions',
  'scaleOut.settings': 'scale-out',
  'nodeTemplates.edit': 'node-templates',
  'rebalance.trigger': 'rebalancer',
  'rebalance.schedule': 'rebalancer',
  'hpa.apply': 'hpa-apply',
};

/** Node-automation controls that need the agents on the cluster (§6.3 "AGENT_NOT_INSTALLED"). */
const NEEDS_AUTOMATION_AGENT: ReadonlySet<ActionId> = new Set<ActionId>([
  'automation.enable',
  'automation.scope',
  'evictor.settings',
  'evictor.live',
  'nodeOptimizer.settings',
  'nodeOptimizer.actions',
  'guardrails.edit',
]);

export const RETIRED_RIGHTSIZER_MESSAGE =
  "The automation agent's rightsizer is retired. Workload rightsizing is configured in Workload autoscaling.";

export interface CapabilityContext {
  /** The cluster the control acts on; its own writeGate and agent state apply. */
  cluster?: string | null;
  /** The vertical engine's view for `vertical.*` actions. */
  vertical?: VerticalGateInput;
}

export interface CapabilityInput extends CapabilityContext {
  actionId: ActionId;
  status: CapabilitiesStatus;
  data: CapabilitiesDto | null;
  /** The selected account's provider; anything but AWS adds PROVIDER_UNSUPPORTED. */
  provider?: string | null;
  /** Demo session (isDemoMode()). */
  demo?: boolean;
}

/** A server reason name as a gate; NO_USER_PERMISSION takes its detail, unknown names fail closed. */
function serverGate(name: string, data: CapabilitiesDto): Gate {
  const code = stripCodePrefix(name);
  if (!isReasonCode(code)) return { reason: 'UNKNOWN', raw: name };
  if (code === 'NO_USER_PERMISSION' && isNoUserPermissionDetail(data.user?.detail)) {
    return { reason: code, detail: data.user.detail };
  }
  return { reason: code };
}

export function findCluster(data: CapabilitiesDto | null, name: string | null | undefined): CapabilitiesCluster | null {
  if (!data || !name) return null;
  return data.clusters?.find((c) => c.clusterName === name) ?? null;
}

/**
 * What one control may do (§6.3, §6.4, §11.2):
 * write gates from the cluster's own writeGate when capabilities lists it (else the top level),
 * readiness per action, the agents for node-automation actions, PROVIDER_UNSUPPORTED off AWS,
 * DEMO in demo sessions, UNKNOWN when capabilities failed, and for `vertical.*` the engine's gates
 * through the adapter (including the contract block).
 */
export function computeCapability(input: CapabilityInput): Capability {
  const { actionId, status, data } = input;
  if (status === 'loading') return LOADING_CAPABILITY;

  const gates: Gate[] = [];
  const provider = input.provider ?? 'AWS';
  if (provider.toUpperCase() !== 'AWS') gates.push({ reason: 'PROVIDER_UNSUPPORTED' });

  if (status === 'error') {
    gates.push({ reason: 'UNKNOWN' });
  }

  // Vertical gates first, so the contract block's CONTROLLER_NOT_READY (and its message) is the
  // occurrence de-duplication keeps (§6.4).
  gates.push(...verticalGates(input.vertical ?? {}, actionId));

  if (status === 'ready' && data) {
    const cluster = findCluster(data, input.cluster);
    const writeGate = cluster?.writeGate ?? data.writeGate;
    for (const name of writeGate?.reasons ?? []) gates.push(serverGate(name, data));

    if (actionId === 'agentRightsizer.edit') {
      gates.push({ reason: 'CONTROLLER_NOT_READY', message: RETIRED_RIGHTSIZER_MESSAGE });
    }
    const key = READINESS_BY_ACTION[actionId];
    if (key) {
      const readiness = data.readiness?.[key];
      if (!readiness) {
        gates.push({ reason: 'CONTROLLER_NOT_READY' }); // a missing key fails closed
      } else if (!readiness.ready) {
        const reason = readiness.reason && isReasonCode(stripCodePrefix(readiness.reason))
          ? (stripCodePrefix(readiness.reason) as ReasonCode)
          : 'CONTROLLER_NOT_READY';
        gates.push({ reason, message: readiness.message ?? undefined });
      }
    }

    if (NEEDS_AUTOMATION_AGENT.has(actionId) && input.cluster) {
      if (!cluster || cluster.agent.status === 'NOT_INSTALLED') {
        gates.push({ reason: 'AGENT_NOT_INSTALLED', detail: 'XAMOPS_AGENT' });
      } else if (cluster.automationAgent.status === 'NOT_INSTALLED') {
        gates.push({ reason: 'AGENT_NOT_INSTALLED', detail: 'AUTOMATION_AGENT' });
      } else if (cluster.automationAgent.status === 'UNKNOWN') {
        gates.push({ reason: 'UNKNOWN' });
      }
    }
  } else if (status === 'ready' && !data && provider.toUpperCase() === 'AWS') {
    gates.push({ reason: 'UNKNOWN' }); // no data on an AWS account: fail closed
  }

  if (input.demo) gates.push({ reason: 'DEMO' });
  return rankGates(gates);
}

// ── Provider ─────────────────────────────────────────────────────────────────

export interface K8sCapabilitiesValue {
  status: CapabilitiesStatus;
  data: CapabilitiesDto | null;
  /** The load failure, when status is 'error'. */
  error: K8sApiError | Error | null;
  /** The account isn't the caller's: show "You don't have access to this account." page-level. */
  noAccess: boolean;
  forCluster: (name: string | null | undefined) => CapabilitiesCluster | null;
  /** The engine's own switch; when false no engine call is made at all. */
  verticalEngineEnabled: boolean;
  can: (actionId: ActionId, ctx?: CapabilityContext) => Capability;
  /** Reloads capabilities (Retry, or after a gate changed underneath the page). */
  refetch: () => void;
  /** Call with any mutation error: refetches when the refusal's code is a gate. */
  onRefusal: (error: unknown) => void;
  demo: boolean;
  provider: string;
}

const K8sCapabilitiesContext = createContext<K8sCapabilitiesValue | null>(null);

export interface K8sCapabilitiesProviderProps {
  /** The AWS account number. */
  accountId: string | null;
  /** The selected account's provider (AWS, GCP, Azure). Non-AWS accounts make no request. */
  provider?: string | null;
  /** Overrides isDemoMode(), for tests. */
  demo?: boolean;
  children: React.ReactNode;
}

export function K8sCapabilitiesProvider({ accountId, provider, demo, children }: K8sCapabilitiesProviderProps) {
  const effectiveProvider = provider ?? 'AWS';
  const isAws = effectiveProvider.toUpperCase() === 'AWS';
  const demoSession = demo ?? isDemoMode();
  const [status, setStatus] = useState<CapabilitiesStatus>(isAws && accountId ? 'loading' : 'ready');
  const [data, setData] = useState<CapabilitiesDto | null>(null);
  const [error, setError] = useState<K8sApiError | Error | null>(null);
  const [noAccess, setNoAccess] = useState(false);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    if (!isAws || !accountId) {
      setStatus('ready');
      setData(null);
      setError(null);
      setNoAccess(false);
      return undefined;
    }
    const controller = new AbortController();
    setStatus('loading');
    setNoAccess(false);
    getCapabilities(accountId, { signal: controller.signal })
      .then((dto) => {
        if (controller.signal.aborted) return;
        setData(dto);
        setError(null);
        setStatus('ready');
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted || (err instanceof DOMException && err.name === 'AbortError')) return;
        console.warn('CloudK8s capabilities failed to load', err);
        const forbidden = isK8sApiError(err) && err.status === 403
          && (err.code === null || err.code === 'K8S_NO_ACCOUNT_ACCESS');
        setNoAccess(forbidden);
        setData(null);
        setError(err instanceof Error ? err : new Error(String(err)));
        setStatus('error');
      });
    return () => controller.abort();
  }, [accountId, isAws, generation]);

  const refetch = useCallback(() => setGeneration((g) => g + 1), []);

  const onRefusal = useCallback((err: unknown) => {
    if (isK8sApiError(err) && err.reason && isReasonCode(err.reason)) refetch();
  }, [refetch]);

  const value = useMemo<K8sCapabilitiesValue>(() => ({
    status,
    data,
    error,
    noAccess,
    forCluster: (name) => findCluster(data, name),
    verticalEngineEnabled: status === 'ready' && data?.verticalEngine?.enabled === true,
    can: (actionId, ctx) => computeCapability({
      actionId, status, data, provider: effectiveProvider, demo: demoSession, ...ctx,
    }),
    refetch,
    onRefusal,
    demo: demoSession,
    provider: effectiveProvider,
  }), [status, data, error, noAccess, refetch, onRefusal, demoSession, effectiveProvider]);

  return <K8sCapabilitiesContext.Provider value={value}>{children}</K8sCapabilitiesContext.Provider>;
}

/** The surrounding provider's value, or null outside one (shared components that only need Retry). */
export function useOptionalK8sCapabilities(): K8sCapabilitiesValue | null {
  return useContext(K8sCapabilitiesContext);
}

/** The capability model of the surrounding {@link K8sCapabilitiesProvider}. */
export function useK8sCapabilities(): K8sCapabilitiesValue {
  const value = useContext(K8sCapabilitiesContext);
  if (!value) throw new Error('useK8sCapabilities must be used inside K8sCapabilitiesProvider');
  return value;
}
