import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import type { HistoryDataPoint, HistorySummary } from '../../../hooks/useSpotAutomationData';

type DatasetConfig = { label: string; data: Array<number | null> };
const charts = vi.hoisted(() => [] as Array<{ data: { labels: string[]; datasets: DatasetConfig[] } }>);

vi.mock('chart.js', () => ({
  Chart: class {
    static register() {}
    constructor(_canvas: unknown, config: (typeof charts)[number]) {
      charts.push(config);
    }
    destroy() {}
  },
  registerables: [],
}));

import SpotAutomationChart from '../SpotAutomationChart';

const zeroSummary: HistorySummary = { total_savings: 0, average_hourly_savings: 0, peak_savings: 0 };

function renderChart(historyData: HistoryDataPoint[], historySummary: HistorySummary = zeroSummary) {
  return render(
    <SpotAutomationChart
      historyData={historyData}
      historySummary={historySummary}
      timeRange="last7d"
      isLoading={false}
      onTimeRangeChange={() => {}}
      onRefresh={() => {}}
    />,
  );
}

const datasets = () => Object.fromEntries(charts[charts.length - 1].data.datasets.map((ds) => [ds.label, ds.data]));

describe('SpotAutomationChart', () => {
  beforeEach(() => {
    charts.length = 0;
  });

  it('GCP history with missing days: one point per recorded day, legacy on its own series, no $0 drawn', () => {
    // What the GCP endpoint now returns for two recorded days in a seven-day range.
    renderChart(
      [
        { timestamp: '2026-10-03', total_actual_savings: 3, total_potential_savings: null, legacy: true },
        { timestamp: '2026-10-08', total_actual_savings: 2, total_potential_savings: null, legacy: true },
      ],
      { ...zeroSummary, legacy_total_savings: 5 },
    );

    expect(charts).toHaveLength(1);
    expect(charts[0].data.labels).toHaveLength(2); // the missing days are not padded in
    const byLabel = datasets();
    expect(byLabel['Actual Savings']).toEqual([null, null]);
    expect(byLabel['Potential Savings']).toEqual([null, null]);
    expect(byLabel['Legacy (unverified)']).toEqual([3, 2]);
    // No series is drawn through zero for a day nothing was recorded on.
    expect(Object.values(byLabel).flat()).not.toContain(0);

    const legacy = screen.getByTestId('history-legacy-total');
    expect(within(legacy).getByText('$5.00')).toBeInTheDocument();
    expect(within(legacy).getByText('Legacy (unverified)')).toBeInTheDocument();
  });

  it('AWS: legacy past days and the live point for today stay on separate series', () => {
    renderChart(
      [
        { timestamp: '2026-10-04', total_actual_savings: 4, total_potential_savings: null, legacy: true },
        { timestamp: '2026-10-06', total_actual_savings: 6, total_potential_savings: null, legacy: true },
        { timestamp: '2026-10-09', total_actual_savings: 12, total_potential_savings: 19.2, legacy: false },
      ],
      { total_savings: 12, average_hourly_savings: 0.5, peak_savings: 12, legacy_total_savings: 10 },
    );

    const byLabel = datasets();
    expect(byLabel['Actual Savings']).toEqual([null, null, 12]);
    expect(byLabel['Potential Savings']).toEqual([null, null, 19.2]);
    expect(byLabel['Legacy (unverified)']).toEqual([4, 6, null]);
    expect(screen.getByText('$12.00')).toBeInTheDocument(); // Period Savings: the live point only
  });

  it('a range with no data at all shows the "no data" message and draws no chart', () => {
    renderChart([]);

    expect(screen.getByText('No history data available for this period')).toBeInTheDocument();
    expect(charts).toHaveLength(0);
    expect(screen.queryByTestId('history-legacy-total')).not.toBeInTheDocument();
  });

  it('adds no legacy series or figure when no point is legacy', () => {
    renderChart([{ timestamp: '2026-10-09', total_actual_savings: 12, total_potential_savings: 19.2, legacy: false }],
      { total_savings: 12, average_hourly_savings: 0.5, peak_savings: 12, legacy_total_savings: 0 });

    expect(Object.keys(datasets())).toEqual(['Actual Savings', 'Potential Savings']);
    expect(screen.queryByText('Legacy (unverified)')).not.toBeInTheDocument();
  });
});
