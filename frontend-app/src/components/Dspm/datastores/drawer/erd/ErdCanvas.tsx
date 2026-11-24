import React, { useId, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { Icon } from '@iconify/react';
import type { DataFlowErd, DataFlowErdExternalTable, DataFlowErdNode } from '../../../../../types/dspmDrawers';
import type { DatastoreDrawerApi } from '../../../../../hooks/useDspmDatastoreDrawer';
import { ZoomControls } from '../../ui/ZoomControls';
import type { OpenTable } from '../DataFlowTables';
import { ERD_FIT_MIN, fitZoom, layoutErd } from './erdLayout';
import { ERD_DASH, ERD_EDGE, ERD_EDGE_ACTIVE } from './ErdParts';
import ErdTableCard, { ErdExternalCard } from './ErdTableCard';

export interface ErdCanvasProps {
  erd: DataFlowErd;
  api: DatastoreDrawerApi;
  /** Inside the full-screen frame: the viewport takes the screen's height and the toggle is not shown. */
  fullscreen: boolean;
  /** The "Full screen" button; `FullscreenFrame` focuses it again after the exit. */
  toggleRef: RefObject<HTMLButtonElement | null>;
  onFullscreen: () => void;
  /** The search's dimming term: '' for none (fewer than 3 characters). */
  query: string;
  /** Node ids of the expanded cards. */
  expanded: ReadonlySet<string>;
  onToggleExpanded: (id: string) => void;
  onOpenTable: (table: OpenTable, opener: HTMLElement) => void;
}

/** The first entry per key, as `layoutErd` keeps it. */
function firstByKey<T>(items: readonly T[], key: (item: T) => string): Map<string, T> {
  const map = new Map<string, T>();
  for (const item of items) if (!map.has(key(item))) map.set(key(item), item);
  return map;
}

/** The name a search matches: the table's, or "schema.table" for a table outside the diagram. */
const nameOf = (node: DataFlowErdNode | undefined, table: DataFlowErdExternalTable | undefined): string =>
  node ? node.name ?? '' : table ? `${table.schemaName}.${table.tableName}` : '';

const CONTROL = 'inline-flex h-7 w-7 items-center justify-center rounded-lg border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.65)] transition-colors hover:bg-[rgba(0,0,0,0.03)]';

/**
 * The schema diagram (design 4.6, 6.8): table cards and dashed boxes at the positions of `layoutErd`,
 * with one SVG connector per foreign key, scaled inside a viewport that scrolls both ways. Build 1's
 * Access Graph structure plus an unscaled wrapper: the zoom control and the full-screen button sit
 * outside the scaled layer, so they keep their size at any zoom and the viewport never clips them.
 *
 * The zoom opens at the fit-to-width value (55%..100%), once per mount; entering or leaving full screen
 * remounts the canvas, so it fits again. Hovering or focusing a card dims every card that shares no
 * connector with it and darkens its own connectors; a search term dims the cards whose name does not
 * contain it. A dimmed card's connectors are dimmed too.
 *
 * The module's default export, loaded with `React.lazy`, so cytoscape arrives with Graph View only.
 */
const ErdCanvas: React.FC<ErdCanvasProps> = ({
  erd, api, fullscreen, toggleRef, onFullscreen, query, expanded, onToggleExpanded, onOpenTable,
}) => {
  const layout = useMemo(() => layoutErd(erd), [erd]);
  const nodes = useMemo(() => firstByKey(erd.nodes, node => node.id), [erd]);
  const externals = useMemo(() => firstByKey(erd.externalTables, table => table.key), [erd]);

  const viewport = useRef<HTMLDivElement>(null);
  const [zoom, setZoom] = useState<number | null>(null);
  useLayoutEffect(() => {
    if (zoom !== null || layout.width === 0) return;
    setZoom(fitZoom(layout.width, viewport.current?.clientWidth ?? 0));
  }, [layout.width, zoom]);
  const scale = zoom ?? ERD_FIT_MIN;

  const [hovered, setHovered] = useState<string | null>(null);
  const [focused, setFocused] = useState<string | null>(null);
  const active = hovered ?? focused;

  const dimmed = useMemo(() => {
    let linked: Set<string> | null = null;
    if (active !== null) {
      linked = new Set([active]);
      for (const path of layout.paths) {
        if (path.sourceKey === active) linked.add(path.targetKey);
        if (path.targetKey === active) linked.add(path.sourceKey);
      }
    }
    const needle = query.toLowerCase();
    const out = new Set<string>();
    for (const box of layout.boxes) {
      const name = nameOf(nodes.get(box.key), externals.get(box.key)).toLowerCase();
      if ((linked !== null && !linked.has(box.key)) || (needle !== '' && !name.includes(needle))) out.add(box.key);
    }
    return out;
  }, [active, query, layout, nodes, externals]);

  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const arrow = `erd-arrow-${uid}`;
  const arrowActive = `erd-arrow-active-${uid}`;

  return (
    <div className="relative">
      <div
        ref={viewport}
        role="group"
        aria-label="Schema diagram"
        className={`overflow-auto rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.015)] ${
          fullscreen ? 'h-[calc(100vh-9rem)]' : 'h-[560px]'
        }`}
      >
        <div className="relative" style={{ width: layout.width * scale, height: layout.height * scale }}>
          <div
            className="absolute left-0 top-0"
            style={{ width: layout.width, height: layout.height, transform: `scale(${scale})`, transformOrigin: '0 0' }}
          >
            <svg aria-hidden="true" width={layout.width} height={layout.height} className="pointer-events-none absolute left-0 top-0">
              <defs>
                {[[arrow, ERD_EDGE], [arrowActive, ERD_EDGE_ACTIVE]].map(([id, fill]) => (
                  <marker
                    key={id}
                    id={id}
                    viewBox="0 0 10 10"
                    refX="10"
                    refY="5"
                    markerWidth="8"
                    markerHeight="8"
                    markerUnits="userSpaceOnUse"
                    orient="auto"
                  >
                    <path d="M 0 0 L 10 5 L 0 10 z" fill={fill} />
                  </marker>
                ))}
              </defs>
              {layout.paths.map(path => {
                const lit = active !== null && (path.sourceKey === active || path.targetKey === active);
                const dim = dimmed.has(path.sourceKey) || dimmed.has(path.targetKey);
                return (
                  <path
                    key={path.id}
                    data-path={path.id}
                    data-active={lit ? 'true' : undefined}
                    d={path.d}
                    fill="none"
                    stroke={lit ? ERD_EDGE_ACTIVE : ERD_EDGE}
                    strokeWidth={lit ? 2 : 1.25}
                    strokeDasharray={path.external ? ERD_DASH : undefined}
                    markerEnd={path.external ? undefined : `url(#${lit ? arrowActive : arrow})`}
                    className={dim ? 'opacity-35' : undefined}
                  />
                );
              })}
            </svg>
            {layout.boxes.map(box => (box.external ? (
              <ErdExternalCard key={box.key} box={box} table={externals.get(box.key)} dimmed={dimmed.has(box.key)} />
            ) : (
              <ErdTableCard
                key={box.key}
                node={nodes.get(box.key)!}
                box={box}
                dimmed={dimmed.has(box.key)}
                expanded={expanded.has(box.key)}
                onToggleExpanded={onToggleExpanded}
                onOpen={opener => onOpenTable({ id: box.key, name: nodes.get(box.key)?.name ?? '-' }, opener)}
                onHover={setHovered}
                onFocusChange={setFocused}
                api={api}
              />
            )))}
          </div>
        </div>
      </div>
      <div className="absolute right-2 top-2 flex items-center gap-1 rounded-lg bg-white/90 p-0.5 shadow-sm">
        <ZoomControls zoom={scale} onZoom={setZoom} />
        {!fullscreen && (
          <button ref={toggleRef} type="button" aria-label="Full screen" onClick={onFullscreen} className={CONTROL}>
            <Icon icon="mdi:fullscreen" width={16} />
          </button>
        )}
      </div>
    </div>
  );
};

export default ErdCanvas;
