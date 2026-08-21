import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  WarAttackPathBoard,
  WarBlockedBanner,
  WarCategoryStrip,
  WarFilterBar,
  WarFindingsTable,
  WarScanHealth,
  WarScoreHeader,
  WarTopRisks,
} from '../components/War';
import { demoWarReport } from './warMockData';

/**
 * Renders the pinned header and all five WAR tabs against the demo report.
 *
 * The data test proves the aggregates agree with each other; this proves each tab reads
 * the shape it was given. Every one of these panels would render as an empty state
 * against the `{}` the generic demo fallback used to serve, and an empty state is exactly
 * what a demo must not show.
 */
const draw = (ui: React.ReactElement) => render(<MemoryRouter>{ui}</MemoryRouter>);

const noop = () => undefined;

const report = demoWarReport('demo-aws-001');

const FILTERS = {
  attackPath: 'All',
  category: 'All',
  severity: 'All',
  status: 'OPEN' as const,
  search: '',
};

describe('WAR demo data renders in every tab', () => {
  it('the score header publishes a real verdict and account context', () => {
    draw(<WarScoreHeader report={report} />);
    expect(screen.getByText(String(report.warScore))).toBeInTheDocument();
    expect(screen.getByText('WAR score')).toBeInTheDocument();
    expect(screen.getByText(report.accountId)).toBeInTheDocument();
    // A dash here would mean the fixture failed the coverage gate.
    expect(screen.queryByText('—')).not.toBeInTheDocument();
  });

  it('the blocked banner stays hidden — this scan is not blocked', () => {
    const { container } = draw(<WarBlockedBanner report={report} onRetry={noop} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('Fix first ranks confirmed exposures with their evidence summary', () => {
    draw(
      <WarTopRisks
        risks={report.topRisks}
        unverifiedCount={report.statusCounts.UNKNOWN ?? 0}
        onSelectFinding={noop}
      />,
    );
    expect(screen.getByText('Fix these first')).toBeInTheDocument();
    expect(screen.getByText(report.topRisks[0].title)).toBeInTheDocument();
    // Several controls can share a weight, so this only asserts the rank input is shown.
    expect(screen.getAllByText(`weight ${report.topRisks[0].riskWeight}`).length)
      .toBeGreaterThan(0);
  });

  it('Attack paths shows a board of states rather than an empty grid', () => {
    draw(
      <WarAttackPathBoard
        detailed
        paths={report.attackPaths}
        activePath="All"
        onSelect={noop}
      />,
    );
    expect(screen.getByText(report.attackPaths[0].label)).toBeInTheDocument();
    expect(screen.getAllByText('Open').length).toBeGreaterThan(0);
  });

  it('Control families scores each advisory family', () => {
    draw(
      <WarCategoryStrip
        categories={report.categories}
        activeCategory="All"
        onSelect={noop}
      />,
    );
    expect(screen.getByText('Control families')).toBeInTheDocument();
    report.categories.forEach((c) => {
      expect(screen.getAllByText(c.category).length).toBeGreaterThan(0);
    });
  });

  it('All controls lists the baseline and its filter bar reflects the total', () => {
    const open = report.findings.filter(
      (f) => f.status === 'FAIL' || f.status === 'WARN' || f.status === 'UNKNOWN',
    );
    draw(
      <>
        <WarFilterBar
          report={report}
          filters={FILTERS}
          resultCount={open.length}
          onChange={noop}
          onReset={noop}
        />
        <WarFindingsTable findings={open} onResetFilters={noop} />
      </>,
    );
    expect(screen.getByText(`${open.length} of ${report.totalControls}`)).toBeInTheDocument();
    expect(screen.getByText(`Showing 1–15 of ${open.length}`)).toBeInTheDocument();
  });

  it('Scan health explains partial coverage instead of claiming full access', () => {
    draw(<WarScanHealth report={report} onRescan={noop} isRefreshing={false} />);
    expect(screen.getByText('Coverage')).toBeInTheDocument();
    expect(screen.getByText('Incomplete checks')).toBeInTheDocument();
    expect(screen.getByText('API access')).toBeInTheDocument();
    // The permission list is the tab's payload; "Full read access" would mean the
    // fixture has nothing to show here.
    expect(screen.queryByText('Full read access')).not.toBeInTheDocument();
    expect(screen.getByText(report.deniedActions[0])).toBeInTheDocument();
    report.scannedRegions.forEach((r) => {
      expect(screen.getAllByText(r).length).toBeGreaterThan(0);
    });
  });
});
