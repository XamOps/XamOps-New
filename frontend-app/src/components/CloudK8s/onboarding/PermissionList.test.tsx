import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PermissionList } from './PermissionList';
import type { PermissionManifest } from '../../../hooks/useK8sOnboarding';
import type { OnboardingFeatureWire } from './featureCatalogPresentation';

const DASH = '—';

function features(): OnboardingFeatureWire[] {
  return [
    { id: 'cost', label: 'Cost insights', phase: 1, available: true, reason: null, selected: true },
    { id: 'rightsizing', label: 'Workload rightsizing', phase: 1, available: true, reason: null, selected: true },
  ];
}

function manifest(overrides: Partial<PermissionManifest> = {}): PermissionManifest {
  return {
    version: '3',
    phase: 'READ_ONLY',
    clusterName: 'prod-eks',
    region: 'us-east-1',
    accountId: '123456789',
    identityMode: 'IRSA',
    nodeScaler: 'karpenter',
    kubernetes: [],
    aws: [
      {
        role: 'read',
        boundTo: 'arn:aws:iam::123456789012:role/xamops-read',
        policy: {
          Version: '2012-10-17',
          Statement: [
            { Sid: 'XamOpsRead', Effect: 'Allow', Action: ['eks:DescribeCluster', 'ec2:DescribeInstances'], Resource: '*' },
          ],
        },
      },
    ],
    adminPermissions: [],
    features: features(),
    nodeRemoval: false,
    notes: [],
    hash: 'abc123',
    ...overrides,
  };
}

describe('PermissionList', () => {
  it('lists the feature labels', () => {
    render(<PermissionList manifest={manifest()} />);
    expect(screen.getByText('Cost insights')).toBeInTheDocument();
    expect(screen.getByText('Workload rightsizing')).toBeInTheDocument();
  });

  it('lists the IAM actions the user is about to grant', () => {
    render(<PermissionList manifest={manifest()} />);
    expect(screen.getByText('eks:DescribeCluster')).toBeInTheDocument();
    expect(screen.getByText('ec2:DescribeInstances')).toBeInTheDocument();
  });

  it('shows the em-dash for a null identityMode', () => {
    const { container } = render(<PermissionList manifest={manifest({ identityMode: null })} />);
    const meta = container.querySelector('[data-permission-meta]');
    expect(meta).not.toBeNull();
    expect(meta!.textContent).toContain(DASH);
  });
});
