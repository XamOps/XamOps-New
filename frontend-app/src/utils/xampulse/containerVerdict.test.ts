import { describe, expect, it } from 'vitest';
import { containerVerdict, containerVitals, formatCorePct, isHostSizedLimit, memoryLimitState } from './containerVerdict';

describe('formatCorePct', () => {
  it('keeps small values apart instead of rounding them all to 0%', () => {
    const ticks = [0, 0.0125, 0.025, 0.0375, 0.05].map(formatCorePct);
    expect(new Set(ticks).size).toBe(ticks.length);
    expect(ticks.filter((t) => t === '0%')).toEqual(['0%']);
    expect(ticks[2]).toBe('0.025%');
    expect(ticks[4]).toBe('0.05%');
    expect(formatCorePct(0.42)).toBe('0.42%');
    expect(formatCorePct(0.0004)).toBe('<0.001%');
  });
  it('uses one decimal below 10% and whole numbers above', () => {
    expect(formatCorePct(3)).toBe('3.0%');
    expect(formatCorePct(148.4)).toBe('148%');
  });
});
import type { XpLimitFinding } from '../../types/xampulse';

const GiB = 1024 ** 3;
const pts = (...v: number[]): [number, number][] => v.map((x, i) => [i * 60_000, x]);
const base = { cpu: pts(2, 3, 4), memory: pts(1.5 * GiB, 1.6 * GiB), memory_limit: pts(7.59 * GiB, 7.59 * GiB), host_memory: pts(7.59 * GiB) };
const finding = (severity: XpLimitFinding['severity'], code: string, title: string): XpLimitFinding => ({ severity, code, source: 'CONTAINER', title, detail: `${title} detail.`, fix: `${title} fix.`, evidence: {} });

describe('isHostSizedLimit', () => {
  it('treats a limit equal to (or within 3% of) the host memory as no limit', () => {
    expect(isHostSizedLimit(7.59 * GiB, 7.59 * GiB)).toBe(true);
    expect(isHostSizedLimit(7.4 * GiB, 7.59 * GiB)).toBe(true);
    expect(isHostSizedLimit(2 * GiB, 7.59 * GiB)).toBe(false);
  });
  it('is unknown, not false, when either side is missing', () => {
    expect(isHostSizedLimit(2 * GiB, null)).toBeNull();
    expect(isHostSizedLimit(undefined, 8 * GiB)).toBeNull();
  });
});

describe('memoryLimitState', () => {
  it('says no limit when Docker reports the host memory as the limit', () => {
    expect(memoryLimitState(base).noLimit).toBe(true);
  });
  it('keeps a real limit', () => {
    expect(memoryLimitState({ ...base, memory_limit: pts(2 * GiB) })).toMatchObject({ noLimit: false, limit: 2 * GiB });
  });
});

