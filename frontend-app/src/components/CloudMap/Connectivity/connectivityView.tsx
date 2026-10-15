import React from 'react';
import { AWS_ICONS, NODE_TYPE_DATA, TAILWIND_COLORS } from '../../../types/cloudmap';

/**
 * Presentation shared by the two views of the connectivity model.
 *
 * <p>The Cloud Map's pan/zoom canvas and the cost drawer's compact flow draw the same resources
 * from the same {@link connectivityFlowModel} output. These three helpers are *moved* here rather
 * than copied, so there is one glyph table and one tooltip builder for both: a second copy is how
 * two views of one account come to disagree about what a node is.
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
