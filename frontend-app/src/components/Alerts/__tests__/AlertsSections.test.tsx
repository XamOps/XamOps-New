import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import AlertsLimitsSection from '../AlertsLimitsSection';
import AlertsSecuritySection from '../AlertsSecuritySection';
import AlertsCostSection from '../AlertsCostSection';
import { groupQuotaAlerts } from '../alertGroups';
import type { AlertLink, QuotaAlert, SecurityAlert } from '../../../types/alerts';

const consoleLink = (region: string) => `https://console.aws.amazon.com/servicequotas/home?region=${region}`;
const link: AlertLink = { href: '/security', label: 'Open Security Center', external: false };

const quota = (region: string, usage: number): QuotaAlert => ({
  id: `vpc-${region}`, type: 'QUOTA', service: 'VPC', name: 'VPCs per Region', description: '',
  region, usage, limit: 5, severity: usage >= 5 ? 'CRITICAL' : 'MEDIUM',
});

describe('AlertsLimitsSection', () => {
  it('shows one row per limit with a linked chip per region', () => {
    const rows = [quota('ap-south-1', 5), quota('ap-south-2', 4), quota('us-east-1', 4)];
    render(
      <AlertsLimitsSection groups={groupQuotaAlerts(rows)} total={3} isLoading={false} incomplete={false}
        consoleLink={consoleLink} />
    );

    expect(screen.getAllByText('VPCs per Region')).toHaveLength(1);
    expect(screen.getByText(/close to full in 3 regions/)).toBeInTheDocument();
    const chip = screen.getByText('ap-south-1').closest('a');
    expect(chip).toHaveAttribute('href', consoleLink('ap-south-1'));
    expect(chip).toHaveTextContent('5 / 5');
  });

  it('says the check found nothing, rather than showing an empty table', () => {
    render(<AlertsLimitsSection groups={[]} total={0} isLoading={false} incomplete={false} consoleLink={consoleLink} />);

    expect(screen.getByText('No service limit is above 75% in any region.')).toBeInTheDocument();
  });

  it('says the check did not run when its source failed', () => {
    render(<AlertsLimitsSection groups={[]} total={0} isLoading={false} incomplete consoleLink={consoleLink} />);

    expect(screen.getByText(/Couldn't check this/)).toBeInTheDocument();
    expect(screen.queryByText(/No service limit/)).not.toBeInTheDocument();
  });
});

describe('AlertsSecuritySection', () => {
  const finding: SecurityAlert = {
    id: 'finding-1', type: 'SECURITY', source: 'LIVE', service: 'XamOps', region: 'us-east-1',
    severity: 'HIGH', name: 'Administrator-level policy attached',
    description: 'Administrator-level policy attached — AdministratorAccess was attached to Akshay-Dev. '
      + 'Remediation: Confirm this level of access is required and time-bounded.',
  };

  it('keeps the fix behind "Show details" and reveals it on click', () => {
    render(
      <MemoryRouter>
        <AlertsSecuritySection alerts={[finding]} total={1} isLoading={false} incomplete={false}
          getAlertLink={() => link} />
      </MemoryRouter>
    );

    expect(screen.getByText('AdministratorAccess was attached to Akshay-Dev.')).toBeInTheDocument();
    expect(screen.queryByText('How to fix')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /show details and how to fix/i }));

    expect(screen.getByText('How to fix')).toBeInTheDocument();
    expect(screen.getByText('Confirm this level of access is required and time-bounded.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /open security center/i })).toHaveAttribute('href', '/security');
  });

  it('says so when the severity filter hides every finding', () => {
    render(
      <MemoryRouter>
        <AlertsSecuritySection alerts={[]} total={2} isLoading={false} incomplete={false} getAlertLink={() => link} />
      </MemoryRouter>
    );

    expect(screen.getByText(/Nothing at this severity. 2 alerts are hidden by the filter./)).toBeInTheDocument();
  });
});

describe('AlertsCostSection', () => {
  it('states that the check ran when there are no spikes', () => {
    render(<AlertsCostSection alerts={[]} total={0} isLoading={false} incomplete={false} getAlertLink={() => link} />);

    expect(screen.getByText('No cost spikes over $1 in the last 14 days.')).toBeInTheDocument();
  });
});
