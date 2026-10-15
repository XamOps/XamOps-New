import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { ResourceSecurityFindings } from '../../../api/curInsightsApi';
import ResourceSecurityTab, { toSearch, type SecurityFetchers } from '../ResourceSecurityTab';
import {
  securityFindingMatchesResource,
  performanceInsightMatchesResource,
  complianceControlMatchesResource,
} from '../resourceSecurityDeepLink';

/**
 * The Security & Alerts tab's obligations: fetch nothing until it is asked; render the state ladder
 * (loading, error, empty) exactly; group findings and link each row to the server-provided target;
 * and keep rows keyboard-activatable. Plus the three destination `?resource=` matchers the rows
 * navigate into.
 */

// A spy for the navigation target, shared by every render.
const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigate };
});

const ARN = 'arn:aws:ec2:ap-south-1:123456789012:instance/i-0abc';

const FINDINGS: ResourceSecurityFindings = {
  resourceId: ARN,
  matched: true,
  groups: [
    {
      product: 'SECURITY_CENTER',
      label: 'Security Center',
      route: '/security',
      findings: [
        {
          title: 'Public S3 bucket',
          severity: 'CRITICAL',
          deepLink: { path: '/security', query: { resource: ARN } },
        },
      ],
    },
    {
      product: 'PERFORMANCE',
      label: 'Performance Insights',
      route: '/performance',
      findings: [
        {
          title: 'Underutilised instance',
          severity: 'LOW',
          deepLink: { path: '/performance', query: { resource: 'i-0abc' } },
        },
      ],
    },
  ],
  errors: [],
};

const fetchers = (findings: () => Promise<ResourceSecurityFindings | null>): SecurityFetchers =>
  ({ findings });

const renderTab = (props: {
  active?: boolean;
  security: SecurityFetchers;
}) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ResourceSecurityTab
          active={props.active ?? true}
          accountId="123456789012"
          resourceId={ARN}
          period="2026-08"
          security={props.security}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

beforeEach(() => navigate.mockReset());

describe('ResourceSecurityTab', () => {
  it('fetches nothing while inactive', () => {
    const findings = vi.fn().mockResolvedValue(FINDINGS);
    renderTab({ active: false, security: fetchers(findings) });
    expect(findings).not.toHaveBeenCalled();
  });

  it('renders the empty state when there are no groups and no errors', async () => {
    renderTab({
      security: fetchers(() => Promise.resolve({ ...FINDINGS, groups: [], errors: [] })),
    });
    expect(await screen.findByText('No findings reference this resource')).toBeInTheDocument();
  });

  it('renders the error state when the request could not be read', async () => {
    renderTab({ security: fetchers(() => Promise.resolve(null)) });
    expect(
      await screen.findByText('The findings for this resource could not be read'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('renders a per-product error row alongside the groups that did load', async () => {
    renderTab({
      security: fetchers(() => Promise.resolve({
        ...FINDINGS,
        errors: [{ product: 'PERFORMANCE', message: 'Performance insights could not be read.' }],
      })),
    });
    // The Security Center group still renders.
    expect(await screen.findByText('Public S3 bucket')).toBeInTheDocument();
    // And the error line shows.
    expect(
      screen.getByText('Performance insights could not be read.'),
    ).toBeInTheDocument();
  });

  it('groups findings and navigates to the server-provided target on a row click', async () => {
    renderTab({ security: fetchers(() => Promise.resolve(FINDINGS)) });

    const row = await screen.findByRole('button', { name: /Public S3 bucket/ });
    // Rows are real <button>s, so they are keyboard-activatable.
    expect(row.tagName).toBe('BUTTON');

    await userEvent.click(row);
    expect(navigate).toHaveBeenCalledWith({
      pathname: '/security',
      search: `?resource=${encodeURIComponent(ARN)}`,
    });

    await userEvent.click(screen.getByRole('button', { name: /Underutilised instance/ }));
    expect(navigate).toHaveBeenCalledWith({
      pathname: '/performance',
      search: '?resource=i-0abc',
    });
  });
});

describe('toSearch', () => {
  it('is empty for an empty query and encoded otherwise', () => {
    expect(toSearch({})).toBe('');
    expect(toSearch({ resource: ARN })).toBe(`?resource=${encodeURIComponent(ARN)}`);
  });
});

describe('destination ?resource= matchers', () => {
  it('Security Center is ARN-tolerant: a NAME-form finding matches an ARN param', () => {
    // Live projection: resourceId is the ARN — exact match.
    expect(securityFindingMatchesResource(ARN, ARN)).toBe(true);
    // Prowler/describe row: resourceId is the short id, the ARN param ends with "/i-0abc".
    expect(securityFindingMatchesResource('i-0abc', ARN)).toBe(true);
    // An IAM-style name as the ":"-tail of its ARN.
    expect(
      securityFindingMatchesResource('alice', 'arn:aws:iam::123456789012:user/alice'),
    ).toBe(true);
    // A different resource does not match.
    expect(securityFindingMatchesResource('i-0xyz', ARN)).toBe(false);
    // No param → everything passes (page unchanged).
    expect(securityFindingMatchesResource('anything', null)).toBe(true);
  });

  it('Performance matches on the exact short id', () => {
    expect(performanceInsightMatchesResource('i-0abc', 'i-0abc')).toBe(true);
    expect(performanceInsightMatchesResource('i-0abc', 'i-0xyz')).toBe(false);
    expect(performanceInsightMatchesResource(undefined, 'i-0abc')).toBe(false);
    expect(performanceInsightMatchesResource('i-0abc', null)).toBe(true);
  });

  it('ComplianceOps matches a control whose resource carries the exact uid', () => {
    expect(complianceControlMatchesResource([ARN, 'i-other'], ARN)).toBe(true);
    expect(complianceControlMatchesResource(['i-other'], ARN)).toBe(false);
    expect(complianceControlMatchesResource([null], ARN)).toBe(false);
    expect(complianceControlMatchesResource([], null)).toBe(true);
  });
});
