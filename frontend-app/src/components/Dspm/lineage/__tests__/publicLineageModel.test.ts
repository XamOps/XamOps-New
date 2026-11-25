import { describe, expect, it } from 'vitest';
import type { SaasFileLineage } from '../../../../types/dspmDrawers';
import type { EndpointFileLineageAsset } from '../../../../types/dspmFiles';
import type { PublicFileLineage } from '../../../../types/dspmLineage';
import { BUILT_EVENT_KEY, OWN_LANE_KEY } from '../../datastores/endpoint/endpointLineageModel';
import { publicLineageLanes, type PublicLineageSubject } from '../publicLineageModel';
import { ROOT_NODE_ICON, datastoreIcon, serviceLabel } from '../lineageVisuals';
import { ROOT_FALLBACK_NAME } from '../lineageText';

const T0 = 1_770_000_000;
const T1 = T0 + 86_400;
const T2 = T1 + 86_400;
const T3 = T2 + 86_400;

const UNAVAILABLE: SaasFileLineage = { available: false, coverageNote: null, lanes: [], events: [] };
const COPIES: SaasFileLineage = {
  available: true,
  coverageNote: null,
  lanes: [
    { key: 'copy-0', fileName: 'plan.docx', ownerName: 'Studio Laptop', ownerEmail: null, driveType: null, os: 'MAC',
      provider: null, channelLabel: 'Endpoint', self: true, firstSeenAt: null },
    { key: 'copy-1', fileName: 'plan (1).docx', ownerName: 'Spare Laptop', ownerEmail: null, driveType: null,
      os: 'WINDOWS', provider: null, channelLabel: 'Endpoint', self: false, firstSeenAt: null },
  ],
  events: [],
};

const asset = (over: Partial<EndpointFileLineageAsset> = {}): EndpointFileLineageAsset => ({
  title: 'plan.docx', device: 'Studio Laptop', osUser: 'sam', firstSeenAt: T1,
  events: [{ laneKey: null, typeLabel: 'File Modified', actor: 'sam@example.com', occurredAt: T2 }],
  ...over,
});

const ENDPOINT_SUBJECT: PublicLineageSubject = {
  name: 'plan.docx', datastoreName: 'Studio Laptop', service: 'ENDPOINT', actor: 'sam', createdAt: T0,
};
const endpointLineage = (over: Partial<PublicFileLineage> = {}): PublicFileLineage => ({
  kind: 'ENDPOINT', asset: asset(), copies: COPIES, ownerEmail: null, ...over,
});

const DRIVE_SUBJECT: PublicLineageSubject = {
  name: 'budget.xlsx', datastoreName: 'Robin Lee', service: 'GOOGLE_DRIVE', actor: null, createdAt: null,
};
const driveLineage = (over: Partial<PublicFileLineage> = {}): PublicFileLineage => ({
  kind: 'DRIVE',
  asset: asset({
    title: 'budget.xlsx', device: null, osUser: null, firstSeenAt: T1,
    events: [
      { laneKey: null, typeLabel: 'Viewed', actor: 'kim@example.com', occurredAt: T3 },
      { laneKey: null, typeLabel: 'Shared', actor: 'robin@example.com', occurredAt: T2 },
    ],
  }),
  copies: UNAVAILABLE,
  ownerEmail: 'robin@example.com',
  ...over,
});

describe('publicLineageLanes, endpoint', () => {
  it('builds "File Created" by the actor, the recorded events, then the copies\' lanes', () => {
    const lanes = publicLineageLanes('ENDPOINT', ENDPOINT_SUBJECT, endpointLineage());
    expect(lanes.map(l => l.key)).toEqual([OWN_LANE_KEY, 'copy-1']);
    expect(lanes[0]).toMatchObject({ fileName: 'plan.docx', owner: 'Studio Laptop', own: true });
    expect(lanes[0].events.map(e => [e.key, e.type, e.at, e.actor])).toEqual([
      [BUILT_EVENT_KEY, 'File Created', T0, 'sam'],
      ['asset:0', 'File Modified', T2, 'sam'],
    ]);
    expect(lanes[0].events[0].channel).toBe('Studio Laptop');
  });

  it('builds "First Seen" from the asset without a creation time', () => {
    const lanes = publicLineageLanes('ENDPOINT', { ...ENDPOINT_SUBJECT, createdAt: null }, endpointLineage());
    expect(lanes[0].events[0]).toMatchObject({ key: BUILT_EVENT_KEY, type: 'First Seen', at: T1 });
  });

  it('leaves the built event out when a recorded event is the creation', () => {
    const lineage = endpointLineage({
      asset: asset({ events: [{ laneKey: null, typeLabel: 'File Created', actor: 'sam', occurredAt: T0 }] }),
    });
    const events = publicLineageLanes('ENDPOINT', ENDPOINT_SUBJECT, lineage)[0].events;
    expect(events.map(e => e.key)).toEqual(['asset:0']);
  });
});

describe('publicLineageLanes, drive', () => {
  it('captions the own lane with the owner e-mail, Google Drive as channel and logo, First Seen first', () => {
    const lanes = publicLineageLanes('DRIVE', DRIVE_SUBJECT, driveLineage());
    expect(lanes).toHaveLength(1);
    expect(lanes[0]).toMatchObject({ key: OWN_LANE_KEY, icon: 'GOOGLE_DRIVE', fileName: 'budget.xlsx', owner: 'robin@example.com' });
    expect(lanes[0].events.map(e => [e.type, e.at, e.actor, e.channel])).toEqual([
      ['First Seen', T1, 'robin', 'Google Drive'],
      ['Shared', T2, 'robin', 'Google Drive'],
      ['Viewed', T3, 'kim', 'Google Drive'],
    ]);
    expect(lanes[0].events[0].actorEmail).toBe('robin@example.com');
  });

  it('uses no logo and no channel for a service without a label', () => {
    const lanes = publicLineageLanes('DRIVE', { ...DRIVE_SUBJECT, service: 'BOX' }, driveLineage());
    expect(lanes[0].icon).toBeNull();
    expect(lanes[0].events[0].channel).toBeNull();
  });

  it('draws nothing without an asset', () => {
    expect(publicLineageLanes('DRIVE', DRIVE_SUBJECT, driveLineage({ asset: null }))).toEqual([]);
  });
});

describe('publicLineageLanes, no lineage', () => {
  it('draws nothing while the lineage loads or after it failed', () => {
    expect(publicLineageLanes('ENDPOINT', ENDPOINT_SUBJECT, null)).toEqual([]);
    expect(publicLineageLanes('DRIVE', DRIVE_SUBJECT, null)).toEqual([]);
  });
});

describe('the public path\'s visuals and text', () => {
  it('labels the services and picks the datastore icons', () => {
    expect([null, 'ENDPOINT', 'GOOGLE_DRIVE', 'ONE_DRIVE', 'SHARE_POINT', 'BOX'].map(serviceLabel))
      .toEqual([null, 'Endpoint', 'Google Drive', 'OneDrive', 'SharePoint', null]);
    expect(datastoreIcon('GOOGLE_DRIVE')).toBe('mdi:google-drive');
    expect(datastoreIcon('ENDPOINT')).toBe('mdi:laptop');
    expect(datastoreIcon(null)).toBe('mdi:database-outline');
    expect(ROOT_NODE_ICON).toBe('mdi:folder-outline');
    expect(ROOT_FALLBACK_NAME).toBe('Project');
  });
});
