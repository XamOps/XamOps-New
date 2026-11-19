import React from 'react';
import { Icon } from '@iconify/react';
import type { LineageCategory } from '../../../types/dspmLineage';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import { EmptyState } from '../datastores/ui/EmptyState';
import { isLineageCategoryId, type LineageCategoryId } from './lineageQuery';
import { NO_CATEGORIES_TEXT, PICKER_SUBTITLE, PICKER_TITLE, exposureCountText } from './lineageText';
import { CATEGORY_ICONS } from './lineageVisuals';

interface Props {
  categories: LineageCategory[];
  /** False while the exposure routes are switched off: every card is a greyed console-only control. */
  available: boolean;
  onChoose: (id: LineageCategoryId) => void;
}

const CARD = 'flex w-full flex-col items-start gap-3 rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-5 text-left transition-colors hover:border-[rgba(0,0,0,0.2)]';

const CardBody: React.FC<{ id: LineageCategoryId; name: string; count: number | null }> = ({ id, name, count }) => (
  <>
    <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.7)]">
      <Icon icon={CATEGORY_ICONS[id]} width={20} aria-hidden="true" />
    </span>
    <span className="text-sm font-semibold text-[rgba(0,0,0,0.85)]">{name}</span>
    {count !== null && <span className="text-xs text-[rgba(0,0,0,0.5)]">{exposureCountText(count)}</span>}
  </>
);

/**
 * Step 0 (build 4 design 6.4; FR-S0): the centred title and the category cards in a 3 × 2 grid. Switched
 * on, each card shows its count and opens step 1, also at 0 exposures (D4); switched off, each is greyed
 * with the "Disabled by admin" tooltip and no count.
 */
const LineageCategoryPicker: React.FC<Props> = ({ categories, available, onChoose }) => {
  const known = categories.filter(
    (category): category is LineageCategory & { id: LineageCategoryId } => isLineageCategoryId(category.id),
  );
  return (
    <div className="flex flex-col items-center py-12">
      <h1 className="text-xl font-bold text-[rgba(0,0,0,0.88)]">{PICKER_TITLE}</h1>
      <p className="mt-1 text-sm text-[rgba(0,0,0,0.5)]">{PICKER_SUBTITLE}</p>
      {available && known.length === 0 ? (
        <div className="mt-8 w-full max-w-3xl"><EmptyState title={NO_CATEGORIES_TEXT} /></div>
      ) : (
        <ul className="mt-8 grid w-full max-w-3xl grid-cols-1 gap-4 sm:grid-cols-3">
          {known.map(category => (
            <li key={category.id} className="flex">
              {available ? (
                <button type="button" onClick={() => onChoose(category.id)} className={CARD}>
                  <CardBody id={category.id} name={category.name} count={category.exposures} />
                </button>
              ) : (
                <ConsoleOnlyButton variant="card" placement="bottom">
                  <CardBody id={category.id} name={category.name} count={null} />
                </ConsoleOnlyButton>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};

export default LineageCategoryPicker;
