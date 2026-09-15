/**
 * Shared SVG geometry for the XamSecure funnel ribbons.
 *
 * Extracted because both funnels drew their edges with straight `L` segments between stage
 * points, which puts a corner at every stage boundary and a hard wedge wherever a stage drops
 * sharply. Keeping the curve in one place means a third funnel inherits the right shape instead
 * of repeating that.
 */

export type Point = readonly [number, number];

/**
 * Smooth edge through the given points.
 *
 * Cubic beziers with both control points on the midpoint x: the tangent at every stage is
 * horizontal, so the edge leaves and arrives level and the curve reads as a single flowing
 * profile rather than rounded-off corners. Returns the segment commands only, so the caller
 * controls the opening `M`.
 */
export function curveThrough(points: ReadonlyArray<Point>): string {
  return points.slice(1).reduce((acc, [x1, y1], idx) => {
    const [x0, y0] = points[idx];
    const cx = (x0 + x1) / 2;
    return `${acc} C ${cx} ${y0}, ${cx} ${y1}, ${x1} ${y1}`;
  }, '');
}

/**
 * Closed ribbon between an upper and a lower edge, both given left to right.
 *
 * The lower edge is reversed here so callers do not each have to remember to do it. Fewer than
 * two points cannot describe a band, so that yields an empty path rather than a stray dot.
 */
export function ribbonPath(upper: ReadonlyArray<Point>, lower: ReadonlyArray<Point>): string {
  if (upper.length < 2 || lower.length < 2) return '';
  const back = [...lower].reverse();
  return `M ${upper[0][0]} ${upper[0][1]}${curveThrough(upper)}`
    + ` L ${back[0][0]} ${back[0][1]}${curveThrough(back)} Z`;
}
