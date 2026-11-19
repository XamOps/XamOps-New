import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import type { DspmStatus } from '../../../../types/dspm';
import type { DatastoreFilterOptions } from '../../../../types/dspmDatastores';
import type { SaasFileLineage } from '../../../../types/dspmDrawers';
import type {
  LineageCategories, LineageDatastore, LineageFile, LineagePolicy,
} from '../../../../types/dspmLineage';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../utils/apiClient';
import DataLineagePage from '../DataLineagePage';
import { formatNodeDay } from '../lineageText';

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

// ─── A fake backend with invented data ──────────────────────────────────────────────────────────

const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM'] as const;
const LABEL: Record<string, string> = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium' };
const policyId = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const POLICIES: LineagePolicy[] = Array.from({ length: 9 }, (_, i) => {
  const severity = SEVERITIES[i % 3];
  return {
    id: policyId(i + 1), name: `Policy ${i + 1}`, severity, severityLabel: LABEL[severity], service: 'ENDPOINT',
    datastores: 2,
  };
});
const P1 = POLICIES[0].id!;

const DATASTORES: LineageDatastore[] = [
  { id: 'ds-studio', name: 'Studio MacBook Pro', service: 'ENDPOINT', files: 9 },
  { id: 'ds-ops', name: 'Ops MacBook Air', service: 'ENDPOINT', files: 1 },
];

const DAY = 1_775_000_000;
const fileId = (n: number) => `aaaaaaaaaaaaaaaaaaaaaa${String(n).padStart(2, '0')}`;
const FILES: LineageFile[] = Array.from({ length: 9 }, (_, i) => ({
  id: fileId(i + 1), name: `report-${i + 1}.pdf`, extension: 'pdf', actor: 'alex', modifiedAt: DAY + i * 86_400,
}));

const LINEAGE: SaasFileLineage = {
  available: true,
  coverageNote: null,
  lanes: [{
    key: 'copy-0', fileName: 'report-1.pdf', ownerName: 'Studio MacBook Pro', ownerEmail: null, driveType: null,
    os: 'MAC', provider: null, channelLabel: 'Endpoint', self: true, firstSeenAt: null,
  }],
  events: [{ laneKey: 'copy-0', typeLabel: 'File Modified', actor: 'alex@example.com', occurredAt: DAY }],
};
const NO_LINEAGE: SaasFileLineage = { available: false, coverageNote: null, lanes: [], events: [] };

const OPTIONS: Record<string, DatastoreFilterOptions> = {
  policy: {
    dimensions: [
      { key: 'severity', options: SEVERITIES.map(s => ({ value: s, label: LABEL[s], count: null })), hasMore: false },
      { key: 'datastoreName', options: DATASTORES.map(d => ({ value: d.name!, label: d.name!, count: null })), hasMore: false },
      { key: 'label', options: ['PII', 'PCI'].map(l => ({ value: l, label: l, count: null })), hasMore: false },
    ],
  },
  datastore: {
    dimensions: [{ key: 'severity', options: SEVERITIES.map(s => ({ value: s, label: LABEL[s], count: null })), hasMore: false }],
  },
};

interface Backend {
  categories: LineageCategories[];
  policies?: (params: URLSearchParams) => unknown;
}

function page<T extends { name: string | null }>(rows: T[], params: URLSearchParams) {
  const q = params.get('q')?.toLowerCase() ?? '';
  const matched = q ? rows.filter(r => r.name?.toLowerCase().includes(q)) : rows;
  const skip = Number(params.get('skip') ?? 0);
  const limit = Number(params.get('limit') ?? 7);
  return { available: true, rows: matched.slice(skip, skip + limit), total: matched.length, skip, limit };
}

function serve(backend: Backend) {
  let categoryCalls = 0;
  get.mockImplementation((url: string) => {
    const parsed = new URL(url, 'http://localhost');
    const path = parsed.pathname;
    const params = parsed.searchParams;
    if (path === '/api/dspm/lineage/categories') {
      const answer = backend.categories[Math.min(categoryCalls, backend.categories.length - 1)];
      categoryCalls += 1;
      return Promise.resolve(answer);
    }
    if (path === '/api/dspm/lineage/filter-options') return Promise.resolve(OPTIONS[params.get('level')!]);
    if (path === '/api/dspm/lineage/policies') {
      if (backend.policies) return Promise.resolve(backend.policies(params));
      if (params.get('category') !== 'SENSITIVE_FINDINGS') return Promise.resolve(page([], params));
      return Promise.resolve(page(POLICIES, params));
    }
    if (path === `/api/dspm/lineage/policies/${P1}/datastores`) return Promise.resolve(page(DATASTORES, params));
    if (path === '/api/dspm/lineage/datastores/ds-studio/files') return Promise.resolve(page(FILES, params));
    if (path === `/api/dspm/lineage/files/${fileId(1)}/lineage`) return Promise.resolve(LINEAGE);
    if (path.startsWith('/api/dspm/lineage/files/')) return Promise.resolve(NO_LINEAGE);
    return Promise.reject(new Error(`HTTP 404: {"code":"ASSET_NOT_FOUND","message":"${path}"}`));
  });
}

