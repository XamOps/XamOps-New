/**
 * The "Now" sentence (design-delta §4.1; glossary §3). A pure function: given a page and the inputs
 * that answered, it returns the clauses that make up the one-sentence summary at the top of an
 * automation page, plus the chips to show under it.
 *
 * The rules it keeps:
 *  - a clause appears only when its input is present (an input is `undefined` when its source failed
 *    or had no answer); the sentence never states a number whose source failed;
 *  - when a part failed, the caller may add a "couldn't read" clause by passing `failed` for it;
 *  - chips are at most 5 (glossary §3).
 *
 * It is a pure function so it unit-tests against fixtures for every clause combination, including
 * today's two real applies.
 */
import type { Tone } from './statusTones';

export type NowPage = 'automation-overview' | 'workloads';

export interface NowChip {
  tone: Tone;
  icon: string;
  label: string;
}

export interface NowSentence {
  /** The ordered clauses; join with a space to render. */
  clauses: string[];
  chips: NowChip[];
}

/** A workload resized / deferred recently, for the "Last change" clause. */
export interface LastChange {
  time: string;
  workload: string;
  resource: string;
  from: string;
  to: string;
  pods: number;
  restartDetected: boolean;
}

export interface AutomationOverviewInputs {
  cluster: string;
  /** How many workloads XamOps is watching. `undefined` = endpoint 1 failed. */
  watching?: number;
  /** How many can be resized now. */
  ready?: number;
  /** Monthly saving if the ready ones are applied, formatted e.g. "$42/mo". */
  readyMoney?: string;
  /** How many need more CPU. */
  needMore?: number;
  /** True when at least one of the "need more" workloads waits for room on its node. */
  needMoreWaitsForRoom?: boolean;
  /** When any workload is in Apply-automatically mode, the count (replaces the Apply clause). */
  autoApplyCount?: number;
  /** The latest change (BE-5). */
  lastChange?: LastChange;
  /** The reason there are no workloads, when `watching` is 0. */
  noneReason?: string;
  /** Parts whose source failed, so their "couldn't read" clause is added and no chip is shown. */
  failed?: ReadonlyArray<'activity'>;
}

export interface WorkloadsInputs {
  /** How many workloads were analysed. `undefined` = endpoint 1 failed. */
  analysed?: number;
  /** The time of the analysis, formatted (IST). */
  analysedAt?: string;
  /** How many are over-provisioned and can be resized now. */
  ready?: number;
  /** The names of the ready workloads. */
  readyNames?: string[];
  /** The monthly saving of applying the ready ones, formatted. */
  readyMoney?: string;
  /** A workload that needs more CPU but whose node has no free CPU, and the one to apply first. */
  needsRoom?: { workload: string; freeingWorkload: string; freed: string };
  /** A workload already resized, with the time and its in-place monthly saving. */
  resized?: { workload: string; time: string; money: string };
}

const CHIP_GOOD = (label: string): NowChip => ({ tone: 'good', icon: 'mdi:check-circle', label });
const CHIP_WARN = (label: string): NowChip => ({ tone: 'warn', icon: 'mdi:clock-alert-outline', label });
const CHIP_INFO = (label: string): NowChip => ({ tone: 'info', icon: 'mdi:progress-clock', label });

