import { describe, expect, it } from 'vitest';
import { adaptActivityEntry } from '../../../../../api/automationActivityAdapter';
import type { ActivityEntryWire } from '../../../../../types/automationActivity';
import type { ConsolidationRun, ConsolidationRunResponse } from '../../../../../types/k8sAutomation';
import { proofChip } from '../activity/activityText';
import { summarizeDrains, summarizeProof } from '../proofOfNoHarm';
import { APPLIED, HPA_CHANGE, LEGACY, MODE, PUT_BACK, REFUSED, TEST_RUN } from '../activity/__tests__/activityFixtures';

const view = (wires: ActivityEntryWire[]) => wires.map((w, i) => adaptActivityEntry(w, i));
const withPods = (pods: object[], extra: object = {}): ActivityEntryWire => ({
  ...APPLIED,
  restartFree: false,
  proof: { restartFree: false, reverted: null, pods: pods as never, ...extra },
});

describe('summarizeProof', () => {
  it('APPLIED: zero restarts, same pod, ready', () => {
    const s = summarizeProof(view([APPLIED]));
    expect(s).toMatchObject({
      changes: 1, changesRestartFree: 1, pods: 2, podsRestarted: 0, podsChecked: 2, podsUnrecorded: 0,
      samePodKnown: 2, samePod: 2, readyKnown: 2, ready: 2,
    });
  });

  it('a restarted pod and a replaced pod each count once and break the headline', () => {
    const restarted = withPods([{ pod: 'a', node: null, changed: true, uidChanged: false, restartsBefore: 0, restartsAfter: 1, ready: true, revert: null }]);
    const replaced = withPods([{ pod: 'b', node: null, changed: true, uidChanged: true, restartsBefore: null, restartsAfter: null, ready: true, revert: null }]);
    const s = summarizeProof(view([restarted, replaced]));
    expect(s.podsRestarted).toBe(2);
    expect(s.changesRestartFree).toBe(0);
    for (const e of view([restarted, replaced])) expect(proofChip(e)?.label).toBe('Restarted');
  });

  it('counts put-back confirmed and unconfirmed; null is none', () => {
    expect(summarizeProof(view([PUT_BACK]))).toMatchObject({ putBackConfirmed: 1, putBackUnconfirmed: 0 });
    const unconfirmed: ActivityEntryWire = { ...PUT_BACK, proof: { ...PUT_BACK.proof!, reverted: false } };
    expect(summarizeProof(view([unconfirmed]))).toMatchObject({ putBackConfirmed: 0, putBackUnconfirmed: 1 });
    expect(summarizeProof(view([APPLIED]))).toMatchObject({ putBackConfirmed: 0, putBackUnconfirmed: 0 });
  });

  it('LEGACY (restartFree on the wire, no proof) is not counted as proved, and proofChip is unchanged', () => {
    const entries = view([LEGACY]);
    expect(summarizeProof(entries)).toMatchObject({ changes: 1, changesRestartFree: 0, changesWithoutProof: 1, pods: 0 });
    expect(proofChip(entries[0])?.label).toBe('Restart-free');
  });

  it('null restart counts are "not recorded", never 0', () => {
    const unknown: ActivityEntryWire = {
      ...APPLIED,
      restartFree: true,
      proof: { restartFree: true, reverted: null, pods: [{ pod: 'a', node: null, changed: null, uidChanged: null, restartsBefore: null, restartsAfter: null, ready: null, revert: null }] },
    };
    expect(summarizeProof(view([unknown]))).toMatchObject({
      pods: 1, podsChecked: 0, podsUnrecorded: 1, podsRestarted: 0, samePodKnown: 0, readyKnown: 0,
    });
  });

  it('excludes test runs, refusals, HPA and mode changes', () => {
    expect(summarizeProof(view([TEST_RUN, REFUSED, HPA_CHANGE, MODE])).changes).toBe(0);
  });
});

const run = (over: Partial<ConsolidationRun> & { podsEvicted?: number; aborted?: number; refused?: number } = {}): ConsolidationRun => ({
  schemaVersion: 1, runId: 'r', generatedAt: '2026-05-09T00:00:00Z', mode: 'live', dryRun: false,
  actor: {} as never, nodes: [],
  summary: {
    considered: 0, removed: 0, deferred: 0, simulated: 0, skipped: 0,
    refused: over.refused ?? 0, aborted: over.aborted ?? 0, podsEvicted: over.podsEvicted ?? 0,
  },
  ...over,
} as ConsolidationRun);
const resp = (runs: ConsolidationRun[], status: ConsolidationRunResponse['status'] = 'OK'): ConsolidationRunResponse =>
  ({ status, runs, realizedSavings: null });
const WINDOW = Date.parse('2026-05-03T00:00:00Z');

describe('summarizeDrains', () => {
  it('UNREADABLE and none', () => {
    expect(summarizeDrains(resp([], 'UNREADABLE'), WINDOW)).toEqual({ kind: 'unreadable' });
    expect(summarizeDrains(resp([], 'NO_RUNS'), WINDOW)).toEqual({ kind: 'none' });
    expect(summarizeDrains(resp([]), WINDOW)).toEqual({ kind: 'none' });
  });

  it('live runs add evictions; simulated runs are counted apart and add nothing', () => {
    const s = summarizeDrains(resp([
      run({ podsEvicted: 5, aborted: 1 }),
      run({ podsEvicted: 7, refused: 0, generatedAt: '2026-05-05T00:00:00Z' }),
      run({ mode: 'simulate', dryRun: true, podsEvicted: 99, aborted: 9, generatedAt: '2026-05-06T00:00:00Z' }),
    ]), WINDOW);
    expect(s).toMatchObject({ kind: 'runs', runsKept: 3, liveRuns: 2, simulatedRuns: 1, podsEvicted: 12, abortedOrRefused: 1 });
  });

  it('a history that starts after the window start sets the note; one reaching past it does not', () => {
    expect(summarizeDrains(resp([run()]), WINDOW)).toMatchObject({ historyStartsNote: true });
    expect(summarizeDrains(resp([run({ generatedAt: '2026-05-01T00:00:00Z' })]), WINDOW)).toMatchObject({ historyStartsNote: false });
  });

  it('a run without a parseable date counts in totals but not in the dates', () => {
    const s = summarizeDrains(resp([run({ podsEvicted: 3, generatedAt: 'garbage' }), run({ podsEvicted: 2 })]), WINDOW);
    expect(s).toMatchObject({ runsKept: 2, podsEvicted: 5, newest: Date.parse('2026-05-09T00:00:00Z'), oldest: Date.parse('2026-05-09T00:00:00Z') });
    const none = summarizeDrains(resp([run({ generatedAt: 'garbage' })]), WINDOW);
    expect(none).toMatchObject({ runsKept: 1, newest: null, oldest: null, historyStartsNote: false });
  });
});
