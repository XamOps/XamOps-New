/**
 * The SVG `<pattern>` definitions a chart declares once and references as `url(#id)` (design-delta
 * §2.4, §3.3, §2.7). They are patterns, not gradients, so they pass the design-rule guard. Each
 * hatch is drawn in a series/band stroke class from chartTokens, so a hatched span still carries one
 * meaning. Render this inside a recharts `<defs>` (via the chart's `defs` slot) or at the top of a
 * plain-SVG primitive.
 */
import { HATCH } from './chartTokens';

/** The four hatches, declared together. Deterministic: the same markup every render. */
export function ChartDefs() {
  return (
    <>
      {Object.values(HATCH).map((h) => (
        <pattern key={h.id} id={h.id} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <line x1="0" y1="0" x2="0" y2="6" stroke={h.stroke} strokeWidth="1" />
        </pattern>
      ))}
    </>
  );
}

export default ChartDefs;
