import React, { useEffect, useRef, useState } from 'react';
import { usePrefersReducedMotion } from './usePrefersReducedMotion';

interface Props {
  /** Final integer value to count toward. */
  value: number;
  /** Animation length in ms. */
  durationMs?: number;
  className?: string;
  style?: React.CSSProperties;
}

/**
 * Animates an integer from 0 up to `value` on mount with an ease-out curve.
 *
 * Under prefers-reduced-motion it renders the final value immediately with no animation.
 * Formatting matches the rest of the page (locale-grouped integers).
 */
const CountUp: React.FC<Props> = ({ value, durationMs = 900, className, style }) => {
  const reduced = usePrefersReducedMotion();
  const [display, setDisplay] = useState(reduced ? value : 0);
  const frame = useRef<number | null>(null);

  useEffect(() => {
    if (reduced) { setDisplay(value); return; }
    const start = performance.now();
    const from = 0;
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / durationMs);
      const eased = 1 - Math.pow(1 - t, 3); // ease-out cubic
      setDisplay(Math.round(from + (value - from) * eased));
      if (t < 1) frame.current = requestAnimationFrame(tick);
    };
    frame.current = requestAnimationFrame(tick);
    return () => { if (frame.current != null) cancelAnimationFrame(frame.current); };
  }, [value, durationMs, reduced]);

  return <span className={className} style={style}>{display.toLocaleString()}</span>;
};

export default CountUp;