describe('containerVerdict', () => {
  it('is NO_DATA without CPU or memory samples, never Healthy', () => {
    expect(containerVerdict({ series: {}, findings: [], versions: 1 }).level).toBe('NO_DATA');
  });

  it('is OK with no findings, restarts or errors, and says it has no memory limit', () => {
    const v = containerVerdict({ series: base, findings: [], restarts: 0, errors: 0, versions: 1 });
    expect(v.level).toBe('OK');
    expect(v.cause).toContain('no errors in its logs');
    expect(v.cause).toContain('no memory limit');
    expect(v.facts.find((f) => f.label === 'Memory')?.value).toBe('1.60 GB · no limit (host 7.59 GB)');
  });

  it('does not claim "no errors" while the error count is unknown', () => {
    const v = containerVerdict({ series: base, findings: [], restarts: 0, errors: null, versions: 1 });
    expect(v.level).toBe('OK');
    expect(v.cause).not.toContain('errors');
    expect(v.facts.some((f) => f.label === 'Errors')).toBe(false);
  });

  it('warns on errors in the logs and points to them', () => {
    const v = containerVerdict({ series: base, findings: [], restarts: 0, errors: 5, versions: 1 });
    expect(v.level).toBe('WARN');
    expect(v.title).toBe('5 errors logged in this range');
    expect(v.next).toContain('Logs below');
  });

  it('puts a critical finding first and lists the rest under Also', () => {
    const v = containerVerdict({ series: base, findings: [finding('WARNING', 'container_throttled', 'CPU-throttled in 30% of its scheduling periods'), finding('CRITICAL', 'container_oom', 'Restarted right after memory reached its limit (likely an OOM kill)')], restarts: 1, errors: 2, versions: 2 });
    expect(v.level).toBe('CRIT');
    expect(v.title).toContain('OOM');
    expect(v.next).toBe('Restarted right after memory reached its limit (likely an OOM kill) fix.');
    expect(v.cause).toContain('Also: CPU-throttled in 30%');
    expect(v.cause).toContain('2 errors');
    // The OOM finding already explains the restart: no second "Restarted 1 time" issue.
    expect(v.cause).not.toContain('restarted 1 time');
    expect(v.facts.find((f) => f.label === 'Deploys')?.value).toBe('2 versions in range');
  });

  it('flags a single restart that is below the restart-loop threshold', () => {
    const v = containerVerdict({ series: base, findings: [], restarts: 1, errors: 0, versions: 1 });
    expect(v.level).toBe('WARN');
    expect(v.title).toBe('Restarted 1 time in this range');
  });

  it('shows memory against a real limit as a share', () => {
    const v = containerVerdict({ series: { ...base, memory_limit: pts(2 * GiB) }, findings: [], restarts: 0, errors: 0, versions: 1 });
    expect(v.facts.find((f) => f.label === 'Memory')?.value).toBe('1.60 GB of 2.00 GB (80%)');
    expect(v.next).toBe('No action needed.');
  });

  it('reports CPU as percent of one core and uptime as a duration', () => {
    const v = containerVerdict({ series: { ...base, uptime: pts(3600, 90_000) }, findings: [], restarts: 0, errors: 0, versions: 1 });
    expect(v.facts.find((f) => f.label === 'CPU')?.value).toBe('3.0% avg · 4.0% p95 of one core');
    expect(v.facts.find((f) => f.label === 'Up for')?.value).toBe('1d 1h');
  });

  it('returns each issue once, worst first, with its code, fix and evidence', () => {
    const oom = { ...finding('CRITICAL', 'container_oom', 'OOM'), evidence: { peak_share: 0.97 } };
    const v = containerVerdict({ series: base, findings: [finding('WARNING', 'container_throttled', 'Throttled'), oom], restarts: 1, errors: 2, versions: 1 });
    expect(v.issues.map((i) => [i.level, i.code])).toEqual([['CRIT', 'container_oom'], ['WARN', 'container_throttled'], ['WARN', 'log_errors']]);
    expect(v.issues[0]).toMatchObject({ title: 'OOM', cause: 'OOM detail.', next: 'OOM fix.', evidence: { peak_share: 0.97 } });
    expect(Object.keys(v.issues[0])).not.toContain('short');
  });

  it('has no issues when healthy or without data', () => {
    expect(containerVerdict({ series: base, findings: [], restarts: 0, errors: 0, versions: 1 }).issues).toEqual([]);
    expect(containerVerdict({ series: {}, findings: [], versions: 1 }).issues).toEqual([]);
  });
});

describe('containerVitals', () => {
  const byKey = (vs: ReturnType<typeof containerVitals>) => Object.fromEntries(vs.map((v) => [v.key, v]));

  it('shows memory against a real limit with a meter and a tone at 75% and 90%', () => {
    const at = (gib: number) => byKey(containerVitals({ series: { ...base, memory: pts(gib * GiB), memory_limit: pts(2 * GiB) }, findings: [], versions: 1 })).memory;
    expect(at(1)).toMatchObject({ value: '1.00 GB', sub: 'of 2.00 GB limit · 50%', meter: 50 });
    expect(at(1).tone).toBeUndefined();
    expect(at(1.6).tone).toBe('warn');
    expect(at(1.9).tone).toBe('crit');
  });

  it('says no limit, without a meter, when the limit is the host memory', () => {
    const m = byKey(containerVitals({ series: base, findings: [], versions: 1 })).memory;
    expect(m).toMatchObject({ value: '1.60 GB', sub: 'no limit · host 7.59 GB' });
    expect(m.meter).toBeUndefined();
  });

  it('takes the throttling tone from the backend finding only', () => {
    const series = { ...base, throttled_pct: pts(40, 42) };
    expect(byKey(containerVitals({ series, findings: [], versions: 1 })).throttled.tone).toBeUndefined();
    expect(byKey(containerVitals({ series, findings: [finding('WARNING', 'container_throttled', 'T')], versions: 1 })).throttled).toMatchObject({ value: '42%', tone: 'warn' });
  });

  it('marks unknown errors as counting, not zero, and warns on restarts', () => {
    const v = byKey(containerVitals({ series: base, findings: [], restarts: 2, errors: null, versions: 3 }));
    expect(v.errors).toMatchObject({ value: '—', sub: 'counting…' });
    expect(v.restarts).toMatchObject({ value: '2', tone: 'warn' });
    expect(v.versions).toMatchObject({ value: '3', sub: 'redeployed in range' });
    expect(v.throttled).toBeUndefined();
  });
});
