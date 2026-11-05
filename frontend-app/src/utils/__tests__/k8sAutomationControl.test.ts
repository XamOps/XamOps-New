import { describe, expect, it } from 'vitest';
import { PAUSE_SETTLE_MS, deriveControlView, type ControlInput } from '../k8sAutomationControl';
import type { CapabilitiesCluster } from '../../types/k8sAutomation';

const T = Date.parse('2026-05-10T12:00:00Z');
const MARKER = { priorMode: 'scoped' as const, pausedAt: new Date(T).toISOString() };

function cluster(over: {
  registered?: boolean;
  agent?: 'CONNECTED' | 'NOT_INSTALLED';
  automationAgent?: 'CONNECTED' | 'NOT_INSTALLED' | null;
  enabled?: boolean;
  mode?: string | null;
  derivedState?: string | null;
  effectiveActing?: string | null;
} = {}): CapabilitiesCluster {
  const nodeAutomation: Record<string, unknown> = {
    enabled: over.enabled ?? true,
    mode: over.mode === undefined ? 'full' : over.mode,
    evictorAnalyseOnly: false,
    derivedState: over.derivedState === undefined ? 'AUTOMATED' : over.derivedState,
  };
  if (over.effectiveActing !== null) nodeAutomation.effectiveActing = over.effectiveActing ?? 'ACTING';
  if (over.derivedState === null) delete nodeAutomation.derivedState;
  const c: Record<string, unknown> = {
    clusterName: 'prod',
    registered: over.registered ?? true,
    agent: { status: over.agent ?? 'CONNECTED', lastDataAt: null, version: null },
    nodeAutomation,
  };
  if (over.automationAgent !== null) {
    c.automationAgent = { status: over.automationAgent ?? 'CONNECTED', lastSeenAt: null, version: null };
  }
  return c as unknown as CapabilitiesCluster;
}

const derive = (over: Partial<ControlInput> = {}) => deriveControlView({
  status: 'ready', cluster: cluster(), marker: null, override: null, lastWriteAt: null, now: T, ...over,
});

describe('deriveControlView: kinds', () => {
  it('loading without an override; an override outranks loading', () => {
    expect(derive({ status: 'loading', cluster: null }).kind).toBe('loading');
    expect(derive({ status: 'loading', cluster: null, override: 'full' }).kind).toBe('autonomous');
  });

  it('error + override disabled + marker -> paused; no marker -> off', () => {
    const paused = derive({ status: 'error', cluster: null, override: 'disabled', marker: MARKER });
    expect(paused).toMatchObject({ kind: 'paused', priorMode: 'scoped' });
    expect(derive({ status: 'error', cluster: null, override: 'disabled' }).kind).toBe('off');
  });

  it('error + override full -> autonomous, not confirmed', () => {
    expect(derive({ status: 'error', cluster: null, override: 'full' })).toMatchObject({ kind: 'autonomous', acting: 'not-confirmed' });
  });

  it('no cluster and no override -> unknown', () => {
    expect(derive({ status: 'error', cluster: null }).kind).toBe('unknown');
  });

  it('unregistered / NOT_INSTALLED derived state / NOT_INSTALLED agent -> not-installed, with or without an override', () => {
    for (const override of [null, 'full', 'disabled'] as const) {
      expect(derive({ cluster: cluster({ registered: false }), override }).kind).toBe('not-installed');
      expect(derive({ cluster: cluster({ derivedState: 'NOT_INSTALLED' }), override }).kind).toBe('not-installed');
      expect(derive({ cluster: cluster({ automationAgent: 'NOT_INSTALLED' }), override }).kind).toBe('not-installed');
    }
  });

  it('automationAgent absent -> not-installed without throwing', () => {
    expect(derive({ cluster: cluster({ automationAgent: null }) }).kind).toBe('not-installed');
    expect(derive({ cluster: cluster({ automationAgent: null }), override: 'full' }).kind).toBe('not-installed');
  });

  it('agent NOT_INSTALLED with automationAgent CONNECTED stays autonomous (the capability gate blocks the button, not the view)', () => {
    const v = derive({ cluster: cluster({ agent: 'NOT_INSTALLED' }) });
    expect(v).toMatchObject({ kind: 'autonomous', acting: 'acting' });
    expect(derive({ cluster: cluster({ agent: 'NOT_INSTALLED' }), override: 'full' }).kind).toBe('autonomous');
  });

  it('enabled:false -> not-enabled, even under an override', () => {
    expect(derive({ cluster: cluster({ enabled: false }) }).kind).toBe('not-enabled');
    expect(derive({ cluster: cluster({ enabled: false }), override: 'full' }).kind).toBe('not-enabled');
  });

  it('unrecognised or null mode -> unknown', () => {
    expect(derive({ cluster: cluster({ mode: 'weird' }) }).kind).toBe('unknown');
    expect(derive({ cluster: cluster({ mode: null }) }).kind).toBe('unknown');
  });

  it('disabled with a marker -> paused; without -> off; full/scoped -> autonomous', () => {
    expect(derive({ cluster: cluster({ mode: 'disabled' }), marker: MARKER })).toMatchObject({ kind: 'paused', priorMode: 'scoped' });
    expect(derive({ cluster: cluster({ mode: 'disabled' }) }).kind).toBe('off');
    expect(derive({ cluster: cluster({ mode: 'scoped' }) }).kind).toBe('autonomous');
  });

  it('the override replaces the snapshot mode', () => {
    expect(derive({ cluster: cluster({ mode: 'full' }), override: 'disabled', marker: MARKER }).kind).toBe('paused');
  });
});

