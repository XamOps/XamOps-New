/**
 * The SaaS file drawer's Lineage model (design 4.4, 6.6; FR-LIN): the lanes and event cards the canvas
 * draws, built from the file overview and the `/lineage` answer. Pure, so the console's rules live in one
 * tested place:
 * - a Gmail file gets its own "Gmail" lane with an "Attached to Email" event, which no route returns
 *   (GAP-12): it is built from the overview's owner and last-modified time;
 * - every copy lane gets a "First Seen" event at the copy's first-seen time, which the timeline does not
 *   return as an event either (GAP-59);
 * - the opened file's own copy (`self`, decided by file id on the server) is the tinted own lane, or, on a
 *   Gmail file, is folded into the Gmail lane.
 */

import type { SaasFileLineage, SaasFileLineageLane, SaasFileOverview } from '../../../../types/dspmDrawers';
import { sourceIconKey } from '../datastoreVisuals';

/** The console's note on a Gmail file's Lineage tab, verbatim (FR-LIN-2). */
export const GMAIL_LINEAGE_NOTE = 'Gmail activity is not recorded in lineage, so this attachment has no activity of its own. The lanes below are the files inferred to be the same document elsewhere, each with its own recorded activity.';

/** The footer when no lane besides the file's own exists (FR-LIN-7). */
export const NO_RELATED_FILES_TEXT = 'No related files in other sources at this confidence.';

/** The greyed confidence dropdown's text (A12): inferred matches are console-only (GAP-29). */
export const RELATED_FILES_CONFIDENCE = 'Related files: Medium and above';

/** One event card and its popover. */
export interface LineageEventView {
  key: string;
  type: string;
  actor: string;
  actorEmail: string | null;
  /** The popover's grey "Internal" tag. */
  internal: boolean;
  /** Epoch seconds, or null: drawn "-" and sorted last. */
  at: number | null;
  fileName: string | null;
  channel: string | null;
}

/** One lane: its label (icon, file name, owner) and its events in drawing order. */
export interface LineageLaneView {
  key: string;
  /** A `DspmProviderIcon` key. */
  icon: string | null;
  fileName: string;
  owner: string;
  /** The file's own lane (the Gmail lane on a Gmail file), drawn tinted. */
  own: boolean;
  events: LineageEventView[];
}

const GMAIL_LANE_KEY = 'gmail-origin';

/**
 * What the lanes need of the opened file: a SaaS file overview, or any file described by these four fields
 * (the Data Lineage page's file row, with `driveType` null for the non-Gmail path).
 */
export type LineageSubject = Pick<SaasFileOverview, 'name' | 'driveType' | 'owner' | 'lastModifiedAt'>;

export function isGmail(overview: LineageSubject): boolean {
  return overview.driveType === 'GMAIL';
}

/** The text before "@" of an e-mail, else the text itself, else "-". */
export function actorName(actor: string | null): string {
  const text = actor?.trim() ?? '';
  if (text === '') return '-';
  const at = text.indexOf('@');
  return at > 0 ? text.slice(0, at) : text;
}

/**
 * A Gmail file's own lane with its "Attached to Email" event (GAP-12: no route returns this event). The
 * mailbox owner of a Workspace Gmail account is a user of the organisation, hence `internal`.
 */
export function gmailOriginLane(overview: LineageSubject): LineageLaneView {
  return {
    key: GMAIL_LANE_KEY,
    icon: 'GMAIL',
    fileName: overview.name ?? '-',
    owner: overview.owner ?? '-',
    own: true,
    events: [{
      key: 'gmail-attached',
      type: 'Attached to Email',
      actor: actorName(overview.owner),
      actorEmail: overview.owner,
      internal: true,
      at: overview.lastModifiedAt,
      fileName: overview.name,
      channel: 'Gmail',
    }],
  };
}

/** A copy lane's "First Seen" event (GAP-59: the timeline does not return it), or null without a time. */
export function firstSeenEvent(lane: SaasFileLineageLane): LineageEventView | null {
  if (lane.firstSeenAt === null) return null;
  return {
    key: `first-seen:${lane.key}`,
    type: 'First Seen',
    actor: actorName(lane.ownerEmail ?? lane.ownerName),
    actorEmail: lane.ownerEmail,
    internal: false,
    at: lane.firstSeenAt,
    fileName: lane.fileName,
    channel: lane.channelLabel,
  };
}

/** Oldest first, events without a time last; `Array.prototype.sort` is stable, so ties keep their order. */
function byTime(events: LineageEventView[]): LineageEventView[] {
  return [...events].sort((a, b) => {
    if (a.at === null || b.at === null) return a.at === null ? (b.at === null ? 0 : 1) : -1;
    return a.at - b.at;
  });
}

