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

import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
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

/**
 * Ceiling for automatic fit.
 *
 * <p>The model lays the tree out at a fixed 1000px width, so on a wide screen fitting to 1:1
 * left roughly a third of the panel empty with the graph stranded in the middle. Fit is allowed
 * to scale up past 1 to consume that space; capped well below {@link MAX_ZOOM} because a
 * three-bucket collapsed graph blown up to 2× reads as a diagram of nothing.
 */
const FIT_MAX_ZOOM = 1.45;

/** Floor for the canvas. Below this the tree is too cramped to be worth panning. */
const MIN_CANVAS_H = 440;

/** Ceiling, as insurance: a measurement taken mid-scroll must not be able to run away. */
const MAX_CANVAS_H = 1600;

/** Breathing room left under the canvas, matching the page's bottom padding. */
const CANVAS_BOTTOM_GAP = 24;

/**
 * Easing for view changes the user did not drag.
 *
 * <p>Short and decelerating: long enough to show which way the canvas moved so the viewer keeps
 * their bearings, short enough that clicking zoom twice does not queue a wait.
 */
const VIEW_EASE = '220ms cubic-bezier(0.22, 1, 0.36, 1)';

/**
 * The canvas surface.
 *
 * <p>Values taken from the CloudMap canvas (`styles/cloudmap.css`, `.cytoscape-container`) so the
 * console's two graph surfaces read as the same material. The dot was previously
 * `rgba(15,23,42,0.10)`, which composites to `#E3E5E8` against the old `#FBFCFD` ground — a 1px
 * dot at about four percent contrast, which is to say present in the stylesheet and absent on a
 * screen. Solid colours here rather than an alpha, for the same reason CloudMap uses them: the
 * composited result is what matters and it should be legible from the source.
 */
const DOT_COLOR = '#d1d5db';
const DOT_SPACING = 28;
const CANVAS_BG = '#fcfcfd';

/**
 * Dot grid for the canvas, anchored to the transform.
 *
 * <p>Offsetting by the pan and scaling by the zoom is what makes a drag feel like moving over a
 * surface rather than sliding content across a static backdrop. Spacing is held in a comfortable
 * band because the ends of the zoom range are ugly in different ways: at 0.35 the raw grid falls
 * to ~10px, where 1px dots moiré, and past ~40px it is too sparse to read as a surface. The grid
 * is decoration, not a ruler, so letting it drift from the content at the extremes costs nothing.
 */
