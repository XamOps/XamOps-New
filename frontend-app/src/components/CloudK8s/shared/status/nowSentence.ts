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

export type NowPage = 'automation-overview' | 'workloads' | 'workload-detail' | 'policies';

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
  /**
   * The capacity the ready ones would free, formatted e.g. "1.2 vCPU and 750Mi", used when there is
   * no price (UI v3 plan §1: no dollar figure until prices are read).
   */
  readyFreed?: string;
  /** How many need more CPU. */
  needMore?: number;
  /** True when at least one of the "need more" workloads waits for room on its node. */
  needMoreWaitsForRoom?: boolean;
  /** When any workload is in Apply-automatically mode, the count (replaces the Apply clause). */
  autoApplyCount?: number;
  /** The latest change (BE-5). */
  lastChange?: LastChange;
  /**
   * The newest change endpoint 1 recorded (its `lastAction`), when the full change (BE-5) isn't
   * known: the time, the workload and the result in words.
   */
  latestRecorded?: { time: string; workload: string; result: string };
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
  /** The capacity applying the ready ones would free, formatted; used when there is no price. */
  readyFreed?: string;
  /** A workload that needs more CPU but whose node has no free CPU, and the one to apply first. */
  needsRoom?: { workload: string; freeingWorkload: string; freed: string };
  /** A workload already resized, with the time and, when priced, its in-place monthly saving. */
  resized?: { workload: string; time: string; money?: string };
}

/**
 * Workload detail (glossary §3 "Workload detail"). Each case is filled only from what answered:
 * endpoint 1 (the workload, its suggestion, its latest recorded change) and this session's apply
 * reply. Money, node room and the kubelet's numbers (BE-4, BE-12) are not read yet, so their
 * clauses are left out rather than guessed.
 */
export interface WorkloadDetailInputs {
  /** The workload's name. `undefined` = endpoint 1 failed. */
  workload?: string;
  /** This session's live apply, when Kubernetes confirmed it. */
  applied?: {
    time?: string;
    /** The first moved request, e.g. { resource: 'CPU request', from: '100m', to: '25m' }. */
    change?: { resource: string; from: string; to: string };
    /** The limit that moved with it, when it did. */
    limit?: { from: string; to: string };
    pods?: number;
    restartFree?: boolean | null;
    /** When XamOps may suggest the next change (the change time plus the wait). */
    next?: string;
  };
  /** This session's apply, deferred by Kubernetes. */
  deferred?: { suggests?: string; limit?: string; putBack?: boolean | null };
  /** A suggestion waiting for Apply. */
  ready?: { from: string; to: string; direction: 'freeing' | 'adding'; amount?: string; pods?: number };
  /** Still measuring: how much of the window it has. */
  measuring?: { covered?: string; window?: string };
  /** The latest change endpoint 1 recorded, when this session made none. */
  lastChange?: { time: string; result: string; next?: string };
  /** The workload's status words, used when no other case applies. */
  status?: { label: string; detail?: string };
}

