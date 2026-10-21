import React from 'react';
import { motion, useReducedMotion } from 'framer-motion';

interface Props {
  children: React.ReactNode;
  className?: string;
  /** Stagger hint so a row of cards can reveal slightly offset. */
  delay?: number;
}

/** whileInView needs IntersectionObserver; absent in some test/SSR environments. */
const hasIntersectionObserver =
  typeof window !== 'undefined' && typeof window.IntersectionObserver !== 'undefined';

/**
 * Fades + slides content up 8px the first time it scrolls into view.
 *
 * framer-motion's `useReducedMotion` collapses the animation to its final state when the user
 * prefers reduced motion, so this is a no-op (static) in that case. Also falls back to the
 * static final state where IntersectionObserver is unavailable (e.g. jsdom), since `whileInView`
 * relies on it. Kept as a thin wrapper so the existing grid layout is unchanged — it only wraps
 * a cell's content.
 */
const RevealCard: React.FC<Props> = ({ children, className, delay = 0 }) => {
  const reduced = useReducedMotion();

  if (reduced || !hasIntersectionObserver) {
    return <div className={className}>{children}</div>;
  }

  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y: 8 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, amount: 0.2 }}
      transition={{ duration: 0.45, ease: 'easeOut', delay }}
    >
      {children}
    </motion.div>
  );
};

export default RevealCard;
