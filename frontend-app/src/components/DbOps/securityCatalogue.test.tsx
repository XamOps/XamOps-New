import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  SecurityTab, SECURITY_CATALOGUE_AWS, SECURITY_CATALOGUE_GCP,
} from './DbInstanceDetailDrawer';

const flattenNames = (catalogue: { checks: { name: string }[] }[]) =>
  catalogue.flatMap(c => c.checks.map(chk => chk.name));

describe('DbOps security catalogue (COR-23)', () => {
  it('advertises exactly the four checks the backend evaluates (AWS)', () => {
    expect(flattenNames(SECURITY_CATALOGUE_AWS)).toEqual([
      'Public Accessibility',
      'Encryption at Rest',
      'Automated Backups',
      'Multi-AZ Deployment',
    ]);
  });

  it('advertises exactly the four checks the backend evaluates (GCP)', () => {
    expect(flattenNames(SECURITY_CATALOGUE_GCP)).toEqual([
      'Public Accessibility',
      'Encryption at Rest',
      'Automated Backups',
      'High Availability (HA)',
    ]);
  });

  it('renders the four check names and no un-wired "Configure Security Checks" control', () => {
    render(<SecurityTab checks={[]} instanceSource="rds" cloudProvider="aws" />);

    expect(screen.getByText('What we check')).toBeInTheDocument();
    expect(screen.getByText('Public Accessibility')).toBeInTheDocument();
    expect(screen.getByText('Encryption at Rest')).toBeInTheDocument();
    expect(screen.getByText('Automated Backups')).toBeInTheDocument();
    expect(screen.getByText('Multi-AZ Deployment')).toBeInTheDocument();

    expect(screen.queryByText(/Configure Security Checks/i)).toBeNull();
  });
});
