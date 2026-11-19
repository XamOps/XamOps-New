import React from 'react';
import { CategoryChip } from '../dspmShared';

/** How many framework chips show before "+N". */
const SHOWN = 2;

/** The first two frameworks as grey chips, then "+N" naming the rest in its title; "-" for none. */
const FrameworkChips: React.FC<{ frameworks: string[] }> = ({ frameworks }) => {
  if (frameworks.length === 0) return <span className="text-xs text-[rgba(0,0,0,0.4)]">-</span>;
  const rest = frameworks.slice(SHOWN);
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      {frameworks.slice(0, SHOWN).map(framework => <CategoryChip key={framework} label={framework} neutral />)}
      {rest.length > 0 && (
        <span
          title={rest.join(', ')}
          className="rounded bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[10px] font-semibold tabular-nums text-[rgba(0,0,0,0.55)]"
        >
          +{rest.length}
        </span>
      )}
    </span>
  );
};

export default FrameworkChips;
