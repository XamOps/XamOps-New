import { Component, useMemo, type ReactNode } from 'react';
import { Icon } from '@iconify/react';
import Highcharts from 'highcharts';
import HighchartsReact from 'highcharts-react-official';
import { usePrefersReducedMotion } from './tokens';

export interface SeverityRow {
  date: string;
  CRITICAL: number;
  HIGH: number;
  OTHER: number;
  [k: string]: number | string;
}

interface Props {
  data: Array<Record<string, number | string>>;
  height?: number;
  /** Exposes the Highcharts instance so a header legend can toggle series. */
  onChartReady?: (chart: Highcharts.Chart) => void;
}

/**
 * Single-hue red (#ff5223), opacity-encoded. Hard-coded light constants — the page is light-only
 * and static, so there is NO theme watcher, NO MutationObserver, and NO re-render on a theme class.
 */
const RED = '#ff5223';
const LINE: [string, string, string] = [
  RED,
  'color-mix(in srgb, #ff5223 70%, transparent)',
  'color-mix(in srgb, #ff5223 40%, transparent)',
];
const FILL: Array<Highcharts.GradientColorObject> = [
  { linearGradient: { x1: 0, y1: 0, x2: 0, y2: 1 }, stops: [[0, 'rgba(255,82,35,0.125)'], [1, 'rgba(255,82,35,0)']] },
  { linearGradient: { x1: 0, y1: 0, x2: 0, y2: 1 }, stops: [[0, 'rgba(255,82,35,0.0875)'], [1, 'rgba(255,82,35,0)']] },
  { linearGradient: { x1: 0, y1: 0, x2: 0, y2: 1 }, stops: [[0, 'rgba(255,82,35,0.05)'], [1, 'rgba(255,82,35,0)']] },
];

/**
 * Build the memoized options object. Exported for unit testing (the chart canvas internals are not
 * asserted; this object carries every design decision — colors, animation, axis config, binding).
 *
 * Data binding (Recharts → Highcharts port): areaspline series read numeric arrays, so object rows
 * map to `xAxis.categories = data.map(r => r.date)` and each series `data = data.map(r =>
 * Number(r[KEY]) || 0)`. Series index order is fixed [0]=Critical, [1]=High, [2]=Other so a header
 * legend's setVisible(i) maps deterministically.
 */
export function buildAreaOptions(
  data: Array<Record<string, number | string>>,
  opts: { height: number; reducedMotion: boolean },
): Highcharts.Options {
  const categories = data.map((r) => String(r.date));
  const series: Highcharts.SeriesOptionsType[] = (['CRITICAL', 'HIGH', 'OTHER'] as const).map((key, i) => ({
    type: 'areaspline',
    name: key === 'OTHER' ? 'Other' : key.charAt(0) + key.slice(1).toLowerCase(),
    data: data.map((r) => Number(r[key]) || 0),
    color: LINE[i],
    lineColor: LINE[i],
    fillColor: FILL[i],
  }));

  return {
    chart: { type: 'areaspline', height: opts.height, backgroundColor: 'transparent', spacing: [8, 0, 0, 0], style: { fontFamily: 'inherit' } },
    title: { text: undefined },
    credits: { enabled: false },
    exporting: { enabled: false },
    legend: { enabled: false },
    xAxis: {
      categories,
      lineColor: '#d8dfe9',
      lineWidth: 1,
      tickLength: 0,
      gridLineWidth: 0,
      labels: { style: { color: '#75808e', fontSize: '12px' } },
    },
    yAxis: {
      title: { text: undefined },
      gridLineWidth: 0,
      lineWidth: 0,
      tickAmount: 4,
      labels: { style: { color: '#75808e', fontSize: '12px' } },
    },
    tooltip: {
      useHTML: true,
      outside: true,
      shared: false,
      borderWidth: 0,
      backgroundColor: 'transparent',
      shadow: false,
      padding: 0,
      formatter(this: Highcharts.Point) {
        const color = String(this.color ?? RED);
        return (
          `<div style="background:var(--xs-bg-primary);border:1px solid #d8dfe9;border-radius:8px;` +
          `padding:8px 12px;box-shadow:var(--xs-menu-shadow)">` +
          `<div style="display:flex;align-items:center;gap:6px;font-size:12px;color:#75808e">` +
          `<span style="width:8px;height:8px;border-radius:999px;background:${color};display:inline-block"></span>${this.series.name}</div>` +
          `<div style="font-size:14px;font-weight:500;color:#18202d">${this.y}</div>` +
          `<div style="font-size:12px;color:#75808e">${this.category}</div>` +
          `</div>`
        );
      },
    },
    plotOptions: {
      series: {
        animation: opts.reducedMotion ? false : { duration: 1000 },
        marker: { enabled: false, states: { hover: { enabled: true, radius: 4 } } },
        states: { hover: { halo: { size: 10, opacity: 0.25 } } },
        stickyTracking: true,
      },
      areaspline: { lineWidth: 2 },
    },
    series,
  };
}

const EmptyState: React.FC<{ height: number }> = ({ height }) => (
  <div style={{ height, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 8, color: 'var(--xs-text-secondary)' }}>
    <Icon icon="mdi:chart-areaspline" width={28} height={28} color="var(--xs-text-tertiary)" aria-hidden />
    <span style={{ fontSize: 14 }}>No vulnerability data for selected period</span>
  </div>
);

/** Keeps a malformed-options throw from taking the page down; renders the same empty box. */
class ChartErrorBoundary extends Component<{ height: number; children: ReactNode }, { failed: boolean }> {
  constructor(props: { height: number; children: ReactNode }) {
    super(props);
    this.state = { failed: false };
  }
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(err: unknown) { console.error('[XamSecure] overview chart render failed', err); }
  render() { return this.state.failed ? <EmptyState height={this.props.height} /> : this.props.children; }
}

/**
 * The ONLY chart-library chart on the page: a Highcharts areaspline of Critical/High/Other over
 * time. Options are built once per data (and reduced-motion) change via useMemo; nothing re-renders
 * on a theme signal.
 */
const SeverityAreaChart: React.FC<Props> = ({ data, height = 230, onChartReady }) => {
  const reduced = usePrefersReducedMotion();
  const options = useMemo(() => buildAreaOptions(data, { height, reducedMotion: reduced }), [data, height, reduced]);

  if (!Array.isArray(data) || data.length === 0) {
    return <EmptyState height={height} />;
  }

  return (
    <ChartErrorBoundary height={height}>
      <HighchartsReact
        highcharts={Highcharts}
        options={options}
        callback={(chart: Highcharts.Chart) => onChartReady?.(chart)}
      />
    </ChartErrorBoundary>
  );
};

export default SeverityAreaChart;
