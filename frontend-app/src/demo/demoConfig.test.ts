import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  isDemoHost,
  activateDemoModeIfNeeded,
  isDemoMode,
  getDemoRole,
  DEMO_MODE_KEY,
  DEMO_ROLE_KEY,
} from './demoConfig';

/**
 * demoConfig host activation.
 *
 * isDemoHost() decides whether the current host runs in demo mode; the demo host
 * (demo.xamops.com, or a VITE_DEMO_HOST override) forces ROLE_XAMOPS for ANY email,
 * while the live host keeps the DEMO_EMAILS allowlist mapping untouched.
 *
 * jsdom defaults window.location.hostname to 'localhost' and will not let us assign
 * to it, so each test swaps in a plain stub via Object.defineProperty and restores
 * the real location afterwards.
 */

const realLocation = window.location;

function stubHostname(hostname: string): void {
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { ...realLocation, hostname },
  });
}

/**
 * A minimal in-memory Storage. The test runner (Node's experimental localStorage,
 * shadowing jsdom's) exposes a `localStorage` whose methods are undefined, so
 * `activateDemoModeIfNeeded`'s `localStorage.clear()` would throw here even though it
 * works in every real browser. Install a working stub for both storages so the test
 * exercises the real activation logic rather than an environment quirk.
 */
function makeStorage(): Storage {
  const map = new Map<string, string>();
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    getItem: (k: string) => (map.has(k) ? map.get(k)! : null),
    key: (i: number) => Array.from(map.keys())[i] ?? null,
    removeItem: (k: string) => void map.delete(k),
    setItem: (k: string, v: string) => void map.set(k, String(v)),
  } as Storage;
}

beforeEach(() => {
  Object.defineProperty(window, 'localStorage', { configurable: true, value: makeStorage() });
  Object.defineProperty(window, 'sessionStorage', { configurable: true, value: makeStorage() });
});

afterEach(() => {
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: realLocation,
  });
  vi.unstubAllEnvs();
});

describe('isDemoHost()', () => {
  it('is true on demo.xamops.com', () => {
    stubHostname('demo.xamops.com');
    expect(isDemoHost()).toBe(true);
  });

  it('is false on the live host', () => {
    stubHostname('live.xamops.com');
    expect(isDemoHost()).toBe(false);
  });

  it('is false on any other host', () => {
    stubHostname('localhost');
    expect(isDemoHost()).toBe(false);
  });

  it('honours the VITE_DEMO_HOST override', () => {
    vi.stubEnv('VITE_DEMO_HOST', 'preview.xamops.dev');
    stubHostname('preview.xamops.dev');
    expect(isDemoHost()).toBe(true);

    // With the override set, the default demo host no longer matches.
    stubHostname('demo.xamops.com');
    expect(isDemoHost()).toBe(false);
  });

  it('trims the VITE_DEMO_HOST override', () => {
    vi.stubEnv('VITE_DEMO_HOST', '  preview.xamops.dev  ');
    stubHostname('preview.xamops.dev');
    expect(isDemoHost()).toBe(true);
  });
});

describe('activateDemoModeIfNeeded() — demo host', () => {
  beforeEach(() => stubHostname('demo.xamops.com'));

  it('forces ROLE_XAMOPS for any (non-allowlisted) email', () => {
    activateDemoModeIfNeeded('someone@example.com');
    expect(isDemoMode()).toBe(true);
    expect(getDemoRole()).toBe('ROLE_XAMOPS');
    expect(sessionStorage.getItem(DEMO_MODE_KEY)).toBe('true');
    expect(sessionStorage.getItem(DEMO_ROLE_KEY)).toBe('ROLE_XAMOPS');
  });

  it('forces ROLE_XAMOPS even for an email the live allowlist maps to BillOps', () => {
    activateDemoModeIfNeeded('demo.billops@xammer.in');
    expect(getDemoRole()).toBe('ROLE_XAMOPS');
  });

  it('still does nothing without an email', () => {
    activateDemoModeIfNeeded(undefined);
    expect(isDemoMode()).toBe(false);
  });
});

describe('activateDemoModeIfNeeded() — live host', () => {
  beforeEach(() => stubHostname('live.xamops.com'));

  it('maps an allowlisted XamOps demo email to ROLE_XAMOPS', () => {
    activateDemoModeIfNeeded('demo.xamops@xammer.in');
    expect(getDemoRole()).toBe('ROLE_XAMOPS');
  });

  it('maps an allowlisted BillOps demo email to ROLE_BILLOPS_ADMIN', () => {
    activateDemoModeIfNeeded('demo.billops@xammer.in');
    expect(getDemoRole()).toBe('ROLE_BILLOPS_ADMIN');
  });

  it('does not activate demo mode for a non-allowlisted user', () => {
    activateDemoModeIfNeeded('real.user@customer.com');
    expect(isDemoMode()).toBe(false);
    expect(getDemoRole()).toBeNull();
  });
});
