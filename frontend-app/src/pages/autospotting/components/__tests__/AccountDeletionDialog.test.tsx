import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import AccountDeletionDialog from '../AccountDeletionDialog';
import { installFetch } from '../../__tests__/helpers';

afterEach(() => vi.unstubAllGlobals());

const check = (blocking: string[], converted: string[]) => ({
  blocking: blocking.map((k, i) => ({ groupId: `b${i}`, groupKey: k, state: 'ACTIVE' })),
  stillConverted: converted.map((k, i) => ({ groupId: `c${i}`, groupKey: k, state: 'DEGRADED' })),
  revertAllPath: '/autospotting/accounts/7/revert-all',
  deletable: blocking.length === 0,
});

describe('AccountDeletionDialog', () => {
  it('lists blocking groups and keeps Delete disabled', async () => {
    installFetch([{ match: /deletion-check/, responses: [{ body: check(['us-east-1/web'], []) }] }]);
    render(<AccountDeletionDialog accountId={7} accountName="Prod" onClose={() => {}} onDelete={() => {}} />);
    expect(await screen.findByText('us-east-1/web')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete account' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Revert all' })).toBeEnabled();
  });
  it('lists degraded, paused and failed groups as still converted and allows deletion', async () => {
    installFetch([{ match: /deletion-check/, responses: [{ body: check([], ['us-east-1/api']) }] }]);
    const onDelete = vi.fn();
    render(<AccountDeletionDialog accountId={7} accountName="Prod" onClose={() => {}} onDelete={onDelete} />);
    expect(await screen.findByText('Still converted')).toBeInTheDocument();
    expect(screen.getByText('us-east-1/api')).toBeInTheDocument();
    const del = screen.getByRole('button', { name: 'Delete account' });
    await waitFor(() => expect(del).toBeEnabled());
    fireEvent.click(del);
    expect(onDelete).toHaveBeenCalledOnce();
  });
});
