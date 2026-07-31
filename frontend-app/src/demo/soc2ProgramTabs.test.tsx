import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Soc2ProgramPage from '../pages/Soc2ProgramPage';
import { useSoc2ProgramData } from '../hooks/useSoc2ProgramData';
import * as demo from './soc2MockData';

/**
 * Renders every Program tab against the demo dataset. The router test proves the
 * payloads are served; this proves the components can read them — a status string
 * in the wrong case, or a nested array a tab dereferences, only shows up here.
 */
vi.mock('../hooks/useSoc2ProgramData');
vi.mock('../contexts/TopbarContext', () => ({
  useTopbar: () => ({ setTitle: vi.fn(), setActions: vi.fn(), clearActions: vi.fn() }),
}));

const demoData = {
  overview: demo.demoSoc2Overview,
  frameworks: demo.demoSoc2Frameworks,
  controls: demo.demoSoc2Controls,
  policies: demo.demoSoc2Policies,
  tasks: demo.demoSoc2Tasks,
  personnel: demo.demoSoc2Personnel,
  vendors: demo.demoSoc2Vendors,
  risks: demo.demoSoc2Risks,
  cloudTests: demo.demoSoc2CloudTests,
  integrations: demo.demoSoc2Integrations,
  documents: demo.demoSoc2Documents,
  isLoading: false,
  error: null,
  refetch: vi.fn(),
};

const renderTab = (tab: string) => {
  vi.mocked(useSoc2ProgramData).mockReturnValue(demoData as never);
  render(
    <MemoryRouter initialEntries={[`/soc2-program/${tab}`]}>
      <Routes>
        <Route path="/soc2-program/:tab" element={<Soc2ProgramPage />} />
      </Routes>
    </MemoryRouter>
  );
};

describe('SOC 2 demo data renders in every Program tab', () => {
  it('Overview shows the readiness ring and stat cards', () => {
    renderTab('overview');
    expect(screen.getAllByText('SOC 2 Type II').length).toBeGreaterThan(0);
    expect(screen.getByText(`${demo.demoSoc2Controls.length} total`)).toBeInTheDocument();
    // Policies tile reads published/total off the same list the Policies tab renders
    const { policiesPublished, policiesTotal } = demo.demoSoc2Overview;
    expect(screen.getByText(`${policiesPublished}/${policiesTotal}`)).toBeInTheDocument();
    expect(screen.getByText('Cloud Findings')).toBeInTheDocument();
  });

  it('Frameworks lists the tracked frameworks', () => {
    renderTab('frameworks');
    expect(screen.getAllByText(/ISO 27001/).length).toBeGreaterThan(0);
  });

  it('Controls lists the control library', () => {
    renderTab('controls');
    expect(screen.getByText('Logical access provisioning')).toBeInTheDocument();
  });

  it('Policies lists policies across every status', () => {
    renderTab('policies');
    expect(screen.getAllByText('Access Control Policy').length).toBeGreaterThan(0);
  });

  it('Evidence tasks groups by control and fills every KPI bucket', () => {
    renderTab('evidence-tasks');
    // Default view groups tasks under their control
    expect(screen.getAllByText('Audit logging').length).toBeGreaterThan(0);
    expect(screen.getByText(`${demo.demoSoc2Tasks.filter((t) => t.status === 'done').length}/${demo.demoSoc2Tasks.length} complete`))
      .toBeInTheDocument();
    // Every bucket populated, so no KPI tile sits at zero
    ['DONE', 'ACTIVE', 'IN REVIEW', 'PENDING'].forEach((label) =>
      expect(screen.getByText(label)).toBeInTheDocument());
  });

  it('Personnel lists team members', () => {
    renderTab('personnel');
    expect(screen.getAllByText('Akshay Arora').length).toBeGreaterThan(0);
  });

  it('Vendors lists the vendor register', () => {
    renderTab('vendors');
    expect(screen.getAllByText('Amazon Web Services').length).toBeGreaterThan(0);
  });

  it('Risks lists the risk register', () => {
    renderTab('risks');
    expect(screen.getAllByText(/Unauthorised access to production data stores/).length).toBeGreaterThan(0);
  });

  it('Integrations shows connected and available integrations', () => {
    renderTab('integrations');
    expect(screen.getAllByText('Connected').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Coming Soon').length).toBeGreaterThan(0);
  });

  it('Cloud tests groups findings by provider', () => {
    renderTab('cloud-tests');
    // "Not connected" would mean no findings matched that provider
    expect(screen.queryByText('Not connected')).not.toBeInTheDocument();
    expect(screen.getAllByText(/checks ·/).length).toBe(3);
  });

  it('Documents groups by the three rendered categories', () => {
    renderTab('documents');
    expect(screen.getByText('Governance')).toBeInTheDocument();
    expect(screen.getByText('Security')).toBeInTheDocument();
    expect(screen.getByText('People')).toBeInTheDocument();
  });
});
