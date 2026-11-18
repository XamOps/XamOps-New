import React from 'react';
import { ZOOM_MAX, ZOOM_MIN, zoomStep } from './zoom';

/** The Access Graph's zoom button style. */
const ZOOM_BUTTON = 'inline-flex h-7 w-7 items-center justify-center rounded-lg border border-[rgba(0,0,0,0.12)] text-sm font-semibold text-[rgba(0,0,0,0.65)] transition-colors hover:bg-[rgba(0,0,0,0.03)] disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent';

interface ZoomControlsProps {
  zoom: number;
  /** Receives the next zoom, already stepped and clamped. */
  onZoom: (zoom: number) => void;
}

/**
 * The console's zoom control, in its order: "+" (Zoom in), the zoom as a whole percent, "−" (Zoom out).
 * Each button is disabled at its bound (20% and 150%).
 */
export const ZoomControls: React.FC<ZoomControlsProps> = ({ zoom, onZoom }) => (
  <div className="flex items-center gap-1">
    <button
      type="button"
      aria-label="Zoom in"
      disabled={zoom >= ZOOM_MAX}
      onClick={() => onZoom(zoomStep(zoom, 1))}
      className={ZOOM_BUTTON}
    >
      +
    </button>
    <span className="w-10 text-center text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.65)]">
      {Math.round(zoom * 100)}%
    </span>
    <button
      type="button"
      aria-label="Zoom out"
      disabled={zoom <= ZOOM_MIN}
      onClick={() => onZoom(zoomStep(zoom, -1))}
      className={ZOOM_BUTTON}
    >
      −
    </button>
  </div>
);
