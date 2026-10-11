// @vitest-environment node
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  CPU_RATE_PER_VCPU_HOUR,
  MEMORY_RATE_PER_GIB_HOUR,
  RATES_TEXT,
  RATE_PROVENANCE,
  estimateCost,
} from '../k8sPricing';

const SRC = fileURLToPath(new URL('../..', import.meta.url));

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    return statSync(full).isDirectory() ? walk(full) : [full];
  });
}

const toPosix = (p: string) => p.split(sep).join('/');
const isSource = (rel: string) => /\.(ts|tsx)$/.test(rel) && !/\.test\.|\.spec\./.test(rel) && !rel.includes('__tests__/');

/**
 * The CloudK8s tree only (design §17): components/EksDetails, components/CloudK8s, hooks/useEks*
 * and utils/k8s*. Not all of src/, because CloudSitterDashboard.tsx legitimately holds the
 * t3.medium price, which happens to be the same number.
 */
function cloudK8sFiles(): string[] {
  const all = walk(SRC).map((f) => toPosix(relative(SRC, f))).filter(isSource);
  return all.filter((f) =>
    f.startsWith('components/EksDetails/')
    || f.startsWith('components/CloudK8s/')
    || /^hooks\/useEks[^/]*$/.test(f)
    || /^utils\/k8s[^/]*$/.test(f));
}

describe('k8sPricing', () => {
  it('holds the two reference rates and says where they came from', () => {
    expect(CPU_RATE_PER_VCPU_HOUR).toBe(0.0416);
    expect(MEMORY_RATE_PER_GIB_HOUR).toBe(0.00456);
    expect(RATE_PROVENANCE).toBe('Origin not documented in code; not region- or Spot-aware');
    expect(RATES_TEXT).toBe('$0.0416 per vCPU-hour · $0.00456 per GiB-hour');
  });

  it('estimates cost for cores and GiB over hours', () => {
    expect(estimateCost(1, 0, 1)).toBeCloseTo(0.0416, 10);
    expect(estimateCost(0, 1, 1)).toBeCloseTo(0.00456, 10);
    expect(estimateCost(2, 4, 24)).toBeCloseTo((2 * 0.0416 + 4 * 0.00456) * 24, 10);
    expect(estimateCost(0, 0, 720)).toBe(0);
  });

  it('is the only CloudK8s file that contains either rate', () => {
    const files = cloudK8sFiles();
    expect(files.length).toBeGreaterThan(10);
    expect(files).toContain('utils/k8sPricing.ts');
    const offenders = files
      .filter((f) => f !== 'utils/k8sPricing.ts')
      .filter((f) => /0\.0416|0\.00456/.test(readFileSync(join(SRC, f), 'utf8')));
    expect(offenders).toEqual([]);
  });
});
