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
