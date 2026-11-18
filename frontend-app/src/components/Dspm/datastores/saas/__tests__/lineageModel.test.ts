import { describe, expect, it } from 'vitest';
import {
  GMAIL_LINEAGE_NOTE, actorName, buildLineageLanes, firstSeenEvent, gmailOriginLane, isGmail, showsNoRelatedFiles,
  visibleLanes,
} from '../lineageModel';
import { OWNER, driveOverview, event, lane, lineage, local, overview } from './saasTestData';

const types = (events: { type: string }[]) => events.map(e => e.type);

describe('constants', () => {
  it('keeps the console note verbatim', () => {
    expect(GMAIL_LINEAGE_NOTE).toBe(
      'Gmail activity is not recorded in lineage, so this attachment has no activity of its own. The lanes below are '
      + 'the files inferred to be the same document elsewhere, each with its own recorded activity.',
    );
  });
});

describe('actorName', () => {
  it('takes the text before "@", else the text, else "-"', () => {
    expect(actorName('pat@example.com')).toBe('pat');
    expect(actorName('Pat Example')).toBe('Pat Example');
    expect(actorName('  ')).toBe('-');
    expect(actorName(null)).toBe('-');
  });
});

describe('gmailOriginLane', () => {
  it('builds the Gmail lane and its "Attached to Email" event from the overview', () => {
    const file = overview();
    expect(isGmail(file)).toBe(true);
    expect(gmailOriginLane(file)).toEqual({
      key: 'gmail-origin',
      icon: 'GMAIL',
      fileName: 'travel-itinerary.pdf',
      owner: OWNER,
      own: true,
      events: [{
        key: 'gmail-attached',
        type: 'Attached to Email',
        actor: 'pat',
        actorEmail: OWNER,
        internal: true,
        at: local(2026, 9, 10, 12, 54),
        fileName: 'travel-itinerary.pdf',
        channel: 'Gmail',
      }],
    });
  });

  it('still draws the lane without an owner or a time', () => {
    const built = gmailOriginLane(overview({ owner: null, lastModifiedAt: null }));
    expect(built.owner).toBe('-');
    expect(built.events[0]).toMatchObject({ actor: '-', actorEmail: null, at: null });
  });
});

describe('firstSeenEvent', () => {
  it('is null without a first-seen time', () => {
    expect(firstSeenEvent(lane('copy-0'))).toBeNull();
  });

  it("uses the e-mail's local part as the actor and the lane's file and channel", () => {
    expect(firstSeenEvent(lane('copy-1', { firstSeenAt: 1_790_000_000, ownerEmail: 'sam@example.com', channelLabel: 'Gmail' })))
      .toEqual({
        key: 'first-seen:copy-1',
        type: 'First Seen',
        actor: 'sam',
        actorEmail: 'sam@example.com',
        internal: false,
        at: 1_790_000_000,
        fileName: 'copy-1.pdf',
        channel: 'Gmail',
      });
  });

  it('falls back to the owner name without an e-mail', () => {
    expect(firstSeenEvent(lane('copy-2', { firstSeenAt: 5, ownerEmail: null, ownerName: 'build-host' }))?.actor).toBe('build-host');
  });
});

