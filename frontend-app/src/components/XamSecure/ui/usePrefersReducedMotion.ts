import { useEffect, useState } from 'react';

/**
 * Tracks the `prefers-reduced-motion: reduce` user setting.
 *
 * Returns true when the user has asked the OS to reduce motion. Animations on the Overview
 * page read this to show their final state immediately instead of running. SSR-safe: assumes
 * motion is allowed until the media query can be read in the browser.
 */
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);

  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    setReduced(mq.matches);
    const onChange = (e: MediaQueryListEvent) => setReduced(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  return reduced;
}
