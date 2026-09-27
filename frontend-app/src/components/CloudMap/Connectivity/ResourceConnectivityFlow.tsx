import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { AWS_ICONS, NODE_TYPE_DATA, TAILWIND_COLORS } from '../../../types/cloudmap';
import type { GraphElement, NodeData } from '../../../types/cloudmap';
import {
  CCOLS,
  CFLOW,
  GLOBAL_VPC,
  buildConnectivityFlow,
  buildConnectivityIndex,
  detailsOf,
  firstConnectivityBranch,
  flowEdge,
  locate,
  trunkFor,
  type ConnectivityFlow,
  type PeerFlowNode,
} from './connectivityFlowModel';

/**
 * Resource connectivity, as a drill-down:
 * account → VPC → subnet → service → resource → what that resource is connected to.
 *
 * <p>Live view draws the whole topology at once, which answers "what is in this account" well
 * and "what talks to this volume" badly — on a real estate that answer is three edges inside a
 * hairball of hundreds. Here the containment path stays visible and one resource's connections
 * get their own column, labelled with the relationship. Both views read the same
 * {@code /api/xamops/cloudmap/graph} response, so neither can disagree about what is connected.
 *
 * <p>Rendered as positioned DOM over an SVG edge layer rather than through Cytoscape. The tree
 * is six fixed columns with one branch open, so {@link connectivityFlowModel} places it exactly
 * — identical across refreshes, no async layout settle, and every node stays a real focusable
 * button rather than a hit-test on a bitmap.
 */

