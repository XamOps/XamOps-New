/**
 * Zoom bounds and steps shared by the Graph View diagram and the lineage lanes (design 6.2). The Access
 * Graph keeps its own (`drawer/accessGraphLayout.ts`).
 */

export const ZOOM_MIN = 0.2;
export const ZOOM_MAX = 1.5;
export const ZOOM_STEP = 0.1;

/**
 * One step in or out, rounded to two decimals so floating-point drift never leaves 0.7999999999999999
 * behind, and clamped to `min`..`max` (20%..150% unless given). A fitted zoom such as 0.55 keeps its second
 * decimal (0.65, 0.45).
 */
export function zoomStep(zoom: number, direction: 1 | -1, min = ZOOM_MIN, max = ZOOM_MAX): number {
  const next = Math.round((zoom + direction * ZOOM_STEP) * 100) / 100;
  return Math.min(max, Math.max(min, next));
}
