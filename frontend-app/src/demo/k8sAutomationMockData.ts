/**
 * Demo fixtures for CloudK8s automation (docs/cloudk8s/ui-revamp-design.md §16).
 *
 * Demo-only and never reachable outside a demo session. Shapes follow the real endpoints; timestamps
 * are generated relative to now so freshness labels stay current.
 *
 * - `demo-aws-001` is read-write and the demo user is permitted, as a real permitted customer would
 *   see it; the UI adds DEMO itself, so its node-automation drafts are editable and every save reads
 *   "Demo workspace. Changes aren't saved."
 * - `demo-aws-002` is a read-only connection: every control reads "Read-only connection…" except
 *   where a product rule ranks higher (§6.4).
 * - Both: readiness as in production (all not ready), the vertical engine switched on, and the same
 *   clusters. `staging-eks-cluster` has no entry, so it reads as unregistered.
 */
import type {
  CapabilitiesCluster,
  CapabilitiesDto,
  CapabilitiesReadiness,
  K8sRefusal,
  ReadinessKey,
} from '../types/k8sAutomation';
import { READINESS_KEYS } from '../types/k8sAutomation';

/** The §6.7 messages, as the server sends them while a key is not ready. */
export const DEMO_READINESS_MESSAGES: Record<ReadinessKey, string> = {
  'vertical-rightsizing':
    'Workload autoscaling changes are disabled until the vertical rightsizer passes live validation. Recommendations stay visible.',
  'hpa-apply': 'Automated HPA changes are disabled until the HPA manager passes safety validation.',
  'vpa-apply': "XamOps doesn't create or update VPA objects yet.",
  'evictor-live': 'Live consolidation is disabled until pod-fit simulation is available.',
  rebalancer: 'Rebalancing is blocked: the current rebalancer can drain the same nodes repeatedly.',
  'node-optimizer-actions':
    'Node cordon, drain and spot conversion are disabled until the node optimizer passes safety validation.',
  'scale-out': "Not available: the automation agent doesn't provision nodes. Your cluster autoscaler or Karpenter does.",
  'node-templates': "Not available: the automation agent doesn't use node templates yet.",
};

/** Demo accounts the fixture answers for; anything else is refused like an account that isn't yours. */
export const DEMO_K8S_ACCOUNTS = ['demo-aws-001', 'demo-aws-002'] as const;

const ago = (now: number, seconds: number) => new Date(now - seconds * 1000).toISOString();

function readiness(): Record<string, CapabilitiesReadiness> {
  return Object.fromEntries(READINESS_KEYS.map((key) => [key, {
    ready: false,
    reason: key === 'evictor-live' ? 'ANALYSE_ONLY' : 'CONTROLLER_NOT_READY',
    message: DEMO_READINESS_MESSAGES[key],
  }]));
}

function clusters(now: number, readOnly: boolean): CapabilitiesCluster[] {
  const connection = { accessLevel: readOnly ? 'READ_ONLY' : 'READ_WRITE', status: 'CONNECTED' } as const;
  const writeGate = { allowed: !readOnly, reasons: readOnly ? ['READ_ONLY_CONNECTION'] : [] };
  return [
    {
      clusterName: 'prod-eks-cluster',
      registered: true,
      connection,
      writeGate,
      dataSourceMode: 'agent',
      agent: { status: 'CONNECTED', lastDataAt: ago(now, 4 * 60 + 12), version: 'v2.1.0' },
      automationAgent: { status: 'CONNECTED', lastSeenAt: ago(now, 40), version: '1.0.0-autoscaler' },
      nodeAutomation: { enabled: true, mode: 'full', evictorAnalyseOnly: true, derivedState: 'ANALYSIS' },
      dataSources: [
        { type: 'k8s-resources', collectedAt: ago(now, 4 * 60 + 12), items: 1, displayed: true },
        { type: 'prometheus-metrics', collectedAt: ago(now, 4 * 60 + 15), items: 1, displayed: true },
        { type: 'opencost-cost', collectedAt: ago(now, 9 * 60 + 41), items: 14, displayed: true },
        { type: 'trivy-vulnerabilities', collectedAt: ago(now, 52 * 60), items: 37, displayed: true },
        { type: 'kubescape-compliance', collectedAt: ago(now, 3 * 3600 + 7 * 60), items: 22, displayed: false },
        { type: 'autoscaling-metrics', collectedAt: ago(now, 55), items: 1, displayed: true },
      ],
    },
    {
      clusterName: 'eu-eks-cluster',
      registered: true,
      connection,
      writeGate,
      dataSourceMode: 'agent',
      agent: { status: 'CONNECTED', lastDataAt: ago(now, 2 * 60 + 48), version: 'v2.1.0' },
      automationAgent: { status: 'CONNECTED', lastSeenAt: ago(now, 1 * 60 + 5), version: '1.0.0-autoscaler' },
      nodeAutomation: { enabled: false, mode: null, evictorAnalyseOnly: null, derivedState: 'OFF' },
      dataSources: [
        { type: 'k8s-resources', collectedAt: ago(now, 2 * 60 + 48), items: 1, displayed: true },
        { type: 'prometheus-metrics', collectedAt: ago(now, 2 * 60 + 51), items: 1, displayed: true },
      ],
    },
  ];
}

/** Capabilities for a demo account, or null for an account the demo doesn't have. */
export function demoCapabilities(accountId: string, now: number = Date.now()): CapabilitiesDto | null {
  if (!(DEMO_K8S_ACCOUNTS as readonly string[]).includes(accountId)) return null;
  const readOnly = accountId === 'demo-aws-002';
  return {
    schemaVersion: 1,
    accountId,
    generatedAt: new Date(now).toISOString(),
    connection: { accessLevel: readOnly ? 'READ_ONLY' : 'READ_WRITE', status: 'CONNECTED' },
    user: { canChangeAutomation: true, reasons: [], detail: null },
    writeGate: { allowed: !readOnly, reasons: readOnly ? ['READ_ONLY_CONNECTION'] : [] },
    readiness: readiness(),
    verticalEngine: { enabled: true },
    clusters: clusters(now, readOnly),
  };
}

/** What the server answers for an account that isn't the caller's. */
export const DEMO_NO_ACCOUNT_ACCESS: K8sRefusal = {
  success: false,
  code: 'K8S_NO_ACCOUNT_ACCESS',
  message: "You don't have access to this account.",
  field: null,
  detail: null,
};
