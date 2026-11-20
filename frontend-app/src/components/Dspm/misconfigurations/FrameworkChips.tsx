import React from 'react';
import { CategoryChip } from '../dspmShared';
import { Dash, MoreBadge, TEXT_PRIMARY } from './ConsoleParts';

/** At most this many framework chips show before "+ N", as in the console. */
const MAX_FRAMEWORKS = 2;
/** Characters two chips may hold together; past it only the first shows (the console fits chips to the cell). */
const FIT_CHARS = 22;

/** How many chips fit: one at least, two when both names are short. */
export function shownFrameworks(frameworks: readonly string[]): number {
  if (frameworks.length < MAX_FRAMEWORKS) return frameworks.length;
  return frameworks[0].length + frameworks[1].length <= FIT_CHARS ? MAX_FRAMEWORKS : 1;
}

/**
 * The Frameworks cell as the console draws it: grey chips, as many as fit (at most two), each truncated,
 * then a "+ N" naming the rest in its title; "-" for none. The route sends the vendor's framework names
 * (no short codes, and the console's bundle has no name-to-code table), so a long name is cut with an
 * ellipsis and given whole in the chip's title.
 */
const FrameworkChips: React.FC<{ frameworks: string[] }> = ({ frameworks }) => {
  if (frameworks.length === 0) return <Dash />;
  const shown = shownFrameworks(frameworks);
  const rest = frameworks.slice(shown);
  return (
    <span className="flex min-w-0 flex-nowrap items-center gap-2">
      {frameworks.slice(0, shown).map(framework => (
        <span
          key={framework}
          title={framework}
          className={`min-w-0 truncate rounded bg-[#f0f1f2] px-2 py-1 text-xs font-medium leading-4 ${TEXT_PRIMARY}`}
        >
          {framework}
        </span>
      ))}
      {rest.length > 0 && <MoreBadge hidden={rest} />}
    </span>
  );
};

export default FrameworkChips;

/** The Labels cell: the first label's chip, then "+ N" for the rest, as the console shows one; "-" for none. */
export const LabelList: React.FC<{ labels: string[] }> = ({ labels }) => {
  if (labels.length === 0) return <Dash />;
  return (
    <span className="flex min-w-0 flex-nowrap items-center gap-2">
      <CategoryChip label={labels[0]} />
      {labels.length > 1 && <MoreBadge hidden={labels.slice(1)} />}
    </span>
  );
};
