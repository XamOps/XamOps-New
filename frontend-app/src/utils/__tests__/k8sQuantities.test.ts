import { createElement, type ReactNode } from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }));
vi.mock('../apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../apiClient')>();
  return { ...actual, default: { ...actual.default, get: apiGet } };
});

import {
  DASH,
  ageSortValue,
  formatAge,
  formatBytes,
  formatCpu,
  formatMemoryMiB,
  formatPercent,
  formatUsd,
  parseCpuMillicores,
  parseMemoryBytes,
  parseMemoryMiB,
  parseQuantity,
  toNumber,
} from '../k8sQuantities';
import { useEksDashboard } from '../../hooks/useEksDashboard';
import { EksDashboardCacheProvider } from '../../contexts/EksDashboardCacheContext';

/** The parsers as they were in hooks/useEksDashboard.ts before the move, to prove the totals hold. */
function legacyCpu(v: any): number {
  if (v === null || v === undefined || v === '') return 0;
  const s = String(v).trim();
  if (s.endsWith('m')) return parseFloat(s) || 0;
  const n = parseFloat(s);
  return isNaN(n) ? 0 : Math.round(n * 1000);
}
function legacyMem(v: any): number {
  if (v === null || v === undefined || v === '') return 0;
  const s = String(v).trim();
  if (s.endsWith('Ki')) return parseFloat(s) / 1024;
  if (s.endsWith('Mi')) return parseFloat(s);
  if (s.endsWith('Gi')) return parseFloat(s) * 1024;
  if (s.endsWith('Ti')) return parseFloat(s) * 1024 * 1024;
  if (s.endsWith('K') || s.endsWith('k')) return parseFloat(s) / 1024;
  if (s.endsWith('M')) return parseFloat(s);
  if (s.endsWith('G')) return parseFloat(s) * 1024;
  const bytes = parseFloat(s);
  return isNaN(bytes) ? 0 : bytes / (1024 * 1024);
}

describe('CPU quantities', () => {
  it.each([
    ['250m', 250],
    ['10m', 10],
    ['1', 1000],
    ['1.5', 1500],
    ['0.1', 100],
    ['2', 2000],
    ['32', 32000],
    ['317500648n', 317.500648],
    ['500u', 0.5],
    ['1e3m', 1000],
  ])('%s is %d millicores', (input, millicores) => {
    expect(parseCpuMillicores(input)).toBe(millicores);
  });

  it('reads a bare number as cores, like a unitless quantity', () => {
    expect(parseCpuMillicores(2)).toBe(2000);
    expect(parseCpuMillicores(0)).toBe(0);
  });
});

describe('memory quantities', () => {
  it.each([
    ['32Mi', 32],
    ['1Gi', 1024],
    ['2Gi', 2048],
    ['3372960Ki', 3293.90625],
    ['1Ti', 1024 * 1024],
    ['134217728', 128],
  ])('binary %s is %d MiB', (input, mib) => {
    expect(parseMemoryMiB(input)).toBe(mib);
  });

  it.each([
    ['1k', 1000],
    ['1K', 1000],
    ['1M', 1e6],
    ['1G', 1e9],
    ['1T', 1e12],
  ])('decimal %s is %d bytes, a power of 1000 (the old parser read M as MiB)', (input, bytes) => {
    expect(parseMemoryBytes(input)).toBe(bytes);
    expect(parseMemoryMiB(input)).toBeCloseTo(bytes / 1048576, 6);
  });

  it('accepts the m suffix Kubernetes uses for fractional bytes', () => {
    expect(parseMemoryBytes('1500m')).toBe(1.5);
    expect(parseMemoryMiB('1048576000m')).toBe(1);
  });

  it('agrees across suffixes: 256Mi is 0.25Gi and 250M is 0.25G', () => {
    // §17 writes "250Mi equals 0.25Gi"; 0.25Gi is 256Mi, so both true forms are pinned.
    expect(parseMemoryMiB('256Mi')).toBe(parseMemoryMiB('0.25Gi'));
    expect(parseMemoryBytes('250M')).toBe(parseMemoryBytes('0.25G'));
  });
});

describe('missing and unparseable input is null, not 0', () => {
  it.each([undefined, null, '', '   ', 'abc', '12x', '1.2.3', 'Mi', '--1', NaN, Infinity, {}, [], true])(
    '%j',
    (input) => {
      expect(parseCpuMillicores(input)).toBeNull();
      expect(parseMemoryMiB(input)).toBeNull();
      expect(parseQuantity(input)).toBeNull();
    },
  );

  it('toNumber keeps null for what is not a finite number', () => {
    expect(toNumber(null)).toBeNull();
    expect(toNumber('')).toBeNull();
    expect(toNumber('x')).toBeNull();
    expect(toNumber('4.5')).toBe(4.5);
    expect(toNumber(0)).toBe(0);
  });
});