/** An API event as its card, with the key of the lane it matched (null when it matched none). */
interface PlacedEvent {
  laneKey: string | null;
  view: LineageEventView;
}

/** The API events in API order, each as its card; File and Channel come from the matched lane. */
function apiEvents(lineage: SaasFileLineage | null): PlacedEvent[] {
  if (!lineage) return [];
  const lanes = new Map(lineage.lanes.map(lane => [lane.key, lane]));
  return lineage.events.map((event, index) => {
    const lane = event.laneKey === null ? undefined : lanes.get(event.laneKey);
    const actor = event.actor?.trim() ?? '';
    return {
      laneKey: lane ? lane.key : null,
      view: {
        key: `event:${index}`,
        type: event.typeLabel ?? 'Event',
        actor: actorName(event.actor),
        actorEmail: actor.includes('@') ? actor : null,
        internal: false,
        at: event.occurredAt,
        fileName: lane ? lane.fileName : null,
        channel: lane ? lane.channelLabel : null,
      },
    };
  });
}

/** The cards of the events `belongs` picks, in API order. */
const eventsWhere = (placed: PlacedEvent[], belongs: (laneKey: string | null) => boolean) =>
  placed.filter(p => belongs(p.laneKey)).map(p => p.view);

/** An API lane with its First Seen and its events, in drawing order. */
function apiLane(lane: SaasFileLineageLane, events: LineageEventView[]): LineageLaneView {
  const firstSeen = firstSeenEvent(lane);
  return {
    key: lane.key,
    icon: sourceIconKey(lane),
    fileName: lane.fileName ?? '-',
    owner: lane.ownerName ?? '-',
    own: lane.self,
    // First Seen before the lane's API events on a tie.
    events: byTime(firstSeen ? [firstSeen, ...events] : events),
  };
}

/**
 * The lanes to draw. `lineage` is null while it loads or after it failed: a Gmail file still gets its
 * lane (it needs only the overview), any other file none.
 *
 * Gmail file: the Gmail lane first, with "Attached to Email" always first and after it, by time, the
 * timeline events of the skipped `self` lane (the Gmail file itself, so it is not drawn twice, and its
 * First Seen is not added, A33) and the events that match no lane; then every other lane in API order.
 *
 * Any other file: the API lanes in API order, the `self` lane in place as the own lane; events that match
 * no lane go on the own lane, else on the first lane, and are not drawn when there is no lane.
 */
export function buildLineageLanes(overview: LineageSubject, lineage: SaasFileLineage | null): LineageLaneView[] {
  const placed = apiEvents(lineage);
  const lanes = lineage?.lanes ?? [];

  if (isGmail(overview)) {
    const origin = gmailOriginLane(overview);
    const selfKeys = new Set(lanes.filter(lane => lane.self).map(lane => lane.key));
    const folded = eventsWhere(placed, key => key === null || selfKeys.has(key));
    const gmailLane = { ...origin, events: [...origin.events, ...byTime(folded)] };
    const others = lanes
      .filter(lane => !lane.self)
      .map(lane => apiLane(lane, eventsWhere(placed, key => key === lane.key)));
    return [gmailLane, ...others];
  }

  const ownIndex = lanes.findIndex(lane => lane.self);
  const hostKey = lanes[ownIndex >= 0 ? ownIndex : 0]?.key;
  return lanes.map(lane => apiLane(
    lane,
    eventsWhere(placed, key => key === lane.key || (key === null && lane.key === hostKey)),
  ));
}

/**
 * The lanes with only the events whose type, actor, actor e-mail or file name contains the trimmed query,
 * case-insensitively. Lanes stay drawn without events. A blank query keeps everything.
 */
export function visibleLanes(lanes: LineageLaneView[], query: string): LineageLaneView[] {
  const needle = query.trim().toLowerCase();
  if (needle === '') return lanes;
  const matches = (event: LineageEventView) =>
    [event.type, event.actor, event.actorEmail, event.fileName].some(text => text?.toLowerCase().includes(needle));
  return lanes.map(lane => ({ ...lane, events: lane.events.filter(matches) }));
}

/**
 * Whether the footer "No related files in other sources at this confidence." shows: no lane besides the
 * file's own. Pass the lanes `buildLineageLanes` returned, never the searched ones, so a search never makes
 * the footer appear (FR-LIN-7). On a non-Gmail file the rule is A11 [O3].
 */
export function showsNoRelatedFiles(lanes: LineageLaneView[]): boolean {
  return lanes.every(lane => lane.own);
}
