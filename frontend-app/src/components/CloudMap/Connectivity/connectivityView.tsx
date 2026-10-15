import React from 'react';
import { Icon } from '@iconify/react';
import { AWS_ICONS, NODE_TYPE_DATA, TAILWIND_COLORS } from '../../../types/cloudmap';
import { flowEdge } from './connectivityFlowModel';

/**
 * Presentation shared by the two views of the connectivity model.
 *
 * <p>The Cloud Map's pan/zoom canvas and the cost drawer's compact flow draw the same resources
 * from the same {@link connectivityFlowModel} output. Everything here is *moved* out of those
 * views rather than copied, so there is one glyph table, one tooltip builder, one card shell, one
 * edge palette and one legend for both: a second copy is how two views of one account come to
 * disagree about what a node is.
 *
 * <p>What is deliberately *not* here: the card bodies. One view's card carries a copy-id button
 * and the other carries money, so a single configurable card would be two cards in a trench coat.
 * The shell, the chips, the edge and the more-affordance are where the disagreement would bite.
 */

/**
 * Service hue for a node type, falling back to a neutral grey.
 *
 * <p>Module-private: `TypeIcon` is the shared surface, and a hue is only meaningful as the
 * background of the initial it draws. Exporting it would invite a third caller to tint something
 * else that colour and then disagree with the glyph when `NODE_TYPE_DATA` gains an entry.
 */
const hueFor = (type: string): string => {
  const info = NODE_TYPE_DATA[type];
  const palette = info ? TAILWIND_COLORS[info.color as keyof typeof TAILWIND_COLORS] : undefined;
  return palette ? palette[500] : '#94a3b8';
};

/**
 * Hover text for a card or a row, one line per line it shows.
 *
 * <p>Both views pack several lines into a bounded width and truncate all of them, so an id, a CIDR
 * or a "type · location · connections" summary is routinely cut mid-word. The native tooltip is the
 * only place that text can be recovered, which means it has to carry what is actually displayed
 * rather than a shorter restatement of it: a title that stops before the line the reader is
 * squinting at is no help.
 *
 * <p>Blank entries are dropped instead of leaving empty lines, so a row with no facts and no state
 * does not open a tooltip padded with gaps.
 */
export const hoverText = (...lines: (string | false | null | undefined)[]): string =>
  lines.filter((line): line is string => Boolean(line && line.trim())).join('\n');

/**
 * The service icon, or a hue-tinted initial when there is no artwork for the type.
 *
 * <p>Mirrors what the live canvas does for unknown types rather than repeating one generic AWS mark
 * across every unrecognised node.
 */
export const TypeIcon: React.FC<{ type: string }> = ({ type }) => {
  const src = AWS_ICONS[type];
  if (src) {
    return (
      <img src={src} alt="" aria-hidden="true" width={18} height={18}
        className="flex-shrink-0 object-contain" />
    );
  }
  return (
    <span
      aria-hidden="true"
      className="w-[18px] h-[18px] rounded flex items-center justify-center flex-shrink-0 text-[9px] font-bold text-white"
      style={{ backgroundColor: hueFor(type) }}
    >
      {(type || '?').slice(0, 1).toUpperCase()}
    </span>
  );
};

/*
 * Edge palette.
 *
 * Containment is blue and connections are violet, and that is the only thing telling "this volume
 * is in this subnet" from "this volume attaches to that instance". One palette for both invites
 * reading them as the same claim, so the two colours — and the legend that names them — are shared
 * rather than restated per view.
 */
export const ACCENT = '#4285F4';
export const EDGE_IDLE = '#d7dae0';
export const EDGE_LINK = '#8b5cf6';

/**
 * The shell of a containment node — a region, VPC, subnet, service, resource or the compact
 * flow's focus card.
 *
 * <p>Kept byte-identical across the two views: a selected card is how a reader knows which
 * resource the rest of the panel is about, and two views drawing "selected" differently is the
 * same bug as two views disagreeing about a glyph.
 */
export const nodeClass = (selected: boolean): string =>
  [
    'absolute rounded-lg bg-white px-3 py-2 text-left transition-all',
    'flex items-center gap-2.5 outline-none',
    selected
      ? 'border-[1.5px] border-[#4285F4] shadow-[0_2px_10px_rgba(66,133,244,0.18)]'
      : 'border border-[rgba(0,0,0,0.13)] shadow-[0_1px_3px_rgba(0,0,0,0.05)]'
        + ' hover:border-[rgba(0,0,0,0.3)] hover:shadow-[0_2px_8px_rgba(0,0,0,0.08)]',
    'focus-visible:ring-2 focus-visible:ring-[#4285F4] focus-visible:ring-offset-1',
  ].join(' ');

