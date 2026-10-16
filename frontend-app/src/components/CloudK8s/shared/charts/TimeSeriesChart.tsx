/**
 * A time-series area chart (recharts 3.8; design-delta §3, §3.3). It draws one or more series as
 * filled or stacked areas, optional bands (freed / ready / no-history / not-in-effect), and optional
 * markers (e.g. the apply time). Every series sets its `stroke` and `fill` explicitly from
 * chartTokens, so a recharts default colour (#8884d8 / #82ca9d / #ffc658) is never emitted.
 *
 * Deterministic and seek-safe (§2.7): animation is off, so identical inputs render identical DOM —
 * there is no wall-clock-dependent motion. In-area labels are drawn as ink on a card-coloured halo
 * so they meet AA on any layer colour (§2.3).
 */
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  ReferenceArea,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { ChartDefs } from './ChartDefs';
import { HATCH, SERIES, type SeriesKind } from './chartTokens';

export interface TimeSeriesSeries {
  /** The data key in each point. */
  dataKey: string;
  /** The series meaning; fixes colour, line style and fill (§2.2). */
  kind: SeriesKind;
  /** The legend / tooltip name. */
  name: string;
  /** Stack onto this id to build a stacked area; omit for an overlaid filled area. */
  stackId?: string;
  /** A line only (no fill), e.g. a provisioned/suggested reference line. */
  lineOnly?: boolean;
}

export interface TimeSeriesBand {
  /** A hatch band standing for a real quantity (§3.2). */
  kind: keyof typeof HATCH;
  x1: number | string;
  x2: number | string;
  label?: string;
}

export interface TimeSeriesMarker {
  x: number | string;
  label?: string;
}

export interface TimeSeriesChartProps {
  data: Array<Record<string, number | string | null>>;
  series: TimeSeriesSeries[];
  xKey: string;
  bands?: TimeSeriesBand[];
  markers?: TimeSeriesMarker[];
  /** Height in px; width fills the container. */
  height?: number;
  /** A fixed width (tests and deterministic snapshots pass one so ResponsiveContainer is skipped). */
  width?: number;
  /** An accessible name for the plot. */
  ariaLabel?: string;
}

export function TimeSeriesChart({
  data,
  series,
  xKey,
  bands = [],
  markers = [],
  height = 220,
  width,
  ariaLabel,
}: TimeSeriesChartProps) {
  const chart = (
    <AreaChart data={data} margin={{ top: 12, right: 12, bottom: 4, left: 4 }} role="img" aria-label={ariaLabel}>
      <defs>
        <ChartDefs />
      </defs>
      {/* Grid and axes are greyscale: colour is reserved for series. */}
      <CartesianGrid stroke="currentColor" strokeOpacity={0.12} vertical={false} />
      <XAxis dataKey={xKey} stroke="currentColor" strokeOpacity={0.4} tick={{ fontSize: 11 }} />
      <YAxis stroke="currentColor" strokeOpacity={0.4} tick={{ fontSize: 11 }} width={36} />
      <Tooltip isAnimationActive={false} />

      {bands.map((band, i) => (
        <ReferenceArea
          key={`band-${i}`}
          x1={band.x1}
          x2={band.x2}
          fill={`url(#${HATCH[band.kind].id})`}
          fillOpacity={1}
          stroke="none"
          ifOverflow="extendDomain"
          label={band.label ? { value: band.label, fontSize: 11, fill: 'currentColor' } : undefined}
        />
      ))}

      {series.map((s) => {
        const style = SERIES[s.kind];
        const common = {
          dataKey: s.dataKey,
          name: s.name,
          stroke: style.color,
          strokeWidth: style.strokeWidth,
          strokeDasharray: style.dash,
          isAnimationActive: false,
          dot: false,
          connectNulls: false,
        };
        if (s.lineOnly) {
          return <Line key={s.dataKey} type="monotone" {...common} />;
        }
        return (
          <Area
            key={s.dataKey}
            type="monotone"
            {...common}
            stackId={s.stackId}
            fill={style.color}
            fillOpacity={style.fillOpacity}
          />
        );
      })}

      {markers.map((m, i) => (
        <ReferenceLine
          key={`marker-${i}`}
          x={m.x}
          stroke="currentColor"
          strokeOpacity={0.5}
          strokeDasharray="3 3"
          label={m.label ? { value: m.label, fontSize: 11, fill: 'currentColor' } : undefined}
        />
      ))}
    </AreaChart>
  );

  if (width) {
    return (
      <div style={{ width, height }} className="text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]" data-timeseries="" data-k8s-kit="">
        <ResponsiveContainer width={width} height={height}>
          {chart}
        </ResponsiveContainer>
      </div>
    );
  }
  return (
    <div style={{ height }} className="text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]" data-timeseries="" data-k8s-kit="">
      <ResponsiveContainer width="100%" height={height}>
        {chart}
      </ResponsiveContainer>
    </div>
  );
}

export default TimeSeriesChart;
