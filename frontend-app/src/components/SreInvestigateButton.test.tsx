import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SreInvestigateButton from './SreInvestigateButton';
import { MemoryRouter } from 'react-router-dom';
import * as sreHook from '../hooks/useSreInvestigation';

// Mock the SRE Hook module
vi.mock('../hooks/useSreInvestigation', () => ({
  listInvestigations: vi.fn(),
  triggerInvestigation: vi.fn(),
}));

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  };
});

describe('SreInvestigateButton', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockNavigate.mockReset();
    (sreHook.listInvestigations as any).mockResolvedValue([]);
    (sreHook.triggerInvestigation as any).mockResolvedValue({});
  });

  it('renders default button when no existing investigation is found', async () => {
    render(
      <MemoryRouter>
        <SreInvestigateButton resourceType="EC2" resourceId="i-12345" />
      </MemoryRouter>
    );

    await waitFor(() => {
      const button = screen.getByRole('button', { name: 'Investigate with AI' });
      expect(button).toBeTruthy();
      expect(button.getAttribute('title')).toBe('Investigate with AI');
      expect(button.className).toContain('focus-visible:ring-indigo-500');
    });
  });

  it('renders in-progress button when a running investigation is found', async () => {
    (sreHook.listInvestigations as any).mockResolvedValue([
      {
        id: 42,
        resourceId: 'i-12345',
        resourceType: 'EC2',
        status: 'RUNNING',
        createdAt: '2026-07-17T12:00:00Z',
      },
    ]);

    render(
      <MemoryRouter>
        <SreInvestigateButton resourceType="EC2" resourceId="i-12345" />
      </MemoryRouter>
    );

    await waitFor(() => {
      const button = screen.getByRole('button', { name: 'Investigation in progress' });
      expect(button).toBeTruthy();
      expect(button.getAttribute('title')).toBe('Investigation in progress…');
      expect(button.className).toContain('focus-visible:ring-yellow-500');
    });
  });

  it('renders completed button when a completed investigation is found', async () => {
    (sreHook.listInvestigations as any).mockResolvedValue([
      {
        id: 43,
        resourceId: 'i-12345',
        resourceType: 'EC2',
        status: 'COMPLETED',
        createdAt: '2026-07-17T12:00:00Z',
      },
    ]);

    render(
      <MemoryRouter>
        <SreInvestigateButton resourceType="EC2" resourceId="i-12345" />
      </MemoryRouter>
    );

    await waitFor(() => {
      const button = screen.getByRole('button', { name: 'View Investigation Report' });
      expect(button).toBeTruthy();
      expect(button.getAttribute('title')).toBe('View Investigation Report');
      expect(button.className).toContain('focus-visible:ring-green-500');
    });
  });

  it('changes aria-label and title when triggering is in progress', async () => {
    let resolveTrigger: (value: any) => void = () => {};
    const triggerPromise = new Promise((resolve) => {
      resolveTrigger = resolve;
    });
    (sreHook.triggerInvestigation as any).mockReturnValue(triggerPromise);

    render(
      <MemoryRouter>
        <SreInvestigateButton resourceType="EC2" resourceId="i-12345" />
      </MemoryRouter>
    );

    const button = await screen.findByRole('button', { name: 'Investigate with AI' });
    fireEvent.click(button);

    // Verify triggering state
    await waitFor(() => {
      const triggeringBtn = screen.getByRole('button', { name: 'Investigating with AI...' });
      expect(triggeringBtn).toBeTruthy();
      expect(triggeringBtn.getAttribute('title')).toBe('Investigating with AI...');
      expect(triggeringBtn.getAttribute('disabled')).toBe('');
    });

    // Resolve the trigger
    resolveTrigger({
      id: 44,
      resourceId: 'i-12345',
      resourceType: 'EC2',
      status: 'PENDING',
    });

    await waitFor(() => {
      const pendingBtn = screen.getByRole('button', { name: 'Investigation in progress' });
      expect(pendingBtn).toBeTruthy();
    });
  });
});
