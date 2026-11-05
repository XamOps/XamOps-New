/**
 * Pure derivations for Automation › Pending actions: grouping, the countdown, the "why" sentence, the
 * confirm sentence and the metric strip. The clock is always injected. Anything the server did not send
 * is a dash, never a guess.
 */
import type { MetricCell } from '../../../../CloudK8s/shared/MetricStrip';
import type { ProposalDto, ProposalListDto } from '../../../../../types/automationProposals';
import { shortNode } from '../node/consolidationDerivations';
import { DASH, actionWord, actorLine } from './proposalCopy';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

export interface ProposalGroups {
  waiting: ProposalDto[];
  refused: ProposalDto[];
  inProgress: ProposalDto[];
  history: ProposalDto[];
}

const ms = (iso: string | null): number | null => {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
};

/** Waiting rows soonest-expiring first; the rest newest first. */
export function groupProposals(items: readonly ProposalDto[]): ProposalGroups {
  const g: ProposalGroups = { waiting: [], refused: [], inProgress: [], history: [] };
  for (const p of items) {
    if (p.status === 'PENDING') g.waiting.push(p);
    else if (p.status === 'REFUSED') g.refused.push(p);
    else if (p.status === 'APPROVED' || p.status === 'DISPATCHED') g.inProgress.push(p);
    else g.history.push(p);
  }
  const at = (p: ProposalDto) => ms(p.decidedAt) ?? ms(p.lastSeenAt) ?? ms(p.proposedAt) ?? 0;
  g.waiting.sort((a, b) => (ms(a.expiresAt) ?? Infinity) - (ms(b.expiresAt) ?? Infinity));
  g.history.sort((a, b) => at(b) - at(a));
  return g;
}

/** "in 1 h 12 min", "in 5 min", "in under a minute", "Expired"; a dash when there is no expiry. */
export function countdownText(expiresAt: string | null, now: number): string {
  const t = ms(expiresAt);
  if (t === null) return DASH;
  const left = t - now;
  if (left <= 0) return 'Expired';
  if (left < MINUTE) return 'in under a minute';
  const days = Math.floor(left / DAY);
  const hours = Math.floor((left % DAY) / HOUR);
  const mins = Math.floor((left % HOUR) / MINUTE);
  if (days > 0) return `in ${days} d ${hours} h`;
  if (hours > 0) return `in ${hours} h ${mins} min`;
  return `in ${mins} min`;
}

export const isExpired = (p: ProposalDto, now: number): boolean => {
  const t = ms(p.expiresAt);
  return t !== null && t <= now;
};

/** True while a consolidation row's dwell has not passed; the Approve control is replaced by words. */
export function notYetApprovable(p: ProposalDto, now: number): boolean {
  const t = ms(p.approvableAfter);
  return t !== null && t > now;
}

export function approvableFromText(p: ProposalDto): string {
  const t = ms(p.approvableAfter);
  return t === null ? DASH : new Date(t).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

const asRecord = (v: unknown): Record<string, unknown> | null =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);

/** A node's short name, or `ns/Kind/name` for a workload. */
export function targetText(p: ProposalDto): string {
  if (!p.targetRef) return DASH;
  return p.targetKind === 'NODE' ? shortNode(p.targetRef) : p.targetRef;
}

interface ContainerResources {
  container: string;
  cpuRequestM: number | null;
  memRequestMi: number | null;
}
function containers(v: unknown): ContainerResources[] {
  if (!Array.isArray(v)) return [];
  return v.flatMap((c) => {
    const r = asRecord(c);
    if (!r || typeof r.container !== 'string') return [];
    return [{ container: r.container, cpuRequestM: num(r.cpuRequestM), memRequestMi: num(r.memRequestMi) }];
  });
}

