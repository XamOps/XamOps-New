import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ScopeFallbackBanner from './ScopeFallbackBanner';
import { ObservabilityConfig } from '../../hooks/useObservabilityData';

const baseConfig: ObservabilityConfig = {
  orgId: 'org-abc',
  retentionDays: 30,
  provisionedAt: '2026-01-01T00:00:00Z',
};

function renderBanner(config: ObservabilityConfig | null, selectedAccountId?: string | null) {
  return render(
    <MemoryRouter>
      <ScopeFallbackBanner config={config} selectedAccountId={selectedAccountId} />
    </MemoryRouter>,
  );
}

/**
 * Each cloud account gets its own OpenObserve org. When it isn't provisioned, queries fall
 * back to the client-level org, which used to be a server-side log line only — the user saw
 * organisation-wide totals attributed to the account they had selected.
 */
describe('ScopeFallbackBanner', () => {
  it('warns when an account is selected but the response is client-scoped', () => {
    renderBanner(
      { ...baseConfig, scope: 'client', accountProvisioned: false, requestedAccountId: '605134457560' },
      '605134457560',
    );

    expect(screen.getByText('Showing organisation-wide data')).toBeInTheDocument();
  });

  it('prefers the reason supplied by the backend', () => {
    renderBanner(
      {
        ...baseConfig,
        scope: 'client',
        accountProvisioned: false,
        fallbackReason: 'Custom explanation from the API.',
      },
      '605134457560',
    );

    expect(screen.getByText('Custom explanation from the API.')).toBeInTheDocument();
  });

  it('falls back to naming the account when no reason is supplied', () => {
    renderBanner({ ...baseConfig, accountProvisioned: false }, '605134457560');

    expect(screen.getByText(/605134457560/)).toBeInTheDocument();
  });

  it('stays hidden for a properly account-scoped response', () => {
    const { container } = renderBanner(
      { ...baseConfig, scope: 'account', accountProvisioned: true },
      '605134457560',
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('stays hidden when no account is selected', () => {
    // Client scope is exactly what was asked for here, so warning would be noise.
    const { container } = renderBanner(
      { ...baseConfig, scope: 'client', accountProvisioned: false },
      null,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('stays hidden before config has loaded', () => {
    const { container } = renderBanner(null, '605134457560');
    expect(container).toBeEmptyDOMElement();
  });

  it('stays hidden for older responses that omit the scope fields', () => {
    // Backwards compatibility: absent fields must not be read as a fallback.
    const { container } = renderBanner(baseConfig, '605134457560');
    expect(container).toBeEmptyDOMElement();
  });
});
