import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Soc2ProgramPage from './Soc2ProgramPage';
import { useSoc2ProgramData } from '../hooks/useSoc2ProgramData';
import * as soc2Api from '../api/soc2Api';

vi.mock('../hooks/useSoc2ProgramData');
vi.mock('../api/soc2Api', async () => {
  const actual = await vi.importActual<typeof soc2Api>('../api/soc2Api');
  return {
    ...actual,
    publishSoc2Policy: vi.fn(),
    setSoc2PolicyArchived: vi.fn(),
    updateSoc2Task: vi.fn(),
    createSoc2Task: vi.fn(),
    createSoc2Vendor: vi.fn(),
    updateSoc2Vendor: vi.fn(),
    deleteSoc2Vendor: vi.fn(),
    createSoc2Risk: vi.fn(),
    updateSoc2Risk: vi.fn(),
    deleteSoc2Risk: vi.fn(),
    deleteSoc2Control: vi.fn(),
    inviteSoc2Member: vi.fn(),
    updateSoc2Person: vi.fn(),
    deleteSoc2Person: vi.fn(),
  };
});
vi.mock('../contexts/TopbarContext', () => ({
  useTopbar: () => ({ setTitle: vi.fn(), setActions: vi.fn(), clearActions: vi.fn() }),
}));

const baseData: Record<string, any> = {
  overview: null, frameworks: [], controls: [], policies: [], tasks: [], personnel: [], vendors: [],
  risks: [], cloudTests: [], integrations: [], documents: [],
  isLoading: false, error: null, refetch: vi.fn(),
};

function renderTab(tab: string, overrides: Record<string, any> = {}) {
  const refetch = vi.fn();
  vi.mocked(useSoc2ProgramData).mockReturnValue({ ...baseData, ...overrides, refetch } as any);
  render(
    <MemoryRouter initialEntries={[`/soc2-program/${tab}`]}>
      <Routes>
        <Route path="/soc2-program/:tab" element={<Soc2ProgramPage />} />
        <Route path="/soc2-program/policies/:policyId" element={<div>Policy Detail Route</div>} />
        <Route path="/soc2-program/vendors/:vendorId" element={<div>Vendor Detail Route</div>} />
        <Route path="/soc2-program/risks/:riskId" element={<div>Risk Detail Route</div>} />
      </Routes>
    </MemoryRouter>
  );
  return { refetch };
}

