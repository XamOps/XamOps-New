import React from 'react';
import { Icon } from '@iconify/react';
import { CategoryChip } from '../../dspmShared';
import { categoryAccent } from '../../dspmFormat';
import { riskVisual } from '../datastoreVisuals';

export { CategoryChip };

/**
 * A sensitive entity as the console prints it: a colour bar for its category, then the name in upper
 * case. The vendor's blank entity renders as an empty grey chip.
 */
export const EntityChip: React.FC<{ name: string; categoryLabel?: string | null }> = ({ name, categoryLabel }) => {
  if (name.trim() === '') {
    return (
      <span
        aria-label="Unnamed entity"
        className="inline-block h-5 w-12 rounded border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.05)]"
      />
    );
  }
  const accent = categoryAccent(categoryLabel ?? null);
  return (
    <span className="inline-flex max-w-full items-stretch overflow-hidden rounded border border-[rgba(0,0,0,0.1)] bg-white">
      <span className={`w-1 flex-shrink-0 ${accent.bar}`} />
      <span className="truncate px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.7)]" title={name}>
        {name}
      </span>
    </span>
  );
};

/**
 * The neutral `CategoryChip` with a round dot of the given colour class (`dot`, e.g. `bg-sky-400`), for
 * File View's Category and Document Class.
 */
export const DotChip: React.FC<{ label: string; dot: string }> = ({ label, dot }) => (
  <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded border px-1.5 py-0.5 text-[10px] font-semibold ${categoryAccent(null).chip}`}>
    <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${dot}`} />
    {label}
  </span>
);

/**
 * The neutral `CategoryChip` with a coloured left border (`bar`, a full `border-l-[…]` colour class), for
 * the Files page's Document Class.
 */
export const BarChip: React.FC<{ label: string; bar: string }> = ({ label, bar }) => (
  <span
    title={label}
    className={`inline-block max-w-full truncate whitespace-nowrap rounded border border-l-[3px] px-1.5 py-0.5 align-middle text-[10px] font-semibold ${categoryAccent(null).chip} ${bar}`}
  >
    {label}
  </span>
);

/** The first category chip, then "+N" for the rest (named in the badge's title). */
export const LabelChips: React.FC<{ labels: string[] }> = ({ labels }) => {
  if (labels.length === 0) return null;
  const rest = labels.slice(1);
  return (
    <span className="inline-flex items-center gap-1">
      <CategoryChip label={labels[0]} />
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

/** A risk or severity level: its chevron icon and label, or "-" when there is no level. */
export const LevelLabel: React.FC<{ level: string | null; label: string | null }> = ({ level, label }) => {
  if (!level) return <span className="text-xs text-[rgba(0,0,0,0.4)]">-</span>;
  const visual = riskVisual(level);
  return (
    <span className="inline-flex items-center gap-1 text-xs text-[rgba(0,0,0,0.75)]">
      <Icon icon={visual.icon} width={14} className={visual.cls} />
      {label ?? level}
    </span>
  );
};
