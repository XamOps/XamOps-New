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
    updateSoc2TaskStatus: vi.fn(),
    createSoc2Vendor: vi.fn(),
    createSoc2Risk: vi.fn(),
  };
});
vi.mock('../contexts/TopbarContext', () => ({
  useTopbar: () => ({ setTitle: vi.fn(), setActions: vi.fn(), clearActions: vi.fn() }),
}));

const baseData: Record<string, any> = {
  overview: null, controls: [], policies: [], tasks: [], personnel: [], vendors: [],
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

  it('changing the task status dropdown calls updateSoc2TaskStatus and refetches', async () => {
    vi.mocked(soc2Api.updateSoc2TaskStatus).mockResolvedValue({} as any);
    const { refetch } = renderTab('evidence-tasks', {
      tasks: [{ id: 'tsk_1', title: 'Enforce MFA', controlName: 'Access Rights', status: 'todo' }],
    });

    const select = screen.getByRole('combobox');
    fireEvent.change(select, { target: { value: 'done' } });

    await waitFor(() => expect(soc2Api.updateSoc2TaskStatus).toHaveBeenCalledWith('tsk_1', 'done'));
    await waitFor(() => expect(refetch).toHaveBeenCalled());
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
