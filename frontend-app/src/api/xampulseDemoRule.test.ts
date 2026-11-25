import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { isXpDemo } from './xampulseApi';
import { DEMO_MODE_KEY, activateDemoModeIfNeeded } from '../demo/demoConfig';

/** In-memory Storage, so the test does not depend on the runner's localStorage/sessionStorage support. */
function memoryStorage(): Storage {
  let data: Record<string, string> = {};
  return {
    get length() { return Object.keys(data).length; },
    clear: () => { data = {}; },
    getItem: (k: string) => (k in data ? data[k] : null),
    key: (i: number) => Object.keys(data)[i] ?? null,
    removeItem: (k: string) => { delete data[k]; },
    setItem: (k: string, v: string) => { data[k] = String(v); },
  };
}

describe('XamPulse shows sample data only to the demo account', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', { value: memoryStorage(), configurable: true });
    Object.defineProperty(window, 'sessionStorage', { value: memoryStorage(), configurable: true });
  });
  afterEach(() => { window.sessionStorage.clear(); window.localStorage.clear(); });

  it('a real user gets real data', () => {
    activateDemoModeIfNeeded('aditya@example.com');
    expect(window.sessionStorage.getItem(DEMO_MODE_KEY)).toBeNull();
    expect(isXpDemo()).toBe(false);
  });

  it('the demo account gets sample data in XamPulse like everywhere else in XamOps', () => {
    activateDemoModeIfNeeded('demo.xamops@xammer.in');
    expect(isXpDemo()).toBe(true);
  });
});
