import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import type { DspmStatus } from '../../../../types/dspm';
import type { LineageCategories } from '../../../../types/dspmLineage';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../utils/apiClient';
import DataLineagePage from '../DataLineagePage';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

const READY: DspmStatus = {
  ready: true, entitled: true, configured: true, reason: null, message: null,
  projectName: 'Example workspace', lastVerifiedAt: null,
};

const TITLES: Array<[string, string]> = [
  ['SENSITIVE_FINDINGS', 'Sensitive Findings'], ['EXCESSIVE_EXPOSURE', 'Excessive Exposure'],
  ['HIGH_VOLUME_ACCESS', 'High Volume Access'], ['STALE_DATA', 'Stale Data'], ['RISKY_USER', 'Risky User'],
  ['PUBLIC_EXPOSURE', 'Public Exposure'],
];

const OFF: LineageCategories = {
  available: false, categories: TITLES.map(([id, name]) => ({ id, name, exposures: null })),
};
const ON: LineageCategories = {
  available: true, categories: TITLES.map(([id, name], i) => ({ id, name, exposures: [1, 0, 1_234, 0, 0, 0][i] })),
};

const LocationProbe: React.FC = () => {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
};

function renderAt(url: string, status: DspmStatus = READY) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/xamlens/data-lineage" element={<DataLineagePage status={status} onRetry={vi.fn()} />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;

afterEach(() => {
  vi.clearAllMocks();
});

describe('DataLineagePage, switched off', () => {
  it('requests only the categories and shows step 0 greyed (AC4)', async () => {
    get.mockResolvedValue(OFF);
    renderAt('/xamlens/data-lineage');
    expect(await screen.findByRole('heading', { name: 'Data lineage' })).toBeInTheDocument();
    expect(screen.getAllByRole('button').filter(b => b.getAttribute('aria-disabled') === 'true')).toHaveLength(6);
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('/api/dspm/lineage/categories');
  });

  it('strips a deep link\'s query and stays at step 0 (AC5)', async () => {
    get.mockResolvedValue(OFF);
    renderAt('/xamlens/data-lineage?category=SENSITIVE_FINDINGS&policy=0f8e2c4a-1b3d-4e5f-8a9b-0c1d2e3f4a5b');
    expect(await screen.findByRole('heading', { name: 'Data lineage' })).toBeInTheDocument();
    await waitFor(() => expect(location()).toBe('/xamlens/data-lineage'));
    expect(screen.queryByRole('button', { name: 'New Investigation' })).toBeNull();
    expect(get).toHaveBeenCalledTimes(1);
    expect(get.mock.calls.map(c => c[0])).toEqual(['/api/dspm/lineage/categories']);
  });
});

describe('DataLineagePage, switched on', () => {
  it('shows the counts, and a card opens ?category= with the investigation header (AC7)', async () => {
    const user = userEvent.setup();
    get.mockResolvedValue(ON);
    renderAt('/xamlens/data-lineage');
    expect(await screen.findByText('1 exposure')).toBeInTheDocument();
    expect(screen.getByText('1.2k exposures')).toBeInTheDocument();
    expect(screen.getAllByText('0 exposures')).toHaveLength(4);

    await user.click(screen.getByRole('button', { name: /Excessive Exposure/ }));
    expect(location()).toBe('/xamlens/data-lineage?category=EXCESSIVE_EXPOSURE');
    expect(screen.getByRole('heading', { name: 'Data Lineage' })).toBeInTheDocument();
    expect(screen.getByText('Trace file history, access, and sharing across your environment')).toBeInTheDocument();
  });

  it('goes back to step 0 with no query on "New Investigation" (AC14)', async () => {
    const user = userEvent.setup();
    get.mockResolvedValue(ON);
    renderAt('/xamlens/data-lineage?category=SENSITIVE_FINDINGS&policy=0f8e2c4a-1b3d-4e5f-8a9b-0c1d2e3f4a5b');
    await user.click(await screen.findByRole('button', { name: 'New Investigation' }));
    expect(location()).toBe('/xamlens/data-lineage');
    expect(screen.getByRole('heading', { name: 'Data lineage' })).toBeInTheDocument();
    expect(screen.getByText('Start by choosing a exposure category')).toBeInTheDocument();
  });

  it('opens a single category at once', async () => {
    get.mockResolvedValue({ available: true, categories: [{ id: 'STALE_DATA', name: 'Stale Data', exposures: 2 }] });
    renderAt('/xamlens/data-lineage');
    await waitFor(() => expect(location()).toBe('/xamlens/data-lineage?category=STALE_DATA'));
    expect(screen.getByRole('button', { name: 'New Investigation' })).toBeInTheDocument();
  });

  it('normalises an invalid query with replace, and shows step 0 for an unknown category', async () => {
    get.mockResolvedValue(ON);
    renderAt('/xamlens/data-lineage?category=NOPE&policyPage=3&junk=1');
    expect(await screen.findByText('1 exposure')).toBeInTheDocument();
    await waitFor(() => expect(location()).toBe('/xamlens/data-lineage'));
  });

  it('keeps a canonical deep link', async () => {
    get.mockResolvedValue(ON);
    renderAt('/xamlens/data-lineage?category=RISKY_USER&policyPage=2');
    expect(await screen.findByRole('button', { name: 'New Investigation' })).toBeInTheDocument();
    expect(location()).toBe('/xamlens/data-lineage?category=RISKY_USER&policyPage=2');
  });

  it('shows the error with Retry, and Retry requests again', async () => {
    const user = userEvent.setup();
    get.mockRejectedValueOnce(new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'));
    get.mockResolvedValueOnce(ON);
    renderAt('/xamlens/data-lineage');
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('1 exposure')).toBeInTheDocument();
    expect(get).toHaveBeenCalledTimes(2);
  });
});

describe('DataLineagePage, not ready', () => {
  it('shows DspmNotReady and requests nothing', () => {
    renderAt('/xamlens/data-lineage', { ...READY, ready: false, configured: false, reason: 'NOT_CONFIGURED' });
    expect(screen.getByText('Your data security workspace is being set up')).toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });
});
