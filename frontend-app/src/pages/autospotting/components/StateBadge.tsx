import React from 'react';
import type { GroupRow } from '../../../api/autospottingApi';

interface BadgeSpec {
  label: string;
  className: string;
}

/**
 * State badges (design-ux-ops.md §4.2): black, white and grey only. The label always states the
 * state, so meaning never depends on shape or shade.
 */
export function badgeFor(row: Pick<GroupRow, 'state' | 'enableAllowed' | 'externalSpot'>): BadgeSpec {
  if (row.externalSpot) return { label: 'Spot, not managed by XamOps', className: 'border border-dotted border-gray-600 text-gray-600' };
  switch (row.state) {
    case 'UNMANAGED':
      return row.enableAllowed
        ? { label: 'Eligible', className: 'border border-gray-400 text-gray-600' }
        : { label: 'Ineligible', className: 'border border-dashed border-gray-400 text-gray-400' };
    case 'PLANNED':
      return { label: 'Plan open', className: 'border border-gray-400 text-black' };
    case 'APPLYING':
      return { label: 'Applying', className: 'border border-black bg-black text-white' };
    case 'ACTIVE':
      return { label: 'Active', className: 'border border-black bg-black text-white' };
    case 'DEGRADED':
      return { label: 'Degraded', className: 'border-2 border-black bg-white font-bold text-black' };
    case 'FALLBACK':
      return { label: 'On-Demand fallback', className: 'border-2 border-black bg-white font-bold text-black' };
    case 'PAUSED':
      return { label: 'Paused', className: 'border border-gray-200 bg-gray-200 text-black' };
    case 'REVERTING':
      return { label: 'Reverting', className: 'border border-black bg-black text-white' };
    case 'REVERTED':
      return { label: 'Reverted', className: 'border border-gray-100 bg-gray-100 text-gray-600' };
    case 'FAILED':
      return { label: 'Needs attention', className: 'border border-black bg-black font-bold text-white' };
    case 'LEGACY_UNCONFIRMED':
      return { label: 'Converted before tracking', className: 'border border-dashed border-black text-black' };
    case 'GONE':
      return { label: 'Deleted', className: 'text-gray-400 line-through' };
    default:
      return { label: row.state, className: 'border border-gray-400 text-gray-600' };
  }
}

export const StateBadge: React.FC<{ row: Pick<GroupRow, 'state' | 'enableAllowed' | 'externalSpot'> }> = ({ row }) => {
  const spec = badgeFor(row);
  const transient = row.state === 'APPLYING' || row.state === 'REVERTING';
  return (
    <span className="inline-flex flex-col">
      <span className={`inline-block rounded px-2 py-0.5 text-xs ${spec.className}`}>
        {row.state === 'FAILED' ? <span aria-hidden="true" className="mr-1 inline-block h-2 w-2 bg-white" /> : null}
        {spec.label}
      </span>
      {transient && <span aria-hidden="true" className="mt-0.5 h-0.5 w-full animate-pulse bg-black" />}
    </span>
  );
};

export default StateBadge;
