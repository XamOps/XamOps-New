import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { installFetch, renderWithProviders } from '../../pages/autospotting/__tests__/helpers';
import AllowlistPanel from './AllowlistPanel';
afterEach(() => vi.unstubAllGlobals());
const CONTRACT_ID = '11111111-2222-3333-4444-555555555555';
const row = (over: Record<string, unknown> = {}) => ({
  cloudAccountId: 7, provider: 'AWS', accountName: 'Trata prod', clientId: 3, billingBasis: 'CONTRACT',
  contractId: CONTRACT_ID, contractInForce: true, reason: 'Pilot', addedBy: '11', addedAt: '2026-11-20T10:00:00Z', ...over,
});
const options = (over: Record<string, unknown> = {}) => ({
  cloudAccountId: 9, provider: 'GCP', accountName: 'xamops-test', clientId: 4, contractInForce: false, contractId: null, ...over,
});
const mount = (extra: Parameters<typeof installFetch>[0] = []) =>
  installFetch([
    { match: /\/autospotting-admin\/allowlist$/, responses: [{ body: [row(), row({ cloudAccountId: 8, accountName: 'Old', billingBasis: 'NO_FEE', contractId: null, contractInForce: false, reason: 'Sandbox' }), row({ cloudAccountId: 10, accountName: 'Ended co', contractInForce: false })] }] },
    ...extra,
  ]);
