/**
 * Animated count-up hook (DESIGN.md §4.2), extracted from `CostKPICards`.
 *
 * Kept as-is from the source: the 900 ms cubic ease-out requestAnimationFrame
 * loop, cancel-on-unmount cleanup, the `target === prevRef.current` no-op, and
 * the `target === 0` instant-snap early return.
 *
 * Added here (new behaviour the source hook lacked): a
 * `prefers-reduced-motion: reduce` branch that snaps to the target once and
 * skips the rAF loop entirely.
 *
 * The hook owns NO null handling — "never animate a null toward 0" is a
 * call-site guard (feed the hook a number only when
 * `value !== null && Number.isFinite(value)`).
 */
import { useEffect, useRef, useState } from 'react';

export function useAnimatedValue(target: number) {
  const animRef = useRef<number | undefined>(undefined);
  const prevRef = useRef(target);
  const [value, setValue] = useState(target);

  useEffect(() => {
    if (target === prevRef.current) return;
    const prev = prevRef.current;
    prevRef.current = target;
    if (animRef.current) cancelAnimationFrame(animRef.current);
    if (target === 0) { setValue(0); return; }
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) { setValue(target); return; }
    const duration = 900;
    const startTime = performance.now();
    const step = (now: number) => {
      const t = Math.min((now - startTime) / duration, 1);
      const eased = 1 - Math.pow(1 - t, 3);
      setValue(prev + eased * (target - prev));
      if (t < 1) animRef.current = requestAnimationFrame(step);
      else setValue(target);
    };
    animRef.current = requestAnimationFrame(step);
    return () => { if (animRef.current) cancelAnimationFrame(animRef.current); };
  }, [target]);

  return value;
}

export default useAnimatedValue;
