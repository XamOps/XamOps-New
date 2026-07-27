import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Soc2VendorDetailPage from './Soc2VendorDetailPage';
import { useSoc2ProgramData } from '../../hooks/useSoc2ProgramData';

vi.mock('../../hooks/useSoc2ProgramData');
vi.mock('../../contexts/TopbarContext', () => ({ useTopbar: () => ({ setTitle: vi.fn() }) }));

const vendor = {
  id: 'ven_1', name: 'AWS', category: 'cloud', status: 'assessed', description: 'Cloud host',
  website: null, subProcessor: true, inherentSeverity: 'high', residualSeverity: 'medium',
  inherentScore: 6, residualScore: 3, inherentLikelihood: 3, inherentImpact: 5,
  residualLikelihood: 2, residualImpact: 3,
};

function renderPage() {
  vi.mocked(useSoc2ProgramData).mockReturnValue({
    frameworks: [], controls: [], policies: [], tasks: [], personnel: [], vendors: [vendor],
    risks: [], cloudTests: [], integrations: [], documents: [], overview: null,
    isLoading: false, error: null, refetch: vi.fn(),
  } as any);
  render(
    <MemoryRouter initialEntries={['/soc2-program/vendors/ven_1']}>
      <Routes><Route path="/soc2-program/vendors/:vendorId" element={<Soc2VendorDetailPage />} /></Routes>
    </MemoryRouter>
  );
}

describe('Soc2VendorDetailPage', () => {
  it('renders the vendor title and breadcrumb', () => {
    renderPage();
    expect(screen.getByRole('heading', { name: 'AWS' })).toBeInTheDocument();
    expect(screen.getByText('Vendors')).toBeInTheDocument();
  });

  it('shows the risk reduction on the Treatment Plan tab', () => {
    renderPage();
    fireEvent.click(screen.getByText('Treatment Plan'));
    expect(screen.getByText('Risk reduction — treatment impact')).toBeInTheDocument();
  });

  it('renders inherent and residual matrices on the Risk Matrix tab', () => {
    renderPage();
    fireEvent.click(screen.getByText('Risk Matrix'));
    expect(screen.getByText('Inherent Risk')).toBeInTheDocument();
    expect(screen.getByText('Residual Risk')).toBeInTheDocument();
    expect(screen.getAllByText('Very High').length).toBeGreaterThan(0); // matrix legend
  });
});
