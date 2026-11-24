import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmError } from '../../../types/dspm';
import type { ErrorSubject } from '../datastores/datastoreErrors';
import { ErrorNote } from '../datastores/drawer/TabParts';
import { EventCard, EventDetails, LaneLabel } from '../datastores/saas/LineageParts';
import {
  EVENT_H, EVENT_W, LANE_H, LINEAGE_PAD, eventSlotX, layoutLineage,
} from '../datastores/saas/lineageLayout';
import type { LineageEventView, LineageLaneView } from '../datastores/saas/lineageModel';
import { Popover } from '../datastores/ui/Popover';
import { ZoomControls } from '../datastores/ui/ZoomControls';
import LineageNodeCard, { nodeName, type LineageNodeView } from './LineageNodeCard';
import type { RowLevel } from './lineageQuery';
import { rowPlural } from './lineageText';
import {
  MESSAGE_W, NODE_H, NODE_W, PAGER, TREE_ZOOM_MAX, TREE_ZOOM_MIN, isRowLevel, layoutTree, treeFitZoom, type TreeLevel,
} from './lineageTree';

/** What a row or the band shows instead of nodes. */
export type RowMessage =
  | { kind: 'loading'; text: string }
  | { kind: 'error'; error: DspmError; onRetry: () => void; subject?: ErrorSubject }
  | { kind: 'empty'; text: string };

export interface TreeNode {
  /** Unique in its row. */
  key: string;
  /** The id a click chooses; null renders the node disabled. */
  id: string | null;
  view: LineageNodeView;
}

export interface TreeRowView {
  level: TreeLevel;
  nodes: TreeNode[];
  /** The chosen node's key, or null when no node of this page is chosen. */
  chosen: string | null;
  prev: boolean;
  next: boolean;
  /** Drawn instead of the nodes. */
  message: RowMessage | null;
}

/** The chosen file's lineage: a message, or the lanes to draw (already searched). */
export type TreeBandView =
  | { kind: 'message'; message: RowMessage }
  | { kind: 'lanes'; lanes: LineageLaneView[] };

interface Props {
  /** Top to bottom: the category row, then each open row. */
  rows: TreeRowView[];
  band: TreeBandView | null;
  /** Inside the full-screen modal: the viewport is 72vh tall. */
  fullscreen: boolean;
  /** The open level: the drawing is fitted again whenever it changes. */
  fitKey: string;
  onNode: (level: TreeLevel, id: string) => void;
  onPage: (level: RowLevel, direction: 1 | -1) => void;
  /** "View full details →" in the event popover. */
  onViewDetails: (event: LineageEventView, anchor: HTMLElement) => void;
}

const MessageBox: React.FC<{ message: RowMessage; style: React.CSSProperties }> = ({ message, style }) => (
  <div className="absolute flex items-center justify-center" style={style}>
    {message.kind === 'error' ? (
      <div className="w-full">
        <ErrorNote error={message.error} onRetry={message.onRetry} subject={message.subject} />
      </div>
    ) : (
      <p className="rounded-lg border border-dashed border-[rgba(0,0,0,0.12)] bg-white px-4 py-3 text-center text-xs text-[rgba(0,0,0,0.5)]">
        {message.text}
      </p>
    )}
  </div>
);

const isLoading = (message: RowMessage | null | undefined) => message?.kind === 'loading';

/**
 * The Data Lineage tree (build 4 design 4.4, 6.6, 6.7): node buttons, pager buttons, message boxes and the
 * lineage band at the positions of `layoutTree`, with the curves in an SVG layer, all inside a CSS-scaled
 * layer in a scrolling, dotted viewport. The zoom control and the event popover sit in the unscaled
 * wrapper. Each new open level is fitted to the width and scrolled to its newest row; a page, search or
 * filter change on the same level keeps the zoom. The popover closes on any zoom change and any scroll.
 */
