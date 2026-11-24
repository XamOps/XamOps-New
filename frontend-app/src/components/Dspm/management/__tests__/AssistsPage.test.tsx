import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DspmStatus } from '../../../../types/dspm';
import type { Assist, AssistList } from '../../../../types/dspmPages';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
vi.mock('goey-toast', () => ({ gooeyToast: { success: vi.fn(), error: vi.fn() } }));

import apiClient from '../../../../utils/apiClient';
import { gooeyToast } from 'goey-toast';
import AssistsPage from '../AssistsPage';
import { CONSOLE_ONLY_TOOLTIP } from '../../datastores/ui/ConsoleOnly';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const toastSuccess = gooeyToast.success as unknown as ReturnType<typeof vi.fn>;

const READY: DspmStatus = {
  ready: true, entitled: true, configured: true, reason: null, message: null,
  projectName: 'Example workspace', lastVerifiedAt: null,
};
const NOT_READY: DspmStatus = {
  ready: false, entitled: true, configured: false, reason: 'NOT_CONFIGURED', message: null,
  projectName: null, lastVerifiedAt: null,
};

const DAY = 86_400;
const now = () => Math.floor(Date.now() / 1000);

const assist = (over: Partial<Assist> & Pick<Assist, 'id' | 'name'>): Assist => ({
  clientId: null, healthStatus: 'ACTIVE', statusLabel: 'Active', assistType: 'ON_PREM', assistTypeLabel: 'On Prem',
  version: '3.1.0', secretExpiresAt: now() + 120 * DAY, ...over,
});

const LIST = (): AssistList => ({
  assists: [
    assist({ id: 'a1', name: 'north-relay', clientId: 'c2FtcGxlLWNsaWVudC1pZC1vbmU=' }),
    assist({ id: 'a2', name: 'south-relay', healthStatus: 'INACTIVE', statusLabel: 'Disabled', version: null }),
    assist({ id: 'a3', name: 'east-relay', secretExpiresAt: now() + 10 * DAY - 60, assistTypeLabel: null }),
    assist({ id: 'a4', name: 'west-relay', secretExpiresAt: now() - 2 * DAY }),
  ],
});

let answer: () => Promise<unknown>;

beforeEach(() => {
  answer = async () => LIST();
  get.mockImplementation(async (url: string) => {
    if (url === '/api/dspm/assists') return answer();
    throw new Error(`HTTP 404: unexpected ${url}`);
  });
});

afterEach(() => vi.clearAllMocks());

const renderPage = (status: DspmStatus = READY) => render(<AssistsPage status={status} onRetry={() => {}} />);
const rowOf = (name: string) => screen.getByText(name).closest('tr') as HTMLElement;
const cells = (name: string) => within(rowOf(name)).getAllByRole('cell').map(c => c.textContent);

describe('AssistsPage', () => {
  it('lists the assists with the console\'s seven columns', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Assists' })).toBeInTheDocument();
    await screen.findByText('north-relay');
    expect(screen.getAllByRole('columnheader').map(h => h.textContent)).toEqual([
      'Assist Name', 'Client ID', 'Status', 'Assist Type', 'Version', 'Secret Expiry', 'Actions',
    ]);
    expect(screen.getAllByRole('row').slice(1)).toHaveLength(4);
    expect(get).toHaveBeenCalledWith('/api/dspm/assists');
  });

  it('shows Active and Disabled status chips in green and red', async () => {
    renderPage();
    await screen.findByText('north-relay');
    const active = within(rowOf('north-relay')).getByText('Active');
    const disabled = within(rowOf('south-relay')).getByText('Disabled');
    expect(active.querySelector('span')).toHaveClass('bg-emerald-500');
    expect(disabled.querySelector('span')).toHaveClass('bg-rose-500');
  });

  it('shows "-" for a missing version, type and client id', async () => {
    renderPage();
    await screen.findByText('north-relay');
    expect(cells('south-relay')[1]).toBe('-');
    expect(cells('south-relay')[4]).toBe('-');
    expect(cells('east-relay')[3]).toBe('-');
    expect(within(rowOf('south-relay')).queryByRole('button', { name: 'Copy Client ID' })).toBeNull();
  });

  it('tags an expiry 10 days ahead and a past one, and not a far one', async () => {
    renderPage();
    await screen.findByText('north-relay');
    expect(within(rowOf('east-relay')).getByText('Expires in 10 days')).toBeInTheDocument();
    expect(within(rowOf('west-relay')).getByText('Expired')).toBeInTheDocument();
    expect(cells('north-relay')[5]).toMatch(/^\d{2} [A-Z][a-z]{2} \d{4}$/);
  });

  it('copies the Client ID and shows the console\'s toast', async () => {
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    renderPage();
    await screen.findByText('north-relay');
    const row = rowOf('north-relay');
    expect(within(row).getByText('c2FtcGxlLWNsaWVudC1pZC1vbmU=')).toHaveClass('font-mono');
    await user.click(within(row).getByRole('button', { name: 'Copy Client ID' }));
    expect(writeText).toHaveBeenCalledWith('c2FtcGxlLWNsaWVudC1pZC1vbmU=');
    expect(toastSuccess).toHaveBeenCalledWith('Client ID Copied Successfully');
  });

  it('greys Create Assist and every Reissue with the console-only tooltip; clicks send nothing', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('north-relay');
    const create = screen.getByRole('button', { name: 'Create Assist' });
    const reissues = screen.getAllByRole('button', { name: 'Reissue' });
    expect(reissues).toHaveLength(4);
    const calls = get.mock.calls.length;
    for (const control of [create, ...reissues]) {
      expect(control).toHaveAttribute('aria-disabled', 'true');
      await user.hover(control);
      expect(screen.getByRole('tooltip')).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
      await user.click(control);
      await user.unhover(control);
    }
    expect(get.mock.calls.length).toBe(calls);
  });

  it('hides a column from the Columns chooser, Assist Name always shown', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('north-relay');
    await user.click(screen.getByRole('button', { name: /^Columns/ }));
    const boxes = screen.getAllByRole('checkbox');
    expect(boxes).toHaveLength(7);
    expect(boxes[0]).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Version' }));
    expect(screen.getAllByRole('columnheader').map(h => h.textContent)).not.toContain('Version');
  });

  it('pages 10 / 25 / 50 / 100', async () => {
    renderPage();
    await screen.findByText('north-relay');
    const sizes = within(screen.getByRole('combobox')).getAllByRole('option').map(o => o.textContent);
    expect(sizes).toEqual(['10', '25', '50', '100']);
    expect(screen.getByText('Showing 1 - 4 of 4')).toBeInTheDocument();
  });

  it('shows the empty state with no assists', async () => {
    answer = async () => ({ assists: [] });
    renderPage();
    expect(await screen.findByText('No assists found')).toBeInTheDocument();
  });

  it('shows the error with Retry', async () => {
    answer = async () => { throw new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'); };
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('explains a workspace that is not ready and asks for nothing', () => {
    renderPage(NOT_READY);
    expect(screen.queryByRole('heading', { level: 1, name: 'Assists' })).toBeNull();
    expect(get).not.toHaveBeenCalled();
  });
});
