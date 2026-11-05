import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { adaptActivityEntry } from '../../../../../api/automationActivityAdapter';
import type { ActivityEntryView, ActivityReadResult } from '../../../../../types/automationActivity';
import type { ConsolidationRun, ConsolidationRunResponse } from '../../../../../types/k8sAutomation';
import {
  CONSOLIDATION_LABEL,
  CONSOLIDATION_NOTE,
  NOT_MEASURED_NOTE,
  PDB_NOTE,
  ProofOfNoHarmPanel,
  type ProofFeed,
} from '../ProofOfNoHarmPanel';
import { APPLIED, LEGACY, PUT_BACK, TEST_RUN } from '../activity/__tests__/activityFixtures';
import panelSource from '../ProofOfNoHarmPanel.tsx?raw';

const views = (...wires: Parameters<typeof adaptActivityEntry>[0][]): ActivityEntryView[] => wires.map((w, i) => adaptActivityEntry(w, i));

function feedOf(entries: ActivityEntryView[], over: Partial<ProofFeed> = {}): ProofFeed {
  const result: ActivityReadResult = { kind: 'ok', page: { engineEnabled: true, entries, nextCursor: null } };
  return { loading: false, result, entries, hasOlder: false, reload: vi.fn(), ...over };
}

const row = (id: string) => document.querySelector(`[data-proof-row="${id}"]`) as HTMLElement;

