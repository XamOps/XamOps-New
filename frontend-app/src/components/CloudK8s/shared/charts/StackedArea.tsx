/**
 * A stacked-area chart whose layers sum to a stated total (design-delta §3.2 "Stacks"): allocatable,
 * provisioned or node cost. The total is stated in the subline by the caller (ChartCard). Category
 * shades use mixes of ONE meaning token (§2.2 "Category shades") — application namespaces are the
 * requested hue at 100 / 72 / 46 / 28%, system namespaces are the capacity hue at 70% — so "blue"
 * still means reserved and adjacent layers differ only in lightness, never in hue.
 *
 * Every layer sets stroke and fill explicitly from chartTokens, so no recharts default colour is
 * emitted. Deterministic and seek-safe: animation is off.
 */
import { Area, AreaChart, CartesianGrid, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ChartDefs } from './ChartDefs';
import { SERIES, type SeriesKind } from './chartTokens';

export interface StackedLayer {
  dataKey: string;
  name: string;
  /** The meaning token this layer's shade is a mix of (§2.2). */
  kind: SeriesKind;
  /** The shade: a fill opacity in (0,1]. Category shades pass 1 / 0.72 / 0.46 / 0.28, system 0.7. */
  shade?: number;
}

export interface StackedAreaProps {
  data: Array<Record<string, number | string | null>>;
  layers: StackedLayer[];
  xKey: string;
  /** A reference line for the stated total (e.g. provisioned/allocatable), drawn in its token. */
  total?: { dataKey: string; name: string; kind: SeriesKind };
  height?: number;
  width?: number;
  ariaLabel?: string;
}

const STACK_ID = 'k8s-stack';

export function StackedArea({ data, layers, xKey, total, height = 220, width, ariaLabel }: StackedAreaProps) {
  const chart = (
    <AreaChart data={data} margin={{ top: 12, right: 12, bottom: 4, left: 4 }} role="img" aria-label={ariaLabel}>
      <defs>
        <ChartDefs />
      </defs>
      <CartesianGrid stroke="currentColor" strokeOpacity={0.12} vertical={false} />
      <XAxis dataKey={xKey} stroke="currentColor" strokeOpacity={0.4} tick={{ fontSize: 11 }} />
      <YAxis stroke="currentColor" strokeOpacity={0.4} tick={{ fontSize: 11 }} width={36} />
      <Tooltip isAnimationActive={false} />

      {layers.map((layer) => {
        const style = SERIES[layer.kind];
        return (
          <Area
            key={layer.dataKey}
            type="monotone"
            dataKey={layer.dataKey}
            name={layer.name}
            stackId={STACK_ID}
            stroke={style.color}
            strokeWidth={1}
            fill={style.color}
            fillOpacity={layer.shade ?? 1}
            isAnimationActive={false}
            dot={false}
            connectNulls={false}
          />
        );
      })}

      {total && (
        <Line
          type="monotone"
          dataKey={total.dataKey}
          name={total.name}
          stroke={SERIES[total.kind].color}
          strokeWidth={SERIES[total.kind].strokeWidth}
          strokeDasharray={SERIES[total.kind].dash}
          isAnimationActive={false}
          dot={false}
        />
      )}
    </AreaChart>
  );

  if (width) {
    return (
      <div style={{ width, height }} data-stacked-area="">
        <ResponsiveContainer width={width} height={height}>
          {chart}
        </ResponsiveContainer>
      </div>
    );
  }
  return (
    <div style={{ height }} data-stacked-area="">
      <ResponsiveContainer width="100%" height={height}>
        {chart}
      </ResponsiveContainer>
    </div>
  );
}

export default StackedArea;
