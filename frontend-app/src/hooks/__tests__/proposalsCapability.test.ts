import { describe, expect, it } from 'vitest';
import { computeCapability } from '../useK8sCapabilities';
import { ACTION_IDS, READINESS_KEYS } from '../../types/k8sAutomation';
import { CLUSTER, capsDto } from './nodeControlFixtures';

const ready = { ready: true, reason: null, message: null };
const notReady = { ready: false, reason: 'CONTROLLER_NOT_READY', message: 'Manual approval is off.' };
const IDS = ['proposals.approve.node', 'proposals.approve.workload'] as const;

describe('proposals.approve.* actions', () => {
  it('adds the two action ids and the manual-approval readiness key', () => {
    expect(ACTION_IDS).toHaveLength(19);
    for (const id of IDS) expect(ACTION_IDS).toContain(id);
    expect(READINESS_KEYS).toHaveLength(11);
    expect(READINESS_KEYS).toContain('manual-approval');
  });

  it.each(IDS)('%s is allowed when ready and writable', (id) => {
    const data = capsDto({ readiness: { 'manual-approval': ready } });
    expect(computeCapability({ actionId: id, status: 'ready', data, cluster: CLUSTER }).allowed).toBe(true);
  });

  it.each(IDS)('%s is blocked with the readiness message while manual-approval is off', (id) => {
    const data = capsDto({ readiness: { 'manual-approval': notReady } });
    const cap = computeCapability({ actionId: id, status: 'ready', data, cluster: CLUSTER });
    expect(cap.allowed).toBe(false);
    expect(cap.gate?.reason).toBe('CONTROLLER_NOT_READY');
    expect(cap.gate?.message).toBe('Manual approval is off.');
  });

  it('fails closed when the readiness key is missing', () => {
    const cap = computeCapability({ actionId: 'proposals.approve.node', status: 'ready', data: capsDto(), cluster: CLUSTER });
    expect(cap.allowed).toBe(false);
  });

  it.each(IDS)('%s carries the write-gate reason for a read-only connection', (id) => {
    const data = capsDto({ readiness: { 'manual-approval': ready }, writeReasons: ['READ_ONLY_CONNECTION'] });
    const cap = computeCapability({ actionId: id, status: 'ready', data, cluster: CLUSTER });
    expect(cap.allowed).toBe(false);
    expect(cap.gate?.reason).toBe('READ_ONLY_CONNECTION');
  });

  it('the node action needs the automation agent; the workload action does not', () => {
    const data = capsDto({ readiness: { 'manual-approval': ready }, automationAgent: 'NOT_INSTALLED' });
    expect(computeCapability({ actionId: 'proposals.approve.node', status: 'ready', data, cluster: CLUSTER }).gate?.reason).toBe('AGENT_NOT_INSTALLED');
    expect(computeCapability({ actionId: 'proposals.approve.workload', status: 'ready', data, cluster: CLUSTER }).allowed).toBe(true);
  });
});