describe('Soc2ProgramPage write actions', () => {
  it('clicking a policy row navigates to the policy detail page', () => {
    renderTab('policies', {
      policies: [{ id: 'pol_1', name: 'Access Control Policy', description: 'desc', status: 'draft', department: 'it', updatedAt: '2026-07-25T00:00:00Z' }],
    });

    fireEvent.click(screen.getByText('Access Control Policy'));
    expect(screen.getByText('Policy Detail Route')).toBeInTheDocument();
  });

  it('renders the frameworks table and adds a framework via the catalog modal', () => {
    renderTab('frameworks', {
      frameworks: [{ id: 'frm_1', name: 'SOC 2', description: 'SOC 2 Type I & II', controlsTotal: 35, controlsCompleted: 20, compliancePercent: 57 }],
    });

    expect(screen.getByText('SOC 2')).toBeInTheDocument();
    expect(screen.getByText('57%')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Add Framework'));
    expect(screen.getByText('Select the compliance frameworks to add to your organization.')).toBeInTheDocument();

    fireEvent.click(screen.getByText('CCPA'));
    fireEvent.click(screen.getByText('Add Selected'));

    // The newly added framework shows in the table as Not Started.
    expect(screen.getAllByText('CCPA').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Not Started').length).toBeGreaterThan(0);
  });

  it('filters the policy list by status', () => {
    renderTab('policies', {
      policies: [
        { id: 'pol_1', name: 'Access Control Policy', status: 'draft', department: 'it', updatedAt: null },
        { id: 'pol_2', name: 'Encryption Policy', status: 'published', department: 'it', updatedAt: null },
      ],
    });

    expect(screen.getByText('Encryption Policy')).toBeInTheDocument();
    fireEvent.change(screen.getByDisplayValue('All Statuses'), { target: { value: 'draft' } });
    expect(screen.queryByText('Encryption Policy')).not.toBeInTheDocument();
    expect(screen.getByText('Access Control Policy')).toBeInTheDocument();
  });

  it('groups evidence into category cards and drills into a category', () => {
    renderTab('evidence-tasks', {
      tasks: [
        { id: 'tsk_1', title: 'Enforce MFA', controlName: 'Access Rights', status: 'done', assigneeName: 'Priya Sharma' },
        { id: 'tsk_2', title: 'Review access logs', controlName: 'Access Rights', status: 'todo', assigneeName: null },
      ],
    });

    // Default categories view shows a category card with progress (1/2 done = 50%).
    expect(screen.getByText('Access Rights')).toBeInTheDocument();
    expect(screen.getAllByText('50%').length).toBeGreaterThan(0);

    // Drilling in shows the individual task cards.
    fireEvent.click(screen.getByText('Access Rights'));
    expect(screen.getByText('Enforce MFA')).toBeInTheDocument();
    expect(screen.getByText('Review access logs')).toBeInTheDocument();
  });

  it('changing the task status dropdown calls updateSoc2Task and refetches', async () => {
    vi.mocked(soc2Api.updateSoc2Task).mockResolvedValue({} as any);
    const { refetch } = renderTab('evidence-tasks', {
      tasks: [{ id: 'tsk_1', title: 'Enforce MFA', controlName: 'Access Rights', status: 'todo' }],
    });

    fireEvent.click(screen.getByText('List')); // switch from the default Categories view
    const select = screen.getByRole('combobox');
    fireEvent.change(select, { target: { value: 'done' } });

    await waitFor(() => expect(soc2Api.updateSoc2Task).toHaveBeenCalledWith('tsk_1', { status: 'done' }));
    await waitFor(() => expect(refetch).toHaveBeenCalled());
  });

  it('shows the assignee name when a task has an owner, and "Unassigned" otherwise', () => {
    renderTab('evidence-tasks', {
      tasks: [
        { id: 'tsk_1', title: 'Enforce MFA', controlName: 'Access Rights', status: 'todo', assigneeId: 'mem_1', assigneeName: 'Priya Sharma' },
        { id: 'tsk_2', title: 'Review access logs', controlName: 'Access Rights', status: 'todo', assigneeId: null, assigneeName: null },
      ],
    });

    fireEvent.click(screen.getByText('List')); // switch from the default Categories view
    expect(screen.getByText('Priya Sharma')).toBeInTheDocument();
    expect(screen.getByText('Unassigned')).toBeInTheDocument();
  });

  it('Add Vendor opens a modal that submits via createSoc2Vendor', async () => {
    vi.mocked(soc2Api.createSoc2Vendor).mockResolvedValue({} as any);
    const { refetch } = renderTab('vendors', { vendors: [] });

    fireEvent.click(screen.getAllByText('Add Vendor')[0]);
    fireEvent.change(screen.getByPlaceholderText('e.g. AWS'), { target: { value: 'CloudTech' } });
    fireEvent.change(screen.getByPlaceholderText('What does this vendor provide?'), { target: { value: 'Hosting' } });
    const submitButtons = screen.getAllByText('Add Vendor');
    fireEvent.click(submitButtons[submitButtons.length - 1]);

    await waitFor(() => expect(soc2Api.createSoc2Vendor).toHaveBeenCalled());
    await waitFor(() => expect(refetch).toHaveBeenCalled());
  });
});

describe('Soc2ProgramPage loading state', () => {
  it('renders a layout-aware skeleton (not a text spinner) while loading', () => {
    renderTab('policies', { isLoading: true });
    expect(screen.getByTestId('soc2-skeleton')).toBeInTheDocument();
    expect(screen.queryByText(/Loading SOC 2 program/)).not.toBeInTheDocument();
  });
});

describe('Soc2ProgramPage detail drawer', () => {
  it('clicking a control row opens a drawer with its requirements, policies, and tasks', () => {
    renderTab('controls', {
      controls: [{
        id: 'ctl_1', name: 'Access Rights', description: 'Restrict logical access.', category: 'CC6',
        requirementName: 'CC6.1', status: 'IN_PROGRESS', policyName: 'Access Control Policy', policyStatus: 'draft',
        taskCount: 1,
        requirements: [{ identifier: 'CC6.1', name: 'Logical access security is implemented.' }],
        policies: [{ id: 'pol_1', name: 'Access Control Policy', status: 'draft' }],
        tasks: [{ id: 'tsk_1', title: 'Enforce MFA', controlName: 'Access Rights', status: 'todo' }],
      }],
    });

    fireEvent.click(screen.getByText('Access Rights'));

    expect(screen.getByText('Restrict logical access.')).toBeInTheDocument();
    expect(screen.getByText(/Logical access security is implemented\./)).toBeInTheDocument();
    expect(screen.getAllByText('Access Control Policy').length).toBeGreaterThan(0);
    expect(screen.getByText('Enforce MFA')).toBeInTheDocument();
  });

  it('clicking a vendor row navigates to the vendor detail page', () => {
    renderTab('vendors', {
      vendors: [{ id: 'ven_1', name: 'AWS', category: 'cloud', status: 'assessed', description: 'Cloud infrastructure provider.' }],
    });

    fireEvent.click(screen.getByText('AWS'));
    expect(screen.getByText('Vendor Detail Route')).toBeInTheDocument();
  });

  it('clicking a risk row navigates to the risk detail page', () => {
    renderTab('risks', {
      risks: [{ id: 'rsk_1', title: 'Incident response plan not tested', description: 'gap', category: 'technology', status: 'open', severity: 'medium', riskScore: 5, owner: null, updatedAt: null }],
    });

    fireEvent.click(screen.getByText('Incident response plan not tested'));
    expect(screen.getByText('Risk Detail Route')).toBeInTheDocument();
  });
});

describe('Soc2ProgramPage overview tab', () => {
  const overview = {
    frameworkName: 'SOC 2', readinessPercent: 80,
    controlsTotal: 35, controlsPassing: 28, controlsInProgress: 5, controlsNotStarted: 2,
    policiesTotal: 25, policiesPublished: 18,
    tasksTotal: 76, tasksOpen: 30,
    peopleTotal: 6, vendorsTotal: 6, risksTotal: 3, cloudFindingsTotal: 0, integrationsConnected: 1,
  };

  it('renders the readiness percentage and the controls breakdown legend', () => {
    renderTab('overview', { overview });

    expect(screen.getByText('80%')).toBeInTheDocument();
    expect(screen.getByText('SOC 2')).toBeInTheDocument();
    expect(screen.getByText('28')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('links each KPI card to its corresponding sidebar tab', () => {
    renderTab('overview', { overview });

    expect(screen.getByText('Personnel').closest('a')).toHaveAttribute('href', '/soc2-program/personnel');
    expect(screen.getByText('Vendors').closest('a')).toHaveAttribute('href', '/soc2-program/vendors');
    expect(screen.getByText('Open Risks').closest('a')).toHaveAttribute('href', '/soc2-program/risks');
    expect(screen.getByText('View all controls').closest('a')).toHaveAttribute('href', '/soc2-program/frameworks');
  });
});
