import React, { useId, useState } from 'react';
import { Icon } from '@iconify/react';
import { CONSOLE_ONLY_TOOLTIP } from '../datastores/ui/ConsoleOnly';

const CARD_LABEL = 'text-[11px] font-semibold text-[rgba(0,0,0,0.5)]';

/**
 * One greyed card: its label and icon only, focusable, with the "Disabled by admin" tooltip on hover and
 * on focus. Escape while the tooltip shows hides it and is stopped there.
 */
const GreyedCard: React.FC<{ label: string; icon: string }> = ({ label, icon }) => {
  const tooltipId = useId();
  const [visible, setVisible] = useState(false);
  return (
    <div className="relative">
      <div
        role="group"
        aria-label={label}
        aria-disabled="true"
        aria-describedby={tooltipId}
        tabIndex={0}
        onMouseEnter={() => setVisible(true)}
        onMouseLeave={() => setVisible(false)}
        onFocus={() => setVisible(true)}
        onBlur={() => setVisible(false)}
        onKeyDown={e => {
          if (e.key === 'Escape' && visible) {
            e.stopPropagation();
            setVisible(false);
          }
        }}
        className="cursor-not-allowed rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] px-4 py-3 opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]"
      >
        <div className="flex items-center gap-1.5">
          <Icon icon={icon} width={16} className="text-[rgba(0,0,0,0.45)]" />
          <span className={CARD_LABEL}>{label}</span>
        </div>
        {/* The console's figure sits here; XamLens shows none (BUILD-BRIEF-5 §11, GAP-69). */}
        <div className="mt-1.5 h-7" aria-hidden="true" />
      </div>
      <span
        id={tooltipId}
        role="tooltip"
        hidden={!visible}
        className="pointer-events-none absolute left-1/2 top-full z-50 mt-1.5 -translate-x-1/2 whitespace-nowrap rounded-md bg-gray-900 px-2 py-1 text-[11px] font-normal text-white shadow-lg"
      >
        {CONSOLE_ONLY_TOOLTIP}
      </span>
    </div>
  );
};

/**
 * The analytics row: "Total Affected Resources" and "Open Misconfigurations" in the console's place and
 * layout, greyed out with no number and no severity boxes. The API has no project-wide totals; the
 * per-datastore aggregate gives other numbers than the console (GAP-69), so the page never shows them,
 * whatever the list response carries. The console's Resolved card, trend chart and Category chips have
 * no API data and are left out.
 */
const MisconfigurationAnalytics: React.FC = () => (
  <div className="grid gap-3 sm:grid-cols-2 xl:max-w-3xl">
    <GreyedCard label="Total Affected Resources" icon="mdi:database-outline" />
    <GreyedCard label="Open Misconfigurations" icon="mdi:shield-alert-outline" />
  </div>
);

export default MisconfigurationAnalytics;
