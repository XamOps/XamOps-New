import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ComplianceOpsPage from '../pages/ComplianceOpsPage';
import { resolveDemoResponse } from './demoRouter';
import { DEMO_MODE_KEY, DEMO_ROLE_KEY } from './demoConfig';

/**
 * End-to-end for the demo path: ComplianceOps calls `fetch` directly rather than
 * going through apiClient, so this stubs fetch with the demo router exactly as
 * main.tsx does in the browser and asserts real findings reach the page.
 */
vi.mock('../contexts/TopbarContext', () => ({
  useTopbar: () => ({ setTitle: vi.fn(), setActions: vi.fn(), clearActions: vi.fn() }),
}));
vi.mock('../contexts/AccountContext', () => ({
  useAccounts: () => ({
    selectedAccountIds: ['demo-aws-001'],
    selectedAccountDisplay: { id: 'demo-aws-001', name: 'Xammer Production', provider: 'AWS' },
  }),
}));

const realFetch = global.fetch;

beforeEach(() => {
  sessionStorage.setItem(DEMO_MODE_KEY, 'true');
  sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  global.fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString();
    const mock = resolveDemoResponse(url);
    if (mock) return mock;
    throw new Error(`unexpected live fetch in demo mode: ${url}`);
  }) as typeof fetch;
});

afterEach(() => {
  global.fetch = realFetch;
  vi.restoreAllMocks();
});

describe('ComplianceOps renders AWS demo Prowler findings', () => {
  it('loads findings instead of the empty state', async () => {
    render(<MemoryRouter><ComplianceOpsPage /></MemoryRouter>);

    await waitFor(() =>
      expect(screen.getAllByText(/S3 buckets should block public read access/).length)
        .toBeGreaterThan(0));
  });

  it('maps findings onto the compliance frameworks the page knows', async () => {
    render(<MemoryRouter><ComplianceOpsPage /></MemoryRouter>);

    // Framework cards are built from unmapped.compliance keys via getFrameworkKey
    await waitFor(() => expect(screen.getAllByText(/CIS Benchmark/).length).toBeGreaterThan(0));
    expect(screen.getAllByText(/SOC 2/).length).toBeGreaterThan(0);
  });
});