describe('buildLineageLanes on a Gmail file', () => {
  const file = overview();   // last modified 10 Sep 2026, 12:54

  it('draws only the Gmail lane while the lineage loads or after it failed', () => {
    const lanes = buildLineageLanes(file, null);
    expect(lanes.map(l => l.key)).toEqual(['gmail-origin']);
    expect(showsNoRelatedFiles(lanes)).toBe(true);
  });

  it('folds the self lane into the Gmail lane: not drawn, no First Seen, its events after "Attached to Email"', () => {
    const older = file.lastModifiedAt! - 86_400;
    const lanes = buildLineageLanes(file, lineage({
      lanes: [lane('copy-0', { self: true, driveType: 'GMAIL', channelLabel: 'Gmail', firstSeenAt: older - 60 })],
      events: [
        event({ laneKey: 'copy-0', typeLabel: 'Downloaded', occurredAt: older + 10 }),
        event({ laneKey: 'copy-0', typeLabel: 'Opened', occurredAt: older }),
      ],
    }));
    expect(lanes).toHaveLength(1);
    expect(lanes[0].key).toBe('gmail-origin');
    // Both events are older than the attach time and still follow it, oldest first.
    expect(types(lanes[0].events)).toEqual(['Attached to Email', 'Opened', 'Downloaded']);
    expect(lanes[0].events[1]).toMatchObject({ fileName: 'copy-0.pdf', channel: 'Gmail', actor: 'sam', actorEmail: 'sam@example.com' });
    expect(showsNoRelatedFiles(lanes)).toBe(true);
  });

  it('puts unmatched events on the Gmail lane with File and Channel null, in API order on ties', () => {
    const lanes = buildLineageLanes(file, lineage({
      lanes: [lane('copy-0', { self: true })],
      events: [
        event({ laneKey: null, typeLabel: 'Renamed', occurredAt: 100 }),
        event({ laneKey: 'copy-0', typeLabel: 'Moved', occurredAt: 100 }),
        event({ laneKey: 'no-such-lane', typeLabel: null, actor: 'system', occurredAt: null }),
      ],
    }));
    const gmail = lanes[0];
    expect(types(gmail.events)).toEqual(['Attached to Email', 'Renamed', 'Moved', 'Event']);
    expect(gmail.events[1]).toMatchObject({ fileName: null, channel: null });
    expect(gmail.events[3]).toMatchObject({ actor: 'system', actorEmail: null, at: null, fileName: null, channel: null });
  });

  it('draws the other lanes after the Gmail lane, in API order, each with its First Seen', () => {
    const lanes = buildLineageLanes(file, lineage({
      lanes: [
        lane('copy-0', { firstSeenAt: 2_000, fileName: 'offer.docx' }),
        lane('copy-1', { self: true, firstSeenAt: 1_000 }),
        lane('copy-2', { firstSeenAt: 3_000, fileName: 'offer.pdf' }),
      ],
    }));
    expect(lanes.map(l => [l.key, l.fileName, l.own])).toEqual([
      ['gmail-origin', 'travel-itinerary.pdf', true],
      ['copy-0', 'offer.docx', false],
      ['copy-2', 'offer.pdf', false],
    ]);
    expect(lanes[1].icon).toBe('GOOGLE_DRIVE');
    expect(types(lanes[1].events)).toEqual(['First Seen']);
    expect(lanes[1].events[0].actor).toBe('pat');
    expect(types(lanes[0].events)).toEqual(['Attached to Email']);
  });

  it('hides the footer when a lane besides the file is drawn, even one the vendor calls the origin', () => {
    // A copy with is_origin true but another file id arrives as self: false.
    const lanes = buildLineageLanes(file, lineage({ lanes: [lane('copy-0', { self: false })] }));
    expect(lanes).toHaveLength(2);
    expect(showsNoRelatedFiles(lanes)).toBe(false);
  });

  it('keeps only the Gmail lane when lineage is unavailable', () => {
    const lanes = buildLineageLanes(file, lineage({ available: false }));
    expect(lanes.map(l => l.key)).toEqual(['gmail-origin']);
    expect(showsNoRelatedFiles(lanes)).toBe(true);
  });
});

