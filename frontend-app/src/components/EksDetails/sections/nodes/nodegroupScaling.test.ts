import { describe, it, expect } from 'vitest';
import {
  hasAgentScaling,
  indexByName,
  nodeGroupNote,
  NODE_GROUP_API_NOTE,
  type NodegroupScalingResponse,
} from './nodegroupScaling';

const agentResp: NodegroupScalingResponse = {
  source: 'AGENT',
  collectedAt: '2026-10-30T12:00:00Z',
  nodegroups: [
    { name: 'ng-1', status: 'ACTIVE', capacityType: 'ON_DEMAND', min: 1, desired: 4, max: 4,
      asgNames: ['eks-ng-1'], amiType: 'AL2023', instanceTypes: ['m5.large'] },
  ],
};

const noneResp: NodegroupScalingResponse = { source: 'NONE', nodegroups: [] };

describe('nodegroupScaling helpers (design §8.1, both modes)', () => {
  it('AGENT mode swaps the note and shows the age', () => {
    expect(nodeGroupNote(agentResp)).toContain('Scaling limits from the EKS API');
    expect(nodeGroupNote(agentResp)).toContain('2026-10-30T12:00:00Z');
  });

  it('NONE / null falls back to the not-available note', () => {
    expect(nodeGroupNote(noneResp)).toBe(NODE_GROUP_API_NOTE);
    expect(nodeGroupNote(null)).toBe(NODE_GROUP_API_NOTE);
  });

  it('hasAgentScaling is true only for a non-empty AGENT response', () => {
    expect(hasAgentScaling(agentResp)).toBe(true);
    expect(hasAgentScaling(noneResp)).toBe(false);
    expect(hasAgentScaling({ source: 'AGENT', nodegroups: [] })).toBe(false);
    expect(hasAgentScaling(null)).toBe(false);
  });

  it('indexByName maps AGENT rows by nodegroup name', () => {
    const idx = indexByName(agentResp);
    expect(idx.get('ng-1')?.desired).toBe(4);
    expect(indexByName(noneResp).size).toBe(0);
  });
});
