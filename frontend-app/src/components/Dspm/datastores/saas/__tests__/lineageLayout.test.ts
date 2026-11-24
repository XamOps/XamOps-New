import { describe, expect, it } from 'vitest';
import type { LineageEventView, LineageLaneView } from '../lineageModel';
import {
  EVENT_GAP, EVENT_H, EVENT_W, LANE_GAP, LANE_H, LANE_LABEL_W, LINEAGE_PAD, layoutLineage, lineageFitZoom,
} from '../lineageLayout';

const card = (key: string): LineageEventView => ({
  key, type: 'First Seen', actor: 'pat', actorEmail: null, internal: false, at: null, fileName: null, channel: null,
});
const laneOf = (key: string, events: number): LineageLaneView => ({
  key, icon: null, fileName: `${key}.pdf`, owner: 'Pat Example', own: false,
  events: Array.from({ length: events }, (_, i) => card(`${key}:${i}`)),
});

describe('layoutLineage', () => {
  it('places one row per lane and its events left to right', () => {
    const layout = layoutLineage([laneOf('a', 2), laneOf('b', 0), laneOf('c', 1)]);
    expect(layout.rows).toEqual([
      { laneKey: 'a', y: 12 },
      { laneKey: 'b', y: 12 + 64 + 8 },
      { laneKey: 'c', y: 12 + 2 * (64 + 8) },
    ]);
    expect(layout.cards).toEqual([
      { eventKey: 'a:0', laneKey: 'a', x: 12 + 240 + 8, y: 12 + 8 },
      { eventKey: 'a:1', laneKey: 'a', x: 12 + 240 + 8 + 184, y: 12 + 8 },
      { eventKey: 'c:0', laneKey: 'c', x: 12 + 240 + 8, y: 12 + 2 * 72 + 8 },
    ]);
  });

  it('sizes the drawing for the busiest lane', () => {
    const layout = layoutLineage([laneOf('a', 3), laneOf('b', 1)]);
    expect(layout.width).toBe(2 * LINEAGE_PAD + LANE_LABEL_W + EVENT_GAP + 3 * (EVENT_W + EVENT_GAP));
    expect(layout.height).toBe(2 * LINEAGE_PAD + 2 * LANE_H + LANE_GAP);
    expect(layout.cards[0].y - layout.rows[0].y).toBe((LANE_H - EVENT_H) / 2);
  });

  it('leaves one event slot for lanes without events', () => {
    const layout = layoutLineage([laneOf('a', 0)]);
    expect(layout.width).toBe(2 * 12 + 240 + 8 + 184);
    expect(layout.height).toBe(2 * 12 + 64);
    expect(layout.cards).toEqual([]);
  });

  it('gives an empty drawing without lanes', () => {
    expect(layoutLineage([])).toEqual({ width: 0, height: 0, rows: [], cards: [] });
  });

  it('is deterministic', () => {
    const lanes = [laneOf('a', 4), laneOf('b', 2), laneOf('c', 7)];
    expect(layoutLineage(lanes)).toEqual(layoutLineage(lanes));
  });
});

describe('lineageFitZoom', () => {
  it('is 1 when the drawing fits, and when the viewport has no width (jsdom)', () => {
    expect(lineageFitZoom(600, 800)).toBe(1);
    expect(lineageFitZoom(776, 800)).toBe(1);
    expect(lineageFitZoom(5_000, 0)).toBe(1);
    expect(lineageFitZoom(0, 800)).toBe(1);
  });

  it('fits a wider drawing in 5% steps, rounding down', () => {
    expect(lineageFitZoom(1_000, 800)).toBe(0.75);   // 776 / 1000 = 0.776
    expect(lineageFitZoom(1_000, 574)).toBe(0.55);   // exactly 0.55
    expect(lineageFitZoom(1_420, 850)).toBe(0.55);   // 0.5816
    expect(lineageFitZoom(800, 799)).toBe(0.95);
  });

  it('never opens below 20%', () => {
    expect(lineageFitZoom(20_000, 800)).toBe(0.2);
  });
});
