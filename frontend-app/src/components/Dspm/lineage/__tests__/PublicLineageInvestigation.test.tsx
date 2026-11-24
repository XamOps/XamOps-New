import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import type { SaasFileLineage } from '../../../../types/dspmDrawers';
import type {
  PublicFileLineage, PublicLineageDatastore, PublicLineageFile,
} from '../../../../types/dspmLineage';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../utils/apiClient';
import PublicLineageInvestigation from '../PublicLineageInvestigation';
import { formatNodeDay } from '../lineageText';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

// ─── A fake backend with invented data ──────────────────────────────────────────────────────────

const ep = (n: number) => `ep-${'a'.repeat(23)}${n}`;
const dr = (n: number) => `dr-${'b'.repeat(23)}${n}-Person_${n}`;
const EP1 = ep(1);
const EP2 = ep(2);
const EP3 = ep(3);
const DR1 = dr(4);

const DATASTORES: PublicLineageDatastore[] = [
  { id: EP1, kind: 'ENDPOINT', name: 'Studio Laptop', service: 'ENDPOINT', files: 9 },
  { id: EP2, kind: 'ENDPOINT', name: 'Ops Laptop', service: 'ENDPOINT', files: null },
  { id: EP3, kind: 'ENDPOINT', name: 'Lab Laptop', service: 'ENDPOINT', files: 1 },
  ...[4, 5, 6, 7, 8, 9].map((n): PublicLineageDatastore => ({
    id: dr(n), kind: 'DRIVE', name: `Person ${n}`, service: 'GOOGLE_DRIVE', files: 3,
  })),
];

const DAY = 1_775_000_000;
const fileId = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const ENDPOINT_FILES: PublicLineageFile[] = Array.from({ length: 9 }, (_, i) => ({
  id: fileId(i + 1), name: `report-${i + 1}.pdf`, extension: 'pdf', actor: 'sam',
  dateAt: DAY + i * 86_400, createdAt: i === 1 ? null : DAY - 86_400,
}));
const DOC1 = 'DocOne_abcdefghijklmnopqrstuvwxyz01';
const DOC2 = 'DocTwo_abcdefghijklmnopqrstuvwxyz02';
const DRIVE_FILES: PublicLineageFile[] = [
  { id: DOC1, name: 'budget.xlsx', extension: 'xlsx', actor: null, dateAt: DAY, createdAt: null },
  { id: DOC2, name: 'notes.docx', extension: 'docx', actor: null, dateAt: DAY + 86_400, createdAt: null },
];

const UNAVAILABLE: SaasFileLineage = { available: false, coverageNote: null, lanes: [], events: [] };
const COPIES: SaasFileLineage = {
  available: true,
  coverageNote: null,
  lanes: [
    { key: 'copy-0', fileName: 'report-1.pdf', ownerName: 'Studio Laptop', ownerEmail: null, driveType: null, os: 'MAC',
      provider: null, channelLabel: 'Endpoint', self: true, firstSeenAt: null },
    { key: 'copy-1', fileName: 'report-1 (1).pdf', ownerName: 'Spare Laptop', ownerEmail: null, driveType: null,
      os: 'WINDOWS', provider: null, channelLabel: 'Endpoint', self: false, firstSeenAt: null },
  ],
  events: [],
};
const endpointLineage = (title: string): PublicFileLineage => ({
  kind: 'ENDPOINT',
  asset: {
    title, device: 'Studio Laptop', osUser: 'sam', firstSeenAt: DAY,
    events: [{ laneKey: null, typeLabel: 'File Modified', actor: 'sam@example.com', occurredAt: DAY + 3_600 }],
  },
  copies: COPIES,
  ownerEmail: null,
});
const DRIVE_LINEAGE: PublicFileLineage = {
  kind: 'DRIVE',
  asset: {
    title: 'budget.xlsx', device: null, osUser: null, firstSeenAt: DAY,
    events: [{ laneKey: null, typeLabel: 'Shared', actor: 'robin@example.com', occurredAt: DAY + 3_600 }],
  },
  copies: UNAVAILABLE,
  ownerEmail: 'robin@example.com',
};

const BASE = '/api/dspm/lineage/public/datastores';

