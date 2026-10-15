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

export type NowPage =
  | 'automation-overview'
  | 'workloads'
  | 'workload-detail'
  | 'policies'
  | 'hpa'
  | 'vpa'
  | 'node-autoscaler'
  | 'activity';

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

/**
 * Automation › HPA (glossary-additions §3 "Node autoscaler, HPA, VPA, Activity…": say when an HPA is
 * at its maximum). The HPAs and their live numbers come from the XamOps agent; XamOps's decisions and
 * the conflicts from endpoint 1. How long an HPA has been at its maximum needs its history (Phase 2),
 * so that clause is left out rather than guessed.
 */
export interface HpaInputs {
  cluster: string;
  /** The HPAs the XamOps agent reported. `undefined` = it reported no HPA list. */
  hpas?: ReadonlyArray<{ name: string; namespace: string }>;
  /** HPAs running their maximum, with CPU now and its target when the agent reported both. */
  atMax?: ReadonlyArray<{ name: string; max: number; cpuNowPct?: number; cpuTargetPct?: number }>;
  /** HPAs whose ScalingActive condition is False. */
  notScaling?: readonly string[];
  /** XamOps's HPA decisions (endpoint 1). `undefined` = endpoint 1 failed. */
  engine?: { evaluated: boolean; suggestions: number };
  /** Workloads whose CPU XamOps leaves to their HPA (HPA_CPU_MANAGED). */
  cpuLeftToHpa?: readonly string[];
  /** HPA workloads whose pods a Kubernetes VPA also changes. `undefined` = endpoint 1 failed. */
  conflicts?: number;
}

/** Automation › VPA (say whether any VPA changes pods), from endpoint 1. */
export interface VpaInputs {
  /** Analysed workloads a Kubernetes VPA targets. `undefined` = endpoint 1 failed. */
  vpas?: number;
  /** Of those, the ones whose VPA changes pods (VPA_MANAGED). */
  changingPods?: number;
  /** Of those, the ones in Off mode. */
  off?: number;
  /** Workloads whose VPA XamOps can't read (VPA_NOT_READABLE). */
  unreadable?: number;
  /** CPU per pod within 20% of XamOps's, over the workloads both size. */
  agreement?: { agree: number; compared: number };
}

/** Automation › Node autoscaler (say who changes the node count, requested and used, idle nodes). */
export interface NodeAutoscalerInputs {
  cluster: string;
  /** Who adds and removes nodes, from the XamOps agent's data. `undefined` = it reported nothing. */
  provisioners?: { karpenter: boolean; clusterAutoscaler: boolean };
  /** The nodes the XamOps agent reported, by capacity type. */
  nodes?: { total: number; onDemand: number; spot: number; unknown: number };
  /** CPU requested and used, % of what the nodes give pods. */
  cpu?: { requestedPct?: number; usedPct?: number };
  /** Pods waiting for a node. */
  waiting?: number;
  /** Idle nodes, as the automation agent reported them. `undefined` = not reported. */
  idle?: number;
  /** The automation agent. `undefined` = not checked. */
  automationAgent?: 'installed' | 'stale' | 'not-installed';
}

