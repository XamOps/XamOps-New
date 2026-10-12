/**
 * Per-workload lifecycle derivation (design-delta §4.3; glossary §5). Deriving the stage client-side
 * keeps the server contract unchanged, so this is a pure function unit-tested against glossary §5.
 *
 * The five stages are Measuring, Suggestion ready, Applying, Applied and Watching. Each has a status
 * (done / current / problem / todo) and a short label.
 */
import type { Tone } from './statusTones';

export type LifecycleStage = 'measuring' | 'suggestion' | 'applying' | 'applied' | 'watching';

export const LIFECYCLE_STAGES: readonly LifecycleStage[] = [
  'measuring',
  'suggestion',
  'applying',
  'applied',
  'watching',
];

export type StageStatus = 'done' | 'current' | 'problem' | 'todo';

export interface StageState {
  stage: LifecycleStage;
  status: StageStatus;
  /** The tone for the stage's bar/chip. */
  tone: Tone;
}

/** The apply progress for this workload, from BE-11 (glossary §5). */
export type ApplyProgress =
  | 'RUNNING'
  | 'APPLIED'
  | 'DEFERRED'
  | 'PARTIALLY_APPLIED'
  | 'STUCK'
  | 'INFEASIBLE'
  | 'FAILED'
  | 'RESTART_DETECTED'
  | 'NOT_READY';

export interface LifecycleInputs {
  /** True when coverage has reached the engine's minimum (else INSUFFICIENT_HISTORY). */
  measured: boolean;
  /** True when a recommendation exists. */
  hasSuggestion: boolean;
  /** The BE-11 apply progress for this workload, if any. */
  applyProgress?: ApplyProgress;
  /** True when the latest live entry is APPLIED and its interval is still open. */
  appliedOpen?: boolean;
  /** True when the cooldown has ended; false when `cooldownEndsAt` is still in the future. */
  cooldownEnded?: boolean;
  /** True when a cooldown window exists at all (a change has been applied). */
  hasCooldown?: boolean;
}

function statusFor(stage: LifecycleStage, i: LifecycleInputs): StageStatus {
  switch (stage) {
    case 'measuring':
      return i.measured ? 'done' : 'current';
    case 'suggestion':
      return i.hasSuggestion ? 'done' : 'todo';
    case 'applying':
      switch (i.applyProgress) {
        case 'APPLIED':
          return 'done';
        case 'RUNNING':
          return 'current';
        case 'DEFERRED':
        case 'PARTIALLY_APPLIED':
        case 'STUCK':
        case 'INFEASIBLE':
        case 'FAILED':
        case 'RESTART_DETECTED':
        case 'NOT_READY':
          return 'problem';
        default:
          return 'todo';
      }
    case 'applied':
      return i.appliedOpen ? 'done' : 'todo';
    case 'watching':
      if (!i.hasCooldown) return 'todo';
      return i.cooldownEnded ? 'done' : 'current';
  }
}

/** Problems split into warn vs bad per glossary §5 ("Deferred/partial/stuck" warn; the rest bad). */
function problemTone(progress: ApplyProgress | undefined): Tone {
  switch (progress) {
    case 'DEFERRED':
    case 'PARTIALLY_APPLIED':
    case 'STUCK':
      return 'warn';
    default:
      return 'bad';
  }
}

function toneFor(status: StageStatus, i: LifecycleInputs): Tone {
  if (status === 'done') return 'good';
  if (status === 'todo') return 'off';
  if (status === 'problem') return problemTone(i.applyProgress);
  // current
  return 'info';
}

export function deriveLifecycle(inputs: LifecycleInputs): StageState[] {
  return LIFECYCLE_STAGES.map((stage) => {
    const status = statusFor(stage, inputs);
    return { stage, status, tone: toneFor(status, inputs) };
  });
}

/** The index of the stage to highlight: the first problem, else the current, else the last done. */
export function highlightStage(states: StageState[]): number {
  const problem = states.findIndex((s) => s.status === 'problem');
  if (problem >= 0) return problem;
  const current = states.findIndex((s) => s.status === 'current');
  if (current >= 0) return current;
  let lastDone = -1;
  states.forEach((s, i) => {
    if (s.status === 'done') lastDone = i;
  });
  return lastDone;
}
