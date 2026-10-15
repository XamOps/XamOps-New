/**
 * The SVG `<pattern>` definitions a chart declares once and references as `url(#id)` (design-delta
 * §2.4, §3.3, §2.7). They are patterns, not gradients, so they pass the design-rule guard. Each
 * hatch is drawn in a series/band stroke class from chartTokens, so a hatched span still carries one
 * meaning. Render this inside a recharts `<defs>` (via the chart's `defs` slot) or at the top of a
 * plain-SVG primitive.
 *
 * `suffix` makes the ids unique to one chart (`k8s-hatch-suggested-<suffix>`), so two charts on a
 * page never share an id and each `url(#…)` resolves inside its own SVG.
 */
import { HATCH } from './chartTokens';

/** The id of a hatch pattern for one chart (`suffix` from {@link chartIdSuffix}). */
export const hatchId = (id: string, suffix = ''): string => (suffix ? `${id}-${suffix}` : id);

/** A React `useId()` value as an SVG-id-safe suffix ("«r1»" → "r1"). */
export const chartIdSuffix = (reactId: string): string => reactId.replace(/[^a-zA-Z0-9_-]/g, '');

/** The four hatches, declared together. Deterministic: the same markup every render. */
export function ChartDefs({ suffix = '' }: { suffix?: string }) {
  return (
    <>
      {Object.values(HATCH).map((h) => (
        <pattern key={h.id} id={hatchId(h.id, suffix)} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <line x1="0" y1="0" x2="0" y2="6" stroke={h.stroke} strokeWidth="1" />
        </pattern>
      ))}
    </>
  );
}

export default ChartDefs;