function workloadWhy(p: ProposalDto): string {
  const cur = containers(p.params?.current);
  const rec = containers(p.params?.recommended);
  const lines: string[] = [];
  for (const r of rec) {
    const c = cur.find((x) => x.container === r.container);
    const parts: string[] = [];
    if (r.cpuRequestM !== null && c?.cpuRequestM != null && r.cpuRequestM !== c.cpuRequestM) {
      parts.push(`CPU request ${c.cpuRequestM}m to ${r.cpuRequestM}m`);
    }
    if (r.memRequestMi !== null && c?.memRequestMi != null && r.memRequestMi !== c.memRequestMi) {
      parts.push(`memory request ${c.memRequestMi}Mi to ${r.memRequestMi}Mi`);
    }
    if (parts.length > 0) lines.push(rec.length > 1 ? `${r.container}: ${parts.join(', ')}` : parts.join(', '));
  }
  if (lines.length === 0) return DASH;
  const text = lines.join('; ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** One sentence for the Why column. Node params are agent-defined, so each field is read defensively. */
export function whyText(p: ProposalDto): string {
  if (p.actionType === 'WORKLOAD_RESIZE') return workloadWhy(p);
  const cpu = num(p.params?.cpuUsagePct);
  const mem = num(p.params?.memUsagePct);
  const moving = num(p.params?.podsToMove);
  if (cpu !== null || mem !== null) {
    const parts: string[] = [];
    if (cpu !== null) parts.push(`CPU usage ${Math.round(cpu)}%`);
    if (mem !== null) parts.push(`memory usage ${Math.round(mem)}%`);
    if (moving !== null) parts.push(`${moving} ${moving === 1 ? 'pod' : 'pods'} can move`);
    return `Node is idle: ${parts.join(', ')}`;
  }
  const fit = asRecord(asRecord(p.evidence)?.fit);
  const summary = typeof fit?.summary === 'string' ? fit.summary.trim() : '';
  if (summary) return summary.charAt(0).toUpperCase() + summary.slice(1);
  return DASH;
}

/** How many pods a drain evicts, when the evidence says. */
export function podsMoving(p: ProposalDto): number | null {
  const moving = num(p.params?.podsToMove);
  if (moving !== null) return moving;
  const moves = asRecord(asRecord(p.evidence)?.fit)?.moves;
  return Array.isArray(moves) ? moves.length : null;
}

/** The exact consequence line shown in the inline Approve confirm. */
export function consequenceText(p: ProposalDto): string {
  const target = targetText(p);
  if (p.dryRun) {
    return `This is a dry run. Approving simulates "${actionWord(p.actionType, p.operation).toLowerCase()}" on ${target} and changes nothing.`;
  }
  if (p.actionType === 'WORKLOAD_RESIZE') {
    return `Applies the new CPU and memory requests shown to ${target}.`;
  }
  if (p.operation === 'CORDON_ONLY') {
    return `Cordons ${target}: no new pods are scheduled there. Nothing is evicted.`;
  }
  const n = podsMoving(p);
  const evicting = n === null ? 'its pods' : `${n} ${n === 1 ? 'pod' : 'pods'}`;
  return `Drains ${target} by evicting ${evicting} through the Eviction API. What happens to the node afterwards depends on the cluster's node-removal actor: ${actorLine(p.evidence)}.`;
}

/** Counts of the strip. Dash while loading or unreadable. */
export function metricCells(list: ProposalListDto | null, now: number): MetricCell[] {
  const ok = list && list.state === 'OK';
  const dash = DASH;
  const week = ok
    ? list.items.filter((p) => p.status === 'EXECUTED' && !p.dryRun && (ms(p.decidedAt) ?? 0) >= now - 7 * DAY).length
    : null;
  return [
    { label: 'Pending', value: ok && list.counts ? list.counts.pending : dash },
    { label: 'Approved or running', value: ok && list.counts ? list.counts.approved + list.counts.dispatched : dash },
    { label: 'Refused', value: ok && list.counts ? list.counts.refused : dash },
    { label: 'Executed in the last 7 days', value: week === null ? dash : `${week}${list?.nextCursor ? '+' : ''}` },
  ];
}
