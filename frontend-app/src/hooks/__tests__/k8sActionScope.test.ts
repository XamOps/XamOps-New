import { describe, expect, it } from 'vitest';
import {
  ACCOUNT_SCOPE_ACTIONS,
  ACTION_IDS,
  NODE_AGENT_SCOPE_ACTIONS,
  READINESS_KEYS,
  isNodeAgentScopeAction,
  isVerticalAction,
  type ActionId,
  type CapabilitiesCluster,
  type CapabilitiesDto,
  type CapabilitiesWriteGate,
} from '../../types/k8sAutomation';
import { computeCapability } from '../useK8sCapabilities';
import { navAccessFor } from '../useK8sNavAccess';

// ── Scope partition (BP-9 / OQ-6): the 17 ACTION_IDS split 12 + 5 ──────────────

describe('action scope partition', () => {
  it('splits the 17 ACTION_IDS into 12 node-agent + 5 account scope', () => {
    expect(NODE_AGENT_SCOPE_ACTIONS).toHaveLength(12);
    expect(ACCOUNT_SCOPE_ACTIONS).toHaveLength(5);
  });

  it('is exhaustive and disjoint — every action is in exactly one scope', () => {
    const node = new Set<ActionId>(NODE_AGENT_SCOPE_ACTIONS);
    const account = new Set<ActionId>(ACCOUNT_SCOPE_ACTIONS);
    for (const id of ACTION_IDS) {
      const inNode = node.has(id);
      const inAccount = account.has(id);
      expect(inNode !== inAccount, `${id} must be in exactly one scope`).toBe(true);
      expect(isNodeAgentScopeAction(id)).toBe(inNode);
    }
    expect(node.size + account.size).toBe(ACTION_IDS.length);
  });

  it('puts the four vertical.* actions and hpa.apply in the account scope', () => {
    expect([...ACCOUNT_SCOPE_ACTIONS].filter(isVerticalAction)).toHaveLength(4);
    expect(ACCOUNT_SCOPE_ACTIONS).toContain('hpa.apply');
    expect(NODE_AGENT_SCOPE_ACTIONS).not.toContain('hpa.apply');
  });
});

// ── RO + verified cluster: relaxation reaches node-automation only ─────────────

const ALL_READY = READINESS_KEYS;

const RO_WRITE_GATE: CapabilitiesWriteGate = { allowed: false, reasons: ['READ_ONLY_CONNECTION'] };
/** The node-automation gate the backend sends for an RO + verified cluster: READ_ONLY dropped. */
const RELAXED_NODE_GATE: CapabilitiesWriteGate = { allowed: true, reasons: [] };

function roVerifiedCluster(over: Partial<CapabilitiesCluster> = {}): CapabilitiesCluster {
  return {
    clusterName: 'prod-eks',
    registered: true,
    connection: { accessLevel: 'READ_ONLY', status: 'CONNECTED' },
    writeGate: RO_WRITE_GATE,
    nodeAutomationWriteGate: RELAXED_NODE_GATE,
    dataSourceMode: 'agent',
    agent: { status: 'CONNECTED', lastDataAt: null, version: 'v2.1.0' },
    automationAgent: { status: 'CONNECTED', lastSeenAt: null, version: '1.0.0-autoscaler' },
    nodeAutomation: { enabled: true, mode: 'full', evictorAnalyseOnly: true, derivedState: 'ANALYSIS' },
    dataSources: [],
    ...over,
  };
}

function caps(cluster: CapabilitiesCluster): CapabilitiesDto {
  return {
    schemaVersion: 1,
    accountId: '111111111111',
    generatedAt: '2026-10-30T12:00:00Z',
    connection: { accessLevel: 'READ_ONLY', status: 'CONNECTED' },
    user: { canChangeAutomation: true, reasons: [], detail: null },
    writeGate: RO_WRITE_GATE,
    readiness: Object.fromEntries(ALL_READY.map((k) => [k, { ready: true, reason: null, message: null }])),
    verticalEngine: { enabled: true },
    clusters: [cluster],
  };
}

const can = (actionId: ActionId, data: CapabilitiesDto) =>
  computeCapability({ actionId, status: 'ready', data, provider: 'AWS', demo: false, cluster: 'prod-eks' });

describe('RO + verified cluster (nodeAutomationWriteGate)', () => {
  it('opens automation.enable (the node-automation switch) via can()', () => {
    const data = caps(roVerifiedCluster());
    expect(can('automation.enable', data).allowed).toBe(true);
  });

  it('opens every node-agent-scope action that has no other gate, but keeps hpa.apply refused', () => {
    const data = caps(roVerifiedCluster());
    // hpa.apply is account-scope: the RO connection still refuses it.
    const hpa = can('hpa.apply', data);
    expect(hpa.allowed).toBe(false);
    expect(hpa.gate?.reason).toBe('READ_ONLY_CONNECTION');
    // The node-automation switch stays open.
    expect(can('automation.enable', data).allowed).toBe(true);
    expect(can('automation.scope', data).allowed).toBe(true);
  });

  it('navAccessFor is not view-only on an RO + verified cluster', () => {
    const data = caps(roVerifiedCluster());
    const access = navAccessFor('ready', (id) => can(id, data), 'prod-eks');
    expect(access).toEqual({ viewOnly: false, reason: null });
  });

  it('older backend (no nodeAutomationWriteGate) is unchanged — node automation stays refused (AC-27)', () => {
    const cluster = roVerifiedCluster();
    delete (cluster as Partial<CapabilitiesCluster>).nodeAutomationWriteGate;
    const data = caps(cluster);
    const enable = can('automation.enable', data);
    expect(enable.allowed).toBe(false);
    expect(enable.gate?.reason).toBe('READ_ONLY_CONNECTION');
    // And the sidebar reads view-only, exactly as before the relaxation shipped.
    const access = navAccessFor('ready', (id) => can(id, data), 'prod-eks');
    expect(access?.viewOnly).toBe(true);
  });
});