/** Workload autoscaler › Policies: the rules XamOps uses today, from endpoint 1. */
export interface PoliciesInputs {
  /** How many workloads endpoint 1 lists. `undefined` = endpoint 1 failed. */
  workloads?: number;
  /** The most common mode, in words, and how many workloads use it. */
  commonMode?: { mode: string; count: number };
  /** How many workloads have their own saved rules. */
  ownRules?: number;
  /** How many are set to Apply automatically. */
  automatic?: number;
  /** How many a rule on the page leaves alone (a skip reason, an HPA, unset requests). */
  leftAlone?: number;
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
  } else if (inputs.ready !== undefined && inputs.ready > 0 && inputs.readyFreed) {
    clauses.push(`${inputs.ready} can be resized now, freeing ${inputs.readyFreed}.`);
    chips.push(CHIP_WARN(`${inputs.ready} can be resized`));
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
  } else if (inputs.latestRecorded) {
    const r = inputs.latestRecorded;
    clauses.push(`Last change: ${r.time}, ${r.workload}: ${r.result}.`);
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
  } else if (inputs.ready !== undefined && inputs.ready > 0 && inputs.readyNames?.length && inputs.readyFreed) {
    const verb = inputs.ready === 1 ? 'is' : 'are';
    clauses.push(`${inputs.ready} ${verb} over-provisioned and can be resized now: ${inputs.readyNames.join(', ')}, freeing ${inputs.readyFreed}.`);
    chips.push(CHIP_WARN(`${inputs.ready} can be resized`));
  }

  if (inputs.needsRoom) {
    clauses.push(`${inputs.needsRoom.workload} needs more CPU, but its node has no free CPU: apply ${inputs.needsRoom.freeingWorkload} first, which frees ${inputs.needsRoom.freed} there.`);
    chips.push(CHIP_WARN(`${inputs.needsRoom.workload}: needs room`));
  }

  clauses.push('Nothing changes until you click Apply.');

  if (inputs.resized) {
    const r = inputs.resized;
    clauses.push(r.money ? `${r.workload} was resized at ${r.time} and saves ${r.money}.` : `${r.workload} was resized at ${r.time}.`);
    chips.push(CHIP_GOOD(`${r.workload}: applied`));
  }

  return { clauses, chips: chips.slice(0, 5) };
}

const CHIP_WATCH = (next: string): NowChip => ({ tone: 'info', icon: 'mdi:eye-outline', label: `Watching until ${next}` });

/** Workload detail (glossary §3): one case, from the reply first, then endpoint 1. */
export function workloadDetailSentence(inputs: WorkloadDetailInputs): NowSentence {
  const clauses: string[] = [];
  const chips: NowChip[] = [];
  const name = inputs.workload;

  if (name === undefined) {
    clauses.push("XamOps couldn't read this workload just now.");
    return { clauses, chips };
  }

  if (inputs.applied) {
    const a = inputs.applied;
    const when = a.time ? ` ${a.time}` : '';
    const what = a.change ? `: ${a.change.resource} ${a.change.from} → ${a.change.to}` : '';
    const limit = a.change && a.limit ? ` and limit ${a.limit.from} → ${a.limit.to}` : '';
    const pods = a.pods !== undefined ? ` on all ${a.pods} pod${a.pods === 1 ? '' : 's'}` : '';
    const restart = a.restartFree === true ? ', in place, with no restart' : a.restartFree === false ? ', restart detected' : ', in place';
    clauses.push(`Resized${when}${what}${limit}${pods}${restart}.`);
    chips.push(
      a.restartFree === false
        ? { tone: 'bad', icon: 'mdi:alert-octagon-outline', label: `${name}: restart detected` }
        : CHIP_GOOD(`${name}: applied`),
    );
    if (a.next) {
      clauses.push(`XamOps waits until ${a.next} before it suggests another change.`);
      chips.push(CHIP_WATCH(a.next));
    }
    return { clauses, chips: chips.slice(0, 5) };
  }

  if (inputs.deferred) {
    const d = inputs.deferred;
    const suggests = d.suggests ? ` XamOps suggests ${d.suggests}${d.limit ? ` (limit ${d.limit})` : ''}.` : '';
    clauses.push(`${name}:${suggests} Kubernetes deferred the change: its node had no free room.`);
    if (d.putBack === true) clauses.push('XamOps put the pod back. Nothing changed.');
    else if (d.putBack === false) clauses.push('The new size may still be applied later.');
    chips.push(CHIP_WARN('Deferred: no room on its node'));
    return { clauses, chips };
  }

  if (inputs.measuring) {
    const m = inputs.measuring;
    const covered = m.covered && m.window ? `: it has ${m.covered} of the ${m.window} it needs` : '';
    clauses.push(`XamOps is still measuring ${name}${covered}.`);
    chips.push(CHIP_INFO('Measuring'));
  } else if (inputs.ready) {
    const r = inputs.ready;
    const across = r.amount ? `, ${r.direction} ${r.amount}${r.pods !== undefined ? ` across ${r.pods} pod${r.pods === 1 ? '' : 's'}` : ''}` : '';
    clauses.push(`XamOps suggests ${r.from} → ${r.to} per pod${across}.`);
    clauses.push('Nothing changes until you click Apply.');
    chips.push({ tone: 'warn', icon: 'mdi:lightbulb-on-outline', label: 'Suggestion ready' });
  } else if (inputs.status) {
    clauses.push(`${name}: ${inputs.status.label}.${inputs.status.detail ? ` ${inputs.status.detail}` : ''}`);
  }

  if (inputs.lastChange) {
    const c = inputs.lastChange;
    clauses.push(`Last change XamOps recorded: ${c.time}, ${c.result}.`);
    if (c.next) {
      clauses.push(`XamOps waits until ${c.next} before it suggests another change.`);
      chips.push(CHIP_WATCH(c.next));
    }
  }

  return { clauses, chips: chips.slice(0, 5) };
}

