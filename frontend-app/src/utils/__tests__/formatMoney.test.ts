import { describe, it, expect } from 'vitest';
import { formatMoney, NOT_PRICED } from '../formatMoney';

describe('formatMoney', () => {
  it('shows the ISO code and never a currency symbol', () => {
    expect(formatMoney(820, 'USD')).toBe('USD 820');
    expect(formatMoney(4210)).toBe('USD 4,210');
    expect(formatMoney(820)).not.toMatch(/[$\u20B9\u20AC\u00A3]/);
  });

  it('keeps cents on small amounts', () => {
    expect(formatMoney(12.5)).toBe('USD 12.50');
  });

  it('renders null and undefined as Not priced, never 0', () => {
    expect(formatMoney(null)).toBe(NOT_PRICED);
    expect(formatMoney(undefined)).toBe('Not priced');
    expect(formatMoney(0)).toBe('USD 0.00');
  });

  it('supports the per-month suffix and a label', () => {
    expect(formatMoney(820, 'USD', { perMonth: true })).toBe('USD 820/mo');
    expect(formatMoney(820, 'USD', { label: 'Estimate' })).toBe('USD 820 (Estimate)');
  });
});