/** Automation › Activity (give what XamOps did today, by result), over each workload's latest change. */
export interface ActivityInputs {
  /** Workloads with a recorded latest change. `undefined` = endpoint 1 failed. */
  recorded?: number;
  /** Today's latest changes, by result. */
  today?: { applied: number; deferred: number; refused: number; failed: number; testRuns: number };
  /** Workloads whose latest change was applied live and is in place now. */
  inPlace?: readonly string[];
  /** Workloads whose latest change detected a restart. */
  restarted?: readonly string[];
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

const count = (n: number, one: string, many = `${one}s`): string => `${n} ${n === 1 ? one : many}`;

/** "a", "a and b", "a, b and c". */
const listWords = (names: readonly string[]): string =>
  names.length <= 1 ? (names[0] ?? '') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;

/** Automation › HPA: how many HPAs, which is at its maximum, what XamOps suggests, conflicts. */
export function hpaSentence(inputs: HpaInputs): NowSentence {
  const clauses: string[] = [];
  const chips: NowChip[] = [];
  const { hpas } = inputs;

  if (hpas === undefined) {
    clauses.push("XamOps couldn't read this cluster's HPAs just now.");
  } else if (hpas.length === 0) {
    clauses.push(`No HPAs on ${inputs.cluster}.`);
  } else if (hpas.length === 1) {
    clauses.push(`1 HPA on ${inputs.cluster}: ${hpas[0].name} in ${hpas[0].namespace}.`);
  } else {
    clauses.push(`${hpas.length} HPAs on ${inputs.cluster}.`);
  }

  const atMax = hpas === undefined ? [] : inputs.atMax ?? [];
  if (atMax.length === 1) {
    const h = atMax[0];
    const wants = h.cpuNowPct !== undefined && h.cpuTargetPct !== undefined && h.cpuNowPct > h.cpuTargetPct
      ? `, with CPU at ${h.cpuNowPct}% against a ${h.cpuTargetPct}% target, so it would add pods if it could`
      : '';
    clauses.push(`${h.name} is at its maximum of ${count(h.max, 'replica')}${wants}.`);
    chips.push({ tone: 'warn', icon: 'mdi:arrow-collapse-up', label: `${h.name}: at its maximum` });
  } else if (atMax.length > 1) {
    clauses.push(`${atMax.length} are at their maximum: ${listWords(atMax.map((h) => h.name))}.`);
    chips.push({ tone: 'warn', icon: 'mdi:arrow-collapse-up', label: `${atMax.length} at their maximum` });
  }

  const notScaling = hpas === undefined ? [] : inputs.notScaling ?? [];
  if (notScaling.length > 0) {
    clauses.push(`${listWords(notScaling)} ${notScaling.length === 1 ? "isn't" : "aren't"} scaling: Kubernetes can't read the metric.`);
    chips.push({ tone: 'bad', icon: 'mdi:alert-octagon-outline', label: `${notScaling.length} not scaling` });
  }

  if (inputs.engine === undefined) {
    clauses.push("XamOps couldn't read its HPA decisions just now.");
  } else if (!inputs.engine.evaluated) {
    clauses.push('XamOps HPA suggestions are off in this environment.');
  } else if (inputs.engine.suggestions > 0) {
    clauses.push(`XamOps suggests a change to ${count(inputs.engine.suggestions, 'HPA')}. Nothing changes until you click Apply.`);
    chips.push({ tone: 'warn', icon: 'mdi:lightbulb-on-outline', label: count(inputs.engine.suggestions, 'suggestion') });
  } else {
    clauses.push('XamOps suggests no HPA change.');
  }

  const cpu = inputs.cpuLeftToHpa ?? [];
  if (cpu.length === 1) clauses.push(`XamOps leaves ${cpu[0]}'s CPU alone because its HPA scales on it.`);
  else if (cpu.length > 1) clauses.push(`XamOps leaves CPU alone on ${cpu.length} workloads whose HPA scales on it.`);

  if (inputs.conflicts !== undefined) {
    if (inputs.conflicts > 0) {
      clauses.push(`${count(inputs.conflicts, 'conflict')} with XamOps: a Kubernetes VPA also changes ${inputs.conflicts === 1 ? 'its' : 'their'} pods.`);
      chips.push({ tone: 'bad', icon: 'mdi:alert-octagon-outline', label: count(inputs.conflicts, 'conflict') });
    } else {
      chips.push(CHIP_GOOD('No conflicts with XamOps'));
    }
  }

  return { clauses, chips: chips.slice(0, 5) };
}

/** Automation › VPA: how many VPAs, whether any changes pods, and how far they agree with XamOps. */
export function vpaSentence(inputs: VpaInputs): NowSentence {
  const clauses: string[] = [];
  const chips: NowChip[] = [];
  const n = inputs.vpas;

  if (n === undefined) {
    clauses.push("XamOps couldn't read the Kubernetes VPAs just now.");
    return { clauses, chips };
  }

  const unreadable = inputs.unreadable ?? 0;
  if (n === 0) {
    if (unreadable === 0) clauses.push('No Kubernetes VPA targets a workload XamOps analyses.');
  } else {
    const allOff = inputs.off !== undefined && inputs.off === n ? ', all Off' : '';
    clauses.push(`${count(n, 'analysed workload')} ${n === 1 ? 'has' : 'have'} a Kubernetes VPA${allOff}.`);
  }

  if (inputs.changingPods !== undefined && n > 0) {
    if (inputs.changingPods > 0) {
      const k = inputs.changingPods;
      clauses.push(`${k} of them ${k === 1 ? 'changes' : 'change'} pods, so XamOps leaves ${k === 1 ? 'that workload' : 'those workloads'} alone.`);
      chips.push({ tone: 'bad', icon: 'mdi:alert-octagon-outline', label: `${count(k, 'VPA')} ${k === 1 ? 'changes' : 'change'} pods` });
    } else {
      clauses.push(`None of them changes pods, so nothing conflicts with XamOps.`);
      chips.push(CHIP_GOOD('No conflicts'));
    }
  }

  if (unreadable > 0) {
    clauses.push(`XamOps can't read the VPA for ${count(unreadable, 'workload')}, so it leaves ${unreadable === 1 ? 'its' : 'their'} requests alone.`);
    chips.push({ tone: 'warn', icon: 'mdi:eye-off-outline', label: `${unreadable} not readable` });
  }

  if (inputs.agreement && inputs.agreement.compared > 0) {
    const { agree, compared } = inputs.agreement;
    clauses.push(`On CPU the VPA agrees with XamOps within 20% for ${agree} of the ${count(compared, 'workload')} both size.`);
  }

  return { clauses, chips: chips.slice(0, 5) };
}

/** Automation › Node autoscaler: who changes the node count, how full the nodes are, what waits. */
export function nodeAutoscalerSentence(inputs: NodeAutoscalerInputs): NowSentence {
  const clauses: string[] = [];
  const chips: NowChip[] = [];
  const p = inputs.provisioners;

  if (p === undefined) {
    clauses.push("XamOps couldn't read who changes the node count just now.");
  } else {
    if (p.karpenter && p.clusterAutoscaler) {
      clauses.push(`Karpenter and the Cluster Autoscaler are both installed on ${inputs.cluster}, so they can fight over the same nodes.`);
    } else if (p.karpenter) {
      clauses.push(`Karpenter adds and removes nodes on ${inputs.cluster}.`);
    } else if (p.clusterAutoscaler) {
      clauses.push(`The Cluster Autoscaler adds and removes nodes on ${inputs.cluster}.`);
    } else {
      clauses.push(`Nothing adds or removes nodes on ${inputs.cluster} today.`);
    }
    chips.push({ tone: p.karpenter ? 'good' : 'off', icon: p.karpenter ? 'mdi:check-circle' : 'mdi:minus-circle-outline', label: `Karpenter: ${p.karpenter ? 'installed' : 'not installed'}` });
    chips.push({
      tone: p.clusterAutoscaler ? 'good' : 'off',
      icon: p.clusterAutoscaler ? 'mdi:check-circle' : 'mdi:minus-circle-outline',
      label: `Cluster Autoscaler: ${p.clusterAutoscaler ? 'installed' : 'not installed'}`,
    });
  }

  const nodes = inputs.nodes;
  if (nodes && nodes.total > 0) {
    const kinds = [`${nodes.onDemand} on-demand`, `${nodes.spot} Spot`];
    if (nodes.unknown > 0) kinds.push(`${nodes.unknown} with no capacity type`);
    clauses.push(`${count(nodes.total, 'node')}: ${kinds.join(', ')}.`);
  }

  const cpu = inputs.cpu;
  if (cpu?.requestedPct !== undefined) {
    const used = cpu.usedPct !== undefined ? ` and use ${Math.round(cpu.usedPct)}%` : '';
    clauses.push(`Pods reserve ${Math.round(cpu.requestedPct)}% of the nodes' CPU${used}.`);
  }

  if (inputs.idle !== undefined) {
    clauses.push(inputs.idle === 0 ? 'No node is idle.' : `${count(inputs.idle, 'node')} ${inputs.idle === 1 ? 'is' : 'are'} idle.`);
  }

  if (inputs.waiting !== undefined && inputs.waiting > 0) {
    clauses.push(`${count(inputs.waiting, 'pod')} ${inputs.waiting === 1 ? 'waits' : 'wait'} for a node.`);
  }

  if (inputs.automationAgent === 'installed') chips.push(CHIP_GOOD('Automation agent: installed'));
  else if (inputs.automationAgent === 'stale') chips.push({ tone: 'warn', icon: 'mdi:clock-alert-outline', label: 'Automation agent: no recent report' });
  else if (inputs.automationAgent === 'not-installed') chips.push({ tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Automation agent: not installed' });

  if (inputs.waiting !== undefined && inputs.waiting > 0) {
    chips.push({ tone: 'bad', icon: 'mdi:alert-octagon-outline', label: `${count(inputs.waiting, 'pod')} waiting for a node` });
  }

  return { clauses, chips: chips.slice(0, 5) };
}

/** Automation › Activity: what XamOps did today, by result, from each workload's latest change. */
export function activitySentence(inputs: ActivityInputs): NowSentence {
  const clauses: string[] = [];
  const chips: NowChip[] = [];

  if (inputs.recorded === undefined) {
    clauses.push("XamOps couldn't read its recorded changes just now.");
    return { clauses, chips };
  }
  if (inputs.recorded === 0) {
    clauses.push("XamOps hasn't recorded a change on this cluster yet.");
    return { clauses, chips };
  }

  const t = inputs.today;
  if (t) {
    const parts: string[] = [];
    if (t.applied > 0) parts.push(`${t.applied} applied`);
    if (t.deferred > 0) parts.push(`${t.deferred} deferred`);
    if (t.refused > 0) parts.push(`${t.refused} refused`);
    if (t.failed > 0) parts.push(`${t.failed} failed`);
    if (t.testRuns > 0) parts.push(count(t.testRuns, 'test run'));
    clauses.push(parts.length > 0 ? `Today, by each workload's latest change: ${listWords(parts)}.` : "No workload's latest change is from today.");
    if (t.applied > 0) chips.push(CHIP_GOOD(`${t.applied} applied today`));
    if (t.deferred > 0) chips.push(CHIP_WARN(`${t.deferred} deferred today`));
    if (t.refused + t.failed > 0) chips.push({ tone: 'bad', icon: 'mdi:alert-octagon-outline', label: `${t.refused + t.failed} refused or failed today` });
  }

  const inPlace = inputs.inPlace ?? [];
  if (inPlace.length > 0) {
    clauses.push(`${listWords(inPlace)} ${inPlace.length === 1 ? 'is' : 'are'} in place now.`);
  }

  const restarted = inputs.restarted ?? [];
  if (restarted.length > 0) {
    clauses.push(`Restart detected: ${listWords(restarted)}.`);
    chips.push({ tone: 'bad', icon: 'mdi:alert-octagon-outline', label: `Restart detected: ${restarted.length}` });
  }

  return { clauses, chips: chips.slice(0, 5) };
}

export function nowSentence(page: 'automation-overview', inputs: AutomationOverviewInputs): NowSentence;
export function nowSentence(page: 'workloads', inputs: WorkloadsInputs): NowSentence;
export function nowSentence(page: 'workload-detail', inputs: WorkloadDetailInputs): NowSentence;
export function nowSentence(page: 'policies', inputs: PoliciesInputs): NowSentence;
export function nowSentence(page: 'hpa', inputs: HpaInputs): NowSentence;
export function nowSentence(page: 'vpa', inputs: VpaInputs): NowSentence;
export function nowSentence(page: 'node-autoscaler', inputs: NodeAutoscalerInputs): NowSentence;
export function nowSentence(page: 'activity', inputs: ActivityInputs): NowSentence;
export function nowSentence(
  page: NowPage,
  inputs:
    | AutomationOverviewInputs
    | WorkloadsInputs
    | WorkloadDetailInputs
    | PoliciesInputs
    | HpaInputs
    | VpaInputs
    | NodeAutoscalerInputs
    | ActivityInputs,
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
    case 'hpa':
      return hpaSentence(inputs as HpaInputs);
    case 'vpa':
      return vpaSentence(inputs as VpaInputs);
    case 'node-autoscaler':
      return nodeAutoscalerSentence(inputs as NodeAutoscalerInputs);
    case 'activity':
      return activitySentence(inputs as ActivityInputs);
  }
}
