/**
 * A bar chart (recharts 3.8) in its four v3 modes (design-delta §2.5, §3):
 *   - 'grouped'      — grouped bars per x (e.g. CPU by namespace: requested / used / suggested);
 *   - 'before-after' — a before and an after bar per x (e.g. before/after a change, per node);
 *   - 'capacity'     — horizontal capacity rows (requested of allocatable per node);
 *   - 'day'          — one stacked/!grouped bar per IST day (changes per day, cost per day).
 *
 * Every bar sets its `fill` (and, for money "extra", a `stroke` outline, §2.1) explicitly from
 * chartTokens — never a recharts default. Deterministic and seek-safe: animation is off.
 */
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { MONEY, SERIES, type MoneyKind, type SeriesKind } from './chartTokens';

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
}

export interface BarsProps {
  mode: BarsMode;
  data: Array<Record<string, number | string | null>>;
  bars: BarSeries[];
  xKey: string;
  height?: number;
  width?: number;
  ariaLabel?: string;
}

function fillFor(bar: BarSeries): { fill: string; fillOpacity: number; stroke: string; strokeWidth: number } {
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
  return { fill: s.color, fillOpacity: 1, stroke: s.color, strokeWidth: 0 };
}

export function Bars({ mode, data, bars, xKey, height = 220, width, ariaLabel }: BarsProps) {
  const horizontal = mode === 'capacity';

  const chart = (
    <BarChart
      data={data}
      layout={horizontal ? 'vertical' : 'horizontal'}
      margin={{ top: 12, right: 12, bottom: 4, left: 4 }}
      barCategoryGap={mode === 'before-after' ? '20%' : '12%'}
      role="img"
      aria-label={ariaLabel}
      data-bars-mode={mode}
    >
      <CartesianGrid stroke="currentColor" strokeOpacity={0.12} vertical={horizontal} horizontal={!horizontal} />
      {horizontal ? (
        <>
          <XAxis type="number" stroke="currentColor" strokeOpacity={0.4} tick={{ fontSize: 11 }} />
          <YAxis type="category" dataKey={xKey} stroke="currentColor" strokeOpacity={0.4} tick={{ fontSize: 11 }} width={90} />
        </>
      ) : (
        <>
          <XAxis dataKey={xKey} stroke="currentColor" strokeOpacity={0.4} tick={{ fontSize: 11 }} />
          <YAxis stroke="currentColor" strokeOpacity={0.4} tick={{ fontSize: 11 }} width={36} />
        </>
      )}
      <Tooltip isAnimationActive={false} />

      {bars.map((bar) => {
        const f = fillFor(bar);
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
            isAnimationActive={false}
            radius={horizontal ? [0, 2, 2, 0] : [2, 2, 0, 0]}
          />
        );
      })}
    </BarChart>
  );

  if (width) {
    return (
      <div style={{ width, height }} data-bars="">
        <ResponsiveContainer width={width} height={height}>
          {chart}
        </ResponsiveContainer>
      </div>
    );
  }
  return (
    <div style={{ height }} data-bars="">
      <ResponsiveContainer width="100%" height={height}>
        {chart}
      </ResponsiveContainer>
    </div>
  );
}

export default Bars;
