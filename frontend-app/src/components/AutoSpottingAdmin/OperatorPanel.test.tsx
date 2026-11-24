import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { installFetch, renderWithProviders } from '../../pages/autospotting/__tests__/helpers';
import OperatorPanel from './OperatorPanel';

afterEach(() => vi.unstubAllGlobals());

const sw = (over: Record<string, unknown> = {}) => ({
  scopeKey: 'AWS_CONVERSIONS', propertyValue: false, engaged: false, effectiveValue: false, reason: null,
  changedBy: null, changedAt: null, version: 3, ...over,
});
const stuck = {
  operations: [{
    operationId: 'op-1', groupId: 'g-9', kind: 'ENABLE', status: 'WAITING', phase: 'FORWARD', step: 2,
    waitReason: 'CLOUD_QUOTA', progress: null, errorCode: 'SPOT_QUOTA', errorMessage: 'Spot vCPU quota reached.',
    dryRun: false, deadlineAt: '2026-11-20T12:30:00Z', finishedAt: null,
  }],
  page: 0, size: 50, totalElements: 1, totalPages: 1,
};

const mount = (extra: Parameters<typeof installFetch>[0] = []) =>
  installFetch([
    { match: /\/autospotting-admin\/switches$/, responses: [{ body: [sw(), sw({ scopeKey: 'GLOBAL', engaged: true, version: 5, reason: 'incident' })] }] },
    { match: /\/autospotting-admin\/operations/, responses: [{ body: stuck }] },
    ...extra,
  ]);

describe('OperatorPanel', () => {
  it('engaging a switch sends the reason, expectedVersion, the intent header and an idempotency key', async () => {
    const fx = mount([
      { method: 'PUT', match: /\/switches\/AWS_CONVERSIONS$/, responses: [{ body: sw({ engaged: true, version: 4 }) }] },
    ]);
    renderWithProviders(<OperatorPanel />);
    const row = (await screen.findByText('AWS_CONVERSIONS')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: 'Engage' }));
    await userEvent.type(screen.getByLabelText('Reason'), 'Quota incident');
    await userEvent.click(screen.getByRole('button', { name: 'Engage AWS_CONVERSIONS' }));
    await waitFor(() => expect(fx.calls.some(c => c.method === 'PUT')).toBe(true));
    const put = fx.calls.find(c => c.method === 'PUT')!;
    expect(put.url).toBe('/api/xamops/autospotting-admin/switches/AWS_CONVERSIONS');
    expect(put.body).toEqual({ engaged: true, expectedVersion: 3, reason: 'Quota incident' });
    expect(put.headers['X-XamOps-Intent']).toBe('autospotting');
    expect(put.headers['Idempotency-Key']).toBeTruthy();
    expect(put.headers['Content-Type']).toBe('application/json');
  });

  it('releases an engaged switch, and needs a reason first', async () => {
    const fx = mount([{ method: 'PUT', match: /\/switches\/GLOBAL$/, responses: [{ body: sw({ scopeKey: 'GLOBAL', version: 6 }) }] }]);
    renderWithProviders(<OperatorPanel />);
    const row = (await screen.findByText('GLOBAL')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: 'Release' }));
    expect(screen.getByRole('button', { name: 'Release GLOBAL' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), 'Resolved');
    await userEvent.click(screen.getByRole('button', { name: 'Release GLOBAL' }));
    await waitFor(() => expect(fx.calls.some(c => c.method === 'PUT')).toBe(true));
    expect(fx.calls.find(c => c.method === 'PUT')!.body).toEqual({ engaged: false, expectedVersion: 5, reason: 'Resolved' });
  });

  it('tells the operator when someone else changed the switch first', async () => {
    mount([{ method: 'PUT', match: /\/switches\/AWS_CONVERSIONS$/, responses: [{ status: 409, body: { code: 'SWITCH_VERSION_MISMATCH' } }] }]);
    renderWithProviders(<OperatorPanel />);
    const row = (await screen.findByText('AWS_CONVERSIONS')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: 'Engage' }));
    await userEvent.type(screen.getByLabelText('Reason'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Engage AWS_CONVERSIONS' }));
    expect(await screen.findByText(/Someone else changed it/)).toBeInTheDocument();
  });

  it('renders each stuck operation with its deadline and last error', async () => {
    mount();
    renderWithProviders(<OperatorPanel />);
    const row = (await screen.findByText('op-1')).closest('tr') as HTMLElement;
    expect(within(row).getByText('2026-11-20 12:30:00 UTC')).toBeInTheDocument();
    expect(within(row).getByText('SPOT_QUOTA')).toBeInTheDocument();
    expect(within(row).getByText('Spot vCPU quota reached.')).toBeInTheDocument();
    expect(within(row).getByText('CLOUD_QUOTA')).toBeInTheDocument();
  });

  it('pauses a group with a reason', async () => {
    const fx = mount([{ method: 'POST', match: /\/groups\/g-9\/pause$/, responses: [{ body: { groupId: 'g-9', state: 'PAUSED' } }] }]);
    renderWithProviders(<OperatorPanel />);
    const row = (await screen.findByText('op-1')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: 'Pause' }));
    await userEvent.type(screen.getByLabelText('Reason'), 'Customer asked');
    await userEvent.click(screen.getByRole('button', { name: 'Pause group' }));
    await waitFor(() => expect(fx.calls.some(c => c.method === 'POST')).toBe(true));
    const post = fx.calls.find(c => c.method === 'POST')!;
    expect(post.body).toEqual({ reason: 'Customer asked' });
    expect(post.headers['Idempotency-Key']).toBeTruthy();
    expect(post.headers['X-XamOps-Intent']).toBe('autospotting');
  });

  it('shows the empty states', async () => {
    installFetch([
      { match: /\/switches$/, responses: [{ body: [] }] },
      { match: /\/operations/, responses: [{ body: { operations: [], page: 0, size: 50, totalElements: 0, totalPages: 0 } }] },
    ]);
    renderWithProviders(<OperatorPanel />);
    expect(await screen.findByText('No switches.')).toBeInTheDocument();
    expect(await screen.findByText('No stuck operations.')).toBeInTheDocument();
  });

  it('shows a refusal instead of an empty list when the reads fail', async () => {
    installFetch([
      { match: /\/switches$/, responses: [{ status: 403, body: { code: 'AS_FORBIDDEN_ROLE' } }] },
      { match: /\/operations/, responses: [{ status: 403, body: { code: 'AS_FORBIDDEN_ROLE' } }] },
    ]);
    renderWithProviders(<OperatorPanel />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/not allowed/);
  });
});
