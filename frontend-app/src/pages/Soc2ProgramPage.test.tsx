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
  risks: [], cloudTests: [], integrations: [],
  isLoading: false, error: null, refetch: vi.fn(),
};

function renderTab(tab: string, overrides: Record<string, any> = {}) {
  const refetch = vi.fn();
  vi.mocked(useSoc2ProgramData).mockReturnValue({ ...baseData, ...overrides, refetch } as any);
  render(
    <MemoryRouter initialEntries={[`/soc2-program/${tab}`]}>
      <Routes>
        <Route path="/soc2-program/:tab" element={<Soc2ProgramPage />} />
      </Routes>
    </MemoryRouter>
  );
  return { refetch };
}

describe('Soc2ProgramPage write actions', () => {
  it('Publish button calls publishSoc2Policy and refetches for a draft policy', async () => {
    vi.mocked(soc2Api.publishSoc2Policy).mockResolvedValue({} as any);
    const { refetch } = renderTab('policies', {
      policies: [{ id: 'pol_1', name: 'Access Control Policy', description: 'desc', status: 'draft' }],
    });

    fireEvent.click(screen.getByText('Publish'));

    await waitFor(() => expect(soc2Api.publishSoc2Policy).toHaveBeenCalledWith('pol_1'));
    await waitFor(() => expect(refetch).toHaveBeenCalled());
  });

  it('does not show a Publish button for an already-published policy', () => {
    renderTab('policies', {
      policies: [{ id: 'pol_2', name: 'Encryption Policy', description: 'desc', status: 'published' }],
    });
    expect(screen.queryByText('Publish')).not.toBeInTheDocument();
  });

  it('changing the task status dropdown calls updateSoc2Task and refetches', async () => {
    vi.mocked(soc2Api.updateSoc2Task).mockResolvedValue({} as any);
    const { refetch } = renderTab('evidence-tasks', {
      tasks: [{ id: 'tsk_1', title: 'Enforce MFA', controlName: 'Access Rights', status: 'todo' }],
    });

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

  it('clicking a policy row opens a drawer showing the extracted document content', () => {
    renderTab('policies', {
      policies: [{ id: 'pol_2', name: 'Encryption Policy', description: 'desc', status: 'published', content: 'Purpose\n\nEncrypt all data at rest.' }],
    });

    fireEvent.click(screen.getByText('Encryption Policy'));

    expect(screen.getByText(/Encrypt all data at rest\./)).toBeInTheDocument();
  });

  it('clicking a vendor row opens a drawer with its description', () => {
    renderTab('vendors', {
      vendors: [{ id: 'ven_1', name: 'AWS', category: 'cloud', status: 'assessed', description: 'Cloud infrastructure provider.' }],
    });

    // Before opening: only the table row shows the description.
    expect(screen.getAllByText('Cloud infrastructure provider.')).toHaveLength(1);

    fireEvent.click(screen.getByText('AWS'));

    // After opening: table row + drawer both show it.
    expect(screen.getAllByText('Cloud infrastructure provider.')).toHaveLength(2);
  });

  it('closing the drawer removes it from the document', () => {
    renderTab('vendors', {
      vendors: [{ id: 'ven_1', name: 'AWS', category: 'cloud', status: 'assessed', description: 'Cloud infrastructure provider.' }],
    });

    fireEvent.click(screen.getByText('AWS'));
    expect(screen.getAllByText('Cloud infrastructure provider.')).toHaveLength(2);

    const closeButtons = screen.getAllByRole('button', { name: '' });
    fireEvent.click(closeButtons[closeButtons.length - 1]); // the drawer's close (X) icon button
    expect(screen.getAllByText('Cloud infrastructure provider.')).toHaveLength(1);
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
