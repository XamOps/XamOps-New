import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PreflightList } from './PreflightList';
import type { PreflightResult } from '../../../hooks/useK8sOnboarding';

function result(overrides: Partial<PreflightResult> = {}): PreflightResult {
  return {
    ranAt: '2026-11-04T10:00:00Z',
    blocking: true,
    identityMode: 'IRSA',
    nodeScaler: 'karpenter',
    checks: [
      { id: 'oidc', result: 'PASS', blocksPhases: [], title: 'OIDC provider present' },
      {
        id: 'iam-role',
        result: 'FAIL',
        blocksPhases: ['AUTOMATION'],
        title: 'IAM role is bound',
        detail: 'No access entry maps the XamOps role.',
        remediation: { text: 'Create the access entry with the CLI.', command: 'aws eks create-access-entry --cluster-name prod-eks' },
      },
      { id: 'metrics', result: 'UNKNOWN', blocksPhases: [], title: 'Metrics source reachable' },
    ],
    ...overrides,
  };
}

describe('PreflightList', () => {
  it('renders a PASS check', () => {
    const { container } = render(<PreflightList result={result()} />);
    const row = container.querySelector('[data-check-row="oidc"]');
    expect(row).not.toBeNull();
    expect(row!.textContent).toContain('OIDC provider present');
    expect(row!.getAttribute('data-check-result')).toBe('PASS');
  });

  it('renders a blocking FAIL with its remediation command', () => {
    const { container } = render(<PreflightList result={result()} />);
    const row = container.querySelector('[data-check-row="iam-role"]');
    expect(row).not.toBeNull();
    expect(row!.textContent).toContain('Create the access entry with the CLI.');
    expect(screen.getByText('aws eks create-access-entry --cluster-name prod-eks')).toBeInTheDocument();
  });

  it('renders an UNKNOWN check', () => {
    const { container } = render(<PreflightList result={result()} />);
    const row = container.querySelector('[data-check-row="metrics"]');
    expect(row).not.toBeNull();
    expect(row!.getAttribute('data-check-result')).toBe('UNKNOWN');
    expect(row!.textContent).toContain('Metrics source reachable');
  });
});
