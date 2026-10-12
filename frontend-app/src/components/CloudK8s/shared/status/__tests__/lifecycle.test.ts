import { describe, expect, it } from 'vitest';
import { deriveLifecycle, highlightStage, type LifecycleInputs } from '../lifecycle';

const stageMap = (inputs: LifecycleInputs) =>
  Object.fromEntries(deriveLifecycle(inputs).map((s) => [s.stage, s]));

describe('deriveLifecycle (glossary §5)', () => {
  it('Measuring is current until coverage reaches the minimum', () => {
    const s = stageMap({ measured: false, hasSuggestion: false });
    expect(s.measuring.status).toBe('current');
    expect(s.measuring.tone).toBe('info');
    expect(s.suggestion.status).toBe('todo');
  });

  it('Suggestion ready is done once a recommendation exists', () => {
    const s = stageMap({ measured: true, hasSuggestion: true });
    expect(s.measuring.status).toBe('done');
    expect(s.suggestion.status).toBe('done');
    expect(s.suggestion.tone).toBe('good');
  });

  it('Applying is current while the apply runs', () => {
    const s = stageMap({ measured: true, hasSuggestion: true, applyProgress: 'RUNNING' });
    expect(s.applying.status).toBe('current');
    expect(s.applying.tone).toBe('info');
  });

  it('Applying is done on APPLIED', () => {
    const s = stageMap({ measured: true, hasSuggestion: true, applyProgress: 'APPLIED', appliedOpen: true });
    expect(s.applying.status).toBe('done');
    expect(s.applied.status).toBe('done');
  });

  it('DEFERRED, PARTIALLY_APPLIED and STUCK are warn problems', () => {
    for (const p of ['DEFERRED', 'PARTIALLY_APPLIED', 'STUCK'] as const) {
      const s = stageMap({ measured: true, hasSuggestion: true, applyProgress: p });
      expect(s.applying.status).toBe('problem');
      expect(s.applying.tone).toBe('warn');
    }
  });

  it('INFEASIBLE, FAILED, RESTART_DETECTED and NOT_READY are bad problems', () => {
    for (const p of ['INFEASIBLE', 'FAILED', 'RESTART_DETECTED', 'NOT_READY'] as const) {
      const s = stageMap({ measured: true, hasSuggestion: true, applyProgress: p });
      expect(s.applying.status).toBe('problem');
      expect(s.applying.tone).toBe('bad');
    }
  });

  it('Watching is current while the cooldown is in the future, done once it ends', () => {
    const future = stageMap({ measured: true, hasSuggestion: true, applyProgress: 'APPLIED', appliedOpen: true, hasCooldown: true, cooldownEnded: false });
    expect(future.watching.status).toBe('current');
    const past = stageMap({ measured: true, hasSuggestion: true, applyProgress: 'APPLIED', appliedOpen: true, hasCooldown: true, cooldownEnded: true });
    expect(past.watching.status).toBe('done');
  });

  it('Watching is todo with no cooldown yet', () => {
    const s = stageMap({ measured: true, hasSuggestion: true });
    expect(s.watching.status).toBe('todo');
    expect(s.watching.tone).toBe('off');
  });
});

describe('highlightStage', () => {
  it('prefers the first problem', () => {
    const states = deriveLifecycle({ measured: true, hasSuggestion: true, applyProgress: 'DEFERRED' });
    expect(highlightStage(states)).toBe(2); // applying
  });

  it('falls back to the current stage', () => {
    const states = deriveLifecycle({ measured: false, hasSuggestion: false });
    expect(highlightStage(states)).toBe(0); // measuring (current)
  });

  it('falls back to the last done stage', () => {
    const states = deriveLifecycle({ measured: true, hasSuggestion: true, applyProgress: 'APPLIED', appliedOpen: true, hasCooldown: true, cooldownEnded: true });
    expect(highlightStage(states)).toBe(4); // watching (done)
  });
});