const LineageTreeCanvas: React.FC<Props> = ({ rows, band, fullscreen, fitKey, onNode, onPage, onViewDetails }) => {
  const container = useRef<HTMLDivElement>(null);
  const viewport = useRef<HTMLDivElement>(null);

  const lanes = band?.kind === 'lanes' ? band.lanes : null;
  const bandLayout = useMemo(() => (lanes ? layoutLineage(lanes) : null), [lanes]);
  const bandSize = band === null ? null : bandLayout
    ? { width: bandLayout.width, height: bandLayout.height }
    : { width: MESSAGE_W, height: NODE_H };

  const layout = layoutTree(
    rows.map(row => ({
      level: row.level,
      keys: row.nodes.map(n => n.key),
      chosen: row.chosen,
      prev: row.prev,
      next: row.next,
      message: row.message !== null,
    })),
    bandSize,
  );

  const [zoom, setZoom] = useState(1);
  const [openEvent, setOpenEvent] = useState<{ event: LineageEventView; anchor: HTMLElement } | null>(null);
  const closeEvent = useCallback(() => setOpenEvent(null), []);
  // Another file or search draws other cards: the open one may be gone.
  useEffect(() => { setOpenEvent(null); }, [band]);

  const newestLoading = band ? band.kind === 'message' && isLoading(band.message) : isLoading(rows[rows.length - 1]?.message);
  const fitted = useRef<string | null>(null);
  const [pendingScroll, setPendingScroll] = useState<number | null>(null);
  useLayoutEffect(() => {
    // Once per open level, when its newest row (or the band) has more than a loading message.
    if (fitted.current === fitKey || newestLoading) return;
    fitted.current = fitKey;
    setZoom(treeFitZoom(layout.width, viewport.current?.clientWidth ?? 0));
    setPendingScroll(layout.band ? layout.band.y : (layout.rows[layout.rows.length - 1]?.y ?? 0));
  }, [fitKey, layout, newestLoading]);
  useLayoutEffect(() => {
    if (pendingScroll === null) return;
    if (viewport.current) viewport.current.scrollTop = Math.max(0, pendingScroll * zoom - 24);
    setPendingScroll(null);
  }, [zoom, pendingScroll]);

  const onZoom = (next: number) => {
    setZoom(next);
    setOpenEvent(null);
  };

  const cards = new Map((bandLayout?.cards ?? []).map(card => [card.eventKey, card]));

  return (
    <div ref={container} className="relative">
      <div
        ref={viewport}
        role="group"
        aria-label="Lineage tree"
        onScroll={closeEvent}
        className={`overflow-auto rounded-xl border border-[rgba(0,0,0,0.08)] bg-[radial-gradient(rgba(0,0,0,0.12)_1px,transparent_1px)] [background-size:16px_16px] ${
          fullscreen ? 'h-[72vh]' : 'h-[520px]'
        }`}
      >
        <div className="relative" style={{ width: layout.width * zoom, height: layout.height * zoom }}>
          <div
            className="absolute left-0 top-0"
            style={{ width: layout.width, height: layout.height, transform: `scale(${zoom})`, transformOrigin: '0 0' }}
          >
            <svg
              className="pointer-events-none absolute inset-0"
              width={layout.width}
              height={layout.height}
              aria-hidden="true"
            >
              {layout.edges.map(edge => (
                <path
                  key={edge.key}
                  data-edge={edge.key}
                  data-active={edge.active || undefined}
                  d={edge.d}
                  fill="none"
                  stroke={edge.active ? '#0ea5e9' : 'rgba(0,0,0,0.18)'}
                  strokeWidth={edge.active ? 2 : 1.5}
                />
              ))}
            </svg>

            {rows.map((row, i) => {
              const laid = layout.rows[i];
              const level = row.level;
              const pagerTop = laid.y + (NODE_H - PAGER) / 2;
              const pagerClass = 'absolute flex items-center justify-center rounded-full border border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.6)] shadow-sm transition-colors hover:bg-[rgba(0,0,0,0.03)]';
              return (
                <React.Fragment key={row.level}>
                  {row.message !== null && laid.message !== null && (
                    <MessageBox message={row.message} style={{ left: laid.message, top: laid.y, width: MESSAGE_W, height: NODE_H }} />
                  )}
                  {row.message === null && row.nodes.map((node, j) => {
                    const chosen = node.key === row.chosen;
                    const id = node.id;
                    const name = nodeName(node.view);
                    return (
                      <button
                        key={node.key}
                        type="button"
                        data-node-level={row.level}
                        aria-pressed={chosen}
                        aria-label={name}
                        aria-disabled={id === null || undefined}
                        onClick={id === null ? undefined : () => onNode(row.level, id)}
                        className={`absolute rounded-xl border bg-white p-2.5 shadow-sm transition-colors ${
                          chosen ? 'border-sky-400' : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.25)]'
                        } ${id === null ? 'cursor-not-allowed opacity-60' : ''}`}
                        style={{ left: laid.nodes[j].x, top: laid.y, width: NODE_W, height: NODE_H }}
                      >
                        <LineageNodeCard view={node.view} chosen={chosen} />
                      </button>
                    );
                  })}
                  {laid.prev !== null && isRowLevel(level) && (
                    <button
                      type="button"
                      aria-label={`Show previous ${rowPlural(level)}`}
                      onClick={() => onPage(level, -1)}
                      className={pagerClass}
                      style={{ left: laid.prev, top: pagerTop, width: PAGER, height: PAGER }}
                    >
                      <Icon icon="mdi:chevron-left" width={18} aria-hidden="true" />
                    </button>
                  )}
                  {laid.next !== null && isRowLevel(level) && (
                    <button
                      type="button"
                      aria-label={`Show more ${rowPlural(level)}`}
                      onClick={() => onPage(level, 1)}
                      className={pagerClass}
                      style={{ left: laid.next, top: pagerTop, width: PAGER, height: PAGER }}
                    >
                      <Icon icon="mdi:chevron-right" width={18} aria-hidden="true" />
                    </button>
                  )}
                </React.Fragment>
              );
            })}

            {band && layout.band && (band.kind === 'message' ? (
              <MessageBox
                message={band.message}
                style={{ left: layout.band.x, top: layout.band.y, width: layout.band.width, height: layout.band.height }}
              />
            ) : bandLayout && (
              <div
                data-lineage-band=""
                className="absolute rounded-xl bg-[rgba(0,0,0,0.04)]"
                style={{ left: layout.band.x, top: layout.band.y, width: layout.band.width, height: layout.band.height }}
              >
                {band.lanes.map((lane, i) => (
                  <div
                    key={lane.key}
                    data-lane={lane.key}
                    data-own={lane.own || undefined}
                    className={`absolute rounded-lg border ${lane.own ? 'border-rose-100 bg-rose-50' : 'border-[rgba(0,0,0,0.06)] bg-white'}`}
                    style={{ left: LINEAGE_PAD, top: bandLayout.rows[i].y, width: bandLayout.width - 2 * LINEAGE_PAD, height: LANE_H }}
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
                ))}
                {band.lanes.flatMap(lane => lane.events.map(event => {
                  const card = cards.get(event.key);
                  if (!card) return null;
                  return (
                    <EventCard
                      key={event.key}
                      event={event}
                      laneKey={lane.key}
                      open={openEvent?.event.key === event.key}
                      onClick={e => {
                        const anchor = e.currentTarget;
                        setOpenEvent(prev => (prev?.event.key === event.key ? null : { event, anchor }));
                      }}
                      style={{ left: card.x, top: card.y, width: EVENT_W, height: EVENT_H }}
                    />
                  );
                }))}
              </div>
            ))}
          </div>
        </div>
      </div>
      <div className="absolute right-2 top-2 rounded-lg bg-white/90 p-0.5 shadow-sm">
        <ZoomControls zoom={zoom} onZoom={onZoom} min={TREE_ZOOM_MIN} max={TREE_ZOOM_MAX} />
      </div>
      <Popover
        open={openEvent !== null}
        onClose={closeEvent}
        anchor={openEvent?.anchor ?? null}
        container={container}
        label="Event details"
      >
        {openEvent && (
          <EventDetails
            event={openEvent.event}
            onViewDetails={() => {
              const { event, anchor } = openEvent;
              setOpenEvent(null);
              onViewDetails(event, anchor);
            }}
          />
        )}
      </Popover>
    </div>
  );
};

export default LineageTreeCanvas;
