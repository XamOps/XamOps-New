import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import type { DspmStatus } from '../../../../types/dspm';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn(() => new Promise(() => undefined)) } }));

import FilesPage from '../FilesPage';

const READY: DspmStatus = {
  ready: true, entitled: true, configured: true, reason: null, message: null,
  projectName: 'Example workspace', lastVerifiedAt: null,
};

const LocationProbe: React.FC = () => <div data-testid="location">{useLocation().pathname}</div>;

function renderAt(tab: 'saas' | 'endpoint', status: DspmStatus = READY, onRetry = vi.fn()) {
  return render(
    <MemoryRouter initialEntries={[`/xamlens/files/${tab}`]}>
      <Routes>
        <Route path="/xamlens/files/:filesTab" element={<FilesPage tab={tab} status={status} onRetry={onRetry} />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

afterEach(() => vi.clearAllMocks());

describe('FilesPage', () => {
  it('reads "Files" with its subtitle', () => {
    renderAt('saas');
    expect(screen.getByRole('heading', { level: 1, name: 'Files' })).toBeInTheDocument();
    expect(screen.getByText('Files discovered across your connected sources')).toBeInTheDocument();
  });

  it('draws the SaaS and Endpoint tabs with the active one selected', () => {
    renderAt('endpoint');
    const tablist = screen.getByRole('tablist', { name: 'File sources' });
    expect(tablist).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map(t => t.textContent)).toEqual(['SaaS', 'Endpoint']);
    expect(screen.getByRole('tab', { name: 'Endpoint' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'SaaS' })).toHaveAttribute('aria-selected', 'false');
  });

  it('has no Export, KPI card or scan button', () => {
    renderAt('saas');
    expect(screen.queryByRole('button', { name: /export/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /scan/i })).toBeNull();
    expect(screen.queryByText(/Total/)).toBeNull();
  });

  it('navigates on a tab click', async () => {
    renderAt('saas');
    await userEvent.setup().click(screen.getByRole('tab', { name: 'Endpoint' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/files/endpoint');
  });

  it('explains a workspace that is not ready instead of the tabs', () => {
    renderAt('saas', { ...READY, ready: false, configured: false, reason: 'NOT_CONFIGURED', message: null });
    expect(screen.queryByRole('tablist')).toBeNull();
    expect(screen.queryByRole('heading', { level: 1, name: 'Files' })).toBeNull();
  });
});
