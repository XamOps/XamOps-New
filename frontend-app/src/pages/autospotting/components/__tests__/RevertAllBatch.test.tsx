import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import RevertAllBatch from '../RevertAllBatch';
import { installFetch } from '../../__tests__/helpers';

afterEach(() => vi.unstubAllGlobals());

const item = (key: string, extra: Record<string, unknown> = {}) => ({
  groupId: key,
  groupKey: key,
  groupState: 'ACTIVE',
  plan: { planId: `p-${key}`, warningIds: '[]' },
  errorCode: null,
  errorDetail: null,
  operationId: null,
  operationStatus: null,
  ...extra,
});
const batch = (items: unknown[], reverted = 0) => ({
  batchId: 'b1',
  accountId: 7,
  items,
  total: items.length,
  reverted,
  failed: 0,
  alreadyReverting: [],
});

describe('RevertAllBatch', () => {
  it('creates the batch, confirms it once and shows n of m reverted', async () => {
    const f = installFetch([
      { method: 'POST', match: /revert-all$/, responses: [{ status: 201, body: batch([item('a'), item('b')]) }] },
      {
        method: 'POST',
        match: /revert-all\/b1\/confirm/,
        responses: [{ status: 202, body: batch([item('a', { operationId: 'o1', operationStatus: 'QUEUED' }), item('b', { operationId: 'o2', operationStatus: 'QUEUED' })]) }],
      },
    ]);
    render(<RevertAllBatch accountId={7} onClose={() => {}} pollMs={60000} retryDelayMs={0} />);
    expect(await screen.findByRole('status')).toHaveTextContent('0 of 2 reverted');
    fireEvent.click(screen.getByRole('button', { name: 'Revert 2 groups' }));
    expect(await screen.findAllByText('Revert queued')).toHaveLength(2);
    expect(f.calls.filter(c => c.url.includes('/confirm'))).toHaveLength(1);
  });
  it('shows a group that could not be started without stopping the rest', async () => {
    installFetch([
      {
        method: 'POST',
        match: /revert-all$/,
        responses: [{ status: 201, body: batch([item('a'), item('b', { plan: null, errorCode: 'AS_GROUP_BUSY', errorDetail: 'busy' })]) }],
      },
    ]);
    render(<RevertAllBatch accountId={7} onClose={() => {}} pollMs={60000} retryDelayMs={0} />);
    expect(await screen.findByText('Not started: busy')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Revert 1 group' })).toBeEnabled();
  });
  it('requires acknowledging plan warnings before reverting', async () => {
    installFetch([
      {
        method: 'POST',
        match: /revert-all$/,
        responses: [{ status: 201, body: batch([item('a', { plan: { planId: 'p', warningIds: '["REVERT_FROM_FAILED"]' } })]) }],
      },
    ]);
    render(<RevertAllBatch accountId={7} onClose={() => {}} pollMs={60000} retryDelayMs={0} />);
    const btn = await screen.findByRole('button', { name: 'Revert 1 group' });
    expect(btn).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox'));
    expect(btn).toBeEnabled();
  });
});
