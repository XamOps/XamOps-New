/** Largest-Triangle-Three-Buckets downsampling. Points are [x, y]; null y values are dropped first. */
export type Pt = [number, number];

export function lttb(data: Pt[], threshold: number): Pt[] {
  const n = data.length;
  if (threshold >= n || threshold < 3) return data.slice();
  const out: Pt[] = [data[0]];
  const every = (n - 2) / (threshold - 2);
  let a = 0;
  for (let i = 0; i < threshold - 2; i += 1) {
    const rangeStart = Math.floor((i + 1) * every) + 1;
    const rangeEnd = Math.min(Math.floor((i + 2) * every) + 1, n);
    let avgX = 0;
    let avgY = 0;
    const avgLen = Math.max(1, rangeEnd - rangeStart);
    for (let j = rangeStart; j < rangeEnd; j += 1) { avgX += data[j][0]; avgY += data[j][1]; }
    avgX /= avgLen;
    avgY /= avgLen;
    const bStart = Math.floor(i * every) + 1;
    const bEnd = Math.floor((i + 1) * every) + 1;
    let maxArea = -1;
    let next = bStart;
    for (let j = bStart; j < bEnd; j += 1) {
      const area = Math.abs((data[a][0] - avgX) * (data[j][1] - data[a][1]) - (data[a][0] - data[j][0]) * (avgY - data[a][1]));
      if (area > maxArea) { maxArea = area; next = j; }
    }
    out.push(data[next]);
    a = next;
  }
  out.push(data[n - 1]);
  return out;
}

/** Drops null/NaN points then downsamples to at most `max` points. */
export function prepareSeries(points: [number, number | null][], max: number): Pt[] {
  const clean: Pt[] = [];
  for (const [x, y] of points) {
    if (y != null && Number.isFinite(y)) clean.push([x, y]);
  }
  return lttb(clean, max);
}

/** How many sample intervals may pass before the line is broken: one or two missing points are not yet a gap. */
export const GAP_FACTOR = 2.5;

/** Median spacing between consecutive x values (0 when there are fewer than two distinct ones). */
export function typicalSpacing(xs: number[]): number {
  const d: number[] = [];
  for (let i = 1; i < xs.length; i += 1) {
    const s = xs[i] - xs[i - 1];
    if (s > 0) d.push(s);
  }
  if (d.length === 0) return 0;
  d.sort((a, b) => a - b);
  const mid = Math.floor(d.length / 2);
  return d.length % 2 ? d[mid] : (d[mid - 1] + d[mid]) / 2;
}

export interface Segments {
  /** Runs of real points. A line is never drawn from one run to the next. */
  segments: Pt[][];
  /** Spacing above which two neighbouring points are treated as having missing data between them. */
  gapMs: number;
}

/**
 * Splits a series into runs of real data. A run ends at a null/NaN value or where the next point is more than
 * `gapMs` away (default: {@link GAP_FACTOR} times the series' median spacing), so an agent that was offline for
 * twenty minutes shows as a break, never as a straight line of invented values.
 */
export function segmentSeries(points: [number, number | null][], gapMs?: number): Segments {
  const sorted = points.slice().sort((a, b) => a[0] - b[0]);
  const spacing = typicalSpacing(sorted.map((p) => p[0]));
  const limit = gapMs ?? (spacing > 0 ? spacing * GAP_FACTOR : Infinity);
  const segments: Pt[][] = [];
  let cur: Pt[] = [];
  for (const [x, y] of sorted) {
    if (y == null || !Number.isFinite(y)) {
      if (cur.length) segments.push(cur);
      cur = [];
      continue;
    }
    if (cur.length && x - cur[cur.length - 1][0] > limit) {
      segments.push(cur);
      cur = [];
    }
    cur.push([x, y]);
  }
  if (cur.length) segments.push(cur);
  return { segments, gapMs: limit };
}

/** {@link segmentSeries}, then LTTB inside each run with a point budget shared in proportion to run length. */
export function prepareSegments(points: [number, number | null][], max: number, gapMs?: number): Segments {
  const s = segmentSeries(points, gapMs);
  const total = s.segments.reduce((n, seg) => n + seg.length, 0);
  if (total <= max) return s;
  return { gapMs: s.gapMs, segments: s.segments.map((seg) => lttb(seg, Math.max(3, Math.round((max * seg.length) / total)))) };
}

/**
 * Time ranges inside [from, to] where no series has data: between runs, before the first point and after the last
 * one. Each point covers half the gap limit on either side, so ordinary sample spacing never shows as missing data.
 */
export function noDataRanges(all: Segments[], from: number, to: number): [number, number][] {
  const covered: [number, number][] = [];
  for (const { segments, gapMs } of all) {
    const half = Number.isFinite(gapMs) ? gapMs / 2 : Infinity;
    for (const seg of segments) {
      if (seg.length) covered.push([seg[0][0] - half, seg[seg.length - 1][0] + half]);
    }
  }
  if (covered.length === 0) return [];
  covered.sort((a, b) => a[0] - b[0]);
  const out: [number, number][] = [];
  let cursor = from;
  for (const [a, b] of covered) {
    if (a > cursor) out.push([cursor, Math.min(a, to)]);
    cursor = Math.max(cursor, b);
    if (cursor >= to) break;
  }
  if (cursor < to) out.push([cursor, to]);
  return out.filter(([a, b]) => b > a);
}
