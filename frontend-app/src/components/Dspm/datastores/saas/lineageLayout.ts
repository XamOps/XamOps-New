/**
 * Positions of the lineage drawing (design 4.11, 6.6): one row per lane, its label box on the left and its
 * event cards left to right. Pure, so `LineageCanvas` only scales what this returns. The sizes are fixed
 * (DA17); the console's are unseen.
 */

import type { LineageLaneView } from './lineageModel';
import { ZOOM_MIN } from '../ui/zoom';

export const LANE_LABEL_W = 240;
export const LANE_H = 64;
export const LANE_GAP = 8;
export const EVENT_W = 176;
export const EVENT_H = 48;
export const EVENT_GAP = 8;
export const LINEAGE_PAD = 12;

export interface LineageLayout {
  width: number;
  height: number;
  rows: { laneKey: string; y: number }[];
  cards: { eventKey: string; laneKey: string; x: number; y: number }[];
}

/** The x of a lane's `index`-th event slot (the first slot also holds "No recorded activity"). */
export const eventSlotX = (index: number) => LINEAGE_PAD + LANE_LABEL_W + EVENT_GAP + index * (EVENT_W + EVENT_GAP);

/**
 * Row `i` at `y = PAD + i × (LANE_H + LANE_GAP)`, its label box at `x = PAD`; event `j` of the row at
 * `eventSlotX(j)`, centred in the row. The width leaves room for the busiest lane, and for one slot at
 * least; no lanes give an empty 0 × 0 drawing.
 */
export function layoutLineage(lanes: LineageLaneView[]): LineageLayout {
  if (lanes.length === 0) return { width: 0, height: 0, rows: [], cards: [] };
  const rows: LineageLayout['rows'] = [];
  const cards: LineageLayout['cards'] = [];
  let busiest = 1;
  lanes.forEach((lane, i) => {
    const y = LINEAGE_PAD + i * (LANE_H + LANE_GAP);
    rows.push({ laneKey: lane.key, y });
    busiest = Math.max(busiest, lane.events.length);
    lane.events.forEach((event, j) => {
      cards.push({ eventKey: event.key, laneKey: lane.key, x: eventSlotX(j), y: y + (LANE_H - EVENT_H) / 2 });
    });
  });
  return {
    width: 2 * LINEAGE_PAD + LANE_LABEL_W + EVENT_GAP + busiest * (EVENT_W + EVENT_GAP),
    height: 2 * LINEAGE_PAD + lanes.length * LANE_H + (lanes.length - 1) * LANE_GAP,
    rows,
    cards,
  };
}

/**
 * The opening zoom of a non-Gmail lineage (A29): 1 when the drawing fits, or when the viewport has no
 * width (jsdom); else the largest multiple of 0.05 not above `(viewport − 2 × PAD) / width`, clamped to
 * `[ZOOM_MIN, 1]`.
 */
export function lineageFitZoom(layoutWidth: number, viewportWidth: number): number {
  if (layoutWidth <= 0 || viewportWidth <= 0) return 1;
  const ratio = (viewportWidth - 2 * LINEAGE_PAD) / layoutWidth;
  if (ratio >= 1) return 1;
  // The epsilon keeps an exact multiple (0.55 computed as 0.5499999…) on its own step.
  const stepped = Math.floor(ratio * 20 + 1e-9) / 20;
  return Math.min(1, Math.max(ZOOM_MIN, stepped));
}