describe('deriveControlView: acting word', () => {
  it.each([
    ['AUTOMATED', 'ACTING', 'acting'],
    ['AUTOMATED', 'NOT_ACTING', 'ready-to-enable'],
    ['AUTOMATED', 'UNKNOWN', 'not-confirmed'],
    ['AUTOMATED', null, 'not-confirmed'],
    ['ANALYSIS', 'ACTING', 'analysis-only'],
  ])('%s + %s -> %s', (derivedState, effectiveActing, expected) => {
    expect(derive({ cluster: cluster({ derivedState, effectiveActing }) }).acting).toBe(expected);
  });

  it.each(['OFF', 'UNKNOWN', 'NOT_INSTALLED', null])('derivedState %s is not-confirmed even with override full/scoped', (derivedState) => {
    for (const override of ['full', 'scoped'] as const) {
      const v = derive({ cluster: cluster({ derivedState, effectiveActing: 'ACTING', automationAgent: 'CONNECTED' }), override });
      // NOT_INSTALLED derived state is a not-installed view; the others are autonomous / not-confirmed.
      if (derivedState === 'NOT_INSTALLED') expect(v.kind).toBe('not-installed');
      else expect(v).toMatchObject({ kind: 'autonomous', acting: 'not-confirmed' });
    }
  });
});

describe('deriveControlView: time-bound still-acting', () => {
  const paused = (now: number, over: Partial<ControlInput> = {}, acting: string | null = 'ACTING') =>
    derive({ cluster: cluster({ mode: 'disabled', effectiveActing: acting }), marker: MARKER, now, ...over });

  it('inside the window: stillActingAfterPause', () => {
    expect(paused(T + PAUSE_SETTLE_MS - 1)).toMatchObject({ kind: 'paused', stillActingAfterPause: true, lastRunPredatesPause: false });
  });

  it('at the boundary and an hour later: lastRunPredatesPause', () => {
    for (const now of [T + PAUSE_SETTLE_MS, T + 3600 * 1000]) {
      expect(paused(now)).toMatchObject({ stillActingAfterPause: false, lastRunPredatesPause: true });
    }
  });

  it('NOT_ACTING or absent: both false at any time', () => {
    for (const acting of ['NOT_ACTING', null]) {
      expect(paused(T + 1, {}, acting)).toMatchObject({ stillActingAfterPause: false, lastRunPredatesPause: false });
      expect(paused(T + 3600 * 1000, {}, acting)).toMatchObject({ stillActingAfterPause: false, lastRunPredatesPause: false });
    }
  });

  it('no marker: lastWriteAt is the clock (kind off)', () => {
    const off = (now: number, lastWriteAt: number | null) =>
      derive({ cluster: cluster({ mode: 'full' }), override: 'disabled', lastWriteAt, now });
    expect(off(T + PAUSE_SETTLE_MS - 1, T)).toMatchObject({ kind: 'off', stillActingAfterPause: true });
    expect(off(T + PAUSE_SETTLE_MS, T)).toMatchObject({ kind: 'off', stillActingAfterPause: false, lastRunPredatesPause: true });
    expect(off(T + 1, null)).toMatchObject({ stillActingAfterPause: false, lastRunPredatesPause: false });
  });

  it('a marker wins over lastWriteAt as the clock', () => {
    const v = paused(T + PAUSE_SETTLE_MS + 1, { lastWriteAt: T + PAUSE_SETTLE_MS });
    expect(v.lastRunPredatesPause).toBe(true);
  });

  it('is never claimed outside the disabled mode', () => {
    expect(derive({ cluster: cluster({ mode: 'full' }) })).toMatchObject({ stillActingAfterPause: false, lastRunPredatesPause: false });
  });
});
