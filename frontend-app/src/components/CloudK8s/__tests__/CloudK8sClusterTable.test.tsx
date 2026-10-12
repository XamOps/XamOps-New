import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { EKSCluster } from '../../../pages/CloudK8sPage';
import CloudK8sClusterTable, { CONTAINER_INSIGHTS_TOOLTIP } from '../CloudK8sClusterTable';

const cluster = (name: string, over: Partial<EKSCluster>): EKSCluster => ({ name, region: 'us-east-1', version: '1.29', connected: false, ...over });

describe('fleet table: the Container Insights column (demo-cut review S3)', () => {
  it('names the check it shows, and reads each state from the backend detail', () => {
    render(
      <CloudK8sClusterTable
        isLoading={false}
        onViewDetails={() => {}}
        clusters={[
          cluster('configured', { connected: true, containerInsights: { status: 'CONFIGURED' } }),
          cluster('not-configured', { containerInsights: { status: 'NOT_CONFIGURED' } }),
          cluster('denied', { containerInsights: { status: 'UNKNOWN', reason: 'MISSING_AWS_PERMISSION', action: 'logs:DescribeLogGroups' } }),
          // A list cached before the detail existed: its old `connected` meant something else.
          cluster('cached', { connected: true }),
        ]}
      />,
    );
    const header = screen.getByRole('columnheader', { name: 'Container Insights' });
    expect(header).toHaveAttribute('title', CONTAINER_INSIGHTS_TOOLTIP);
    expect(screen.queryByText('Connected')).toBeNull();
    const cell = (name: string) => within(screen.getByText(name).closest('tr')!).getAllByRole('cell')[3];
    expect(cell('configured')).toHaveTextContent('Configured');
    expect(cell('not-configured')).toHaveTextContent('Not configured');
    expect(cell('denied')).toHaveTextContent('Unknown');
    expect(cell('denied').querySelector('[title]')).toHaveAttribute('title', "XamOps couldn't check: the role is denied logs:DescribeLogGroups.");
    expect(cell('cached')).toHaveTextContent('Unknown');
  });
});
