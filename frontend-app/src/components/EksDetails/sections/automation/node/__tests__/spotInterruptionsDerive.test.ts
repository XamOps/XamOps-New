import { describe, expect, it } from 'vitest';
import {
  DASH,
  eventRows,
  handlingView,
  kindParts,
  matchHandling,
  matchNotice,
  metricCells,
  noticeView,
  nowSentence,
  pageState,
  recoveryMedianSeconds,
  recoveryText,
  recoveryView,
  drainWord,
  NOT_MEASURABLE_REASON,
} from '../spotInterruptionsDerive';
import { MIXED, NOW, dto, event, isoAgo } from './spotInterruptionsFixtures';

const FLEET = { totalNodes: 6, spotNodes: 2, spotSharePct: 33, collectedAt: isoAgo(60_000) };
const unconfirmed = () => event({ noticeKind: 'unconfirmed' });
const rebalance = () => event({ noticeKind: 'rebalance', sources: ['karpenter-event'], rebalanceNoticedAt: isoAgo(60_000) });
const interruption = (over = {}) => event({ noticeKind: 'interruption', sources: ['nth-taint'], ...over });

describe('kindParts and nowSentence (AC27: only a notice is called an interruption)', () => {
  it('unconfirmed only: removals with no cause, never "interruption"', () => {
    const events = [unconfirmed(), unconfirmed()];
    expect(kindParts(events)).toEqual(['2 Spot nodes removed · cause unconfirmed']);
    const s = nowSentence(events, FLEET, 30);
    expect(s).toBe('In the last 30 days: 2 Spot nodes removed · cause unconfirmed · Spot is 33% of nodes');
    expect(s).not.toMatch(/interruption/i);
  });

  it('rebalance only', () => {
    const s = nowSentence([rebalance()], FLEET, 30);
    expect(s).toBe('In the last 30 days: 1 rebalance recommendation · Spot is 33% of nodes');
    expect(s).not.toMatch(/interruption/i);
  });

  it('interruption only, and rebalance → interruption counted once as an interruption notice', () => {
    expect(kindParts([interruption()])).toEqual(['1 interruption notice']);
    expect(kindParts([interruption({ rebalanceNoticedAt: isoAgo(120_000) })])).toEqual(['1 interruption notice']);
    expect(kindParts([interruption(), interruption()])).toEqual(['2 interruption notices']);
  });

  it('mixed, with an unknown future kind counted as other', () => {
    expect(nowSentence([interruption(), rebalance(), unconfirmed()], FLEET, 30)).toBe(
      'In the last 30 days: 1 interruption notice · 1 rebalance recommendation · 1 Spot node removed · cause unconfirmed · Spot is 33% of nodes',
    );
    expect(kindParts([event({ noticeKind: 'capacity-change' })])).toEqual(['1 other Spot event']);
  });

  it('leaves the share out when it is unknown', () => {
    expect(nowSentence([unconfirmed()], null, 7)).toBe('In the last 7 days: 1 Spot node removed · cause unconfirmed');
    expect(nowSentence([unconfirmed()], { ...FLEET, spotSharePct: null }, 7)).toBe('In the last 7 days: 1 Spot node removed · cause unconfirmed');
  });

  it('no events: no spot nodes, or none recorded', () => {
    expect(nowSentence([], { ...FLEET, spotNodes: 0, spotSharePct: 0 }, 30)).toBe('No spot nodes in this cluster');
    expect(nowSentence([], FLEET, 90)).toBe('No interruptions recorded in the last 90 days');
    expect(nowSentence([], null, 30)).toBe('No interruptions recorded in the last 30 days');
  });
});

describe('pageState (AC11)', () => {
  it('maps every read to one state', () => {
    expect(pageState({ kind: 'loading' })).toBe('loading');
    expect(pageState({ kind: 'error', message: 'x' })).toBe('error');
    expect(pageState({ kind: 'ok', data: dto({ coverage: { agentSnapshotAt: null, automationReportAt: null, handlingNow: null }, fleet: null }) })).toBe('not-monitored');
    expect(pageState({ kind: 'ok', data: dto({ fleet: { ...FLEET, spotNodes: 0, spotSharePct: 0 } }) })).toBe('no-spot');
    expect(pageState({ kind: 'ok', data: dto() })).toBe('none');
    expect(pageState({ kind: 'ok', data: dto({ events: [unconfirmed()] }) })).toBe('events');
    // Events always show, even with no coverage or no spot nodes now.
    expect(pageState({ kind: 'ok', data: dto({ events: [unconfirmed()], fleet: { ...FLEET, spotNodes: 0 } }) })).toBe('events');
  });
});

