import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
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
vi.mock('../AutoSpottingPage', () => ({
  default: ({ preselect }: { preselect?: string }) => <div>{`shared shell${preselect ? ` ${preselect}` : ''}`}</div>,
}));

import AutoSpottingRoute from '../AutoSpottingRoute';

afterEach(() => {
  vi.unstubAllGlobals();
  account.provider = 'AWS';
});

const config = (shellEnabled: boolean) => ({ shellEnabled, dryRun: false, providers: {}, engagedSwitches: [] });

describe('AutoSpottingRoute', () => {
  it('renders the legacy AWS page while the shell flag is off', async () => {
    installFetch([{ match: /\/v2\/config$/, responses: [{ body: config(false) }] }]);
    renderWithProviders(<AutoSpottingRoute />);
    expect(await screen.findByText('legacy aws page')).toBeInTheDocument();
  });

  it('renders the legacy AWS page when the config cannot be read', async () => {
    installFetch([{ match: /\/v2\/config$/, responses: [{ status: 500, body: {} }] }]);
    renderWithProviders(<AutoSpottingRoute />);
    expect(await screen.findByText('legacy aws page')).toBeInTheDocument();
  });

  it('renders the shell for an AWS account when the flag is on', async () => {
    installFetch([{ match: /\/v2\/config$/, responses: [{ body: config(true) }] }]);
    renderWithProviders(<AutoSpottingRoute />);
    expect(await screen.findByText('shared shell')).toBeInTheDocument();
  });

  it('renders the shell for a GCP account when the flag is on', async () => {
    account.provider = 'GCP';
    installFetch([{ match: /\/v2\/config$/, responses: [{ body: config(true) }] }]);
    renderWithProviders(<AutoSpottingRoute />);
    expect(await screen.findByText('shared shell GCP')).toBeInTheDocument();
  });

  it('/gcp-autospotting opens the shell with GCP preselected when the flag is on', async () => {
    installFetch([{ match: /\/v2\/config$/, responses: [{ body: config(true) }] }]);
    renderWithProviders(<AutoSpottingRoute gcp />);
    expect(await screen.findByText('shared shell GCP')).toBeInTheDocument();
  });

  it('/gcp-autospotting keeps the legacy GCP page while the flag is off', async () => {
    installFetch([{ match: /\/v2\/config$/, responses: [{ body: config(false) }] }]);
    renderWithProviders(<AutoSpottingRoute gcp />);
    expect(await screen.findByText('legacy gcp page')).toBeInTheDocument();
  });
});
