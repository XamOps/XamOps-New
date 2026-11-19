/**
 * The Endpoint file drawer's Lineage model (plan C19). Pure. With a lineage asset, the file's own lane
 * comes first, built from the asset and its recorded events, then the hash-lineage lanes of the file's
 * copies without their own lane (the console's other lanes are inferred matches, console-only, GAP-29).
 * Without an asset, build 2's hash-lineage lanes are drawn as they are.
 *
 * The own lane gets one event the vendor does not record, as in the console: "File Created" at the file's
 * creation time, else "First Seen" at the asset's first-seen time. It is left out when a recorded event
 * already is the creation.
 */

import type { SaasFileLineageEvent } from '../../../../types/dspmDrawers';
import type { EndpointFileLineage, EndpointFileLineageAsset } from '../../../../types/dspmFiles';
import {
  actorName, buildLineageLanes, byTime, type LineageEventView, type LineageLaneView,
} from '../saas/lineageModel';

/** What the lanes need of the opened file; owner, device and creation time come from its overview. */
export interface EndpointLineageSubject {
  /** The drawer's title: the own lane's name when the asset has no title. */
  name: string;
  owner: string | null;
  device: string | null;
  fileCreatedAt: number | null;
}

export const OWN_LANE_KEY = 'endpoint-asset';
export const BUILT_EVENT_KEY = 'endpoint-built';

/** Recorded event types that already are the file's creation, normalised. */
const CREATE_TYPES: ReadonlySet<string> = new Set(['create', 'created', 'file_created', 'file_create']);
const RED_TYPE = /delete|remove|purge|trash/i;

/** Lower case, trimmed, spaces as "_": "File Created" → "file_created". */
const normalisedType = (type: string) => type.toLowerCase().trim().replace(/\s+/g, '_');

/** A card's tone: red for a deletion, purple for anything else. */
export function eventTone(type: string): 'red' | 'purple' {
  return RED_TYPE.test(type) ? 'red' : 'purple';
}

const emailOf = (actor: string | null): string | null => {
  const text = actor?.trim() ?? '';
  return text.includes('@') ? text : null;
};

function recordedEvent(event: SaasFileLineageEvent, index: number, fileName: string, channel: string | null): LineageEventView {
  return {
    key: `asset:${index}`,
    type: event.typeLabel ?? 'Event',
    actor: actorName(event.actor),
    actorEmail: emailOf(event.actor),
    internal: false,
    at: event.occurredAt,
    fileName,
    channel,
  };
}

function builtEvent(
  subject: EndpointLineageSubject, asset: EndpointFileLineageAsset, fileName: string, channel: string | null,
): LineageEventView {
  const created = subject.fileCreatedAt !== null;
  const actor = subject.owner ?? asset.osUser;
  return {
    key: BUILT_EVENT_KEY,
    type: created ? 'File Created' : 'First Seen',
    actor: actorName(actor),
    actorEmail: emailOf(actor),
    internal: false,
    at: created ? subject.fileCreatedAt : asset.firstSeenAt,
    fileName,
    channel,
  };
}

const toned = (lane: LineageLaneView): LineageLaneView => ({
  ...lane,
  events: lane.events.map(event => ({ ...event, tone: eventTone(event.type) })),
});

/**
 * The lanes to draw, each event with its tone; none while the lineage loads or after it failed.
 *
 * With an asset: the own lane (the asset's title, else the file name; the device, else the owner, below
 * it) with the built event and the recorded events, oldest first and the built event first on a tie;
 * then the copies' lanes other than the file's own, in API order.
 */
export function buildEndpointLineageLanes(
  subject: EndpointLineageSubject, lineage: EndpointFileLineage | null,
): LineageLaneView[] {
  if (!lineage) return [];
  const copies = buildLineageLanes(
    { name: subject.name, driveType: null, owner: subject.owner, lastModifiedAt: null }, lineage.copies);
  const { asset } = lineage;
  if (!asset) return copies.map(toned);

  const fileName = asset.title ?? subject.name;
  const channel = asset.device ?? subject.device;
  const recorded = asset.events.map((event, index) => recordedEvent(event, index, fileName, channel));
  const createdRecorded = asset.events.some(event =>
    event.typeLabel !== null && CREATE_TYPES.has(normalisedType(event.typeLabel)));
  const built = createdRecorded ? [] : [builtEvent(subject, asset, fileName, channel)];
  const own: LineageLaneView = {
    key: OWN_LANE_KEY,
    icon: null,
    fileName,
    owner: asset.device ?? subject.owner ?? '-',
    own: true,
    events: byTime([...built, ...recorded]),
  };
  return [own, ...copies.filter(lane => !lane.own)].map(toned);
}

/** Whether the only event drawn is the built one: the title's ⓘ explains that more will appear. */
export function onlyBuiltEvent(lanes: LineageLaneView[]): boolean {
  const events = lanes.flatMap(lane => lane.events);
  return events.length === 1 && events[0].key === BUILT_EVENT_KEY;
}
