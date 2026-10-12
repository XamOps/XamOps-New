import { describe, expect, it } from 'vitest';
import { inPageState, pageKeyOf } from '../inPageNavigation';

describe('in-page navigation keys', () => {
  it('keys an ordinary entry by its own location key', () => {
    expect(pageKeyOf({ key: 'k1', state: null })).toBe('k1');
    expect(pageKeyOf({ key: 'k1', state: { notice: 'x' } })).toBe('k1');
  });

  it('carries the key of the entry the page was opened on across in-page navigations', () => {
    const opened = { key: 'k1', state: null };
    const first = { key: 'k2', state: inPageState(opened) };
    const second = { key: 'k3', state: inPageState(first) };
    expect(pageKeyOf(first)).toBe('k1');
    expect(pageKeyOf(second)).toBe('k1');
  });

  it('ignores a pageKey that is not a string', () => {
    expect(pageKeyOf({ key: 'k4', state: { pageKey: 7 } })).toBe('k4');
  });
});