describe('handlingView (AC17)', () => {
  it('maps each mode to its mark', () => {
    expect(handlingView({ mode: 'live', reason: null })).toMatchObject({ key: 'live', state: 'healthy', label: 'Live' });
    expect(handlingView({ mode: 'simulate', reason: null })).toMatchObject({ key: 'simulate', state: 'simulated', label: 'Simulated (dry run)' });
    expect(handlingView({ mode: 'off', reason: 'handleSpotInterruptions is off' })).toMatchObject({
      key: 'off', state: 'not-set-up', label: 'Off', reason: 'handleSpotInterruptions is off',
    });
    expect(handlingView(null)).toMatchObject({ key: 'unknown', state: 'unknown', label: 'Not reported' });
    expect(handlingView({ mode: 'unknown', reason: null })).toMatchObject({ key: 'unknown', label: 'Not reported' });
  });

  it('not-eligible is "Not handled by XamOps" with its reason, never "Off"', () => {
    const v = handlingView({ mode: 'not-eligible', reason: 'live: not in the drain list' });
    expect(v).toMatchObject({ key: 'not-eligible', state: 'unknown', label: 'Not handled by XamOps', reason: 'live: not in the drain list' });
    expect(v.label).not.toBe('Off');
  });

  it('filters rows by handling and notice kind', () => {
    const rows = eventRows(MIXED);
    expect(rows.filter((r) => matchHandling(r, 'simulate'))).toHaveLength(1);
    expect(rows.filter((r) => matchHandling(r, 'not-eligible'))).toHaveLength(1);
    expect(rows.filter((r) => matchHandling(r, 'unknown'))).toHaveLength(1);
    expect(rows.filter((r) => matchHandling(r, 'off'))).toHaveLength(0);
    expect(rows.filter((r) => matchNotice(r, 'unconfirmed'))).toHaveLength(1);
  });
});

describe('noticeView and drainWord', () => {
  it('words each kind as the Notice column does', () => {
    expect(noticeView(interruption())).toMatchObject({ state: 'attention', label: '2-min notice' });
    expect(noticeView(interruption({ rebalanceNoticedAt: isoAgo(1) }))).toMatchObject({ label: 'Rebalance → 2-min notice' });
    expect(noticeView(rebalance())).toMatchObject({ state: 'attention', label: 'Rebalance recommendation' });
    const u = noticeView(unconfirmed());
    expect(u).toMatchObject({ state: 'unverified', label: 'Removed · cause unconfirmed' });
    expect(u.label).not.toMatch(/interruption/i);
  });

  it('words the drain outcome', () => {
    expect(drainWord(null)).toBeNull();
    expect(drainWord({ status: 'simulated', reason: null, dryRun: true, finishedAt: null })).toBe('Simulated drain');
    expect(drainWord({ status: 'succeeded', reason: null, dryRun: false, finishedAt: null })).toBe('Drained');
    expect(drainWord({ status: 'refused', reason: 'pdb', dryRun: false, finishedAt: null })).toBe('Drain refused');
  });
});

