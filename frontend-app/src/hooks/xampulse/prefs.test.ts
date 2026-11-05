import { beforeEach, describe, expect, it } from 'vitest';
import { DEFAULT_PREFS, PREFS_KEY, parsePrefs, resetPrefsCache, resolveTheme, setPrefs } from './prefs';
import { parseViewState, serializeViewState } from './useViewContext';


// Node's experimental localStorage shadows jsdom's and has no methods; install a working in-memory Storage.
function installStorage() {
  const map = new Map<string, string>();
  const s = {
    get length() { return map.size; },
    clear: () => map.clear(),
    getItem: (k: string) => (map.has(k) ? map.get(k)! : null),
    key: (i: number) => Array.from(map.keys())[i] ?? null,
    removeItem: (k: string) => void map.delete(k),
    setItem: (k: string, v: string) => void map.set(k, String(v)),
  } as Storage;
  Object.defineProperty(window, 'localStorage', { configurable: true, value: s });
}
describe('prefs', () => {
  beforeEach(() => { installStorage(); window.localStorage.clear(); resetPrefsCache(); });
  it('falls back to defaults for junk', () => {
    expect(parsePrefs(null)).toEqual(DEFAULT_PREFS);
    expect(parsePrefs('{nope')).toEqual(DEFAULT_PREFS);
    expect(parsePrefs('{"theme":"neon","density":"compact"}')).toEqual({ ...DEFAULT_PREFS, density: 'compact' });
  });
  it('persists under xp:prefs:v1', () => {
    setPrefs({ theme: 'light', density: 'compact' });
    expect(JSON.parse(window.localStorage.getItem(PREFS_KEY) ?? '{}')).toMatchObject({ theme: 'light', density: 'compact' });
  });
  it('resolves explicit themes without consulting the system', () => {
    expect(resolveTheme('light')).toBe('light');
    expect(resolveTheme('dark')).toBe('dark');
  });
});

describe('view context URL round trip', () => {
  it('parses and serialises accounts, range, query and flags', () => {
    const sp = new URLSearchParams('accounts=1,2&range=6h&q=error&live=1&compare=1&region=us-east-1');
    const s = parseViewState(sp);
    expect(s).toMatchObject({ accountIds: [1, 2], range: '6h', q: 'error', live: true, compare: true, region: 'us-east-1' });
    const out = serializeViewState({ accountIds: s.accountIds, range: s.range, q: s.q, live: s.live, compare: s.compare, region: s.region });
    expect(parseViewState(out)).toEqual(s);
  });
  it('prefers a valid absolute range and ignores an inverted one', () => {
    const abs = parseViewState(new URLSearchParams('from=2026-01-01T00:00:00Z&to=2026-01-01T01:00:00Z'));
    expect(abs.range).toBeNull();
    expect(abs.absFrom).toBe('2026-01-01T00:00:00Z');
    const bad = parseViewState(new URLSearchParams('from=2026-01-01T02:00:00Z&to=2026-01-01T01:00:00Z'));
    expect(bad.range).toBe('1h');
  });
  it('clears the query when set to empty', () => {
    expect(serializeViewState({ q: '' }, new URLSearchParams('q=x&range=1h')).has('q')).toBe(false);
  });
});