describe('AllowlistPanel', () => {
  it('lists each row with its basis and whether its contract is still in force', async () => {
    mount();
    renderWithProviders(<AllowlistPanel />);
    const contract = (await screen.findByText('Trata prod')).closest('tr') as HTMLElement;
    expect(within(contract).getByText('Client contract')).toBeInTheDocument();
    expect(within(contract).getByText('In force')).toBeInTheDocument();
    const noFee = screen.getByText('Old').closest('tr') as HTMLElement;
    expect(within(noFee).getByText('Never bills')).toBeInTheDocument();
    expect(within(noFee).getByText('None')).toBeInTheDocument();
    const ended = screen.getByText('Ended co').closest('tr') as HTMLElement;
    expect(within(ended).getByText('Ended')).toBeInTheDocument();
  });
  it('shows the empty state and a refusal', async () => {
    installFetch([{ match: /\/allowlist$/, responses: [{ body: [] }] }]);
    const { unmount } = renderWithProviders(<AllowlistPanel />);
    expect(await screen.findByText('No accounts on the allowlist.')).toBeInTheDocument();
    unmount();
    installFetch([{ match: /\/allowlist$/, responses: [{ status: 403, body: { code: 'AS_FORBIDDEN_ROLE' } }] }]);
    renderWithProviders(<AllowlistPanel />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/not allowed/);
  });
  it('offers only "Never bills" while the client has no contract in force, and needs a reason', async () => {
    const fx = mount([
      { match: /\/allowlist\/options\/9$/, responses: [{ body: options() }] },
      { method: 'POST', match: /\/allowlist$/, responses: [{ body: row({ cloudAccountId: 9, billingBasis: 'NO_FEE', contractId: null }) }] },
    ]);
    renderWithProviders(<AllowlistPanel />);
    await screen.findByText('Trata prod');
    await userEvent.type(screen.getByLabelText('Account id'), '9');
    await userEvent.click(screen.getByRole('button', { name: 'Find' }));
    expect(await screen.findByText(/xamops-test/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Client contract')).not.toBeInTheDocument();
    expect(screen.getByText('No contract in force for this client.')).toBeInTheDocument();
    const add = screen.getByRole('button', { name: 'Add to allowlist' });
    expect(add).toBeDisabled();
    await userEvent.click(screen.getByLabelText('Never bills'));
    expect(add).toBeDisabled(); // a reason is required
    await userEvent.type(screen.getByLabelText('Reason'), 'XamOps test project');
    expect(add).toBeEnabled();
    await userEvent.click(add);
    await waitFor(() => expect(fx.calls.some(c => c.method === 'POST')).toBe(true));
    const post = fx.calls.find(c => c.method === 'POST')!;
    expect(post.url).toBe('/api/xamops/autospotting-admin/allowlist');
    expect(post.body).toEqual({ cloudAccountId: 9, billingBasis: 'NO_FEE', reason: 'XamOps test project' });
    expect(post.headers['X-XamOps-Intent']).toBe('autospotting');
    expect(post.headers['Idempotency-Key']).toBeTruthy();
    expect(await screen.findByRole('status')).toHaveTextContent('Account 9 added: Never bills.');
  });
  it('offers "Client contract" while a contract is in force', async () => {
    const fx = mount([
      { match: /\/allowlist\/options\/9$/, responses: [{ body: options({ contractInForce: true, contractId: CONTRACT_ID }) }] },
      { method: 'POST', match: /\/allowlist$/, responses: [{ body: row({ cloudAccountId: 9 }) }] },
    ]);
    renderWithProviders(<AllowlistPanel />);
    await screen.findByText('Trata prod');
    await userEvent.type(screen.getByLabelText('Account id'), '9');
    await userEvent.click(screen.getByRole('button', { name: 'Find' }));
    await userEvent.click(await screen.findByLabelText('Client contract'));
    await userEvent.type(screen.getByLabelText('Reason'), 'Signed 2026-11');
    await userEvent.click(screen.getByRole('button', { name: 'Add to allowlist' }));
    await waitFor(() => expect(fx.calls.some(c => c.method === 'POST')).toBe(true));
    expect(fx.calls.find(c => c.method === 'POST')!.body).toEqual({ cloudAccountId: 9, billingBasis: 'CONTRACT', reason: 'Signed 2026-11' });
  });
  it('shows the server refusal when the basis is not accepted', async () => {
    mount([
      { match: /\/allowlist\/options\/9$/, responses: [{ body: options({ contractInForce: true }) }] },
      { method: 'POST', match: /\/allowlist$/, responses: [{ status: 409, body: { code: 'ALLOWLIST_NEEDS_BILLING_BASIS', detail: 'The account\'s client has no contract in force.' } }] },
    ]);
    renderWithProviders(<AllowlistPanel />);
    await screen.findByText('Trata prod');
    await userEvent.type(screen.getByLabelText('Account id'), '9');
    await userEvent.click(screen.getByRole('button', { name: 'Find' }));
    await userEvent.click(await screen.findByLabelText('Client contract'));
    await userEvent.type(screen.getByLabelText('Reason'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Add to allowlist' }));
    expect(await screen.findByText(/no contract in force/)).toBeInTheDocument();
  });
  it('rejects a bad account id and an unknown account', async () => {
    mount([{ match: /\/allowlist\/options\/99$/, responses: [{ status: 404, body: { code: 'NOT_FOUND' } }] }]);
    renderWithProviders(<AllowlistPanel />);
    await screen.findByText('Trata prod');
    await userEvent.type(screen.getByLabelText('Account id'), 'abc');
    await userEvent.click(screen.getByRole('button', { name: 'Find' }));
    expect(await screen.findByText('Enter an account id.')).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText('Account id'));
    await userEvent.type(screen.getByLabelText('Account id'), '99');
    await userEvent.click(screen.getByRole('button', { name: 'Find' }));
    expect(await screen.findByText('Account not found.')).toBeInTheDocument();
  });
  it('removes a row with a reason', async () => {
    const fx = mount([{ method: 'DELETE', match: /\/allowlist\/8$/, responses: [{ status: 204, body: null }] }]);
    renderWithProviders(<AllowlistPanel />);
    const r = (await screen.findByText('Old')).closest('tr') as HTMLElement;
    await userEvent.click(within(r).getByRole('button', { name: 'Remove' }));
    const confirm = screen.getByRole('button', { name: 'Remove 8' });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Removal reason'), 'Pilot over');
    await userEvent.click(confirm);
    await waitFor(() => expect(fx.calls.some(c => c.method === 'DELETE')).toBe(true));
    const del = fx.calls.find(c => c.method === 'DELETE')!;
    expect(del.url).toBe('/api/xamops/autospotting-admin/allowlist/8');
    expect(del.body).toEqual({ reason: 'Pilot over' });
    expect(del.headers['Idempotency-Key']).toBeTruthy();
  });
  it('uses no colour and no emoji', async () => {
    mount();
    const { container } = renderWithProviders(<AllowlistPanel />);
    await screen.findByText('Trata prod');
    expect(container.innerHTML).not.toMatch(/(red|green|blue|amber|orange|indigo|purple|yellow|emerald|rose)-\d{2,3}/);
    expect(container.textContent ?? '').not.toMatch(/\p{Extended_Pictographic}/u);
  });
});
