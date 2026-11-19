import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

const resource = { resourceId: 'web', resourceName: 'web', resourceType: 'Auto Scaling Group', region: 'us-east-1', reason: 'idle', monthlySavings: 0 };
const removeResource = vi.fn();
const fetchMock = vi.fn();

vi.mock('../../contexts/AccountContext', () => ({
  useAccounts: () => ({ selectedAccountIds: ['111122223333'], accounts: [{ id: '111122223333', access: 'read-write' }] }),
}));
vi.mock('../../hooks/useWasteData', () => ({
  useWasteData: () => ({ wastedResources: [resource], isLoading: false, isRefreshing: false, error: null, loadWastedResources: vi.fn(), removeResource }),
}));
vi.mock('../../utils/apiClient', () => ({ default: { fetch: (...a: unknown[]) => fetchMock(...a) } }));
vi.mock('../../components/Waste/WasteHeader', () => ({ default: () => null }));
vi.mock('../../components/Waste/WasteKPICards', () => ({ default: () => null }));
vi.mock('../../components/Waste/WasteResourceGroup', () => ({
  default: (p: { onDelete: (r: unknown) => void }) => <button onClick={() => p.onDelete(resource)}>request delete</button>,
}));

import WastePage from '../WastePage';

const res = (status: number, body: unknown = {}) => ({ ok: status < 400, status, json: async () => body });

describe('WastePage delete of an AutoSpotting-managed group', () => {
  it('shows the warning on a 409 and keeps Delete disabled until the box is ticked', async () => {
    fetchMock
      .mockResolvedValueOnce(res(409, { code: 'AUTOSPOTTING_MANAGED_GROUP', state: 'ACTIVE' }))
      .mockResolvedValueOnce(res(200));
    render(<WastePage />);
    fireEvent.click(screen.getByText('request delete'));
    fireEvent.click(await screen.findByRole('button', { name: 'Yes, Delete' }));
    expect(await screen.findByText('AutoSpotting manages this group')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Revert first' })).toBeInTheDocument();
    const confirm = screen.getByRole('button', { name: 'Yes, Delete' });
    expect(confirm).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox'));
    await waitFor(() => expect(confirm).toBeEnabled());
    fireEvent.click(confirm);
    await waitFor(() => expect(removeResource).toHaveBeenCalledWith('web'));
    expect(String(fetchMock.mock.calls[1][0])).toContain('acknowledgeAutoSpotting=true');
    expect(String(fetchMock.mock.calls[0][0])).not.toContain('acknowledgeAutoSpotting');
  });
});
