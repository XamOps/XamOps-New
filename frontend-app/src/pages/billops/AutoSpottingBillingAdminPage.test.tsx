import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { installFetch, renderWithProviders } from '../autospotting/__tests__/helpers';
import AutoSpottingBillingAdminPage from './AutoSpottingBillingAdminPage';
afterEach(() => vi.unstubAllGlobals());
const contract = (over: Record<string, unknown> = {}) => ({
  id: 'c-1', clientId: 3, sharePct: '12.500000', monthlyCap: { amount: '1000.00', currency: 'USD' },
  declaredDiscountPct: '0', discountDeclaration: 'DECLARED', effectiveFrom: '2020-01-01T00:00:00Z', effectiveTo: null,
  createdBy: '5', createdAt: '2019-12-01T00:00:00Z', inForce: true, ...over,
});
const CONTRACTS = /\/billops-admin\/autospotting\/clients\/3\/contracts$/;
async function load(fx: ReturnType<typeof installFetch>) {
  void fx;
  await userEvent.type(screen.getByLabelText('Client id'), '3');
  await userEvent.click(screen.getByRole('button', { name: 'Load' }));
}
describe('AutoSpottingBillingAdminPage: Contracts', () => {
  it('lists the contracts of a client with discount, cap and state', async () => {
    const fx = installFetch([{ match: CONTRACTS, responses: [{ body: [
      contract(),
      contract({ id: 'c-2', sharePct: '10.000000', monthlyCap: null, declaredDiscountPct: null, discountDeclaration: 'NOT_DECLARED', effectiveFrom: '2019-01-01T00:00:00Z', effectiveTo: '2019-12-31T00:00:00Z', inForce: false }),
    ] }] }]);
    renderWithProviders(<AutoSpottingBillingAdminPage />);
    await load(fx);
    const first = (await screen.findByText('12.500000')).closest('tr') as HTMLElement;
    expect(within(first).getByText('1000.00')).toBeInTheDocument();
    expect(within(first).getByText('Declared 0%')).toBeInTheDocument();
    expect(within(first).getByText('In force')).toBeInTheDocument();
    const second = screen.getByText('10.000000').closest('tr') as HTMLElement;
    expect(within(second).getByText('Not declared')).toBeInTheDocument();
    expect(within(second).getByText('None')).toBeInTheDocument();
    expect(within(second).getByText('Ended')).toBeInTheDocument();
    expect(within(second).queryByRole('button', { name: 'End contract' })).not.toBeInTheDocument();
  });
  it('the discount is a required choice between two radios, with no preselection', async () => {
    const fx = installFetch([{ match: CONTRACTS, responses: [{ body: [] }] }]);
    renderWithProviders(<AutoSpottingBillingAdminPage />);
    await load(fx);
    await screen.findByText('No contracts for this client.');
    const declared = screen.getByRole('radio', { name: /Discount declared/ });
    const notDeclared = screen.getByRole('radio', { name: 'Not declared' });
    expect(declared).not.toBeChecked();
    expect(notDeclared).not.toBeChecked();
    expect(screen.getAllByRole('radio')).toHaveLength(2);
    await userEvent.type(screen.getByLabelText('Share %'), '20');
    await userEvent.type(screen.getByLabelText('Reason'), 'Signed');
    const create = screen.getByRole('button', { name: 'Create contract' });
    expect(create).toBeDisabled(); // no discount choice yet
    await userEvent.click(declared);
    expect(create).toBeDisabled(); // declared needs a percentage
    await userEvent.type(screen.getByLabelText('Declared discount %'), '0');
    expect(create).toBeEnabled(); // 0 means declared none
    await userEvent.click(notDeclared);
    expect(screen.getByLabelText('Declared discount %')).toBeDisabled();
    expect(create).toBeEnabled();
  });
  it('sends "Not declared" as null, never 0, and decimals with their digits', async () => {
    const calls: string[] = [];
    const fx = installFetch([
      { match: CONTRACTS, responses: [{ body: [] }] },
      { method: 'POST', match: CONTRACTS, responses: [{ status: 201, body: contract() }] },
    ]);
    const original = globalThis.fetch;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') calls.push(String(init.body));
      return original(input, init);
    }));
    renderWithProviders(<AutoSpottingBillingAdminPage />);
    await load(fx);
    await screen.findByText('No contracts for this client.');
    await userEvent.type(screen.getByLabelText('Share %'), '12.123456');
    await userEvent.type(screen.getByLabelText('Monthly cap (USD)'), '999999999.99');
    await userEvent.click(screen.getByRole('radio', { name: 'Not declared' }));
    await userEvent.type(screen.getByLabelText('Reason'), 'Signed');
    await userEvent.click(screen.getByRole('button', { name: 'Create contract' }));
    await waitFor(() => expect(calls).toHaveLength(1));
    const raw = calls[0];
    expect(raw).toContain('"sharePct":12.123456');
    expect(raw).toContain('"monthlyCap":999999999.99');
    expect(raw).toContain('"discountDeclaration":"NOT_DECLARED"');
    expect(raw).toContain('"declaredDiscountPct":null');
    expect(raw).toContain('"capCurrency":"USD"');
    expect(JSON.parse(raw).reason).toBe('Signed');
    expect(await screen.findByRole('status')).toHaveTextContent('Contract created.');
  });
  it('blocks an invalid share, cap, or number before anything is sent', async () => {
    const fx = installFetch([{ match: CONTRACTS, responses: [{ body: [] }] }]);
    renderWithProviders(<AutoSpottingBillingAdminPage />);
    await load(fx);
    await screen.findByText('No contracts for this client.');
    await userEvent.click(screen.getByRole('radio', { name: 'Not declared' }));
    await userEvent.type(screen.getByLabelText('Reason'), 'r');
    const create = screen.getByRole('button', { name: 'Create contract' });
    for (const bad of ['0', '100.5', '-1', '1e3', '1,5', '07', '']) {
      await userEvent.clear(screen.getByLabelText('Share %'));
      if (bad) await userEvent.type(screen.getByLabelText('Share %'), bad);
      expect(create).toBeDisabled();
    }
    await userEvent.clear(screen.getByLabelText('Share %'));
    await userEvent.type(screen.getByLabelText('Share %'), '100');
    expect(create).toBeEnabled();
    for (const bad of ['0', '10.001', 'abc']) {
      await userEvent.clear(screen.getByLabelText('Monthly cap (USD)'));
      await userEvent.type(screen.getByLabelText('Monthly cap (USD)'), bad);
      expect(create).toBeDisabled();
    }
    expect(fx.calls.filter(c => c.method === 'POST')).toHaveLength(0);
  });
  it('shows an overlap as a named message', async () => {
    const fx = installFetch([
      { match: CONTRACTS, responses: [{ body: [] }] },
      { method: 'POST', match: CONTRACTS, responses: [{ status: 409, body: { code: 'CONTRACT_OVERLAP' } }] },
    ]);
    renderWithProviders(<AutoSpottingBillingAdminPage />);
    await load(fx);
    await screen.findByText('No contracts for this client.');
    await userEvent.type(screen.getByLabelText('Share %'), '20');
    await userEvent.click(screen.getByRole('radio', { name: 'Not declared' }));
    await userEvent.type(screen.getByLabelText('Reason'), 'r');
    await userEvent.click(screen.getByRole('button', { name: 'Create contract' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('overlaps another contract');
  });
  it('ends a contract with a reason', async () => {
    const fx = installFetch([
      { match: CONTRACTS, responses: [{ body: [contract()] }] },
      { method: 'POST', match: /\/contracts\/c-1\/end$/, responses: [{ body: contract({ effectiveTo: '2026-12-01T00:00:00Z' }) }] },
    ]);
    renderWithProviders(<AutoSpottingBillingAdminPage />);
    await load(fx);
    await userEvent.click(await screen.findByRole('button', { name: 'End contract' }));
    const confirm = screen.getByRole('button', { name: 'Confirm end' });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByLabelText('End reason'), 'Customer left');
    await userEvent.click(confirm);
    await waitFor(() => expect(fx.calls.some(c => c.method === 'POST')).toBe(true));
    const post = fx.calls.find(c => c.method === 'POST')!;
    expect(post.body).toEqual({ effectiveTo: null, reason: 'Customer left' });
    expect(post.headers['Idempotency-Key']).toBeTruthy();
    expect(post.headers['X-XamOps-Intent']).toBe('autospotting');
  });
});
describe('AutoSpottingBillingAdminPage: Go-live', () => {
  const GOLIVE = /\/billops-admin\/autospotting\/golive$/;
  it('shows the set and unset clouds, and confirms with the consequence named', async () => {
    const fx = installFetch([
      { match: GOLIVE, responses: [{ body: [{ provider: 'AWS', goLiveAt: '2026-11-20T00:00:00Z', setBy: '5', setAt: '2026-11-19T00:00:00Z' }] }] },
      { method: 'POST', match: /\/golive\/gcp$/, responses: [{ status: 201, body: { provider: 'GCP', goLiveAt: '2026-11-21T00:00:00Z', setBy: '5', setAt: '2026-11-21T00:00:00Z' } }] },
    ]);
    renderWithProviders(<AutoSpottingBillingAdminPage />);
    await userEvent.click(screen.getByRole('tab', { name: 'Go-live' }));
    const aws = (await screen.findByText('AWS')).closest('tr') as HTMLElement;
    expect(within(aws).getByText('2026-11-20 00:00:00 UTC')).toBeInTheDocument();
    expect(within(aws).queryByRole('button')).not.toBeInTheDocument(); // set once
    const gcp = screen.getByText('GCP').closest('tr') as HTMLElement;
    expect(within(gcp).getByText('Not set')).toBeInTheDocument();
    await userEvent.click(within(gcp).getByRole('button', { name: 'Set GCP go-live' }));
    expect(screen.getByText(/set once and cannot be changed/)).toBeInTheDocument();
    const confirm = screen.getByRole('button', { name: 'Confirm GCP go-live' });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), 'First lift');
    await userEvent.click(confirm);
    await waitFor(() => expect(fx.calls.some(c => c.method === 'POST')).toBe(true));
    const post = fx.calls.find(c => c.method === 'POST')!;
    expect(post.url).toBe('/api/xamops/billops-admin/autospotting/golive/gcp');
    expect(post.body).toEqual({ goLiveAt: null, reason: 'First lift' });
  });
  it('names a second go-live as already set', async () => {
    installFetch([
      { match: GOLIVE, responses: [{ body: [] }] },
      { method: 'POST', match: /\/golive\/aws$/, responses: [{ status: 409, body: { code: 'GOLIVE_ALREADY_SET' } }] },
    ]);
    renderWithProviders(<AutoSpottingBillingAdminPage />);
    await userEvent.click(screen.getByRole('tab', { name: 'Go-live' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Set AWS go-live' }));
    await userEvent.type(screen.getByLabelText('Reason'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm AWS go-live' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Go-live is already set');
  });
});
describe('AutoSpottingBillingAdminPage: design rules', () => {
  it('uses only the app palette (indigo accent, emerald/amber/red status) and no emoji', async () => {
    installFetch([{ match: /golive$/, responses: [{ body: [] }] }]);
    const { container } = renderWithProviders(<AutoSpottingBillingAdminPage />);
    expect(container.innerHTML).not.toMatch(/(?:^|[^a-z])(?:green|blue|orange|purple|yellow|teal|cyan|sky|violet|pink|lime)-\d{2,3}/);
    expect(container.textContent ?? '').not.toMatch(/\p{Extended_Pictographic}/u);
  });
});