interface ResourceConnectivityFlowProps {
  accountId: string | null;
  accountName: string;
  /** The same elements live view renders — nodes and edges from the cloudmap graph endpoint. */
  elements: GraphElement[];
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

// ─── Canvas ──────────────────────────────────────────────────────────────────

interface CanvasView {
  zoom: number;
  x: number;
  y: number;
}

const MIN_ZOOM = 0.3;
const MAX_ZOOM = 1.8;
/** Ceiling for automatic fit. Above this a two-node flow reads as a diagram of nothing. */
const FIT_MAX_ZOOM = 1.1;

const ACCENT = '#4285F4';
const EDGE_IDLE = '#d7dae0';
/** Connections are a different kind of edge from containment, so a different colour. */
const EDGE_LINK = '#8b5cf6';

const DOT_COLOR = '#d1d5db';
const DOT_SPACING = 28;
const CANVAS_BG = '#fcfcfd';

/** Matches the live canvas surface, so the two views read as the same material. */
function dotSurface(zoom: number, x: number, y: number): React.CSSProperties {
  const spacing = clamp(DOT_SPACING * zoom, 16, 40);
  return {
    backgroundColor: CANVAS_BG,
    backgroundImage: `radial-gradient(circle, ${DOT_COLOR} 1px, transparent 1px)`,
    backgroundSize: `${spacing}px ${spacing}px`,
    backgroundPosition: `${x}px ${y}px`,
  };
}

/**
 * Keeps the flow inside the viewport.
 *
 * <p>Without it a drag can push the whole tree past the edge, leaving an empty grid with no sign
 * anything was there. An axis larger than the viewport travels exactly as far as its overflow;
 * an axis smaller than it is centred, which also stops a collapsed flow sitting off to one side.
 */
function clampView(
  v: CanvasView, vw: number, vh: number, graphW: number, graphH: number,
): CanvasView {
  if (!vw || !vh) return v;
  const scaledW = graphW * v.zoom;
  const scaledH = graphH * v.zoom;
  return {
    zoom: v.zoom,
    x: scaledW <= vw ? (vw - scaledW) / 2 : clamp(v.x, vw - scaledW, 0),
    y: scaledH <= vh ? (vh - scaledH) / 2 : clamp(v.y, vh - scaledH, 0),
  };
}

/**
 * The OS "reduce motion" setting. This canvas animates its own transform, and a moving viewport
 * is one of the things that setting exists to suppress.
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

const VIEW_EASE = '220ms cubic-bezier(0.22, 1, 0.36, 1)';

/** Service hue for a node type, falling back to a neutral grey. */
const hueFor = (type: string): string => {
  const info = NODE_TYPE_DATA[type];
  const palette = info ? TAILWIND_COLORS[info.color as keyof typeof TAILWIND_COLORS] : undefined;
  return palette ? palette[500] : '#94a3b8';
};

/** Shortens a long id from the middle — the ends of an ARN carry the identity. */
const shorten = (id: string, max = 34): string => {
  if (id.length <= max) return id;
  const head = Math.ceil((max - 1) / 2);
  return `${id.slice(0, head)}…${id.slice(id.length - (max - head - 1))}`;
};

/** Which column a click just opened, so the canvas can pan to it. */
type Level = 'vpc' | 'subnet' | 'service' | 'resource';

// ─── Component ───────────────────────────────────────────────────────────────

const ResourceConnectivityFlow: React.FC<ResourceConnectivityFlowProps> = ({
  accountId,
  accountName,
  elements,
  loading,
  error,
  onRefresh,
}) => {
  const [search, setSearch] = useState('');
  const [selectedVpc, setSelectedVpc] = useState<string | null>(null);
  const [selectedSubnet, setSelectedSubnet] = useState<string | null>(null);
  const [selectedService, setSelectedService] = useState<string | null>(null);
  const [selectedResource, setSelectedResource] = useState<string | null>(null);
  const [showAllVpcs, setShowAllVpcs] = useState(false);
  const [showAllSubnets, setShowAllSubnets] = useState(false);
  const [showAllServices, setShowAllServices] = useState(false);
  const [showAllResources, setShowAllResources] = useState(false);
  const [showDetails, setShowDetails] = useState(true);
  const [copied, setCopied] = useState<string | null>(null);

  const [view, setView] = useState<CanvasView>({ zoom: 1, x: CFLOW.PAD, y: 0 });
  const [dragging, setDragging] = useState(false);
  const [smooth, setSmooth] = useState(false);
  const [canvasW, setCanvasW] = useState(0);

  const viewportRef = useRef<HTMLDivElement>(null);
  const reduceMotion = usePrefersReducedMotion();
  const eased = smooth && !reduceMotion;

  const animate = useCallback(() => setSmooth(true), []);
  // Guarded so a wheel handler firing sixty times a second costs nothing once it is off.
  const cutAnimation = useCallback(() => setSmooth((s) => (s ? false : s)), []);

  const term = search.trim().toLowerCase();

  // Indexed once per payload: the parent-chain walks, the detail scan and the adjacency build
  // are the expensive part, and redoing them on every expand is the difference between a click
  // that lands in the same frame and one that does not.
  const index = useMemo(() => buildConnectivityIndex(elements), [elements]);

  const flow: ConnectivityFlow | null = useMemo(
    () =>
      accountId
        ? buildConnectivityFlow({
            index, accountId, accountName,
            selectedVpc, selectedSubnet, selectedService, selectedResource,
            search: term, showAllVpcs, showAllSubnets, showAllServices, showAllResources,
          })
        : null,
    [
      index, accountId, accountName, selectedVpc, selectedSubnet, selectedService,
      selectedResource, term, showAllVpcs, showAllSubnets, showAllServices, showAllResources,
    ],
  );

  /** The node behind the current selection, for the detail panel. */
  const selectedNode: NodeData | null = useMemo(
    () => (selectedResource ? index.byId.get(selectedResource) ?? null : null),
    [selectedResource, index],
  );

  /**
   * Open the first matching branch when a search narrows the tree.
   *
   * <p>Otherwise typing a resource name lands on a column of collapsed VPCs, and a match four
   * levels down looks like no match at all.
   */
  useEffect(() => {
    if (!term || !accountId) return;
    const branch = firstConnectivityBranch(index, accountId, term);
    setSelectedVpc(branch.vpc);
    setSelectedSubnet(branch.subnet);
    setSelectedService(branch.service);
    setSelectedResource(branch.resource);
  }, [term, index, accountId]);

  /** A different account is a different estate; a stale selection would point at nothing. */
  useEffect(() => {
    setSelectedVpc(null);
    setSelectedSubnet(null);
    setSelectedService(null);
    setSelectedResource(null);
    setShowAllVpcs(false);
    setShowAllSubnets(false);
    setShowAllServices(false);
    setShowAllResources(false);
  }, [accountId]);

  const graphW = flow?.width ?? 0;
  const graphH = flow?.height ?? 0;

  // ── Fit ───────────────────────────────────────────────────────────────────

  /**
   * Fit the width, not the bounding box.
   *
   * <p>Fitting height too sounds right and is not: an open service of forty resources is over
   * 2,000px tall, and scaling that into the canvas lands near 0.3 zoom where every label is
   * illegible. Width always fits one screen, so fit that and let the tall case pan.
   *
   * @returns false when the canvas has no box yet, so the caller can try again
   */
  const fit = useCallback((): boolean => {
    const vp = viewportRef.current;
    if (!vp || !graphW) return false;
    const vw = vp.clientWidth;
    const vh = vp.clientHeight;
    if (!vw || !vh) return false;
    const zoom = clamp(vw / graphW, MIN_ZOOM, FIT_MAX_ZOOM);
    animate();
    setView(clampView({ zoom, x: 0, y: 0 }, vw, vh, graphW, graphH));
    return true;
  }, [graphW, graphH, animate]);

  /** Track the canvas width, so a resize or a reveal re-fits. */
  useLayoutEffect(() => {
    let frame = 0;
    const measure = () => {
      const vp = viewportRef.current;
      // No box at all: this view is not the active mode, so measuring would yield zero and the
      // fit below would be computed against nothing.
      if (!vp || vp.clientWidth === 0) return;
      setCanvasW((prev) => (Math.abs(prev - vp.clientWidth) > 1 ? vp.clientWidth : prev));
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
    if (viewportRef.current) observer.observe(viewportRef.current);
    return () => {
      if (frame) cancelAnimationFrame(frame);
      window.removeEventListener('resize', schedule);
      observer.disconnect();
    };
  }, [loading]);

  /** Re-clamp when the content or the canvas changes shape, without changing the zoom. */
  useEffect(() => {
    const vp = viewportRef.current;
    if (!vp || !graphW) return;
    cutAnimation();
    setView((v) => clampView(v, vp.clientWidth, vp.clientHeight, graphW, graphH));
  }, [graphW, graphH, canvasW, cutAnimation]);

  // Fit once per account rather than on every expand: re-fitting when a node opens yanks the
  // canvas out from under the click that opened it.
  const fittedFor = useRef<string | null>(null);
  useLayoutEffect(() => {
    if (!flow || loading || !accountId) return;
    if (fittedFor.current === accountId) return;
    if (!fit()) return;
    fittedFor.current = accountId;
    // Nothing to ease from on the first paint.
    setSmooth(false);
  }, [flow, loading, accountId, canvasW, fit]);

  // ── Pan / zoom ────────────────────────────────────────────────────────────

  // The wheel listener is registered once, non-passive — JSX onWheel cannot preventDefault —
  // so it cannot close over state and reads these instead.
  const viewRef = useRef(view);
  viewRef.current = view;
  const sizeRef = useRef({ w: graphW, h: graphH });
  sizeRef.current = { w: graphW, h: graphH };

  /**
   * Plain wheel pans, Ctrl/Cmd+wheel zooms toward the cursor, matching the live canvas.
   *
   * <p>The gesture is handed back to the page once the canvas has nothing left to give in that
   * direction, so the pointer crossing this view cannot trap the page's scroll.
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
          const next = { zoom, x: cx - (cx - v.x) * k, y: cy - (cy - v.y) * k };
          return clampView(next, vw, vh, sizeRef.current.w, sizeRef.current.h);
        });
        return;
      }

      const { w, h } = sizeRef.current;
      const v = viewRef.current;
      const overflowY = h * v.zoom - vh;
      const overflowX = w * v.zoom - vw;
      const canPanY = overflowY > 1 && (e.deltaY < 0 ? v.y < -1 : v.y > vh - h * v.zoom + 1);
      const canPanX = overflowX > 1 && Math.abs(e.deltaX) > 0
        && (e.deltaX < 0 ? v.x < -1 : v.x > vw - w * v.zoom + 1);

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
    if ((e.target as HTMLElement).closest('button, a, input, select')) return;
    dragRef.current = { px: e.clientX, py: e.clientY, vx: view.x, vy: view.y };
    cutAnimation();
    setDragging(true);
  };

  /** Drag to pan, coalesced to one update per frame. */
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
   * <p>Wired to focus so keyboard traversal works, and used when a click opens a new column:
   * tabbing is the only way through this tree without a mouse, and a focus ring outside the
   * viewport is no navigation.
   */
  const ensureVisible = useCallback(
    (x: number, y: number, w: number, h: number) => {
      const vp = viewportRef.current;
      if (!vp) return;
      animate();
      setView((v) => {
        const m = 28;
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
    },
    [animate],
  );

  /**
   * Bring a freshly-opened column into view, at the current zoom.
   *
   * <p>Six columns fully open are about 1,900px wide, so zooming out to fit them all lands near
   * 0.7 and every label turns to mush. Panning instead keeps the text at whatever size the
   * reader chose and puts the new column where they are already looking — which is also the
   * only reason a horizontal offscreen column is not a dead end, since nobody thinks to try a
   * sideways drag.
   */
  const justOpenedRef = useRef<Level | null>(null);
  useEffect(() => {
    const level = justOpenedRef.current;
    if (!level || !flow) return;
    justOpenedRef.current = null;
    const target =
      level === 'vpc' ? flow.subnets[0]
        : level === 'subnet' ? flow.services[0]
          : level === 'service' ? flow.resources[0]
            : flow.peers[0];
    if (target) ensureVisible(target.x, target.y, target.w, target.h);
  }, [flow, ensureVisible]);

  // ── Selection ─────────────────────────────────────────────────────────────

  /**
   * Opening a level clears the levels below it.
   *
   * <p>A subnet id from the previously open VPC would otherwise stay selected and the model
   * would drop it silently, leaving the reader with a VPC open and no explanation for why the
   * columns to the right went blank.
   */
  const openVpc = (id: string) => {
    const closing = selectedVpc === id;
    setSelectedVpc(closing ? null : id);
    setSelectedSubnet(null);
    setSelectedService(null);
    setSelectedResource(null);
    setShowAllSubnets(false);
    setShowAllServices(false);
    setShowAllResources(false);
    if (!closing) justOpenedRef.current = 'vpc';
  };

  const openSubnet = (id: string) => {
    const closing = selectedSubnet === id;
    setSelectedSubnet(closing ? null : id);
    setSelectedService(null);
    setSelectedResource(null);
    setShowAllServices(false);
    setShowAllResources(false);
    if (!closing) justOpenedRef.current = 'subnet';
  };

  const openService = (type: string) => {
    const closing = selectedService === type;
    setSelectedService(closing ? null : type);
    setSelectedResource(null);
    setShowAllResources(false);
    if (!closing) justOpenedRef.current = 'service';
  };

  const openResource = (id: string) => {
    const closing = selectedResource === id;
    setSelectedResource(closing ? null : id);
    if (!closing) justOpenedRef.current = 'resource';
  };

  /**
   * Follow a connection: re-root the drill-down on the peer.
   *
   * <p>This is what makes the view walkable beyond one hop. Without it a reader who finds that
   * an instance sits behind a target group has to go back to the account and drill down again
   * to see what else that target group serves — which is exactly the work the live view's
   * hairball already forces on them.
   */
  const followPeer = (peer: PeerFlowNode) => {
    if (peer.external) return;
    const at = locate(index, peer.id);
    if (!at) return;
    setSearch('');
    setSelectedVpc(at.vpc);
    setSelectedSubnet(at.subnet);
    setSelectedService(at.service);
    setSelectedResource(at.resource);
    setShowAllSubnets(true);
    setShowAllServices(true);
    setShowAllResources(true);
    justOpenedRef.current = 'resource';
  };

  /** Copies an id — what gets pasted into a console, a CLI call or a ticket. */
  const copyId = async (id: string) => {
    try {
      await navigator.clipboard.writeText(id);
      setCopied(id);
      setTimeout(() => setCopied((c) => (c === id ? null : c)), 1600);
    } catch {
      // Clipboard denied. The title attribute still carries the full id.
    }
  };

  // ── States ────────────────────────────────────────────────────────────────

  if (!accountId) {
    return (
      <Shell>
        <Centered
          icon="mdi:cloud-off-outline"
          title="No account selected"
          body="Pick an account from the sidebar to see how its resources connect."
        />
      </Shell>
    );
  }

  if (loading && elements.length === 0) {
    return (
      <Shell>
        <Centered
          icon="mdi:sitemap-outline"
          title="Reading the topology…"
          body="Listing resources and working out what connects to what."
          spin
        />
      </Shell>
    );
  }

  if (error && elements.length === 0) {
    return (
      <Shell>
        <Centered
          icon="mdi:alert-circle-outline"
          title="Could not read the topology"
          body={error}
          action={{ label: 'Try again', onClick: onRefresh }}
        />
      </Shell>
    );
  }

  if (!flow || (flow.vpcs.length === 0 && !term)) {
    return (
      <Shell>
        <Centered
          icon="mdi:sitemap-outline"
          title="Nothing to draw yet"
          body="No resources came back for this account. If live view shows resources, reload the topology."
          action={{ label: 'Reload topology', onClick: onRefresh }}
        />
      </Shell>
    );
  }

  const emptySearch = flow.vpcs.length === 0 && term !== '';
  const pannable = canvasW > 0
    && (flow.width * view.zoom > canvasW + 1 || flow.height * view.zoom > (viewportRef.current?.clientHeight ?? 0) + 1);

  // ── Edge anchors ──────────────────────────────────────────────────────────

  const accountRight = flow.account.x + flow.account.w;
  const accountMid = flow.account.y + flow.account.h / 2;
  const vpcTrunk = trunkFor(accountRight);
  const subnetTrunk = trunkFor(CCOLS.vpc + CFLOW.VPC_W);
  const serviceTrunk = trunkFor(CCOLS.subnet + CFLOW.SUBNET_W);
  const resourceTrunk = trunkFor(CCOLS.service + CFLOW.SERVICE_W);
  const peerTrunk = trunkFor(CCOLS.resource + CFLOW.RESOURCE_W);

  const openVpcNode = flow.vpcs.find((v) => v.selected) ?? null;
  const openSubnetNode = flow.subnets.find((s) => s.selected) ?? null;
  const openServiceNode = flow.services.find((s) => s.selected) ?? null;
  const openResourceNode = flow.resources.find((r) => r.selected) ?? null;

  return (
    <Shell>
      {/* Toolbar: breadcrumb left, search and view controls right. */}
      <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 border-b border-[rgba(0,0,0,0.07)] bg-white">
        <nav aria-label="Selected path" className="flex items-center gap-1.5 text-[11.5px] min-w-0">
          <Crumb icon="mdi:account-circle-outline" label={flow.account.name} />
          <Sep />
          {flow.selectedVpc ? (
            <>
              <Crumb
                icon={flow.selectedVpc === GLOBAL_VPC ? 'mdi:earth' : 'mdi:cloud-outline'}
                label={openVpcNode?.label ?? flow.selectedVpc}
              />
              <Sep />
              {flow.selectedSubnet ? (
                <>
                  <Crumb icon="mdi:lan" label={openSubnetNode?.label ?? flow.selectedSubnet} />
                  <Sep />
                  {flow.selectedService ? (
                    <>
                      <Crumb icon="mdi:shape-outline" label={flow.selectedService} />
                      <Sep />
                      {flow.selectedResource ? (
                        <Crumb
                          icon="mdi:cube-outline"
                          label={openResourceNode?.label ?? flow.selectedResource}
                        />
                      ) : <Hint>pick a resource</Hint>}
                    </>
                  ) : <Hint>pick a service</Hint>}
                </>
              ) : <Hint>pick a subnet</Hint>}
            </>
          ) : <Hint>pick a VPC</Hint>}
        </nav>

        <div className="flex items-center gap-2 flex-shrink-0">
          <span className="text-[10.5px] text-[rgba(0,0,0,0.4)] tabular-nums hidden lg:inline">
            {flow.matchedResources.toLocaleString('en-US')} resources ·{' '}
            {flow.account.serviceCount} services · {flow.account.linkCount} connections
          </span>

          <div className="relative">
            <Icon
              icon="mdi:magnify"
              width={14}
              className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]"
            />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Find by name, id, type, IP…"
              aria-label="Find a resource by name, id, type or detail"
              className="w-[210px] text-[12px] border border-[rgba(0,0,0,0.15)] rounded-lg pl-7 pr-7 py-1.5 bg-white text-black placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[#4285F4]"
            />
            {search && (
              <button
                type="button"
                onClick={() => setSearch('')}
                aria-label="Clear search"
                className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.6)]"
              >
                <Icon icon="mdi:close-circle" width={14} />
              </button>
            )}
          </div>

          <div className="w-px h-5 bg-[rgba(0,0,0,0.1)]" />

          <IconBtn
            label={showDetails ? 'Hide details panel' : 'Show details panel'}
            icon="mdi:information-outline"
            onClick={() => setShowDetails((s) => !s)}
            active={showDetails}
          />
          <IconBtn label="Zoom out" icon="mdi:minus" onClick={() => zoomBy(1 / 1.2)} />
          <span className="text-[10px] font-bold text-[rgba(0,0,0,0.4)] tabular-nums w-9 text-center">
            {Math.round(view.zoom * 100)}%
          </span>
          <IconBtn label="Zoom in" icon="mdi:plus" onClick={() => zoomBy(1.2)} />
          <IconBtn label="Fit to width" icon="mdi:fit-to-screen-outline" onClick={fit} />
          <IconBtn label="Reload topology" icon="mdi:refresh" onClick={onRefresh} spin={loading} />
        </div>
      </div>

      {/* Canvas */}
      <div
        ref={viewportRef}
        onMouseDown={onMouseDown}
        className="relative flex-1 min-h-0 overflow-hidden select-none"
        style={{
          cursor: dragging ? 'grabbing' : pannable ? 'grab' : 'default',
          ...dotSurface(view.zoom, view.x, view.y),
        }}
      >
        {emptySearch ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center text-center px-6">
            <Icon icon="mdi:magnify-close" width={26} className="text-[rgba(0,0,0,0.18)] mb-2" />
            <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.6)]">No matches</p>
            <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] mt-1">
              Nothing in this account matches “{search.trim()}”.
            </p>
          </div>
        ) : (
          <div
            className="absolute top-0 left-0 origin-top-left"
            style={{
              width: flow.width,
              height: flow.height,
              transform: `translate3d(${view.x}px, ${view.y}px, 0) scale(${view.zoom})`,
              transition: eased ? `transform ${VIEW_EASE}` : undefined,
            }}
          >
            {/* Edges under the nodes, so a node's shadow sits on top of its own connector. */}
            <svg
              width={flow.width}
              height={flow.height}
              viewBox={`0 0 ${flow.width} ${flow.height}`}
              className="absolute inset-0 pointer-events-none"
              aria-hidden="true"
            >
              {flow.vpcs.map((v) => (
                <path
                  key={`e-acc-${v.id}`}
                  d={flowEdge(accountRight, accountMid, v.x, v.y + v.h / 2, vpcTrunk)}
                  fill="none" stroke={v.selected ? ACCENT : EDGE_IDLE}
                  strokeWidth={v.selected ? 1.8 : 1.2}
                />
              ))}
              {flow.vpcMore && (
                <path
                  d={flowEdge(accountRight, accountMid, flow.vpcMore.x,
                    flow.vpcMore.y + flow.vpcMore.h / 2, vpcTrunk)}
                  fill="none" stroke={EDGE_IDLE} strokeWidth={1.2} strokeDasharray="3 3"
                />
              )}

              {openVpcNode && flow.subnets.map((s) => (
                <path
                  key={`e-vpc-${s.id}`}
                  d={flowEdge(openVpcNode.x + openVpcNode.w, openVpcNode.y + openVpcNode.h / 2,
                    s.x, s.y + s.h / 2, subnetTrunk)}
                  fill="none" stroke={s.selected ? ACCENT : EDGE_IDLE}
                  strokeWidth={s.selected ? 1.8 : 1.2}
                />
              ))}

              {openSubnetNode && flow.services.map((s) => (
                <path
                  key={`e-sub-${s.type}`}
                  d={flowEdge(openSubnetNode.x + openSubnetNode.w,
                    openSubnetNode.y + openSubnetNode.h / 2, s.x, s.y + s.h / 2, serviceTrunk)}
                  fill="none" stroke={s.selected ? ACCENT : EDGE_IDLE}
                  strokeWidth={s.selected ? 1.8 : 1.2}
                />
              ))}

              {openServiceNode && flow.resources.map((r) => (
                <path
                  key={`e-svc-${r.id}`}
                  d={flowEdge(openServiceNode.x + openServiceNode.w,
                    openServiceNode.y + openServiceNode.h / 2, r.x, r.y + r.h / 2, resourceTrunk)}
                  fill="none" stroke={r.selected ? ACCENT : EDGE_IDLE}
                  strokeWidth={r.selected ? 1.8 : 1.2}
                />
              ))}

              {/* Connections. A different colour from containment on purpose: "this volume is in
                  this subnet" and "this volume attaches to that instance" are different claims,
                  and one palette for both invites reading them as the same thing. Dashed when
                  the relationship was inferred from a detail rather than stated as an edge. */}
              {openResourceNode && flow.peers.map((p) => (
                <path
                  key={`e-peer-${p.id}-${p.relation}`}
                  d={flowEdge(openResourceNode.x + openResourceNode.w,
                    openResourceNode.y + openResourceNode.h / 2, p.x, p.y + p.h / 2, peerTrunk)}
                  fill="none" stroke={EDGE_LINK} strokeWidth={1.5}
                  strokeDasharray={p.derived || p.external ? '4 3' : undefined}
                />
              ))}
            </svg>

            {/* Account */}
            <div
              className="absolute rounded-xl bg-white border border-[rgba(0,0,0,0.16)] shadow-[0_2px_8px_rgba(0,0,0,0.06)] px-3.5 py-2.5 flex items-center gap-3"
              style={{
                left: flow.account.x, top: flow.account.y,
                width: flow.account.w, height: flow.account.h,
              }}
            >
              <div className="w-9 h-9 rounded-lg bg-[rgba(0,0,0,0.05)] border border-[rgba(0,0,0,0.07)] flex items-center justify-center flex-shrink-0">
                <Icon icon="mdi:aws" width={19} className="text-[rgba(0,0,0,0.6)]" />
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-[13px] font-bold text-black truncate" title={flow.account.name}>
                  {flow.account.name}
                </p>
                <p className="font-mono text-[10.5px] text-[rgba(0,0,0,0.45)] truncate">
                  {flow.account.accountId}
                </p>
                <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] mt-0.5">
                  {flow.account.vpcCount} {flow.account.vpcCount === 1 ? 'VPC' : 'VPCs'} ·{' '}
                  {flow.account.resourceCount.toLocaleString('en-US')} resources
                </p>
              </div>
            </div>

            {/* VPCs */}
            {flow.vpcs.map((v) => (
              <TreeNode
                key={v.id}
                node={v}
                selected={v.selected}
                icon={v.isBucket ? 'mdi:earth' : 'mdi:cloud-outline'}
                title={v.label}
                subtitle={v.isBucket
                  ? 'Global services — S3, IAM, CloudFront and the rest'
                  : [v.id, v.region, v.cidr].filter(Boolean).join(' · ')}
                meta={v.isBucket
                  ? `${v.serviceCount} services · ${v.resourceCount} resources`
                  : `${v.subnetCount} ${v.subnetCount === 1 ? 'subnet' : 'subnets'} · ${v.serviceCount} services · ${v.resourceCount} resources`}
                onClick={() => openVpc(v.id)}
                onFocus={() => ensureVisible(v.x, v.y, v.w, v.h)}
              />
            ))}
            {flow.vpcMore && (
              <MoreButton row={flow.vpcMore} onClick={() => setShowAllVpcs(true)} noun="VPC" />
            )}

            {/* Subnets */}
            {flow.subnets.map((s) => (
              <TreeNode
                key={s.id}
                node={s}
                selected={s.selected}
                icon={s.isBucket ? 'mdi:dots-horizontal-circle-outline' : 'mdi:lan'}
                title={s.label}
                subtitle={s.isBucket
                  ? 'Attached to the VPC, not to a subnet'
                  : [s.id, s.zone, s.cidr].filter(Boolean).join(' · ')}
                meta={`${s.serviceCount} ${s.serviceCount === 1 ? 'service' : 'services'} · ${s.resourceCount} ${s.resourceCount === 1 ? 'resource' : 'resources'}`}
                onClick={() => openSubnet(s.id)}
                onFocus={() => ensureVisible(s.x, s.y, s.w, s.h)}
              />
            ))}
            {flow.subnetMore && (
              <MoreButton row={flow.subnetMore} onClick={() => setShowAllSubnets(true)} noun="subnet" />
            )}

            {/* Services */}
            {flow.services.map((s) => (
              <button
                key={s.type}
                type="button"
                onClick={() => openService(s.type)}
                onFocus={() => ensureVisible(s.x, s.y, s.w, s.h)}
                aria-expanded={s.selected}
                title={`${s.type} — ${s.resourceCount} resources, ${s.connectedCount} connected`}
                className={nodeClass(s.selected)}
                style={{ left: s.x, top: s.y, width: s.w, height: s.h }}
              >
                <TypeIcon type={s.type} />
                <div className="min-w-0 flex-1">
                  <p className="text-[11.5px] font-semibold text-black truncate leading-tight">
                    {s.type}
                  </p>
                  <p className="text-[9.5px] text-[rgba(0,0,0,0.45)] truncate">
                    {s.resourceCount} {s.resourceCount === 1 ? 'resource' : 'resources'}
                    {s.connectedCount > 0 && (
                      <span className="text-[#7c3aed] font-semibold">
                        {' '}· {s.connectedCount} connected
                      </span>
                    )}
                  </p>
                </div>
                <Icon
                  icon={s.selected ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                  width={13}
                  className="text-[rgba(0,0,0,0.3)] flex-shrink-0"
                />
              </button>
            ))}
            {flow.serviceMore && (
              <MoreButton row={flow.serviceMore} onClick={() => setShowAllServices(true)} noun="service" />
            )}

            {/* Resources */}
            {flow.resources.map((r) => (
              <button
                key={r.id}
                type="button"
                onClick={() => openResource(r.id)}
                onFocus={() => ensureVisible(r.x, r.y, r.w, r.h)}
                aria-expanded={r.selected}
                title={`${r.label}\n${r.id}`}
                className={nodeClass(r.selected)}
                style={{ left: r.x, top: r.y, width: r.w, height: r.h }}
              >
                <TypeIcon type={r.type} />
                <div className="min-w-0 flex-1">
                  <p className="text-[11.5px] font-semibold text-black truncate leading-tight">
                    {r.label}
                  </p>
                  <p className="text-[9.5px] text-[rgba(0,0,0,0.45)] truncate">
                    {r.facts.length > 0 ? r.facts.join(' · ') : shorten(r.id, 30)}
                  </p>
                  <div className="flex items-center gap-1.5 mt-0.5">
                    {r.state && <StateChip state={r.state} />}
                    <span
                      className={r.peerCount > 0
                        ? 'text-[9.5px] font-semibold text-[#7c3aed]'
                        : 'text-[9.5px] text-[rgba(0,0,0,0.3)]'}
                    >
                      {r.peerCount > 0
                        ? `${r.peerCount} ${r.peerCount === 1 ? 'connection' : 'connections'}`
                        : 'no connections'}
                    </span>
                  </div>
                </div>
                <Icon
                  icon={r.selected ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                  width={13}
                  className="text-[rgba(0,0,0,0.3)] flex-shrink-0"
                />
              </button>
            ))}
            {flow.resourceMore && (
              <MoreButton row={flow.resourceMore} onClick={() => setShowAllResources(true)} noun="resource" />
            )}

            {/* Peers — what the open resource is connected to */}
            {flow.peers.map((p) => (
              <div
                key={`${p.id}-${p.relation}`}
                className={[
                  'absolute rounded-lg bg-white flex items-center transition-all',
                  copied === p.id
                    ? 'border-[1.5px] border-emerald-500'
                    : p.derived || p.external
                      ? 'border border-dashed border-[#c4b5fd]'
                      : 'border border-[#ddd6fe]',
                  'shadow-[0_1px_3px_rgba(0,0,0,0.05)]',
                ].join(' ')}
                style={{ left: p.x, top: p.y, width: p.w, height: p.h }}
              >
                <button
                  type="button"
                  onClick={() => followPeer(p)}
                  onFocus={() => ensureVisible(p.x, p.y, p.w, p.h)}
                  disabled={p.external}
                  title={p.external
                    ? `${p.relation} ${p.id} — not drawn on the map`
                    : `${p.relation} ${p.id}\nClick to follow`}
                  className={[
                    'flex items-center gap-2.5 flex-1 min-w-0 h-full px-3 py-2 text-left rounded-l-lg',
                    'outline-none focus-visible:ring-2 focus-visible:ring-[#8b5cf6] focus-visible:ring-inset',
                    p.external ? 'cursor-default' : 'hover:bg-[#f5f3ff]',
                  ].join(' ')}
                >
                  <TypeIcon type={p.type} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1">
                      <span className="text-[9px] font-bold uppercase tracking-wide text-[#7c3aed] truncate">
                        {p.outbound ? p.relation : `← ${p.relation}`}
                      </span>
                      {p.derived && (
                        <span
                          title="Inferred from this resource's own details rather than stated as a relationship"
                          className="text-[8px] font-bold uppercase text-[rgba(0,0,0,0.3)] flex-shrink-0"
                        >
                          inferred
                        </span>
                      )}
                    </div>
                    <p className="text-[11.5px] font-semibold text-black truncate leading-tight">
                      {p.label === p.id ? shorten(p.label) : p.label}
                    </p>
                    <p className="text-[9.5px] text-[rgba(0,0,0,0.4)] truncate">
                      {p.external
                        ? 'Not drawn on the map'
                        : [p.type, p.location, p.peerCount > 1 ? `${p.peerCount} connections` : '']
                          .filter(Boolean).join(' · ')}
                    </p>
                  </div>
                  {!p.external && (
                    <Icon
                      icon="mdi:arrow-right-circle-outline"
                      width={13}
                      className="text-[#a78bfa] flex-shrink-0"
                    />
                  )}
                </button>
                <button
                  type="button"
                  onClick={() => copyId(p.id)}
                  aria-label={`Copy id of ${p.label}`}
                  title="Copy id"
                  className="h-full px-2 border-l border-[rgba(0,0,0,0.07)] text-[rgba(0,0,0,0.25)] hover:text-[rgba(0,0,0,0.6)] outline-none focus-visible:ring-2 focus-visible:ring-[#8b5cf6] focus-visible:ring-inset rounded-r-lg"
                >
                  <Icon icon={copied === p.id ? 'mdi:check' : 'mdi:content-copy'} width={12}
                    className={copied === p.id ? 'text-emerald-600' : undefined} />
                </button>
              </div>
            ))}

            {/* Nothing connected — said explicitly, because an empty column next to an open
                resource otherwise reads as a view that failed to load. */}
            {openResourceNode && flow.peers.length === 0 && (
              <div
                className="absolute rounded-lg border border-dashed border-[rgba(0,0,0,0.18)] bg-[rgba(0,0,0,0.015)] px-3 flex items-center gap-2 text-[11px] text-[rgba(0,0,0,0.45)]"
                style={{
                  left: CCOLS.peer,
                  top: openResourceNode.y + openResourceNode.h / 2 - CFLOW.PEER_H / 2,
                  width: CFLOW.PEER_W, height: CFLOW.PEER_H,
                }}
              >
                <Icon icon="mdi:link-off" width={14} className="flex-shrink-0" />
                Nothing else references this resource
              </div>
            )}
          </div>
        )}

        {/* Details panel — floats over the canvas rather than narrowing it, since six open
            columns already want every pixel of width. */}
        {showDetails && selectedNode && !emptySearch && (
          <DetailPanel
            node={selectedNode}
            peers={flow.peers}
            copied={copied}
            onCopy={copyId}
            onClose={() => setShowDetails(false)}
            onFollow={followPeer}
          />
        )}

        {pannable && !emptySearch && (
          <div className="absolute bottom-2.5 right-3 text-[10px] text-[rgba(0,0,0,0.35)] bg-white/80 backdrop-blur-sm px-2 py-0.5 rounded-md pointer-events-none">
            Drag to pan · ⌘/Ctrl + scroll to zoom
          </div>
        )}

        {/* Legend, so the two edge colours and the dashes are not a puzzle. */}
        {!emptySearch && (
          <div className="absolute bottom-2.5 left-3 flex items-center gap-3 text-[10px] text-[rgba(0,0,0,0.45)] bg-white/80 backdrop-blur-sm px-2.5 py-1 rounded-md pointer-events-none">
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
        )}
      </div>
    </Shell>
  );
};

