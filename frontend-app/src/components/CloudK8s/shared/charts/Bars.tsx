/**
 * A bar chart (recharts 3.8) in its four v3 modes (design-delta §2.5, §3):
 *   - 'grouped'      — grouped bars per x (e.g. CPU by namespace: requested / used / suggested);
 *   - 'before-after' — a before and an after bar per x (e.g. before/after a change, per node);
 *   - 'capacity'     — horizontal capacity rows (requested of allocatable per node);
 *   - 'day'          — one stacked/!grouped bar per IST day (changes per day, cost per day).
 *
 * Every bar sets its `fill` (and, for money "extra", a `stroke` outline, §2.1) explicitly from
 * chartTokens — never a recharts default. A series bar's outline keeps its series' dash (§2.2 "one
 * line style per series"), and a `hatch`ed series (the Kubernetes VPA's suggestion: the suggested
 * hue, hatched, §2.2) fills with a pattern declared in this chart's own `<defs>` and draws that
 * outline. `scale="log"` reads values that span orders of magnitude (CPU per pod, 10m to 1000m) on
 * powers of ten; a value that isn't above zero is a gap there, never a bar. Deterministic and
 * seek-safe: animation is off.
 */
import { useId } from 'react';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ChartDefs, chartIdSuffix, hatchId } from './ChartDefs';
import { HATCH, MONEY, SERIES, type HatchKind, type MoneyKind, type SeriesKind } from './chartTokens';

export type BarsMode = 'grouped' | 'before-after' | 'capacity' | 'day';

export interface BarSeries {
  dataKey: string;
  name: string;
  /** A series meaning (requested/used/…) or a money kind (saved/extra). */
  kind: SeriesKind | MoneyKind;
  /** True when `kind` is a money kind. */
  money?: boolean;
  /** Stack onto this id ('day' mode stacks by result; otherwise bars sit side by side). */
  stackId?: string;
  /** Fill with this hatch instead of a solid fill (same meaning, a different source). */
  hatch?: HatchKind;
}

export interface BarsProps {
  mode: BarsMode;
  data: Array<Record<string, number | string | null>>;
  bars: BarSeries[];
  xKey: string;
  height?: number;
  width?: number;
  ariaLabel?: string;
  /** 'capacity' rows: the width of the row labels (e.g. workload names), in px. */
  categoryWidth?: number;
  /** The value axis: linear (default) or log, on powers of ten. */
  scale?: 'linear' | 'log';
  /** Formats a value-axis tick (e.g. `v => `${v}m``). */
  tickFormat?: (value: number) => string;
}

interface BarStyle {
  fill: string;
  fillOpacity: number;
  stroke: string;
  strokeWidth: number;
  strokeDasharray?: string;
}

function styleFor(bar: BarSeries, suffix: string): BarStyle {
  if (bar.money) {
    const m = MONEY[bar.kind as MoneyKind];
    // "Costs more" is drawn as an outline (§2.1); saved is a solid fill.
    const extra = (bar.kind as MoneyKind) === 'extra';
    return {
      fill: m.color,
      fillOpacity: extra ? 0 : 1,
      stroke: m.color,
      strokeWidth: extra ? 1.5 : 0,
    };
  }
  const s = SERIES[bar.kind as SeriesKind];
  if (bar.hatch) {
    return { fill: `url(#${hatchId(HATCH[bar.hatch].id, suffix)})`, fillOpacity: 1, stroke: s.color, strokeWidth: 1.4, strokeDasharray: s.dash };
  }
  return { fill: s.color, fillOpacity: 1, stroke: s.color, strokeWidth: 0, strokeDasharray: s.dash };
}

/** Powers of ten from below the smallest positive value to above the largest; null with none. */
export function logDomain(values: ReadonlyArray<number | string | null | undefined>): { domain: [number, number]; ticks: number[] } | null {
  const positive = values.filter((v): v is number => typeof v === 'number' && Number.isFinite(v) && v > 0);
  if (positive.length === 0) return null;
  const lo = 10 ** Math.floor(Math.log10(Math.min(...positive)));
  const hi = 10 ** Math.ceil(Math.log10(Math.max(...positive)));
  const top = hi > lo ? hi : lo * 10;
  const ticks: number[] = [];
  for (let t = lo; t <= top * 1.0001; t *= 10) ticks.push(Number(t.toPrecision(12)));
  return { domain: [lo, top], ticks };
}