describe('formatters render null as "—"', () => {
  it('formats CPU', () => {
    expect(formatCpu(250)).toBe('250m');
    expect(formatCpu(1000)).toBe('1 core');
    expect(formatCpu(1500)).toBe('1.5 cores');
    expect(formatCpu(null)).toBe(DASH);
  });

  it('formats memory and bytes', () => {
    expect(formatMemoryMiB(512)).toBe('512 MiB');
    expect(formatMemoryMiB(1536)).toBe('1.5 GiB');
    expect(formatMemoryMiB(0.5)).toBe('512 KiB');
    expect(formatMemoryMiB(null)).toBe(DASH);
    expect(formatBytes(536870912)).toBe('512 MiB');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(undefined)).toBe(DASH);
  });

  it('formats percentages and money', () => {
    expect(formatPercent(52.04)).toBe('52%');
    expect(formatPercent(52.44, 1)).toBe('52.4%');
    expect(formatPercent(null)).toBe(DASH);
    expect(formatUsd(48.6)).toBe('$48.60');
    expect(formatUsd(null)).toBe(DASH);
  });

  it('formats ages from timestamps and keeps ready-made ages', () => {
    const now = Date.parse('2026-10-11T12:00:00Z');
    expect(formatAge('2026-10-11T11:59:20Z', now)).toBe('40s');
    expect(formatAge('2026-10-11T11:48:00Z', now)).toBe('12m');
    expect(formatAge('2026-10-11T07:00:00Z', now)).toBe('5h');
    expect(formatAge('2026-09-23T12:00:00Z', now)).toBe('18d');
    expect(formatAge('18d', now)).toBe('18d');
    expect(formatAge(null, now)).toBe(DASH);
    expect(ageSortValue('18d', now)).toBe(18 * 86400 * 1000);
    expect(ageSortValue('2026-10-11T11:59:20Z', now)).toBe(40_000);
    expect(ageSortValue('N/A', now)).toBeNull();
  });
});

describe('dashboard request totals after the move', () => {
  afterEach(() => apiGet.mockReset());

  // Millicore, core and binary-suffixed requests, as the agent and the demo send them, plus unset ones.
  const containers = [
    { name: 'a', cpuRequest: '250m', memoryRequest: '512Mi', cpuLimit: '500m', memoryLimit: '1Gi' },
    { name: 'b', cpuRequest: '1', memoryRequest: '3400576Ki', cpuLimit: '1.5', memoryLimit: '4Gi' },
    { name: 'c', cpuRequest: '100m', memoryRequest: '70Mi', cpuLimit: '', memoryLimit: '170Mi' },
    { name: 'd', cpuRequest: '25m', memoryRequest: '', cpuLimit: '', memoryLimit: '' },
    { name: 'e' },
  ];
  const dashboard = {
    clusterOverview: { totalNodes: 1 },
    k8sPods: [
      { name: 'p1', namespace: 'ns', phase: 'Running', nodeName: 'n1', containers: containers.slice(0, 3) },
      { name: 'p2', namespace: 'ns', phase: 'Running', nodeName: 'n1', containers: containers.slice(3) },
    ],
    nodeMetrics: { nodes: [{ name: 'n1', allocatableCpu: '1930m', allocatableMemory: '3372960Ki' }] },
    resourceUtilization: { nodes: [{ nodeName: 'n1', allocatableCpu: '1930m', allocatableMemory: '3372960Ki' }] },
  };

  const wrapper = ({ children }: { children: ReactNode }) => createElement(EksDashboardCacheProvider, null, children);

  it('keeps derivedPodResourceMetrics and derivedNodeRequestMetrics totals unchanged', async () => {
    apiGet.mockResolvedValue(dashboard);
    const { result } = renderHook(
      () => useEksDashboard({ clusterName: 'c1', region: 'us-east-1', version: null, accountId: '111111111111' }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.dashboard).not.toBeNull());

    const sum = (f: (v: any) => number, key: string) => containers.reduce((s, c) => s + f((c as any)[key]), 0);
    const metrics = result.current.derivedPodResourceMetrics!;
    expect(metrics.totalCpuRequests).toBe(sum(legacyCpu, 'cpuRequest'));
    expect(metrics.totalCpuLimits).toBe(sum(legacyCpu, 'cpuLimit'));
    expect(metrics.totalMemoryRequests).toBe(sum(legacyMem, 'memoryRequest'));
    expect(metrics.totalMemoryLimits).toBe(sum(legacyMem, 'memoryLimit'));
    expect(metrics.totalCpuCapacity).toBe(legacyCpu('1930m'));
    expect(metrics.totalMemoryCapacity).toBe(legacyMem('3372960Ki'));
    expect(metrics.podsWithoutRequests).toBe(0);
    // d has no memory request and e has neither: counted, not hidden in the sum.
    expect(metrics.containersWithoutRequests).toBe(2);

    const node = result.current.derivedNodeRequestMetrics.n1;
    expect(node.cpuRequestsMillicores).toBe(Math.round(sum(legacyCpu, 'cpuRequest')));
    expect(node.memoryRequestsMiB).toBe(Math.round(sum(legacyMem, 'memoryRequest')));
    expect(node.allocatableCpuMillicores).toBe(1930);
    expect(node.allocatableMemoryMiB).toBe(legacyMem('3372960Ki'));
  });

  it('changes only decimal-suffixed requests, which now read as powers of 1000', async () => {
    apiGet.mockResolvedValue({
      ...dashboard,
      k8sPods: [{ name: 'p', namespace: 'ns', phase: 'Running', containers: [{ name: 'x', cpuRequest: '100m', memoryRequest: '1G' }] }],
    });
    const { result } = renderHook(
      () => useEksDashboard({ clusterName: 'c2', region: 'us-east-1', version: null, accountId: '111111111111' }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.dashboard).not.toBeNull());
    expect(legacyMem('1G')).toBe(1024);
    expect(result.current.derivedPodResourceMetrics!.totalMemoryRequests).toBeCloseTo(1e9 / 1048576, 6);
  });
});
