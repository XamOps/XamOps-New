import React from 'react';
import { Icon } from '@iconify/react';
import { DspmProviderIcon } from '../../DspmIcons';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { CopyButton } from '../ui/CopyButton';
import type { LineageEventView, LineageLaneView } from './lineageModel';
import { LANE_LABEL_W } from './lineageLayout';
import { formatLineageTime } from './saasFormat';
import { GreyTag } from './SaasParts';

/**
 * The lineage drawing's parts, shared by the SaaS file drawer's `LineageCanvas` and the Data Lineage
 * page's band (build 4 design 6.9): a lane's label, an event card and the event popover's body.
 */

/**
 * `endpoint` (the Endpoint file drawer): own lanes draw a file icon instead of their source's, and each
 * card a violet circle (red for a deletion, by the event's tone) and "Actor: <actor> · <time>".
 */
export type LineageVariant = 'saas' | 'endpoint';

/** A lane's label: the provider icon, the bold file name with its copy button, and the muted owner. */
export const LaneLabel: React.FC<{ lane: LineageLaneView; variant?: LineageVariant }> = ({ lane, variant = 'saas' }) => (
  <div className="flex h-full min-w-0 items-center gap-2 px-3" style={{ width: LANE_LABEL_W }}>
    {variant === 'endpoint' && lane.own
      ? <Icon icon="mdi:file-outline" width={18} className="flex-shrink-0 text-[rgba(0,0,0,0.55)]" />
      : <DspmProviderIcon provider={lane.icon} size={18} />}
    <div className="min-w-0">
      <div className="flex min-w-0 items-center gap-0.5">
        <span className="truncate text-xs font-semibold text-[rgba(0,0,0,0.85)]" title={lane.fileName}>{lane.fileName}</span>
        {lane.fileName !== '-' && <CopyButton text={lane.fileName} label="Copy file name" />}
      </div>
      <div className="truncate text-[11px] text-[rgba(0,0,0,0.45)]" title={lane.owner}>{lane.owner}</div>
    </div>
  </div>
);

interface EventCardProps {
  event: LineageEventView;
  /** The lane the card sits on (`data-event-lane`). */
  laneKey: string;
  /** Its popover is open. */
  open: boolean;
  /** The card's absolute position and size. */
  style: React.CSSProperties;
  onClick: (e: React.MouseEvent<HTMLButtonElement>) => void;
  variant?: LineageVariant;
}

/** An event card: the event type, then "actor · time" (endpoint: a toned file circle, then "Actor: actor · time"). */
export const EventCard: React.FC<EventCardProps> = ({ event, laneKey, open, style, onClick, variant = 'saas' }) => (
  <button
    type="button"
    data-event-lane={laneKey}
    aria-haspopup="dialog"
    aria-expanded={open}
    onClick={onClick}
    className={`absolute flex flex-col justify-center rounded-lg border bg-white px-2.5 text-left shadow-sm transition-colors hover:border-[rgba(0,0,0,0.25)] ${
      open ? 'border-sky-400' : 'border-[rgba(0,0,0,0.1)]'
    }`}
    style={style}
  >
    {variant === 'endpoint' ? (
      <span className="flex min-w-0 items-center gap-2">
        <span
          data-tone={event.tone ?? 'purple'}
          className={`flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full ${
            event.tone === 'red' ? 'bg-rose-100 text-rose-600' : 'bg-violet-100 text-violet-600'
          }`}
        >
          <Icon icon="mdi:file-outline" width={13} />
        </span>
        <span className="flex min-w-0 flex-col">
          <span className="truncate text-[11px] font-semibold text-[rgba(0,0,0,0.85)]">{event.type}</span>
          <span className="truncate text-[10px] text-[rgba(0,0,0,0.5)]">
            Actor: {event.actor} · {event.at === null ? '-' : formatLineageTime(event.at)}
          </span>
        </span>
      </span>
    ) : (
      <>
        <span className="truncate text-[11px] font-semibold text-[rgba(0,0,0,0.85)]">{event.type}</span>
        <span className="truncate text-[10px] text-[rgba(0,0,0,0.5)]">
          {event.actor} · {event.at === null ? '-' : formatLineageTime(event.at)}
        </span>
      </>
    )}
  </button>
);

interface EventDetailsProps {
  event: LineageEventView;
  /**
   * Opens the event drawer. Without it, "View full details →" is the greyed console-only link (the SaaS
   * file drawer); with it, a working link (the Data Lineage page).
   */
  onViewDetails?: () => void;
}

/** The event popover's body: the type, then Actor (with "Internal"), Email, File and Channel. */
export const EventDetails: React.FC<EventDetailsProps> = ({ event, onViewDetails }) => {
  const rows: Array<[string, React.ReactNode]> = [
    ['Actor', (
      <span className="inline-flex flex-wrap items-center gap-1.5">
        {event.actor}
        {event.internal && <GreyTag>Internal</GreyTag>}
      </span>
    )],
    ['Email', event.actorEmail ?? '-'],
    ['File', event.fileName ?? '-'],
    ['Channel', event.channel ?? '-'],
  ];
  return (
    <>
      <p className="mb-2 text-[11px] font-bold text-[rgba(0,0,0,0.8)]">{event.type}</p>
      <dl className="space-y-1.5">
        {rows.map(([label, value]) => (
          <div key={label} className="flex items-start gap-3">
            <dt className="w-14 flex-shrink-0 text-[11px] text-[rgba(0,0,0,0.45)]">{label}</dt>
            <dd className="min-w-0 break-all text-[11px] font-semibold text-[rgba(0,0,0,0.8)]">{value}</dd>
          </div>
        ))}
      </dl>
      <div className="mt-3">
        {onViewDetails ? (
          <button
            type="button"
            onClick={onViewDetails}
            className="inline-flex items-center gap-1 text-xs font-semibold text-sky-700 hover:underline"
          >
            View full details →
          </button>
        ) : (
          <ConsoleOnlyButton variant="link" placement="right">View full details →</ConsoleOnlyButton>
        )}
      </div>
    </>
  );
};
