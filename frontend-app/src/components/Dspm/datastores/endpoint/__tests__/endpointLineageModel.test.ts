import { describe, expect, it } from 'vitest';
import type { EndpointFileLineage, EndpointFileLineageAsset } from '../../../../../types/dspmFiles';
import { buildLineageLanes, showsNoRelatedFiles } from '../../saas/lineageModel';
import { event, lane, lineage, local } from '../../saas/__tests__/saasTestData';
import {
  BUILT_EVENT_KEY, OWN_LANE_KEY, buildEndpointLineageLanes, eventTone, onlyBuiltEvent, type EndpointLineageSubject,
} from '../endpointLineageModel';

// ─── synthetic data (shapes only; no captured values) ────────────────────────────────────────────

const CREATED = local(2026, 8, 4, 18, 40);
const FIRST_SEEN = local(2026, 8, 5, 10, 0);

const SUBJECT: EndpointLineageSubject = {
  name: 'contacts.json', owner: 'priya', device: 'build-laptop-07', fileCreatedAt: CREATED,
};

function asset(over: Partial<EndpointFileLineageAsset> = {}): EndpointFileLineageAsset {
  return { title: 'Contacts export', device: 'build-laptop-07', osUser: 'tester', firstSeenAt: FIRST_SEEN, events: [], ...over };
}

const endpointLineage = (over: Partial<EndpointFileLineage> = {}): EndpointFileLineage =>
  ({ asset: asset(), copies: lineage(), ...over });

const types = (events: { type: string }[]) => events.map(e => e.type);