export function Bars({ mode, data, bars, xKey, height = 220, width, ariaLabel, categoryWidth = 90, scale = 'linear', tickFormat }: BarsProps) {
  const horizontal = mode === 'capacity';
  const log = scale === 'log';
  const keys = bars.map((b) => b.dataKey);
  // On a log axis a value that isn't above zero has no place: it is a gap, never a bar at the floor.
  const rows = log
    ? data.map((row) => {
      const next: Record<string, number | string | null> = { ...row };
      for (const k of keys) {
        const v = row[k];
        if (typeof v !== 'number' || !Number.isFinite(v) || v <= 0) next[k] = null;
      }
      return next;
    })
    : data;
  const logAxis = log ? logDomain(rows.flatMap((row) => keys.map((k) => row[k]))) : null;
  const valueAxis = logAxis
    ? { scale: 'log' as const, domain: logAxis.domain, ticks: logAxis.ticks, allowDataOverflow: true }
    : {};
  const tick = { fontSize: 11 };
  const formatter = tickFormat ? (v: number) => tickFormat(v) : undefined;
  const hatched = bars.some((b) => b.hatch);
  // This chart's own pattern ids, so a hatch never resolves to another chart's SVG.
  const suffix = chartIdSuffix(useId());

  const chart = (
    <BarChart
      data={rows}
      layout={horizontal ? 'vertical' : 'horizontal'}
      margin={{ top: 12, right: 12, bottom: 4, left: 4 }}
      barCategoryGap={mode === 'before-after' ? '20%' : '12%'}
      role="img"
      aria-label={ariaLabel}
      data-bars-mode={mode}
    >
      {hatched && (
        <defs>
          <ChartDefs suffix={suffix} />
        </defs>
      )}
      <CartesianGrid stroke="currentColor" strokeOpacity={0.12} vertical={horizontal} horizontal={!horizontal} />
      {horizontal ? (
        <>
          <XAxis type="number" stroke="currentColor" strokeOpacity={0.4} tick={tick} tickFormatter={formatter} {...valueAxis} />
          <YAxis type="category" dataKey={xKey} stroke="currentColor" strokeOpacity={0.4} tick={tick} width={categoryWidth} />
        </>
      ) : (
        <>
          <XAxis dataKey={xKey} stroke="currentColor" strokeOpacity={0.4} tick={tick} />
          <YAxis stroke="currentColor" strokeOpacity={0.4} tick={tick} width={tickFormat ? 48 : 36} tickFormatter={formatter} {...valueAxis} />
        </>
      )}
      <Tooltip isAnimationActive={false} />

      {bars.map((bar) => {
        const f = styleFor(bar, suffix);
        return (
          <Bar
            key={bar.dataKey}
            dataKey={bar.dataKey}
            name={bar.name}
            stackId={bar.stackId}
            fill={f.fill}
            fillOpacity={f.fillOpacity}
            stroke={f.stroke}
            strokeWidth={f.strokeWidth}
            strokeDasharray={f.strokeDasharray}
            isAnimationActive={false}
            radius={horizontal ? [0, 2, 2, 0] : [2, 2, 0, 0]}
          />
        );
      })}
    </BarChart>
  );

  if (width) {
    return (
      <div style={{ width, height }} data-bars="" data-bars-scale={logAxis ? 'log' : 'linear'}>
        <ResponsiveContainer width={width} height={height}>
          {chart}
        </ResponsiveContainer>
      </div>
    );
  }
  return (
    <div style={{ height }} data-bars="" data-bars-scale={logAxis ? 'log' : 'linear'}>
      <ResponsiveContainer width="100%" height={height}>
        {chart}
      </ResponsiveContainer>
    </div>
  );
}

export default Bars;
