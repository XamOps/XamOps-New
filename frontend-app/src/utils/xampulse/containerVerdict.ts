/**
 * Container 360's verdict: is this container healthy, why not, and what to do, from the container summary plus the
 * error count in its logs. The backend's findings (throttling, memory against its limit, OOM, restart loops) come first;
 * this adds what they do not cover: errors in the logs and restarts below the loop threshold.
 */
import type { XpLimitFinding } from '../../types/xampulse';
import type { Verdict } from '../../components/XamPulse/ui/ui';
import { shortDuration } from '../../components/XamPulse/charts/TimeSeriesChart';
import { formatBytes } from './format';
import { p95 } from './cpuVerdict';

type Series = Record<string, [number, number | null][]>;

/** Docker reports the host's memory as a container's limit when none is set; same 97% rule as the backend. */
export const NO_LIMIT_SHARE = 0.97;

export function isHostSizedLimit(limitBytes: number | null | undefined, hostBytes: number | null | undefined): boolean | null {
  if (limitBytes == null || hostBytes == null || limitBytes <= 0 || hostBytes <= 0) return null;
  return limitBytes >= hostBytes * NO_LIMIT_SHARE;
}

export interface ContainerInput {
  series: Series;
  findings: XpLimitFinding[];
  restarts?: number | null;
  /** Container ids (deploys) seen for this name in the range. */
  versions: number;
  /** ERROR + FATAL lines in the range; null while unknown. */
  errors?: number | null;
}

const vals = (m: Series, key: string): number[] => (m[key] ?? []).map((p) => p[1]).filter((v): v is number => v != null && Number.isFinite(v));
const last = (xs: number[]): number | null => (xs.length ? xs[xs.length - 1] : null);
const avg = (xs: number[]): number | null => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);
/**
 * Percent of one core, with enough digits to tell small values apart: an idle service at 0.04% must not read "0.0%",
 * and chart ticks of 0.0125 / 0.025 / 0.05 must not all read "0%". Two significant digits below 1%, one decimal below 10%.
 */
export function formatCorePct(v: number): string {
  const a = Math.abs(v);
  if (a === 0) return '0%';
  if (a >= 10) return `${Math.round(v)}%`;
  if (a >= 1) return `${v.toFixed(1)}%`;
  if (a < 0.001) return '<0.001%';
  return `${Number(v.toPrecision(2))}%`;
}
const cores = formatCorePct;
const plural = (n: number, one: string, many = `${one}s`): string => `${n.toLocaleString()} ${n === 1 ? one : many}`;

export function memoryLimitState(series: Series): { limit: number | null; host: number | null; noLimit: boolean } {
  const limit = last(vals(series, 'memory_limit'));
  const host = last(vals(series, 'host_memory'));
  return { limit, host, noLimit: limit == null || limit <= 0 || isHostSizedLimit(limit, host) === true };
}

/**
 * One problem the verdict found, most severe first. `code` is the backend finding's code, or `restarts` / `log_errors`
 * for the two this module adds; `evidence` is the finding's measured values, if any.
 */
export interface ContainerIssue { level: 'CRIT' | 'WARN'; code: string; title: string; cause: string; next: string; evidence: Record<string, number> }

/** The verdict plus its issues one by one, so a page can show each problem once instead of the merged "Also:" line. */
export interface ContainerVerdict extends Verdict { issues: ContainerIssue[] }

