/**
 * The CPU tab's verdict: is CPU the problem, why, and what to do next, from the host-summary series.
 * Saturation uses a sustained rule (like Dynatrace's CPU saturation event, 95% held over several samples), never a
 * single spike. The cause names the dominant CPU state: AWS steal, disk wait, kernel time or application code.
 */
export type CpuLevel = 'CRIT' | 'WARN' | 'OK' | 'NO_DATA';

export interface CpuVerdict {
  level: CpuLevel;
  title: string;
  cause: string;
  next: string;
  facts: { label: string; value: string }[];
}

type Series = Record<string, [number, number | null][]>;

const vals = (m: Series, key: string): number[] => (m[key] ?? []).map((p) => p[1]).filter((v): v is number => v != null && Number.isFinite(v));
const avg = (xs: number[]): number | null => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);
const last = (xs: number[]): number | null => (xs.length ? xs[xs.length - 1] : null);
const pct = (v: number): string => `${v.toFixed(v < 10 ? 1 : 0)}%`;

/** 95th percentile (nearest rank). */
export function p95(xs: number[]): number | null {
  if (!xs.length) return null;
  const s = xs.slice().sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.ceil(0.95 * s.length) - 1)];
}

/** Sustained saturation: at least 3 of the last 5 samples at or above 95%. */
export function sustainedSaturation(total: number[]): boolean {
  const tail = total.slice(-5);
  return tail.length >= 3 && tail.filter((v) => v >= 95).length >= 3;
}

export function cpuVerdict(m: Series): CpuVerdict {
  const total = vals(m, 'system.cpu.utilization');
  if (!total.length) {
    return { level: 'NO_DATA', title: 'No CPU data in this range', cause: 'The agent sent no CPU samples for the selected time range.', next: 'Pick a longer range or check the agent in Setup.', facts: [] };
  }
  const vcpus = last(vals(m, 'system.cpu.logical.count'));
  const load1 = vals(m, 'system.cpu.load_average.1m');
  const loadPerVcpu = vcpus && vcpus > 0 && load1.length ? (avg(load1) as number) / vcpus : null;
  const psi = avg(vals(m, 'xp.pressure{resource=cpu,level=some}'));
  const user = avg(vals(m, 'system.cpu.utilization{state=user}')) ?? 0;
  const system = avg(vals(m, 'system.cpu.utilization{state=system}')) ?? 0;
  // Absent (not 0) when the host does not report the state, so the facts never show an invented 0%.
  const iowaitRaw = avg(vals(m, 'system.cpu.utilization{state=iowait}'));
  const stealRaw = avg(vals(m, 'system.cpu.utilization{state=steal}'));
  const iowait = iowaitRaw ?? 0;
  const steal = stealRaw ?? 0;
  const busyAvg = avg(total) as number;
  const busyPeak = p95(total) as number;

  let level: CpuLevel = 'OK';
  let title = 'Enough CPU headroom';
  if (sustainedSaturation(total)) {
    level = 'CRIT';
    title = 'CPU saturated right now';
  } else if ((loadPerVcpu != null && loadPerVcpu > 1) || (psi != null && psi >= 25)) {
    level = 'WARN';
    title = 'More work than CPUs';
  } else if (busyPeak >= 85) {
    level = 'WARN';
    title = 'Busy at peaks';
  }

  let cause: string;
  let next: string;
  if (steal >= 5) {
    cause = `AWS is taking ${pct(steal)} of the CPU (steal): a noisy neighbour, or burst credits running out on a T-family type.`;
    next = 'Check the AWS tab (CPU credits), or move to a non-burstable instance type.';
  } else if (iowait >= 10) {
    cause = `${pct(iowait)} of CPU time is spent waiting on disk (I/O wait): storage is the bottleneck, not CPU.`;
    next = 'Open Storage: disk throughput, queue and EBS limits.';
  } else if (system >= 15 && system >= user * 0.6) {
    cause = `Kernel time is high (${pct(system)} system): heavy system calls, networking or context switching.`;
    next = 'Check Processes for the busiest program and Network for packet load.';
  } else {
    cause = `Application code is doing most of the work (${pct(user)} user time${system > 0 ? `, ${pct(system)} kernel` : ''}).`;
    next = level === 'OK'
      ? 'No action needed.'
      : 'Find the busiest process (Processes tab), or move to a size with more vCPUs.';
  }

  const facts = [
    { label: 'Busy', value: `${pct(busyAvg)} avg · ${pct(busyPeak)} p95` },
    ...(loadPerVcpu != null ? [{ label: 'Load per vCPU', value: `${loadPerVcpu.toFixed(2)} on ${vcpus} vCPU${vcpus === 1 ? '' : 's'}` }] : []),
    ...(psi != null ? [{ label: 'Tasks waiting for CPU', value: pct(psi) }] : []),
    ...(iowaitRaw != null ? [{ label: 'I/O wait', value: pct(iowaitRaw) }] : []),
    ...(stealRaw != null ? [{ label: 'Steal', value: pct(stealRaw) }] : []),
  ];
  return { level, title, cause, next, facts };
}