// ─── Pieces ──────────────────────────────────────────────────────────────────

const Shell: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="flex flex-col flex-1 min-h-0 min-w-0 overflow-hidden bg-white">{children}</div>
);

const Sep: React.FC = () => (
  <Icon icon="mdi:chevron-right" width={13} className="text-[rgba(0,0,0,0.25)] flex-shrink-0" />
);

const Hint: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <span className="text-[rgba(0,0,0,0.3)] whitespace-nowrap">{children}</span>
);

const Crumb: React.FC<{ icon: string; label: string }> = ({ icon, label }) => (
  <span className="flex items-center gap-1 font-semibold text-[rgba(0,0,0,0.7)] min-w-0">
    <Icon icon={icon} width={12} className="text-[rgba(0,0,0,0.4)] flex-shrink-0" />
    <span className="truncate max-w-[150px]">{label}</span>
  </span>
);

const IconBtn: React.FC<{
  label: string;
  icon: string;
  onClick: () => void;
  spin?: boolean;
  active?: boolean;
}> = ({ label, icon, onClick, spin, active }) => (
  <button
    type="button"
    onClick={onClick}
    aria-label={label}
    aria-pressed={active}
    title={label}
    className={[
      'w-7 h-7 rounded-lg border flex items-center justify-center transition-colors',
      active
        ? 'border-[#4285F4] text-[#4285F4] bg-[#4285F4]/5'
        : 'border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.04)]',
    ].join(' ')}
  >
    <Icon icon={icon} width={13} className={spin ? 'animate-spin' : undefined} />
  </button>
);

