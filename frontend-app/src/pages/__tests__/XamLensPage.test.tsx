import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { DspmStatus } from '../../types/dspm';

vi.mock('../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
// The page's own behaviour is covered by DatastoresPage.test.tsx; here only the tab it receives matters.
vi.mock('../../components/Dspm/datastores/DatastoresPage', () => ({
  default: ({ tab }: { tab: string }) => <div data-testid="datastores-page">{tab}</div>,
}));
// Covered by MisconfigurationsPage.test.tsx; here only that the route renders it.
vi.mock('../../components/Dspm/misconfigurations/MisconfigurationsPage', () => ({
  default: ({ status }: { status: { ready: boolean } }) => <div data-testid="misconfigurations-page">{String(status.ready)}</div>,
}));
// DataLineagePage.test.tsx covers the page; here only that the route reaches it.
vi.mock('../../components/Dspm/lineage/DataLineagePage', () => ({
  default: () => <div data-testid="data-lineage-page" />,
}));
vi.mock('../../components/Dspm/files/FilesPage', () => ({
  default: ({ tab }: { tab: string }) => <div data-testid="files-page">{tab}</div>,
}));

import apiClient from '../../utils/apiClient';
import { TopbarProvider } from '../../contexts/TopbarContext';
import XamLensPage from '../XamLensPage';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

const READY: DspmStatus = {
  ready: true, entitled: true, configured: true, reason: null, message: null,
  projectName: 'Example workspace', lastVerifiedAt: null,
};

const LocationProbe: React.FC = () => <div data-testid="location">{useLocation().pathname}</div>;

/** The XamLens routes as App.tsx declares them. */
function renderAt(url: string) {
  return render(
    <TopbarProvider>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/xamlens" element={<Navigate to="/xamlens/datastores/all" replace />} />
          <Route path="/xamlens/datastores/:datastoreTab" element={<XamLensPage />} />
          <Route path="/xamlens/files/:filesTab" element={<XamLensPage />} />
          <Route path="/xamlens/:tab" element={<XamLensPage />} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </TopbarProvider>,
  );
}

beforeEach(() => {
  get.mockResolvedValue(READY);
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('XamLensPage routing', () => {
  it.each([
    ['/xamlens/cloud', 'cloud'],
    ['/xamlens/saas', 'saas'],
    ['/xamlens/endpoint', 'endpoint'],
  ])('sends the old link %s to the matching Datastores tab', async (url, tab) => {
    renderAt(url);
    expect(await screen.findByTestId('datastores-page')).toHaveTextContent(tab);
    expect(screen.getByTestId('location')).toHaveTextContent(`/xamlens/datastores/${tab}`);
  });

  it.each([
    '/xamlens',
    '/xamlens/foo',
    '/xamlens/datastores',
    '/xamlens/datastores/foo',
    '/xamlens/constructor',
  ])('sends %s to All', async url => {
    renderAt(url);
    expect(await screen.findByTestId('datastores-page')).toHaveTextContent('all');
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/datastores/all');
  });

  it('renders a known Datastores tab in place', async () => {
    renderAt('/xamlens/datastores/on-prem');
    expect(await screen.findByTestId('datastores-page')).toHaveTextContent('on-prem');
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/datastores/on-prem');
  });

  it('renders the Misconfigurations page on /xamlens/misconfigurations', async () => {
    renderAt('/xamlens/misconfigurations');
    expect(await screen.findByTestId('misconfigurations-page')).toHaveTextContent('true');
    expect(screen.queryByTestId('datastores-page')).toBeNull();
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/misconfigurations');
  });

  it.each(['/xamlens/files', '/xamlens/files/foo'])('sends %s to the SaaS Files tab', async url => {
    renderAt(url);
    expect(await screen.findByTestId('files-page')).toHaveTextContent('saas');
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/files/saas');
    expect(screen.queryByTestId('datastores-page')).toBeNull();
  });

  it.each(['saas', 'endpoint'])('renders the Files tab %s in place', async tab => {
    renderAt(`/xamlens/files/${tab}`);
    expect(await screen.findByTestId('files-page')).toHaveTextContent(tab);
    expect(screen.getByTestId('location')).toHaveTextContent(`/xamlens/files/${tab}`);
    expect(screen.queryByTestId('datastores-page')).toBeNull();
  });

  it('renders the status page on /xamlens/connection', async () => {
    renderAt('/xamlens/connection');
    expect(await screen.findByText('Connected')).toBeInTheDocument();
    expect(screen.queryByTestId('datastores-page')).toBeNull();
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/connection');
    expect(get).toHaveBeenCalledWith('/api/dspm/status');
  });

  it('renders the Data Lineage page on /xamlens/data-lineage, keeping its query', async () => {
    renderAt('/xamlens/data-lineage?category=SENSITIVE_FINDINGS');
    expect(await screen.findByTestId('data-lineage-page')).toBeInTheDocument();
    expect(screen.queryByTestId('datastores-page')).toBeNull();
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/data-lineage');
    expect(get).toHaveBeenCalledWith('/api/dspm/status');
  });

  it('explains a missing XamLens tab grant (403 TAB_ACCESS_DENIED) instead of reporting an outage', async () => {
    get.mockRejectedValue(new Error('HTTP 403: {"code":"TAB_ACCESS_DENIED","message":"You do not have access to XamLens."}'));
    renderAt('/xamlens/connection');
    expect(await screen.findByText("You don't have access to XamLens")).toBeInTheDocument();
    expect(screen.getByText('Ask your XamOps administrator to give you access to XamLens.')).toBeInTheDocument();
    expect(screen.queryByText('Temporarily unavailable')).toBeNull();
  });

  it('keeps the outage state for any other failure', async () => {
    get.mockRejectedValue(new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'));
    renderAt('/xamlens/connection');
    expect(await screen.findByText('Temporarily unavailable')).toBeInTheDocument();
  });
});
