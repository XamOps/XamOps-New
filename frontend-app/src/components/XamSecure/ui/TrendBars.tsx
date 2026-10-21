import React, { useState } from 'react';
import { usePrefersReducedMotion } from './usePrefersReducedMotion';

interface Datum {
  label: string;
  value: number;
}

interface Props {
  data: Datum[];
}

const TRACK_H = 84; // matches the original BarChart column height

/**
 * Findings-over-time bars: keeps the original flex-column structure and cumulative data, but
 * fills each bar with a subtle blue-500→blue-400 vertical gradient, rounds the tops, grows the
 * bars in from the baseline on mount, and shows a small hover tooltip card (label + value).
 *
 * The height-grow mount transition is a CSS transition gated behind prefers-reduced-motion: when
 * reduced, bars render at final height immediately.
 */
const TrendBars: React.FC<Props> = ({ data }) => {
  const reduced = usePrefersReducedMotion();
  const [hover, setHover] = useState<number | null>(null);
  // Start grown=false so the transition runs from 0 → target on first paint.
  const [grown, setGrown] = useState(reduced);

  React.useEffect(() => {
    if (reduced) { setGrown(true); return; }
    const r = requestAnimationFrame(() => setGrown(true));
    return () => cancelAnimationFrame(r);
  }, [reduced]);

  const max = Math.max(1, ...data.map((d) => d.value));

  return (
    <div className="flex items-end justify-between gap-1 h-28">
      {data.map((d, i) => {
        const targetH = Math.round((d.value / max) * TRACK_H);
        const h = grown ? Math.max(targetH, d.value > 0 ? 4 : 0) : 0;
        return (
          <div
            key={d.label}
            className="flex-1 flex flex-col items-center gap-1 relative"
            onMouseEnter={() => setHover(i)}
            onMouseLeave={() => setHover((cur) => (cur === i ? null : cur))}
          >
            {hover === i && (
              <div className="absolute -top-1 z-10 -translate-y-full rounded-lg border border-gray-200 bg-white shadow-md px-2.5 py-1.5 whitespace-nowrap pointer-events-none">
                <p className="text-[10px] font-medium text-gray-500">{d.label}</p>
                <p className="font-mono text-xs font-semibold text-gray-900 tabular-nums">
                  {d.value.toLocaleString()}
                </p>
              </div>
            )}
            <div className="w-full flex items-end justify-center" style={{ height: `${TRACK_H}px` }}>
              <div
                className="w-full max-w-[24px] rounded-t-md"
                style={{
                  height: `${h}px`,
                  background: 'linear-gradient(to bottom, #3b82f6, #60a5fa)',
                  transition: reduced ? undefined : 'height 650ms cubic-bezier(0.22,1,0.36,1)',
                  boxShadow: hover === i ? '0 2px 6px rgba(37,99,235,0.25)' : undefined,
                }}
              />
            </div>
            <span className="text-[9px] text-gray-400 whitespace-nowrap">{d.label}</span>
          </div>
        );
      })}
    </div>
  );
};

export default TrendBars;
