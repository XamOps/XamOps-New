import { describe, expect, it } from 'vitest';
import { actionWord, errorLine, reasonWord, statusView, unavailableLine } from '../proposalCopy';
import {
  consequenceText, countdownText, groupProposals, metricCells, notYetApprovable, targetText, whyText,
} from '../proposalDerivations';
import { IN_72_MIN, NOW, iso, list, proposal, workloadProposal } from './proposalFixtures';

describe('countdownText', () => {
  it('counts down in hours and minutes, then minutes, then under a minute, then Expired', () => {
    expect(countdownText(iso(IN_72_MIN), NOW)).toBe('in 1 h 12 min');
    expect(countdownText(iso(5 * 60_000), NOW)).toBe('in 5 min');
    expect(countdownText(iso(20_000), NOW)).toBe('in under a minute');
    expect(countdownText(iso(0), NOW)).toBe('Expired');
    expect(countdownText(iso(-1000), NOW)).toBe('Expired');
  });
  it('uses days past a day and an em dash with no expiry', () => {
    expect(countdownText(iso(26 * 3_600_000), NOW)).toBe('in 1 d 2 h');
    expect(countdownText(null, NOW)).toBe('—');
  });
});

describe('groupProposals', () => {
  it('splits by status and sorts waiting rows by soonest expiry', () => {
    const g = groupProposals([
      proposal({ id: 'a', expiresAt: iso(3_600_000) }),
      proposal({ id: 'b', expiresAt: iso(60_000) }),
      proposal({ id: 'c', status: 'REFUSED' }),
      proposal({ id: 'd', status: 'APPROVED' }),
      proposal({ id: 'e', status: 'DISPATCHED' }),
      proposal({ id: 'f', status: 'EXECUTED' }),
      proposal({ id: 'g', status: 'STALE' }),
    ]);
    expect(g.waiting.map((p) => p.id)).toEqual(['b', 'a']);
    expect(g.refused.map((p) => p.id)).toEqual(['c']);
    expect(g.inProgress.map((p) => p.id)).toEqual(['d', 'e']);
    expect(g.history.map((p) => p.id).sort()).toEqual(['f', 'g']);
  });
});

describe('words', () => {
  it('derives a why sentence from node usage and from a workload resize', () => {
    expect(whyText(proposal())).toBe('Node is idle: CPU usage 4%, memory usage 11%, 7 pods can move');
    expect(whyText(workloadProposal())).toBe('CPU request 500m to 220m');
    expect(whyText(proposal({ params: {}, evidence: {} }))).toBe('—');
    expect(whyText(proposal({ params: {} }))).toBe('7 pods fit on the remaining nodes');
  });
  it('shows a node by short name and a workload by ns/Kind/name', () => {
    expect(targetText(proposal())).toBe('ip-10-0-1-5');
    expect(targetText(workloadProposal())).toBe('shop/Deployment/api');
  });
  it('names the node-removal actor in the approve consequence', () => {
    expect(consequenceText(proposal())).toContain('Drains ip-10-0-1-5 by evicting 7 pods through the Eviction API');
    expect(consequenceText(proposal())).toContain('the node is left to KARPENTER to remove');
    expect(consequenceText(proposal({ evidence: { actor: 'NONE' } }))).toContain('stays cordoned and empty; XamOps does not remove it');
    expect(consequenceText(proposal({ evidence: { actor: 'MULTIPLE' } }))).toContain('removal is not decided by XamOps');
    expect(consequenceText(proposal({ evidence: {} }))).toContain('removal is not decided by XamOps');
    expect(consequenceText(proposal({ dryRun: true }))).toContain('changes nothing');
  });
  it('words every status, including executed variants', () => {
    expect(statusView(proposal({ status: 'STALE' })).word).toBe('Stale, the cluster changed before it ran');
    expect(statusView(proposal({ status: 'EXECUTED', outcomeVerified: true })).word).toBe('Executed and verified');
    expect(statusView(proposal({ status: 'EXECUTED', outcomeVerified: false })).word).toBe('Executed, not verified');
    expect(statusView(proposal({ status: 'EXECUTED', dryRun: true })).word).toBe('Simulated, nothing changed');
    expect(statusView(proposal({ status: 'FAILED' })).tone).toBe('bad');
    expect(actionWord('NODE_DRAIN', 'CORDON_ONLY')).toBe('Cordon node');
  });
  it('words fit reason codes and keeps the detail', () => {
    expect(reasonWord('pdb-no-disruptions:shop/api')).toBe('A PodDisruptionBudget allows no disruptions (shop/api)');
    expect(reasonWord('db-primary')).toBe('Hosts a database primary');
    expect(reasonWord('brand-new-code')).toBe('brand-new-code');
  });
  it('has a line per approve error code and a fallback', () => {
    expect(errorLine('VERSION_CONFLICT', 'x')).toBe('Someone else already decided this.');
    expect(errorLine('FINGERPRINT_MISMATCH', 'x')).toContain('changed just now');
    expect(errorLine('STALE', 'x')).toContain('went stale before approval');
    expect(errorLine('K8S_MODE_DISABLED'.replace('K8S_', ''), 'x')).toContain('Automation is paused');
    expect(errorLine('READ_ONLY_CONNECTION', 'server words')).toBe('server words');
    expect(unavailableLine('CONTROLLER_NOT_READY')).toBe("Manual mode isn't enabled in this environment.");
  });
});

describe('notYetApprovable and the metric strip', () => {
  it('is true only while approvableAfter is in the future', () => {
    expect(notYetApprovable(proposal({ approvableAfter: iso(60_000) }), NOW)).toBe(true);
    expect(notYetApprovable(proposal({ approvableAfter: iso(-60_000) }), NOW)).toBe(false);
    expect(notYetApprovable(proposal(), NOW)).toBe(false);
  });
  it('uses an em dash while loading or unreadable and counts executed rows of the last 7 days', () => {
    expect(metricCells(null, NOW).map((c) => c.value)).toEqual(['—', '—', '—', '—']);
    expect(metricCells(list([], { state: 'UNREADABLE', counts: null }), NOW).map((c) => c.value)).toEqual(['—', '—', '—', '—']);
    const cells = metricCells(list([
      proposal({ id: 'a' }),
      proposal({ id: 'b', status: 'EXECUTED', decidedAt: iso(-86_400_000) }),
      proposal({ id: 'c', status: 'EXECUTED', decidedAt: iso(-9 * 86_400_000) }),
      proposal({ id: 'd', status: 'EXECUTED', dryRun: true, decidedAt: iso(-1000) }),
    ]), NOW);
    expect(cells.map((c) => c.value)).toEqual([1, 0, 0, '1']);
  });
});