/** The query of every request to `path`, in order. */
const requests = (path: string) => get.mock.calls
  .map(call => new URL(call[0] as string, 'http://localhost'))
  .filter(u => u.pathname === path)
  .map(u => u.searchParams);

/** The query of the latest request to `path`. */
const lastRequest = (path: string) => {
  const all = requests(path);
  return all[all.length - 1];
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
const search = () => new URLSearchParams(location()!.split('?')[1] ?? '');
const STEP1 = '/xamlens/data-lineage?category=SENSITIVE_FINDINGS';
const policyNodes = () => screen.queryAllByRole('button').filter(b => b.getAttribute('data-node-level') === 'policy');

afterEach(() => {
  vi.useRealTimers();
  get.mockReset();
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
    serve({ categories: [ON] });
    renderAt('/xamlens/data-lineage');
    expect(await screen.findByText('1 exposure')).toBeInTheDocument();
    expect(screen.getByText('1.2k exposures')).toBeInTheDocument();
    expect(screen.getAllByText('0 exposures')).toHaveLength(4);

    await user.click(screen.getByRole('button', { name: /Excessive Exposure/ }));
    expect(location()).toBe('/xamlens/data-lineage?category=EXCESSIVE_EXPOSURE');
    expect(screen.getByRole('heading', { name: 'Data Lineage' })).toBeInTheDocument();
    expect(screen.getByText('Trace file history, access, and sharing across your environment')).toBeInTheDocument();
    expect(await screen.findByText('No exposures found for this category.')).toBeInTheDocument();
  });

  it('goes back to step 0 with no query on "New Investigation" (AC14)', async () => {
    const user = userEvent.setup();
    serve({ categories: [ON] });
    renderAt(`${STEP1}&policy=${P1}`);
    await user.click(await screen.findByRole('button', { name: 'New Investigation' }));
    expect(location()).toBe('/xamlens/data-lineage');
    expect(screen.getByRole('heading', { name: 'Data lineage' })).toBeInTheDocument();
    expect(screen.getByText('Start by choosing a exposure category')).toBeInTheDocument();
  });

  it('opens a single category at once', async () => {
    serve({ categories: [{ available: true, categories: [{ id: 'STALE_DATA', name: 'Stale Data', exposures: 2 }] }] });
    renderAt('/xamlens/data-lineage');
    await waitFor(() => expect(location()).toBe('/xamlens/data-lineage?category=STALE_DATA'));
    expect(screen.getByRole('button', { name: 'New Investigation' })).toBeInTheDocument();
  });

  it('normalises an invalid query with replace, and shows step 0 for an unknown category', async () => {
    serve({ categories: [ON] });
    renderAt('/xamlens/data-lineage?category=NOPE&policyPage=3&junk=1');
    expect(await screen.findByText('1 exposure')).toBeInTheDocument();
    await waitFor(() => expect(location()).toBe('/xamlens/data-lineage'));
  });

  it('keeps a canonical deep link', async () => {
    serve({ categories: [ON] });
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

describe('DataLineagePage, the investigation', () => {
  it('shows step 1: header, panel copy, the category node and 7 policies (AC8)', async () => {
    serve({ categories: [ON] });
    renderAt(STEP1);
    expect(await screen.findByRole('button', { name: 'Policy 1' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Data Lineage' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New Investigation' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Choose a policy' })).toBeInTheDocument();
    expect(screen.getByText('Select a policy to view the datastores it affects.')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Search policies' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Full screen' })).toBeInTheDocument();

    const category = screen.getByRole('button', { name: 'Sensitive Findings' });
    expect(within(category).getByText('9 exposures')).toBeInTheDocument();
    expect(policyNodes()).toHaveLength(7);
    const first = screen.getByRole('button', { name: 'Policy 1' });
    expect(within(first).getByText('Critical')).toBeInTheDocument();
    expect(within(first).getByText('2 datastores')).toBeInTheDocument();
    expect(requests('/api/dspm/lineage/policies')[0].toString()).toBe('category=SENSITIVE_FINDINGS&skip=0&limit=7');
  });

  it('pages with "›" to policyPage=1 and back with "‹" (AC9)', async () => {
    const user = userEvent.setup();
    serve({ categories: [ON] });
    renderAt(STEP1);
    await screen.findByRole('button', { name: 'Policy 1' });
    expect(screen.queryByRole('button', { name: 'Show previous policies' })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Show more policies' }));
    expect(search().get('policyPage')).toBe('1');
    expect(await screen.findByRole('button', { name: 'Policy 8' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Policy 9' })).toBeInTheDocument();
    expect(policyNodes()).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Show more policies' })).toBeNull();
    expect(lastRequest('/api/dspm/lineage/policies').get('skip')).toBe('7');

    await user.click(screen.getByRole('button', { name: 'Show previous policies' }));
    expect(search().get('policyPage')).toBeNull();
    expect(await screen.findByRole('button', { name: 'Policy 1' })).toBeInTheDocument();
  });

  it('sends the search 300 ms after the last keystroke with limit=50, and shows its empty text (AC10)', async () => {
    serve({ categories: [ON] });
    renderAt(STEP1);
    await screen.findByRole('button', { name: 'Policy 1' });
    const box = screen.getByRole('searchbox', { name: 'Search policies' });

    vi.useFakeTimers();
    fireEvent.change(box, { target: { value: 'zz' } });
    await act(async () => { vi.advanceTimersByTime(200); });
    fireEvent.change(box, { target: { value: 'zzz' } });
    await act(async () => { vi.advanceTimersByTime(299); });
    expect(requests('/api/dspm/lineage/policies').some(p => p.has('q'))).toBe(false);
    await act(async () => { vi.advanceTimersByTime(1); });
    vi.useRealTimers();

    expect(search().get('policyQ')).toBe('zzz');
    const searched = requests('/api/dspm/lineage/policies').filter(p => p.has('q'));
    expect(searched).toHaveLength(1);
    expect(searched[0].toString()).toBe('category=SENSITIVE_FINDINGS&q=zzz&skip=0&limit=50');
    expect(await screen.findByText('No policies match “zzz”.')).toBeInTheDocument();
  });

  it('lists the level\'s filters and sends Exclude as severity_exclude (AC11)', async () => {
    const user = userEvent.setup();
    serve({ categories: [ON] });
    renderAt(STEP1);
    await screen.findByRole('button', { name: 'Policy 1' });

    await user.click(screen.getByRole('button', { name: 'More filters' }));
    const panel = await screen.findByRole('dialog', { name: 'More filters' });
    const dimensions = within(within(panel).getByRole('list', { name: 'Filters' })).getAllByRole('button');
    expect(dimensions.map(b => b.textContent?.trim())).toEqual(['Severity', 'Data Store', 'Labels']);
    expect(requests('/api/dspm/lineage/filter-options').map(p => p.toString())).toEqual(['level=policy']);

    await user.selectOptions(within(panel).getByRole('combobox', { name: 'Include or exclude' }), 'exclude');
    await user.click(await within(panel).findByRole('checkbox', { name: 'Critical' }));
    await waitFor(() => expect(lastRequest('/api/dspm/lineage/policies').get('severity_exclude')).toBe('CRITICAL'));
    expect(lastRequest('/api/dspm/lineage/policies').has('severity')).toBe(false);
    expect(await screen.findByRole('button', { name: 'Policy 2' })).toBeInTheDocument();
  });

  it('offers Severity only on the datastore level (AC11, D2)', async () => {
    const user = userEvent.setup();
    serve({ categories: [ON] });
    renderAt(`${STEP1}&policy=${P1}`);
    await screen.findByRole('button', { name: 'Studio MacBook Pro' });
    expect(screen.getByRole('heading', { name: 'Choose a datastore' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'More filters' }));
    const panel = await screen.findByRole('dialog', { name: 'More filters' });
    const dimensions = within(within(panel).getByRole('list', { name: 'Filters' })).getAllByRole('button');
    expect(dimensions.map(b => b.textContent?.trim())).toEqual(['Severity']);
    expect(requests('/api/dspm/lineage/filter-options').map(p => p.toString())).toEqual(['level=datastore']);
  });

  it('chooses a policy, a datastore and a file down to the lineage band (AC12)', async () => {
    const user = userEvent.setup();
    serve({ categories: [ON] });
    renderAt(STEP1);
    await user.click(await screen.findByRole('button', { name: 'Policy 1' }));
    expect(search().get('policy')).toBe(P1);
    expect(screen.getByRole('button', { name: 'Policy 1' })).toHaveAttribute('aria-pressed', 'true');
    const studio = await screen.findByRole('button', { name: 'Studio MacBook Pro' });
    expect(within(studio).getByText('9 files')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Choose a datastore' })).toBeInTheDocument();

    await user.click(studio);
    expect(search().get('datastore')).toBe('ds-studio');
    const file = await screen.findByRole('button', { name: 'report-1.pdf' });
    expect(within(file).getByText('Actor: alex')).toBeInTheDocument();
    expect(within(file).getByText(formatNodeDay(DAY))).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Choose a file' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show more files' })).toBeInTheDocument();

    await user.click(file);
    expect(search().get('file')).toBe(fileId(1));
    expect(await screen.findByRole('button', { name: /File Modified/ })).toBeInTheDocument();
    expect(document.querySelector('[data-lineage-band]')).not.toBeNull();
    expect(screen.getByRole('heading', { name: 'File lineage' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Search events' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'More filters' })).toBeNull();
  });

  it('applies the click rules (AC13)', async () => {
    const user = userEvent.setup();
    serve({ categories: [ON] });
    renderAt(`${STEP1}&policy=${P1}&datastore=ds-studio`);
    await screen.findByRole('button', { name: 'report-1.pdf' });

    // The chosen policy with a chosen datastore below: stays chosen, the datastore is cleared.
    await user.click(screen.getByRole('button', { name: 'Policy 1' }));
    expect(location()).toBe(`${STEP1}&policy=${P1}`);
    expect(screen.queryByRole('button', { name: 'report-1.pdf' })).toBeNull();

    // The deepest chosen datastore: unchosen.
    await user.click(await screen.findByRole('button', { name: 'Studio MacBook Pro' }));
    expect(search().get('datastore')).toBe('ds-studio');
    await user.click(screen.getByRole('button', { name: 'Studio MacBook Pro' }));
    expect(location()).toBe(`${STEP1}&policy=${P1}`);

    // The category node clears every level below it, then does nothing.
    await user.click(screen.getByRole('button', { name: 'Sensitive Findings' }));
    expect(location()).toBe(STEP1);
    await user.click(screen.getByRole('button', { name: 'Sensitive Findings' }));
    expect(location()).toBe(STEP1);
  });

  it('opens full screen as a modal of at most 1280 px, and Escape returns focus to its button (AC16)', async () => {
    const user = userEvent.setup();
    serve({ categories: [ON] });
    renderAt(STEP1);
    await screen.findByRole('button', { name: 'Policy 1' });
    await user.click(screen.getByRole('button', { name: 'Full screen' }));

    const dialog = screen.getByRole('dialog', { name: 'Choose a policy' });
    expect(dialog).toHaveClass('max-w-[1280px]');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(within(dialog).getByRole('button', { name: 'Policy 1' })).toBeInTheDocument();
    expect(within(dialog).getByRole('searchbox', { name: 'Search policies' })).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Full screen' })).toBeNull();
    expect(within(dialog).getByRole('button', { name: 'Exit full screen' })).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Choose a policy' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Full screen' })).toHaveFocus();
  });

  it('shows the band messages: no lineage, and an event search without a match (AC19)', async () => {
    const user = userEvent.setup();
    serve({ categories: [ON] });
    renderAt(`${STEP1}&policy=${P1}&datastore=ds-studio&file=${fileId(2)}`);
    expect(await screen.findByText('No lineage recorded for this file yet.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'report-1.pdf' }));
    expect(await screen.findByRole('button', { name: /File Modified/ })).toBeInTheDocument();
    await user.type(screen.getByRole('searchbox', { name: 'Search events' }), 'zzz');
    expect(await screen.findByText('Nothing matches this search.')).toBeInTheDocument();
    expect(search().get('eventQ')).toBe('zzz');
  });

  it('goes back to step 0 when a row answers available: false', async () => {
    serve({
      categories: [ON, OFF],
      policies: params => ({ available: false, rows: [], total: 0, skip: Number(params.get('skip')), limit: 7 }),
    });
    renderAt(STEP1);
    await waitFor(() => expect(location()).toBe('/xamlens/data-lineage'));
    expect(await screen.findByRole('heading', { name: 'Data lineage' })).toBeInTheDocument();
    expect(requests('/api/dspm/lineage/categories')).toHaveLength(2);
  });

  it('resets a stale page to 0', async () => {
    serve({ categories: [ON] });
    renderAt(`${STEP1}&policyPage=5`);
    await waitFor(() => expect(location()).toBe(STEP1));
    expect(await screen.findByRole('button', { name: 'Policy 1' })).toBeInTheDocument();
  });
});

describe('DataLineagePage, not ready', () => {
  it('shows DspmNotReady and requests nothing', () => {
    renderAt('/xamlens/data-lineage', { ...READY, ready: false, configured: false, reason: 'NOT_CONFIGURED' });
    expect(screen.getByText('Your data security workspace is being set up')).toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });
});