describe('recovery (AC23, AC26)', () => {
  const rec = (lowerSeconds: number | null, upperSeconds: number | null, recoveredByFirstSighting = false) => ({
    status: 'recovered' as const, lowerSeconds, upperSeconds, recoveredByFirstSighting, observedAt: null,
  });

  it('is a range, "≤ N min" from zero, or recovered by first sighting; never "0 min"', () => {
    expect(recoveryText(rec(0, 300))).toBe('≤ 5 min');
    expect(recoveryText(rec(180, 480))).toBe('3–8 min');
    expect(recoveryText(rec(0, 0, true))).toBe('Recovered by first sighting');
    expect(recoveryText(rec(0, 20))).toBe('≤ 1 min');
    for (const r of [rec(0, 300), rec(180, 480), rec(0, 0, true), rec(0, 20), rec(30, 330)]) {
      expect(recoveryText(r)).not.toMatch(/(^|\D)0 min/);
    }
    expect(recoveryText({ ...rec(null, null), status: 'tracking' })).toBeNull();
  });

  it('not-measurable is a mark with the AC26 reason, never a duration', () => {
    const v = recoveryView(event({ recovery: { status: 'not-measurable', lowerSeconds: null, upperSeconds: null, recoveredByFirstSighting: false, observedAt: null } }));
    expect(v).toEqual({ kind: 'mark', mark: { state: 'unknown', label: 'Not measurable', reason: NOT_MEASURABLE_REASON } });
  });

  it('dashes what was not observed', () => {
    const status = (s: string) => recoveryView(event({ recovery: { status: s as never, lowerSeconds: null, upperSeconds: null, recoveredByFirstSighting: false, observedAt: null } }));
    expect(status('not-observed')).toEqual({ kind: 'dash', reason: 'Not observed in 6 h' });
    expect(status('no-snapshot')).toEqual({ kind: 'dash', reason: 'No snapshot' });
    expect(status('no-workloads')).toEqual({ kind: 'text', text: 'Nothing to move' });
    expect(status('node-not-removed')).toEqual({ kind: 'text', text: 'Node not removed' });
    expect(status('tracking')).toMatchObject({ kind: 'mark', mark: { label: 'Tracking' } });
  });

  it('takes the median over measured recoveries only (not-measurable and first sighting excluded)', () => {
    const notMeasurable = event({ recovery: { status: 'not-measurable', lowerSeconds: null, upperSeconds: null, recoveredByFirstSighting: false, observedAt: null } });
    const first = event({ recovery: rec(0, 0, true) });
    expect(recoveryMedianSeconds([notMeasurable, first])).toBeNull();
    expect(recoveryMedianSeconds([event({ recovery: rec(0, 300) }), notMeasurable, first])).toBe(300);
    expect(recoveryMedianSeconds([event({ recovery: rec(0, 300) }), event({ recovery: rec(0, 600) })])).toBe(450);
    expect(recoveryMedianSeconds([event({ recovery: rec(0, 120) }), event({ recovery: rec(0, 300) }), event({ recovery: rec(0, 900) })])).toBe(300);
  });
});

describe('metricCells', () => {
  it('shows real values for a read with events, split by kind', () => {
    const c = metricCells(dto({ events: MIXED }), NOW);
    expect(c.share).toEqual({ value: '33%', sub: '2 of 6 nodes · as of 2 min ago' });
    expect(c.events.value).toBe('3');
    expect(c.events.sub).toBe('1 interruption notice · 1 rebalance recommendation · 1 Spot node removed · cause unconfirmed');
    expect(c.recovery.value).toBe('≤ 8 min');
    expect(c.handling.mark.label).toBe('Simulated (dry run)');
  });

  it('never calls an unconfirmed-only window an interruption (AC27)', () => {
    const c = metricCells(dto({ events: [unconfirmed(), unconfirmed()] }), NOW);
    expect(`${c.events.value} ${c.events.sub}`).not.toMatch(/interruption/i);
  });

  it('dashes what was not observed, and shows 0 only when recording looked and found none', () => {
    const none = metricCells(dto(), NOW);
    expect(none.events).toEqual({ value: '0', sub: 'None in the window' });
    expect(none.recovery).toEqual({ value: DASH, sub: 'No recovery measured yet' });
    const off = metricCells(dto({ recordingEnabled: false }), NOW);
    expect(off.events).toEqual({ value: DASH, sub: 'Recording is off' });
    expect(metricCells(dto({ fleet: null }), NOW).share).toEqual({ value: DASH, sub: 'No XamOps agent snapshot' });
    expect(metricCells(dto({ fleet: { ...FLEET, totalNodes: 0, spotNodes: 0, spotSharePct: null } }), NOW).share.value).toBe(DASH);
  });

  it('handling now: not reported, with the report age or its absence', () => {
    const stale = metricCells(dto({ coverage: { agentSnapshotAt: isoAgo(60_000), automationReportAt: isoAgo(12 * 60_000), handlingNow: null } }), NOW);
    expect(stale.handling).toMatchObject({ mark: { label: 'Not reported' }, sub: 'Automation agent report 12 min old' });
    const absent = metricCells(dto({ coverage: { agentSnapshotAt: isoAgo(60_000), automationReportAt: null, handlingNow: null } }), NOW);
    expect(absent.handling.sub).toBe('No automation agent report');
  });
});

describe('eventRows', () => {
  it('builds the view model, with a dash for uncaptured workloads and "+" when truncated', () => {
    const rows = eventRows([
      event({ workloads: null, podCount: null, workloadsTruncated: null }),
      event({ workloadsTruncated: true }),
    ]);
    expect(rows[0].workloads).toBeNull();
    expect(rows[1].workloads).toBe('1+');
    expect(rows[1].node).toBe('ip-10-0-1-23');
  });
});