/**
 * The violet shell of a peer card: border, radius, background, shadow — and nothing else.
 *
 * <p>Positioning and size are the caller's, because the two views lay their peers out
 * differently: the canvas places a 264 × 52 card absolutely on a pan surface, the drawer a taller
 * one that has to fit a money sentence. What they must agree on is that a connection looks violet,
 * a dashed border means inferred, and emerald means "id just copied".
 */
export const peerCardClass = (o: { dashed: boolean; confirmed?: boolean }): string =>
  [
    'rounded-lg bg-white',
    o.confirmed
      ? 'border-[1.5px] border-emerald-500'
      : o.dashed
        ? 'border border-dashed border-[#c4b5fd]'
        : 'border border-[#ddd6fe]',
    'shadow-[0_1px_3px_rgba(0,0,0,0.05)]',
  ].join(' ');

/** Running/available in green, stopped/failed in red, anything else neutral. */
export const StateChip: React.FC<{ state: string }> = ({ state }) => {
  const s = state.toLowerCase();
  const good = /run|avail|active|in-use|inuse|healthy|enabled|attached/.test(s);
  const bad = /stop|fail|error|terminat|unhealth|delet|expir|disabled/.test(s);
  return (
    <span
      className={[
        'text-[9px] px-1 py-[1px] rounded font-medium',
        good ? 'bg-emerald-50 text-emerald-700'
          : bad ? 'bg-red-50 text-red-700'
            : 'bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.55)]',
      ].join(' ')}
    >
      {state}
    </span>
  );
};

/**
 * The row that closes a capped column, saying how many rows it is standing in for.
 *
 * <p>`expanded` is optional and the Cloud Map's five call sites omit it, so no `aria-expanded`
 * appears there and that page's DOM is unchanged by the move. The drawer passes it because its
 * reveal is a disclosure of rows already in the payload rather than a jump to more data.
 */
export const MoreButton: React.FC<{
  row: { x: number; y: number; w: number; h: number; hidden: number };
  onClick: () => void;
  noun: string;
  expanded?: boolean;
}> = ({ row, onClick, noun, expanded }) => (
  <button
    type="button"
    onClick={onClick}
    aria-expanded={expanded}
    className="absolute rounded-lg border border-dashed border-[rgba(0,0,0,0.18)] bg-[rgba(0,0,0,0.015)] text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.75)] transition-colors flex items-center justify-center gap-1.5 outline-none focus-visible:ring-2 focus-visible:ring-[#4285F4]"
    style={{ left: row.x, top: row.y, width: row.w, height: row.h }}
  >
    <Icon icon="mdi:plus-circle-outline" width={12} />
    Show {row.hidden} more {row.hidden === 1 ? noun : `${noun}s`}
  </button>
);

/**
 * One edge, from a parent's right edge to a child's left edge.
 *
 * <p>The widths and dash patterns are the whole signal — a selected containment edge is the
 * thickest line on the canvas because it is the branch the reader opened, and a dashed one is a
 * relationship nobody stated. Wrapping the single `<path>` keeps that mapping in one place instead
 * of fifteen call sites each spelling out a colour and a width.
 */
export const FlowEdge: React.FC<{
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  trunkX: number;
  kind: 'contains' | 'connects';
  selected?: boolean;
  dashed?: boolean;
}> = ({ x1, y1, x2, y2, trunkX, kind, selected, dashed }) => {
  const connects = kind === 'connects';
  return (
    <path
      d={flowEdge(x1, y1, x2, y2, trunkX)}
      fill="none"
      stroke={connects ? EDGE_LINK : selected ? ACCENT : EDGE_IDLE}
      strokeWidth={connects ? 1.5 : selected ? 1.8 : 1.2}
      strokeDasharray={dashed ? (connects ? '4 3' : '3 3') : undefined}
    />
  );
};

/**
 * The three swatches, so the two colours and the dashes are not a puzzle.
 *
 * <p>Unpositioned: the canvas floats it over the bottom-left corner of a pan surface and the
 * drawer sets it below the flow, so the layout classes come in from the caller and the words stay
 * here — the legend is the only place the palette is explained, and two wordings would be two
 * explanations of one colour.
 */
export const EdgeLegend: React.FC<{ className?: string }> = ({ className }) => (
  <div className={className}>
    <span className="flex items-center gap-1.5">
      <span className="w-4 h-0 border-t-[1.5px]" style={{ borderColor: ACCENT }} />
      contains
    </span>
    <span className="flex items-center gap-1.5">
      <span className="w-4 h-0 border-t-[1.5px]" style={{ borderColor: EDGE_LINK }} />
      connects to
    </span>
    <span className="flex items-center gap-1.5">
      <span className="w-4 h-0 border-t-[1.5px] border-dashed" style={{ borderColor: EDGE_LINK }} />
      inferred
    </span>
  </div>
);
