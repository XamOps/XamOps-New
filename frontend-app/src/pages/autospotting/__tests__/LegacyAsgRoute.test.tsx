import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes, useLocation } from 'react-router-dom';
import { installFetch, renderWithProviders } from './helpers';

const account = vi.hoisted(() => ({ provider: 'AWS' as 'AWS' | 'GCP' }));
vi.mock('../../../contexts/AccountContext', () => ({
  useAccounts: () => ({
    accounts: [{ id: 'acct', dbId: 7, name: 'Prod', provider: account.provider, status: 'CONNECTED' }],
    selectedAccountIds: ['acct'],
  }),
}));
vi.mock('../../SpotAutomationPage', () => ({ default: () => <div>legacy aws page</div> }));
vi.mock('../../GcpSpotAutomationPage', () => ({ default: () => <div>legacy gcp page</div> }));
vi.mock('../AutoSpottingPage', () => ({ default: () => <div>shared shell</div> }));

import AutoSpottingRoute from '../AutoSpottingRoute';
import LegacyAsgRoute, { groupDeepLink } from '../LegacyAsgRoute';

afterEach(() => {
  vi.unstubAllGlobals();
  account.provider = 'AWS';
});

const config = (shellEnabled: boolean) => ({ shellEnabled, dryRun: false, providers: {}, engagedSwitches: [] });

const Where: React.FC = () => {
  const l = useLocation();
  return <div>{`at ${l.pathname}${l.search}`}</div>;
};

const routes = (
  <Routes>
    <Route path="/spot-automation" element={<AutoSpottingRoute canonical />} />
    <Route path="/autospotting" element={<Where />} />
    <Route path="/asg-detail" element={<LegacyAsgRoute legacy={<div>legacy asg detail</div>} />} />
    <Route path="/asg-config" element={<LegacyAsgRoute legacy={<div>legacy asg config</div>} />} />
  </Routes>
);

describe('legacy AWS routes (plan-phase-14 task 14.3)', () => {
  describe('shellEnabled = false: every route renders as today', () => {
    it.each([
      ['/spot-automation', 'legacy aws page'],
      ['/asg-detail?asgName=web&region=us-east-1', 'legacy asg detail'],
      ['/asg-config?asgName=web&region=us-east-1', 'legacy asg config'],
    ])('%s', async (path, text) => {
      installFetch([{ match: /\/v2\/config$/, responses: [{ body: config(false) }] }]);
      renderWithProviders(routes, path);
      expect(await screen.findByText(text)).toBeInTheDocument();
    });

    it('also renders the legacy pages when the config cannot be read', async () => {
      installFetch([{ match: /\/v2\/config$/, responses: [{ status: 500, body: {} }] }]);
      renderWithProviders(routes, '/asg-detail?asgName=web&region=us-east-1');
      expect(await screen.findByText('legacy asg detail')).toBeInTheDocument();
    });
  });

  describe('shellEnabled = true', () => {
    it('sends /spot-automation to /autospotting', async () => {
      installFetch([{ match: /\/v2\/config$/, responses: [{ body: config(true) }] }]);
      renderWithProviders(routes, '/spot-automation');
      expect(await screen.findByText('at /autospotting')).toBeInTheDocument();
    });

    it.each(['/asg-detail', '/asg-config'])('sends %s to the group deep link', async path => {
      installFetch([{ match: /\/v2\/config$/, responses: [{ body: config(true) }] }]);
      renderWithProviders(routes, `${path}?asgName=web-asg&region=us-east-1&accountIds=123`);
      expect(await screen.findByText(`at /autospotting?group=${encodeURIComponent('us-east-1/web-asg')}`)).toBeInTheDocument();
    });

    it('keeps a GCP account on the legacy GCP page', async () => {
      account.provider = 'GCP';
      installFetch([{ match: /\/v2\/config$/, responses: [{ body: config(true) }] }]);
      renderWithProviders(routes, '/spot-automation');
      expect(await screen.findByText('legacy gcp page')).toBeInTheDocument();
    });
  });

  it('builds the deep link, and falls back to the shell root without a group', () => {
    expect(groupDeepLink('us-east-1', 'web-asg')).toBe('/autospotting?group=us-east-1%2Fweb-asg');
    expect(groupDeepLink(null, null)).toBe('/autospotting');
  });
});
