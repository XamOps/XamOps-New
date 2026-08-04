import React from 'react';
import { Icon } from '@iconify/react';
import type { WarCategorySummary } from '../../types/war';
import { CARD, EYEBROW } from './warTokens';

interface Props {
  categories: WarCategorySummary[];
  activeCategory: string;
  onSelect: (category: string) => void;
}

/** Icon per advisory control family. */
const CATEGORY_ICONS: Record<string, string> = {
  'Identity & Access Management': 'mdi:account-key-outline',
  'Network Security': 'mdi:lan',
  'Data Protection': 'mdi:database-lock-outline',
  'Logging & Monitoring': 'mdi:text-search',
  'Compute Security': 'mdi:server-security',
  'Compliance & Governance': 'mdi:gavel',
  'AI Services Security': 'mdi:robot-outline',
  'Cryptocurrency Mining Threat Detection': 'mdi:pickaxe',
};

const scoreTone = (score: number): string => {
  if (score >= 85) return 'text-emerald-600';
  if (score >= 70) return 'text-amber-600';
  if (score >= 50) return 'text-orange-600';
  return 'text-red-600';
};

const barTone = (score: number): string => {
  if (score >= 85) return 'bg-emerald-500';
  if (score >= 70) return 'bg-amber-500';
  if (score >= 50) return 'bg-orange-500';
  return 'bg-red-500';
};

/**
 * Whether a family verified enough of its controls for the score to mean anything.
 *
 * Same 50% coverage gate the report uses overall. Without it, a family with one failure
 * and eleven blocked controls renders "0/100" — a number that describes our scan
 * coverage while looking like a verdict on the customer's network.
 */
const isCategoryAssessable = (category: WarCategorySummary): boolean => {
  const verified = category.passed + category.failed + category.warned;
  const scoreable = verified + category.unknown;
  return verified > 0 && verified * 2 >= scoreable;
};

/**
 * The advisory's own control families, as a secondary axis to the attack-path board.
 *
 * Auditors and remediation owners think in these terms ("who owns network security?"),
 * so the grouping stays available even though it is not what the page leads with.
 */
const WarCategoryStrip: React.FC<Props> = ({ categories, activeCategory, onSelect }) => {
  if (categories.length === 0) return null;

  return (
    <div className={`${CARD} p-4`}>
      <div className="flex items-center gap-3 mb-3">
        <span className={`${EYEBROW} whitespace-nowrap`}>Control families</span>
        <div className="h-px bg-[#e5e7eb] flex-1 min-w-[20px]" />
        <span className="text-[11px] text-[rgba(0,0,0,0.35)] whitespace-nowrap hidden sm:block">
          A dash means too few controls produced a verdict to score
        </span>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2.5">
        {categories.map((category) => {
          const isActive = activeCategory === category.category;
          // Unverified counts as open — see openCountOf in WarAttackPathBoard for why
          // the card and the list it filters must agree.
          const open = category.failed + category.warned + category.unknown;
          const assessable = isCategoryAssessable(category);

          return (
            <button
              key={category.category}
              onClick={() => onSelect(isActive ? 'All' : category.category)}
              aria-pressed={isActive}
              className={`text-left rounded-lg border p-3 transition-all ${
                isActive
                  ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.02)]'
                  : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.2)] hover:shadow-sm'
              }`}
            >
              <div className="flex items-start gap-1.5">
                <Icon
                  icon={CATEGORY_ICONS[category.category] ?? 'mdi:shield-outline'}
                  width={14}
                  className="text-[rgba(0,0,0,0.5)] shrink-0 mt-0.5"
                />
                <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] leading-snug">
                  {category.category}
                </span>
              </div>

              <div className="mt-2.5 flex items-end justify-between">
                <span
                  className={`text-[20px] font-bold leading-none tabular-nums ${
                    assessable ? scoreTone(category.score) : 'text-[rgba(0,0,0,0.25)]'
                  }`}
                >
                  {assessable ? category.score : '—'}
                  <span className="text-[10.5px] font-medium text-[rgba(0,0,0,0.3)]">/100</span>
                </span>
                <span className="text-[10.5px] text-[rgba(0,0,0,0.4)] tabular-nums">
                  {open > 0 ? `${open} open` : 'all clear'}
                </span>
              </div>

              <div className="mt-2 h-1 w-full bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                {assessable ? (
                  <div
                    className={`h-full ${barTone(category.score)}`}
                    style={{ width: `${category.score}%` }}
                  />
                ) : (
                  // Nothing to draw. A 0%-width bar and an unscoreable family look the
                  // same, so leave the track empty rather than implying a measurement.
                  <div className="h-full" />
                )}
              </div>

              <div className="mt-2 flex items-center gap-2 text-[10px] text-[rgba(0,0,0,0.35)] flex-wrap tabular-nums">
                <span className="text-emerald-600 font-medium">{category.passed} pass</span>
                {category.failed > 0 && (
                  <span className="text-red-600 font-medium">{category.failed} fail</span>
                )}
                {category.warned > 0 && (
                  <span className="text-amber-600 font-medium">{category.warned} warn</span>
                )}
                {category.unknown > 0 && <span>{category.unknown} unverified</span>}
                {category.manual > 0 && <span>{category.manual} manual</span>}
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default WarCategoryStrip;
