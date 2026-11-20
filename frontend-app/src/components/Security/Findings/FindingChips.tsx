import React from 'react';
import { categoryAccent } from '../../Dspm/dspmFormat';
import { resolveAwsServiceIcon } from '../../../utils/awsServiceIcons';

const NEUTRAL = categoryAccent(null);

/** A grey chip with the XamLens chip's square marker, for a framework or a region. */
export const NeutralChip: React.FC<{ label: string }> = ({ label }) => (
  <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded border px-1.5 py-0.5 text-[10px] font-semibold ${NEUTRAL.chip}`}>
    <span className={`h-1.5 w-1.5 rounded-sm ${NEUTRAL.bar}`} />
    {label}
  </span>
);

export const Dash: React.FC = () => <span className="text-xs text-[rgba(0,0,0,0.4)]">-</span>;

/** The first `shown` values as chips, then "+N" naming the rest in its title; "-" for none. */
export const ChipList: React.FC<{ values: readonly string[]; shown?: number }> = ({ values, shown = 1 }) => {
  if (values.length === 0) return <Dash />;
  const rest = values.slice(shown);
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      {values.slice(0, shown).map(value => <NeutralChip key={value} label={value} />)}
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

/**
 * The AWS service mark of a finding's category (IAM, S3, VPC, CloudTrail…), decorative: the category
 * is written beside it. An asset that fails to load is hidden rather than drawn broken.
 */
export const CategoryIcon: React.FC<{ category: string | null; size: number }> = ({ category, size }) => (
  <img
    src={resolveAwsServiceIcon(category)}
    alt=""
    aria-hidden="true"
    width={size}
    height={size}
    className="flex-shrink-0 object-contain"
    style={{ width: size, height: size }}
    loading="lazy"
    onError={e => { e.currentTarget.style.visibility = 'hidden'; }}
  />
);