function page<T extends { name: string | null }>(rows: T[], params: URLSearchParams) {
  const q = params.get('q')?.toLowerCase() ?? '';
  const matched = q ? rows.filter(r => r.name?.toLowerCase().includes(q)) : rows;
  const skip = Number(params.get('skip') ?? 0);
  const limit = Number(params.get('limit') ?? 7);
  return { rows: matched.slice(skip, skip + limit), total: matched.length, skip, limit };
}

const upstreamDown = () => Promise.reject(new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'));

/** `fail` lists paths that fail once, then answer. */
function serve(fail: string[] = []) {
  const failing = new Set(fail);
  get.mockImplementation((url: string) => {
    const parsed = new URL(url, 'http://localhost');
    const path = parsed.pathname;
    const params = parsed.searchParams;
    if (failing.delete(path)) return upstreamDown();
    if (path === BASE) return Promise.resolve(page(DATASTORES, params));
    if (path === `${BASE}/${EP1}/files`) return Promise.resolve(page(ENDPOINT_FILES, params));
    if (path === `${BASE}/${EP3}/files`) return Promise.resolve(page(ENDPOINT_FILES.slice(0, 1), params));
    if (path === `${BASE}/${DR1}/files`) return Promise.resolve(page(DRIVE_FILES, params));
    if (path === `${BASE}/${DR1}/files/${DOC1}/lineage`) return Promise.resolve(DRIVE_LINEAGE);
    if (path === `${BASE}/${DR1}/files/${DOC2}/lineage`) {
      return Promise.resolve({ ...DRIVE_LINEAGE, asset: null });
    }
    const lineage = /^\/api\/dspm\/lineage\/public\/datastores\/ep-[0-9a-f]{24}\/files\/([0-9a-f-]{36})\/lineage$/.exec(path);
    if (lineage) {
      const file = ENDPOINT_FILES.find(f => f.id === lineage[1]);
      if (file) return Promise.resolve(endpointLineage(file.name!));
    }
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

const PAGE = '/xamlens/data-lineage';

function renderAt(url: string, projectName: string | null = 'Example workspace') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path={PAGE} element={<PublicLineageInvestigation projectName={projectName} />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;
const search = () => new URLSearchParams(location()!.split('?')[1] ?? '');
const nodes = (level: string) => screen.queryAllByRole('button').filter(b => b.getAttribute('data-node-level') === level);
const lane = (key: string) => document.querySelector(`[data-lane="${key}"]`) as HTMLElement | null;

afterEach(() => {
  vi.useRealTimers();
  get.mockReset();
});

describe('PublicLineageInvestigation, the datastore step', () => {
  it('opens under the project root with the header, the panel copy and 7 datastores (AC1, AC2)', async () => {
    serve();
    renderAt(PAGE);
    expect(await screen.findByRole('button', { name: 'Studio Laptop' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Data Lineage' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New Investigation' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Choose a datastore' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Search datastores' })).toBeInTheDocument();

    const root = screen.getByRole('button', { name: 'Example workspace' });
    expect(root).toHaveAttribute('aria-pressed', 'true');
    expect(root).toHaveAttribute('data-node-level', 'root');
    expect(nodes('datastore')).toHaveLength(7);
    expect(requests(BASE).map(p => p.toString())).toEqual(['skip=0&limit=7']);
    expect(get.mock.calls.every(call => (call[0] as string).startsWith(BASE))).toBe(true);
  });

  it('names the root "Project" without a project name', async () => {
    serve();
    renderAt(PAGE, null);
    expect(await screen.findByRole('button', { name: 'Project' })).toHaveAttribute('data-node-level', 'root');
  });

  it('draws each datastore\'s icon and file count, and no chip when the count is unknown (AC4)', async () => {
    serve();
    renderAt(PAGE);
    const studio = await screen.findByRole('button', { name: 'Studio Laptop' });
    expect(studio.querySelector('[data-icon="mdi:laptop"]')).not.toBeNull();
    expect(within(studio).getByText('9 files')).toBeInTheDocument();
    const ops = screen.getByRole('button', { name: 'Ops Laptop' });
    expect(within(ops).queryByText(/files?$/)).toBeNull();
    const drive = screen.getByRole('button', { name: 'Person 4' });
    expect(drive.querySelector('[data-icon="mdi:google-drive"]')).not.toBeNull();
    expect(within(drive).getByText('3 files')).toBeInTheDocument();
  });

  it('pages with "›" to datastorePage=1 and back with "‹" (AC5)', async () => {
    const user = userEvent.setup();
    serve();
    renderAt(PAGE);
    await screen.findByRole('button', { name: 'Studio Laptop' });
    expect(screen.queryByRole('button', { name: 'Show previous datastores' })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Show more datastores' }));
    expect(search().get('datastorePage')).toBe('1');
    expect(await screen.findByRole('button', { name: 'Person 9' })).toBeInTheDocument();
    expect(nodes('datastore')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Show more datastores' })).toBeNull();
    expect(lastRequest(BASE).toString()).toBe('skip=7&limit=7');

    await user.click(screen.getByRole('button', { name: 'Show previous datastores' }));
    expect(search().get('datastorePage')).toBeNull();
    expect(await screen.findByRole('button', { name: 'Studio Laptop' })).toBeInTheDocument();
  });

  it('sends the search 300 ms after the last keystroke with q and limit=50 (AC6)', async () => {
    serve();
    renderAt(PAGE);
    await screen.findByRole('button', { name: 'Studio Laptop' });
    const box = screen.getByRole('searchbox', { name: 'Search datastores' });

    vi.useFakeTimers();
    fireEvent.change(box, { target: { value: 'lap' } });
    await act(async () => { vi.advanceTimersByTime(299); });
    expect(requests(BASE).some(p => p.has('q'))).toBe(false);
    await act(async () => { vi.advanceTimersByTime(1); });
    vi.useRealTimers();

    expect(search().get('datastoreQ')).toBe('lap');
    const searched = requests(BASE).filter(p => p.has('q'));
    expect(searched.map(p => p.toString())).toEqual(['q=lap&skip=0&limit=50']);
    await waitFor(() => expect(nodes('datastore')).toHaveLength(3));
    expect(screen.queryByRole('button', { name: 'Show more datastores' })).toBeNull();
  });

  it('shows "+ More filters" greyed with "Disabled by admin", and it sends nothing (AC7)', async () => {
    const user = userEvent.setup();
    serve();
    renderAt(PAGE);
    await screen.findByRole('button', { name: 'Studio Laptop' });
    const filters = screen.getByRole('button', { name: 'More filters' });
    expect(filters).toHaveAttribute('aria-disabled', 'true');
    const calls = get.mock.calls.length;
    await user.hover(filters);
    expect(screen.getByRole('tooltip')).toHaveTextContent('Disabled by admin');
    await user.click(filters);
    expect(get.mock.calls.length).toBe(calls);
    expect(screen.queryByRole('dialog', { name: 'More filters' })).toBeNull();
  });

  it('shows the row error with Retry, and Retry requests again (AC15)', async () => {
    const user = userEvent.setup();
    serve([BASE]);
    renderAt(PAGE);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('button', { name: 'Studio Laptop' })).toBeInTheDocument();
    expect(requests(BASE)).toHaveLength(2);
  });
});

describe('PublicLineageInvestigation, files and lineage', () => {
  it('lists an endpoint\'s files with the actor line and pages them (AC8)', async () => {
    const user = userEvent.setup();
    serve();
    renderAt(PAGE);
    await user.click(await screen.findByRole('button', { name: 'Studio Laptop' }));
    expect(search().get('datastore')).toBe(EP1);
    const file = await screen.findByRole('button', { name: 'report-1.pdf' });
    expect(within(file).getByText('Actor: sam')).toBeInTheDocument();
    expect(within(file).getByText(formatNodeDay(DAY))).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Choose a file' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'More filters' })).toBeNull();
    expect(requests(`${BASE}/${EP1}/files`).map(p => p.toString())).toEqual(['skip=0&limit=7']);

    await user.click(screen.getByRole('button', { name: 'Show more files' }));
    expect(search().get('filePage')).toBe('1');
    expect(await screen.findByRole('button', { name: 'report-9.pdf' })).toBeInTheDocument();
  });

  it('lists a drive person\'s files without the actor line (AC9)', async () => {
    serve();
    renderAt(`${PAGE}?datastore=${DR1}`);
    const file = await screen.findByRole('button', { name: 'budget.xlsx' });
    expect(within(file).queryByText(/Actor:/)).toBeNull();
    expect(within(file).getByText(formatNodeDay(DAY))).toBeInTheDocument();
  });

  it('searches the first 100 files by name in the browser', async () => {
    const user = userEvent.setup();
    serve();
    renderAt(`${PAGE}?datastore=${EP1}`);
    await screen.findByRole('button', { name: 'report-1.pdf' });
    await user.type(screen.getByRole('searchbox', { name: 'Search files' }), 'report-9');
    await waitFor(() => expect(nodes('file')).toHaveLength(1));
    expect(screen.getByRole('button', { name: 'report-9.pdf' })).toBeInTheDocument();
    expect(lastRequest(`${BASE}/${EP1}/files`).toString()).toBe('skip=0&limit=100');
  });

  it('draws an endpoint file\'s band: File Created, the recorded event and the copy lane (AC10)', async () => {
    const user = userEvent.setup();
    serve();
    renderAt(`${PAGE}?datastore=${EP1}`);
    await user.click(await screen.findByRole('button', { name: 'report-1.pdf' }));
    expect(search().get('file')).toBe(fileId(1));
    expect(await screen.findByRole('button', { name: /^File Created/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^File Modified/ })).toBeInTheDocument();
    expect(lane('endpoint-asset')).toHaveTextContent('Studio Laptop');
    expect(lane('copy-1')).toHaveTextContent('Spare Laptop');
    expect(screen.getByRole('heading', { name: 'File lineage' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Search events' })).toBeInTheDocument();
  });

  it('draws "First Seen" for an endpoint file without a creation time', async () => {
    serve();
    renderAt(`${PAGE}?datastore=${EP1}&file=${fileId(2)}`);
    expect(await screen.findByRole('button', { name: /^First Seen/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^File Created/ })).toBeNull();
  });

  it('draws a drive file\'s band: the owner e-mail, the Google Drive logo, First Seen and Shared (AC11)', async () => {
    serve();
    renderAt(`${PAGE}?datastore=${DR1}&file=${DOC1}`);
    expect(await screen.findByRole('button', { name: /^First Seenrobin/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Shared/ })).toBeInTheDocument();
    const own = lane('endpoint-asset')!;
    expect(own).toHaveTextContent('robin@example.com');
    expect(own.querySelector('[data-icon="logos:google-drive"]')).not.toBeNull();
    expect(requests(`${BASE}/${DR1}/files/${DOC1}/lineage`)).toHaveLength(1);
  });

  it('shows the no-lineage text for a file without an asset, and the event search\'s empty text (AC12)', async () => {
    const user = userEvent.setup();
    serve();
    renderAt(`${PAGE}?datastore=${DR1}&file=${DOC2}`);
    expect(await screen.findByText('No lineage recorded for this file yet.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'budget.xlsx' }));
    expect(await screen.findByRole('button', { name: /^Shared/ })).toBeInTheDocument();
    await user.type(screen.getByRole('searchbox', { name: 'Search events' }), 'zzz');
    expect(await screen.findByText('Nothing matches this search.')).toBeInTheDocument();
    expect(search().get('eventQ')).toBe('zzz');
  });

  it('shows the file row\'s and the band\'s errors with Retry (AC15)', async () => {
    const user = userEvent.setup();
    serve([`${BASE}/${EP1}/files`, `${BASE}/${EP1}/files/${fileId(1)}/lineage`]);
    renderAt(`${PAGE}?datastore=${EP1}&file=${fileId(1)}`);
    await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(2));
    for (const retry of screen.getAllByRole('button', { name: 'Retry' })) await user.click(retry);
    expect(await screen.findByRole('button', { name: 'report-1.pdf' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /^File Modified/ })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('PublicLineageInvestigation, navigation', () => {
  it('opens the event drawer from the popover, and Escape closes popover, drawer and full screen in turn (AC13)', async () => {
    const user = userEvent.setup();
    serve();
    renderAt(`${PAGE}?datastore=${EP1}&file=${fileId(1)}`);

    const card = await screen.findByRole('button', { name: /^File Modified/ });
    await user.click(card);
    const popover = screen.getByRole('dialog', { name: 'Event details' });
    await user.click(within(popover).getByRole('button', { name: 'View full details →' }));
    const drawer = screen.getByRole('dialog', { name: 'File Modified' });
    expect(within(drawer).getByRole('region', { name: 'Summary' })).toHaveTextContent('sam@example.com');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'File Modified' })).toBeNull();
    expect(card).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Full screen' }));
    const modal = screen.getByRole('dialog', { name: 'File lineage' });
    const modalCard = within(modal).getByRole('button', { name: /^File Modified/ });
    await user.click(modalCard);
    expect(screen.getByRole('dialog', { name: 'Event details' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Event details' })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'File lineage' })).toBeInTheDocument();

    await user.click(modalCard);
    await user.click(within(screen.getByRole('dialog', { name: 'Event details' })).getByRole('button', { name: 'View full details →' }));
    expect(screen.getByRole('dialog', { name: 'File Modified' })).toHaveClass('z-[80]');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'File Modified' })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'File lineage' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'File lineage' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Full screen' })).toHaveFocus();
  });

  it('applies the click rules, the root going back to the datastore step (AC14)', async () => {
    const user = userEvent.setup();
    serve();
    renderAt(`${PAGE}?datastore=${EP1}&file=${fileId(1)}&eventQ=mod`);
    await screen.findByRole('button', { name: /^File Modified/ });

    // The chosen datastore with a chosen file below: stays chosen, the file is cleared.
    await user.click(screen.getByRole('button', { name: 'Studio Laptop' }));
    expect(location()).toBe(`${PAGE}?datastore=${EP1}`);
    expect(screen.getByRole('heading', { name: 'Choose a file' })).toBeInTheDocument();

    // The deepest chosen datastore: unchosen.
    await user.click(screen.getByRole('button', { name: 'Studio Laptop' }));
    expect(location()).toBe(PAGE);
    expect(screen.getByRole('heading', { name: 'Choose a datastore' })).toBeInTheDocument();

    // The root does nothing without a choice, and clears everything with one.
    await user.click(screen.getByRole('button', { name: 'Example workspace' }));
    expect(location()).toBe(PAGE);
    await user.click(screen.getByRole('button', { name: 'Person 4' }));
    expect(search().get('datastore')).toBe(DR1);
    await user.click(screen.getByRole('button', { name: 'Example workspace' }));
    expect(location()).toBe(PAGE);
  });

  it('goes back to the datastore step on "New Investigation"', async () => {
    const user = userEvent.setup();
    serve();
    renderAt(`${PAGE}?datastore=${EP1}&file=${fileId(1)}&datastorePage=1`);
    await screen.findByRole('button', { name: /^File Modified/ });
    await user.click(screen.getByRole('button', { name: 'New Investigation' }));
    expect(location()).toBe(PAGE);
    expect(screen.getByRole('heading', { name: 'Choose a datastore' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Studio Laptop' })).toBeInTheDocument();
  });

  it('restores a deep link, canonicalising its ids and dropping build 4\'s keys', async () => {
    serve();
    const upper = EP1.toUpperCase().replace('EP-', 'ep-');
    renderAt(`${PAGE}?category=SENSITIVE_FINDINGS&file=${fileId(1).toUpperCase()}&datastore=${upper}&eventQ=x`);
    await waitFor(() => expect(location()).toBe(`${PAGE}?datastore=${EP1}&file=${fileId(1)}&eventQ=x`));
    expect(await screen.findByRole('button', { name: 'report-1.pdf' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Studio Laptop' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('searchbox', { name: 'Search events' })).toHaveValue('x');
  });

  it('resets a stale datastore page to 0', async () => {
    serve();
    renderAt(`${PAGE}?datastorePage=5`);
    await waitFor(() => expect(location()).toBe(PAGE));
    expect(await screen.findByRole('button', { name: 'Studio Laptop' })).toBeInTheDocument();
  });
});
