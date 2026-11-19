import React, { useId, useState } from 'react';
import { Icon } from '@iconify/react';
import { NestedDrawerShell } from '../datastores/drawer/DrawerShell';
import type { LineageEventView } from '../datastores/saas/lineageModel';
import { formatEventTime } from './lineageText';

interface Props {
  event: LineageEventView;
  /** The event card that opened the drawer; focused again when it closes. */
  returnFocus: HTMLElement | null;
  /** Above the full-screen modal. */
  elevated: boolean;
  /** Must be stable (`useCallback`), as `NestedDrawerShell` requires. */
  onClose: () => void;
}

/** A collapsible section: a header button with `aria-expanded` and the region it controls. */
const DrawerSection: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => {
  const [open, setOpen] = useState(true);
  const region = useId();
  return (
    <section className="rounded-xl border border-[rgba(0,0,0,0.08)]">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={region}
        onClick={() => setOpen(o => !o)}
        className="flex w-full items-center justify-between px-4 py-3 text-left text-sm font-bold text-[rgba(0,0,0,0.85)]"
      >
        {title}
        <Icon icon={open ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={16} aria-hidden="true" />
      </button>
      <div id={region} role="region" aria-label={title} hidden={!open} className="px-4 pb-4">
        {children}
      </div>
    </section>
  );
};

const Rows: React.FC<{ rows: Array<[string, React.ReactNode]> }> = ({ rows }) => (
  <dl className="space-y-2.5">
    {rows.map(([label, value]) => (
      <div key={label} className="flex items-start gap-3 text-xs">
        <dt className="w-24 flex-shrink-0 text-[rgba(0,0,0,0.45)]">{label}</dt>
        <dd className="min-w-0 break-all font-semibold text-[rgba(0,0,0,0.8)]">{value}</dd>
      </div>
    ))}
  </dl>
);

/**
 * An event's full details (build 4 design 6.8, FR-EVD), opened by "View full details →" in the Data
 * Lineage page's event popover: the event type and file name, then "Summary" and "Details". It joins the
 * page's Escape stack, takes focus on its close X and returns it to the event card; `elevated` puts it
 * above the full-screen modal.
 */
const LineageEventDrawer: React.FC<Props> = ({ event, returnFocus, elevated, onClose }) => (
  <NestedDrawerShell
    label={event.type}
    returnFocus={returnFocus}
    onClose={onClose}
    widthClass="lg:w-[30rem]"
    elevated={elevated}
  >
    {({ closeButton, close }) => (
      <>
        <div className="flex items-start gap-3 px-5 pb-3 pt-4">
          <span className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)]">
            <Icon icon="mdi:source-branch" width={16} aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-base font-bold text-[rgba(0,0,0,0.88)]">{event.type}</h2>
            <p className="mt-0.5 truncate text-xs text-[rgba(0,0,0,0.5)]" title={event.fileName ?? '-'}>{event.fileName ?? '-'}</p>
          </div>
          <button
            ref={closeButton}
            type="button"
            onClick={close}
            aria-label="Close"
            className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
          >
            <Icon icon="mdi:close" width={14} />
          </button>
        </div>
        <div className="flex-1 space-y-3 overflow-y-auto px-5 pb-6">
          <DrawerSection title="Summary">
            <Rows
              rows={[
                ['Actor', event.actor],
                ['Actor Type', event.internal ? (
                  <span className="inline-block rounded border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.05)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(0,0,0,0.6)]">
                    Internal
                  </span>
                ) : '-'],
                ['Email', event.actorEmail ?? '-'],
                ['Time', event.at === null ? '-' : formatEventTime(event.at)],
                ['Channel', event.channel ?? '-'],
              ]}
            />
          </DrawerSection>
          <DrawerSection title="Details">
            <Rows
              rows={[[
                'Event type',
                <span className="inline-block rounded border border-violet-200 bg-violet-50 px-1.5 py-0.5 text-[10px] font-semibold text-violet-700">
                  {event.type}
                </span>,
              ]]}
            />
          </DrawerSection>
        </div>
      </>
    )}
  </NestedDrawerShell>
);

export default LineageEventDrawer;