function dotSurface(zoom: number, x: number, y: number): React.CSSProperties {
  const spacing = clamp(DOT_SPACING * zoom, 16, 40);
  return {
    backgroundColor: CANVAS_BG,
    backgroundImage: `radial-gradient(circle, ${DOT_COLOR} 1px, transparent 1px)`,
    backgroundSize: `${spacing}px ${spacing}px`,
    backgroundPosition: `${x}px ${y}px`,
  };
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

/**
 * The OS "reduce motion" setting.
 *
 * <p>This canvas animates its own transform, and a moving viewport is one of the things that
 * setting exists to suppress — for vestibular disorders a graph that glides is not a nicer graph.
 * Kept local rather than promoted to a shared hook because nothing else in the app consults the
 * preference yet, and a utility with one caller is a guess about the second.
 */
function usePrefersReducedMotion(): boolean {
  const query = '(prefers-reduced-motion: reduce)';
  const [reduced, setReduced] = useState(
    () => typeof window !== 'undefined' && window.matchMedia?.(query).matches === true,
  );

  useEffect(() => {
    const mq = window.matchMedia?.(query);
    if (!mq) return;
    const onChange = () => setReduced(mq.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  return reduced;
}

/**
 * Keeps the graph inside the viewport.
 *
 * <p>Without this the canvas panned without limit: a drag could push the whole tree past the
 * edge and leave an empty grid with no indication anything was there, and no way back except
 * the fit button. Now an axis larger than the viewport can travel exactly as far as its
 * overflow, and an axis smaller than the viewport is centred instead of drifting — which is
 * also what stops a collapsed graph sitting off to one side of an otherwise empty panel.
 */
function clampView(v: CanvasView, vw: number, vh: number, graphW: number, graphH: number): CanvasView {
  if (!vw || !vh) return v;
  const scaledW = graphW * v.zoom;
  const scaledH = graphH * v.zoom;
  return {
    zoom: v.zoom,
    x: scaledW <= vw ? (vw - scaledW) / 2 : clamp(v.x, vw - scaledW, 0),
    y: scaledH <= vh ? (vh - scaledH) / 2 : clamp(v.y, vh - scaledH, 0),
  };
}

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

  /**
   * Everything shut on arrival.
   *
   * <p>Open, an account of seventy identities renders as a column thousands of pixels tall that
   * overflows the canvas immediately, so the first thing the view showed was a clipped list —
   * burying the shape of the account, which is the only thing this tab does better than the
   * cards. The count and the risk dot on each bucket are the summary; opening one is the
   * question the user asks second, not first.
   */
  const [expanded, setExpanded] = useState<Set<BucketId>>(() => new Set());
  const [showAll, setShowAll] = useState<Set<BucketId>>(() => new Set());
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [view, setView] = useState<CanvasView>({ zoom: 1, x: LAYOUT.PAD, y: 0 });
  const [dragging, setDragging] = useState(false);

  /**
   * Whether the next transform change should be animated.
   *
   * <p>On for changes the user did not make with their hand — fit, the zoom buttons, keyboard
   * focus, the reveal after expanding a bucket — where teleporting the canvas loses the
   * viewer's place. Off while dragging or scrolling, because a transition there means the graph
   * trails behind the cursor.
   *
   * <p>Turned off with a guarded setter so the wheel handler firing sixty times a second costs
   * nothing once it is already off: React bails on a state write that does not change the value.
   */
  const [smooth, setSmooth] = useState(false);
  const animate = useCallback(() => setSmooth(true), []);
  const cutAnimation = useCallback(() => setSmooth((s) => (s ? false : s)), []);

  const reduceMotion = usePrefersReducedMotion();
  /** What actually reaches the style attribute. */
  const eased = smooth && !reduceMotion;

  /**
   * Canvas height, measured rather than fixed.
   *
   * <p>It was a hardcoded 620px, which left dead white space below the panel on a normal window
   * while the node column above was still clipped — the worst of both. Measuring the distance
   * from the canvas's own top to the bottom of the window means the graph takes whatever the
   * KPI strip and controls leave it, on any screen.
   */
  const [canvasH, setCanvasH] = useState(620);

  /** Canvas width, from the same measurement. Only used to decide whether panning is possible. */
  const [canvasW, setCanvasW] = useState(0);

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
   * pixels tall, and scaling that into the canvas lands around 0.15 zoom, where every label is
   * illegible. Width always fits in one screen, so fit that, floor the zoom at something
   * readable, and let the tall case pan — which is what the collapse chevrons are for.
   *
   * <p>Allowed above 1:1 up to {@link FIT_MAX_ZOOM}. The layout is a fixed 1000px wide, so on a
   * wide panel clamping at 1 left the tree marooned in the middle of empty space.
   */
  const fit = useCallback(() => {
    const vp = viewportRef.current;
    if (!vp) return;
    const vw = vp.clientWidth;
    const vh = vp.clientHeight;
    if (!vw || !vh) return;
    const zoom = clamp(vw / graph.width, MIN_ZOOM, FIT_MAX_ZOOM);
    animate();
    setView(clampView({ zoom, x: 0, y: 0 }, vw, vh, graph.width, graph.height));
  }, [graph.width, graph.height, animate]);

  /**
   * Measure the space the page leaves for the canvas.
   *
   * <p>A layout effect, so the first paint already has the right height instead of showing the
   * 620px default for a frame and then jumping.
   *
   * <p>Measurements are coalesced into an animation frame. The tab enters under a framer-motion
   * translate, so during that animation the canvas's own top moves several pixels per frame; run
   * synchronously, every one of those became a height change, and the panel visibly settled
   * rather than appeared. Batching to a frame and ignoring deltas under 12px absorbs the whole
   * entrance, and also keeps the body observer — which fires as a consequence of our own height
   * change — from doing more than one no-op comparison.
   */
  useLayoutEffect(() => {
    let frame = 0;

    const measure = () => {
      const vp = viewportRef.current;
      if (!vp) return;
      const top = vp.getBoundingClientRect().top;
      const nextH = clamp(
        Math.round(window.innerHeight - top - CANVAS_BOTTOM_GAP),
        MIN_CANVAS_H,
        MAX_CANVAS_H,
      );
      setCanvasH((prev) => (Math.abs(prev - nextH) > 12 ? nextH : prev));
      setCanvasW((prev) => {
        const nextW = vp.clientWidth;
        return Math.abs(prev - nextW) > 1 ? nextW : prev;
      });
    };

    const schedule = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        measure();
      });
    };

    measure();
    window.addEventListener('resize', schedule);
    const observer = new ResizeObserver(schedule);
    observer.observe(document.body);
    return () => {
      if (frame) cancelAnimationFrame(frame);
      window.removeEventListener('resize', schedule);
      observer.disconnect();
    };
  }, [isLoading]);

  /**
   * Keep the view honest when the content or the canvas resizes.
   *
   * <p>Two jobs. Re-clamping stops a collapse from leaving the viewport parked past the end of a
   * now-short graph. Zooming out to reveal a newly-wider graph covers the case that matters more:
   * opening a bucket adds the leaf column, and at the zoom fitted to the collapsed tree that
   * column lands off-screen, where the only way to it is a horizontal drag nobody thinks to try.
   * It never zooms *in* — an expand that already fits is left exactly as the user had it.
   */
  const lastGraphSize = useRef({ w: graph.width, h: graph.height });
  useEffect(() => {
    const vp = viewportRef.current;
    if (!vp) return;
    const vw = vp.clientWidth;
    const vh = vp.clientHeight;

    // Animate only when the *content* changed shape — opening a bucket is worth easing so the
    // eye follows it. A window resize runs this too, and easing there makes the graph lag a
    // frame behind the edge being dragged, which reads as the panel struggling to keep up.
    const contentChanged = lastGraphSize.current.w !== graph.width
      || lastGraphSize.current.h !== graph.height;
    lastGraphSize.current = { w: graph.width, h: graph.height };
    if (contentChanged) animate(); else cutAnimation();

    setView((v) => {
      const zoom = graph.width * v.zoom > vw
        ? clamp(vw / graph.width, MIN_ZOOM, v.zoom)
        : v.zoom;
      return clampView({ ...v, zoom }, vw, vh, graph.width, graph.height);
    });
  }, [graph.width, graph.height, canvasH, animate, cutAnimation]);

  // Fit once per account rather than on every expand: re-fitting when a chevron opens would
  // yank the canvas out from under the click that opened it. A layout effect so the fitted
  // transform is the first one painted — with a plain effect the graph appeared at 1:1 in the
  // top-left corner for a frame and then snapped into place.
  const fittedForRef = useRef<string | null>(null);
  useLayoutEffect(() => {
    if (isLoading || graph.totalLeaves === 0) return;
    const key = `${accountId}|${provider}`;
    if (fittedForRef.current === key) return;
    fittedForRef.current = key;
    fit();
    // Nothing to ease from on the first paint, and animating from the default transform reads
    // as the graph sliding in from the corner.
    setSmooth(false);
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
    // Returning the previous set when it is already open keeps React from re-rendering, and more
    // importantly keeps the `graph` memo's identity stable — otherwise every keystroke in the
    // search box rebuilt the whole layout twice.
    setExpanded((prev) => (ALL_BUCKETS.every((b) => prev.has(b)) ? prev : new Set(ALL_BUCKETS)));
  }, [narrowed, groupQuery, flaggedOnly]);

  // ── Pan / zoom ────────────────────────────────────────────────────────────

  // Current view and content size, for the wheel listener. It is registered once with a
  // non-passive handler — JSX onWheel cannot preventDefault — so it cannot close over state.
  const viewRef = useRef(view);
  viewRef.current = view;
  const sizeRef = useRef({ w: graph.width, h: graph.height });
  sizeRef.current = { w: graph.width, h: graph.height };

  /**
   * Plain wheel pans, Ctrl/Cmd+wheel zooms toward the cursor, matching CloudMap.
   *
   * <p>The gesture is handed back to the page once the canvas has nothing left to give in that
   * direction. Previously this called {@code preventDefault} on every wheel event, so putting the
   * pointer over the graph — which now fills most of the window — trapped the page's scroll
   * entirely and there was no way to reach anything below it.
   */
  useEffect(() => {
    const el = viewportRef.current;
    if (!el) return;

    const onWheel = (e: WheelEvent) => {
      const vw = el.clientWidth;
      const vh = el.clientHeight;

      if (e.ctrlKey || e.metaKey) {
        e.preventDefault();
        cutAnimation();
        const rect = el.getBoundingClientRect();
        const cx = e.clientX - rect.left;
        const cy = e.clientY - rect.top;
        setView((v) => {
          const zoom = clamp(v.zoom * (e.deltaY < 0 ? 1.12 : 1 / 1.12), MIN_ZOOM, MAX_ZOOM);
          const k = zoom / v.zoom;
          // Keep the graph point under the cursor pinned to the cursor.
          const next = { zoom, x: cx - (cx - v.x) * k, y: cy - (cy - v.y) * k };
          return clampView(next, vw, vh, sizeRef.current.w, sizeRef.current.h);
        });
        return;
      }

      const { w, h } = sizeRef.current;
      const v = viewRef.current;
      const overflowY = h * v.zoom - vh;
      const overflowX = w * v.zoom - vw;

      // Room left in the direction of travel? A 1px tolerance keeps a rounded bound from
      // reading as "still scrollable" forever.
      const wantsUp = e.deltaY < 0;
      const canPanY = overflowY > 1 && (wantsUp ? v.y < -1 : v.y > vh - h * v.zoom + 1);
      const canPanX = overflowX > 1
        && (e.deltaX < 0 ? v.x < -1 : v.x > vw - w * v.zoom + 1)
        && Math.abs(e.deltaX) > 0;

      if (!canPanY && !canPanX) return; // let the page scroll

      e.preventDefault();
      cutAnimation();
      setView((cur) => clampView(
        { ...cur, x: cur.x - e.deltaX, y: cur.y - e.deltaY },
        vw, vh, sizeRef.current.w, sizeRef.current.h,
      ));
    };

    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [cutAnimation]);

  const dragRef = useRef<{ px: number; py: number; vx: number; vy: number } | null>(null);

  const onMouseDown = (e: React.MouseEvent) => {
    // Let the nodes have their clicks; only empty canvas starts a pan.
    if ((e.target as HTMLElement).closest('button, a')) return;
    dragRef.current = { px: e.clientX, py: e.clientY, vx: view.x, vy: view.y };
    cutAnimation();
    setDragging(true);
  };

  /**
   * Drag to pan, coalesced to one update per animation frame.
   *
   * <p>A mouse reporting at 120Hz fires roughly twice per frame, and each event re-rendered the
   * whole tree — every node and every edge path — so a drag across an expanded bucket did
   * measurably more work than the display could use. Collapsing the events to the frame boundary
   * gives the same motion for half the renders, and reads as smoother rather than merely cheaper
   * because no frame is spent on a transform that is overwritten before it paints.
   */
  useEffect(() => {
    if (!dragging) return;

    let frame = 0;
    let latest: { x: number; y: number } | null = null;

    const flush = () => {
      frame = 0;
      const point = latest;
      const d = dragRef.current;
      const vp = viewportRef.current;
      if (!point || !d || !vp) return;
      setView((v) => clampView(
        { ...v, x: d.vx + (point.x - d.px), y: d.vy + (point.y - d.py) },
        vp.clientWidth, vp.clientHeight, sizeRef.current.w, sizeRef.current.h,
      ));
    };

    const onMove = (e: MouseEvent) => {
      latest = { x: e.clientX, y: e.clientY };
      if (!frame) frame = requestAnimationFrame(flush);
    };

    const onUp = () => {
      if (frame) {
        cancelAnimationFrame(frame);
        flush();
      }
      dragRef.current = null;
      setDragging(false);
    };

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      if (frame) cancelAnimationFrame(frame);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [dragging]);

  const zoomBy = (factor: number) => {
    animate();
    setView((v) => {
      const vp = viewportRef.current;
      const vw = vp?.clientWidth ?? 0;
      const vh = vp?.clientHeight ?? 0;
      const cx = vw / 2;
      const cy = vh / 2;
      const zoom = clamp(v.zoom * factor, MIN_ZOOM, MAX_ZOOM);
      const k = zoom / v.zoom;
      return clampView(
        { zoom, x: cx - (cx - v.x) * k, y: cy - (cy - v.y) * k },
        vw, vh, sizeRef.current.w, sizeRef.current.h,
      );
    });
  };

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
    animate();
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
      if (nx === v.x && ny === v.y) return v;
      return clampView(
        { ...v, x: nx, y: ny },
        vp.clientWidth, vp.clientHeight, sizeRef.current.w, sizeRef.current.h,
      );
    });
  }, [animate]);

  /** Bucket opened on the last click, so the reveal below knows what to bring into view. */
  const justOpenedRef = useRef<BucketId | null>(null);

  const toggleBucket = (id: BucketId) => setExpanded((prev) => {
    const next = new Set(prev);
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
      justOpenedRef.current = id;
    }
    return next;
  });

  /**
   * Bring a freshly-opened bucket into view.
   *
   * <p>A bucket re-centres on its own leaves when it opens, so on a tall graph the branch the
   * user just clicked can end up below the fold — the click appears to do nothing except make a
   * scrollbar's worth of content appear somewhere off screen. Runs after the clamp effect above
   * so it pans from the corrected position, and rides the same easing, which is what makes the
   * open read as the graph growing rather than as the canvas jumping.
   */
  useEffect(() => {
    const id = justOpenedRef.current;
    if (!id) return;
    justOpenedRef.current = null;
    const bucket = graph.buckets.find((b) => b.id === id);
    if (bucket) ensureVisible(bucket.x, bucket.y, bucket.w, bucket.h);
  }, [graph, ensureVisible]);

  const allOpen = graph.buckets.length > 0 && graph.buckets.every((b) => expanded.has(b.id));

  /** Whether either axis overflows the canvas, i.e. whether dragging would do anything. */
  const pannable = graph.height * view.zoom > canvasH + 1
    || (canvasW > 0 && graph.width * view.zoom > canvasW + 1);

  // ── Focus / dim ───────────────────────────────────────────────────────────

  const dimBucket = (id: BucketId) => Boolean(selectedBucketId) && selectedBucketId !== id;
  const dimLeaf = (leaf: LeafNode) => Boolean(selectedId) && selectedId !== leaf.id;
  const dimStyle = (dim: boolean): React.CSSProperties =>
    dim ? { opacity: 0.28, transition: 'opacity 160ms ease' } : { transition: 'opacity 160ms ease' };

  // ── Empty / loading ───────────────────────────────────────────────────────

  if (isLoading) {
    return (
      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white shadow-sm overflow-hidden">
        {/* Same surface as the live canvas, so the panel does not change material when the data
            lands — only what is drawn on it. */}
        <div
          className="flex flex-col items-center justify-center gap-3"
          style={{ height: canvasH, ...dotSurface(1, 0, 0) }}
        >
          <Icon icon="mdi:graph-outline" width={30} className="text-[rgba(0,0,0,0.15)] animate-pulse" />
          <p className="text-xs text-[rgba(0,0,0,0.4)]">Building access graph…</p>
        </div>
      </div>
    );
  }

  if (graph.buckets.length === 0) {
    return (
      <div
        className="rounded-xl border border-[rgba(0,0,0,0.12)] shadow-sm text-center flex flex-col items-center justify-center overflow-hidden"
        style={{ height: canvasH, ...dotSurface(1, 0, 0) }}
      >
        <div className="w-14 h-14 bg-white border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-3">
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
            height: canvasH,
            // Only claim the graph is draggable when there is somewhere to drag it. A grab
            // cursor over a graph that is fully in view promises movement that never happens.
            cursor: dragging ? 'grabbing' : pannable ? 'grab' : 'default',
            ...dotSurface(view.zoom, view.x, view.y),
            // Matches the content layer so the grid travels with the tree during an animated
            // move instead of arriving a beat early.
            transition: eased ? `background-position ${VIEW_EASE}, background-size ${VIEW_EASE}` : undefined,
          }}
        >
          <div
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: graph.width,
              height: graph.height,
              transform: `translate3d(${view.x}px, ${view.y}px, 0) scale(${view.zoom})`,
              transformOrigin: '0 0',
              // Promoted to its own layer so a pan compositves instead of repainting seventy
              // nodes and their edges. translate3d rather than translate for the same reason.
              willChange: 'transform',
              transition: eased ? `transform ${VIEW_EASE}` : undefined,
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
            Scroll or drag to pan · ⌘/Ctrl + scroll to zoom
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
