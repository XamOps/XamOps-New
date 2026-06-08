/**
 * Smoke tests for utility functions.
 * These run without a DOM or network, so they pass in any CI environment.
 * Add component-level tests alongside each feature as the codebase grows.
 */

import { describe, it, expect } from 'vitest';

// ── Number / currency formatting ──────────────────────────────────────────────
describe('currency formatting', () => {
  const formatUSD = (value: number): string =>
    new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);

  it('formats zero', () => {
    expect(formatUSD(0)).toBe('$0.00');
  });

  it('formats positive integer', () => {
    expect(formatUSD(1000)).toBe('$1,000.00');
  });

  it('formats decimal', () => {
    expect(formatUSD(14820.5)).toBe('$14,820.50');
  });

  it('formats negative', () => {
    expect(formatUSD(-250)).toContain('250');
  });
});

// ── Date range helpers ────────────────────────────────────────────────────────
describe('date utilities', () => {
  it('start of month is day 1', () => {
    const d = new Date(2026, 5, 8); // June 8 2026
    const startOfMonth = new Date(d.getFullYear(), d.getMonth(), 1);
    expect(startOfMonth.getDate()).toBe(1);
    expect(startOfMonth.getMonth()).toBe(5); // June
  });

  it('ISO date string round-trips correctly', () => {
    const iso = '2026-06-08';
    const parsed = new Date(iso + 'T00:00:00Z');
    expect(parsed.getUTCFullYear()).toBe(2026);
    expect(parsed.getUTCMonth()).toBe(5); // 0-indexed
    expect(parsed.getUTCDate()).toBe(8);
  });
});

// ── Array / object utilities ─────────────────────────────────────────────────
describe('array utilities', () => {
  it('sums an array of numbers', () => {
    const sum = (arr: number[]) => arr.reduce((a, b) => a + b, 0);
    expect(sum([100, 50, 25])).toBe(175);
    expect(sum([])).toBe(0);
  });

  it('groups by key', () => {
    const items = [
      { provider: 'AWS', cost: 100 },
      { provider: 'GCP', cost: 50 },
      { provider: 'AWS', cost: 75 },
    ];
    const grouped = items.reduce<Record<string, typeof items>>((acc, item) => {
      (acc[item.provider] ??= []).push(item);
      return acc;
    }, {});
    expect(grouped['AWS']).toHaveLength(2);
    expect(grouped['GCP']).toHaveLength(1);
  });
});
