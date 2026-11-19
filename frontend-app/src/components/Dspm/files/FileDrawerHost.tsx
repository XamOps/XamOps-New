import React, { useEffect, useRef } from 'react';
import { EscapeStack, useEscapeStack } from '../datastores/drawer/EscapeStack';

/**
 * Gives a nested file drawer opened from a page what a parent drawer gives it: an Escape stack and its
 * listener. The drawer registers through `useSidePanel`, and popovers inside it register above it, so each
 * Escape closes the newest layer and focus returns to its opener. With nothing registered, Escape does
 * nothing. Mounted only while a drawer is open.
 */
const FileDrawerHost: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const noFallback = useRef<HTMLElement | null>(null);
  const stack = useEscapeStack(noFallback);
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => { if (e.key === 'Escape') stack.closeTopmost(); };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [stack]);
  return <EscapeStack stack={stack}>{children}</EscapeStack>;
};

export default FileDrawerHost;
