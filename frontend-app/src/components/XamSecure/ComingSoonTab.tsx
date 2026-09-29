import React from 'react';
import { Icon } from '@iconify/react';

/**
 * Placeholder for a tab that is announced but not yet built.
 *
 * <h3>Why announce at all rather than hide</h3>
 * A roadmapped capability and a capability that does not exist look identical when both are absent. A
 * customer who cannot see SaaS coverage cannot tell whether it is coming, and reports it as missing.
 * Listing it with a "Soon" pill answers the question before it is asked.
 *
 * <h3>Why it renders no data shell</h3>
 * No empty table, no zeroed counters, no skeleton rows. On a data-security product an empty table is not
 * a neutral placeholder — it reads as "we looked and found nothing", which is a claim about the
 * customer's estate rather than about our build progress. The same reasoning drives the "Not available"
 * cells in the Cloud table: never let an absence of scanning look like an absence of risk.
 */
interface Props {
  /** Tab name, used in the heading. */
  title: string;
  /** One sentence on what the tab will cover. */
  blurb?: string;
  icon?: string;
}

const ComingSoonTab: React.FC<Props> = ({ title, blurb, icon = 'mdi:progress-wrench' }) => (
  <div className="flex min-h-[24rem] items-center justify-center p-10">
    <div className="max-w-md text-center">
      <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-2xl bg-[rgba(0,0,0,0.04)]">
        <Icon icon={icon} width={24} className="text-[rgba(0,0,0,0.35)]" />
      </div>

      <div className="mt-4 flex items-center justify-center gap-2">
        <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{title}</h2>
        <span className="rounded-full bg-[rgba(0,0,0,0.06)] px-2 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
          Coming soon
        </span>
      </div>

      {blurb && (
        <p className="mt-2 text-xs leading-relaxed text-[rgba(0,0,0,0.5)]">{blurb}</p>
      )}

      <p className="mt-3 text-[11px] text-[rgba(0,0,0,0.35)]">
        This view is not live yet. Nothing here reflects your environment.
      </p>
    </div>
  </div>
);

export default ComingSoonTab;
