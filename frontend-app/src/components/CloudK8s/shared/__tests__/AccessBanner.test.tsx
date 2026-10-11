import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AccessBanner, OVERLAY_TEXT, accessOverlays } from '../AccessBanner';
import { READINESS_KEYS, type CapabilitiesDto } from '../../../../types/k8sAutomation';

interface Options {
  accessLevel?: 'READ_WRITE' | 'READ_ONLY';
  status?: string;
  userReasons?: string[];
  detail?: string | null;
  clusterAccessLevel?: 'READ_WRITE' | 'READ_ONLY';
}

function caps(o: Options = {}): CapabilitiesDto {
  const accessLevel = o.accessLevel ?? 'READ_WRITE';
  const status = o.status ?? 'CONNECTED';
  return {
    schemaVersion: 1,
    accountId: '111111111111',
    generatedAt: '2026-10-10T09:30:00Z',
    connection: { accessLevel, status },
    user: { canChangeAutomation: !(o.userReasons ?? []).length, reasons: o.userReasons ?? [], detail: o.detail ?? null },
    writeGate: { allowed: true, reasons: [] },
    readiness: Object.fromEntries(READINESS_KEYS.map((k) => [k, { ready: false, reason: 'CONTROLLER_NOT_READY', message: 'x' }])),
    verticalEngine: { enabled: false },
    clusters: [{
      clusterName: 'prod-eks', registered: true,
      connection: { accessLevel: o.clusterAccessLevel ?? accessLevel, status },
      writeGate: { allowed: true, reasons: [] }, dataSourceMode: 'agent',
      agent: { status: 'CONNECTED', lastDataAt: null, version: null },
      automationAgent: { status: 'CONNECTED', lastSeenAt: null, version: null },
      nodeAutomation: { enabled: false, mode: null, evictorAnalyseOnly: null, derivedState: 'OFF' },
      dataSources: [],
    }],
  };
}

const value = (data: CapabilitiesDto | null, extra: Partial<{ status: 'loading' | 'ready' | 'error'; noAccess: boolean; demo: boolean }> = {}) => ({
  status: extra.status ?? 'ready' as const,
  data,
  noAccess: extra.noAccess ?? false,
  demo: extra.demo ?? false,
  refetch: vi.fn(),
});

function renderBanner(v: ReturnType<typeof value>, cluster: string | null = 'prod-eks') {
  return render(<MemoryRouter><AccessBanner capabilities={v} cluster={cluster} /></MemoryRouter>);
}

const banners = () => [...document.querySelectorAll('[data-overlay]')].map((el) => el.getAttribute('data-overlay'));

describe('accessOverlays (§8.2)', () => {
  it('shows nothing to a read-write, permitted user, or while loading', () => {
    expect(accessOverlays(value(caps()))).toEqual([]);
    expect(accessOverlays(value(null, { status: 'loading' }))).toEqual([]);
  });

  it('maps each overlay', () => {
    expect(accessOverlays(value(caps({ accessLevel: 'READ_ONLY' })))).toEqual(['read-only']);
    expect(accessOverlays(value(caps({ userReasons: ['NO_USER_PERMISSION'], detail: 'TAB_GRANT' })))).toEqual(['no-permission']);
    expect(accessOverlays(value(caps({ userReasons: ['NO_USER_PERMISSION'], detail: 'ROLE' })))).toEqual(['no-permission']);
    expect(accessOverlays(value(caps({ userReasons: ['NO_USER_PERMISSION'], detail: 'IMPERSONATION' })))).toEqual(['impersonation']);
    expect(accessOverlays(value(caps({ status: 'PENDING' })))).toEqual(['not-verified']);
    expect(accessOverlays(value(caps(), { demo: true }))).toEqual(['demo']);
    expect(accessOverlays(value(caps({ accessLevel: 'READ_ONLY' }), { demo: true }))).toEqual(['read-only', 'demo']);
    expect(accessOverlays(value(null, { status: 'error' }))).toEqual(['capabilities-error']);
    expect(accessOverlays(value(null, { status: 'error', noAccess: true }))).toEqual(['no-account-access']);
  });

  it("uses the cluster's own connection when capabilities lists it", () => {
    const mixed = caps({ accessLevel: 'READ_ONLY', clusterAccessLevel: 'READ_WRITE' });
    expect(accessOverlays(value(mixed), 'prod-eks')).toEqual([]);
    expect(accessOverlays(value(mixed), 'other-eks')).toEqual(['read-only']);
  });
});

