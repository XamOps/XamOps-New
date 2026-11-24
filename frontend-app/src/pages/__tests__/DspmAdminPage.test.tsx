/**
 * The XamLens (DSPM) admin page.
 *
 * - Only the roles its API admits get the page. A BillOps sub-admin opening /dspm-admin gets the app's
 *   "This page isn't available" screen, and no admin request is sent.
 * - A FORBIDDEN mapping says the key needs the PLATFORM scope: in the table, after Verify and after a
 *   save, and it counts under "Need attention".
 * - The BillOps demo login renders the empty page through the demo router instead of crashing.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { DspmMappingAdminView } from '../../types/dspm';
import { DEMO_MODE_KEY, DEMO_ROLE_KEY } from '../../demo/demoConfig';

let roles: string[] = [];
vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({
    hasAnyRole: (wanted: string | string[]) => (Array.isArray(wanted) ? wanted : [wanted]).some(r => roles.includes(r)),
  }),
}));

import apiClient from '../../utils/apiClient';
import DspmAdminRoute from '../DspmAdminPage';

const PLATFORM = 'This key needs the PLATFORM scope. Issue a Matters key with that scope and save it here.';
const EMPTY_AUDIT = { data: [], page: 0, pageSize: 50, total: 0, totalPages: 0 };

const mapping = (over: Partial<DspmMappingAdminView> = {}): DspmMappingAdminView => ({
  id: 1, clientId: 7, clientName: 'Example Org', mattersProjectId: 'project-1',
  mattersProjectName: 'Example project', mattersAccountId: null, baseUrl: 'https://matters.test',
  apiKeyEnv: 'prod', apiKeyId: 'k1', apiKeyLastFour: 'WXYZ', apiKeyExpiresAt: null,
  daysUntilExpiry: null, expiryState: 'NONE', enabled: true, entitled: true, credentialStored: true,
  connectionStatus: 'UNVERIFIED', lastVerifiedAt: null, lastError: null, lastErrorAt: null,
  updatedBy: 'operator', createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z',
  ...over,
});

const FORBIDDEN = mapping({
  connectionStatus: 'FORBIDDEN',
  lastError: 'FORBIDDEN_SCOPE: This Matters key needs the PLATFORM scope',
  lastErrorAt: '2026-11-18T10:00:00Z',
});

/** The value shown on the tile with this label. */
const tileValue = (label: string) => screen.getByText(label).parentElement!.firstElementChild!.textContent;

function renderPage() {
  return render(<MemoryRouter initialEntries={['/dspm-admin']}><DspmAdminRoute /></MemoryRouter>);
}

let current: DspmMappingAdminView[];

beforeEach(() => {
  roles = ['ROLE_BILLOPS_ADMIN'];
  current = [];
});

afterEach(() => {
  vi.restoreAllMocks();
  sessionStorage.clear();
});

describe('DspmAdminPage, who may open it', () => {
  it('shows a BillOps sub-admin the no-access screen and sends no admin request', () => {
    roles = ['ROLE_BILLOPS_ADMIN_SUB'];
    const get = vi.spyOn(apiClient, 'get');

    renderPage();

    expect(screen.getByText("This page isn't available")).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to dashboard' })).toHaveAttribute('href', '/unified-dashboard');
    expect(screen.queryByText('XamLens (DSPM)')).toBeNull();
    expect(get).not.toHaveBeenCalled();
  });

  it.each(['ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN'])('opens for %s, as the API does', async role => {
    roles = [role];
    const get = vi.spyOn(apiClient, 'get').mockImplementation(async (url: string) =>
      (url.includes('/audit') ? EMPTY_AUDIT : []) as never);

    renderPage();

    expect(await screen.findByText('No DSPM mappings yet')).toBeInTheDocument();
    expect(screen.getByText('XamLens (DSPM)')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/xamops/billops-admin/dspm/mappings');
  });
});

describe('DspmAdminPage, a key without the PLATFORM scope', () => {
  beforeEach(() => {
    vi.spyOn(apiClient, 'get').mockImplementation(async (url: string) =>
      (url.includes('/audit') ? EMPTY_AUDIT : current) as never);
  });

  it('reads Scope denied, says the key needs the PLATFORM scope and counts under Need attention', async () => {
    current = [FORBIDDEN];

    renderPage();

    expect(await screen.findByText('Scope denied')).toBeInTheDocument();
    expect(screen.getByText(PLATFORM)).toHaveAttribute('title', PLATFORM);
    expect(tileValue('Need attention')).toBe('1');
    expect(tileValue('Connected')).toBe('0');
  });

  it('reports it after Verify', async () => {
    current = [mapping({ connectionStatus: 'CONNECTED', lastVerifiedAt: '2026-11-01T00:00:00Z' })];
    const post = vi.spyOn(apiClient, 'post').mockImplementation(async () => {
      current = [FORBIDDEN];
      return FORBIDDEN as never;
    });

    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Verify' }));

    expect(await screen.findByText(`Example Org: ${PLATFORM}`)).toBeInTheDocument();
    expect(post).toHaveBeenCalledWith('/api/xamops/billops-admin/dspm/mappings/7/verify');
    expect(await screen.findByText('Scope denied')).toBeInTheDocument();
    expect(tileValue('Need attention')).toBe('1');
  });

  it('reports a Verify that a rate limit cut short as that, and the row keeps the PLATFORM reason', async () => {
    current = [FORBIDDEN];
    const limited = { ...FORBIDDEN, lastError: "RATE_LIMITED: Matters rate limit exceeded for this organisation's API key" };
    vi.spyOn(apiClient, 'post').mockImplementation(async () => {
      current = [limited];
      return limited as never;
    });

    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Verify' }));

    expect(await screen.findByText(`Example Org: ${limited.lastError}`)).toBeInTheDocument();
    expect(screen.getByText('Scope denied')).toBeInTheDocument();
    expect(screen.getByText(PLATFORM)).toBeInTheDocument();
  });

  it('reports it after saving a new key, which the save verifies', async () => {
    current = [mapping()];
    const put = vi.spyOn(apiClient, 'put').mockImplementation(async () => {
      current = [FORBIDDEN];
      return FORBIDDEN as never;
    });

    renderPage();
    fireEvent.click(await screen.findByTitle('Edit mapping'));
    fireEvent.change(screen.getByPlaceholderText('Currently ····WXYZ'), {
      target: { value: 'mts_prod_k2_SyntheticSecretValue' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save mapping' }));

    expect(await screen.findByText(`Example Org: ${PLATFORM}`)).toBeInTheDocument();
    expect(put).toHaveBeenCalledWith('/api/xamops/billops-admin/dspm/mappings/7',
      expect.objectContaining({ apiKey: 'mts_prod_k2_SyntheticSecretValue' }));
    expect(screen.queryByRole('button', { name: 'Save mapping' })).toBeNull();
    expect(tileValue('Need attention')).toBe('1');
  });
});

describe('DspmAdminPage, BillOps demo login', () => {
  it('renders the empty page and the customer picker from the demo router', async () => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_BILLOPS_ADMIN');
    const fetchSpy = vi.spyOn(window, 'fetch');

    renderPage();

    expect(await screen.findByText('No DSPM mappings yet')).toBeInTheDocument();
    expect(tileValue('Mapped customers')).toBe('0');
    fireEvent.click(screen.getByRole('button', { name: 'Add mapping' }));
    expect(await screen.findByText('Every customer already has a mapping. Edit an existing one instead.'))
      .toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