describe('buildLineageLanes on a Drive file', () => {
  const file = driveOverview();

  it('draws nothing while the lineage loads', () => {
    expect(isGmail(file)).toBe(false);
    expect(buildLineageLanes(file, null)).toEqual([]);
  });

  it('keeps the API order, with the self lane in place as the own lane', () => {
    const lanes = buildLineageLanes(file, lineage({
      lanes: [
        lane('copy-0', { driveType: 'GMAIL', firstSeenAt: 100 }),
        lane('copy-1', { self: true, firstSeenAt: 200 }),
        lane('tl-0', { fileName: null, ownerName: 'Sam Lee', driveType: null, os: 'MAC', firstSeenAt: null }),
      ],
    }));
    expect(lanes.map(l => [l.key, l.own, l.icon])).toEqual([
      ['copy-0', false, 'GMAIL'],
      ['copy-1', true, 'GOOGLE_DRIVE'],
      ['tl-0', false, 'MAC'],
    ]);
    expect(lanes[2]).toMatchObject({ fileName: '-', owner: 'Sam Lee', events: [] });
    expect(showsNoRelatedFiles(lanes)).toBe(false);
  });

  it('orders events oldest first, untimed last, First Seen first on ties', () => {
    const lanes = buildLineageLanes(file, lineage({
      lanes: [lane('copy-0', { self: true, firstSeenAt: 500 })],
      events: [
        event({ laneKey: 'copy-0', typeLabel: 'Untimed', occurredAt: null }),
        event({ laneKey: 'copy-0', typeLabel: 'Later', occurredAt: 900 }),
        event({ laneKey: 'copy-0', typeLabel: 'Tie', occurredAt: 500 }),
        event({ laneKey: 'copy-0', typeLabel: 'Earlier', occurredAt: 100 }),
      ],
    }));
    expect(types(lanes[0].events)).toEqual(['Earlier', 'First Seen', 'Tie', 'Later', 'Untimed']);
  });

  it('puts unmatched events on the own lane, with File and Channel null', () => {
    const lanes = buildLineageLanes(file, lineage({
      lanes: [lane('copy-0'), lane('copy-1', { self: true })],
      events: [event({ laneKey: null, typeLabel: 'Shared', occurredAt: 10 }), event({ laneKey: 'copy-0', typeLabel: 'Opened', occurredAt: 20 })],
    }));
    expect(types(lanes[0].events)).toEqual(['Opened']);
    expect(lanes[0].events[0]).toMatchObject({ fileName: 'copy-0.pdf', channel: 'Google Drive' });
    expect(types(lanes[1].events)).toEqual(['Shared']);
    expect(lanes[1].events[0]).toMatchObject({ fileName: null, channel: null });
  });

  it('puts unmatched events on the first lane when none is the own lane, and drops them without lanes', () => {
    const unmatched = [event({ typeLabel: 'Shared', occurredAt: 10 })];
    const lanes = buildLineageLanes(file, lineage({ lanes: [lane('copy-0'), lane('copy-1')], events: unmatched }));
    expect(types(lanes[0].events)).toEqual(['Shared']);
    expect(lanes[1].events).toEqual([]);
    expect(buildLineageLanes(file, lineage({ events: unmatched }))).toEqual([]);
  });

  it('shows the footer with only the own lane, and with no lane at all', () => {
    expect(showsNoRelatedFiles(buildLineageLanes(file, lineage({ lanes: [lane('copy-0', { self: true })] })))).toBe(true);
    expect(showsNoRelatedFiles(buildLineageLanes(file, lineage()))).toBe(true);
  });
});

describe('visibleLanes', () => {
  const lanes = buildLineageLanes(overview(), lineage({
    lanes: [lane('copy-0', { firstSeenAt: 100, fileName: 'offer.docx', ownerEmail: 'sam@example.com' })],
    events: [event({ laneKey: 'copy-0', typeLabel: 'Downloaded', actor: 'lee@example.com', occurredAt: 200 })],
  }));

  it('keeps every event for a blank query', () => {
    expect(visibleLanes(lanes, '  ')).toBe(lanes);
  });

  it.each([
    ['attached', ['Attached to Email'], []],
    ['FIRST', [], ['First Seen']],
    ['lee@', [], ['Downloaded']],
    ['sam', [], ['First Seen']],
    ['OFFER.DOCX', [], ['First Seen', 'Downloaded']],
    ['itinerary', ['Attached to Email'], []],
  ])('matches "%s" on type, actor, e-mail or file name', (query, gmailEvents, copyEvents) => {
    const shown = visibleLanes(lanes, query);
    expect(shown.map(l => l.key)).toEqual(['gmail-origin', 'copy-0']);
    expect(types(shown[0].events)).toEqual(gmailEvents);
    expect(types(shown[1].events)).toEqual(copyEvents);
  });

  it('keeps the lanes drawn when nothing matches, and the footer reads the unfiltered lanes', () => {
    const shown = visibleLanes(lanes, 'nothing-like-this');
    expect(shown.every(l => l.events.length === 0)).toBe(true);
    expect(showsNoRelatedFiles(lanes)).toBe(false);
    const own = buildLineageLanes(driveOverview(), lineage({ lanes: [lane('copy-0', { self: true, firstSeenAt: 1 })] }));
    expect(showsNoRelatedFiles(own)).toBe(true);
    expect(visibleLanes(own, 'nothing-like-this')[0].events).toEqual([]);
  });
});