export function containerVerdict(c: ContainerInput): ContainerVerdict {
  const cpu = vals(c.series, 'cpu');
  const mem = vals(c.series, 'memory');
  if (!cpu.length && !mem.length) {
    return { level: 'NO_DATA', title: 'No container metrics in this range', cause: 'The agent sent no CPU or memory samples for this container in the selected range.', next: 'Pick a longer range, or check that Docker collection is on for this host (Setup).', facts: [], issues: [] };
  }
  const { limit, host, noLimit } = memoryLimitState(c.series);
  const memNow = last(mem);
  const throttle = vals(c.series, 'throttled_pct');
  const uptimeSec = last(vals(c.series, 'uptime'));
  const restarts = c.restarts ?? null;
  const errors = c.errors ?? null;

  // Problems, most severe first. Backend findings carry their own wording and fix.
  type Issue = ContainerIssue & { short: string };
  const issues: Issue[] = [];
  const rank = { CRITICAL: 0, WARNING: 1, INFO: 2 } as const;
  c.findings.slice().sort((a, b) => rank[a.severity] - rank[b.severity]).forEach((f) => {
    if (f.severity !== 'INFO') issues.push({ level: f.severity === 'CRITICAL' ? 'CRIT' : 'WARN', code: f.code, title: f.title, cause: f.detail, next: f.fix, evidence: f.evidence ?? {}, short: f.title });
  });
  const restartLoop = c.findings.some((f) => f.code === 'container_restart_loop' || f.code === 'container_oom');
  if (restarts != null && restarts > 0 && !restartLoop) {
    issues.push({ level: 'WARN', code: 'restarts', title: `Restarted ${plural(restarts, 'time')} in this range`, cause: 'Docker restarted the container: it exited (crash, failed health check or kill) and the restart policy started it again.', next: 'Filter the logs below to Error+ around the restart.', evidence: {}, short: `restarted ${plural(restarts, 'time')}` });
  }
  if (errors != null && errors > 0) {
    issues.push({ level: 'WARN', code: 'log_errors', title: `${plural(errors, 'error')} logged in this range`, cause: `The container wrote ${plural(errors, 'ERROR or FATAL line', 'ERROR or FATAL lines')} in the selected range.`, next: 'Read them in Logs below (Error+), newest first; the volume chart shows when they started.', evidence: {}, short: plural(errors, 'error') });
  }
  issues.sort((a, b) => (a.level === b.level ? 0 : a.level === 'CRIT' ? -1 : 1));

  let verdict: Pick<Verdict, 'level' | 'title' | 'cause' | 'next'>;
  if (issues.length) {
    const [top, ...rest] = issues;
    verdict = { level: top.level, title: top.title, cause: rest.length ? `${top.cause} Also: ${rest.map((i) => i.short).join('; ')}.` : top.cause, next: top.next };
  } else {
    verdict = {
      level: 'OK',
      title: 'Running normally',
      cause: `No limits hit, no restarts${errors === 0 ? ' and no errors in its logs' : ''} in this range.${noLimit && memNow != null ? ' It has no memory limit, so it can use all of the host’s memory.' : ''}`,
      next: noLimit ? 'No action needed. Consider a memory limit (--memory) so one container cannot starve the host.' : 'No action needed.',
    };
  }

  const cpuAvg = avg(cpu);
  const cpuPeak = p95(cpu);
  const facts: Verdict['facts'] = [
    ...(cpuAvg != null && cpuPeak != null ? [{ label: 'CPU', value: `${cores(cpuAvg)} avg · ${cores(cpuPeak)} p95 of one core` }] : []),
    ...(memNow != null ? [{ label: 'Memory', value: noLimit
      ? `${formatBytes(memNow)} · no limit${host != null ? ` (host ${formatBytes(host)})` : ''}`
      : `${formatBytes(memNow)} of ${formatBytes(limit)} (${Math.round((memNow / (limit as number)) * 100)}%)` }] : []),
    ...(throttle.length ? [{ label: 'CPU throttled', value: `${Math.round(p95(throttle) as number)}% p95` }] : []),
    ...(uptimeSec != null ? [{ label: 'Up for', value: shortDuration(uptimeSec * 1000) }] : []),
    ...(restarts != null ? [{ label: 'Restarts', value: restarts.toLocaleString() }] : []),
    ...(errors != null ? [{ label: 'Errors', value: errors.toLocaleString() }] : []),
    ...(c.versions > 1 ? [{ label: 'Deploys', value: `${c.versions} versions in range` }] : []),
  ];
  return { ...verdict, facts, issues: issues.map(({ short: _short, ...issue }) => issue) };
}

/**
 * One cell of Container 360's vitals strip: a label, the headline value, one line of context and, where a threshold
 * applies, a tone. `meter` (0..100) draws a usage bar under the value. Tones follow the rest of XamPulse: memory warns
 * at 75% of its limit and is critical at 90%; throttling takes its tone from the backend's finding, never a guess.
 */
export interface ContainerVital { key: string; label: string; value: string; sub: string; tone?: 'warn' | 'crit'; meter?: number }

export function containerVitals(c: ContainerInput): ContainerVital[] {
  const cpu = vals(c.series, 'cpu');
  const mem = vals(c.series, 'memory');
  const throttle = vals(c.series, 'throttled_pct');
  const uptimeSec = last(vals(c.series, 'uptime'));
  const { limit, host, noLimit } = memoryLimitState(c.series);
  const memNow = last(mem);
  const out: ContainerVital[] = [];

  const cpuAvg = avg(cpu);
  const cpuPeak = p95(cpu);
  out.push(cpuAvg != null && cpuPeak != null
    ? { key: 'cpu', label: 'CPU avg', value: cores(cpuAvg), sub: `p95 ${cores(cpuPeak)} of one core` }
    : { key: 'cpu', label: 'CPU avg', value: '—', sub: 'not reported' });

  if (memNow == null) out.push({ key: 'memory', label: 'Memory', value: '—', sub: 'not reported' });
  else if (noLimit) out.push({ key: 'memory', label: 'Memory', value: formatBytes(memNow), sub: host != null ? `no limit · host ${formatBytes(host)}` : 'no limit set' });
  else {
    const pct = (memNow / (limit as number)) * 100;
    out.push({ key: 'memory', label: 'Memory', value: formatBytes(memNow), sub: `of ${formatBytes(limit)} limit · ${Math.round(pct)}%`, meter: Math.min(100, pct), tone: pct >= 90 ? 'crit' : pct >= 75 ? 'warn' : undefined });
  }

  if (throttle.length) {
    const f = c.findings.find((x) => x.code === 'container_throttled');
    out.push({ key: 'throttled', label: 'CPU throttled', value: `${Math.round(p95(throttle) as number)}%`, sub: 'p95 of CPU periods', tone: f ? (f.severity === 'CRITICAL' ? 'crit' : f.severity === 'WARNING' ? 'warn' : undefined) : undefined });
  }
  out.push(uptimeSec != null ? { key: 'uptime', label: 'Up for', value: shortDuration(uptimeSec * 1000), sub: 'since last start' } : { key: 'uptime', label: 'Up for', value: '—', sub: 'not reported' });
  out.push({ key: 'restarts', label: 'Restarts', value: c.restarts == null ? '—' : c.restarts.toLocaleString(), sub: c.restarts == null ? 'not reported' : 'in this range', tone: (c.restarts ?? 0) > 0 ? 'warn' : undefined });
  out.push({ key: 'errors', label: 'Errors', value: c.errors == null ? '—' : c.errors.toLocaleString(), sub: c.errors == null ? 'counting…' : 'ERROR+ lines in range', tone: (c.errors ?? 0) > 0 ? 'warn' : undefined });
  out.push({ key: 'versions', label: 'Versions', value: c.versions.toLocaleString(), sub: c.versions > 1 ? 'redeployed in range' : 'no redeploy' });
  return out;
}
