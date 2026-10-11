import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { NOT_MONITORED_TITLE, NotMonitoredPanel, WOULD_SHOW } from '../NotMonitoredPanel';

describe('NotMonitoredPanel (§8.1 M0, D10)', () => {
  it('says the cluster is not monitored, lists what XamOps would show, and shows no values', () => {
    render(<NotMonitoredPanel clusterName="staging-eks-cluster" onSetUp={() => {}} />);
    const region = screen.getByRole('region', { name: NOT_MONITORED_TITLE });
    expect(region).toHaveTextContent('XamOps has no agent data for staging-eks-cluster.');
    for (const item of WOULD_SHOW) expect(screen.getByText(item)).toBeInTheDocument();
    expect(region.textContent).not.toMatch(/\d/);
  });

  it('opens the setup guide from Set up monitoring', async () => {
    const user = userEvent.setup();
    const onSetUp = vi.fn();
    render(<NotMonitoredPanel clusterName="staging-eks-cluster" onSetUp={onSetUp} />);
    await user.click(screen.getByRole('button', { name: 'Set up monitoring' }));
    expect(onSetUp).toHaveBeenCalledTimes(1);
  });
});
