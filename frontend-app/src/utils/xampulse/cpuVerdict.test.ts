import { describe, expect, it } from 'vitest';
import { cpuVerdict, p95, sustainedSaturation } from './cpuVerdict';

const series = (values: number[]): [number, number][] => values.map((v, i) => [i * 60_000, v]);

describe('cpuVerdict', () => {
  it('says so when there is no CPU data', () => {
    expect(cpuVerdict({}).level).toBe('NO_DATA');
  });

  it('calls it saturated only when 95% is sustained (3 of the last 5), never for one spike', () => {
    expect(sustainedSaturation([50, 99, 50, 50, 50])).toBe(false);
    expect(sustainedSaturation([96, 97, 40, 98, 60])).toBe(true);
    const spike = cpuVerdict({ 'system.cpu.utilization': series([40, 40, 100, 40, 40]) });
    expect(spike.level).not.toBe('CRIT');
    expect(cpuVerdict({ 'system.cpu.utilization': series([90, 96, 97, 99, 98]) }).level).toBe('CRIT');
  });

  it('flags more work than CPUs from load per vCPU (the live instance: 4.24 load on 2 vCPUs)', () => {
    const v = cpuVerdict({
      'system.cpu.utilization': series([78, 80, 81, 79, 80]),
      'system.cpu.load_average.1m': series([4.2, 4.3, 4.24, 4.2, 4.24]),
      'system.cpu.logical.count': series([2, 2, 2, 2, 2]),
      'system.cpu.utilization{state=user}': series([70, 70, 70, 70, 70]),
      'system.cpu.utilization{state=system}': series([8, 8, 8, 8, 8]),
    });
    expect(v.level).toBe('WARN');
    expect(v.title).toBe('More work than CPUs');
    expect(v.cause).toMatch(/Application code/);
    expect(v.facts.find((f) => f.label === 'Load per vCPU')?.value).toBe('2.12 on 2 vCPUs');
  });

  it('names the real cause: AWS steal, disk wait, or kernel time', () => {
    const base = { 'system.cpu.utilization': series([60, 60, 60, 60, 60]) };
    expect(cpuVerdict({ ...base, 'system.cpu.utilization{state=steal}': series([12, 12, 12, 12, 12]) }).cause).toMatch(/steal/);
    expect(cpuVerdict({ ...base, 'system.cpu.utilization{state=iowait}': series([25, 25, 25, 25, 25]) }).next).toMatch(/Storage/);
    expect(cpuVerdict({ ...base, 'system.cpu.utilization{state=system}': series([30, 30, 30, 30, 30]), 'system.cpu.utilization{state=user}': series([20, 20, 20, 20, 20]) }).cause).toMatch(/Kernel/);
  });

  it('never invents 0% for states the host does not report', () => {
    const v = cpuVerdict({ 'system.cpu.utilization': series([20, 20, 20, 20, 20]) });
    expect(v.level).toBe('OK');
    expect(v.facts.map((f) => f.label)).toEqual(['Busy']);
  });

  it('p95 uses nearest rank', () => {
    expect(p95([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20])).toBe(19);
  });
});
