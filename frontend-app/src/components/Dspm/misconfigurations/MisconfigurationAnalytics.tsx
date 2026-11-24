import React from 'react';
import { Icon } from '@iconify/react';
import { CAPTION, ConsoleOnlyRegion, PANEL, TEXT_PRIMARY } from './ConsoleParts';

/** One of the three stacked cards: its caption (and icon) only, greyed, no figure and no severity boxes. */
const GreyedCard: React.FC<{ label: string; icon?: string }> = ({ label, icon }) => (
  <ConsoleOnlyRegion label={label}>
    <div className={`${PANEL} flex w-full flex-col gap-3 p-4`}>
      <span className={CAPTION}>{label}</span>
      {/* The console's figure (and severity boxes) sit here; XamLens shows none (BUILD-BRIEF-5 §11, §12, GAP-69). */}
      <div className="flex h-6 items-center gap-[5px]" aria-hidden="true">
        {icon && <Icon icon={icon} width={20} className="text-[#6b6e76]" />}
      </div>
    </div>
  </ConsoleOnlyRegion>
);

/** Empty dashed axes: five grid lines and the two axes, no tick values and no line. */
const EmptyAxes: React.FC = () => (
  <svg className="h-full w-full" viewBox="0 0 400 200" preserveAspectRatio="none" aria-hidden="true">
    {[0, 1, 2, 3, 4].map(i => (
      <line key={i} x1="0" x2="400" y1={8 + i * 46} y2={8 + i * 46} stroke="#e5e7eb" strokeDasharray="4 4" vectorEffect="non-scaling-stroke" />
    ))}
    <line x1="0" x2="0" y1="0" y2="192" stroke="#e5e7eb" strokeDasharray="4 4" vectorEffect="non-scaling-stroke" />
    <line x1="0" x2="400" y1="192" y2="192" stroke="#e5e7eb" strokeDasharray="4 4" vectorEffect="non-scaling-stroke" />
  </svg>
);

/**
 * The analytics row in the console's layout: a grey box holding, left to right, the three stacked cards
 * (Total Affected Resources, Open Misconfigurations, Resolved Misconfigurations), the "Open Misconfigured
 * Resources" chart card with its "Last 30 days" range, and the "Category" panel. On narrow screens they
 * stack, as the console's do.
 *
 * None has API data: the console reads them from `/risk/dashboard`, which answers a project key with
 * 401, and the per-datastore aggregate gives other numbers than the console (GAP-69). So each is greyed
 * with "Disabled by admin" and shows no number, no line and no chip, whatever the list response carries.
 */
const MisconfigurationAnalytics: React.FC = () => (
  <div className="flex flex-col items-start gap-5 rounded-[3px] border border-[#dddedd] bg-[#fbfbfb] p-5 lg:flex-row">
    <div className="flex w-full flex-col gap-3 lg:w-fit lg:min-w-[288px] lg:flex-shrink-0">
      <GreyedCard label="Total Affected Resources" icon="mdi:database-outline" />
      <GreyedCard label="Open Misconfigurations" />
      <GreyedCard label="Resolved Misconfigurations" />
    </div>

    <ConsoleOnlyRegion label="Open Misconfigured Resources" className="w-full lg:min-w-0 lg:flex-1">
      <div className={`${PANEL} flex h-72 w-full flex-col gap-4 p-5`}>
        <div className="flex items-center justify-between gap-3">
          <span className={CAPTION}>Open Misconfigured Resources</span>
          <span className={`inline-flex items-center gap-1.5 rounded-[4px] border border-[#dddedd] bg-[#f9f9f9] px-3 py-1 text-sm font-medium ${TEXT_PRIMARY}`}>
            <Icon icon="mdi:clock-outline" width={14} />
            Last 30 days
          </span>
        </div>
        <div className="min-h-0 flex-1 pb-1 pl-2">
          <EmptyAxes />
        </div>
      </div>
    </ConsoleOnlyRegion>

    <ConsoleOnlyRegion label="Category" className="w-full lg:min-w-[300px] lg:flex-1">
      <div className={`${PANEL} flex h-auto w-full flex-col gap-4 p-5 lg:h-72`}>
        <span className={CAPTION}>Category</span>
      </div>
    </ConsoleOnlyRegion>
  </div>
);

export default MisconfigurationAnalytics;
