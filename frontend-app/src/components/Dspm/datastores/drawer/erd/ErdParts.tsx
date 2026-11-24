import React from 'react';

/*
 * Pieces the diagram's cards and its legend share. This module loads no layout code, so the legend can
 * use it before the lazily loaded canvas arrives.
 */

/** A connector's colour, and the colour of the hovered or focused card's connectors. */
export const ERD_EDGE = 'rgba(0,0,0,0.25)';
export const ERD_EDGE_ACTIVE = 'rgba(0,0,0,0.75)';
/** The dash of a connector to a table outside the diagram. */
export const ERD_DASH = '6 4';

/** The orange "PK" badge of a primary-key column, or the blue "FK" badge of a foreign-key column. */
export const KeyBadge: React.FC<{ kind: 'PK' | 'FK' }> = ({ kind }) => (
  <span
    className={`inline-flex h-4 min-w-[1.5rem] flex-shrink-0 items-center justify-center rounded px-1 text-[9px] font-bold tracking-wide ${
      kind === 'PK' ? 'bg-orange-100 text-orange-700' : 'bg-sky-100 text-sky-700'
    }`}
  >
    {kind}
  </span>
);

/** The red dot of a sensitives count. */
export const SensitiveDot: React.FC = () => (
  <span aria-hidden="true" className="inline-block h-1.5 w-1.5 flex-shrink-0 rounded-full bg-rose-500" />
);
