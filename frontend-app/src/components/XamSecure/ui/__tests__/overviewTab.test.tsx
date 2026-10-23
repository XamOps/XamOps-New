import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import OverviewTab from '../../OverviewTab';
import * as demo from '../../../../demo/xamsecureMockData';

/** The Overview page uses CSS matchMedia for reduced-motion; keep it defined. */
function mockMatchMedia(reduce: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('reduce') ? reduce : false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

const draw = (ui: React.ReactElement) => render(<MemoryRouter>{ui}</MemoryRouter>);

afterEach(() => vi.restoreAllMocks());

describe('OverviewTab — full fixture', () => {
  it('places every existing metric: findings caption, top issue, threat feed, stories, freshness', () => {
    mockMatchMedia(false);
    draw(
      <OverviewTab
        summary={demo.demoXamSecureSummary}
        issues={demo.demoIssues}
        threats={demo.demoThreatsPage('?page=0&size=50')}
        stories={demo.demoThreatStories}
        vulnDashboard={demo.demoVulnDashboard}
        isLoading={false}
      />,
    );
    // Findings-summary caption (widget 1).
    expect(screen.getByText(`${demo.demoIssues.length} total issues`)).toBeInTheDocument();
    // KPI hero label (widget 3).
    expect(screen.getAllByText('Active threat stories').length).toBeGreaterThan(0);
    // Threat feed row (widget 8).
    expect(screen.getByText(/Crypto-mining binary/)).toBeInTheDocument();
    // Data freshness footer, driven by summary.syncState.
    expect(screen.getByText('vulnerabilities')).toBeInTheDocument();
    expect(screen.getByText('Data Freshness')).toBeInTheDocument();
    // Funnel stat labels (widget 4).
    expect(screen.getByText('Events')).toBeInTheDocument();
    expect(screen.getByText('Detections')).toBeInTheDocument();
  });

  it('renders no dark class and no Tailwind dark: variant anywhere in the tree', () => {
    mockMatchMedia(false);
    const { container } = draw(
      <OverviewTab
        summary={demo.demoXamSecureSummary}
        issues={demo.demoIssues}
        threats={demo.demoThreatsPage('?page=0&size=50')}
        stories={demo.demoThreatStories}
        vulnDashboard={demo.demoVulnDashboard}
        isLoading={false}
      />,
    );
    const html = container.innerHTML;
    expect(container.querySelector('.dark')).toBeNull();
    expect(container.querySelector('[data-theme="dark"]')).toBeNull();
    expect(html).not.toMatch(/\bdark:[a-z-]/);
    // The page root carries the token-consumption scope class.
    expect(container.querySelector('.xs-overview')).not.toBeNull();
  });
});

describe('OverviewTab — empty / null props', () => {
  it('renders empty states without fabricating numbers', () => {
    mockMatchMedia(false);
    draw(
      <OverviewTab
        summary={{ threatsBySeverity: {}, vulnerabilitiesBySeverity: {}, postureBySeverity: {}, syncState: [] }}
        issues={[]}
        threats={{ content: [], totalElements: 0, totalPages: 0, number: 0 }}
        stories={{ activeCount: 0, stories: [] }}
        vulnDashboard={null}
        isLoading={false}
      />,
    );
    expect(screen.getByText('0 total issues')).toBeInTheDocument();
    expect(screen.getByText('No open issues.')).toBeInTheDocument();
    expect(screen.getByText('No recent threat detections.')).toBeInTheDocument();
    // Area chart empty state (null vulnDashboard).
    expect(screen.getByText(/No vulnerability data for selected period/)).toBeInTheDocument();
    // Sync footer empty state.
    expect(screen.getByText('Sync has not run yet.')).toBeInTheDocument();
  });

  it('handles all-null props and shows the loading skeleton when still loading', () => {
    mockMatchMedia(false);
    const { container } = draw(
      <OverviewTab summary={null} issues={null} threats={null} stories={null} vulnDashboard={null} isLoading />,
    );
    // Skeleton shimmer shells render while the first load is in flight.
    expect(container.querySelectorAll('.xs-skeleton').length).toBeGreaterThan(0);
  });
});