/** Workload autoscaler › Policies: what the rules do today, from endpoint 1. */
export function policiesSentence(inputs: PoliciesInputs): NowSentence {
  const clauses: string[] = [];
  const chips: NowChip[] = [];
  const n = inputs.workloads;

  if (n === undefined) {
    clauses.push("XamOps couldn't read its rules just now.");
    return { clauses, chips };
  }
  if (n === 0) {
    clauses.push('There are no workloads for these rules to apply to yet.');
    return { clauses, chips };
  }

  if (inputs.commonMode) {
    const { mode, count } = inputs.commonMode;
    clauses.push(count === n ? `All ${n} workloads use ${mode}.` : `${count} of ${n} workloads use ${mode}.`);
  }
  if (inputs.ownRules !== undefined && inputs.ownRules > 0) {
    clauses.push(`${inputs.ownRules} ${inputs.ownRules === 1 ? 'has its' : 'have their'} own saved rules.`);
  }
  if (inputs.automatic !== undefined && inputs.automatic > 0) {
    clauses.push(`${inputs.automatic} ${inputs.automatic === 1 ? 'is' : 'are'} set to Apply automatically.`);
    chips.push({ tone: 'good', icon: 'mdi:robot-outline', label: `Apply automatically: ${inputs.automatic}` });
  } else {
    clauses.push('Nothing changes until someone clicks Apply.');
  }
  if (inputs.leftAlone !== undefined && inputs.leftAlone > 0) {
    clauses.push(`${inputs.leftAlone} ${inputs.leftAlone === 1 ? 'is' : 'are'} left alone by a rule below.`);
    chips.push({ tone: 'off', icon: 'mdi:minus-circle-outline', label: `Left alone: ${inputs.leftAlone}` });
  }

  return { clauses, chips: chips.slice(0, 5) };
}

export function nowSentence(page: 'automation-overview', inputs: AutomationOverviewInputs): NowSentence;
export function nowSentence(page: 'workloads', inputs: WorkloadsInputs): NowSentence;
export function nowSentence(page: 'workload-detail', inputs: WorkloadDetailInputs): NowSentence;
export function nowSentence(page: 'policies', inputs: PoliciesInputs): NowSentence;
export function nowSentence(
  page: NowPage,
  inputs: AutomationOverviewInputs | WorkloadsInputs | WorkloadDetailInputs | PoliciesInputs,
): NowSentence {
  switch (page) {
    case 'automation-overview':
      return automationOverviewSentence(inputs as AutomationOverviewInputs);
    case 'workloads':
      return workloadsSentence(inputs as WorkloadsInputs);
    case 'workload-detail':
      return workloadDetailSentence(inputs as WorkloadDetailInputs);
    case 'policies':
      return policiesSentence(inputs as PoliciesInputs);
  }
}
