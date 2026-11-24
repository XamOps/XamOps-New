import React, { useEffect, useRef } from 'react';
import { EscapeStack, useEscapeStack } from '../datastores/drawer/EscapeStack';

/**
 * The Data Lineage page's Escape stack (build 4 design 6.5). The page has no drawer to provide one, so the
 * investigation does, as a root drawer would: the full-screen modal, the event drawer, the More-filters
 * panel and the event popover register while open, and one Escape on `window` closes the newest of them.
 * With nothing registered, Escape does nothing.
 */
const LineageEscapeHost: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const noFallback = useRef<HTMLElement | null>(null);
  const stack = useEscapeStack(noFallback);
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => { if (e.key === 'Escape') stack.closeTopmost(); };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [stack]);
  return <EscapeStack stack={stack}>{children}</EscapeStack>;
};

export default LineageEscapeHost;