/** Automation › Overview (glossary §3). */
export function automationOverviewSentence(inputs: AutomationOverviewInputs): NowSentence {
  const clauses: string[] = [];
  const chips: NowChip[] = [];

  if (inputs.watching === undefined) {
    clauses.push("XamOps couldn't read its workloads just now.");
    return { clauses, chips };
  }

  if (inputs.watching === 0) {
    clauses.push(`XamOps isn't watching any workloads on ${inputs.cluster}: ${inputs.noneReason ?? 'none are eligible'}.`);
    return { clauses, chips };
  }

  clauses.push(`XamOps is watching ${inputs.watching} workloads on ${inputs.cluster}.`);

  if (inputs.ready !== undefined && inputs.ready > 0 && inputs.readyMoney) {
    clauses.push(`${inputs.ready} can be resized now, saving ${inputs.readyMoney}.`);
    chips.push(CHIP_WARN(`${inputs.ready} can be resized, ${inputs.readyMoney}`));
  }

  if (inputs.needMore !== undefined && inputs.needMore > 0) {
    const roomClause = inputs.needMoreWaitsForRoom ? ', which waits for room on its node' : '';
    clauses.push(`${inputs.needMore} need${inputs.needMore === 1 ? 's' : ''} more CPU${roomClause}.`);
    chips.push(CHIP_WARN(`${inputs.needMore} need more CPU`));
  }

  if (inputs.autoApplyCount !== undefined && inputs.autoApplyCount > 0) {
    clauses.push(`XamOps applies suggestions on its own for ${inputs.autoApplyCount} workload${inputs.autoApplyCount === 1 ? '' : 's'}, one at a time.`);
    chips.push(CHIP_INFO(`Applying automatically: ${inputs.autoApplyCount}`));
  } else {
    clauses.push('Nothing changes until you click Apply.');
  }

  if (inputs.failed?.includes('activity')) {
    clauses.push("XamOps couldn't read recent activity just now.");
  } else if (inputs.lastChange) {
    const c = inputs.lastChange;
    const restart = c.restartDetected ? 'restart detected' : 'no restart';
    clauses.push(`Last change: ${c.time}, ${c.workload} ${c.resource} ${c.from} to ${c.to} on ${c.pods} pods, ${restart}.`);
    chips.push(
      c.restartDetected
        ? { tone: 'bad', icon: 'mdi:alert-octagon-outline', label: `${c.workload}: restart detected` }
        : CHIP_GOOD(`${c.workload}: applied`),
    );
  }

  return { clauses, chips: chips.slice(0, 5) };
}

/** Workload autoscaler › Workloads (glossary §3). */
export function workloadsSentence(inputs: WorkloadsInputs): NowSentence {
  const clauses: string[] = [];
  const chips: NowChip[] = [];

  if (inputs.analysed === undefined) {
    clauses.push("XamOps couldn't read its workloads just now.");
    return { clauses, chips };
  }

  clauses.push(`${inputs.analysed} workloads analysed${inputs.analysedAt ? ` at ${inputs.analysedAt}` : ''}.`);

  if (inputs.ready !== undefined && inputs.ready > 0 && inputs.readyNames?.length && inputs.readyMoney) {
    clauses.push(`${inputs.ready} are over-provisioned and can be resized now: ${inputs.readyNames.join(', ')}, saving ${inputs.readyMoney}.`);
    chips.push(CHIP_WARN(`${inputs.ready} can be resized, ${inputs.readyMoney}`));
  }

  if (inputs.needsRoom) {
    clauses.push(`${inputs.needsRoom.workload} needs more CPU, but its node has no free CPU: apply ${inputs.needsRoom.freeingWorkload} first, which frees ${inputs.needsRoom.freed} there.`);
    chips.push(CHIP_WARN(`${inputs.needsRoom.workload}: needs room`));
  }

  clauses.push('Nothing changes until you click Apply.');

  if (inputs.resized) {
    clauses.push(`${inputs.resized.workload} was resized at ${inputs.resized.time} and saves ${inputs.resized.money}.`);
    chips.push(CHIP_GOOD(`${inputs.resized.workload}: applied`));
  }

  return { clauses, chips: chips.slice(0, 5) };
}

export function nowSentence(page: 'automation-overview', inputs: AutomationOverviewInputs): NowSentence;
export function nowSentence(page: 'workloads', inputs: WorkloadsInputs): NowSentence;
export function nowSentence(page: NowPage, inputs: AutomationOverviewInputs | WorkloadsInputs): NowSentence {
  return page === 'automation-overview'
    ? automationOverviewSentence(inputs as AutomationOverviewInputs)
    : workloadsSentence(inputs as WorkloadsInputs);
}
