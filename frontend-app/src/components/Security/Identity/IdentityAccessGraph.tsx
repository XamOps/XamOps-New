/**
 * IdentityAccessGraph
 *
 * The Identity & Access tab as a left-to-right access graph on a pannable canvas: the account,
 * a labelled privilege lane per principal kind, a collapsible bucket carrying the count, then
 * the principals themselves.
 *
 * <p>The card canvas answers "which identity should I look at". This answers a question cards
 * cannot: "what shape is this account's access". Three lanes reading FULL ADMIN / ESCALATION /
 * SCOPED tell you in one glance whether privilege is concentrated in roles or scattered across
 * users, which is the thing you would otherwise reconstruct by opening twenty cards.
 *
 * <p>Rendered as positioned DOM over an SVG edge layer rather than through Cytoscape. The tree
 * is a fixed four columns of containment edges, so {@link accessGraphModel} can place it exactly
 * — which keeps the picture identical across refreshes, needs no async layout settle, keeps the
 * 537 KB Cytoscape chunk out of the Security Center route, and lets every bucket chevron and
 * principal be a real focusable button instead of a hit-test on a canvas bitmap.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { IamIdentity, IamRole, IamUser } from '../../../types/userManagement';
import { isIamUser } from '../../../types/userManagement';
import { riskToken } from './riskTokens';
import { PolicyRow, RiskRow } from './AccessRows';
import {
  ACCESS_LEVEL_TOKENS,
  COLS,
  LAYOUT,
  buildAccessGraph,
  elbowPath,
  visibleLeaves,
  type AccessLevel,
  type BucketId,
  type LeafNode,
} from './accessGraphModel';

/** Canvas transform. Named so LAYOUT's `as const` literals cannot narrow the state type. */
interface CanvasView {
  zoom: number;
  x: number;
  y: number;
}

const MIN_ZOOM = 0.35;
const MAX_ZOOM = 2;
const CANVAS_H = 620;

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

interface IdentityAccessGraphProps {
  /** Filtered and sorted by useUserManagement. */
  users: IamIdentity[];
  roles: IamIdentity[];
  /** Unfiltered users, so a search on user names does not silently empty the groups lane. */
  allUsers: IamUser[];
  accountName: string;
  accountId: string;
  provider: 'AWS' | 'GCP' | 'Azure';
  labels: { users: string; roles: string };
  /** False on an unassessed account. Hides risk colouring rather than painting everything clean. */
  showRisk: boolean;
  searchQuery: string;
  flaggedOnly: boolean;
  isLoading: boolean;
  formatDate: (date: string | null) => string;
  isOldDate: (date: string | null) => boolean;
  onCopy: (text: string) => void;
}

const ALL_BUCKETS: BucketId[] = ['groups', 'roles', 'users'];

const PROVIDER_ICON: Record<string, string> = {
  AWS: 'mdi:aws',
  GCP: 'mdi:google-cloud',
  Azure: 'mdi:microsoft-azure',
};

const LEAF_ICON: Record<LeafNode['kind'], string> = {
  group: 'mdi:account-group-outline',
  role: 'mdi:shield-account-outline',
  user: 'mdi:account-outline',
};

/** Lane pill text, e.g. "Full admin / Scoped". Capped so a five-level lane stays one line. */
function laneLabel(levels: AccessLevel[]): string {
  const names = levels.map((l) => ACCESS_LEVEL_TOKENS[l].label);
  if (names.length <= 2) return names.join(' / ');
  return `${names.slice(0, 2).join(' / ')} +${names.length - 2}`;
}

