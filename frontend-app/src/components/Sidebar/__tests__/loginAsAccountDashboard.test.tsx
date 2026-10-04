/**
 * BillOps "Login as" -> Account Dashboard must stay on the dashboard.
 *
 * Production bug (2026-10-04): in a "Login as" tab, clicking Account Dashboard ended on /login.
 * The cause was server-side. Spring Security 7 answered 401 on the async dispatch of
 * GET /api/xamops/finops/budgets, which the AWS dashboard's cost trend calls through apiClient,
 * and apiClient treats any 401 as an expired session (see ImpersonationJwtAsyncDispatchTest in
 * xamops-service for the server half).
 *
 * This pins the client half of that chain with the real fetch interceptor from main.tsx, the
 * real Auth/Account providers, the real Sidebar and the real CostTrendSection:
 * - the click is a client-side route change, not a page load;
 * - every request carries the impersonation JWT and is sent with credentials 'omit', so the
 *   admin's JSESSIONID is never sent or overwritten;
 * - the tab only logs out when the backend really answers 401.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { BrowserRouter, Routes, Route, Outlet } from 'react-router-dom';

const net = vi.hoisted(() => {
  // main.tsx captures window.fetch when it loads, so the network stub has to exist first.
  const AWS_ACCOUNT_ID = '123456789012';
  const calls: { url: string; headers: Headers; credentials?: RequestCredentials }[] = [];
  const state = { budgetsStatus: 200, budgetsBodyRead: false };

  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

  const stub = async (input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    calls.push({ url, headers: new Headers(init.headers), credentials: init.credentials });

    switch (url.split('?')[0]) {
      case '/api/xamops/user/profile':
        return json(200, {
          id: 42,
          username: 'target-user',
          email: 'target-user@example.com',
          roles: ['ROLE_XAMOPS'],
          tenantId: 'tenant-a',
        });
      case '/api/xamops/account-manager/accounts':
        return json(200, [{ id: AWS_ACCOUNT_ID, name: 'Production', provider: 'AWS', status: 'CONNECTED' }]);
      case '/api/xamops/team/profile':
        return json(200, { allowedTabs: [] });
      case '/api/xamops/finops/budgets': {
        // An empty-bodied 401 is exactly what the unfixed backend sent.
        const res = state.budgetsStatus === 200 ? json(200, []) : new Response('', { status: state.budgetsStatus });
        const readText = res.text.bind(res);
        // apiClient reads the body only after its 401 check, so this marks "response handled".
        res.text = () => {
          state.budgetsBodyRead = true;
          return readText();
        };
        return res;
      }
      default:
        return json(200, {});
    }
  };

  window.fetch = stub as typeof fetch;
  const root = document.createElement('div');
  root.id = 'root';
  document.body.appendChild(root);

  return { AWS_ACCOUNT_ID, calls, state };
});

// main.tsx renders <App/> into #root; only its interceptors are wanted here.
vi.mock('../../../App', () => ({ default: () => null }));

// The extension matters: a legacy src/main.js would otherwise be resolved first.
import '../../../main.tsx';
import { AuthProvider } from '../../../contexts/AuthContext';
import { AccountProvider, useAccounts } from '../../../contexts/AccountContext';
import { ThemeProvider } from '../../../contexts/ThemeContext';
import Sidebar from '../Sidebar';
import CostTrendSection from '../../LiveDashboard/CostTrendSection';

const { AWS_ACCOUNT_ID } = net;
const IMPERSONATION_JWT = 'test-header.test-payload.test-signature';

/** Stands in for pages/Dashboard.tsx, which mounts CostTrendSection for the selected AWS account. */
const AwsDashboardStandIn: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  return (
    <div>
      <h1>Account dashboard loaded</h1>
      <CostTrendSection
        costHistory={{ labels: ['Sep', 'Oct'], costs: [120, 80], anomalies: [false, false] }}
        isLoading={false}
        provider="AWS"
        accountId={selectedAccountIds[0] ?? null}
      />
    </div>
  );
};

const Layout: React.FC = () => (
  <>
    <Sidebar />
    <Outlet />
  </>
);

