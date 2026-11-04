import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { get, canMock, forCluster, status } = vi.hoisted(() => ({
  get: vi.fn(), canMock: vi.fn(), forCluster: vi.fn(), status: { value: 'ready' },
}));
vi.mock('../../../utils/apiClient', () => ({ default: { get } }));
vi.mock('../../../hooks/useK8sCapabilities', () => ({
  useOptionalK8sCapabilities: () => ({ can: canMock, forCluster, status: status.value }),
}));

import { clearK8sCaches } from '../../../utils/k8sCache';
import { ClusterSwitcher } from '../ClusterSwitcher';

function open() {
  render(
    <MemoryRouter initialEntries={['/eks-details?name=prod-eks&region=us-east-1&accountIds=111']}>
      <Routes>
        <Route path="/eks-details" element={<ClusterSwitcher accountId="111" clusterName="prod-eks" region="us-east-1" />} />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('button', { name: 'Switch cluster' }));
}

describe('ClusterSwitcher pending count (AC-F4)', () => {
  beforeEach(() => {
    get.mockReset().mockResolvedValue([{ name: 'prod-eks', region: 'us-east-1', version: '1.30', connected: true }]);
    forCluster.mockReset();
    status.value = 'ready';
    clearK8sCaches();
  });

  it('shows the pending count beside the current cluster in Manual', async () => {
    forCluster.mockReturnValue({ manual: { mode: 'MANUAL', pending: 4 } });
    open();
    await waitFor(() => expect(screen.getByText('4 pending')).toBeTruthy());
    expect(screen.getByText('prod-eks').closest('a')).toHaveAttribute('aria-current', 'true');
  });

  it('shows an em dash when the count is unknown', async () => {
    forCluster.mockReturnValue({ manual: { mode: 'MANUAL', pending: null } });
    open();
    await waitFor(() => expect(screen.getByText('— pending')).toBeTruthy());
  });

  it('shows an em dash when capabilities failed to load', async () => {
    status.value = 'error';
    forCluster.mockReturnValue(null);
    open();
    await waitFor(() => expect(screen.getByText('— pending')).toBeTruthy());
  });

  it('shows nothing for a cluster that is not in Manual', async () => {
    forCluster.mockReturnValue({ manual: { mode: 'AUTONOMOUS', pending: 0 } });
    open();
    await waitFor(() => expect(screen.getByText('prod-eks')).toBeTruthy());
    expect(screen.queryByText(/pending/)).toBeNull();
  });
});