const nodeClass = (selected: boolean): string =>
  [
    'absolute rounded-lg bg-white px-3 py-2 text-left transition-all',
    'flex items-center gap-2.5 outline-none',
    selected
      ? 'border-[1.5px] border-[#4285F4] shadow-[0_2px_10px_rgba(66,133,244,0.18)]'
      : 'border border-[rgba(0,0,0,0.13)] shadow-[0_1px_3px_rgba(0,0,0,0.05)]'
        + ' hover:border-[rgba(0,0,0,0.3)] hover:shadow-[0_2px_8px_rgba(0,0,0,0.08)]',
    'focus-visible:ring-2 focus-visible:ring-[#4285F4] focus-visible:ring-offset-1',
  ].join(' ');

/** Running/available in green, stopped/failed in red, anything else neutral. */
const StateChip: React.FC<{ state: string }> = ({ state }) => {
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
 * The service icon, or a hue-tinted glyph when there is no artwork for the type.
 *
 * Mirrors what the live canvas does for unknown types rather than repeating one generic AWS
 * mark across every unrecognised node.
 */
const TypeIcon: React.FC<{ type: string }> = ({ type }) => {
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

/** One containment node — a VPC or a subnet. */
const TreeNode: React.FC<{
  node: { x: number; y: number; w: number; h: number };
  selected: boolean;
  icon: string;
  title: string;
  subtitle: string;
  meta: string;
  onClick: () => void;
  onFocus: () => void;
}> = ({ node, selected, icon, title, subtitle, meta, onClick, onFocus }) => (
  <button
    type="button"
    onClick={onClick}
    onFocus={onFocus}
    aria-expanded={selected}
    title={`${title}\n${subtitle}`}
    className={nodeClass(selected)}
    style={{ left: node.x, top: node.y, width: node.w, height: node.h }}
  >
    <Icon icon={icon} width={16}
      className={selected ? 'text-[#4285F4] flex-shrink-0' : 'text-[rgba(0,0,0,0.45)] flex-shrink-0'} />
    <div className="min-w-0 flex-1">
      <p className="text-[11.5px] font-semibold text-black truncate leading-tight">{title}</p>
      <p className="font-mono text-[9.5px] text-[rgba(0,0,0,0.4)] truncate">{subtitle}</p>
      <p className="text-[9.5px] text-[rgba(0,0,0,0.45)] truncate">{meta}</p>
    </div>
    <Icon icon={selected ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={13}
      className="text-[rgba(0,0,0,0.3)] flex-shrink-0" />
  </button>
);

const MoreButton: React.FC<{
  row: { x: number; y: number; w: number; h: number; hidden: number };
  onClick: () => void;
  noun: string;
}> = ({ row, onClick, noun }) => (
  <button
    type="button"
    onClick={onClick}
    className="absolute rounded-lg border border-dashed border-[rgba(0,0,0,0.18)] bg-[rgba(0,0,0,0.015)] text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.75)] transition-colors flex items-center justify-center gap-1.5 outline-none focus-visible:ring-2 focus-visible:ring-[#4285F4]"
    style={{ left: row.x, top: row.y, width: row.w, height: row.h }}
  >
    <Icon icon="mdi:plus-circle-outline" width={12} />
    Show {row.hidden} more {row.hidden === 1 ? noun : `${noun}s`}
  </button>
);

/**
 * Everything the payload knows about the selected resource.
 *
 * <p>The cards can only carry two facts before they stop being scannable, and the interesting
 * one varies by service — an RDS instance is about its engine and Multi-AZ, a volume about its
 * size and attachment. Rather than guess a universal pair, the cards show the two most likely
 * and this shows all of them, so nothing the backend collected is unreachable.
 */
const DetailPanel: React.FC<{
  node: NodeData;
  peers: PeerFlowNode[];
  copied: string | null;
  onCopy: (id: string) => void;
  onClose: () => void;
  onFollow: (peer: PeerFlowNode) => void;
}> = ({ node, peers, copied, onCopy, onClose, onFollow }) => {
  const details = detailsOf(node);
  const declared = peers.filter((p) => !p.derived);
  const inferred = peers.filter((p) => p.derived);

  return (
    <aside
      aria-label="Resource details"
      className="absolute top-3 right-3 bottom-12 w-[288px] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-[0_8px_28px_rgba(0,0,0,0.12)] flex flex-col overflow-hidden"
    >
      <header className="flex items-start gap-2 px-3 py-2.5 border-b border-[rgba(0,0,0,0.07)]">
        <TypeIcon type={node.type ?? 'Resource'} />
        <div className="min-w-0 flex-1">
          <p className="text-[12.5px] font-bold text-black leading-tight break-words">
            {node.label || node.id}
          </p>
          <p className="text-[10px] text-[rgba(0,0,0,0.45)]">{node.type}</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close details"
          className="text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.7)] flex-shrink-0"
        >
          <Icon icon="mdi:close" width={15} />
        </button>
      </header>

      <div className="flex-1 overflow-y-auto px-3 py-2.5 space-y-3">
        <div>
          <Label>Identifier</Label>
          <button
            type="button"
            onClick={() => onCopy(node.id)}
            title="Copy id"
            className="mt-0.5 w-full text-left font-mono text-[10.5px] text-[rgba(0,0,0,0.75)] break-all hover:text-black flex items-start gap-1.5 group"
          >
            <span className="flex-1">{node.id}</span>
            <Icon
              icon={copied === node.id ? 'mdi:check' : 'mdi:content-copy'}
              width={12}
              className={copied === node.id
                ? 'text-emerald-600 flex-shrink-0 mt-0.5'
                : 'text-[rgba(0,0,0,0.25)] group-hover:text-[rgba(0,0,0,0.6)] flex-shrink-0 mt-0.5'}
            />
          </button>
        </div>

        {details.length > 0 && (
          <div>
            <Label>Attributes</Label>
            <dl className="mt-1 space-y-1">
              {details.map((d) => (
                <div key={d.key} className="flex items-baseline gap-2 text-[10.5px]">
                  <dt className="text-[rgba(0,0,0,0.45)] flex-shrink-0 w-[38%] truncate" title={d.key}>
                    {d.key}
                  </dt>
                  <dd className="text-[rgba(0,0,0,0.8)] font-medium break-all flex-1">{d.value}</dd>
                </div>
              ))}
            </dl>
          </div>
        )}

        <div>
          <Label>
            Connections{' '}
            <span className="text-[rgba(0,0,0,0.3)] font-normal">({peers.length})</span>
          </Label>
          {peers.length === 0 ? (
            <p className="mt-1 text-[10.5px] text-[rgba(0,0,0,0.4)]">
              Nothing else in this account references it.
            </p>
          ) : (
            <div className="mt-1 space-y-2">
              {declared.length > 0 && (
                <PeerList title="Stated" peers={declared} onFollow={onFollow} />
              )}
              {inferred.length > 0 && (
                <PeerList
                  title="Inferred from details"
                  peers={inferred}
                  onFollow={onFollow}
                />
              )}
            </div>
          )}
        </div>
      </div>
    </aside>
  );
};

const Label: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <p className="text-[9.5px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.35)]">
    {children}
  </p>
);

const PeerList: React.FC<{
  title: string;
  peers: PeerFlowNode[];
  onFollow: (peer: PeerFlowNode) => void;
}> = ({ title, peers, onFollow }) => (
  <div>
    <p className="text-[9px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.28)] mb-0.5">
      {title}
    </p>
    <ul className="space-y-0.5">
      {peers.map((p) => (
        <li key={`${p.id}-${p.relation}`}>
          <button
            type="button"
            onClick={() => onFollow(p)}
            disabled={p.external}
            title={p.external ? `${p.id} — not drawn on the map` : `Go to ${p.id}`}
            className={[
              'w-full text-left px-1.5 py-1 rounded-md flex items-start gap-1.5',
              p.external ? 'cursor-default opacity-60' : 'hover:bg-[#f5f3ff]',
            ].join(' ')}
          >
            <span className="text-[9px] font-bold uppercase text-[#7c3aed] flex-shrink-0 mt-[1px] w-[68px] truncate"
              title={p.relation}>
              {p.relation}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block text-[10.5px] font-medium text-black truncate">
                {p.label}
              </span>
              <span className="block text-[9px] text-[rgba(0,0,0,0.4)] truncate">
                {p.external ? 'Not on the map' : [p.type, p.location].filter(Boolean).join(' · ')}
              </span>
            </span>
          </button>
        </li>
      ))}
    </ul>
  </div>
);

const Centered: React.FC<{
  icon: string;
  title: string;
  body: string;
  spin?: boolean;
  action?: { label: string; onClick: () => void };
}> = ({ icon, title, body, spin, action }) => (
  <div
    className="flex-1 flex flex-col items-center justify-center text-center px-8 gap-2"
    style={dotSurface(1, 0, 0)}
  >
    <div className="w-12 h-12 rounded-xl bg-white border border-[rgba(0,0,0,0.08)] flex items-center justify-center mb-1">
      <Icon icon={icon} width={24}
        className={spin ? 'text-[rgba(0,0,0,0.3)] animate-pulse' : 'text-[rgba(0,0,0,0.3)]'} />
    </div>
    <p className="text-[13.5px] font-bold text-[rgba(0,0,0,0.7)]">{title}</p>
    <p className="text-[12px] text-[rgba(0,0,0,0.45)] max-w-[480px] leading-relaxed">{body}</p>
    {action && (
      <button
        type="button"
        onClick={action.onClick}
        className="mt-3 inline-flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.15)] rounded-lg hover:border-[rgba(0,0,0,0.35)] transition-colors"
      >
        <Icon icon="mdi:refresh" width={13} />
        {action.label}
      </button>
    )}
  </div>
);

export default ResourceConnectivityFlow;