describe('AccessBanner', () => {
  it('renders nothing for a read-write, permitted user', () => {
    const { container } = renderBanner(value(caps()));
    expect(container.querySelector('[data-testid="access-banner"]')).toBeNull();
  });

  it.each([
    ['read-only', caps({ accessLevel: 'READ_ONLY' }), 'Read-only view. Recommendations and projected savings are shown; automation changes are off.'],
    ['no-permission', caps({ userReasons: ['NO_USER_PERMISSION'], detail: 'TAB_GRANT' }), 'You can view automation but not change it.'],
    ['impersonation', caps({ userReasons: ['NO_USER_PERMISSION'], detail: 'IMPERSONATION' }),
      "You're signed in as another user. Automation is view-only in this session. Return to your own session to change automation."],
    ['not-verified', caps({ status: 'FAILED' }), "This connection isn't verified."],
  ] as const)('%s shows its exact copy', (overlay, data, text) => {
    renderBanner(value(data));
    expect(banners()).toEqual([overlay]);
    expect(screen.getByText(text)).toBeInTheDocument();
    expect(OVERLAY_TEXT[overlay]).toBe(text);
  });

  it('offers "Enable automation" for read-only and missing permission only', () => {
    const { unmount } = renderBanner(value(caps({ accessLevel: 'READ_ONLY' })));
    expect(screen.getByRole('button', { name: 'Enable automation' })).toBeInTheDocument();
    unmount();
    const second = renderBanner(value(caps({ userReasons: ['NO_USER_PERMISSION'], detail: 'ROLE' })));
    expect(screen.getByRole('button', { name: 'Enable automation' })).toBeInTheDocument();
    second.unmount();
    renderBanner(value(caps({ userReasons: ['NO_USER_PERMISSION'], detail: 'IMPERSONATION' })));
    expect(screen.queryByRole('button', { name: 'Enable automation' })).toBeNull();
  });

  it('links an unverified connection to Account Manager', () => {
    renderBanner(value(caps({ status: 'PENDING' })));
    expect(screen.getByRole('link', { name: 'Open Account Manager' })).toHaveAttribute('href', '/account-manager');
  });

  it('shows both the read-only and the demo banner on the read-only demo account', () => {
    renderBanner(value(caps({ accessLevel: 'READ_ONLY' }), { demo: true }));
    expect(banners()).toEqual(['read-only', 'demo']);
    expect(screen.getByText(OVERLAY_TEXT['read-only'])).toBeInTheDocument();
    expect(screen.getByText("Demo workspace. Changes aren't saved.")).toBeInTheDocument();
  });

  it('shows only the demo banner on the read-write demo account', () => {
    renderBanner(value(caps(), { demo: true }));
    expect(banners()).toEqual(['demo']);
    expect(screen.queryByRole('button', { name: 'Enable automation' })).toBeNull();
  });

  it('shows "Couldn\'t check automation access." with Retry when capabilities failed', async () => {
    const user = userEvent.setup();
    const v = value(null, { status: 'error' });
    renderBanner(v);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent("Couldn't check automation access.");
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(v.refetch).toHaveBeenCalledTimes(1);
  });

  it('shows the page-level message on a 403', () => {
    renderBanner(value(null, { status: 'error', noAccess: true }));
    expect(screen.getByRole('alert')).toHaveTextContent("You don't have access to this account.");
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('opens and closes the checklist from "Enable automation"', async () => {
    const user = userEvent.setup();
    renderBanner(value(caps({ accessLevel: 'READ_ONLY' })));
    const toggle = screen.getByRole('button', { name: 'Enable automation' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(document.getElementById(toggle.getAttribute('aria-controls')!)).not.toBeNull();
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const panel = screen.getByRole('region', { name: 'What automation needs' });
    expect(panel.id).toBe(toggle.getAttribute('aria-controls'));
    await user.click(toggle);
    expect(screen.queryByRole('region', { name: 'What automation needs' })).toBeNull();
  });
});
