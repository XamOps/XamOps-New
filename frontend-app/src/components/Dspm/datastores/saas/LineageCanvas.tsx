import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Popover } from '../ui/Popover';
import { ZoomControls } from '../ui/ZoomControls';
import type { LineageEventView, LineageLaneView } from './lineageModel';
import {
  EVENT_H, EVENT_W, LANE_H, LINEAGE_PAD, eventSlotX, layoutLineage, lineageFitZoom,
} from './lineageLayout';
import { EventCard, EventDetails, LaneLabel } from './LineageParts';

interface Props {
  /** The lanes to draw, already searched (`visibleLanes`). */
  lanes: LineageLaneView[];
  /** Non-Gmail files: the zoom control, opening at the fit-to-width zoom (A29). */
  zoomable: boolean;
  /** Inside the full-screen frame: the viewport takes the screen's height. */
  fullscreen: boolean;
}

/**
 * The lineage drawing (design 4.11, 6.6): lane rows and event cards at the positions of `layoutLineage`,
 * scaled inside a scrolling viewport. The zoom control and the event popover sit in the unscaled wrapper,
 * so they keep their size at any zoom. The popover opens under its card and closes on any zoom change and
 * any scroll of the viewport, so it never drifts from the card.
 */
const LineageCanvas: React.FC<Props> = ({ lanes, zoomable, fullscreen }) => {
  const container = useRef<HTMLDivElement>(null);
  const viewport = useRef<HTMLDivElement>(null);
  const layout = useMemo(() => layoutLineage(lanes), [lanes]);

  const [zoom, setZoom] = useState(1);
  const fitted = useRef(false);
  useLayoutEffect(() => {
    // The opening zoom, once the drawing first exists; a search never refits it.
    if (fitted.current || layout.width === 0) return;
    fitted.current = true;
    setZoom(zoomable ? lineageFitZoom(layout.width, viewport.current?.clientWidth ?? 0) : 1);
  }, [layout.width, zoomable]);

  const [openEvent, setOpenEvent] = useState<{ event: LineageEventView; anchor: HTMLElement } | null>(null);
  const closeEvent = useCallback(() => setOpenEvent(null), []);
  // Another search draws other cards: the open one may be gone.
  useEffect(() => { setOpenEvent(null); }, [lanes]);

  const onZoom = (next: number) => {
    setZoom(next);
    setOpenEvent(null);
  };

  const cards = new Map(layout.cards.map(card => [card.eventKey, card]));

  return (
    <div ref={container} className="relative">
      <div
        ref={viewport}
        role="group"
        aria-label="Lineage lanes"
        onScroll={closeEvent}
        className={`overflow-auto rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.015)] ${
          fullscreen ? 'h-[calc(100vh-14rem)]' : 'max-h-[420px]'
        }`}
      >
        <div className="relative" style={{ width: layout.width * zoom, height: layout.height * zoom }}>
          <div
            className="absolute left-0 top-0"
            style={{ width: layout.width, height: layout.height, transform: `scale(${zoom})`, transformOrigin: '0 0' }}
          >
            {lanes.map((lane, i) => {
              const y = layout.rows[i].y;
              return (
                <div
                  key={lane.key}
                  data-lane={lane.key}
                  data-own={lane.own || undefined}
                  className={`absolute rounded-lg border ${lane.own ? 'border-rose-100 bg-rose-50' : 'border-[rgba(0,0,0,0.06)] bg-white'}`}
                  style={{ left: LINEAGE_PAD, top: y, width: layout.width - 2 * LINEAGE_PAD, height: LANE_H }}
                >
                  <LaneLabel lane={lane} />
                  {lane.events.length === 0 && (
                    <span
                      className="absolute flex items-center text-[11px] text-[rgba(0,0,0,0.4)]"
                      style={{ left: eventSlotX(0) - LINEAGE_PAD, top: (LANE_H - EVENT_H) / 2, width: EVENT_W, height: EVENT_H }}
                    >
                      No recorded activity
                    </span>
                  )}
                </div>
              );
            })}
            {lanes.flatMap(lane => lane.events.map(event => {
              const card = cards.get(event.key);
              if (!card) return null;
              const open = openEvent?.event.key === event.key;
              return (
                <EventCard
                  key={event.key}
                  event={event}
                  laneKey={lane.key}
                  open={open}
                  onClick={e => {
                    const anchor = e.currentTarget;
                    setOpenEvent(prev => (prev?.event.key === event.key ? null : { event, anchor }));
                  }}
                  style={{ left: card.x, top: card.y, width: EVENT_W, height: EVENT_H }}
                />
              );
            }))}
          </div>
        </div>
      </div>
      {zoomable && (
        <div className="absolute right-2 top-2 rounded-lg bg-white/90 p-0.5 shadow-sm">
          <ZoomControls zoom={zoom} onZoom={onZoom} />
        </div>
      )}
      <Popover
        open={openEvent !== null}
        onClose={closeEvent}
        anchor={openEvent?.anchor ?? null}
        container={container}
        label="Event details"
      >
        {openEvent && <EventDetails event={openEvent.event} />}
      </Popover>
    </div>
  );
};

export default LineageCanvas;