describe('ProofOfNoHarmPanel', () => {
  it('a perfect proof: headline good, zero restarts, same pod, ready', () => {
    render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf(views(APPLIED))} windowLabel="last 7 days" />);
    const headline = row('headline');
    expect(headline.textContent).toContain('1 of 1 changes proved restart-free');
    expect(headline.querySelector('[data-tone="good"]')).toBeTruthy();
    expect(row('restarts').textContent).toContain('0 across 2 pods');
    expect(row('same-pod').textContent).toContain('2 of 2 pods');
    expect(row('ready').textContent).toContain('2 of 2 pods');
    expect(row('put-back').textContent).toContain('None needed');
    expect(screen.getByTestId('proof-of-no-harm').textContent).toContain('Source: activity log, last 7 days.');
  });

  it('a restarted pod turns the headline bad with "Restarts seen"', () => {
    const restarted = {
      ...APPLIED, restartFree: false,
      proof: { restartFree: false, reverted: null, pods: [{ pod: 'a', node: null, changed: true, uidChanged: false, restartsBefore: 0, restartsAfter: 2, ready: true, revert: null }] },
    };
    render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf(views(restarted))} windowLabel="last 7 days" />);
    expect(row('headline').querySelector('[data-tone="bad"]')).toBeTruthy();
    expect(row('headline').textContent).toContain('Restarts seen');
    expect(row('restarts').textContent).toContain('1 across 1 pod');
  });

  it('put back: confirmed, unconfirmed and none', () => {
    const { unmount } = render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf(views(PUT_BACK))} windowLabel="w" />);
    expect(row('put-back').textContent).toContain('1 change put back and confirmed');
    unmount();
    const unconfirmed = { ...PUT_BACK, proof: { ...PUT_BACK.proof!, reverted: false } };
    render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf(views(unconfirmed))} windowLabel="w" />);
    expect(row('put-back').querySelector('[data-tone="warn"]')?.textContent).toContain('1 put back, not confirmed');
  });

  it('a legacy row is "Not proved", not zero restarts, with the older-changes footer line', () => {
    render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf(views(LEGACY))} windowLabel="w" />);
    expect(row('headline').textContent).toContain('0 of 1 changes proved restart-free');
    expect(row('headline').textContent).toContain('Not proved on 1 change');
    expect(row('headline').querySelector('[data-tone="warn"]')).toBeTruthy();
    expect(row('restarts').textContent).toContain('—');
    expect(row('restarts').textContent).toContain('No pod proof recorded');
    expect(screen.getByTestId('proof-of-no-harm').textContent).toContain(
      '1 older change has no recorded pod proof, so it is not counted as proved.',
    );
  });

  it('no resizes in the window: off tone and dashes', () => {
    render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf(views(TEST_RUN))} windowLabel="w" />);
    expect(row('headline').textContent).toContain('No resizes in this window');
    expect(row('headline').querySelector('[data-tone="off"]')).toBeTruthy();
    for (const id of ['restarts', 'same-pod', 'ready', 'put-back']) expect(row(id).textContent).toContain('—');
  });

  it('unmeasured rows are an em-dash with their notes, even for a perfect proof', () => {
    render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf(views(APPLIED))} windowLabel="w" />);
    for (const id of ['requests-succeeded', 'test-requests']) {
      expect(row(id).textContent).toContain('—');
      expect(row(id).textContent).toContain(NOT_MEASURED_NOTE);
    }
    expect(row('pdb').textContent).toContain('—');
    expect(row('pdb').textContent).toContain(PDB_NOTE);
  });

  it('QoS: per container at workload scope, a dash with "Shown per workload" at cluster scope, a dash when all null', () => {
    const { unmount } = render(
      <ProofOfNoHarmPanel
        scope="workload"
        feed={feedOf(views(APPLIED))}
        windowLabel="w"
        qosClasses={[{ container: 'api', qosClass: 'Guaranteed' }, { container: 'side', qosClass: null }]}
      />,
    );
    expect(row('qos').textContent).toContain('api: Guaranteed');
    expect(row('qos').textContent).toContain("Current class. The log doesn't record it before a change.");
    unmount();
    const nulls = render(
      <ProofOfNoHarmPanel scope="workload" feed={feedOf(views(APPLIED))} windowLabel="w" qosClasses={[{ container: 'api', qosClass: null }]} />,
    );
    expect(row('qos').textContent).toContain('—');
    nulls.unmount();
    render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf(views(APPLIED))} windowLabel="w" />);
    expect(row('qos').textContent).toContain('—');
    expect(row('qos').textContent).toContain('Shown per workload.');
  });

  describe('Consolidation runs group', () => {
    const run = (over: Partial<ConsolidationRun> & { evicted?: number; aborted?: number } = {}): ConsolidationRun => ({
      schemaVersion: 1, runId: 'r', generatedAt: '2026-05-09T00:00:00Z', mode: 'live', dryRun: false, actor: {} as never, nodes: [],
      summary: { considered: 0, removed: 0, deferred: 0, simulated: 0, skipped: 0, refused: 0, aborted: over.aborted ?? 0, podsEvicted: over.evicted ?? 0 },
      ...over,
    } as ConsolidationRun);
    const ok = (runs: ConsolidationRun[], status: ConsolidationRunResponse['status'] = 'OK') =>
      ({ kind: 'ok' as const, data: { status, runs, realizedSavings: null } });
    const group = () => document.querySelector('[data-proof-group="consolidation-runs"]') as HTMLElement;
    const WINDOW = Date.parse('2026-05-03T00:00:00Z');
    const renderDrains = (drains: Parameters<typeof ProofOfNoHarmPanel>[0]['drains'], windowStartMs = WINDOW) =>
      render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf(views(APPLIED))} windowLabel="w" drains={drains} windowStartMs={windowStartMs} />);

    it('is labelled Consolidation runs (never Node drains) with the muted note', () => {
      renderDrains(ok([run({ evicted: 4 })]));
      expect(within(group()).getByRole('heading', { name: CONSOLIDATION_LABEL })).toBeTruthy();
      expect(group().textContent).toContain(CONSOLIDATION_NOTE);
      expect(within(group()).queryByText(/Node drains/)).toBeNull();
    });

    it('live runs add evictions; simulated runs are counted apart; restarts caused stays a dash', () => {
      renderDrains(ok([
        run({ evicted: 5, aborted: 1 }),
        run({ evicted: 7, generatedAt: '2026-05-05T00:00:00Z' }),
        run({ mode: 'simulate', dryRun: true, evicted: 99, generatedAt: '2026-05-06T00:00:00Z' }),
      ]));
      expect(row('drains-evicted').textContent).toContain('12 in 2 live runs');
      expect(row('drains-aborted').textContent).toContain('1');
      expect(row('drains-simulated').textContent).toContain('1 simulated run evicted nothing.');
      expect(row('drains-restarts').textContent).toContain('—');
      expect(row('drains-restarts').textContent).toContain('Not measured for drains.');
      expect(group().querySelector('[data-proof-drains-kept]')!.textContent).toContain('In the 3 runs XamOps kept (newest 2026-05-09, oldest 2026-05-05)');
    });

    it('a short history says where it starts and never claims 7 days; a long one may', () => {
      const { unmount } = renderDrains(ok([run()]));
      const text = group().querySelector('[data-proof-drains-kept]')!.textContent!;
      expect(text).toContain('History starts 2026-05-09');
      expect(text).not.toMatch(/last 7 days/);
      unmount();
      renderDrains(ok([run({ generatedAt: '2026-05-01T00:00:00Z' })]));
      expect(group().querySelector('[data-proof-drains-kept]')!.textContent).toContain('Covers at least the last 7 days');
    });

    it('NO_RUNS, UNREADABLE and a read error', () => {
      const none = renderDrains(ok([], 'NO_RUNS'));
      expect(group().textContent).toContain('No drain runs recorded.');
      none.unmount();
      const unreadable = renderDrains(ok([], 'UNREADABLE'));
      expect(group().textContent).toContain("Run history couldn't be read.");
      expect(row('drains-unreadable').textContent).toContain('—');
      expect(group().textContent).not.toMatch(/\b0\b/);
      unreadable.unmount();
      const retry = vi.fn();
      render(
        <ProofOfNoHarmPanel
          scope="cluster" feed={feedOf(views(APPLIED))} windowLabel="w"
          drains={{ kind: 'error', message: 'boom' }} onRetryDrains={retry}
        />,
      );
      expect(row('drains-error').textContent).toContain('—');
      expect(row('drains-error').textContent).toContain('boom');
      fireEvent.click(within(row('drains-error')).getByRole('button', { name: 'Retry' }));
      expect(retry).toHaveBeenCalled();
    });

    it('is absent at workload scope', () => {
      render(<ProofOfNoHarmPanel scope="workload" feed={feedOf(views(APPLIED))} windowLabel="w" drains={ok([run()])} />);
      expect(document.querySelector('[data-proof-group="consolidation-runs"]')).toBeNull();
    });
  });

  describe('panel states', () => {
    it('loading is a busy skeleton', () => {
      render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf([], { loading: true, result: null })} windowLabel="w" />);
      expect(screen.getByTestId('proof-of-no-harm').getAttribute('aria-busy')).toBe('true');
      expect(document.querySelector('[data-proof-row]')).toBeNull();
    });

    it('not-deployed says so and shows dashes without a Retry', () => {
      render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf([], { result: { kind: 'not-deployed' } })} windowLabel="w" />);
      expect(screen.getByText('This backend has no activity log.')).toBeTruthy();
      expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
      expect(row('restarts').textContent).toContain('—');
    });

    it('an error shows its message and Retry reloads the feed', () => {
      const reload = vi.fn();
      render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf([], { result: { kind: 'error', message: 'Backend down' }, reload })} windowLabel="w" />);
      expect(screen.getByText('Backend down')).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
      expect(reload).toHaveBeenCalled();
    });

    it('a truncated cluster window says "the most recent N log entries"', () => {
      render(<ProofOfNoHarmPanel scope="cluster" feed={feedOf(views(APPLIED), { hasOlder: true })} windowLabel="last 7 days" />);
      expect(screen.getByTestId('proof-of-no-harm').textContent).toContain('Source: activity log, the most recent 1 log entries.');
    });
  });

  it('v3 conformance: dot + word, no pills in the panel source', () => {
    expect(panelSource).toContain('StatusDot');
    expect(panelSource).not.toMatch(/StatusChip|rounded-full/);
  });
});
