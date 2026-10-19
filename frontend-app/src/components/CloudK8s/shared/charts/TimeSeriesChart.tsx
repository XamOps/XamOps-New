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
import { useId } from 'react';
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
import { ChartDefs, chartIdSuffix, hatchId } from './ChartDefs';
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
  /**
   * Bound the band vertically too, e.g. the freed request between the size after and before a change,
   * or the zone above an HPA's target (y1 only: up to the top of the plot). Omit both for full height.
   */
  y1?: number;
  y2?: number;
  label?: string;
}

export interface TimeSeriesMarker {
  x: number | string;
  label?: string;
}

/**
 * A constant drawn across the plot in its series' token and line style, e.g. XamOps's suggestion now.
 * It is one value, not a series: nothing is drawn per point.
 */
export interface TimeSeriesReference {
  y: number;
  kind: SeriesKind;
  label?: string;
}

export interface TimeSeriesChartProps {
  data: Array<Record<string, number | string | null>>;
  series: TimeSeriesSeries[];
  xKey: string;
  bands?: TimeSeriesBand[];
  markers?: TimeSeriesMarker[];
  references?: TimeSeriesReference[];
  /** Height in px; width fills the container. */
  height?: number;
  /** A fixed width (tests and deterministic snapshots pass one so ResponsiveContainer is skipped). */
  width?: number;
  /** An accessible name for the plot. */
  ariaLabel?: string;
  /** Formats a value-axis tick (e.g. `v => `${v}m``). */
  tickFormat?: (value: number) => string;
}

export function TimeSeriesChart({
  data,
  series,
  xKey,
  bands = [],
  markers = [],
  references = [],
  height = 220,
  width,
  ariaLabel,
  tickFormat,
}: TimeSeriesChartProps) {
  // This chart's own pattern ids, so a band never resolves to another chart's SVG.
  const suffix = chartIdSuffix(useId());
  const chart = (
    <AreaChart data={data} margin={{ top: 12, right: 12, bottom: 4, left: 4 }} role="img" aria-label={ariaLabel}>
      <defs>
        <ChartDefs suffix={suffix} />
      </defs>
      {/* Grid and axes are greyscale: colour is reserved for series. */}
      <CartesianGrid stroke="currentColor" strokeOpacity={0.12} vertical={false} />
      <XAxis dataKey={xKey} stroke="currentColor" strokeOpacity={0.4} tick={{ fontSize: 11 }} minTickGap={24} />
      <YAxis
        stroke="currentColor"
        strokeOpacity={0.4}
        tick={{ fontSize: 11 }}
        width={tickFormat ? 52 : 36}
        tickFormatter={tickFormat ? (v: number) => tickFormat(v) : undefined}
      />
      <Tooltip isAnimationActive={false} />

      {bands.map((band, i) => (
        <ReferenceArea
          key={`band-${i}`}
          x1={band.x1}
          x2={band.x2}
          y1={band.y1}
          y2={band.y2}
          fill={`url(#${hatchId(HATCH[band.kind].id, suffix)})`}
          fillOpacity={1}
          stroke="none"
          ifOverflow="extendDomain"
          label={band.label ? { value: band.label, fontSize: 11, fill: 'currentColor' } : undefined}
        />
      ))}

      {references.map((r, i) => (
        <ReferenceLine
          key={`ref-${i}`}
          y={r.y}
          stroke={SERIES[r.kind].color}
          strokeWidth={SERIES[r.kind].strokeWidth}
          strokeDasharray={SERIES[r.kind].dash}
          ifOverflow="extendDomain"
          label={r.label ? { value: r.label, fontSize: 11, fill: 'currentColor', position: 'insideTopRight' } : undefined}
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
