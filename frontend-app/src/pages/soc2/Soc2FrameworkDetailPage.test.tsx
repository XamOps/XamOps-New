import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Soc2FrameworkDetailPage from './Soc2FrameworkDetailPage';
import { useSoc2ProgramData } from '../../hooks/useSoc2ProgramData';

vi.mock('../../hooks/useSoc2ProgramData');
vi.mock('../../contexts/TopbarContext', () => ({
  useTopbar: () => ({ setTitle: vi.fn(), clearActions: vi.fn() }),
}));

const framework = {
  id: 'frm_1', name: 'SOC 2', description: 'SOC 2 Type I & II',
  controlsTotal: 2, controlsCompleted: 1, compliancePercent: 80,
};

const controls = [
  {
    id: 'ctl_1', name: 'Access Rights', description: 'Manage access', category: 'CC6',
    requirementName: 'CC6.1', status: 'PASSING', policyName: 'P', policyStatus: 'published',
    taskCount: 2, compliancePercent: 100, policiesPublished: 2, policiesTotal: 2,
    tasksDone: 2, tasksTotal: 2, documentsCompleted: 0, documentsTotal: 0,
    requirements: [{ identifier: 'CC6.1', name: 'Logical access' }],
    policies: [], tasks: [],
  },
  {
    id: 'ctl_2', name: 'Data Privacy', description: 'Privacy', category: 'C1',
    requirementName: 'C1.1', status: 'IN_PROGRESS', policyName: 'P', policyStatus: 'draft',
    taskCount: 2, compliancePercent: 60, policiesPublished: 2, policiesTotal: 3,
    tasksDone: 1, tasksTotal: 2, documentsCompleted: 0, documentsTotal: 0,
    requirements: [{ identifier: 'C1.1', name: 'Confidentiality' }],
    policies: [], tasks: [],
  },
];

function renderPage() {
  vi.mocked(useSoc2ProgramData).mockReturnValue({
    frameworks: [framework], controls, policies: [], tasks: [], personnel: [], vendors: [],
    risks: [], cloudTests: [], integrations: [], overview: null,
    isLoading: false, error: null, refetch: vi.fn(),
  } as any);
  render(
    <MemoryRouter initialEntries={['/soc2-program/frameworks/frm_1']}>
      <Routes>
        <Route path="/soc2-program/frameworks/:frameworkId" element={<Soc2FrameworkDetailPage />} />
        <Route path="/soc2-program/frameworks/:frameworkId/controls/:controlId" element={<div>Control Detail Route</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('Soc2FrameworkDetailPage', () => {
  it('renders the breadcrumb, compliance header and enriched control rows', () => {
    renderPage();
    expect(screen.getByRole('heading', { name: 'SOC 2' })).toBeInTheDocument();
    expect(screen.getByText('80% COMPLIANT')).toBeInTheDocument();
    expect(screen.getByText('1 completed')).toBeInTheDocument();
    expect(screen.getByText('Access Rights')).toBeInTheDocument();
    expect(screen.getByText('CC6.1')).toBeInTheDocument();
    // policy/task counters rendered
    expect(screen.getByText('2/3')).toBeInTheDocument();
  });

  it('filters controls by the search box', () => {
    renderPage();
    expect(screen.getByText('Data Privacy')).toBeInTheDocument();
    fireEvent.change(screen.getByPlaceholderText('Search controls…'), { target: { value: 'access' } });
    expect(screen.queryByText('Data Privacy')).not.toBeInTheDocument();
    expect(screen.getByText('Access Rights')).toBeInTheDocument();
  });

  it('navigates to the control detail route on row click', () => {
    renderPage();
    fireEvent.click(screen.getByText('Access Rights'));
    expect(screen.getByText('Control Detail Route')).toBeInTheDocument();
  });

  it('shows aggregated requirements on the Requirements tab', () => {
    renderPage();
    fireEvent.click(screen.getByText('Requirements'));
    expect(screen.getByText('Logical access')).toBeInTheDocument();
    expect(screen.getByText('Confidentiality')).toBeInTheDocument();
  });
});