const renderLoginAsTab = () =>
  render(
    <BrowserRouter>
      <AuthProvider>
        <AccountProvider>
          <ThemeProvider>
            <Routes>
              <Route element={<Layout />}>
                <Route path="/unified-dashboard" element={<h1>Unified dashboard</h1>} />
                <Route path="/dashboard" element={<AwsDashboardStandIn />} />
              </Route>
            </Routes>
          </ThemeProvider>
        </AccountProvider>
      </AuthProvider>
    </BrowserRouter>
  );

const clickAccountDashboard = async () => {
  // Wait for the accounts to load so the link points at the provider's dashboard.
  await waitFor(() => expect(sessionStorage.getItem('selectedAccountIds')).toBe(JSON.stringify([AWS_ACCOUNT_ID])));
  const link = await screen.findByRole('link', { name: 'Account Dashboard' });
  expect(link).toHaveAttribute('href', '/dashboard');
  fireEvent.click(link);
};

const budgetsRequest = () => net.calls.find(c => c.url.startsWith('/api/xamops/finops/budgets'));

describe('BillOps "Login as" tab -> Account Dashboard', () => {
  let sessionExpired: Mock<(event: Event) => void>;

  beforeEach(() => {
    net.calls.length = 0;
    net.state.budgetsStatus = 200;
    net.state.budgetsBodyRead = false;

    // What AuthContext's #imp= bootstrap leaves behind in a freshly opened "Login as" tab.
    sessionStorage.clear();
    sessionStorage.setItem('impersonation_jwt', IMPERSONATION_JWT);
    sessionStorage.setItem('billops_impersonation', 'true');
    sessionStorage.setItem('impersonateUserId', '42');
    sessionStorage.setItem('selectedTenantId', 'tenant-a');
    sessionStorage.setItem('allowedAccountIds', AWS_ACCOUNT_ID);
    window.history.replaceState({}, '', '/unified-dashboard');

    // recharts' ResponsiveContainer needs ResizeObserver, which jsdom lacks.
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });

    sessionExpired = vi.fn<(event: Event) => void>();
    window.addEventListener('session-expired', sessionExpired);
  });

  afterEach(() => {
    window.removeEventListener('session-expired', sessionExpired);
    vi.unstubAllGlobals();
    sessionStorage.clear();
  });

  it('reaches the dashboard with the impersonation JWT on every request and stays logged in', async () => {
    renderLoginAsTab();
    await clickAccountDashboard();

    expect(await screen.findByRole('heading', { name: 'Account dashboard loaded' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/dashboard');

    // The call that logged users out in production.
    await waitFor(() => expect(budgetsRequest()).toBeDefined());
    const budgets = budgetsRequest()!;
    expect(budgets.url).toBe(`/api/xamops/finops/budgets?accountId=${AWS_ACCOUNT_ID}`);
    expect(budgets.headers.get('Authorization')).toBe(`Bearer ${IMPERSONATION_JWT}`);
    expect(budgets.credentials).toBe('omit');
    await waitFor(() => expect(net.state.budgetsBodyRead).toBe(true));

    // Every API call of the session, the sidebar's raw fetches included: JWT only, no cookies.
    const apiCalls = net.calls.filter(c => c.url.startsWith('/api/'));
    expect(apiCalls.length).toBeGreaterThan(3);
    for (const call of apiCalls) {
      expect(call.headers.get('Authorization'), call.url).toBe(`Bearer ${IMPERSONATION_JWT}`);
      expect(call.credentials, call.url).toBe('omit');
    }

    expect(sessionExpired).not.toHaveBeenCalled();
    expect(sessionStorage.getItem('impersonation_jwt')).toBe(IMPERSONATION_JWT);
    expect(sessionStorage.getItem('billops_impersonation')).toBe('true');
    expect(window.location.pathname).toBe('/dashboard');
  });

  it('still ends the tab when the backend really answers 401 (e.g. an expired token)', async () => {
    net.state.budgetsStatus = 401;
    renderLoginAsTab();
    await clickAccountDashboard();

    await waitFor(() => expect(sessionExpired).toHaveBeenCalledTimes(1));
    // The request did carry the token; the logout comes from the backend's answer alone.
    expect(budgetsRequest()!.headers.get('Authorization')).toBe(`Bearer ${IMPERSONATION_JWT}`);
    // AuthContext's handler wipes the tab's impersonation state before sending it to /login.
    expect(sessionStorage.getItem('impersonation_jwt')).toBeNull();
  });
});