describe('buildEndpointLineageLanes with a lineage asset', () => {
  it('draws the own lane first, with File Created at the creation time by the owner on the device', () => {
    const lanes = buildEndpointLineageLanes(SUBJECT, endpointLineage());
    expect(lanes).toEqual([{
      key: OWN_LANE_KEY,
      icon: null,
      fileName: 'Contacts export',
      owner: 'build-laptop-07',
      own: true,
      events: [{
        key: BUILT_EVENT_KEY,
        type: 'File Created',
        actor: 'priya',
        actorEmail: null,
        internal: false,
        at: CREATED,
        fileName: 'Contacts export',
        channel: 'build-laptop-07',
        tone: 'purple',
      }],
    }]);
    expect(onlyBuiltEvent(lanes)).toBe(true);
    expect(showsNoRelatedFiles(lanes)).toBe(true);
  });

  it('falls back to First Seen at the first-seen time, by the OS user when there is no owner', () => {
    const lanes = buildEndpointLineageLanes({ ...SUBJECT, owner: null, fileCreatedAt: null }, endpointLineage());
    expect(lanes[0].events).toHaveLength(1);
    expect(lanes[0].events[0]).toMatchObject({ key: BUILT_EVENT_KEY, type: 'First Seen', at: FIRST_SEEN, actor: 'tester' });
  });

  it('names the lane after the file and the owner when the asset has no title or device', () => {
    const lanes = buildEndpointLineageLanes(SUBJECT, endpointLineage({ asset: asset({ title: null, device: null }) }));
    expect(lanes[0]).toMatchObject({ fileName: 'contacts.json', owner: 'priya' });
    expect(lanes[0].events[0]).toMatchObject({ fileName: 'contacts.json', channel: 'build-laptop-07' });
    const nobody = buildEndpointLineageLanes({ ...SUBJECT, owner: null }, endpointLineage({ asset: asset({ device: null }) }));
    expect(nobody[0].owner).toBe('-');
  });

  it.each(['File Created', 'created', ' Create ', 'FILE_CREATE'])(
    'adds no built event when a recorded event is the creation ("%s")',
    type => {
      const lanes = buildEndpointLineageLanes(SUBJECT, endpointLineage({
        asset: asset({ events: [event({ typeLabel: type, actor: 'priya', occurredAt: CREATED })] }),
      }));
      expect(types(lanes[0].events)).toEqual([type]);
      expect(onlyBuiltEvent(lanes)).toBe(false);
    },
  );

  it('orders the events oldest first, the built event first on a tie and untimed events last', () => {
    const lanes = buildEndpointLineageLanes({ ...SUBJECT, fileCreatedAt: 500 }, endpointLineage({
      asset: asset({
        events: [
          event({ typeLabel: 'Untimed', occurredAt: null }),
          event({ typeLabel: 'Opened', occurredAt: 900 }),
          event({ typeLabel: 'Renamed', occurredAt: 500 }),
          event({ typeLabel: 'Copied', occurredAt: 100 }),
        ],
      }),
    }));
    expect(types(lanes[0].events)).toEqual(['Copied', 'File Created', 'Renamed', 'Opened', 'Untimed']);
    expect(lanes[0].events[0]).toMatchObject({
      actor: 'sam', actorEmail: 'sam@example.com', fileName: 'Contacts export', channel: 'build-laptop-07',
    });
    expect(onlyBuiltEvent(lanes)).toBe(false);
  });

  it('gives a deletion a red tone and any other event purple', () => {
    expect(['File Deleted', 'Removed from device', 'PURGED', 'Moved to Trash'].map(eventTone)).toEqual(['red', 'red', 'red', 'red']);
    expect(eventTone('Opened')).toBe('purple');
    const lanes = buildEndpointLineageLanes(SUBJECT, endpointLineage({
      asset: asset({ events: [event({ typeLabel: 'File Deleted', occurredAt: CREATED + 60 })] }),
    }));
    expect(lanes[0].events.map(e => [e.type, e.tone])).toEqual([['File Created', 'purple'], ['File Deleted', 'red']]);
  });

  it("draws the copies' lanes after it, without the file's own copy", () => {
    const lanes = buildEndpointLineageLanes(SUBJECT, endpointLineage({
      copies: lineage({
        lanes: [
          lane('copy-0', { self: true, fileName: 'contacts.json', firstSeenAt: 100 }),
          lane('copy-1', { fileName: 'contacts-copy.json', driveType: 'GMAIL', channelLabel: 'Gmail', firstSeenAt: 200 }),
        ],
      }),
    }));
    expect(lanes.map(l => [l.key, l.own])).toEqual([[OWN_LANE_KEY, true], ['copy-1', false]]);
    expect(lanes[1].events.map(e => [e.type, e.tone])).toEqual([['First Seen', 'purple']]);
    expect(onlyBuiltEvent(lanes)).toBe(false);
    expect(showsNoRelatedFiles(lanes)).toBe(false);
  });
});

describe('buildEndpointLineageLanes without a lineage asset', () => {
  it("gives build 2's hash-lineage lanes, own lane included, each event toned", () => {
    const copies = lineage({
      lanes: [lane('copy-0', { self: true, firstSeenAt: 100 }), lane('copy-1', { firstSeenAt: 200 })],
      events: [event({ laneKey: 'copy-1', typeLabel: 'File Deleted', occurredAt: 300 })],
    });
    const lanes = buildEndpointLineageLanes(SUBJECT, { asset: null, copies });
    const build2 = buildLineageLanes({ name: SUBJECT.name, driveType: null, owner: SUBJECT.owner, lastModifiedAt: null }, copies);
    expect(lanes).toEqual(build2.map(l => ({ ...l, events: l.events.map(e => ({ ...e, tone: eventTone(e.type) })) })));
    expect(lanes.map(l => [l.key, l.own])).toEqual([['copy-0', true], ['copy-1', false]]);
    expect(lanes[1].events.map(e => e.tone)).toEqual(['purple', 'red']);
  });

  it('draws nothing while the lineage loads or after it failed, and nothing without lanes', () => {
    expect(buildEndpointLineageLanes(SUBJECT, null)).toEqual([]);
    const lanes = buildEndpointLineageLanes(SUBJECT, { asset: null, copies: lineage() });
    expect(lanes).toEqual([]);
    expect(onlyBuiltEvent(lanes)).toBe(false);
  });
});