const IdentityAccessGraph: React.FC<IdentityAccessGraphProps> = ({
  users,
  roles,
  allUsers,
  accountName,
  accountId,
  provider,
  labels,
  showRisk,
  searchQuery,
  flaggedOnly,
  isLoading,
  formatDate,
  isOldDate,
  onCopy,
}) => {
  const viewportRef = useRef<HTMLDivElement>(null);

  const [expanded, setExpanded] = useState<Set<BucketId>>(() => new Set(ALL_BUCKETS));
  const [showAll, setShowAll] = useState<Set<BucketId>>(() => new Set());
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [view, setView] = useState<CanvasView>({ zoom: 1, x: LAYOUT.PAD, y: 0 });
  const [dragging, setDragging] = useState(false);

  const groupQuery = searchQuery.trim().toLowerCase();

  const graph = useMemo(
    () => buildAccessGraph({
      users, roles, allUsers, accountName, accountId, provider, labels,
      showRisk, expanded, showAll, groupQuery, flaggedOnly,
    }),
    [users, roles, allUsers, accountName, accountId, provider, labels,
      showRisk, expanded, showAll, groupQuery, flaggedOnly],
  );

  const selected = useMemo(() => {
    if (!selectedId) return null;
    for (const bucket of graph.buckets) {
      const hit = bucket.leaves.find((l) => l.id === selectedId);
      if (hit) return hit;
    }
    return null;
  }, [selectedId, graph.buckets]);

  /** Bucket owning the selection, for the focus/dim pass. */
  const selectedBucketId = useMemo(() => {
    if (!selectedId) return null;
    return graph.buckets.find((b) => b.leaves.some((l) => l.id === selectedId))?.id ?? null;
  }, [selectedId, graph.buckets]);

  // ── Fit ───────────────────────────────────────────────────────────────────

  /**
   * Fit the graph's width, not its bounding box.
   *
   * <p>Fitting height as well sounds right and is not: a 30-role column is several thousand
   * pixels tall, and scaling that into a 620px canvas lands around 0.15 zoom, where every label
   * is illegible. Width always fits in one screen, so fit that, floor the zoom at something
   * readable, and let the tall case scroll — which is what the collapse chevrons are for.
   */
  const fit = useCallback(() => {
    const vp = viewportRef.current;
    if (!vp) return;
    const vw = vp.clientWidth;
    const vh = vp.clientHeight;
    if (!vw || !vh) return;
    const zoom = clamp(vw / graph.width, MIN_ZOOM, 1);
    const scaledH = graph.height * zoom;
    setView({
      zoom,
      x: Math.max(0, (vw - graph.width * zoom) / 2),
      y: scaledH <= vh ? (vh - scaledH) / 2 : LAYOUT.PAD,
    });
  }, [graph.width, graph.height]);

  // Fit once per account rather than on every expand: re-fitting when a chevron opens would
  // yank the canvas out from under the click that opened it.
  const fittedForRef = useRef<string | null>(null);
  useEffect(() => {
    if (isLoading || graph.totalLeaves === 0) return;
    const key = `${accountId}|${provider}`;
    if (fittedForRef.current === key) return;
    fittedForRef.current = key;
    fit();
  }, [isLoading, graph.totalLeaves, accountId, provider, fit]);

  /**
   * Re-seed which buckets are open when a filter narrows the graph.
   *
   * <p>Without this a search lands on collapsed headers: the user types a role name, the roles
   * bucket holds the single match, and the canvas shows three shut buckets.
   */
  const narrowed = groupQuery !== '' || flaggedOnly;
  useEffect(() => {
    if (!narrowed) return;
    setExpanded(new Set(ALL_BUCKETS));
  }, [narrowed, groupQuery, flaggedOnly]);

  // ── Pan / zoom ────────────────────────────────────────────────────────────

  // Plain wheel pans and Ctrl/Cmd+wheel zooms toward the cursor, matching CloudMap. Registered
  // manually because preventDefault needs a non-passive listener, which JSX onWheel cannot give.
  useEffect(() => {
    const el = viewportRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      if (e.ctrlKey || e.metaKey) {
        const rect = el.getBoundingClientRect();
        const cx = e.clientX - rect.left;
        const cy = e.clientY - rect.top;
        setView((v) => {
          const zoom = clamp(v.zoom * (e.deltaY < 0 ? 1.12 : 1 / 1.12), MIN_ZOOM, MAX_ZOOM);
          const k = zoom / v.zoom;
          // Keep the graph point under the cursor pinned to the cursor.
          return { zoom, x: cx - (cx - v.x) * k, y: cy - (cy - v.y) * k };
        });
      } else {
        setView((v) => ({ ...v, x: v.x - e.deltaX, y: v.y - e.deltaY }));
      }
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, []);

  const dragRef = useRef<{ px: number; py: number; vx: number; vy: number } | null>(null);

  const onMouseDown = (e: React.MouseEvent) => {
    // Let the nodes have their clicks; only empty canvas starts a pan.
    if ((e.target as HTMLElement).closest('button, a')) return;
    dragRef.current = { px: e.clientX, py: e.clientY, vx: view.x, vy: view.y };
    setDragging(true);
  };

  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: MouseEvent) => {
      const d = dragRef.current;
      if (!d) return;
      setView((v) => ({ ...v, x: d.vx + (e.clientX - d.px), y: d.vy + (e.clientY - d.py) }));
    };
    const onUp = () => { dragRef.current = null; setDragging(false); };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [dragging]);

  const zoomBy = (factor: number) => setView((v) => {
    const vp = viewportRef.current;
    const cx = (vp?.clientWidth ?? 0) / 2;
    const cy = (vp?.clientHeight ?? 0) / 2;
    const zoom = clamp(v.zoom * factor, MIN_ZOOM, MAX_ZOOM);
    const k = zoom / v.zoom;
    return { zoom, x: cx - (cx - v.x) * k, y: cy - (cy - v.y) * k };
  });

  /**
   * Pan a node into view.
   *
   * <p>Wired to focus so keyboard traversal works: tabbing is the only way through this graph
   * without a mouse, and a focus ring on a node parked outside the viewport is no navigation
   * at all.
   */
  const ensureVisible = useCallback((x: number, y: number, w: number, h: number) => {
    const vp = viewportRef.current;
    if (!vp) return;
    setView((v) => {
      const m = 24;
      const left = x * v.zoom + v.x;
      const top = y * v.zoom + v.y;
      const right = left + w * v.zoom;
      const bottom = top + h * v.zoom;
      let nx = v.x;
      let ny = v.y;
      if (left < m) nx += m - left;
      else if (right > vp.clientWidth - m) nx -= right - (vp.clientWidth - m);
      if (top < m) ny += m - top;
      else if (bottom > vp.clientHeight - m) ny -= bottom - (vp.clientHeight - m);
      return nx === v.x && ny === v.y ? v : { ...v, x: nx, y: ny };
    });
  }, []);

  const toggleBucket = (id: BucketId) => setExpanded((prev) => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  const allOpen = graph.buckets.length > 0 && graph.buckets.every((b) => expanded.has(b.id));

  // ── Focus / dim ───────────────────────────────────────────────────────────

  const dimBucket = (id: BucketId) => Boolean(selectedBucketId) && selectedBucketId !== id;
  const dimLeaf = (leaf: LeafNode) => Boolean(selectedId) && selectedId !== leaf.id;
  const dimStyle = (dim: boolean): React.CSSProperties =>
    dim ? { opacity: 0.28, transition: 'opacity 160ms ease' } : { transition: 'opacity 160ms ease' };

  // ── Empty / loading ───────────────────────────────────────────────────────

  if (isLoading) {
    return (
      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white shadow-sm overflow-hidden">
        <div className="h-[620px] flex flex-col items-center justify-center gap-3">
          <Icon icon="mdi:graph-outline" width={30} className="text-[rgba(0,0,0,0.15)] animate-pulse" />
          <p className="text-xs text-[rgba(0,0,0,0.4)]">Building access graph…</p>
        </div>
      </div>
    );
  }

  if (graph.buckets.length === 0) {
    return (
      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white shadow-sm py-20 text-center">
        <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-3">
          <Icon icon={flaggedOnly ? 'mdi:shield-check' : 'mdi:graph-outline'} width={28} color="rgba(0,0,0,0.25)" />
        </div>
        {flaggedOnly ? (
          <>
            <p className="text-sm font-bold text-[rgba(0,0,0,0.6)]">Nothing over-privileged to graph</p>
            <p className="text-xs text-[rgba(0,0,0,0.45)] mt-1">
              No principal holds excess access. Clear the filter to see the whole account.
            </p>
          </>
        ) : groupQuery ? (
          <>
            <p className="text-sm font-bold text-[rgba(0,0,0,0.6)]">No matches</p>
            <p className="text-xs text-[rgba(0,0,0,0.45)] mt-1">Nothing matches “{searchQuery}”.</p>
          </>
        ) : (
          <p className="text-sm font-bold text-[rgba(0,0,0,0.5)]">No identities to graph</p>
        )}
      </div>
    );
  }

  // ── Edges ─────────────────────────────────────────────────────────────────

  const rootRight = graph.rootX + graph.rootW;
  const rootCentreY = graph.rootY + graph.rootH / 2;

  return (
    <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white shadow-sm overflow-hidden">
      {/* Toolbar */}
      <div className="flex items-center justify-between gap-3 px-3 py-2 border-b border-[rgba(0,0,0,0.07)] bg-[rgba(0,0,0,0.015)]">
        <div className="flex items-center gap-2 min-w-0">
          <Icon icon="mdi:graph-outline" width={14} className="text-[rgba(0,0,0,0.4)] flex-shrink-0" />
          <span className="text-[11px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.45)]">Access graph</span>
          <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-[rgba(0,0,0,0.06)] font-bold text-[rgba(0,0,0,0.5)]">
            {graph.totalLeaves} {graph.totalLeaves === 1 ? 'node' : 'nodes'}
          </span>
        </div>

        <div className="flex items-center gap-1.5 flex-shrink-0">
          <button
            type="button"
            onClick={() => setExpanded(allOpen ? new Set() : new Set(ALL_BUCKETS))}
            className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
          >
            <Icon icon={allOpen ? 'mdi:collapse-all-outline' : 'mdi:expand-all-outline'} width={12} />
            {allOpen ? 'Collapse all' : 'Expand all'}
          </button>
          <div className="w-px h-5 bg-[rgba(0,0,0,0.1)]" />
          <button type="button" onClick={() => zoomBy(1 / 1.2)} aria-label="Zoom out"
            className="w-7 h-7 rounded-lg border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.04)] flex items-center justify-center transition-colors">
            <Icon icon="mdi:minus" width={13} />
          </button>
          <span className="text-[10px] font-bold text-[rgba(0,0,0,0.4)] tabular-nums w-9 text-center">
            {Math.round(view.zoom * 100)}%
          </span>
          <button type="button" onClick={() => zoomBy(1.2)} aria-label="Zoom in"
            className="w-7 h-7 rounded-lg border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.04)] flex items-center justify-center transition-colors">
            <Icon icon="mdi:plus" width={13} />
          </button>
          <button type="button" onClick={fit} aria-label="Fit to view"
            className="w-7 h-7 rounded-lg border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.04)] flex items-center justify-center transition-colors">
            <Icon icon="mdi:fit-to-page-outline" width={13} />
          </button>
        </div>
      </div>

      <div className="relative">
        {/* Canvas */}
        <div
          ref={viewportRef}
          role="group"
          aria-label="IAM access graph"
          onMouseDown={onMouseDown}
          className="relative overflow-hidden select-none"
          style={{
            height: CANVAS_H,
            cursor: dragging ? 'grabbing' : 'grab',
            backgroundColor: '#fbfcfd',
            // Grid rides the transform so panning feels like moving over a surface rather
            // than sliding content across a static backdrop.
            backgroundImage: 'radial-gradient(circle, rgba(15,23,42,0.10) 1px, transparent 1px)',
            backgroundSize: `${18 * view.zoom}px ${18 * view.zoom}px`,
            backgroundPosition: `${view.x}px ${view.y}px`,
          }}
        >
          <div
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: graph.width,
              height: graph.height,
              transform: `translate(${view.x}px, ${view.y}px) scale(${view.zoom})`,
              transformOrigin: '0 0',
            }}
          >
            {/* Edge layer */}
            <svg
              width={graph.width}
              height={graph.height}
              className="absolute inset-0 pointer-events-none"
              style={{ overflow: 'visible' }}
              aria-hidden="true"
            >
              {graph.buckets.map((bucket) => {
                const tone = ACCESS_LEVEL_TOKENS[bucket.worst];
                const dim = dimBucket(bucket.id);
                const open = expanded.has(bucket.id);
                const leaves = open ? visibleLeaves(bucket, showAll.has(bucket.id)) : [];
                return (
                  <g key={bucket.id} style={dimStyle(dim)}>
                    {/* Root → bucket, through the shared trunk. */}
                    <path
                      d={elbowPath(rootRight, rootCentreY, bucket.x, bucket.laneY, COLS.trunk)}
                      fill="none"
                      stroke={tone.stroke}
                      strokeOpacity={0.5}
                      strokeWidth={1.5}
                    />
                    {/* Bucket → each leaf. */}
                    {leaves.map((leaf) => {
                      const hot = leaf.flagged || leaf.level === 'ADMIN' || leaf.level === 'ESCALATION';
                      return (
                        <path
                          key={leaf.id}
                          d={elbowPath(
                            bucket.x + bucket.w,
                            bucket.laneY,
                            leaf.x,
                            leaf.y + leaf.h / 2,
                            COLS.leafTrunk,
                          )}
                          fill="none"
                          stroke={hot ? ACCESS_LEVEL_TOKENS[leaf.level].stroke : '#cbd5e1'}
                          strokeOpacity={dimLeaf(leaf) ? 0.3 : hot ? 0.6 : 0.9}
                          strokeWidth={1.25}
                        />
                      );
                    })}
                  </g>
                );
              })}
            </svg>

            {/* Root */}
            <div
              className="absolute rounded-xl bg-white border border-[rgba(0,0,0,0.14)] shadow-sm flex items-center gap-2.5 px-3"
              style={{ left: graph.rootX, top: graph.rootY, width: graph.rootW, height: graph.rootH }}
            >
              <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] flex items-center justify-center flex-shrink-0">
                <Icon icon={PROVIDER_ICON[provider] ?? 'mdi:cloud-outline'} width={16} className="text-[rgba(0,0,0,0.6)]" />
              </div>
              <div className="min-w-0">
                <p className="text-[12px] font-bold text-[rgba(0,0,0,0.88)] truncate leading-tight">{graph.rootLabel}</p>
                <p className="text-[9px] text-[rgba(0,0,0,0.42)] truncate mt-0.5">{graph.rootSubLabel}</p>
              </div>
            </div>

            {/* Lane pills, buckets, leaves */}
            {graph.buckets.map((bucket) => {
              const open = expanded.has(bucket.id);
              const leaves = open ? visibleLeaves(bucket, showAll.has(bucket.id)) : [];
              const tone = ACCESS_LEVEL_TOKENS[bucket.worst];
              const dim = dimBucket(bucket.id);

              return (
                <React.Fragment key={bucket.id}>
                  {/* Lane pill — what this branch can do. Sits on the horizontal run so the
                      label reads as a property of the edge, not of the bucket. */}
                  <div
                    className={`absolute rounded-md border flex items-center justify-center px-2 ${tone.pill}`}
                    style={{
                      left: COLS.lane,
                      top: bucket.laneY - LAYOUT.LANE_PILL_H / 2,
                      width: LAYOUT.LANE_PILL_W,
                      height: LAYOUT.LANE_PILL_H,
                      ...dimStyle(dim),
                    }}
                    title={bucket.levels.map((l) => `${ACCESS_LEVEL_TOKENS[l].label}: ${ACCESS_LEVEL_TOKENS[l].hint}`).join('\n')}
                  >
                    <span className="text-[9px] font-bold uppercase tracking-[0.08em] truncate">
                      {laneLabel(bucket.levels)}
                    </span>
                  </div>

                  {/* Bucket */}
                  <button
                    type="button"
                    onClick={() => toggleBucket(bucket.id)}
                    onFocus={() => ensureVisible(bucket.x, bucket.y, bucket.w, bucket.h)}
                    aria-expanded={open}
                    aria-label={`${bucket.leaves.length} ${bucket.label}`}
                    className="absolute rounded-lg bg-white border border-[rgba(0,0,0,0.14)] shadow-sm flex items-center gap-1.5 px-2.5 hover:border-[rgba(0,0,0,0.3)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.4)] transition-colors"
                    style={{ left: bucket.x, top: bucket.y, width: bucket.w, height: bucket.h, ...dimStyle(dim) }}
                  >
                    <Icon icon={bucket.icon} width={13} className="text-[rgba(0,0,0,0.45)] flex-shrink-0" />
                    <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.7)]">{bucket.label}</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-[rgba(0,0,0,0.06)] font-bold text-[rgba(0,0,0,0.55)] tabular-nums">
                      {bucket.leaves.length}
                    </span>
                    {bucket.flaggedCount > 0 && (
                      <span
                        className="w-1.5 h-1.5 rounded-full bg-red-500 flex-shrink-0"
                        title={`${bucket.flaggedCount} over-privileged`}
                      />
                    )}
                    <Icon
                      icon="mdi:chevron-down"
                      width={14}
                      className={`ml-auto text-[rgba(0,0,0,0.35)] flex-shrink-0 transition-transform duration-200 ${open ? '' : '-rotate-90'}`}
                    />
                  </button>

                  {/* Leaves */}
                  {leaves.map((leaf) => {
                    const token = riskToken(leaf.riskLevel ?? undefined);
                    const isSel = selectedId === leaf.id;
                    return (
                      <button
                        key={leaf.id}
                        type="button"
                        onClick={() => setSelectedId(isSel ? null : leaf.id)}
                        onFocus={() => ensureVisible(leaf.x, leaf.y, leaf.w, leaf.h)}
                        aria-pressed={isSel}
                        className={`absolute rounded-lg border shadow-sm flex items-center gap-2 px-2.5 text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.4)] transition-colors ${
                          isSel
                            ? 'border-[rgba(0,0,0,0.55)] bg-white'
                            : leaf.flagged
                              ? 'border-red-200 bg-red-50/50 hover:border-red-300'
                              : 'border-[rgba(0,0,0,0.12)] bg-white hover:border-[rgba(0,0,0,0.28)]'
                        }`}
                        style={{ left: leaf.x, top: leaf.y, width: leaf.w, height: leaf.h, ...dimStyle(dimLeaf(leaf)) }}
                      >
                        <Icon icon={LEAF_ICON[leaf.kind]} width={13}
                          className={`flex-shrink-0 ${leaf.flagged ? 'text-red-500' : 'text-[rgba(0,0,0,0.35)]'}`} />
                        <span
                          className="text-[11.5px] font-semibold text-[rgba(0,0,0,0.8)] leading-[1.25] min-w-0 flex-1"
                          style={{
                            display: '-webkit-box',
                            WebkitLineClamp: 2,
                            WebkitBoxOrient: 'vertical',
                            overflow: 'hidden',
                            wordBreak: 'break-all',
                          }}
                        >
                          {leaf.label}
                        </span>
                        {/* Grant count, then the risk dot. Both are why this node is here. */}
                        <span className="text-[9.5px] font-bold text-[rgba(0,0,0,0.35)] tabular-nums flex-shrink-0">
                          {leaf.grantCount}
                        </span>
                        {showRisk && leaf.kind !== 'group' && (
                          <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${token.dot}`} title={token.label} />
                        )}
                      </button>
                    );
                  })}

                  {/* Show all — a bucket of 200 roles is a column nobody scrolls. */}
                  {bucket.moreY !== null && (
                    <button
                      type="button"
                      onClick={() => setShowAll((prev) => new Set(prev).add(bucket.id))}
                      className="absolute rounded-lg border border-dashed border-[rgba(0,0,0,0.2)] bg-white/70 text-[10.5px] font-bold text-[rgba(0,0,0,0.5)] hover:border-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.75)] transition-colors flex items-center justify-center gap-1"
                      style={{ left: COLS.leaf, top: bucket.moreY, width: LAYOUT.LEAF_W, height: LAYOUT.MORE_H, ...dimStyle(dim) }}
                    >
                      <Icon icon="mdi:dots-horizontal" width={12} />
                      Show {bucket.hiddenCount} more
                    </button>
                  )}
                </React.Fragment>
              );
            })}
          </div>

          {/* Legend. The lane pills make a severity claim, so the scale has to be stated. */}
          <div className="absolute bottom-2 left-2 flex flex-wrap items-center gap-x-3 gap-y-1 px-2.5 py-1.5 rounded-lg bg-white/90 border border-[rgba(0,0,0,0.1)] backdrop-blur-sm pointer-events-none max-w-[calc(100%-1rem)]">
            <span className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)]">Privilege</span>
            {(['ADMIN', 'ESCALATION', 'WILDCARD', 'NOT_ASSESSED', 'SCOPED'] as AccessLevel[]).map((level) => (
              <span key={level} className="inline-flex items-center gap-1">
                <span className="w-2.5 h-[2px] rounded-full" style={{ backgroundColor: ACCESS_LEVEL_TOKENS[level].stroke }} />
                <span className="text-[9px] font-semibold text-[rgba(0,0,0,0.5)]">{ACCESS_LEVEL_TOKENS[level].label}</span>
              </span>
            ))}
          </div>

          <p className="absolute bottom-2 right-2 text-[9px] text-[rgba(0,0,0,0.3)] pointer-events-none">
            Drag to pan · ⌘/Ctrl + scroll to zoom
          </p>
        </div>

        {/* Inspector */}
        {selected && (
          <NodeInspector
            leaf={selected}
            provider={provider}
            showRisk={showRisk}
            formatDate={formatDate}
            isOldDate={isOldDate}
            onCopy={onCopy}
            onClose={() => setSelectedId(null)}
          />
        )}
      </div>
    </div>
  );
};

// ─── Inspector ───────────────────────────────────────────────────────────────

/**
 * Detail for the selected node, as an overlay rather than a column.
 *
 * <p>An overlay keeps the graph's geometry fixed: a side column would change the viewport width,
 * which re-fits the canvas and moves the node the user just clicked out from under the pointer.
 */
const NodeInspector: React.FC<{
  leaf: LeafNode;
  provider: 'AWS' | 'GCP' | 'Azure';
  showRisk: boolean;
  formatDate: (date: string | null) => string;
  isOldDate: (date: string | null) => boolean;
  onCopy: (text: string) => void;
  onClose: () => void;
}> = ({ leaf, provider, showRisk, formatDate, isOldDate, onCopy, onClose }) => {
  const identity = leaf.identity;
  const user = identity && isIamUser(identity) ? (identity as IamUser) : null;
  const role = identity && !isIamUser(identity) ? (identity as IamRole) : null;
  const group = leaf.group;

  const risks = (showRisk ? identity?.risks : null) ?? [];
  const policies = group ? group.policies : identity?.attachedPolicies ?? [];
  const token = riskToken(leaf.riskLevel ?? undefined);
  const levelToken = ACCESS_LEVEL_TOKENS[leaf.level];

  // Escape closes, matching every other dismissible surface in the console.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <aside
      role="dialog"
      aria-label={`Access detail for ${leaf.label}`}
      className="absolute top-0 right-0 h-full w-full sm:w-[380px] bg-white border-l border-[rgba(0,0,0,0.12)] shadow-xl flex flex-col"
      style={{ animation: 'fadeInUp 0.2s cubic-bezier(0.22, 1, 0.36, 1) both' }}
    >
      <div className="flex items-start gap-2.5 px-4 py-3 border-b border-[rgba(0,0,0,0.07)]">
        <div className={`w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0 border ${
          leaf.flagged ? 'bg-red-100 border-red-200'
            : leaf.kind === 'group' ? 'bg-blue-50 border-blue-100'
              : leaf.kind === 'role' ? 'bg-violet-50 border-violet-100'
                : 'bg-indigo-50 border-indigo-100'
        }`}>
          <Icon icon={LEAF_ICON[leaf.kind]} width={16}
            className={leaf.flagged ? 'text-red-600'
              : leaf.kind === 'group' ? 'text-blue-600'
                : leaf.kind === 'role' ? 'text-violet-600' : 'text-indigo-600'} />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] break-all leading-tight">{leaf.label}</p>
          <div className="flex items-center gap-1.5 mt-1 flex-wrap">
            <span className={`px-1.5 py-0.5 rounded border text-[9px] font-bold uppercase tracking-wider ${levelToken.pill}`}>
              {levelToken.label}
            </span>
            {showRisk && leaf.kind !== 'group' && (
              <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full border text-[9px] font-bold uppercase tracking-wider ${token.soft}`}>
                <span className={`w-1.5 h-1.5 rounded-full ${token.dot}`} />
                {token.label}
              </span>
            )}
            {provider === 'AWS' && user && (
              <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded border text-[9px] font-bold uppercase tracking-wider ${
                user.mfaEnabled ? 'bg-emerald-50 border-emerald-100 text-emerald-600' : 'bg-rose-50 border-rose-100 text-rose-600'
              }`}>
                <Icon icon={user.mfaEnabled ? 'mdi:shield-check' : 'mdi:shield-off-outline'} width={9} />
                {user.mfaEnabled ? 'MFA' : 'No MFA'}
              </span>
            )}
          </div>
        </div>
        <button type="button" onClick={onClose} aria-label="Close detail"
          className="w-7 h-7 rounded-lg flex items-center justify-center hover:bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.4)] flex-shrink-0 transition-colors">
          <Icon icon="mdi:close" width={16} />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-3 space-y-4">
        <p className="text-[11px] text-[rgba(0,0,0,0.5)] leading-relaxed">{levelToken.hint}</p>

        {identity && (
          <div>
            <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">
              {user ? 'User ARN' : 'Role ARN'}
            </p>
            <button type="button" onClick={() => onCopy(identity.arn)} title="Copy ARN"
              className="group inline-flex items-start gap-1.5 text-left">
              <code className="text-[10px] font-mono text-[rgba(0,0,0,0.6)] break-all group-hover:text-[rgba(0,0,0,0.88)]">
                {identity.arn}
              </code>
              <Icon icon="mdi:content-copy" width={11} className="flex-shrink-0 mt-0.5 text-[rgba(0,0,0,0.3)] group-hover:text-[rgba(0,0,0,0.6)]" />
            </button>
          </div>
        )}

        {role?.description && (
          <div>
            <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">Description</p>
            <p className="text-xs text-[rgba(0,0,0,0.65)]">{role.description}</p>
          </div>
        )}

        {group && (
          <div>
            <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1.5">
              Members ({group.members.length})
            </p>
            {group.members.length > 0 ? (
              <div className="flex flex-wrap gap-1.5">
                {group.members.map((m) => (
                  <span key={m} className="inline-flex items-center gap-1 px-2 py-0.5 bg-indigo-50 border border-indigo-100 text-indigo-700 text-[10px] font-semibold rounded-full">
                    <Icon icon="mdi:account-outline" width={10} />
                    {m}
                  </span>
                ))}
              </div>
            ) : (
              <p className="text-xs text-[rgba(0,0,0,0.35)] italic">No members</p>
            )}
            {/* Stated because it changes the fix: revoking here hits every member above. */}
            <p className="text-[10px] text-[rgba(0,0,0,0.45)] mt-2 leading-relaxed">
              Grants below are inherited by every member. Detaching one from a single user is not
              possible — it has to be removed from the group.
            </p>
          </div>
        )}

        {showRisk && risks.length > 0 && (
          <div>
            <p className="text-[9px] font-bold uppercase tracking-wider text-red-600 mb-2.5 flex items-center gap-1.5">
              <Icon icon="mdi:alert-octagon-outline" width={11} />
              Excess permissions detected
            </p>
            <div className="space-y-3">
              {risks.map((risk) => <RiskRow key={risk.code} risk={risk} />)}
            </div>
          </div>
        )}

        {showRisk && identity && risks.length === 0 && identity.riskLevel === 'NONE' && (
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-emerald-50 border border-emerald-100">
            <Icon icon="mdi:shield-check" width={14} className="text-emerald-600 flex-shrink-0" />
            <p className="text-xs text-emerald-700">
              No excess permissions found. Every attached policy was read and scoped.
            </p>
          </div>
        )}

        <div>
          <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">
            {group ? 'Group grants' : 'Access'} ({policies.length})
          </p>
          {policies.length > 0 ? (
            <div>
              {policies.map((policy, index) => (
                <PolicyRow
                  key={`${policy.policyName}-${policy.policyArn ?? policy.type ?? 'inline'}-${index}`}
                  policy={policy}
                />
              ))}
            </div>
          ) : (
            <p className="text-xs text-[rgba(0,0,0,0.35)] italic py-2">Nothing attached</p>
          )}
        </div>

        {user && (
          <>
            <div>
              <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1.5">Groups</p>
              {user.groups && user.groups.length > 0 ? (
                <div className="flex flex-wrap gap-1.5">
                  {user.groups.map((g) => (
                    <span key={g} className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-50 border border-blue-100 text-blue-700 text-[10px] font-semibold rounded-full">
                      <Icon icon="mdi:account-group-outline" width={10} />
                      {g}
                    </span>
                  ))}
                </div>
              ) : (
                <p className="text-xs text-[rgba(0,0,0,0.35)] italic">No groups</p>
              )}
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">Created</p>
                <p className="text-xs text-[rgba(0,0,0,0.7)]">{formatDate(user.createDate)}</p>
              </div>
              <div>
                <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">Last sign-in</p>
                <p className={`text-xs font-medium ${isOldDate(user.passwordLastUsed) ? 'text-red-600' : 'text-[rgba(0,0,0,0.7)]'}`}>
                  {formatDate(user.passwordLastUsed)}
                </p>
              </div>
            </div>
          </>
        )}

        {role && (
          <div>
            <p className="text-[9px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)] mb-1">Created</p>
            <p className="text-xs text-[rgba(0,0,0,0.7)]">{formatDate(role.createDate)}</p>
          </div>
        )}
      </div>
    </aside>
  );
};

export default IdentityAccessGraph;
