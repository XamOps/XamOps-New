import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Soc2PolicyDetailPage from './Soc2PolicyDetailPage';
import { useSoc2ProgramData } from '../../hooks/useSoc2ProgramData';
import * as soc2Api from '../../api/soc2Api';

vi.mock('../../hooks/useSoc2ProgramData');
vi.mock('../../api/soc2Api', async () => {
  const actual = await vi.importActual<typeof soc2Api>('../../api/soc2Api');
  return { ...actual, publishSoc2Policy: vi.fn(), setSoc2PolicyArchived: vi.fn() };
});
vi.mock('../../contexts/TopbarContext', () => ({
  useTopbar: () => ({ setTitle: vi.fn(), clearActions: vi.fn() }),
}));

const draftPolicy = {
  id: 'pol_1', name: 'Encryption & Crypto Controls', description: 'Encryption everywhere', status: 'draft',
  content: 'Purpose\n\nEncrypt all data at rest.', frequency: 'yearly', department: 'it',
  requiresSignature: true, reviewDate: null, lastPublishedAt: null, updatedAt: '2026-07-25T00:00:00Z',
};

const controls = [{
  id: 'ctl_1', name: 'Encryption at Rest', description: '', category: 'CC6', requirementName: 'CC6.1',
  status: 'IN_PROGRESS', policyName: null, policyStatus: null, taskCount: 0,
  compliancePercent: 50, policiesPublished: 0, policiesTotal: 1, tasksDone: 1, tasksTotal: 1,
  documentsCompleted: 0, documentsTotal: 0,
  requirements: [{ identifier: 'CC6.1', name: 'Logical access' }],
  policies: [{ id: 'pol_1', name: 'Encryption & Crypto Controls', status: 'draft' }], tasks: [],
}];

function renderPage(refetch = vi.fn()) {
  vi.mocked(useSoc2ProgramData).mockReturnValue({
    frameworks: [], controls, policies: [draftPolicy], tasks: [], personnel: [], vendors: [],
    risks: [], cloudTests: [], integrations: [], overview: null,
    isLoading: false, error: null, refetch,
  } as any);
  render(
    <MemoryRouter initialEntries={['/soc2-program/policies/pol_1']}>
      <Routes>
        <Route path="/soc2-program/policies/:policyId" element={<Soc2PolicyDetailPage />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('Soc2PolicyDetailPage', () => {
  it('renders title, breadcrumb and governance metadata on the Overview tab', () => {
    renderPage();
    expect(screen.getByRole('heading', { name: 'Encryption & Crypto Controls' })).toBeInTheDocument();
    expect(screen.getByText('Review cadence')).toBeInTheDocument();
    expect(screen.getByText('Required')).toBeInTheDocument(); // employee sign-off
  });

  it('shows the rendered document content on the Content tab', () => {
    renderPage();
    fireEvent.click(screen.getByText('Content'));
    expect(screen.getByText(/Encrypt all data at rest\./)).toBeInTheDocument();
    expect(screen.getByText('PDF View')).toBeInTheDocument();
  });

  it('lists mapped controls on the Mappings tab', () => {
    renderPage();
    fireEvent.click(screen.getByText('Mappings'));
    expect(screen.getByText('Encryption at Rest')).toBeInTheDocument();
  });

  it('publishes a draft policy and refetches', async () => {
    vi.mocked(soc2Api.publishSoc2Policy).mockResolvedValue({} as any);
    const refetch = vi.fn();
    renderPage(refetch);

    fireEvent.click(screen.getByText('Publish'));

    await waitFor(() => expect(soc2Api.publishSoc2Policy).toHaveBeenCalledWith('pol_1'));
    await waitFor(() => expect(refetch).toHaveBeenCalled());
  });
});
