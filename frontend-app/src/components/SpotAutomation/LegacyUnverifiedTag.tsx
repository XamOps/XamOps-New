import React from 'react';

/** The money label for figures computed from legacy savings rows (FR-UI-05). */
export const LEGACY_UNVERIFIED_LABEL = 'Legacy (unverified)';

interface LegacyUnverifiedTagProps {
  className?: string;
}

/**
 * Small grey outlined tag placed beside a legacy amount. A legacy amount is never added into a
 * total, so the tag always sits next to its own figure.
 */
const LegacyUnverifiedTag: React.FC<LegacyUnverifiedTagProps> = ({ className = '' }) => (
  <span
    className={`inline-flex items-center whitespace-nowrap rounded border border-[rgba(0,0,0,0.2)] px-1.5 text-[10px] font-medium leading-4 text-[rgba(0,0,0,0.55)] ${className}`}
  >
    {LEGACY_UNVERIFIED_LABEL}
  </span>
);

export default LegacyUnverifiedTag;
