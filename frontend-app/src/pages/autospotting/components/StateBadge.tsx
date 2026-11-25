import React from 'react';
import { Icon } from '@iconify/react';
import type { GroupRow } from '../../../api/autospottingApi';
interface BadgeSpec {
  label: string;
  className: string;
  icon: string;
}
const EMERALD = 'border border-emerald-200 bg-emerald-50 text-emerald-700';
const INDIGO = 'border border-indigo-200 bg-indigo-50 text-indigo-700';
const AMBER = 'border border-amber-200 bg-amber-50 text-amber-800';
const RED = 'border border-red-200 bg-red-50 text-red-700';
const GREY = 'border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.6)]';
/**
 * State badges (design-ux-ops.md §4.2): tinted pills in the app palette (emerald good, amber warning,
 * red failure, indigo in progress). The label always states the state and a leading icon repeats it, so
 * meaning never depends on colour. Text is 700 on a 50 tint for AA contrast.
 */
export function badgeFor(row: Pick<GroupRow, 'state' | 'enableAllowed' | 'externalSpot'>): BadgeSpec {
  if (row.externalSpot)
    return { label: 'Spot, not managed by XamOps', className: `${GREY} border-dotted`, icon: 'mdi:link-variant-off' };
  switch (row.state) {
    case 'UNMANAGED':
      return row.enableAllowed
        ? { label: 'Eligible', className: INDIGO, icon: 'mdi:circle-outline' }
        : { label: 'Ineligible', className: `${GREY} border-dashed`, icon: 'mdi:minus-circle-outline' };
    case 'PLANNED':
      return { label: 'Plan open', className: 'border border-indigo-300 bg-white text-indigo-700', icon: 'mdi:clipboard-text-outline' };
    case 'APPLYING':
      return { label: 'Applying', className: INDIGO, icon: 'mdi:progress-clock' };
    case 'ACTIVE':
      return { label: 'Active', className: EMERALD, icon: 'mdi:check-circle' };
    case 'DEGRADED':
      return { label: 'Degraded', className: `${AMBER} font-bold`, icon: 'mdi:alert-outline' };
    case 'FALLBACK':
      return { label: 'On-Demand fallback', className: `${AMBER} font-bold`, icon: 'mdi:swap-horizontal' };
    case 'PAUSED':
      return { label: 'Paused', className: AMBER, icon: 'mdi:pause-circle-outline' };
    case 'REVERTING':
      return { label: 'Reverting', className: INDIGO, icon: 'mdi:undo-variant' };
    case 'REVERTED':
      return { label: 'Reverted', className: GREY, icon: 'mdi:undo-variant' };
    case 'FAILED':
      return { label: 'Needs attention', className: `${RED} font-bold`, icon: 'mdi:alert-circle' };
    case 'LEGACY_UNCONFIRMED':
      return { label: 'Converted before tracking', className: `${AMBER} border-dashed`, icon: 'mdi:help-circle-outline' };
    case 'GONE':
      return { label: 'Deleted', className: `${GREY} line-through`, icon: 'mdi:delete-outline' };
    default:
      return { label: row.state, className: GREY, icon: 'mdi:circle-outline' };
  }
}
export const StateBadge: React.FC<{ row: Pick<GroupRow, 'state' | 'enableAllowed' | 'externalSpot'> }> = ({ row }) => {
  const spec = badgeFor(row);
  const transient = row.state === 'APPLYING' || row.state === 'REVERTING';
  return (
    <span
      className={`inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-[11px] font-semibold ${spec.className}`}
    >
      {transient ? (
        <span aria-hidden="true" className="h-1.5 w-1.5 animate-pulse rounded-full bg-indigo-600" />
      ) : (
        <Icon icon={spec.icon} width={12} aria-hidden="true" />
      )}
      {spec.label}
    </span>
  );
};
export default StateBadge;
