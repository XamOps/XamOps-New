import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Soc2RiskDetailPage from './Soc2RiskDetailPage';
import { useSoc2ProgramData } from '../../hooks/useSoc2ProgramData';

vi.mock('../../hooks/useSoc2ProgramData');
vi.mock('../../contexts/TopbarContext', () => ({ useTopbar: () => ({ setTitle: vi.fn() }) }));

const risk = {
  id: 'rsk_1', title: 'Incident response plan not tested this year', description: 'IR untested',
  category: 'technology', status: 'open', severity: 'medium', riskScore: 5,
  likelihood: 3, impact: 4, department: 'it', owner: null, updatedAt: '2026-07-27T00:00:00Z',
};

function renderPage() {
  vi.mocked(useSoc2ProgramData).mockReturnValue({
    frameworks: [], controls: [], policies: [], tasks: [], personnel: [], vendors: [],
    risks: [risk], cloudTests: [], integrations: [], documents: [], overview: null,
    isLoading: false, error: null, refetch: vi.fn(),
  } as any);
  render(
    <MemoryRouter initialEntries={['/soc2-program/risks/rsk_1']}>
      <Routes><Route path="/soc2-program/risks/:riskId" element={<Soc2RiskDetailPage />} /></Routes>
    </MemoryRouter>
  );
}

describe('Soc2RiskDetailPage', () => {
  it('renders the risk title and settings on the Overview tab', () => {
    renderPage();
    expect(screen.getByRole('heading', { name: /Incident response plan not tested/ })).toBeInTheDocument();
    expect(screen.getByText('Risk Settings')).toBeInTheDocument();
    expect(screen.getByText('Assignee')).toBeInTheDocument();
  });

  it('renders the 5x5 matrix on the Risk Matrix tab', () => {
    renderPage();
    fireEvent.click(screen.getByText('Risk Matrix'));
    expect(screen.getAllByText('Medium').length).toBeGreaterThan(0); // legend band
  });
});
