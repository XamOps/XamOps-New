import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import OverviewTab from '../../OverviewTab';
import type { XamSecureSummary } from '../../../../types/xamsecure';

/**
 * The data-freshness footer renders a calm amber "Sync issue" pill when a sync domain carries a
 * `lastError`, and keeps the raw backend error string in the `title` tooltip ONLY — never in the
 * visible card body. (This is the regression the user originally reported: a Data Freshness error
 * must degrade calmly rather than shout or leak a stack trace.)
 */
const RAW_ERROR = 'java.net.SocketTimeoutException: Read timed out at UpwindClient.fetch(line 412)';

const summaryWithFailure: XamSecureSummary = {
  threatsBySeverity: {},
  vulnerabilitiesBySeverity: {},
  postureBySeverity: {},
  syncState: [
    { domain: 'threats', lastSuccessAt: '2024-01-01T12:00:00Z', lastError: null },
    { domain: 'vulnerabilities', lastSuccessAt: '2024-01-01T10:00:00Z', lastError: RAW_ERROR },
  ],
};

const draw = () =>
  render(
    <MemoryRouter>
      <OverviewTab
        summary={summaryWithFailure}
        issues={[]}
        threats={null}
        stories={null}
        vulnDashboard={null}
        isLoading={false}
      />
    </MemoryRouter>,
  );

describe('Data freshness footer', () => {
  it('shows a calm amber "Sync issue" pill for a failing domain, raw error in the title only', () => {
    draw();
    const pillText = screen.getByText(/Sync issue — showing last data/);
    expect(pillText).toBeInTheDocument();

    // The raw backend error is NOT surfaced as visible text anywhere on the page.
    expect(screen.queryByText(RAW_ERROR)).toBeNull();
    expect(screen.queryByText(/SocketTimeoutException/)).toBeNull();

    // It lives only in the title attribute of the pill container.
    const pill = pillText.closest('[title]');
    expect(pill).not.toBeNull();
    expect(pill).toHaveAttribute('title', RAW_ERROR);
  });

  it('shows a calm green "Last synced" status for a healthy domain', () => {
    draw();
    expect(screen.getByText(/Last synced/)).toBeInTheDocument();
  });
});
