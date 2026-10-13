/**
 * The global fetch patch in main.tsx must not bounce an admin off the user manager when a
 * trial-admin endpoint answers 402.
 *
 * Everywhere else a 402 means "this tenant's trial has ended", and the patch sends the user to
 * /trial-expired. The trial-admin endpoints are the exception, for the same reason
 * TrialEnforcementFilter.shouldNotFilter exempts them: they are how an admin rescues a lapsed
 * tenant, so a 402 from one of them has to stay a message on the page. Without this test the
 * exemption is a bare condition nobody would notice losing.
 *
 * Driven through the real patched window.fetch, following
 * components/Sidebar/__tests__/loginAsAccountDashboard.test.tsx.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';

const net = vi.hoisted(() => {
  // main.tsx captures window.fetch when it loads, so the stub has to exist first.
  const state = { status: 402 };
  const navigations: string[] = [];

  // jsdom refuses a real navigation, so window.location becomes a recorder instead.
  const realOrigin = window.location.origin;
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: {
      origin: realOrigin,
      pathname: '/billops-admin-user-manager',
      set href(value: string) { navigations.push(value); },
      get href() { return navigations[navigations.length - 1] ?? realOrigin; },
      reload: () => {},
    },
  });

  window.fetch = (async () => new Response('', { status: state.status })) as typeof fetch;

  const root = document.createElement('div');
  root.id = 'root';
  document.body.appendChild(root);

  return { state, navigations };
});

// main.tsx renders <App/> into #root; only its fetch interceptor is wanted here.
vi.mock('../../../App', () => ({ default: () => null }));

// The extension matters: a legacy src/main.js would otherwise be resolved first.
import '../../../main.tsx';

describe('main.tsx fetch patch: 402 on the trial-admin endpoints', () => {
  beforeEach(() => {
    net.navigations.length = 0;
    net.state.status = 402;
  });

  it('keeps the admin on the page when the trial list answers 402', async () => {
    const response = await window.fetch('/api/billops/admin/trials?includeConverted=true');

    expect(response.status).toBe(402);
    expect(net.navigations).toEqual([]);
  });

  it('keeps the admin on the page when a trial mutation answers 402', async () => {
    await window.fetch('/api/billops/admin/trials/135/extend', {
      method: 'POST',
      body: JSON.stringify({ days: '30' }),
    });

    expect(net.navigations).toEqual([]);
  });

  it('still sends the user to /trial-expired on a 402 from any other internal API', async () => {
    await window.fetch('/api/xamops/dashboard/data');

    expect(net.navigations).toEqual(['/trial-expired']);
  });

  it('does not redirect when a trial-admin call succeeds', async () => {
    net.state.status = 200;

    await window.fetch('/api/billops/admin/trials?includeConverted=true');

    expect(net.navigations).toEqual([]);
  });
});
