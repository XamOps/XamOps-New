/**
 * CloudMap Page — Network visualization + draw.io-like Design Studio
 *
 * Design Studio features (draw.io parity):
 * - Properties panel (label, color, shape, font size, edge style, arrow type)
 * - Double-click inline label editing
 * - Keyboard shortcuts: Delete, Ctrl+Z/Y, Ctrl+A, Escape
 * - Undo / Redo (50-step stack)
 * - Right-click context menu
 * - Generic shapes palette (rectangle, ellipse, diamond, hexagon, DB, etc.)
 * - Dot-grid background
 * - Zoom level indicator
 * - React save-dialog (no browser prompt)
 */

import React, { useEffect, useRef, useState, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import { useTheme } from '../contexts/ThemeContext';
import { useVPC } from '../contexts/VPCContext';
import { useCloudMap } from '../hooks/useCloudMap';
import CloudMapTopbarControls from '../components/CloudMap/CloudMapTopbarControls';
import EnhancedDesignPalette, { GCP_DESIGN_ICONS, AZURE_DESIGN_ICONS } from '../components/CloudMap/EnhancedDesignPalette';
import type { GenericShape } from '../components/CloudMap/EnhancedDesignPalette';
import type { IconData } from '../components/CloudMap/IconPicker';
import { iconLibrary } from '../components/CloudMap/IconPicker';
import { renderToStaticMarkup } from 'react-dom/server';
import DesignPropertiesPanel from '../components/CloudMap/DesignPropertiesPanel';
import type { ElementProperties } from '../components/CloudMap/DesignPropertiesPanel';
import DesignContextMenu from '../components/CloudMap/DesignContextMenu';
import DesignSaveModal from '../components/CloudMap/DesignSaveModal';
import DesignHelpModal from '../components/CloudMap/DesignHelpModal';
import DesignTemplatesModal from '../components/CloudMap/DesignTemplatesModal';
import Tooltip from '../components/CloudMap/Tooltip';
import ResizeHandles from '../components/CloudMap/ResizeHandles';
import ConnectionArrows from '../components/CloudMap/ConnectionArrows';
import {
  ALL_VPCS_VALUE,
  NODE_TYPE_DATA,
  TAILWIND_COLORS,
  AWS_ICONS,
  type CloudMapMode,
  type GraphElement,
  type NodeData,
} from '../types/cloudmap';
import {
  buildCytoscapeStyle,
  tagEdgeKinds,
  applyLevelOfDetail,
  CANVAS_TOKENS,
  type CanvasTheme,
} from '../components/CloudMap/canvasTheme';

import { sanitizeInlineHtml } from '../utils/sanitizeHtml';

import katex from 'katex';
import 'katex/dist/katex.min.css';

import cytoscape from 'cytoscape';
// @ts-ignore
import cytoscapeDagre from 'cytoscape-dagre';
// @ts-ignore
import cytoscapeEdgehandles from 'cytoscape-edgehandles';
// @ts-ignore
import cytoscapeCdnd from 'cytoscape-compound-drag-and-drop';
// @ts-ignore
import cytoscapeFcose from 'cytoscape-fcose';
// @ts-ignore
import cytoscapeCola from 'cytoscape-cola';

cytoscape.use(cytoscapeDagre);
cytoscape.use(cytoscapeEdgehandles);
cytoscape.use(cytoscapeCdnd);
cytoscape.use(cytoscapeFcose);
cytoscape.use(cytoscapeCola);

// ─── Icon Library → SVG data URL map ────────────────────────────────────────
// Pre-render each icon library component to an SVG data URL so Cytoscape can
// use it as a background-image. Built once at module load time.
const CUSTOM_ICON_URLS: Record<string, string> = (() => {
  const map: Record<string, string> = {};
  iconLibrary.forEach(icon => {
    const color = TAILWIND_COLORS[icon.color as keyof typeof TAILWIND_COLORS]?.[500] || '#6b7280';
    try {
      const svgStr = renderToStaticMarkup(
        React.createElement(icon.icon as React.ComponentType<{ size?: number; color?: string }>, { size: 40, color })
      );
      const withNs = svgStr.includes('xmlns')
        ? svgStr
        : svgStr.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg"');
      map[icon.id] = `data:image/svg+xml,${encodeURIComponent(withNs)}`;
    } catch { /* skip icons that fail to render */ }
  });
  return map;
})();

// ─── Helpers ────────────────────────────────────────────────────────────────

/** Apply stored style data to a Cytoscape element (node or edge). */
const applyElementStyle = (el: any) => {
  if (el.isEdge()) {
    const c = el.data('fillColor');
    const s = el.data('edgeStyle');
    const a = el.data('arrowType');
    const ew = el.data('edgeWidth');
    const ed = el.data('edgeDash');
    const op = el.data('opacity');
    const fc = el.data('fontColor');
    if (c) { el.style('line-color', c); el.style('target-arrow-color', c); el.style('source-arrow-color', c); }
    if (s) el.style('curve-style', s);
    if (a) el.style('target-arrow-shape', a);
    if (ew) el.style('width', ew);
    if (ed) el.style('line-style', ed === 'solid' ? 'solid' : ed === 'dashed' ? 'dashed' : 'dotted');
    if (op !== undefined) el.style('opacity', op);
    if (fc) el.style('color', fc);
    // Restore bezier control point
    const cpd = el.data('controlPointDistance');
    if (cpd !== undefined && s === 'unbundled-bezier') {
      el.style('control-point-weights', '0.5');
      el.style('control-point-distances', String(cpd));
    }
    // Source/target labels
    el.style('source-label', el.data('sourceLabel') || '');
    el.style('target-label', el.data('targetLabel') || '');
    // Mid-arrow waypoint shape
    const ma = el.data('midArrowShape') || 'none';
    el.style('mid-target-arrow-shape', ma !== 'none' ? ma : 'none');
    if (ma !== 'none') el.style('mid-target-arrow-color', el.data('fillColor') || '#9ca3af');
    // Source/target ports (compute from node dimensions)
    const sp = el.data('sourcePort');
    const tp = el.data('targetPort');
    if (sp && sp !== 'auto') {
      const srcNode = el.source();
      const sw = srcNode.width() || 60; const sh2 = srcNode.height() || 60;
      const portOffsets: Record<string, [number, number]> = {
        top: [0, -sh2 / 2], bottom: [0, sh2 / 2], left: [-sw / 2, 0], right: [sw / 2, 0],
      };
      if (portOffsets[sp]) el.style('source-endpoint', portOffsets[sp]);
    } else {
      el.style('source-endpoint', 'outside-to-line');
    }
    if (tp && tp !== 'auto') {
      const tgtNode = el.target();
      const tw = tgtNode.width() || 60; const th2 = tgtNode.height() || 60;
      const portOffsets: Record<string, [number, number]> = {
        top: [0, -th2 / 2], bottom: [0, th2 / 2], left: [-tw / 2, 0], right: [tw / 2, 0],
      };
      if (portOffsets[tp]) el.style('target-endpoint', portOffsets[tp]);
    } else {
      el.style('target-endpoint', 'outside-to-line');
    }
    // Taxi direction
    if (s === 'taxi') {
      const td = el.data('taxiDir') || 'auto';
      el.style('taxi-direction', td);
    }
  } else {
    const f = el.data('fillColor');
    const b = el.data('borderColor');
    const bw = el.data('borderWidth');
    const bd = el.data('borderDash');
    const sh = el.data('nodeShape');
    const fs = el.data('fontSize');
    const op = el.data('opacity');
    const type = el.data('type');
    const customIcon = el.data('customIcon');

    const f2 = el.data('fillColor2');
    const gdir = el.data('gradientDir');
    const shad = el.data('nodeShadow');

    // Cloud icon nodes (AWS / GCP / Azure): transparent background + icon image
    const cloudProvider = el.data('cloudProvider');
    const cloudServiceType = el.data('cloudServiceType');
    const cloudIconPath = cloudProvider === 'gcp' ? GCP_DESIGN_ICONS[cloudServiceType]
      : cloudProvider === 'azure' ? AZURE_DESIGN_ICONS[cloudServiceType]
        : null;
    // _iconUrl is stored in data for reliable restore through jsons() serialization
    const iconId = el.data('iconId');
    const customIconUrl = (type === 'custom-icon' && iconId) ? (CUSTOM_ICON_URLS[iconId] ?? null) : null;
    const resolvedIconUrl = el.data('_iconUrl') || customIconUrl || (!customIcon && type && AWS_ICONS[type] ? AWS_ICONS[type] : null) || cloudIconPath;
    if (resolvedIconUrl && !el.isParent()) {
      if (!el.data('_iconUrl')) el.data('_iconUrl', resolvedIconUrl); // cache for future restores
      el.style('background-image', resolvedIconUrl);
      el.style('background-opacity', 0);
      el.style('background-fit', 'contain');
      el.style('background-fill', 'solid');
      // Labels default below the icon; respect explicit user override
      const tv = el.data('textValign');
      el.style('text-valign', tv || 'bottom');
      el.style('text-margin-y', tv === 'top' ? -8 : tv === 'center' ? 0 : 8);
    } else {
      el.style('background-image', 'none');
      if (f2 && f && f !== 'transparent' && f !== 'none') {
        // Linear gradient
        el.style('background-fill', 'linear-gradient');
        el.style('background-gradient-stop-colors', `${f} ${f2}`);
        el.style('background-gradient-stop-positions', '0% 100%');
        el.style('background-gradient-direction', gdir || 'to-bottom');
        el.style('background-opacity', 1);
      } else if (f === 'transparent' || f === 'none') {
        el.style('background-fill', 'solid');
        el.style('background-opacity', 0);
      } else if (f) {
        el.style('background-fill', 'solid');
        el.style('background-color', f);
        el.style('background-opacity', 1);
      }
    }

    if (b === 'none') {
      el.style('border-width', 0);
    } else if (b) {
      el.style('border-color', b);
    }
    if (bw !== undefined) el.style('border-width', bw);
    if (bd) el.style('border-style', bd === 'dashed' ? 'dashed' : bd === 'dotted' ? 'dotted' : 'solid');
    if (sh) el.style('shape', sh);
    if (fs) el.style('font-size', `${fs}px`);
    if (op !== undefined) el.style('opacity', op);

    // Drop shadow (with per-element blur/offset/color)
    if (shad) {
      el.style({
        'shadow-blur': el.data('shadowBlur') ?? 10,
        'shadow-color': el.data('shadowColor') || '#000000',
        'shadow-opacity': 0.25,
        'shadow-offset-x': el.data('shadowOffsetX') ?? 2,
        'shadow-offset-y': el.data('shadowOffsetY') ?? 4,
      });
    } else if (shad === false) {
      el.style('shadow-opacity', 0);
    }

    // Rotation
    const rot = el.data('rotation') ?? 0;
    el.style('background-image-rotation', `${rot}deg`);

    // Flip H / Flip V via SVG wrapper
    const flipH = el.data('flipH') ?? false;
    const flipV = el.data('flipV') ?? false;
    const origImg = el.data('originalBackgroundImage') || el.style('background-image');
    if ((flipH || flipV) && origImg && origImg !== 'none') {
      if (!el.data('originalBackgroundImage')) el.data('originalBackgroundImage', origImg);
      const sx = flipH ? -1 : 1; const sy = flipV ? -1 : 1;
      const tx = flipH ? -100 : 0; const ty = flipV ? -100 : 0;
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><image href="${el.data('originalBackgroundImage')}" width="100" height="100" transform="scale(${sx},${sy}) translate(${tx},${ty})"/></svg>`;
      el.style('background-image', `data:image/svg+xml,${encodeURIComponent(svg)}`);
    } else if (!flipH && !flipV && el.data('originalBackgroundImage')) {
      el.style('background-image', el.data('originalBackgroundImage'));
    }

    const fb = el.data('fontBold');
    const fi = el.data('fontItalic');
    const tv = el.data('textValign');
    const fc = el.data('fontColor');
    const ff = el.data('fontFamily');
    const th = el.data('textHalign');
    if (fb !== undefined) el.style('font-weight', fb ? 'bold' : 'normal');
    if (fi !== undefined) el.style('font-style', fi ? 'italic' : 'normal');
    if (fc) el.style('color', fc);
    if (ff) el.style('font-family', ff);
    if (th) el.style('text-halign', th);
    if (tv) {
      el.style('text-valign', tv);
      el.style('text-margin-y', tv === 'bottom' ? 6 : tv === 'top' ? -6 : 0);
    }
    const nw = el.data('nodeWidth');
    const nh = el.data('nodeHeight');
    if (nw && nh) el.style({ width: nw, height: nh });

    // Sticky note: enforce text-wrap + shadow
    if (el.data('shapeId') === 'sticky-note') {
      el.style({ 'text-wrap': 'wrap', 'text-max-width': '120px', 'text-valign': 'center' });
      el.style({ 'shadow-blur': 8, 'shadow-color': '#a16207', 'shadow-opacity': 0.20, 'shadow-offset-x': 3, 'shadow-offset-y': 3 });
    }
    // LaTeX labels: hide the built-in Cytoscape label (KaTeX overlay handles display)
    const lbl: string = el.data('label') || '';
    if (lbl.startsWith('$') && lbl.endsWith('$') && lbl.length > 2) {
      el.style('label', '');
    }
  }

  // Lock state — apply to both nodes and edges
  const isLocked = el.data('locked');
  try { if (isLocked) el.lock(); else el.unlock(); } catch (_) { }
};

// ── Page format dimensions (96 dpi logical px) ───────────────────────────────
const PAGE_FORMATS: Record<string, { w: number; h: number; label: string }> = {
  'a4-portrait': { w: 794, h: 1123, label: 'A4 Portrait' },
  'a4-landscape': { w: 1123, h: 794, label: 'A4 Landscape' },
  'letter-portrait': { w: 816, h: 1056, label: 'Letter Portrait' },
  'letter-landscape': { w: 1056, h: 816, label: 'Letter Landscape' },
  'a3-portrait': { w: 1123, h: 1587, label: 'A3 Portrait' },
  'a3-landscape': { w: 1587, h: 1123, label: 'A3 Landscape' },
};

// ─── Edge crossing helper ────────────────────────────────────────────────────

const segmentIntersect = (
  p1: { x: number; y: number },
  p2: { x: number; y: number },
  p3: { x: number; y: number },
  p4: { x: number; y: number },
): { x: number; y: number } | null => {
  const dx1 = p2.x - p1.x, dy1 = p2.y - p1.y;
  const dx2 = p4.x - p3.x, dy2 = p4.y - p3.y;
  const denom = dx1 * dy2 - dy1 * dx2;
  if (Math.abs(denom) < 1e-6) return null;
  const t = ((p3.x - p1.x) * dy2 - (p3.y - p1.y) * dx2) / denom;
  const u = ((p3.x - p1.x) * dy1 - (p3.y - p1.y) * dx1) / denom;
  if (t > 0.05 && t < 0.95 && u > 0.05 && u < 0.95) {
    return { x: p1.x + t * dx1, y: p1.y + t * dy1 };
  }
  return null;
};

// ─── Component ──────────────────────────────────────────────────────────────

const CloudMapPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const { awsVpcs, awsVpcsLoading, fetchAwsVpcs } = useVPC();
  const { setActions } = useTopbarActions();
  const { theme } = useTheme();

  /**
   * The canvas used to hardcode a #f8fafc floor, which rendered as a bright slab
   * inside the app's #020617 dark shell. It now tracks the console theme.
   */
  const canvasThemeName: CanvasTheme = theme === 'dark' ? 'dark' : 'light';
  const canvasTokens = CANVAS_TOKENS[canvasThemeName];

  const accountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Mode
  const [mode, setMode] = useState<CloudMapMode>('live');

  // Cytoscape refs
  const cyRef = useRef<any>(null);
  const ehRef = useRef<any>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const wheelCleanupRef = useRef<(() => void) | null>(null);

  // Undo / Redo stacks (JSON snapshots)
  const undoStack = useRef<string[]>([]);
  const redoStack = useRef<string[]>([]);

  // Live ref so renderGraph (stable callback) can always call latest snapshot fn
  const takeSnapshotRef = useRef<() => void>(() => { });

  // Inline label editor
  const labelInputRef = useRef<HTMLInputElement>(null);

  // ── UI state ──────────────────────────────────────────────────────────────
  const [infoPanelOpen, setInfoPanelOpen] = useState(false);
  const [selectedNodeData, setSelectedNodeData] = useState<NodeData | null>(null);
  const [showEmptyState, setShowEmptyState] = useState(false);
  const [messageOverlay, setMessageOverlay] = useState<{ show: boolean; title: string }>({ show: false, title: '' });
  const [selectedVpcValue, setSelectedVpcValue] = useState<string>('');
  const [showGrid, setShowGrid] = useState(true);
  const [zoomLevel, setZoomLevel] = useState(100);
  const [refreshingTopology, setRefreshingTopology] = useState(false);

  // Design-mode specific state
  const [selectedElement, setSelectedElement] = useState<ElementProperties | null>(null);
  const [contextMenu, setContextMenu] = useState<{
    x: number; y: number; elementId: string; elementType: 'node' | 'edge';
  } | null>(null);
  const [labelEditor, setLabelEditor] = useState<{
    x: number; y: number; elementId: string; value: string;
  } | null>(null);
  const [showSaveModal, setShowSaveModal] = useState(false);
  const [currentBlueprintName, setCurrentBlueprintName] = useState('');
  const [snapToGrid, setSnapToGrid] = useState(false);
  const [showLayoutMenu, setShowLayoutMenu] = useState(false);
  const [selectedCount, setSelectedCount] = useState(0);
  const [drawEdgeMode, setDrawEdgeMode] = useState(false);
  const [panModeActive, setPanModeActive] = useState(false);
  const cdndRef = useRef<any>(null);
  const snapToGridRef = useRef(false);
  const drawEdgeModeRef = useRef(false);

  // Phase 1: resize handles, copy/paste, connection arrows
  const clipboardRef = useRef<any[]>([]);
  const isResizingRef = useRef(false);
  const hoverNodeIdRef = useRef<string | null>(null);
  const hoverClearTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [nodeBox, setNodeBox] = useState<{ nodeId: string; x1: number; y1: number; x2: number; y2: number } | null>(null);
  const [hoverBox, setHoverBox] = useState<{ x1: number; y1: number; x2: number; y2: number } | null>(null);
  const [hoverNodeId, setHoverNodeId] = useState<string | null>(null);
  const [edgeDraft, setEdgeDraft] = useState<{ sourceId: string; x1: number; y1: number; x2: number; y2: number } | null>(null);
  const [alignGuides, setAlignGuides] = useState<{ type: 'h' | 'v'; pos: number }[]>([]);

  // ── Multi-page tabs ──────────────────────────────────────────────────────
  interface CanvasPage { id: string; name: string; elements: any[]; }
  const [pages, setPages] = useState<CanvasPage[]>([{ id: 'page-1', name: 'Page 1', elements: [] }]);
  const [currentPageId, setCurrentPageId] = useState('page-1');
  const pagesRef = useRef<CanvasPage[]>([{ id: 'page-1', name: 'Page 1', elements: [] }]);
  const currentPageIdRef = useRef('page-1');
  const [renamingPageId, setRenamingPageId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');

  // ── Find modal ────────────────────────────────────────────────────────────
  const [showFind, setShowFind] = useState(false);
  const [findQuery, setFindQuery] = useState('');
  const [findResults, setFindResults] = useState<string[]>([]);
  const [findIndex, setFindIndex] = useState(0);

  // ── Canvas background ─────────────────────────────────────────────────────
  const [canvasBackground, setCanvasBackground] = useState(canvasTokens.canvas);
  const [showBgPicker, setShowBgPicker] = useState(false);
  const [canvasBackgroundImage, setCanvasBackgroundImage] = useState('');
  /**
   * Set once the user picks a colour from the background swatches, so a theme
   * switch stops overwriting their choice.
   */
  const bgOverriddenRef = useRef(false);
  /**
   * Live mirrors for the stable Cytoscape callbacks inside renderGraph, which
   * close over the first render and can't read current state directly. Same
   * pattern the page already uses for modeRef / snapToGridRef.
   */
  const canvasBackgroundRef = useRef(canvasTokens.canvas);
  const canvasThemeRef = useRef<CanvasTheme>(canvasThemeName);
  useEffect(() => { canvasBackgroundRef.current = canvasBackground; }, [canvasBackground]);
  useEffect(() => { canvasThemeRef.current = canvasThemeName; }, [canvasThemeName]);

  // ── Page drag-to-reorder ──────────────────────────────────────────────────
  const [dragPageId, setDragPageId] = useState<string | null>(null);
  const [dragOverPageId, setDragOverPageId] = useState<string | null>(null);

  // ── Page format / print boundary ─────────────────────────────────────────
  const [pageFormat, setPageFormat] = useState<string>('none');
  const [showPageBoundary, setShowPageBoundary] = useState(false);
  const [showPageFormatMenu, setShowPageFormatMenu] = useState(false);
  const [pageFormatMenuAnchor, setPageFormatMenuAnchor] = useState<{ top: number; left: number } | null>(null);
  const pageFormatBtnRef = useRef<HTMLDivElement>(null);
  const [layoutMenuAnchor, setLayoutMenuAnchor] = useState<{ top: number; left: number } | null>(null);
  const layoutBtnRef = useRef<HTMLDivElement>(null);
  const [showMoreMenu, setShowMoreMenu] = useState(false);
  const [moreMenuAnchor, setMoreMenuAnchor] = useState<{ top: number; left: number } | null>(null);
  const moreBtnRef = useRef<HTMLButtonElement>(null);
  const [canvasViewport, setCanvasViewport] = useState({ zoom: 1, pan: { x: 0, y: 0 } });

  // ── Find & Replace ────────────────────────────────────────────────────────
  const [replaceQuery, setReplaceQuery] = useState('');
  const [showReplace, setShowReplace] = useState(false);

  // ── Version history ───────────────────────────────────────────────────────
  const [versionHistory, setVersionHistory] = useState<Array<{ id: string; name: string; ts: number; elements: any[] }>>([]);
  const [showVersionHistory, setShowVersionHistory] = useState(false);
  const [showSaveVersionModal, setShowSaveVersionModal] = useState(false);
  const [pendingVersionName, setPendingVersionName] = useState('');

  // ── Templates ─────────────────────────────────────────────────────────────
  const [showTemplates, setShowTemplates] = useState(false);

  // ── Freehand drawing ──────────────────────────────────────────────────────
  const [freehandMode, setFreehandMode] = useState(false);
  const [eraserMode, setEraserMode] = useState(false);
  const [freehandPaths, setFreehandPaths] = useState<Array<{ id: string; d: string; color: string; width: number }>>([]);
  const freehandDrawingRef = useRef<{ active: boolean; points: string; color: string; width: number } | null>(null);
  const freehandModeRef = useRef(false);
  useEffect(() => { freehandModeRef.current = freehandMode; }, [freehandMode]);

  // ── Scratchpad ────────────────────────────────────────────────────────────
  const [showScratchpad, setShowScratchpad] = useState(false);
  const [scratchpadItems, setScratchpadItems] = useState<Array<{ id: string; label: string; data: any }>>([]);

  // ── LaTeX overlays — rendered positions for nodes with $…$ labels ─────────
  const [latexOverlays, setLatexOverlays] = useState<Array<{ id: string; html: string; x: number; y: number; w: number; h: number }>>([]);
  const refreshLatexOverlaysRef = useRef<() => void>(() => { });

  // Format painter
  const [formatPainterActive, setFormatPainterActive] = useState(false);
  const formatPainterActiveRef = useRef(false);
  const formatPainterSourceRef = useRef<string | null>(null);
  useEffect(() => { formatPainterActiveRef.current = formatPainterActive; }, [formatPainterActive]);

  // Edge waypoint handle
  const [edgeHandle, setEdgeHandle] = useState<{ edgeId: string; x: number; y: number } | null>(null);
  const edgeHandleRef = useRef<{ edgeId: string; x: number; y: number } | null>(null);
  useEffect(() => { edgeHandleRef.current = edgeHandle; }, [edgeHandle]);

  // Edge crossing detection
  const [showCrossings, setShowCrossings] = useState(false);
  const [edgeCrossings, setEdgeCrossings] = useState<Array<{ x: number; y: number }>>([]);

  // Pending source port (set when clicking a port dot before drawing)
  const [pendingSourcePort, setPendingSourcePort] = useState<string | null>(null);
  const pendingSourcePortRef = useRef<string | null>(null);
  useEffect(() => { pendingSourcePortRef.current = pendingSourcePort; }, [pendingSourcePort]);

  // Minimap
  const [showMinimap, setShowMinimap] = useState(false);
  const [minimapDataUrl, setMinimapDataUrl] = useState('');
  const showMinimapRef = useRef(false);
  const modeRef = useRef(mode);
  const minimapTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => { showMinimapRef.current = showMinimap; }, [showMinimap]);
  useEffect(() => { modeRef.current = mode; }, [mode]);
  // Auto-show minimap in live mode, hide when switching to design
  useEffect(() => { setShowMinimap(mode === 'live'); }, [mode]);
  // Refresh minimap when toggled on
  useEffect(() => {
    if (!showMinimap || !cyRef.current) return;
    const bg = canvasBackground || canvasTokens.canvas;
    try { setMinimapDataUrl(cyRef.current.png({ full: true, scale: 0.15, bg })); } catch (_) { }
  }, [showMinimap, mode]);

  // ── New feature state ────────────────────────────────────────────────────
  const [isRotating, setIsRotating] = useState(false); // cursor hint while drag-rotating
  const [showSvgImport, setShowSvgImport] = useState(false);
  const [svgImportText, setSvgImportText] = useState('');

  // ── Sprint 3: Tooltip, Rulers, Guides ────────────────────────────────────
  const [showHelp, setShowHelp] = useState(false);
  const [nodeTooltip, setNodeTooltip] = useState<{ text: string; x: number; y: number } | null>(null);
  const [showRulers, setShowRulers] = useState(false);
  const [guides, setGuides] = useState<{ id: string; type: 'h' | 'v'; pos: number }[]>([]);
  const spaceHeldRef = useRef(false);

  // ── Animations & Visual Effects ──────────────────────────────────────────
  const [sketchMode, setSketchMode] = useState(false);
  const [hoverFade, setHoverFade] = useState(false);
  const flowAnimsRef = useRef<Map<string, boolean>>(new Map());
  const pulseAnimsRef = useRef<Map<string, boolean>>(new Map());
  const glowAnimsRef = useRef<Map<string, boolean>>(new Map());

  // Hover fade — dim all non-hovered elements
  useEffect(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    if (!hoverFade) {
      cy.elements().style('opacity', 1);
      return;
    }
    const onOver = (e: any) => {
      const t = e.target;
      cy.elements().not(t).not(t.connectedEdges?.() ?? cy.collection())
        .animate({ style: { opacity: 0.15 } } as any, { duration: 180 });
      t.animate({ style: { opacity: 1 } } as any, { duration: 180 });
    };
    const onOut = () => {
      cy.elements().animate({ style: { opacity: 1 } } as any, { duration: 180 });
    };
    cy.on('mouseover', 'node, edge', onOver);
    cy.on('mouseout', 'node, edge', onOut);
    return () => {
      cy.off('mouseover', 'node, edge', onOver);
      cy.off('mouseout', 'node, edge', onOut);
      cy.elements().style('opacity', 1);
    };
  }, [hoverFade]);

  // Sketch mode — add/remove class on canvas wrapper
  useEffect(() => {
    const wrapper = containerRef.current?.closest('.canvas-wrapper');
    if (!wrapper) return;
    if (sketchMode) wrapper.classList.add('sketch-mode');
    else wrapper.classList.remove('sketch-mode');
  }, [sketchMode]);

  // When rulers are toggled the cytoscape-container changes size (top/left inset changes).
  // Call cy.resize() so Cytoscape recomputes its canvas to the new dimensions.
  useEffect(() => {
    if (cyRef.current) cyRef.current.resize();
  }, [showRulers]);

  // Edge crossing detection
  useEffect(() => {
    if (!cyRef.current || !showCrossings) { setEdgeCrossings([]); return; }
    const cy = cyRef.current;
    const compute = () => {
      const pts: Array<{ x: number; y: number }> = [];
      const edges = cy.edges().filter((e: any) => !e.hasClass('eh-ghost') && !e.hasClass('eh-preview'));
      const arr = edges.toArray() as any[];
      for (let i = 0; i < arr.length; i++) {
        for (let j = i + 1; j < arr.length; j++) {
          const e1 = arr[i], e2 = arr[j];
          const s1 = e1.source().renderedPosition();
          const t1 = e1.target().renderedPosition();
          const s2 = e2.source().renderedPosition();
          const t2 = e2.target().renderedPosition();
          if (!s1 || !t1 || !s2 || !t2) continue;
          const pt = segmentIntersect(s1, t1, s2, t2);
          if (pt) pts.push(pt);
        }
      }
      setEdgeCrossings(pts);
    };
    cy.on('render layoutstop add remove', compute);
    compute();
    return () => { cy.off('render layoutstop add remove', compute); };
  }, [showCrossings]);

  // Perimeter glow on select — pulsing shadow animation
  useEffect(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    const onSelect = (e: any) => {
      const node = e.target;
      if (node.isEdge()) return;
      const id = node.id();
      if (glowAnimsRef.current.get(id)) return;
      glowAnimsRef.current.set(id, true);
      const glow = () => {
        if (!node.selected() || !node.inside()) {
          glowAnimsRef.current.delete(id);
          return;
        }
        node.animate({ style: { 'shadow-blur': 24, 'shadow-opacity': 0.5, 'shadow-color': '#374151', 'shadow-offset-x': 0, 'shadow-offset-y': 0 } } as any, {
          duration: 700, easing: 'ease-in-out',
          complete: () => node.animate({ style: { 'shadow-blur': 8, 'shadow-opacity': 0.15, 'shadow-color': '#374151', 'shadow-offset-x': 0, 'shadow-offset-y': 0 } } as any, {
            duration: 700, easing: 'ease-in-out', complete: glow,
          }),
        });
      };
      glow();
    };
    const onUnselect = (e: any) => {
      const node = e.target;
      if (node.isEdge()) return;
      const id = node.id();
      glowAnimsRef.current.delete(id);
      node.stop(false, true);
      applyElementStyle(node);
    };
    cy.on('select', 'node', onSelect);
    cy.on('unselect', 'node', onUnselect);
    return () => {
      cy.off('select', 'node', onSelect);
      cy.off('unselect', 'node', onUnselect);
    };
  }, []);

  // Hook
  const {
    loadingVpcs,
    vpcError,
    graphElements,
    loadingGraph,
    graphError,
    loadGraph,
    loadAllGraphs,
    blueprints,
    selectedBlueprintId,
    savingBlueprint,
    saveBlueprint,
    loadBlueprint,
    clearBlueprint,
    exportToPng,
  } = useCloudMap({ accountId, mode });

  // ── Snapshot / Undo / Redo ────────────────────────────────────────────────

  const takeSnapshot = useCallback(() => {
    if (!cyRef.current) return;
    // Save elements only — NOT the viewport (zoom/pan). Viewport is user-controlled
    // and should never be reset by undo/redo.
    undoStack.current.push(JSON.stringify(cyRef.current.elements().jsons()));
    if (undoStack.current.length > 50) undoStack.current.shift();
    redoStack.current = [];
  }, []);

  useEffect(() => { takeSnapshotRef.current = takeSnapshot; }, [takeSnapshot]);
  useEffect(() => { snapToGridRef.current = snapToGrid; }, [snapToGrid]);
  useEffect(() => { drawEdgeModeRef.current = drawEdgeMode; }, [drawEdgeMode]);

  const restoreSnapshot = useCallback((snapshot: any[]) => {
    if (!cyRef.current) return;
    const zoom = cyRef.current.zoom();
    const pan = cyRef.current.pan();
    cyRef.current.elements().remove();
    if (snapshot.length > 0) {
      // Nodes must be added before edges (edges reference node IDs)
      const nodes = snapshot.filter((el: any) => el.group === 'nodes' || (!el.data?.source && !el.data?.target));
      const edges = snapshot.filter((el: any) => el.group === 'edges' || (el.data?.source && el.data?.target));
      if (nodes.length > 0) cyRef.current.add(nodes);
      if (edges.length > 0) cyRef.current.add(edges);
    }
    cyRef.current.zoom(zoom);
    cyRef.current.pan(pan);
    cyRef.current.elements().forEach(applyElementStyle);
    cyRef.current.style().update();
    // Second pass after layout engine settles — ensures icon background-images persist
    requestAnimationFrame(() => {
      if (!cyRef.current) return;
      cyRef.current.elements().forEach(applyElementStyle);
      cyRef.current.style().update();
    });
    setSelectedElement(null);
  }, []);

  const undo = useCallback(() => {
    if (!cyRef.current || undoStack.current.length === 0) return;
    redoStack.current.push(JSON.stringify(cyRef.current.elements().jsons()));
    restoreSnapshot(JSON.parse(undoStack.current.pop()!));
  }, [restoreSnapshot]);

  const redo = useCallback(() => {
    if (!cyRef.current || redoStack.current.length === 0) return;
    undoStack.current.push(JSON.stringify(cyRef.current.elements().jsons()));
    restoreSnapshot(JSON.parse(redoStack.current.pop()!));
  }, [restoreSnapshot]);

  // ── Load VPCs ─────────────────────────────────────────────────────────────

  useEffect(() => {
    if (mode === 'live' && accountId) {
      fetchAwsVpcs(accountId).then(vpcsData => {
        if (vpcsData.length > 0) {
          setSelectedVpcValue(ALL_VPCS_VALUE);
        }
      }).catch(err => {
        console.error('Error loading VPCs:', err);
      });
    }
  }, [accountId, mode, fetchAwsVpcs]);

  // ── Cytoscape style ───────────────────────────────────────────────────────

  /**
   * Canvas stylesheet.
   *
   * The visual system lives in components/CloudMap/canvasTheme.ts so this page
   * stays about behaviour. Live mode bakes full resource cards (service icon,
   * name, type caption, status dot); design mode keeps native Cytoscape labels
   * so inline editing still works. Both follow the app's ThemeContext.
   */
  const getCytoscapeStyle = (): any[] => buildCytoscapeStyle(canvasThemeName, mode);

  /**
   * Follow the console theme unless the user picked a canvas colour themselves.
   */
  useEffect(() => {
    if (bgOverriddenRef.current) return;
    setCanvasBackground(CANVAS_TOKENS[canvasThemeName].canvas);
  }, [canvasThemeName]);

  /**
   * Restyle in place on theme change.
   *
   * Cheaper and less disruptive than a full renderGraph: swapping the stylesheet
   * keeps positions, selection, pan and zoom, so toggling light/dark doesn't
   * throw away the user's place on the canvas.
   */
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    try {
      cy.style(buildCytoscapeStyle(canvasThemeName, modeRef.current));
    } catch (err) {
      console.warn('[CloudMap] theme restyle failed', err);
    }
  }, [canvasThemeName]);

  // ── Read selected element props from Cytoscape ────────────────────────────

  const readElementProps = useCallback((el: any): ElementProperties => {
    const isEdge = el.isEdge();
    return {
      id: el.id(),
      elementType: isEdge ? 'edge' : 'node',
      label: el.data('label') || '',
      fillColor: el.data('fillColor') || (isEdge ? '#9ca3af' : '#f3f4f6'),
      fillColor2: el.data('fillColor2') || '',
      gradientDir: el.data('gradientDir') || 'to-bottom',
      nodeShadow: el.data('nodeShadow') ?? false,
      shadowBlur: el.data('shadowBlur') ?? 10,
      shadowOffsetX: el.data('shadowOffsetX') ?? 2,
      shadowOffsetY: el.data('shadowOffsetY') ?? 4,
      shadowColor: el.data('shadowColor') || '#000000',
      borderColor: el.data('borderColor') || '#e5e7eb',
      borderWidth: el.data('borderWidth') ?? 2,
      borderDash: el.data('borderDash') || 'solid',
      nodeShape: el.data('nodeShape') || 'rectangle',
      edgeStyle: el.data('edgeStyle') || 'bezier',
      arrowType: el.data('arrowType') || 'triangle',
      fontSize: el.data('fontSize') || 11,
      fontBold: el.data('fontBold') ?? false,
      fontItalic: el.data('fontItalic') ?? false,
      fontFamily: el.data('fontFamily') || 'sans-serif',
      fontColor: el.data('fontColor') || '#374151',
      textValign: el.data('textValign') || 'bottom',
      textHalign: el.data('textHalign') || 'center',
      width: Math.round(isEdge ? 0 : (el.width() || 50)),
      height: Math.round(isEdge ? 0 : (el.height() || 50)),
      opacity: el.data('opacity') ?? 1,
      edgeWidth: el.data('edgeWidth') ?? 2,
      edgeDash: el.data('edgeDash') || 'solid',
      locked: el.data('locked') ?? false,
      tooltip: el.data('tooltip') || '',
      pulse: el.data('pulse') ?? false,
      flowAnimation: el.data('flowAnimation') ?? false,
      flowSpeed: el.data('flowSpeed') ?? 1000,
      sourceLabel: el.data('sourceLabel') || '',
      targetLabel: el.data('targetLabel') || '',
      midArrowShape: el.data('midArrowShape') || 'none',
      sourcePort: el.data('sourcePort') || 'auto',
      targetPort: el.data('targetPort') || 'auto',
      taxiDir: el.data('taxiDir') || 'auto',
      rotation: el.data('rotation') ?? 0,
      flipH: el.data('flipH') ?? false,
      flipV: el.data('flipV') ?? false,
      hyperlink: el.data('hyperlink') || '',
      metadata: el.data('metadata') || [],
      comments: el.data('comments') || '',
    };
  }, []);

  // ── Render graph ──────────────────────────────────────────────────────────

  const renderGraph = useCallback((elements: GraphElement[], isEditable: boolean) => {
    setNodeBox(null);
    setHoverBox(null);
    setHoverNodeId(null);
    setAlignGuides([]);
    hoverNodeIdRef.current = null;
    if (hoverClearTimerRef.current) { clearTimeout(hoverClearTimerRef.current); hoverClearTimerRef.current = null; }

    if (cyRef.current) {
      if (ehRef.current) { ehRef.current.destroy(); ehRef.current = null; }
      cyRef.current.destroy();
      cyRef.current = null;
    }
    if (!containerRef.current) return;

    // Deduplicate
    const seenIds = new Set<string>();
    const deduped = elements.filter(el => {
      const id = el.data.id;
      if (seenIds.has(id)) return false;
      seenIds.add(id);
      return true;
    });

    const nodeIds = new Set(
      deduped
        .filter(el => el.group === 'nodes' || (!(el.data as any).source && !(el.data as any).target))
        .map(el => el.data.id)
    );
    const valid = deduped.filter(el => {
      const isEdge = el.group === 'edges' || ((el.data as any).source && (el.data as any).target);
      if (isEdge) {
        const d = el.data as any;
        return nodeIds.has(d.source) && nodeIds.has(d.target);
      }
      return true;
    });

    cyRef.current = cytoscape({
      container: containerRef.current,
      elements: valid,
      layout: isEditable
        ? { name: 'preset' }
        // nodeSep clears the 184px card width; the old 60 was sized for 50px squares.
        : { name: 'dagre', nodeSep: 130, rankSep: 110, rankDir: 'TB', fit: false } as any,
      style: getCytoscapeStyle(),
      wheelSensitivity: 0.8,
      minZoom: 0.02,
      maxZoom: 5,
      // Design mode: rubber-band select on canvas drag; Space+drag to pan
      boxSelectionEnabled: isEditable,
      selectionType: isEditable ? 'additive' : 'single',
      userPanningEnabled: !isEditable, // design mode: only pan with Space held
    });

    const cy = cyRef.current;

    // Both modes: plain wheel = pan, Ctrl/Cmd+wheel = zoom toward cursor
    // Disable Cytoscape's built-in wheel zoom and intercept manually
    if (containerRef.current) {
      if (wheelCleanupRef.current) { wheelCleanupRef.current(); wheelCleanupRef.current = null; }
      cy.userZoomingEnabled(false);
      const container = containerRef.current;
      const onWheel = (e: WheelEvent) => {
        e.preventDefault();
        e.stopPropagation();
        const c = cyRef.current;
        if (!c) return;
        if (e.ctrlKey || e.metaKey) {
          // Ctrl/Cmd + wheel → zoom toward cursor
          const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
          const rect = container.getBoundingClientRect();
          const newZoom = Math.max(c.minZoom(), Math.min(c.maxZoom(), c.zoom() * factor));
          c.zoom({ level: newZoom, renderedPosition: { x: e.clientX - rect.left, y: e.clientY - rect.top } });
        } else {
          // Plain wheel → pan
          c.panBy({ x: -e.deltaX * 0.8, y: -e.deltaY * 0.8 });
        }
      };
      container.addEventListener('wheel', onWheel, { passive: false });
      wheelCleanupRef.current = () => container.removeEventListener('wheel', onWheel);
    }

    // Classify relationships so edges carry meaning (security / traffic / attach /
    // manage) instead of every edge rendering as the same grey line. Must run
    // before styles resolve.
    tagEdgeKinds(cy);

    // Restore stored styles after load (must set inline bypasses AND update stylesheet)
    cy.elements().forEach(applyElementStyle);
    cy.style().update();

    // Level of detail: cards when close, chips when far. Seeded once here, then
    // maintained on zoom. rAF-coalesced because Cytoscape fires zoom per frame
    // during a pinch or wheel run and a class flip per frame would thrash.
    applyLevelOfDetail(cy);
    let lodFrame = 0;
    cy.on('zoom', () => {
      if (lodFrame) return;
      lodFrame = requestAnimationFrame(() => {
        lodFrame = 0;
        if (cyRef.current) applyLevelOfDetail(cyRef.current);
      });
    });

    // Live mode layout — compact fcose for both all-VPCs and single-VPC views
    if (!isEditable) {
      // Spacing is tuned for the 184x56 resource card, not the old 50x50 square.
      // A card is ~3.7x wider than tall, so horizontal separation has to lead or
      // neighbouring cards collide even while their centres look well spread.
      const vpcRoots = cy.nodes('[type="VPC"]');
      if (vpcRoots.length > 1) {
        // All VPCs on one canvas.
        cy.layout({
          name: 'fcose',
          quality: 'default',
          packComponents: true,
          tile: true,
          tilingPaddingVertical: 90,
          tilingPaddingHorizontal: 150,
          nodeSeparation: 210,
          idealEdgeLength: 190,
          nodeRepulsion: 14000,
          gravity: 0.2,
          fit: false,
          padding: 60,
        } as any).run();
        cy.fit(undefined, 60);
      } else {
        // Single VPC: wrap resources into a block rather than a long strip.
        cy.layout({
          name: 'fcose',
          quality: 'default',
          packComponents: true,
          tile: true,
          tilingPaddingVertical: 60,
          tilingPaddingHorizontal: 110,
          nodeSeparation: 150,
          idealEdgeLength: 150,
          nodeRepulsion: 9000,
          gravity: 0.28,
          fit: false,
          padding: 70,
        } as any).run();
        cy.fit(undefined, 80);
      }
    }

    // Compound drag-and-drop (design mode — allows drop-to-reparent)
    if (isEditable) {
      cdndRef.current = (cy as any).compoundDragAndDrop({
        grabbedNode: (node: any) => !node.data('isContainer'),
        dropTarget: (target: any) => target.data('isContainer') === true,
        dropSibling: () => false,
        overThreshold: 10,
        outThreshold: 10,
      });
    }

    // Smart alignment guides (design mode)
    if (isEditable) {
      const SNAP_PX = 5;
      cy.on('drag', 'node', (evt: any) => {
        const dragged = evt.target;
        if (dragged.hasClass('eh-handle')) return;
        const dbb = dragged.renderedBoundingBox({ includeLabels: false });
        const dcx = (dbb.x1 + dbb.x2) / 2;
        const dcy = (dbb.y1 + dbb.y2) / 2;
        const guides: { type: 'h' | 'v'; pos: number }[] = [];

        cy.nodes().forEach((other: any) => {
          if (other.id() === dragged.id() || other.hasClass('eh-handle')) return;
          const obb = other.renderedBoundingBox({ includeLabels: false });
          const ocx = (obb.x1 + obb.x2) / 2;
          const ocy = (obb.y1 + obb.y2) / 2;

          // Center-to-center
          if (Math.abs(dcx - ocx) <= SNAP_PX) guides.push({ type: 'v', pos: ocx });
          if (Math.abs(dcy - ocy) <= SNAP_PX) guides.push({ type: 'h', pos: ocy });
          // Left edges
          if (Math.abs(dbb.x1 - obb.x1) <= SNAP_PX) guides.push({ type: 'v', pos: obb.x1 });
          // Right edges
          if (Math.abs(dbb.x2 - obb.x2) <= SNAP_PX) guides.push({ type: 'v', pos: obb.x2 });
          // Top edges
          if (Math.abs(dbb.y1 - obb.y1) <= SNAP_PX) guides.push({ type: 'h', pos: obb.y1 });
          // Bottom edges
          if (Math.abs(dbb.y2 - obb.y2) <= SNAP_PX) guides.push({ type: 'h', pos: obb.y2 });
        });

        // Deduplicate by type + rounded position
        const seen = new Set<string>();
        const unique = guides.filter(g => {
          const key = `${g.type}:${Math.round(g.pos)}`;
          if (seen.has(key)) return false;
          seen.add(key);
          return true;
        });
        setAlignGuides(unique);
      });

      cy.on('dragfree', 'node', (evt: any) => {
        setAlignGuides([]);
        // Also update nodeBox after drag settles
        if (evt.target.selected()) {
          const bb = evt.target.renderedBoundingBox({ includeLabels: false });
          setNodeBox(prev => prev ? { ...prev, x1: bb.x1, y1: bb.y1, x2: bb.x2, y2: bb.y2 } : null);
        }
      });
    }

    // Snap to grid on drag end
    cy.on('dragfree', 'node', (evt: any) => {
      if (!snapToGridRef.current) return;
      const node = evt.target;
      const pos = node.position();
      const G = 24;
      node.position({ x: Math.round(pos.x / G) * G, y: Math.round(pos.y / G) * G });
      // Refresh nodeBox so resize handles follow the snapped position
      if (node.selected()) {
        const bb = node.renderedBoundingBox({ includeLabels: false });
        setNodeBox(prev => prev ? { ...prev, x1: bb.x1, y1: bb.y1, x2: bb.x2, y2: bb.y2 } : null);
      }
    });

    // Minimap thumbnail — debounced update on any change
    const scheduleMinimapUpdate = () => {
      if (!showMinimapRef.current) return;
      if (minimapTimerRef.current) clearTimeout(minimapTimerRef.current);
      minimapTimerRef.current = setTimeout(() => {
        try {
          if (cyRef.current && showMinimapRef.current) {
            const bg = canvasBackgroundRef.current || CANVAS_TOKENS[canvasThemeRef.current].canvas;
            setMinimapDataUrl(cyRef.current.png({ full: true, scale: 0.15, bg }));
          }
        } catch (_) { }
        minimapTimerRef.current = null;
      }, 400);
    };
    cy.on('pan zoom dragfree add remove', scheduleMinimapUpdate);
    cy.on('dragfree add remove', 'node', refreshLatexOverlays);

    // Zoom level indicator
    cy.on('zoom', () => {
      setZoomLevel(Math.round(cy.zoom() * 100));
      setCanvasViewport({ zoom: cy.zoom(), pan: cy.pan() });
      refreshLatexOverlays();
    });

    // Pan viewport tracking (for page boundary overlay)
    cy.on('pan', () => {
      setCanvasViewport({ zoom: cy.zoom(), pan: cy.pan() });
      refreshLatexOverlays();
    });

    // Node tap (live mode: info panel; design mode: select for properties)
    cy.on('tap', 'node', (evt: any) => {
      // Ctrl+click / Cmd+click opens hyperlink
      if (isEditable && (evt.originalEvent?.ctrlKey || evt.originalEvent?.metaKey)) {
        const link = evt.target.data('hyperlink');
        if (link) { window.open(link, '_blank', 'noopener,noreferrer'); return; }
      }
      const data = evt.target.data();
      setSelectedNodeData(data);
      if (!isEditable) setInfoPanelOpen(true);
    });

    // Background tap
    cy.on('tap', (evt: any) => {
      if (evt.target === cy) {
        setInfoPanelOpen(false);
        if (isEditable) {
          setSelectedElement(null);
          setContextMenu(null);
        }
      }
    });

    // Design mode: plain tap on element = replace selection; Shift+tap = add/toggle
    if (isEditable) {
      cy.on('tap', 'node, edge', (evt: any) => {
        const shift = evt.originalEvent?.shiftKey;
        if (shift) {
          // Toggle: if already selected before this tap, unselect it
          // (additive mode always selects — we invert for a true toggle)
          if (evt.target.selected()) {
            // already selected before tap (additive kept it) — leave selected (Shift adds, not removes)
          }
        } else {
          // Plain click: deselect everything except the tapped element
          cy.$(':selected').not(evt.target).unselect();
        }
      });
    }

    // Select → update properties panel
    cy.on('select', 'node, edge', (evt: any) => {
      if (isEditable) setSelectedElement(readElementProps(evt.target));
    });

    // Edge waypoint handle — show on edge select, hide on deselect
    cy.on('select', 'edge', (evt: any) => {
      if (!isEditable) return;
      try {
        const mid = evt.target.renderedMidpoint();
        const h = { edgeId: evt.target.id(), x: mid.x, y: mid.y };
        setEdgeHandle(h);
        edgeHandleRef.current = h;
      } catch (_) { }
    });
    cy.on('unselect', 'edge', () => {
      setEdgeHandle(null);
      edgeHandleRef.current = null;
    });

    // Unselect → clear properties if nothing selected
    cy.on('unselect', () => {
      if (isEditable && cy.$(':selected').length === 0) {
        setSelectedElement(null);
        // Also clear edge handle if no edges selected
        if (cy.$('edge:selected').length === 0) {
          setEdgeHandle(null);
          edgeHandleRef.current = null;
        }
      }
    });

    // Track selected node count for alignment toolbar
    cy.on('select unselect', () => {
      setSelectedCount(cy.$(':selected').filter('node').length);
    });

    // ── Resize handle box tracking ──────────────────────────────────────────
    const refreshNodeBox = () => {
      if (!isEditable) return;
      const sel = cy.$('node:selected');
      if (sel.length === 1 && !sel[0].hasClass('eh-handle')) {
        const bb = sel[0].renderedBoundingBox({ includeLabels: false });
        setNodeBox({ nodeId: sel[0].id(), x1: bb.x1, y1: bb.y1, x2: bb.x2, y2: bb.y2 });
      } else {
        setNodeBox(null);
      }
    };
    cy.on('select unselect', refreshNodeBox);
    cy.on('pan zoom', refreshNodeBox);
    cy.on('drag', 'node', (evt: any) => {
      if (!isEditable || !evt.target.selected()) return;
      const bb = evt.target.renderedBoundingBox({ includeLabels: false });
      setNodeBox(prev => prev ? { ...prev, x1: bb.x1, y1: bb.y1, x2: bb.x2, y2: bb.y2 } : null);
    });

    // ── Hover connection arrows ─────────────────────────────────────────────
    cy.on('mouseover', 'node', (evt: any) => {
      if (!isEditable || isResizingRef.current) return;
      const node = evt.target;
      if (node.hasClass('eh-handle')) return;
      if (hoverClearTimerRef.current) { clearTimeout(hoverClearTimerRef.current); hoverClearTimerRef.current = null; }
      const bb = node.renderedBoundingBox({ includeLabels: false });
      hoverNodeIdRef.current = node.id();
      setHoverNodeId(node.id());
      setHoverBox({ x1: bb.x1, y1: bb.y1, x2: bb.x2, y2: bb.y2 });
    });
    cy.on('mouseout', 'node', (evt: any) => {
      if (evt.target.hasClass('eh-handle')) return;
      // Delay clear so mouse can move onto connection arrows without flicker
      hoverClearTimerRef.current = setTimeout(() => {
        hoverNodeIdRef.current = null;
        setHoverNodeId(null);
        setHoverBox(null);
        hoverClearTimerRef.current = null;
      }, 150);
    });
    cy.on('pan zoom', () => {
      if (!isEditable) return;
      if (hoverNodeIdRef.current) {
        const el = cy.getElementById(hoverNodeIdRef.current);
        if (el.length > 0) {
          const bb = el.renderedBoundingBox({ includeLabels: false });
          setHoverBox({ x1: bb.x1, y1: bb.y1, x2: bb.x2, y2: bb.y2 });
        }
      }
      if (edgeHandleRef.current) {
        const e = cy.getElementById(edgeHandleRef.current.edgeId);
        if (e.length > 0) {
          try {
            const mid = e.renderedMidpoint();
            setEdgeHandle(prev => prev ? { ...prev, x: mid.x, y: mid.y } : null);
          } catch (_) { }
        }
      }
    });

    // ── Node tooltip on hover ───────────────────────────────────────────────
    cy.on('mouseover', 'node', (evt: any) => {
      const tip = evt.target.data('tooltip');
      if (!tip) return;
      const rp = evt.renderedPosition ?? evt.target.renderedPosition();
      setNodeTooltip({ text: tip, x: rp.x, y: rp.y - 20 });
    });
    cy.on('mouseout', 'node', () => setNodeTooltip(null));
    cy.on('pan zoom drag', () => setNodeTooltip(null));

    // Format painter — intercept taps when active
    cy.on('tap', 'node, edge', (evt: any) => {
      if (!isEditable || !formatPainterActiveRef.current || !formatPainterSourceRef.current) return;
      const src = cy.getElementById(formatPainterSourceRef.current);
      const tgt = evt.target;
      if (!src.length || tgt.id() === formatPainterSourceRef.current) return;
      takeSnapshotRef.current();
      const STYLE_PROPS = ['fillColor', 'fillColor2', 'gradientDir', 'nodeShadow', 'borderColor',
        'borderWidth', 'borderDash', 'fontSize', 'fontBold', 'fontItalic', 'fontColor',
        'textValign', 'opacity', 'edgeWidth', 'edgeDash', 'nodeShape'];
      STYLE_PROPS.forEach(p => { const v = src.data(p); if (v !== undefined) tgt.data(p, v); });
      applyElementStyle(tgt);
      setSelectedElement(readElementProps(tgt));
      formatPainterActiveRef.current = false;
      formatPainterSourceRef.current = null;
      setFormatPainterActive(false);
    });

    // Double-click → inline label editor
    cy.on('dblclick', 'node, edge', (evt: any) => {
      if (!isEditable) return;
      const el = evt.target;
      const rPos = el.isEdge() ? el.renderedMidpoint() : el.renderedPosition();
      setLabelEditor({ x: rPos.x, y: rPos.y, elementId: el.id(), value: el.data('label') || '' });
    });

    // Right-click → context menu
    cy.on('cxttap', 'node, edge', (evt: any) => {
      if (!isEditable) return;
      const el = evt.target;
      const rp = evt.renderedPosition;
      const rect = containerRef.current!.getBoundingClientRect();
      setContextMenu({ x: rect.left + rp.x, y: rect.top + rp.y, elementId: el.id(), elementType: el.isEdge() ? 'edge' : 'node' });
    });

    // Edge handles (design mode)
    if (isEditable && cytoscapeEdgehandles) {
      ehRef.current = cy.edgehandles({
        snap: true,
        edgeParams: () => ({ data: { label: '' } }),
        complete: () => takeSnapshotRef.current(),
      });
    }

    // Resize observer — only recalculate canvas geometry, never auto-fit
    // (auto-fit on resize causes runaway zoom when palette panel width changes)
    if (window.ResizeObserver && containerRef.current) {
      const ro = new ResizeObserver(() => {
        cyRef.current?.resize();
      });
      ro.observe(containerRef.current);
    }
  }, [readElementProps]);

  // ── Graph effect ──────────────────────────────────────────────────────────

  useEffect(() => {
    if (graphElements.length > 0) {
      setShowEmptyState(false);
      renderGraph(graphElements, mode === 'design');
      // Only auto-fit in live mode; design mode zoom is user-controlled
      if (mode === 'live') {
        setTimeout(() => {
          const cy = cyRef.current;
          if (!cy) return;
          cy.resize();
          if (selectedVpcValue && selectedVpcValue !== ALL_VPCS_VALUE) {
            // Single VPC: animate to fit, then clamp so it never stays below 35%
            cy.animate({ fit: { eles: cy.elements(), padding: 60 }, duration: 500 } as any);
            setTimeout(() => { if (cyRef.current && cyRef.current.zoom() < 0.35) { cyRef.current.zoom(0.35); cyRef.current.center(); } }, 560);
          } else {
            cy.fit(undefined, 40);
            if (cy.zoom() < 0.12) { cy.zoom(0.12); cy.center(); }
          }
        }, 400);
      } else {
        setTimeout(() => { cyRef.current?.resize(); }, 100);
      }
    } else if (!loadingGraph && mode === 'live') {
      setShowEmptyState(true);
      renderGraph([], false);
    } else if (mode === 'design') {
      renderGraph([], true);
    }
  }, [graphElements, mode, renderGraph, loadingGraph, selectedVpcValue]);

  // ── Message overlay ───────────────────────────────────────────────────────

  useEffect(() => {
    if (loadingVpcs) { setMessageOverlay({ show: true, title: 'Loading VPCs...' }); return; }
    if (loadingGraph) { setMessageOverlay({ show: true, title: 'Loading AWS Topology...' }); return; }
    if (vpcError) { setMessageOverlay({ show: true, title: `Error: ${vpcError}` }); return; }
    if (graphError) { setMessageOverlay({ show: true, title: `Error: ${graphError}` }); return; }
    setMessageOverlay({ show: false, title: '' });
  }, [loadingVpcs, loadingGraph, vpcError, graphError]);

  // ── Keyboard shortcuts (design mode only) ─────────────────────────────────

  useEffect(() => {
    if (mode !== 'design') return;

    const onKeyDown = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;

      const ctrl = e.ctrlKey || e.metaKey;

      // F2 → edit label of selected element
      if (e.key === 'F2' && cyRef.current) {
        e.preventDefault();
        const sel = cyRef.current.$(':selected').first();
        if (sel && sel.length > 0) {
          const rPos = sel.isEdge() ? sel.renderedMidpoint() : sel.renderedPosition();
          setLabelEditor({ x: rPos.x, y: rPos.y, elementId: sel.id(), value: sel.data('label') || '' });
        }
        return;
      }

      if (e.key === 'Delete' || e.key === 'Backspace') {
        if (!cyRef.current) return;
        const sel = cyRef.current.$(':selected').filter((el: any) => !el.data('locked'));
        if (sel.length === 0) return;
        takeSnapshotRef.current();
        sel.remove();
        setSelectedElement(null);
        return;
      }

      if (ctrl && e.key === 'z') {
        e.preventDefault();
        undo();
        return;
      }

      if (ctrl && (e.key === 'y' || e.key === 'Z')) {
        e.preventDefault();
        redo();
        return;
      }

      if (ctrl && e.key === 'c') {
        e.preventDefault();
        if (!cyRef.current) return;
        const sel = cyRef.current.$('node:selected');
        if (sel.length === 0) return;
        clipboardRef.current = sel.map((n: any) => ({
          data: { ...n.data() },
          position: { ...n.position() },
        }));
        return;
      }

      if (ctrl && e.key === 'v') {
        e.preventDefault();
        if (clipboardRef.current.length === 0 || !cyRef.current) return;
        takeSnapshotRef.current();
        cyRef.current.$(':selected').unselect();
        clipboardRef.current.forEach((item: any) => {
          const newNode = cyRef.current.add({
            group: 'nodes',
            data: { ...item.data, id: 'node-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6) },
            position: { x: item.position.x + 30, y: item.position.y + 30 },
          });
          applyElementStyle(newNode);
          newNode.select();
        });
        cyRef.current.style().update();
        return;
      }

      if (ctrl && e.key === 'a') {
        e.preventDefault();
        cyRef.current?.elements().select();
        return;
      }

      if (ctrl && e.key === 'd') {
        e.preventDefault();
        handleDuplicateSelected();
        return;
      }

      if (ctrl && e.key === 'g' && !e.shiftKey) {
        e.preventDefault();
        handleGroup();
        return;
      }

      if (ctrl && e.shiftKey && e.key === 'G') {
        e.preventDefault();
        handleUngroup();
        return;
      }

      if (ctrl && e.key === 'l') {
        e.preventDefault();
        if (selectedElement) handleLockToggle(selectedElement.id);
        return;
      }

      if (ctrl && e.key === 'f') {
        e.preventDefault();
        setShowFind(v => !v);
        return;
      }

      if (e.key === '?') {
        e.preventDefault();
        setShowHelp(v => !v);
        return;
      }

      // Arrow keys → nudge selected or pan canvas
      if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)) {
        e.preventDefault();
        const sel = cyRef.current?.$('node:selected');
        const step = e.shiftKey ? 20 : 5;
        if (sel && sel.length > 0) {
          takeSnapshotRef.current();
          sel.forEach((n: any) => {
            const pos = n.position();
            n.position({
              x: pos.x + (e.key === 'ArrowLeft' ? -step : e.key === 'ArrowRight' ? step : 0),
              y: pos.y + (e.key === 'ArrowUp' ? -step : e.key === 'ArrowDown' ? step : 0),
            });
          });
        } else {
          cyRef.current?.panBy({
            x: e.key === 'ArrowLeft' ? step * 4 : e.key === 'ArrowRight' ? -step * 4 : 0,
            y: e.key === 'ArrowUp' ? step * 4 : e.key === 'ArrowDown' ? -step * 4 : 0,
          });
        }
        return;
      }

      // Space → enable pan mode (rubber-band select is default; Space+drag pans)
      if (e.key === ' ' && !spaceHeldRef.current) {
        e.preventDefault();
        spaceHeldRef.current = true;
        if (containerRef.current) containerRef.current.style.cursor = 'grab';
        // Enable panning, disable rubber-band while Space is held
        cyRef.current?.userPanningEnabled(true);
        cyRef.current?.boxSelectionEnabled(false);
        setPanModeActive(true);
        return;
      }

      if (e.key === 'Escape') {
        // Close Find bar if open
        if (showFind) {
          setShowFind(false);
          setFindQuery('');
          setFindResults([]);
          setShowReplace(false);
          return;
        }
        // Exit freehand mode first
        if (freehandModeRef.current) {
          setFreehandMode(false);
          return;
        }
        // Cancel format painter first
        if (formatPainterActiveRef.current) {
          formatPainterActiveRef.current = false;
          formatPainterSourceRef.current = null;
          setFormatPainterActive(false);
          return;
        }
        cyRef.current?.$(':selected').unselect();
        setSelectedElement(null);
        setContextMenu(null);
        setLabelEditor(null);
        if (drawEdgeModeRef.current && ehRef.current) {
          ehRef.current.disableDrawMode();
          setDrawEdgeMode(false);
        }
        return;
      }
    };

    const onKeyUp = (e: KeyboardEvent) => {
      if (e.key === ' ') {
        spaceHeldRef.current = false;
        if (containerRef.current) containerRef.current.style.cursor = '';
        // Restore rubber-band select, disable drag-to-pan
        cyRef.current?.userPanningEnabled(false);
        cyRef.current?.boxSelectionEnabled(true);
        setPanModeActive(false);
      }
    };

    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('keyup', onKeyUp);
    };
  }, [mode, undo, redo]);

  // Auto-focus label input when editor appears
  useEffect(() => {
    if (labelEditor) requestAnimationFrame(() => labelInputRef.current?.focus());
  }, [labelEditor]);

  // ── Property change handlers ───────────────────────────────────────────────

  const handleLabelChange = useCallback((id: string, label: string) => {
    if (!cyRef.current) return;
    takeSnapshotRef.current();
    cyRef.current.getElementById(id).data('label', label);
    setSelectedElement(prev => prev?.id === id ? { ...prev, label } : prev);
    setTimeout(() => refreshLatexOverlaysRef.current(), 50);
  }, []);

  const handleTooltipChange = useCallback((id: string, tip: string) => {
    if (!cyRef.current) return;
    cyRef.current.getElementById(id).data('tooltip', tip);
    setSelectedElement(prev => prev?.id === id ? { ...prev, tooltip: tip } : prev);
  }, []);

  const handleFillColorChange = useCallback((id: string, color: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('fillColor', color);
    if (el.isEdge()) {
      el.style('line-color', color);
      el.style('target-arrow-color', color);
      el.style('source-arrow-color', color);
    } else {
      if (color === 'transparent' || color === 'none') {
        el.style('background-opacity', 0);
      } else {
        el.style('background-color', color);
        el.style('background-opacity', 1);
      }
    }
    setSelectedElement(prev => prev?.id === id ? { ...prev, fillColor: color } : prev);
  }, []);

  const handleBorderColorChange = useCallback((id: string, color: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('borderColor', color);
    if (color === 'none') { el.style('border-width', 0); }
    else { el.style('border-color', color); el.style('border-width', 2); }
    setSelectedElement(prev => prev?.id === id ? { ...prev, borderColor: color } : prev);
  }, []);

  const handleShapeChange = useCallback((id: string, shape: string) => {
    if (!cyRef.current) return;
    takeSnapshotRef.current();
    const el = cyRef.current.getElementById(id);
    el.data('nodeShape', shape);
    el.style('shape', shape);
    setSelectedElement(prev => prev?.id === id ? { ...prev, nodeShape: shape } : prev);
  }, []);

  const handleEdgeStyleChange = useCallback((id: string, style: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('edgeStyle', style);
    el.style('curve-style', style);
    // Clear waypoint when switching away from unbundled-bezier
    if (style !== 'unbundled-bezier') {
      el.data('controlPointDistance', undefined);
      el.style('control-point-distances', '0');
    }
    setSelectedElement(prev => prev?.id === id ? { ...prev, edgeStyle: style } : prev);
  }, []);

  const handleArrowTypeChange = useCallback((id: string, arrowType: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('arrowType', arrowType);
    el.style('target-arrow-shape', arrowType);
    setSelectedElement(prev => prev?.id === id ? { ...prev, arrowType } : prev);
  }, []);

  const handleFontSizeChange = useCallback((id: string, size: number) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('fontSize', size);
    el.style('font-size', `${size}px`);
    setSelectedElement(prev => prev?.id === id ? { ...prev, fontSize: size } : prev);
  }, []);

  const handleFontBoldChange = useCallback((id: string, bold: boolean) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('fontBold', bold);
    el.style('font-weight', bold ? 'bold' : 'normal');
    setSelectedElement(prev => prev?.id === id ? { ...prev, fontBold: bold } : prev);
  }, []);

  const handleFontItalicChange = useCallback((id: string, italic: boolean) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('fontItalic', italic);
    el.style('font-style', italic ? 'italic' : 'normal');
    setSelectedElement(prev => prev?.id === id ? { ...prev, fontItalic: italic } : prev);
  }, []);

  const handleTextValignChange = useCallback((id: string, valign: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('textValign', valign);
    el.style('text-valign', valign);
    el.style('text-margin-y', valign === 'bottom' ? 6 : valign === 'top' ? -6 : 0);
    setSelectedElement(prev => prev?.id === id ? { ...prev, textValign: valign } : prev);
  }, []);

  const handleFillColor2Change = useCallback((id: string, color: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('fillColor2', color);
    applyElementStyle(el);
    setSelectedElement(prev => prev?.id === id ? { ...prev, fillColor2: color } : prev);
  }, []);

  const handleGradientDirChange = useCallback((id: string, dir: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('gradientDir', dir);
    applyElementStyle(el);
    setSelectedElement(prev => prev?.id === id ? { ...prev, gradientDir: dir } : prev);
  }, []);

  const handleNodeShadowChange = useCallback((id: string, shadow: boolean) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('nodeShadow', shadow);
    applyElementStyle(el);
    setSelectedElement(prev => prev?.id === id ? { ...prev, nodeShadow: shadow } : prev);
  }, []);

  const handleShadowDetailChange = useCallback((id: string, blur: number, ox: number, oy: number, color: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('shadowBlur', blur);
    el.data('shadowOffsetX', ox);
    el.data('shadowOffsetY', oy);
    el.data('shadowColor', color);
    applyElementStyle(el);
    setSelectedElement(prev => prev?.id === id
      ? { ...prev, shadowBlur: blur, shadowOffsetX: ox, shadowOffsetY: oy, shadowColor: color }
      : prev);
  }, []);

  const startFlowAnim = useCallback((edge: any) => {
    const id = edge.id();
    if (flowAnimsRef.current.get(id)) return;
    flowAnimsRef.current.set(id, true);
    // ensure edge is dashed for the flow to be visible
    if (edge.style('line-style') === 'solid') {
      edge.style('line-style', 'dashed');
      edge.style('line-dash-pattern', [8, 4]);
    }
    const step = () => {
      if (!edge.data('flowAnimation') || !edge.inside()) {
        flowAnimsRef.current.delete(id);
        return;
      }
      const speed = edge.data('flowSpeed') ?? 1000;
      edge.animate({ style: { 'line-dash-offset': -24 } } as any, {
        duration: speed,
        easing: 'linear',
        complete: () => {
          edge.style('line-dash-offset', 0);
          step();
        },
      });
    };
    step();
  }, []);

  const handleFlowAnimChange = useCallback((id: string, v: boolean) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('flowAnimation', v);
    if (!v) {
      flowAnimsRef.current.delete(id);
      el.stop(false, true);
      el.style('line-dash-offset', 0);
    } else {
      startFlowAnim(el);
    }
    setSelectedElement(prev => prev?.id === id ? { ...prev, flowAnimation: v } : prev);
  }, [startFlowAnim]);

  const handleFlowSpeedChange = useCallback((id: string, v: number) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('flowSpeed', v);
    // restart so new speed takes effect immediately
    flowAnimsRef.current.delete(id);
    el.stop(false, true);
    el.style('line-dash-offset', 0);
    if (el.data('flowAnimation')) startFlowAnim(el);
    setSelectedElement(prev => prev?.id === id ? { ...prev, flowSpeed: v } : prev);
  }, [startFlowAnim]);

  const startPulseAnim = useCallback((node: any) => {
    const id = node.id();
    if (pulseAnimsRef.current.get(id)) return;
    pulseAnimsRef.current.set(id, true);
    const origBW = node.data('borderWidth') ?? 1;
    const step = () => {
      if (!node.data('pulse') || !node.inside()) {
        pulseAnimsRef.current.delete(id);
        node.stop(false, true);
        node.style('border-width', origBW);
        return;
      }
      node.animate({ style: { 'border-width': origBW + 3, 'shadow-opacity': 0.5 } } as any, {
        duration: 600, easing: 'ease-in-out',
        complete: () => node.animate({ style: { 'border-width': origBW, 'shadow-opacity': 0.05 } } as any, {
          duration: 600, easing: 'ease-in-out', complete: step,
        }),
      });
    };
    step();
  }, []);

  const handlePulseChange = useCallback((id: string, v: boolean) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('pulse', v);
    if (!v) {
      pulseAnimsRef.current.delete(id);
      el.stop(false, true);
      el.style('border-width', el.data('borderWidth') ?? 1);
    } else {
      startPulseAnim(el);
    }
    setSelectedElement(prev => prev?.id === id ? { ...prev, pulse: v } : prev);
  }, [startPulseAnim]);

  // ── Group / Ungroup ───────────────────────────────────────────────────────

  const handleGroup = useCallback(() => {
    if (!cyRef.current) return;
    const sel = cyRef.current.$('node:selected').filter((n: any) => !n.hasClass('eh-handle'));
    if (sel.length < 2) return;
    takeSnapshotRef.current();
    const bb = sel.boundingBox({});
    const cx = (bb.x1 + bb.x2) / 2;
    const cy2 = (bb.y1 + bb.y2) / 2;
    const groupId = 'group-' + Date.now();
    const group = cyRef.current.add({
      group: 'nodes',
      data: { id: groupId, label: 'Group', isContainer: true, containerType: 'group', type: 'container' },
      position: { x: cx, y: cy2 },
    });
    group.style({ width: bb.w + 80, height: bb.h + 80 });
    sel.forEach((n: any) => n.move({ parent: groupId }));
    applyElementStyle(group);
  }, []);

  const handleUngroup = useCallback(() => {
    if (!cyRef.current) return;
    const sel = cyRef.current.$('node:selected').filter((n: any) => n.isParent());
    if (sel.length === 0) return;
    takeSnapshotRef.current();
    sel.forEach((container: any) => {
      container.children().move({ parent: null });
      container.remove();
    });
    setSelectedElement(null);
  }, []);

  const handleSizeChange = useCallback((id: string, width: number, height: number) => {
    if (!cyRef.current) return;
    takeSnapshotRef.current();
    const el = cyRef.current.getElementById(id);
    el.style({ width, height });
    el.data('nodeWidth', width);
    el.data('nodeHeight', height);
    setSelectedElement(prev => prev?.id === id ? { ...prev, width, height } : prev);
  }, []);

  const handleBorderWidthChange = useCallback((id: string, width: number) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('borderWidth', width);
    el.style('border-width', width);
    setSelectedElement(prev => prev?.id === id ? { ...prev, borderWidth: width } : prev);
  }, []);

  const handleBorderDashChange = useCallback((id: string, dash: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('borderDash', dash);
    el.style('border-style', dash === 'dashed' ? 'dashed' : dash === 'dotted' ? 'dotted' : 'solid');
    setSelectedElement(prev => prev?.id === id ? { ...prev, borderDash: dash } : prev);
  }, []);

  const handleOpacityChange = useCallback((id: string, opacity: number) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('opacity', opacity);
    el.style('opacity', opacity);
    setSelectedElement(prev => prev?.id === id ? { ...prev, opacity } : prev);
  }, []);

  const handleEdgeWidthChange = useCallback((id: string, width: number) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('edgeWidth', width);
    el.style('width', width);
    setSelectedElement(prev => prev?.id === id ? { ...prev, edgeWidth: width } : prev);
  }, []);

  const handleEdgeDashChange = useCallback((id: string, dash: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('edgeDash', dash);
    el.style('line-style', dash === 'solid' ? 'solid' : dash === 'dashed' ? 'dashed' : 'dotted');
    setSelectedElement(prev => prev?.id === id ? { ...prev, edgeDash: dash } : prev);
  }, []);

  const handleFontColorChange = useCallback((id: string, color: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('fontColor', color);
    el.style('color', color);
    setSelectedElement(prev => prev?.id === id ? { ...prev, fontColor: color } : prev);
  }, []);

  const handleSourceLabelChange = useCallback((id: string, v: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('sourceLabel', v);
    el.style('source-label', v);
    setSelectedElement(prev => prev?.id === id ? { ...prev, sourceLabel: v } : prev);
  }, []);

  const handleTargetLabelChange = useCallback((id: string, v: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('targetLabel', v);
    el.style('target-label', v);
    setSelectedElement(prev => prev?.id === id ? { ...prev, targetLabel: v } : prev);
  }, []);

  const handleMidArrowShapeChange = useCallback((id: string, v: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('midArrowShape', v);
    applyElementStyle(el);
    setSelectedElement(prev => prev?.id === id ? { ...prev, midArrowShape: v } : prev);
  }, []);

  const handleSourcePortChange = useCallback((id: string, v: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('sourcePort', v);
    applyElementStyle(el);
    setSelectedElement(prev => prev?.id === id ? { ...prev, sourcePort: v } : prev);
  }, []);

  const handleTargetPortChange = useCallback((id: string, v: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('targetPort', v);
    applyElementStyle(el);
    setSelectedElement(prev => prev?.id === id ? { ...prev, targetPort: v } : prev);
  }, []);

  const handleTaxiDirChange = useCallback((id: string, v: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('taxiDir', v);
    el.style('taxi-direction', v);
    setSelectedElement(prev => prev?.id === id ? { ...prev, taxiDir: v } : prev);
  }, []);

  const handleRotationChange = useCallback((id: string, v: number) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('rotation', v);
    el.style('background-image-rotation', `${v}deg`);
    setSelectedElement(prev => prev?.id === id ? { ...prev, rotation: v } : prev);
  }, []);

  const handleFlipChange = useCallback((id: string, flipH: boolean, flipV: boolean) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('flipH', flipH);
    el.data('flipV', flipV);
    applyElementStyle(el);
    setSelectedElement(prev => prev?.id === id ? { ...prev, flipH, flipV } : prev);
  }, []);

  const handleHyperlinkChange = useCallback((id: string, v: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('hyperlink', v);
    setSelectedElement(prev => prev?.id === id ? { ...prev, hyperlink: v } : prev);
  }, []);

  const handleMetadataChange = useCallback((id: string, metadata: Array<{ key: string; value: string }>) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('metadata', metadata);
    setSelectedElement(prev => prev?.id === id ? { ...prev, metadata } : prev);
  }, []);

  const handleCommentsChange = useCallback((id: string, v: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('comments', v);
    setSelectedElement(prev => prev?.id === id ? { ...prev, comments: v } : prev);
  }, []);

  const handleRawStyleApply = useCallback((id: string, style: Record<string, unknown>) => {
    if (!cyRef.current) return;
    takeSnapshotRef.current();
    const el = cyRef.current.getElementById(id);
    Object.entries(style).forEach(([k, v]) => { if (v !== undefined && v !== null) el.data(k, v); });
    applyElementStyle(el);
    setSelectedElement(prev => prev ? { ...prev, ...(style as any) } : null);
  }, []);

  // ── Fit page — zoom/pan so the page boundary fills the viewport ──────────
  const handleFitPage = useCallback(() => {
    if (!cyRef.current || pageFormat === 'none') return;
    const fmt = PAGE_FORMATS[pageFormat];
    if (!fmt) return;
    const container = cyRef.current.container();
    if (!container) return;
    const vw = container.offsetWidth;
    const vh = container.offsetHeight;
    const padding = 40;
    const zoom = Math.min((vw - padding * 2) / fmt.w, (vh - padding * 2) / fmt.h);
    const panX = (vw - fmt.w * zoom) / 2;
    const panY = (vh - fmt.h * zoom) / 2;
    cyRef.current.animate({ zoom, pan: { x: panX, y: panY } }, { duration: 350, easing: 'ease-in-out-quad' } as any);
  }, [pageFormat]);

  // ── Refresh LaTeX overlays — called after canvas moves/zooms ─────────────
  const refreshLatexOverlays = useCallback(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    const overlays: Array<{ id: string; html: string; x: number; y: number; w: number; h: number }> = [];
    cy.nodes().filter((n: any) => !n.hasClass('eh-handle') && !n.hasClass('eh-ghost')).forEach((n: any) => {
      const label: string = n.data('label') || '';
      if (!label.startsWith('$') || !label.endsWith('$') || label.length < 3) return;
      const expr = label.slice(1, -1).trim();
      let html = '';
      try {
        // KaTeX escapes the expression and, with the default `trust: false`,
        // refuses markup-producing commands (\url, \htmlData, \includegraphics).
        // Its output is therefore safe to hand to dangerouslySetInnerHTML below.
        html = katex.renderToString(expr, { throwOnError: false, displayMode: true, output: 'html', trust: false });
      } catch { return; }
      const pos = n.renderedPosition();
      if (!pos) return;
      const w = n.renderedWidth() || 80;
      const h = n.renderedHeight() || 60;
      overlays.push({ id: n.id(), html, x: pos.x - w / 2, y: pos.y - h / 2, w, h });
    });
    setLatexOverlays(overlays);
  }, []);

  // Keep the ref in sync so early-declared callbacks can call it. Assigned in an
  // effect rather than during render (React treats render as side-effect free,
  // and a render that is thrown away would otherwise still have mutated the ref).
  // Both call sites fire from user-driven timers, long after the first effect
  // flush, and the ref is seeded with a no-op — so nothing observes the gap.
  useEffect(() => { refreshLatexOverlaysRef.current = refreshLatexOverlays; }, [refreshLatexOverlays]);

  const handleBatchStyleApply = useCallback(() => {
    if (!cyRef.current || !selectedElement) return;
    takeSnapshotRef.current();
    const source = cyRef.current.getElementById(selectedElement.id);
    const sel = cyRef.current.$(':selected').filter((el: any) => el.id() !== selectedElement.id);
    const STYLE_DATA_KEYS = ['fillColor', 'fillColor2', 'gradientDir', 'nodeShadow', 'borderColor', 'borderWidth', 'borderDash', 'nodeShape', 'fontColor', 'fontFamily', 'fontSize', 'fontBold', 'fontItalic', 'opacity'];
    sel.forEach((el: any) => {
      STYLE_DATA_KEYS.forEach(key => { const v = source.data(key); if (v !== undefined) el.data(key, v); });
      applyElementStyle(el);
    });
  }, [selectedElement]);

  const handleSaveVersion = useCallback(() => {
    if (!cyRef.current) return;
    setPendingVersionName(`v${versionHistory.length + 1} – ${new Date().toLocaleTimeString()}`);
    setShowSaveVersionModal(true);
  }, [versionHistory.length]);

  const handleConfirmSaveVersion = useCallback((name: string) => {
    if (!cyRef.current || !name.trim()) return;
    setShowSaveVersionModal(false);
    // Defer serialization out of the click handler so React can repaint first
    setTimeout(() => {
      if (!cyRef.current) return;
      const elements = cyRef.current.elements().map((el: any) => ({
        group: el.group(),
        data: el.data(),
        position: el.isNode() ? el.position() : undefined,
      }));
      setVersionHistory(prev => [...prev.slice(-19), { id: Date.now().toString(), name: name.trim(), ts: Date.now(), elements }]);
    }, 0);
  }, []);

  const handleReplaceOne = useCallback(() => {
    if (!cyRef.current || findResults.length === 0 || !replaceQuery) return;
    takeSnapshotRef.current();
    const elId = findResults[findIndex];
    const el = cyRef.current.getElementById(elId);
    const newLabel = (el.data('label') || '').replace(new RegExp(findQuery, 'i'), replaceQuery);
    el.data('label', newLabel);
    el.style('label', newLabel);
    setSelectedElement(prev => prev?.id === elId ? { ...prev, label: newLabel } : prev);
  }, [findResults, findIndex, findQuery, replaceQuery]);

  const handleReplaceAll = useCallback(() => {
    if (!cyRef.current || findResults.length === 0 || !replaceQuery) return;
    takeSnapshotRef.current();
    findResults.forEach(elId => {
      const el = cyRef.current!.getElementById(elId);
      const newLabel = (el.data('label') || '').replace(new RegExp(findQuery, 'gi'), replaceQuery);
      el.data('label', newLabel);
      el.style('label', newLabel);
    });
  }, [findResults, findQuery, replaceQuery]);

  const handleExportXml = useCallback(() => {
    if (!cyRef.current) return;
    const nodes = cyRef.current.nodes().filter((n: any) => !n.hasClass('eh-handle'));
    const edges = cyRef.current.edges().filter((e: any) => !e.hasClass('eh-ghost'));

    const xmlNodes = nodes.map((n: any) => {
      const pos = n.position();
      const w = n.data('nodeWidth') || n.width() || 80;
      const h = n.data('nodeHeight') || n.height() || 60;
      const style = `rounded=1;whiteSpace=wrap;html=1;fillColor=${n.data('fillColor') || '#ffffff'};strokeColor=${n.data('borderColor') || '#000000'};`;
      return `    <mxCell id="${n.id()}" value="${(n.data('label') || '').replace(/&/g, '&amp;').replace(/</g, '&lt;')}" style="${style}" vertex="1" parent="1">
      <mxGeometry x="${Math.round(pos.x)}" y="${Math.round(pos.y)}" width="${w}" height="${h}" as="geometry" />
    </mxCell>`;
    }).join('\n');

    const xmlEdges = edges.map((e: any) => {
      const style = `edgeStyle=orthogonalEdgeStyle;`;
      return `    <mxCell id="${e.id()}" value="${(e.data('label') || '').replace(/&/g, '&amp;')}" style="${style}" edge="1" source="${e.source().id()}" target="${e.target().id()}" parent="1">
      <mxGeometry relative="1" as="geometry" />
    </mxCell>`;
    }).join('\n');

    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<mxGraphModel><root>
  <mxCell id="0"/><mxCell id="1" parent="0"/>
${xmlNodes}
${xmlEdges}
</root></mxGraphModel>`;

    const blob = new Blob([xml], { type: 'application/xml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = 'diagram.drawio';
    a.click(); URL.revokeObjectURL(url);
  }, []);

  const handleAddTableRow = useCallback((parentId: string) => {
    if (!cyRef.current) return;
    takeSnapshotRef.current();
    const parent = cyRef.current.getElementById(parentId);
    const rowCount = parent.children().length;
    const rowId = `${parentId}-row-${Date.now()}`;
    const row = cyRef.current.add({
      group: 'nodes',
      data: { id: rowId, parent: parentId, label: `Row ${rowCount + 1}`, isTableRow: true, fillColor: rowCount % 2 === 0 ? '#f9fafb' : '#ffffff', borderColor: '#e5e7eb', borderWidth: 1 },
      position: parent.position(),
    });
    row.style({ 'text-halign': 'left', 'text-valign': 'center', 'text-margin-x': 8, height: 24, 'shape': 'rectangle' });
    applyElementStyle(row);
  }, []);

  const handleEdgeReverseDirection = useCallback((id: string) => {
    if (!cyRef.current) return;
    takeSnapshotRef.current();
    const el = cyRef.current.getElementById(id);
    if (!el.isEdge()) return;
    const src = el.source().id();
    const tgt = el.target().id();
    const data = { ...el.data(), id: el.id(), source: tgt, target: src };
    el.remove();
    const newEdge = cyRef.current.add({ group: 'edges', data });
    applyElementStyle(newEdge);
    setSelectedElement(null);
  }, []);

  const handleLockToggle = useCallback((id: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    if (!el.length) return;
    takeSnapshotRef.current();
    const nowLocked = !el.data('locked');
    el.data('locked', nowLocked);
    if (nowLocked) {
      try { el.lock(); } catch (_) { }
      el.addClass('locked-node');
    } else {
      try { el.unlock(); } catch (_) { }
      el.removeClass('locked-node');
    }
    setSelectedElement(prev => prev?.id === id ? { ...prev, locked: nowLocked } : prev);
  }, []);

  const bringForward = useCallback((elementId: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(elementId);
    const z = parseInt(el.style('z-index') || '0', 10);
    el.style('z-index', z + 1);
  }, []);

  const sendBackward = useCallback((elementId: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(elementId);
    const z = parseInt(el.style('z-index') || '0', 10);
    el.style('z-index', Math.max(0, z - 1));
  }, []);

  // ── Multi-page handlers ────────────────────────────────────────────────────

  const switchToPage = useCallback((pageId: string) => {
    if (!cyRef.current || pageId === currentPageIdRef.current) return;
    // Save current page elements
    const currentElements = cyRef.current.elements().jsons();
    const saved = pagesRef.current.map((p: any) =>
      p.id === currentPageIdRef.current ? { ...p, elements: currentElements } : p
    );
    pagesRef.current = saved;
    setPages(saved);
    // Find target page
    const targetPage = saved.find((p: any) => p.id === pageId);
    // Clear canvas state
    setNodeBox(null);
    setEdgeHandle(null);
    edgeHandleRef.current = null;
    hoverNodeIdRef.current = null;
    setHoverBox(null);
    setHoverNodeId(null);
    setSelectedElement(null);
    setAlignGuides([]);
    if (hoverClearTimerRef.current) { clearTimeout(hoverClearTimerRef.current); hoverClearTimerRef.current = null; }
    // Swap elements
    cyRef.current.elements().remove();
    if (targetPage && targetPage.elements.length > 0) {
      cyRef.current.add(targetPage.elements);
      cyRef.current.elements().forEach(applyElementStyle);
      cyRef.current.style().update();
      setTimeout(() => { cyRef.current?.fit(undefined, 40); }, 60);
    }
    setCurrentPageId(pageId);
    currentPageIdRef.current = pageId;
    undoStack.current = [];
    redoStack.current = [];
  }, []);

  const addPage = useCallback(() => {
    if (!cyRef.current) return;
    const currentElements = cyRef.current.elements().jsons();
    const saved = pagesRef.current.map((p: any) =>
      p.id === currentPageIdRef.current ? { ...p, elements: currentElements } : p
    );
    const newId = 'page-' + Date.now();
    const newName = `Page ${saved.length + 1}`;
    const next = [...saved, { id: newId, name: newName, elements: [] }];
    pagesRef.current = next;
    setPages(next);
    // Clear canvas
    cyRef.current.elements().remove();
    setCurrentPageId(newId);
    currentPageIdRef.current = newId;
    setNodeBox(null);
    setEdgeHandle(null);
    edgeHandleRef.current = null;
    setSelectedElement(null);
    undoStack.current = [];
    redoStack.current = [];
  }, []);

  const deletePage = useCallback((pageId: string) => {
    if (pagesRef.current.length <= 1) return;
    const idx = pagesRef.current.findIndex((p: any) => p.id === pageId);
    const next = pagesRef.current.filter((p: any) => p.id !== pageId);
    pagesRef.current = next;
    setPages(next);
    if (pageId === currentPageIdRef.current && cyRef.current) {
      const newPage = next[Math.max(0, idx - 1)];
      setCurrentPageId(newPage.id);
      currentPageIdRef.current = newPage.id;
      cyRef.current.elements().remove();
      if (newPage.elements.length > 0) {
        cyRef.current.add(newPage.elements);
        cyRef.current.elements().forEach(applyElementStyle);
        cyRef.current.style().update();
        setTimeout(() => { cyRef.current?.fit(undefined, 40); }, 60);
      }
      setSelectedElement(null);
      setNodeBox(null);
      setEdgeHandle(null);
      edgeHandleRef.current = null;
      undoStack.current = [];
      redoStack.current = [];
    }
  }, []);

  const commitRename = useCallback(() => {
    if (!renamingPageId) return;
    const next = pagesRef.current.map((p: any) =>
      p.id === renamingPageId ? { ...p, name: renameValue.trim() || p.name } : p
    );
    pagesRef.current = next;
    setPages(next);
    setRenamingPageId(null);
  }, [renamingPageId, renameValue]);

  // ── Find element ──────────────────────────────────────────────────────────

  const doFind = useCallback((query: string) => {
    setFindQuery(query);
    if (!cyRef.current || !query.trim()) {
      cyRef.current?.elements().unselect();
      setFindResults([]);
      setFindIndex(0);
      return;
    }
    const q = query.toLowerCase();
    const matches: string[] = [];
    cyRef.current.elements().forEach((el: any) => {
      const lbl = (el.data('label') || '').toLowerCase();
      if (lbl.includes(q)) matches.push(el.id());
    });
    setFindResults(matches);
    setFindIndex(0);
    cyRef.current.elements().unselect();
    if (matches.length > 0) {
      cyRef.current.getElementById(matches[0]).select();
      cyRef.current.fit(cyRef.current.getElementById(matches[0]), 80);
    }
  }, []);

  const findStep = useCallback((dir: 1 | -1) => {
    if (!cyRef.current || findResults.length === 0) return;
    const next = (findIndex + dir + findResults.length) % findResults.length;
    setFindIndex(next);
    cyRef.current.elements().unselect();
    cyRef.current.getElementById(findResults[next]).select();
    cyRef.current.fit(cyRef.current.getElementById(findResults[next]), 80);
  }, [findIndex, findResults]);

  // ── Font family + text halign handlers ────────────────────────────────────

  const handleFontFamilyChange = useCallback((id: string, family: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('fontFamily', family);
    el.style('font-family', family);
    setSelectedElement(prev => prev?.id === id ? { ...prev, fontFamily: family } : prev);
  }, []);

  const handleTextHalignChange = useCallback((id: string, halign: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(id);
    el.data('textHalign', halign);
    el.style('text-halign', halign);
    setSelectedElement(prev => prev?.id === id ? { ...prev, textHalign: halign } : prev);
  }, []);

  // Format painter — activate by clicking toolbar button while element is selected
  const activateFormatPainter = useCallback(() => {
    if (!selectedElement) return;
    formatPainterSourceRef.current = selectedElement.id;
    setFormatPainterActive(true);
  }, [selectedElement]);

  // ── Resize drag handle ────────────────────────────────────────────────────

  const handleResizeHandleMouseDown = useCallback((wx: number, wy: number, e: React.MouseEvent) => {
    if (!cyRef.current || !nodeBox) return;
    e.preventDefault();
    e.stopPropagation();

    const el = cyRef.current.getElementById(nodeBox.nodeId);
    if (el.length === 0) return;

    isResizingRef.current = true;
    const origW = el.width();
    const origH = el.height();
    const origPos = { x: el.position('x') as number, y: el.position('y') as number };
    const startX = e.clientX;
    const startY = e.clientY;
    const zoom = cyRef.current.zoom();
    const nid = nodeBox.nodeId;

    const onMouseMove = (me: MouseEvent) => {
      const dx = (me.clientX - startX) / zoom;
      const dy = (me.clientY - startY) / zoom;
      const newW = Math.max(20, origW + wx * dx);
      const newH = Math.max(20, origH + wy * dy);
      el.style({ width: newW, height: newH });
      if (wx !== 0) el.position('x', origPos.x + dx / 2);
      if (wy !== 0) el.position('y', origPos.y + dy / 2);
      const bb = el.renderedBoundingBox({ includeLabels: false });
      setNodeBox(prev => prev ? { ...prev, x1: bb.x1, y1: bb.y1, x2: bb.x2, y2: bb.y2 } : null);
    };

    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      isResizingRef.current = false;
      const node = cyRef.current?.getElementById(nid);
      if (node && node.length > 0) {
        takeSnapshotRef.current();
        node.data('nodeWidth', node.width());
        node.data('nodeHeight', node.height());
        setSelectedElement(prev => prev?.id === nid
          ? { ...prev, width: Math.round(node.width()), height: Math.round(node.height()) }
          : prev);
      }
    };

    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  }, [nodeBox]);

  // ── Rotation handle drag ─────────────────────────────────────────────────

  const handleRotateMouseDown = useCallback((e: React.MouseEvent) => {
    if (!cyRef.current || !nodeBox) return;
    e.preventDefault(); e.stopPropagation();
    const cx = (nodeBox.x1 + nodeBox.x2) / 2;
    const cy2 = (nodeBox.y1 + nodeBox.y2) / 2;
    const node = cyRef.current.getElementById(nodeBox.nodeId);
    setIsRotating(true);
    const onMove = (me: MouseEvent) => {
      const rect = containerRef.current?.getBoundingClientRect();
      if (!rect) return;
      const mx = me.clientX - rect.left;
      const my = me.clientY - rect.top;
      const angle = ((Math.atan2(my - cy2, mx - cx) * 180 / Math.PI + 90) % 360 + 360) % 360;
      node.data('rotation', Math.round(angle));
      node.style('background-image-rotation', `${Math.round(angle)}deg`);
      setSelectedElement(prev => prev?.id === nodeBox.nodeId ? { ...prev, rotation: Math.round(angle) } : prev);
    };
    const onUp = () => {
      setIsRotating(false);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }, [nodeBox]);

  // ── Edge waypoint drag → bend edge curve ─────────────────────────────────

  const handleEdgeWaypointMouseDown = useCallback((e: React.MouseEvent) => {
    if (!edgeHandle || !cyRef.current) return;
    e.preventDefault();
    e.stopPropagation();

    const edge = cyRef.current.getElementById(edgeHandle.edgeId);
    if (!edge.length) return;

    // Perpendicular direction relative to source→target
    const src = edge.source().renderedPosition();
    const tgt = edge.target().renderedPosition();
    const dx = tgt.x - src.x;
    const dy = tgt.y - src.y;
    const len = Math.sqrt(dx * dx + dy * dy);
    if (len === 0) return;
    const px = dy / len;   // perpendicular unit vector (rotated 90° CCW)
    const py = -dx / len;

    const startX = e.clientX;
    const startY = e.clientY;
    const zoom = cyRef.current.zoom();
    const baseDist = edge.data('controlPointDistance') ?? 0;

    // Switch to unbundled-bezier to allow free control point
    edge.data('edgeStyle', 'unbundled-bezier');
    edge.style('curve-style', 'unbundled-bezier');
    edge.style('control-point-weights', '0.5');

    const onMouseMove = (me: MouseEvent) => {
      const deltaX = me.clientX - startX;
      const deltaY = me.clientY - startY;
      // Project drag onto perpendicular vector → gives distance offset
      const screenPerp = deltaX * px + deltaY * py;
      const graphDist = baseDist + screenPerp / zoom;
      edge.style('control-point-distances', String(graphDist));
      edge.data('controlPointDistance', graphDist);
      try {
        const mid = edge.renderedMidpoint();
        setEdgeHandle(prev => prev ? { ...prev, x: mid.x, y: mid.y } : null);
      } catch (_) { }
    };

    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      takeSnapshotRef.current();
      setSelectedElement(prev =>
        prev?.id === edgeHandle.edgeId ? { ...prev, edgeStyle: 'unbundled-bezier' } : prev
      );
    };

    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  }, [edgeHandle]);

  // ── Connection arrow drag → draw edge ────────────────────────────────────

  const handleConnectionArrowMouseDown = useCallback((e: React.MouseEvent) => {
    const srcId = hoverNodeIdRef.current;
    if (!srcId || !cyRef.current || !containerRef.current) return;
    const el = cyRef.current.getElementById(srcId);
    if (el.length === 0) return;
    const rPos = el.renderedPosition();
    const rect = containerRef.current.getBoundingClientRect();
    setEdgeDraft({
      sourceId: srcId,
      x1: rPos.x, y1: rPos.y,
      x2: e.clientX - rect.left, y2: e.clientY - rect.top,
    });
  }, []);

  // Resolve edge draft on mouseup
  useEffect(() => {
    if (!edgeDraft) return;
    const onMouseMove = (e: MouseEvent) => {
      const rect = containerRef.current?.getBoundingClientRect();
      if (!rect) return;
      setEdgeDraft(prev => prev ? { ...prev, x2: e.clientX - rect.left, y2: e.clientY - rect.top } : null);
    };
    const onMouseUp = (e: MouseEvent) => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      if (!cyRef.current || !edgeDraft) { setEdgeDraft(null); return; }
      const rect = containerRef.current?.getBoundingClientRect();
      if (!rect) { setEdgeDraft(null); return; }
      const zoom = cyRef.current.zoom();
      const pan = cyRef.current.pan();
      const gx = (e.clientX - rect.left - pan.x) / zoom;
      const gy = (e.clientY - rect.top - pan.y) / zoom;
      const target = cyRef.current.nodes().filter((n: any) => {
        if (n.hasClass('eh-handle') || n.id() === edgeDraft.sourceId) return false;
        const bb = n.boundingBox();
        return gx >= bb.x1 && gx <= bb.x2 && gy >= bb.y1 && gy <= bb.y2;
      });
      if (target.length > 0) {
        takeSnapshotRef.current();
        const edgeData: any = { id: 'edge-' + Date.now(), source: edgeDraft.sourceId, target: target[0].id(), label: '' };
        if (pendingSourcePortRef.current) {
          edgeData.sourcePort = pendingSourcePortRef.current;
        }
        const newEdge = cyRef.current.add({ group: 'edges', data: edgeData });
        if (pendingSourcePortRef.current) applyElementStyle(newEdge);
        setPendingSourcePort(null);
        pendingSourcePortRef.current = null;
      } else {
        setPendingSourcePort(null);
        pendingSourcePortRef.current = null;
      }
      setEdgeDraft(null);
    };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    return () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };
  }, [edgeDraft]);

  const handleDeleteSelected = useCallback(() => {
    if (!cyRef.current) return;
    const sel = cyRef.current.$(':selected').filter((el: any) => !el.data('locked'));
    if (sel.length === 0 && selectedElement && !selectedElement.locked) {
      const el = cyRef.current.getElementById(selectedElement.id);
      if (el.length > 0) {
        takeSnapshotRef.current();
        el.remove();
      }
    } else if (sel.length > 0) {
      takeSnapshotRef.current();
      sel.remove();
    }
    setSelectedElement(null);
  }, [selectedElement]);

  const handleDuplicateSelected = useCallback(() => {
    if (!cyRef.current || !selectedElement || selectedElement.elementType !== 'node') return;
    const el = cyRef.current.getElementById(selectedElement.id);
    if (el.length === 0) return;
    takeSnapshotRef.current();
    const pos = el.position();
    const newNode = cyRef.current.add({
      group: 'nodes',
      data: { ...el.data(), id: 'node-' + Date.now() },
      position: { x: pos.x + 30, y: pos.y + 30 },
    });
    applyElementStyle(newNode);
    cyRef.current.style().update();
  }, [selectedElement]);

  // ── Inline label commit ────────────────────────────────────────────────────

  const commitLabelEdit = useCallback(() => {
    if (!labelEditor || !cyRef.current) { setLabelEditor(null); return; }
    const el = cyRef.current.getElementById(labelEditor.elementId);
    if (el.length > 0) {
      takeSnapshotRef.current();
      el.data('label', labelEditor.value);
      setSelectedElement(prev => prev?.id === labelEditor.elementId ? { ...prev, label: labelEditor.value } : prev);
      setTimeout(() => refreshLatexOverlaysRef.current(), 50);
    }
    setLabelEditor(null);
  }, [labelEditor]);

  // ── Context menu actions ───────────────────────────────────────────────────

  const openLabelEditorFor = useCallback((elementId: string) => {
    if (!cyRef.current) return;
    const el = cyRef.current.getElementById(elementId);
    if (el.length === 0) return;
    const rPos = el.isEdge() ? el.renderedMidpoint() : el.renderedPosition();
    setLabelEditor({ x: rPos.x, y: rPos.y, elementId, value: el.data('label') || '' });
  }, []);

  const bringToFront = useCallback((elementId: string) => {
    if (!cyRef.current) return;
    cyRef.current.getElementById(elementId).style('z-index', 9999);
  }, []);

  const sendToBack = useCallback((elementId: string) => {
    if (!cyRef.current) return;
    cyRef.current.getElementById(elementId).style('z-index', 0);
  }, []);

  // ── Mode switch ────────────────────────────────────────────────────────────

  const handleModeSwitch = useCallback((newMode: CloudMapMode) => {
    setMode(newMode);
    setInfoPanelOpen(false);
    setShowEmptyState(false);
    setSelectedElement(null);
    setContextMenu(null);
    setLabelEditor(null);
    if (newMode === 'design') { setMessageOverlay({ show: false, title: '' }); clearBlueprint(); }
  }, [clearBlueprint]);

  // ── VPC / Blueprint handlers ───────────────────────────────────────────────

  const handleVpcChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    setSelectedVpcValue(value);
    if (value === ALL_VPCS_VALUE) {
      loadAllGraphs(awsVpcs);
    } else {
      loadGraph(value);
    }
  }, [setSelectedVpcValue, loadGraph, loadAllGraphs, awsVpcs]);

  const handleRefreshTopology = useCallback(() => {
    setRefreshingTopology(true);
    const done = () => setRefreshingTopology(false);
    if (selectedVpcValue === ALL_VPCS_VALUE) {
      loadAllGraphs(awsVpcs);
    } else if (selectedVpcValue) {
      loadGraph(selectedVpcValue);
    }
    setTimeout(done, 1500);
  }, [selectedVpcValue, loadGraph, loadAllGraphs, awsVpcs]);

  const handleRelayoutLive = useCallback(() => {
    const cy = cyRef.current;
    if (!cy) return;
    const vpcRoots = cy.nodes('[type="VPC"]');
    const fcoseOpts = vpcRoots.length > 1
      ? { nodeSeparation: 120, tilingPaddingVertical: 100, tilingPaddingHorizontal: 100, nodeRepulsion: 6000, idealEdgeLength: 90 }
      : { nodeSeparation: 60, tilingPaddingVertical: 60, tilingPaddingHorizontal: 60, nodeRepulsion: 3500, idealEdgeLength: 70 };
    cy.layout({
      name: 'fcose', quality: 'default', packComponents: true, tile: true,
      gravity: 0.25, fit: false, padding: 40, ...fcoseOpts,
    } as any).run();
    cy.animate({ fit: { eles: cy.elements(), padding: 40 }, duration: 400 } as any);
  }, []);

  const handleBlueprintChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    if (value) {
      loadBlueprint(value);
    } else {
      clearBlueprint();
      renderGraph([], true);
      setCurrentBlueprintName('');
    }
    setSelectedElement(null);
    undoStack.current = [];
    redoStack.current = [];
  }, [loadBlueprint, clearBlueprint, renderGraph]);

  const handleSaveDesign = useCallback(() => {
    const bp = blueprints.find(b => b.id === selectedBlueprintId);
    setCurrentBlueprintName(bp?.name || '');
    setShowSaveModal(true);
  }, [blueprints, selectedBlueprintId]);

  const handleSaveConfirm = useCallback(async (name: string) => {
    if (!cyRef.current) return;
    try {
      const graphData = JSON.stringify(cyRef.current.elements().jsons());
      await saveBlueprint(name, graphData);
      setCurrentBlueprintName(name);
      setShowSaveModal(false);
    } catch (error) {
      console.error('Save failed:', error);
    }
  }, [saveBlueprint]);

  const handleClearCanvas = useCallback(() => {
    if (!confirm('Clear canvas? Unsaved changes will be lost.')) return;
    takeSnapshotRef.current();
    clearBlueprint();
    cyRef.current?.elements().remove();
    setSelectedElement(null);
    setCurrentBlueprintName('');
  }, [clearBlueprint]);

  const handleExportPng = useCallback(() => {
    const sel = document.getElementById('vpc-selector') as HTMLSelectElement | null;
    exportToPng(cyRef.current, sel);
  }, [exportToPng]);

  // ── Export PDF ────────────────────────────────────────────────────────────

  const handleExportPdf = useCallback(() => {
    if (!cyRef.current) return;
    const dataUrl = cyRef.current.png({ full: true, scale: 2, bg: canvasBackground });
    const win = window.open('', '_blank');
    if (!win) return;
    win.document.write(`<!DOCTYPE html><html><head><title>Architecture Diagram</title>
<style>
  @page { margin: 0; size: auto; }
  body { margin: 0; display: flex; align-items: center; justify-content: center; min-height: 100dvh; background: #ffffff; }
  img { max-width: 100%; max-height: 100vh; object-fit: contain; }
</style></head><body>
<img src="${dataUrl}" onload="window.print()" />
</body></html>`);
    win.document.close();
  }, [canvasBackground]);

  // ── Export SVG ────────────────────────────────────────────────────────────

  const handleExportSvg = useCallback(() => {
    if (!cyRef.current) return;
    const svgStr = cyRef.current.svg({ full: true, bg: canvasBackground || canvasTokens.canvas });
    const blob = new Blob([svgStr], { type: 'image/svg+xml' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = `architecture-${Date.now()}.svg`;
    link.click();
  }, []);

  // ── Auto-layout ───────────────────────────────────────────────────────────

  const runLayout = useCallback((name: string) => {
    if (!cyRef.current) return;
    takeSnapshotRef.current();
    setShowLayoutMenu(false);
    const opts: Record<string, any> = {
      dagre_tb: { name: 'dagre', rankDir: 'TB', nodeSep: 60, rankSep: 80, animate: true, animationDuration: 400 },
      dagre_lr: { name: 'dagre', rankDir: 'LR', nodeSep: 60, rankSep: 80, animate: true, animationDuration: 400 },
      fcose: { name: 'fcose', animate: true, animationDuration: 500, randomize: false, nodeSeparation: 75, idealEdgeLength: 100 },
      breadthfirst: { name: 'breadthfirst', directed: true, animate: true, animationDuration: 400, padding: 20 },
      circle: { name: 'breadthfirst', circular: true, animate: true, animationDuration: 400, padding: 20 },
      grid: { name: 'grid', animate: true, animationDuration: 400, padding: 20 },
    };
    // Defer heavy layout so the UI can render the closed menu first
    setTimeout(() => {
      if (!cyRef.current) return;
      cyRef.current.layout(opts[name] || opts.dagre_tb).run();
    }, 30);
  }, []);

  // Close layout menu / page format menu / bg picker / more menu on outside click
  useEffect(() => {
    if (!showLayoutMenu) return;
    const close = () => setShowLayoutMenu(false);
    // Delay so the button's own click handler fires first
    const t = setTimeout(() => document.addEventListener('mousedown', close), 50);
    return () => { clearTimeout(t); document.removeEventListener('mousedown', close); };
  }, [showLayoutMenu]);

  useEffect(() => {
    if (!showPageFormatMenu) return;
    const close = () => setShowPageFormatMenu(false);
    const t = setTimeout(() => document.addEventListener('mousedown', close), 50);
    return () => { clearTimeout(t); document.removeEventListener('mousedown', close); };
  }, [showPageFormatMenu]);

  useEffect(() => {
    if (!showMoreMenu) return;
    const close = () => setShowMoreMenu(false);
    const t = setTimeout(() => document.addEventListener('mousedown', close), 50);
    return () => { clearTimeout(t); document.removeEventListener('mousedown', close); };
  }, [showMoreMenu]);

  useEffect(() => {
    if (!showBgPicker) return;
    const close = (e: MouseEvent) => {
      const t = e.target as HTMLElement;
      // Keep open if click is inside the picker panel OR the toolbar button that opened it
      if (t?.closest?.('.bg-picker-panel') || t?.closest?.('.toolbar-button')) return;
      setShowBgPicker(false);
    };
    setTimeout(() => document.addEventListener('mousedown', close), 100);
    return () => document.removeEventListener('mousedown', close);
  }, [showBgPicker]);

  // ── Draw-edge mode ────────────────────────────────────────────────────────

  const toggleDrawEdgeMode = useCallback(() => {
    if (!ehRef.current) return;
    const next = !drawEdgeModeRef.current;
    setDrawEdgeMode(next);
    if (next) ehRef.current.enableDrawMode();
    else ehRef.current.disableDrawMode();
  }, []);

  // When draw mode is active, disable CDND so dragging draws edges, not moves nodes
  useEffect(() => {
    if (!cdndRef.current) return;
    if (drawEdgeMode) cdndRef.current.disable?.();
    else cdndRef.current.enable?.();
  }, [drawEdgeMode]);

  // ── Container auto-parenting helper ──────────────────────────────────────

  const reparentIfInsideContainer = useCallback((newNode: any) => {
    if (!cyRef.current) return;
    const containers = cyRef.current.$('node[isContainer]');
    let bestContainer: any = null;
    let bestArea = Infinity;
    containers.forEach((container: any) => {
      if (container.id() === newNode.id()) return;
      const bb = container.boundingBox();
      const pos = newNode.position();
      if (pos.x >= bb.x1 && pos.x <= bb.x2 && pos.y >= bb.y1 && pos.y <= bb.y2) {
        const area = (bb.x2 - bb.x1) * (bb.y2 - bb.y1);
        if (area < bestArea) { bestArea = area; bestContainer = container; }
      }
    });
    if (bestContainer) newNode.move({ parent: bestContainer.id() });
  }, []);

  // ── Align / Distribute ────────────────────────────────────────────────────

  const alignNodes = useCallback((dir: 'left' | 'right' | 'top' | 'bottom' | 'hcenter' | 'vcenter') => {
    if (!cyRef.current) return;
    const nodes = cyRef.current.$('node:selected');
    if (nodes.length < 2) return;
    takeSnapshotRef.current();
    if (dir === 'left') {
      const minX = Math.min(...nodes.map((n: any) => n.position('x') - n.outerWidth() / 2));
      nodes.forEach((n: any) => n.position('x', minX + n.outerWidth() / 2));
    } else if (dir === 'right') {
      const maxX = Math.max(...nodes.map((n: any) => n.position('x') + n.outerWidth() / 2));
      nodes.forEach((n: any) => n.position('x', maxX - n.outerWidth() / 2));
    } else if (dir === 'top') {
      const minY = Math.min(...nodes.map((n: any) => n.position('y') - n.outerHeight() / 2));
      nodes.forEach((n: any) => n.position('y', minY + n.outerHeight() / 2));
    } else if (dir === 'bottom') {
      const maxY = Math.max(...nodes.map((n: any) => n.position('y') + n.outerHeight() / 2));
      nodes.forEach((n: any) => n.position('y', maxY - n.outerHeight() / 2));
    } else if (dir === 'hcenter') {
      const cx = nodes.map((n: any) => n.position('x')).reduce((a: number, b: number) => a + b, 0) / nodes.length;
      nodes.forEach((n: any) => n.position('x', cx));
    } else if (dir === 'vcenter') {
      const cy2 = nodes.map((n: any) => n.position('y')).reduce((a: number, b: number) => a + b, 0) / nodes.length;
      nodes.forEach((n: any) => n.position('y', cy2));
    }
  }, []);

  const distributeNodes = useCallback((axis: 'h' | 'v') => {
    if (!cyRef.current) return;
    const nodes = cyRef.current.$('node:selected').sort((a: any, b: any) =>
      axis === 'h' ? a.position('x') - b.position('x') : a.position('y') - b.position('y')
    );
    if (nodes.length < 3) return;
    takeSnapshotRef.current();
    const arr = nodes.toArray();
    const first = arr[0];
    const last = arr[arr.length - 1];
    const total = axis === 'h'
      ? last.position('x') - first.position('x')
      : last.position('y') - first.position('y');
    const step = total / (arr.length - 1);
    const startVal = axis === 'h' ? first.position('x') : first.position('y');
    arr.forEach((n: any, i: number) => {
      if (i === 0 || i === arr.length - 1) return;
      if (axis === 'h') n.position('x', startVal + step * i);
      else n.position('y', startVal + step * i);
    });
  }, []);

  const handleAlignToGrid = useCallback(() => {
    if (!cyRef.current) return;
    takeSnapshotRef.current();
    const G = 24;
    cyRef.current.nodes().forEach((n: any) => {
      const pos = n.position();
      n.position({ x: Math.round(pos.x / G) * G, y: Math.round(pos.y / G) * G });
    });
  }, []);

  // ── Topbar ────────────────────────────────────────────────────────────────

  useEffect(() => {
    setActions(
      <CloudMapTopbarControls
        mode={mode}
        onModeSwitch={handleModeSwitch}
        vpcs={awsVpcs}
        selectedVpcValue={selectedVpcValue}
        onVpcChange={handleVpcChange}
        loadingVpcs={awsVpcsLoading}
        onExportPng={handleExportPng}
        blueprints={blueprints}
        selectedBlueprintId={selectedBlueprintId}
        onBlueprintChange={handleBlueprintChange}
        onSaveDesign={handleSaveDesign}
        onClearCanvas={handleClearCanvas}
        savingBlueprint={savingBlueprint}
      />
    );
    return () => setActions(null);
  }, [
    mode, awsVpcs, selectedVpcValue, awsVpcsLoading,
    blueprints, selectedBlueprintId, savingBlueprint,
    setActions, handleModeSwitch, handleVpcChange, handleBlueprintChange,
    handleSaveDesign, handleClearCanvas, handleExportPng,
  ]);

  // ── Drag & Drop ────────────────────────────────────────────────────────────

  const handlePaletteDragStart = (e: React.DragEvent<HTMLDivElement>, type: string) => {
    e.dataTransfer.setData('type', type);
  };

  const handleShapeDragStart = (e: React.DragEvent<HTMLDivElement>, shape: GenericShape) => {
    e.dataTransfer.setData('shapeData', JSON.stringify(shape));
    e.dataTransfer.setData('type', 'generic-shape');
  };

  const handleCanvasDrop = (e: React.DragEvent<HTMLDivElement>) => {
    if (mode !== 'design' || !cyRef.current) return;
    e.preventDefault();

    // Handle image file drops
    const files = e.dataTransfer.files;
    if (files && files.length > 0) {
      const file = files[0];
      if (file.type.startsWith('image/')) {
        const rect = containerRef.current?.getBoundingClientRect();
        if (!rect || !cyRef.current) return;
        const zoom = cyRef.current.zoom();
        const pan = cyRef.current.pan();
        const dropX = (e.clientX - rect.left - pan.x) / zoom;
        const dropY = (e.clientY - rect.top - pan.y) / zoom;
        const reader = new FileReader();
        reader.onload = (ev) => {
          const dataUrl = ev.target?.result as string;
          if (!dataUrl || !cyRef.current) return;
          takeSnapshotRef.current();
          const nodeId = 'img-' + Date.now();
          const node = cyRef.current.add({
            group: 'nodes',
            data: { id: nodeId, label: file.name.replace(/\.[^.]+$/, ''), fillColor: 'transparent', borderColor: '#e5e7eb', customIcon: dataUrl, imageFile: true },
            position: { x: dropX, y: dropY },
          });
          node.style({ 'background-image': dataUrl, 'background-opacity': 0, 'background-fit': 'contain', 'width': 80, 'height': 80 });
          node.data('nodeWidth', 80); node.data('nodeHeight', 80);
        };
        reader.readAsDataURL(file);
        return;
      }
    }

    const type = e.dataTransfer.getData('type');
    const iconDataStr = e.dataTransfer.getData('iconData');
    const shapeDataStr = e.dataTransfer.getData('shapeData');

    if (!type && !iconDataStr) return;

    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;

    const zoom = cyRef.current.zoom();
    const pan = cyRef.current.pan();
    const rawX = (e.clientX - rect.left - pan.x) / zoom;
    const rawY = (e.clientY - rect.top - pan.y) / zoom;
    const G = 24;
    const pos = snapToGridRef.current
      ? { x: Math.round(rawX / G) * G, y: Math.round(rawY / G) * G }
      : { x: rawX, y: rawY };

    takeSnapshotRef.current();

    if (type === 'generic-shape' && shapeDataStr) {
      const shape: GenericShape = JSON.parse(shapeDataStr);
      const nodeData: any = {
        id: 'node-' + Date.now(),
        label: shape.defaultLabel ?? shape.label,
        type: 'shape',
        shapeId: shape.id,
        nodeShape: shape.cytoscapeShape,
        fillColor: shape.fillColor !== 'transparent' ? shape.fillColor : undefined,
        borderColor: shape.borderColor !== 'none' ? shape.borderColor : undefined,
      };
      if (shape.isContainer) {
        nodeData.isContainer = true;
        nodeData.containerType = shape.containerType;
        nodeData.type = 'container';
      }
      const newNode = cyRef.current.add({ group: 'nodes', data: nodeData, position: pos });
      if (shape.isContainer) {
        newNode.style({ width: 300, height: 200 });
      } else {
        reparentIfInsideContainer(newNode);
      }
      applyElementStyle(newNode);

    } else if (type === 'cloud-service') {
      const provider = e.dataTransfer.getData('cloudProvider') as 'gcp' | 'azure';
      const svcType = e.dataTransfer.getData('cloudServiceType');
      const iconPath = e.dataTransfer.getData('cloudIconPath');
      const svcLabel = e.dataTransfer.getData('cloudLabel');
      if (!provider || !svcType) return;
      const newNode = cyRef.current.add({
        group: 'nodes',
        data: { id: 'node-' + Date.now(), label: svcLabel || svcType, type: 'cloud-service', cloudProvider: provider, cloudServiceType: svcType, cloudIconPath: iconPath, _iconUrl: iconPath },
        position: pos,
      });
      reparentIfInsideContainer(newNode);
      applyElementStyle(newNode);

    } else if (type === 'custom-icon' && iconDataStr) {
      const iconData: IconData = JSON.parse(iconDataStr);
      const svgUrl = CUSTOM_ICON_URLS[iconData.id];
      const newNode = cyRef.current.add({
        group: 'nodes',
        data: { id: 'node-' + Date.now(), label: iconData.name, type: 'custom-icon', iconId: iconData.id, customIcon: true, ...(svgUrl ? { _iconUrl: svgUrl } : {}) },
        position: pos,
      });
      reparentIfInsideContainer(newNode);
      applyElementStyle(newNode);

    } else if (type) {
      const newNode = cyRef.current.add({
        group: 'nodes',
        data: { id: 'node-' + Date.now(), label: type, type, _iconUrl: AWS_ICONS[type] || undefined },
        position: pos,
      });
      reparentIfInsideContainer(newNode);
      applyElementStyle(newNode);
    }
  };

  const getCanvasCenter = useCallback(() => {
    if (!cyRef.current) return { x: 300, y: 200 };
    const ext = cyRef.current.extent();
    return { x: (ext.x1 + ext.x2) / 2, y: (ext.y1 + ext.y2) / 2 };
  }, []);

  const handleCloudServiceSelect = (provider: 'gcp' | 'azure', serviceType: string, iconPath: string, label: string) => {
    if (mode !== 'design' || !cyRef.current) return;
    takeSnapshotRef.current();
    const pos = getCanvasCenter();
    const newNode = cyRef.current.add({
      group: 'nodes',
      data: { id: 'node-' + Date.now(), label, type: 'cloud-service', cloudProvider: provider, cloudServiceType: serviceType, cloudIconPath: iconPath, _iconUrl: iconPath },
      position: { x: pos.x + Math.random() * 40 - 20, y: pos.y + Math.random() * 40 - 20 },
    });
    reparentIfInsideContainer(newNode);
    applyElementStyle(newNode);
  };

  const handleIconSelect = (icon: IconData) => {
    if (mode !== 'design' || !cyRef.current) return;
    takeSnapshotRef.current();
    const pos = getCanvasCenter();
    const svgUrl = CUSTOM_ICON_URLS[icon.id];
    const newNode = cyRef.current.add({
      group: 'nodes',
      data: { id: 'node-' + Date.now(), label: icon.name, type: 'custom-icon', iconId: icon.id, customIcon: true, ...(svgUrl ? { _iconUrl: svgUrl } : {}) },
      position: { x: pos.x + Math.random() * 40 - 20, y: pos.y + Math.random() * 40 - 20 },
    });
    applyElementStyle(newNode);
  };

  const handleShapeSelect = (shape: GenericShape) => {
    if (mode !== 'design' || !cyRef.current) return;
    takeSnapshotRef.current();
    const pos = getCanvasCenter();
    const nodeData: any = {
      id: 'node-' + Date.now(),
      label: shape.defaultLabel ?? shape.label,
      type: 'shape',
      shapeId: shape.id,
      nodeShape: shape.cytoscapeShape,
      fillColor: shape.fillColor !== 'transparent' ? shape.fillColor : undefined,
      borderColor: shape.borderColor !== 'none' ? shape.borderColor : undefined,
    };
    if (shape.isContainer) {
      nodeData.isContainer = true;
      nodeData.containerType = shape.containerType;
      nodeData.type = 'container';
    }
    const newNode = cyRef.current.add({
      group: 'nodes',
      data: nodeData,
      position: { x: pos.x + Math.random() * 40 - 20, y: pos.y + Math.random() * 40 - 20 },
    });
    if (shape.isContainer) {
      newNode.style({ width: 300, height: 200 });
    } else {
      reparentIfInsideContainer(newNode);
    }
    applyElementStyle(newNode);
  };

  const handleAwsTypeSelect = (type: string) => {
    if (mode !== 'design' || !cyRef.current) return;
    takeSnapshotRef.current();
    const pos = getCanvasCenter();
    const newNode = cyRef.current.add({
      group: 'nodes',
      data: { id: 'node-' + Date.now(), label: type, type, _iconUrl: AWS_ICONS[type] || undefined },
      position: { x: pos.x + Math.random() * 40 - 20, y: pos.y + Math.random() * 40 - 20 },
    });
    reparentIfInsideContainer(newNode);
    applyElementStyle(newNode);
  };

  // ── Info panel (live mode) ────────────────────────────────────────────────

  const renderInfoPanel = () => {
    if (!selectedNodeData) return null;
    const typeInfo = NODE_TYPE_DATA[selectedNodeData.type] || { icon: 'cube', color: 'gray' };
    const gradients: Record<string, string> = {
      'VPC': 'from-green-500 to-emerald-600',
      'EC2 Instance': 'from-orange-500 to-red-600',
      'RDS Instance': 'from-blue-500 to-blue-700',
    };
    const gradient = gradients[selectedNodeData.type] || 'from-gray-500 to-gray-600';
    return (
      <div className={`info-panel ${infoPanelOpen ? 'open' : ''}`}>
        <button onClick={() => setInfoPanelOpen(false)} className="close-btn"><i className="fas fa-times" /></button>
        <div className="info-content">
          <div className="slide-in">
            <div className={`w-12 h-12 bg-gradient-to-br ${gradient} rounded-xl flex items-center justify-center mb-4`}>
              <i className={`fas fa-${typeInfo.icon} text-white text-lg`} />
            </div>
            <h2 className="text-2xl font-bold text-gray-800 mb-2">{selectedNodeData.label}</h2>
            <div className="vpc-badge mb-4 inline-block">{selectedNodeData.type}</div>
            <p className="text-xs text-gray-400 font-mono break-all mb-4">{selectedNodeData.id}</p>
            <div className="space-y-3">
              {Object.entries(selectedNodeData)
                .filter(([key]) => !['id', 'label', 'parent', 'type', 'source', 'target'].includes(key))
                .map(([key, value]) => {
                  if (typeof value === 'object') return null;
                  return (
                    <div key={key} className="network-node">
                      <span className="font-semibold text-gray-700 text-xs">{key}</span>
                      <span className="font-mono text-gray-900 text-xs">{String(value)}</span>
                    </div>
                  );
                })}
            </div>
          </div>
        </div>
      </div>
    );
  };

  // ── Render ────────────────────────────────────────────────────────────────
  // When rulers are enabled the cytoscape-container is inset 18px from the
  // canvas-wrapper origin. All overlay elements that use Cytoscape's
  // renderedBoundingBox()/renderedPosition() coordinates must add this inset
  // so they align with what Cytoscape draws.
  const canvasInset = showRulers ? 18 : 0;

  return (
    /*
     * cm-theme-* scopes the canvas chrome's dark treatment. It is scoped here
     * rather than relying on Tailwind's `dark:` variant because nothing in this
     * app ever adds the `dark` class to <html>, so those variants never match.
     */
    <div className={`cm-root cm-theme-${canvasThemeName} flex flex-col h-full max-h-full relative overflow-hidden`}>
      <div className="flex flex-1 min-h-0 relative overflow-hidden">

        {/* ── Left Palette (design mode) ── */}
        <EnhancedDesignPalette
          mode={mode}
          onDragStart={handlePaletteDragStart}
          onShapeDragStart={handleShapeDragStart}
          onIconSelect={handleIconSelect}
          onShapeSelect={handleShapeSelect}
          onAwsTypeSelect={handleAwsTypeSelect}
          onCloudServiceSelect={handleCloudServiceSelect}
        />

        {/* ── Canvas column: toolbar strip + canvas ── */}
        <div className="flex flex-col flex-1 min-w-0 min-h-0 overflow-hidden relative" style={{ zIndex: 1 }}>

          {/* ── Toolbar strip (outside canvas-wrapper so it never interferes with drop) ── */}
          <div className="toolbar">
            {mode === 'design' && (
              <>
                {/* ── HISTORY ── */}
                <div className="toolbar-group">
                  <span className="toolbar-group-label">History</span>
                  <div className="toolbar-group-buttons">
                    <Tooltip text="Undo (Ctrl+Z)">
                      <button className="toolbar-button" onClick={undo} disabled={undoStack.current.length === 0}>
                        <i className="fas fa-undo" />
                      </button>
                    </Tooltip>
                    <Tooltip text="Redo (Ctrl+Y)">
                      <button className="toolbar-button" onClick={redo} disabled={redoStack.current.length === 0}>
                        <i className="fas fa-redo" />
                      </button>
                    </Tooltip>
                  </div>
                </div>
                <div className="toolbar-separator" />

                {/* ── DRAW ── */}
                <div className="toolbar-group">
                  <span className="toolbar-group-label">Draw</span>
                  <div className="toolbar-group-buttons">
                    <Tooltip text={showGrid ? 'Hide Grid' : 'Show Grid'}>
                      <button className={`toolbar-button ${showGrid ? 'toolbar-button-active' : ''}`} onClick={() => setShowGrid(g => !g)}>
                        <i className="fas fa-border-all" />
                      </button>
                    </Tooltip>
                    <Tooltip text={drawEdgeMode ? 'Stop Drawing Connections (Esc)' : 'Draw Connection — click to enter draw mode, then click nodes to connect'}>
                      <button className={`toolbar-button ${drawEdgeMode ? 'toolbar-button-active' : ''}`} onClick={toggleDrawEdgeMode}>
                        <i className="fas fa-bezier-curve" />
                      </button>
                    </Tooltip>
                    <Tooltip text={freehandMode ? 'Freehand Drawing: On (Esc to exit)' : 'Freehand Draw — pencil tool for free-form annotations'}>
                      <button className={`toolbar-button ${freehandMode ? 'toolbar-button-active' : ''}`} onClick={() => { setFreehandMode(v => !v); setEraserMode(false); }}>
                        <i className="fas fa-pen" />
                      </button>
                    </Tooltip>
                  </div>
                </div>
                <div className="toolbar-separator" />

                {/* ── INSERT ── */}
                <div className="toolbar-group">
                  <span className="toolbar-group-label">Insert</span>
                  <div className="toolbar-group-buttons">
                    <Tooltip text="Templates Library — start from a pre-built AWS, flowchart, or network diagram">
                      <button className="toolbar-button" onClick={() => setShowTemplates(true)}>
                        <i className="fas fa-th-large" />
                      </button>
                    </Tooltip>
                    <Tooltip text="Import Custom SVG Shape">
                      <button className={`toolbar-button ${showSvgImport ? 'toolbar-button-active' : ''}`} onClick={() => setShowSvgImport(v => !v)}>
                        <i className="fas fa-file-code" />
                      </button>
                    </Tooltip>
                    <Tooltip text={showScratchpad ? 'Hide Scratchpad' : 'Scratchpad — clipboard for reusable diagram snippets'}>
                      <button className={`toolbar-button relative ${showScratchpad ? 'toolbar-button-active' : ''}`} onClick={() => setShowScratchpad(v => !v)}>
                        <i className="fas fa-clipboard" />
                        {scratchpadItems.length > 0 && (
                          <span className="absolute -top-0.5 -right-0.5 min-w-[14px] h-3.5 bg-gray-600 text-white text-[7px] rounded-full flex items-center justify-center px-0.5">
                            {scratchpadItems.length}
                          </span>
                        )}
                      </button>
                    </Tooltip>
                  </div>
                </div>
                <div className="toolbar-separator" />

                {/* ── VIEW ── */}
                <div className="toolbar-group">
                  <span className="toolbar-group-label">View</span>
                  <div className="toolbar-group-buttons">
                    <Tooltip text={showRulers ? 'Hide Rulers' : 'Show Rulers — drag from rulers to create guide lines'}>
                      <button className={`toolbar-button ${showRulers ? 'toolbar-button-active' : ''}`} onClick={() => setShowRulers(v => !v)}>
                        <i className="fas fa-ruler-combined" />
                      </button>
                    </Tooltip>
                    <Tooltip text={sketchMode ? 'Sketch Mode: On (click to disable)' : 'Sketch Mode — hand-drawn look for wireframing'}>
                      <button className={`toolbar-button ${sketchMode ? 'toolbar-button-active' : ''}`} onClick={() => setSketchMode(v => !v)}>
                        <i className="fas fa-pencil-alt" />
                      </button>
                    </Tooltip>
                    <Tooltip text={hoverFade ? 'Hover Fade: On — non-adjacent nodes fade on hover (click to disable)' : 'Hover Fade — fades unrelated nodes when hovering a connection'}>
                      <button className={`toolbar-button ${hoverFade ? 'toolbar-button-active' : ''}`} onClick={() => setHoverFade(v => !v)}>
                        <i className="fas fa-eye" />
                      </button>
                    </Tooltip>
                    <Tooltip text={showCrossings ? 'Crossing Jumps: On — arc bridges drawn where edges cross (click to disable)' : 'Crossing Jumps — shows arc bridges where connection lines cross each other'}>
                      <button
                        className={`toolbar-button ${showCrossings ? 'toolbar-button-active' : ''}`}
                        onClick={() => {
                          const next = !showCrossings;
                          setShowCrossings(next);
                          if (next) {
                            const edgeCount = cyRef.current?.edges().length ?? 0;
                            if (edgeCount < 2) {
                              alert('Crossing Jumps enabled!\n\nConnect at least 2 edges that cross each other — arc bridges will appear automatically at every intersection point.');
                            }
                          }
                        }}
                      >
                        <i className="fas fa-code-branch" />
                      </button>
                    </Tooltip>
                    <Tooltip text={showMinimap ? 'Hide Minimap' : 'Show Minimap — overview thumbnail in the corner'}>
                      <button className={`toolbar-button ${showMinimap ? 'toolbar-button-active' : ''}`} onClick={() => setShowMinimap(v => !v)}>
                        <i className="fas fa-map" />
                      </button>
                    </Tooltip>
                  </div>
                </div>
                <div className="toolbar-separator" />

                {/* ── FORMAT ── */}
                <div className="toolbar-group">
                  <span className="toolbar-group-label">Format</span>
                  <div className="toolbar-group-buttons">
                    {/* Canvas background color */}
                    <div className="relative">
                      <Tooltip text="Canvas Background — change canvas colour or set a background image">
                        <button className={`toolbar-button ${showBgPicker ? 'toolbar-button-active' : ''}`} onClick={() => setShowBgPicker(v => !v)}>
                          <span className="w-3.5 h-3.5 rounded-sm border border-gray-300 inline-block" style={{ background: canvasBackground }} />
                        </button>
                      </Tooltip>
                  {showBgPicker && (
                    <div className="bg-picker-panel absolute top-full left-0 mt-1 z-[300] bg-white rounded-xl shadow-2xl border border-gray-100 p-3 min-w-[180px]">
                      <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-2">Canvas Background</p>
                      <div className="grid grid-cols-5 gap-1.5 mb-2">
                        {[CANVAS_TOKENS.light.canvas, '#ffffff', '#f1f5f9', CANVAS_TOKENS.dark.canvas, '#0f172a', '#fef9c3', '#f0fdf4', '#eff6ff', '#fdf4ff', '#f0fdfa'].map(c => (
                          <button key={c} onClick={() => { bgOverriddenRef.current = true; setCanvasBackground(c); }}
                            title={`Canvas background ${c}`}
                            className={`w-7 h-7 rounded border-2 transition-transform hover:scale-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-1 focus-visible:ring-emerald-500 ${canvasBackground === c ? 'border-gray-800' : 'border-transparent'}`}
                            style={{ background: c, boxShadow: c === '#ffffff' ? 'inset 0 0 0 1px #d1d5db' : undefined }} />
                        ))}
                      </div>
                      <div className="flex items-center gap-2">
                        <input type="color" aria-label="Canvas background colour" value={canvasBackground} onChange={(e) => { bgOverriddenRef.current = true; setCanvasBackground(e.target.value); }} className="w-7 h-6 rounded border border-gray-200 cursor-pointer p-0 shrink-0" />
                        <input type="text" aria-label="Canvas background hex" value={canvasBackground} onChange={(e) => { bgOverriddenRef.current = true; setCanvasBackground(e.target.value); }} className="flex-1 min-w-0 px-2 py-1 text-[11px] border border-gray-200 rounded-md font-mono focus:outline-none focus:ring-1 focus:ring-gray-400" />
                      </div>
                      <div className="mt-2 border-t border-gray-100 pt-2">
                        <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-1.5">Background Image</p>
                        <input type="url" value={canvasBackgroundImage} onChange={(e) => setCanvasBackgroundImage(e.target.value)} placeholder="Image URL or data URI…" className="w-full px-2 py-1 text-[10px] border border-gray-200 rounded-md font-mono focus:outline-none focus:ring-1 focus:ring-gray-400 mb-1" />
                        <div className="flex gap-1">
                          <input type="file" id="bg-img-file" accept="image/*" className="hidden" onChange={(e) => { const file = e.target.files?.[0]; if (!file) return; const reader = new FileReader(); reader.onload = (ev) => setCanvasBackgroundImage(ev.target?.result as string); reader.readAsDataURL(file); }} />
                          <label htmlFor="bg-img-file" className="flex-1 text-center py-1 text-[9px] text-gray-500 hover:text-gray-700 rounded border border-gray-200 hover:bg-gray-50 cursor-pointer">Upload</label>
                          {canvasBackgroundImage && (<button onClick={() => setCanvasBackgroundImage('')} className="px-2 py-1 text-[9px] text-red-500 hover:bg-red-50 rounded border border-red-200">Clear</button>)}
                        </div>
                      </div>
                      <button onClick={() => setShowBgPicker(false)} className="mt-2 w-full py-1 text-[10px] text-gray-500 hover:text-gray-700 rounded-md border border-gray-200 hover:bg-gray-50">Done</button>
                    </div>
                  )}
                    </div>
                    {/* Find element */}
                    <Tooltip text="Find & Replace (Ctrl+F) — search nodes by label">
                      <button className={`toolbar-button ${showFind ? 'toolbar-button-active' : ''}`} onClick={() => setShowFind(v => !v)}>
                        <i className="fas fa-search" />
                      </button>
                    </Tooltip>
                    {/* Format painter — visible when an element is selected */}
                    {selectedElement && (
                      <Tooltip text={formatPainterActive ? 'Click a target node to paste style (Esc to cancel)' : 'Format Painter — copy style from selected node'}>
                        <button className={`toolbar-button ${formatPainterActive ? 'toolbar-button-active' : ''}`} onClick={activateFormatPainter}>
                          <i className="fas fa-paint-brush" />
                        </button>
                      </Tooltip>
                    )}
                    {selectedElement && selectedCount >= 2 && (
                      <Tooltip text="Apply This Style to All Selected Nodes">
                        <button className="toolbar-button" onClick={handleBatchStyleApply}>
                          <i className="fas fa-fill-drip" />
                        </button>
                      </Tooltip>
                    )}
                  </div>
                </div>
                <div className="toolbar-separator" />

                {/* ── ⋯ MORE (Page, Export — moved here to prevent toolbar overflow) ── */}
                <div className="toolbar-group">
                  <span className="toolbar-group-label">More</span>
                  <div className="toolbar-group-buttons">
                    <Tooltip text="More options — Page Format, Versions, Export">
                      <button
                        ref={moreBtnRef}
                        className={`toolbar-button ${showMoreMenu ? 'toolbar-button-active' : ''}`}
                        onClick={() => {
                          if (!showMoreMenu && moreBtnRef.current) {
                            const r = moreBtnRef.current.getBoundingClientRect();
                            setMoreMenuAnchor({ top: r.bottom + 4, left: r.left });
                          }
                          setShowMoreMenu(v => !v);
                        }}
                      >
                        <i className="fas fa-ellipsis-h" />
                      </button>
                    </Tooltip>
                  </div>
                </div>
                <div className="toolbar-separator" />

                {/* ── ARRANGE ── */}
                <div className="toolbar-group">
                  <span className="toolbar-group-label">Arrange</span>
                  <div className="toolbar-group-buttons">
                    <div ref={layoutBtnRef}>
                      <Tooltip text="Auto Layout — automatically arrange all nodes (Hierarchical, Organic, Tree, Grid…)">
                        <button
                          className="toolbar-button"
                          onClick={() => {
                            if (!showLayoutMenu && layoutBtnRef.current) {
                              const r = layoutBtnRef.current.getBoundingClientRect();
                              setLayoutMenuAnchor({ top: r.bottom + 4, left: r.left });
                            }
                            setShowLayoutMenu(v => !v);
                          }}
                        >
                          <i className="fas fa-sitemap" />
                        </button>
                      </Tooltip>
                    </div>
                    <Tooltip text={snapToGrid ? 'Snap to Grid: On — nodes snap to 24px grid when moved or dropped (click to disable)' : 'Snap to Grid — nodes lock to a 24px grid for clean alignment; also applies on drop'}>

                      <button className={`toolbar-button relative ${snapToGrid ? 'toolbar-button-active' : ''}`} onClick={() => setSnapToGrid(v => !v)}>
                        <i className="fas fa-magnet" />
                        {snapToGrid && (<span className="absolute -top-0.5 -right-0.5 w-2 h-2 bg-green-500 rounded-full border border-white" />)}
                      </button>
                    </Tooltip>
                    {snapToGrid && (
                      <Tooltip text="Align All to Grid — snap every node to the nearest 24px grid point">
                        <button className="toolbar-button" onClick={handleAlignToGrid}>
                          <i className="fas fa-border-all" />
                        </button>
                      </Tooltip>
                    )}
                  </div>
                </div>

                {/* Multi-select tools — 2+ nodes */}
                {selectedCount >= 2 && (
                  <>
                    <div className="toolbar-separator" />
                    <Tooltip text="Group (Ctrl+G)">
                      <button className="toolbar-button" onClick={handleGroup}>
                        <i className="fas fa-object-group" />
                      </button>
                    </Tooltip>
                    {([
                      { dir: 'left', icon: 'fa-align-left', title: 'Align Left' },
                      { dir: 'hcenter', icon: 'fa-align-center', title: 'Align Center (H)' },
                      { dir: 'right', icon: 'fa-align-right', title: 'Align Right' },
                      { dir: 'top', icon: 'fa-arrows-alt-h', title: 'Align Top' },
                      { dir: 'vcenter', icon: 'fa-align-justify', title: 'Align Middle (V)' },
                      { dir: 'bottom', icon: 'fa-border-bottom', title: 'Align Bottom' },
                    ] as const).map(b => (
                      <Tooltip key={b.dir} text={b.title}>
                        <button className="toolbar-button" onClick={() => alignNodes(b.dir)}>
                          <i className={`fas ${b.icon}`} />
                        </button>
                      </Tooltip>
                    ))}
                    <Tooltip text="Distribute Horizontally">
                      <button className="toolbar-button" onClick={() => distributeNodes('h')}>
                        <i className="fas fa-arrows-alt-h" />
                      </button>
                    </Tooltip>
                    <Tooltip text="Distribute Vertically">
                      <button className="toolbar-button" onClick={() => distributeNodes('v')}>
                        <i className="fas fa-arrows-alt-v" />
                      </button>
                    </Tooltip>
                  </>
                )}

                {/* Zoom to selection — any selection */}
                {selectedCount >= 1 && (
                  <>
                    <div className="toolbar-separator" />
                    <Tooltip text="Zoom to Selection">
                      <button
                        className="toolbar-button"
                        onClick={() => cyRef.current?.fit(cyRef.current.$(':selected'), 60)}
                      >
                        <i className="fas fa-search-plus" />
                      </button>
                    </Tooltip>
                  </>
                )}
              </>
            )}
            {/* ── Live mode: layout action + inline stats ── */}
            {mode === 'live' && (
              <>
                <Tooltip text="Re-arrange VPCs in grid layout">
                  <button className="toolbar-button" onClick={handleRelayoutLive}>
                    <i className="fas fa-th" />
                  </button>
                </Tooltip>
                <div className="toolbar-separator" />
                {!messageOverlay.show && (() => {
                  // Use source/target presence (same as renderGraph) so stats work
                  // regardless of whether the API returns group:'nodes' or group:'node'
                  const lnodes = graphElements.filter(e => !(e.data as any).source && !(e.data as any).target);
                  const ledges = graphElements.filter(e => !!(e.data as any).source && !!(e.data as any).target);
                  // Single-VPC selection: 1 VPC is implicit; All-VPCs: count synthetic roots
                  const lvpcsCount = selectedVpcValue && selectedVpcValue !== ALL_VPCS_VALUE
                    ? (lnodes.length > 0 ? 1 : 0)
                    : lnodes.filter(e => (e.data as NodeData).type === 'VPC').length;
                  const lsubnets = lnodes.filter(e => (e.data as NodeData).type === 'Subnet');
                  const lresources = lnodes.filter(e => !['VPC', 'Subnet'].includes((e.data as NodeData).type ?? ''));
                  return (
                    <>
                      <div className="live-stat-item"><span className="live-stat-dot" style={{ background: canvasTokens.ink }} /><span className="live-stat-count">{lvpcsCount}</span><span>VPCs</span></div>
                      <div className="live-stat-item"><span className="live-stat-dot" style={{ background: '#0ea5e9' }} /><span className="live-stat-count">{lsubnets.length}</span><span>Subnets</span></div>
                      <div className="live-stat-item"><span className="live-stat-dot" style={{ background: '#8b5cf6' }} /><span className="live-stat-count">{lresources.length}</span><span>Resources</span></div>
                      <div className="live-stat-item"><span className="live-stat-dot" style={{ background: canvasTokens.edge }} /><span className="live-stat-count">{ledges.length}</span><span>Connections</span></div>
                      <div className="toolbar-separator" />
                      <button
                        className="toolbar-button"
                        style={{ gap: '0.35rem', display: 'flex', alignItems: 'center', padding: '0 0.6rem', fontSize: '0.72rem', fontWeight: 600 }}
                        onClick={handleRefreshTopology}
                        disabled={refreshingTopology}
                        title="Refresh topology"
                      >
                        <i className="fas fa-sync-alt" style={refreshingTopology ? { animation: 'spin 0.8s linear infinite' } : {}} />
                        {refreshingTopology ? 'Refreshing…' : 'Refresh'}
                      </button>
                      <div className="toolbar-separator" />
                      <Tooltip text={showMinimap ? 'Hide Minimap' : 'Show Minimap'}>
                        <button
                          className={`toolbar-button ${showMinimap ? 'toolbar-button-active' : ''}`}
                          onClick={() => setShowMinimap(v => !v)}
                        >
                          <i className="fas fa-map" />
                        </button>
                      </Tooltip>
                    </>
                  );
                })()}
              </>
            )}
            {/* Spacer — pushes zoom controls to the right */}
            <div className="flex-1" />
            <div className="toolbar-separator" />
            <Tooltip text="Fit All (F)">
              <button className="toolbar-button" onClick={() => cyRef.current?.fit()}>
                <i className="fas fa-expand-arrows-alt" />
              </button>
            </Tooltip>
            <Tooltip text="Center View">
              <button className="toolbar-button" onClick={() => cyRef.current?.center()}>
                <i className="fas fa-crosshairs" />
              </button>
            </Tooltip>
            <Tooltip text="Zoom Out">
              <button className="toolbar-button" onClick={() => cyRef.current?.zoom(cyRef.current.zoom() * 0.8)}>
                <i className="fas fa-minus" />
              </button>
            </Tooltip>
            <div className="toolbar-zoom-badge">{zoomLevel}%</div>
            <Tooltip text="Zoom In">
              <button className="toolbar-button" onClick={() => cyRef.current?.zoom(cyRef.current.zoom() * 1.2)}>
                <i className="fas fa-plus" />
              </button>
            </Tooltip>
            {mode === 'design' && (
              <>
                <div className="toolbar-separator" />
                <Tooltip text="Help & Keyboard Shortcuts (?)">
                  <button
                    className={`toolbar-button ${showHelp ? 'toolbar-button-active' : ''}`}
                    onClick={() => setShowHelp(v => !v)}
                  >
                    <i className="fas fa-question-circle" />
                  </button>
                </Tooltip>
              </>
            )}
          </div>

          {/* ── Canvas ── */}
          <div
            className={`canvas-wrapper cm-theme-${canvasThemeName}${formatPainterActive ? ' format-painter-active' : ''}${mode === 'design' ? ' has-page-tabs design-mode' : ''}${showRulers ? ' has-rulers' : ''}${sketchMode ? ' sketch-mode' : ''}`}
            onDragOver={(e) => e.preventDefault()}
            onDrop={handleCanvasDrop}
          >
            {/* SVG filter definitions for sketch mode */}
            <svg style={{ position: 'absolute', width: 0, height: 0, overflow: 'hidden' }}>
              <defs>
                <filter id="sketch-filter" x="-5%" y="-5%" width="110%" height="110%">
                  <feTurbulence type="fractalNoise" baseFrequency="0.018 0.06" numOctaves="3" seed="5" result="noise" />
                  <feDisplacementMap in="SourceGraphic" in2="noise" scale="3.5" xChannelSelector="R" yChannelSelector="G" />
                </filter>
              </defs>
            </svg>
            <div
              ref={containerRef}
              className={`cytoscape-container${mode === 'design' && showGrid ? ' design-grid' : ''}${mode === 'live' ? ' live-mode' : ''}`}
              style={{
                ...(mode === 'design' ? { backgroundColor: canvasBackground } : {}),
                ...(canvasBackgroundImage ? {
                  backgroundImage: `url(${canvasBackgroundImage})`,
                  backgroundSize: 'cover',
                  backgroundPosition: 'center',
                  backgroundRepeat: 'no-repeat',
                } : {}),
                cursor: isRotating ? 'grabbing' : undefined,
              }}
            />

            {/* Inline label editor */}
            {mode === 'design' && labelEditor && (
              <div
                className="absolute z-50 pointer-events-auto"
                style={{ left: labelEditor.x + canvasInset, top: labelEditor.y + canvasInset, transform: 'translate(-50%, -50%)' }}
              >
                <input
                  ref={labelInputRef}
                  type="text"
                  value={labelEditor.value}
                  onChange={(e) => setLabelEditor(prev => prev ? { ...prev, value: e.target.value } : null)}
                  onBlur={commitLabelEdit}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') commitLabelEdit();
                    if (e.key === 'Escape') setLabelEditor(null);
                    e.stopPropagation();
                  }}
                  className="label-editor-input"
                  style={{ width: Math.max(80, labelEditor.value.length * 9 + 24) }}
                />
              </div>
            )}

            {/* Smart alignment guides during drag */}
            {mode === 'design' && alignGuides.length > 0 && (
              <svg
                className="absolute inset-0 pointer-events-none"
                style={{ width: '100%', height: '100%', zIndex: 88, overflow: 'visible' }}
              >
                <g transform={`translate(${canvasInset}, ${canvasInset})`}>
                  {alignGuides.map((g, i) =>
                    g.type === 'v'
                      ? <line key={i} x1={g.pos} y1={-9999} x2={g.pos} y2={99999}
                        stroke="#374151" strokeWidth={1} strokeDasharray="4 3" opacity={0.55} />
                      : <line key={i} x1={-9999} y1={g.pos} x2={99999} y2={g.pos}
                        stroke="#374151" strokeWidth={1} strokeDasharray="4 3" opacity={0.55} />
                  )}
                </g>
              </svg>
            )}

            {/* Rotation handle — above the resize handles */}
            {mode === 'design' && nodeBox && !edgeDraft && (
              <div
                style={{ position: 'absolute', left: (nodeBox.x1 + nodeBox.x2) / 2 - 10 + canvasInset, top: nodeBox.y1 - 36 + canvasInset, zIndex: 99 }}
                className="w-5 h-5 rounded-full bg-white border-2 border-gray-500 flex items-center justify-center cursor-grab shadow-sm"
                onMouseDown={handleRotateMouseDown}
                title="Drag to rotate"
              >
                <i className="fas fa-sync-alt text-[7px] text-gray-500" />
              </div>
            )}

            {/* Drag resize handles — single node selected */}
            {mode === 'design' && nodeBox && !edgeDraft && (
              <ResizeHandles
                x1={nodeBox.x1 + canvasInset} y1={nodeBox.y1 + canvasInset}
                x2={nodeBox.x2 + canvasInset} y2={nodeBox.y2 + canvasInset}
                onHandleMouseDown={handleResizeHandleMouseDown}
              />
            )}

            {/* SVG Import dialog */}
            {mode === 'design' && showSvgImport && (
              <div className="absolute top-12 left-1/2 -translate-x-1/2 z-[200] bg-white rounded-xl shadow-2xl border border-gray-100 p-4 w-80">
                <div className="flex items-center justify-between mb-3">
                  <span className="text-xs font-bold text-gray-700">Import SVG Shape</span>
                  <button onClick={() => setShowSvgImport(false)} className="w-6 h-6 flex items-center justify-center rounded hover:bg-gray-100 text-gray-400">
                    <i className="fas fa-times text-[11px]" />
                  </button>
                </div>
                <textarea value={svgImportText} onChange={(e) => setSvgImportText(e.target.value)}
                  className="w-full h-32 px-2 py-1.5 text-[11px] border border-gray-200 rounded-md font-mono resize-none focus:outline-none focus:ring-1 focus:ring-gray-400"
                  placeholder="Paste SVG markup here…" />
                <button onClick={() => {
                  if (!svgImportText.trim() || !cyRef.current) return;
                  const dataUrl = `data:image/svg+xml,${encodeURIComponent(svgImportText.trim())}`;
                  takeSnapshotRef.current();
                  const nodeId = 'svg-' + Date.now();
                  const cy2 = cyRef.current;
                  const center = cy2.pan(); const zoom = cy2.zoom();
                  const w = cy2.width() / 2; const h = cy2.height() / 2;
                  const node = cy2.add({ group: 'nodes', data: { id: nodeId, label: 'Custom Shape', fillColor: 'transparent', borderColor: 'none', customIcon: dataUrl }, position: { x: (-center.x + w) / zoom, y: (-center.y + h) / zoom } });
                  node.style({ 'background-image': dataUrl, 'background-opacity': 0, 'background-fit': 'contain', width: 100, height: 100 });
                  setSvgImportText(''); setShowSvgImport(false);
                }} className="mt-2 w-full py-1.5 bg-gray-900 text-white text-[11px] font-semibold rounded-lg hover:bg-gray-800 transition-colors">
                  Add to Canvas
                </button>
              </div>
            )}

            {/* Edge waypoint handle — drag to bend the selected edge */}
            {mode === 'design' && edgeHandle && !edgeDraft && (
              <div
                className="edge-waypoint-handle"
                style={{ left: edgeHandle.x - 7 + canvasInset, top: edgeHandle.y - 7 + canvasInset }}
                onMouseDown={handleEdgeWaypointMouseDown}
                title="Drag to bend edge"
              />
            )}

            {/* Connection arrows — shown on node hover */}
            {mode === 'design' && hoverBox && hoverNodeId && !drawEdgeMode && !edgeDraft && (
              <ConnectionArrows
                x1={hoverBox.x1 + canvasInset} y1={hoverBox.y1 + canvasInset}
                x2={hoverBox.x2 + canvasInset} y2={hoverBox.y2 + canvasInset}
                onArrowMouseDown={handleConnectionArrowMouseDown}
                onArrowMouseEnter={() => {
                  if (hoverClearTimerRef.current) { clearTimeout(hoverClearTimerRef.current); hoverClearTimerRef.current = null; }
                }}
              />
            )}

            {/* Port dots on node hover — design mode only */}
            {mode === 'design' && hoverBox && hoverNodeId && !edgeDraft && (
              <>
                {([
                  { port: 'top', x: (hoverBox.x1 + hoverBox.x2) / 2 + canvasInset, y: hoverBox.y1 + canvasInset },
                  { port: 'bottom', x: (hoverBox.x1 + hoverBox.x2) / 2 + canvasInset, y: hoverBox.y2 + canvasInset },
                  { port: 'left', x: hoverBox.x1 + canvasInset, y: (hoverBox.y1 + hoverBox.y2) / 2 + canvasInset },
                  { port: 'right', x: hoverBox.x2 + canvasInset, y: (hoverBox.y1 + hoverBox.y2) / 2 + canvasInset },
                ] as const).map(({ port, x, y }) => (
                  <div
                    key={port}
                    title={`Connect from ${port}`}
                    className="absolute w-2.5 h-2.5 rounded-full bg-blue-500 border-2 border-white shadow-sm cursor-crosshair z-[97] hover:scale-125 transition-transform"
                    style={{ left: x - 5, top: y - 5 }}
                    onMouseEnter={() => {
                      if (hoverClearTimerRef.current) { clearTimeout(hoverClearTimerRef.current); hoverClearTimerRef.current = null; }
                    }}
                    onMouseDown={(e) => {
                      e.stopPropagation();
                      setPendingSourcePort(port);
                      pendingSourcePortRef.current = port;
                      handleConnectionArrowMouseDown(e);
                    }}
                  />
                ))}
              </>
            )}

            {/* Jump-over crossing arcs */}
            {showCrossings && edgeCrossings.length > 0 && (() => {
              const zoom = canvasViewport.zoom;
              const r = Math.max(6, 8 * zoom);
              return (
                <svg
                  className="absolute inset-0 pointer-events-none"
                  style={{ width: '100%', height: '100%', zIndex: 56, overflow: 'visible' }}
                >
                  <g transform={`translate(${canvasInset}, ${canvasInset})`}>
                    {edgeCrossings.map((pt, i) => (
                      <g key={i}>
                        {/* Wide white gap to erase the underlying edge line */}
                        <path
                          d={`M ${pt.x - r} ${pt.y} A ${r} ${r} 0 0 1 ${pt.x + r} ${pt.y}`}
                          fill="none" stroke="white" strokeWidth={r * 1.6}
                        />
                        {/* Coloured arc bridge on top */}
                        <path
                          d={`M ${pt.x - r} ${pt.y} A ${r} ${r} 0 0 1 ${pt.x + r} ${pt.y}`}
                          fill="none" stroke="#6366f1" strokeWidth={2.5} strokeLinecap="round"
                        />
                      </g>
                    ))}
                  </g>
                </svg>
              );
            })()}

            {/* Page boundary overlay */}
            {showPageBoundary && pageFormat !== 'none' && (() => {
              const fmt = PAGE_FORMATS[pageFormat];
              if (!fmt) return null;
              const zoom = canvasViewport.zoom;
              const pan = canvasViewport.pan;
              const x = pan.x + canvasInset;
              const y = pan.y + canvasInset;
              const w = fmt.w * zoom;
              const h = fmt.h * zoom;
              return (
                <svg
                  className="absolute inset-0 pointer-events-none"
                  style={{ width: '100%', height: '100%', zIndex: 52, overflow: 'visible' }}
                >
                  <rect x={x} y={y} width={w} height={h} fill="rgba(59,130,246,0.03)" stroke="#3b82f6" strokeWidth="1.5" strokeDasharray="8 4" />
                  <text x={x + 6} y={y - 6} fontSize="10" fill="#3b82f6" fontFamily="sans-serif">{fmt.label}</text>
                </svg>
              );
            })()}

            {/* Freehand drawing overlay */}
            {mode === 'design' && (
              <svg
                className="absolute inset-0"
                style={{ width: '100%', height: '100%', zIndex: freehandMode ? 120 : 100, overflow: 'visible', pointerEvents: freehandMode ? 'all' : 'none', cursor: eraserMode ? 'cell' : freehandMode ? 'crosshair' : 'default' }}
                onMouseDown={(e) => {
                  if (!freehandMode) return;
                  if (eraserMode) return; // eraser works on click of individual paths
                  const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
                  const x = e.clientX - rect.left;
                  const y = e.clientY - rect.top;
                  freehandDrawingRef.current = { active: true, points: `M ${x} ${y}`, color: '#374151', width: 2 };
                }}
                onMouseMove={(e) => {
                  if (!freehandDrawingRef.current?.active) return;
                  const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
                  const x = e.clientX - rect.left;
                  const y = e.clientY - rect.top;
                  freehandDrawingRef.current.points += ` L ${x} ${y}`;
                  const d = freehandDrawingRef.current.points;
                  setFreehandPaths(prev => {
                    const last = prev.find(p => p.id === 'current');
                    if (last) return prev.map(p => p.id === 'current' ? { ...p, d } : p);
                    return [...prev, { id: 'current', d, color: '#374151', width: 2 }];
                  });
                }}
                onMouseUp={() => {
                  if (!freehandDrawingRef.current?.active) return;
                  const { points, color, width } = freehandDrawingRef.current;
                  freehandDrawingRef.current = null;
                  setFreehandPaths(prev => [...prev.filter(p => p.id !== 'current'), { id: Date.now().toString(), d: points, color, width }]);
                }}
              >
                {freehandPaths.map(path => (
                  <path
                    key={path.id}
                    d={path.d}
                    fill="none"
                    stroke={path.color}
                    strokeWidth={eraserMode ? path.width + 6 : path.width}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    style={{ cursor: eraserMode ? 'pointer' : 'default' }}
                    onClick={() => {
                      if (!eraserMode) return;
                      setFreehandPaths(prev => prev.filter(p => p.id !== path.id));
                    }}
                  />
                ))}
              </svg>
            )}

            {/* Floating freehand control bar — always visible when freehand mode is on */}
            {mode === 'design' && freehandMode && (
              <div className="absolute top-2 left-1/2 -translate-x-1/2 z-[130] flex items-center gap-1 bg-white rounded-xl shadow-2xl border border-gray-200 px-2 py-1.5">
                <button
                  className={`flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-semibold rounded-lg transition-all ${!eraserMode ? 'bg-gray-900 text-white' : 'text-gray-500 hover:bg-gray-100'}`}
                  onClick={() => setEraserMode(false)}
                >
                  <i className="fas fa-pen text-[10px]" /> Draw
                </button>
                <button
                  className={`flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-semibold rounded-lg transition-all ${eraserMode ? 'bg-gray-900 text-white' : 'text-gray-500 hover:bg-gray-100'}`}
                  onClick={() => setEraserMode(v => !v)}
                >
                  <i className="fas fa-eraser text-[10px]" /> Eraser
                </button>
                <div className="w-px h-4 bg-gray-200 mx-0.5" />
                <button
                  className="flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-semibold text-gray-500 hover:bg-gray-100 rounded-lg transition-all disabled:opacity-40"
                  disabled={freehandPaths.length === 0}
                  onClick={() => setFreehandPaths(prev => prev.slice(0, -1))}
                  title="Undo last stroke"
                >
                  <i className="fas fa-undo text-[10px]" />
                </button>
                <button
                  className="flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-semibold text-red-500 hover:bg-red-50 rounded-lg transition-all disabled:opacity-40"
                  disabled={freehandPaths.length === 0}
                  onClick={() => setFreehandPaths([])}
                  title="Clear all strokes"
                >
                  <i className="fas fa-trash text-[10px]" />
                </button>
                <div className="w-px h-4 bg-gray-200 mx-0.5" />
                <button
                  className="flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-semibold text-gray-500 hover:bg-gray-100 rounded-lg transition-all"
                  onClick={() => { setFreehandMode(false); setEraserMode(false); }}
                >
                  <i className="fas fa-times text-[10px]" /> Exit
                </button>
              </div>
            )}

            {/* Comment indicator badge on selected node */}
            {mode === 'design' && nodeBox && selectedElement?.comments && (
              <div className="absolute pointer-events-none z-[97]" style={{ left: nodeBox.x2 - 8 + canvasInset, top: nodeBox.y1 - 8 + canvasInset }}>
                <div className="w-4 h-4 bg-yellow-400 rounded-full flex items-center justify-center shadow-sm">
                  <i className="fas fa-comment text-[7px] text-yellow-900" />
                </div>
              </div>
            )}

            {/* LaTeX / KaTeX math overlays — rendered for nodes with $…$ labels */}
            {latexOverlays.map(ov => (
              <div
                key={ov.id}
                className="absolute pointer-events-none z-[96] flex items-center justify-center overflow-hidden"
                style={{ left: ov.x + canvasInset, top: ov.y + canvasInset, width: ov.w, height: ov.h }}
                dangerouslySetInnerHTML={{ __html: ov.html }}
              />
            ))}

            {/* Save version modal — replaces window.prompt to avoid main-thread freeze */}
            {showSaveVersionModal && createPortal(
              <div className="fixed inset-0 z-[9000] flex items-center justify-center bg-black/30 backdrop-blur-[1px]" onClick={() => setShowSaveVersionModal(false)}>
                <div className="bg-white rounded-xl shadow-2xl border border-gray-100 p-5 w-80" onClick={e => e.stopPropagation()}>
                  <p className="text-[13px] font-bold text-gray-800 mb-1">Save Version Snapshot</p>
                  <p className="text-[10px] text-gray-400 mb-3">Give this checkpoint a name so you can find it in Version History.</p>
                  <input
                    autoFocus
                    type="text"
                    value={pendingVersionName}
                    onChange={e => setPendingVersionName(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') handleConfirmSaveVersion(pendingVersionName); if (e.key === 'Escape') setShowSaveVersionModal(false); }}
                    className="w-full px-3 py-2 text-[12px] border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-400 mb-3"
                    placeholder="e.g. Before refactor"
                  />
                  <div className="flex justify-end gap-2">
                    <button onClick={() => setShowSaveVersionModal(false)} className="px-3 py-1.5 text-[11px] text-gray-500 hover:bg-gray-100 rounded-lg">Cancel</button>
                    <button onClick={() => handleConfirmSaveVersion(pendingVersionName)} className="px-3 py-1.5 text-[11px] bg-blue-600 text-white rounded-lg hover:bg-blue-700 font-semibold">Save</button>
                  </div>
                </div>
              </div>,
              document.body
            )}

            {/* Version history panel */}
            {mode === 'design' && showVersionHistory && (
              <div className="absolute top-2 right-2 z-[150] bg-white rounded-xl shadow-2xl border border-gray-100 w-56 overflow-hidden">
                <div className="flex items-center justify-between px-3 py-2 border-b border-gray-100">
                  <span className="text-[11px] font-bold text-gray-700">Version History</span>
                  <button onClick={() => setShowVersionHistory(false)} className="w-5 h-5 flex items-center justify-center rounded hover:bg-gray-100 text-gray-400">
                    <i className="fas fa-times text-[9px]" />
                  </button>
                </div>
                <div className="max-h-64 overflow-y-auto">
                  {versionHistory.length === 0 && (
                    <p className="text-[10px] text-gray-400 text-center py-4">No saved versions</p>
                  )}
                  {[...versionHistory].reverse().map(v => (
                    <div key={v.id} className="flex items-center gap-2 px-3 py-2 hover:bg-gray-50 border-b border-gray-50">
                      <i className="fas fa-clock text-gray-300 text-[9px] shrink-0" />
                      <div className="flex-1 min-w-0">
                        <p className="text-[10px] font-semibold text-gray-700 truncate">{v.name}</p>
                        <p className="text-[9px] text-gray-400">{new Date(v.ts).toLocaleTimeString()}</p>
                      </div>
                      <button
                        onClick={() => {
                          if (!cyRef.current) return;
                          if (!window.confirm('Restore this version? Current canvas will be replaced.\n\nTip: the current state is pushed to Undo so you can revert.')) return;
                          takeSnapshotRef.current();
                          const els = v.elements;
                          setTimeout(() => {
                            if (!cyRef.current) return;
                            cyRef.current.elements().remove();
                            cyRef.current.add(els);
                            cyRef.current.elements().forEach((el: any) => applyElementStyle(el));
                          }, 0);
                          setShowVersionHistory(false);
                        }}
                        className="text-[9px] text-blue-600 hover:text-blue-800 font-semibold shrink-0"
                      >
                        Restore
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Scratchpad panel */}
            {mode === 'design' && showScratchpad && (
              <div className="absolute bottom-10 left-2 z-[150] bg-white rounded-xl shadow-2xl border border-gray-100 w-48 overflow-hidden">
                <div className="flex items-center justify-between px-3 py-2 border-b border-gray-100">
                  <span className="text-[11px] font-bold text-gray-700">Scratchpad</span>
                  <button onClick={() => setShowScratchpad(false)} className="w-5 h-5 flex items-center justify-center rounded hover:bg-gray-100 text-gray-400">
                    <i className="fas fa-times text-[9px]" />
                  </button>
                </div>
                <div className="max-h-48 overflow-y-auto">
                  {scratchpadItems.length === 0 && (
                    <p className="text-[10px] text-gray-400 text-center py-3">Right-click node → Send to Scratchpad</p>
                  )}
                  {scratchpadItems.map(item => (
                    <div key={item.id} className="flex items-center gap-2 px-3 py-2 hover:bg-gray-50 border-b border-gray-50">
                      <i className="fas fa-square text-gray-300 text-[9px] shrink-0" />
                      <span className="flex-1 min-w-0 text-[10px] text-gray-700 truncate">{item.label}</span>
                      <button
                        onClick={() => {
                          if (!cyRef.current) return;
                          takeSnapshotRef.current();
                          const newId = 'scratch-' + Date.now();
                          const pan = cyRef.current.pan();
                          const zoom = cyRef.current.zoom();
                          const node = cyRef.current.add({
                            group: 'nodes',
                            data: { ...item.data, id: newId },
                            position: { x: (-pan.x + cyRef.current.width() / 2) / zoom, y: (-pan.y + cyRef.current.height() / 2) / zoom },
                          });
                          applyElementStyle(node);
                        }}
                        className="text-[9px] text-blue-600 hover:text-blue-800 font-semibold shrink-0"
                      >Place</button>
                      <button
                        onClick={() => setScratchpadItems(prev => prev.filter(i => i.id !== item.id))}
                        className="text-[9px] text-red-400 hover:text-red-600"
                      >
                        <i className="fas fa-times" />
                      </button>
                    </div>
                  ))}
                </div>
                {scratchpadItems.length > 0 && (
                  <div className="px-3 py-2 border-t border-gray-100">
                    <button onClick={() => setScratchpadItems([])} className="w-full py-1 text-[9px] text-red-500 hover:bg-red-50 rounded border border-red-200">Clear All</button>
                  </div>
                )}
              </div>
            )}

            {/* Ghost edge while dragging from connection arrow */}
            {edgeDraft && (
              <svg
                className="absolute inset-0 pointer-events-none"
                style={{ width: '100%', height: '100%', zIndex: 96, overflow: 'visible' }}
              >
                <defs>
                  <marker id="draft-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                    markerWidth="6" markerHeight="6" orient="auto">
                    <path d="M 0 0 L 10 5 L 0 10 z" fill="#374151" />
                  </marker>
                </defs>
                <g transform={`translate(${canvasInset}, ${canvasInset})`}>
                  <line
                    x1={edgeDraft.x1} y1={edgeDraft.y1}
                    x2={edgeDraft.x2} y2={edgeDraft.y2}
                    stroke="#374151" strokeWidth={2} strokeDasharray="6 4"
                    markerEnd="url(#draft-arrow)"
                  />
                </g>
              </svg>
            )}

            {/* Find & Replace overlay (Ctrl+F) */}
            {mode === 'design' && showFind && (
              <div className="find-overlay">
                <i className="fas fa-search text-gray-400 text-[11px] shrink-0" />
                <input
                  autoFocus
                  type="text"
                  className="find-input"
                  placeholder="Find label…"
                  value={findQuery}
                  onChange={(e) => doFind(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') findStep(e.shiftKey ? -1 : 1);
                    if (e.key === 'Escape') { setShowFind(false); setFindQuery(''); setFindResults([]); setShowReplace(false); }
                    e.stopPropagation();
                  }}
                />
                <button
                  onClick={() => setShowReplace(v => !v)}
                  className={`px-1.5 py-0.5 text-[9px] rounded border transition-colors ${showReplace ? 'border-gray-700 bg-gray-100 text-gray-700' : 'border-gray-200 text-gray-400 hover:border-gray-300'}`}
                >
                  Replace
                </button>
                <span className="find-count">
                  {findResults.length === 0 ? '–' : `${findIndex + 1}/${findResults.length}`}
                </span>
                <button
                  onClick={() => findStep(-1)}
                  disabled={findResults.length === 0}
                  className="w-6 h-6 flex items-center justify-center rounded hover:bg-gray-100 text-gray-500 disabled:opacity-30"
                ><i className="fas fa-chevron-up text-[9px]" /></button>
                <button
                  onClick={() => findStep(1)}
                  disabled={findResults.length === 0}
                  className="w-6 h-6 flex items-center justify-center rounded hover:bg-gray-100 text-gray-500 disabled:opacity-30"
                ><i className="fas fa-chevron-down text-[9px]" /></button>
                {showReplace && (
                  <>
                    <input
                      type="text"
                      value={replaceQuery}
                      onChange={(e) => setReplaceQuery(e.target.value)}
                      placeholder="Replace with…"
                      onKeyDown={(e) => { e.stopPropagation(); if (e.key === 'Enter') handleReplaceOne(); }}
                      className="find-input border-l border-gray-200 pl-2"
                    />
                    <button
                      onClick={handleReplaceOne}
                      className="px-2 py-0.5 text-[9px] text-gray-600 hover:bg-gray-100 rounded border border-gray-200"
                      title="Replace current"
                    >1</button>
                    <button
                      onClick={handleReplaceAll}
                      className="px-2 py-0.5 text-[9px] text-gray-600 hover:bg-gray-100 rounded border border-gray-200"
                      title="Replace all"
                    >All</button>
                  </>
                )}
                <button
                  onClick={() => { setShowFind(false); setFindQuery(''); setFindResults([]); setShowReplace(false); cyRef.current?.elements().unselect(); }}
                  className="w-6 h-6 flex items-center justify-center rounded hover:bg-gray-100 text-gray-400 hover:text-gray-600"
                ><i className="fas fa-times text-[9px]" /></button>
              </div>
            )}

            {/* Page tab bar */}
            {mode === 'design' && (
              <div className="page-tab-bar">
                {pages.map((page) => (
                  <div
                    key={page.id}
                    className={`page-tab${page.id === currentPageId ? ' active' : ''}${dragOverPageId === page.id && dragPageId !== page.id ? ' border-l-2 border-blue-400' : ''}`}
                    draggable={true}
                    onDragStart={() => setDragPageId(page.id)}
                    onDragOver={(e) => { e.preventDefault(); setDragOverPageId(page.id); }}
                    onDragEnd={() => { setDragPageId(null); setDragOverPageId(null); }}
                    onDrop={() => {
                      if (!dragPageId || dragPageId === page.id) return;
                      setPages(prev => {
                        const arr = [...prev];
                        const fromIdx = arr.findIndex(p => p.id === dragPageId);
                        const toIdx = arr.findIndex(p => p.id === page.id);
                        const [moved] = arr.splice(fromIdx, 1);
                        arr.splice(toIdx, 0, moved);
                        pagesRef.current = arr;
                        return arr;
                      });
                      setDragPageId(null); setDragOverPageId(null);
                    }}
                  >
                    {renamingPageId === page.id ? (
                      <input
                        autoFocus
                        className="page-tab-rename"
                        value={renameValue}
                        onChange={(e) => setRenameValue(e.target.value)}
                        onBlur={commitRename}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') commitRename();
                          if (e.key === 'Escape') setRenamingPageId(null);
                          e.stopPropagation();
                        }}
                      />
                    ) : (
                      <button
                        className="page-tab-btn"
                        onClick={() => switchToPage(page.id)}
                        onDoubleClick={() => { setRenamingPageId(page.id); setRenameValue(page.name); }}
                        title="Double-click to rename"
                      >
                        <i className="fas fa-file-alt text-[9px] opacity-50" />
                        {page.name}
                      </button>
                    )}
                    {pages.length > 1 && renamingPageId !== page.id && (
                      <button
                        className="page-tab-close"
                        onClick={(e) => { e.stopPropagation(); deletePage(page.id); }}
                        title="Delete page"
                      >×</button>
                    )}
                  </div>
                ))}
                <button className="page-tab-add" onClick={addPage} title="Add page">
                  <i className="fas fa-plus text-[11px]" />
                </button>
              </div>
            )}

            {/* Minimap panel */}
            {showMinimap && (
              <div className="absolute bottom-14 right-2 z-[80] bg-white border border-gray-200 rounded-xl shadow-xl overflow-hidden select-none">
                <div className="flex items-center justify-between px-2.5 py-1.5 border-b border-gray-100">
                  <span className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Minimap</span>
                  <button
                    onClick={() => setShowMinimap(false)}
                    className="text-gray-400 hover:text-gray-600 text-[10px] transition-colors"
                  >
                    <i className="fas fa-times" />
                  </button>
                </div>
                {minimapDataUrl
                  ? <img src={minimapDataUrl} alt="Minimap" className="block" style={{ width: 180, height: 120, objectFit: 'contain', background: canvasBackground || canvasTokens.canvas }} />
                  : <div className="flex items-center justify-center text-gray-400 text-[10px]" style={{ width: 180, height: 120 }}>No content</div>
                }
              </div>
            )}

            {/* Snap-to-grid badge */}
            {mode === 'design' && snapToGrid && (
              <div className="absolute bottom-10 left-1/2 -translate-x-1/2 z-[70] pointer-events-none select-none">
                <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-gray-800 text-white text-[10px] font-semibold shadow-lg">
                  <i className="fas fa-magnet text-[9px]" />
                  Snap to Grid: ON
                </div>
              </div>
            )}

            {/* Pan mode badge — shown while Space is held */}
            {mode === 'design' && panModeActive && (
              <div className="absolute bottom-10 left-1/2 -translate-x-1/2 z-[70] pointer-events-none select-none">
                <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-blue-700 text-white text-[10px] font-semibold shadow-lg">
                  <i className="fas fa-hand-paper text-[9px]" />
                  Pan Mode (Space held)
                </div>
              </div>
            )}

            {/* Node tooltip — author-supplied inline HTML, sanitized before render */}
            {mode === 'design' && nodeTooltip && (
              <div
                className="absolute z-[250] pointer-events-none select-none"
                style={{ left: nodeTooltip.x + canvasInset, top: nodeTooltip.y + canvasInset, transform: 'translate(-50%, -100%)' }}
              >
                <div className="bg-gray-900 text-white text-[11px] rounded-lg px-2.5 py-1.5 shadow-xl max-w-[220px] leading-relaxed">
                  <div dangerouslySetInnerHTML={{ __html: sanitizeInlineHtml(nodeTooltip.text.replace(/\n/g, '<br/>')) }} />
                </div>
                <div className="w-2 h-2 bg-gray-900 rotate-45 mx-auto -mt-1" />
              </div>
            )}

            {/* Rulers */}
            {mode === 'design' && showRulers && (
              <>
                {/* Left ruler (vertical) */}
                <div
                  className="absolute left-0 bottom-0 z-[60] select-none"
                  style={{ top: 0, width: 18, background: '#f1f5f9', borderRight: '1px solid #e2e8f0', cursor: 'ew-resize' }}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    const rect = containerRef.current?.getBoundingClientRect();
                    if (!rect) return;
                    const id = 'guide-v-' + Date.now();
                    const onMove = (me: MouseEvent) => {
                      const zoom = cyRef.current?.zoom() ?? 1;
                      const pan = cyRef.current?.pan() ?? { x: 0, y: 0 };
                      const pos = (me.clientX - rect.left - pan.x) / zoom;
                      setGuides(gs => gs.map(g => g.id === id ? { ...g, pos } : g));
                    };
                    const onUp = () => {
                      window.removeEventListener('mousemove', onMove);
                      window.removeEventListener('mouseup', onUp);
                    };
                    const zoom = cyRef.current?.zoom() ?? 1;
                    const pan = cyRef.current?.pan() ?? { x: 0, y: 0 };
                    const initPos = (e.clientX - rect.left - pan.x) / zoom;
                    setGuides(gs => [...gs, { id, type: 'v', pos: initPos }]);
                    window.addEventListener('mousemove', onMove);
                    window.addEventListener('mouseup', onUp);
                  }}
                  title="Drag to create vertical guide"
                >
                  {/* Tick marks */}
                  <svg width="18" height="100%" style={{ position: 'absolute', top: 0, left: 0 }}>
                    {Array.from({ length: 40 }).map((_, i) => (
                      <line key={i} x1={i % 5 === 0 ? 6 : 10} y1={i * 20} x2={18} y2={i * 20}
                        stroke="#94a3b8" strokeWidth="0.5" />
                    ))}
                  </svg>
                </div>
                {/* Top ruler (horizontal) */}
                <div
                  className="absolute left-[18px] right-0 z-[60] select-none"
                  style={{ top: 0, height: 18, background: '#f1f5f9', borderBottom: '1px solid #e2e8f0', cursor: 'ns-resize' }}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    const rect = containerRef.current?.getBoundingClientRect();
                    if (!rect) return;
                    const id = 'guide-h-' + Date.now();
                    const onMove = (me: MouseEvent) => {
                      const zoom = cyRef.current?.zoom() ?? 1;
                      const pan = cyRef.current?.pan() ?? { x: 0, y: 0 };
                      const pos = (me.clientY - rect.top - pan.y) / zoom;
                      setGuides(gs => gs.map(g => g.id === id ? { ...g, pos } : g));
                    };
                    const onUp = () => {
                      window.removeEventListener('mousemove', onMove);
                      window.removeEventListener('mouseup', onUp);
                    };
                    const zoom = cyRef.current?.zoom() ?? 1;
                    const pan = cyRef.current?.pan() ?? { x: 0, y: 0 };
                    const initPos = (e.clientY - rect.top - pan.y) / zoom;
                    setGuides(gs => [...gs, { id, type: 'h', pos: initPos }]);
                    window.addEventListener('mousemove', onMove);
                    window.addEventListener('mouseup', onUp);
                  }}
                  title="Drag to create horizontal guide"
                >
                  <svg width="100%" height="18" style={{ position: 'absolute', top: 0, left: 0 }}>
                    {Array.from({ length: 80 }).map((_, i) => (
                      <line key={i} x1={i * 20} y1={i % 5 === 0 ? 6 : 10} x2={i * 20} y2={18}
                        stroke="#94a3b8" strokeWidth="0.5" />
                    ))}
                  </svg>
                </div>
              </>
            )}

            {/* Guide lines */}
            {mode === 'design' && guides.length > 0 && (
              <svg
                className="absolute inset-0"
                style={{ width: '100%', height: '100%', zIndex: 59, overflow: 'visible', pointerEvents: 'none' }}
              >
                {guides.map((g) => {
                  const zoom = cyRef.current?.zoom() ?? 1;
                  const pan = cyRef.current?.pan() ?? { x: 0, y: 0 };
                  const sp = g.type === 'v' ? g.pos * zoom + pan.x + canvasInset : g.pos * zoom + pan.y + canvasInset;
                  const hitW = 8;
                  return g.type === 'v' ? (
                    <g key={g.id}>
                      <line x1={sp} y1={0} x2={sp} y2={99999}
                        stroke="#3b82f6" strokeWidth="1" strokeDasharray="4 3" opacity={0.6} pointerEvents="none" />
                      <line x1={sp} y1={0} x2={sp} y2={99999}
                        stroke="transparent" strokeWidth={hitW} style={{ cursor: 'col-resize' }}
                        onDoubleClick={() => setGuides(gs => gs.filter(x => x.id !== g.id))}
                      />
                    </g>
                  ) : (
                    <g key={g.id}>
                      <line x1={0} y1={sp} x2={99999} y2={sp}
                        stroke="#3b82f6" strokeWidth="1" strokeDasharray="4 3" opacity={0.6} pointerEvents="none" />
                      <line x1={0} y1={sp} x2={99999} y2={sp}
                        stroke="transparent" strokeWidth={hitW} style={{ cursor: 'row-resize' }}
                        onDoubleClick={() => setGuides(gs => gs.filter(x => x.id !== g.id))}
                      />
                    </g>
                  );
                })}
              </svg>
            )}

            {/* Message Overlay */}
            {messageOverlay.show && (
              <div className="absolute inset-0 flex items-center justify-center z-20 bg-white/80 backdrop-blur-sm">
                <div className="text-center p-8 rounded-2xl bg-white shadow-xl border border-gray-100 max-w-sm">
                  <div className="relative mx-auto mb-6 w-20 h-20">
                    <div className="absolute inset-0 rounded-full border-4 border-gray-200" />
                    <div className="absolute inset-0 rounded-full border-4 border-gray-800 border-t-transparent animate-spin" />
                    <div className="absolute inset-3 rounded-full bg-gray-800 flex items-center justify-center">
                      <i className="fas fa-cloud text-white text-xl" />
                    </div>
                  </div>
                  <h3 className="text-xl font-bold text-gray-800 mb-2">{messageOverlay.title}</h3>
                  {/* Progress cue, not decoration: a staggered opacity pulse
                      reads as "working" without the cartoon bounce easing. */}
                  <div className="mt-4 flex justify-center gap-1.5" role="status" aria-label="Loading">
                    {[0, 160, 320].map(d => (
                      <div
                        key={d}
                        className="w-1.5 h-1.5 rounded-full cm-pulse-dot"
                        style={{ animationDelay: `${d}ms` }}
                      />
                    ))}
                  </div>
                </div>
              </div>
            )}

            {/* Empty State */}
            {showEmptyState && (
              <div className="empty-state">
                <div className="empty-state-content">
                  <div className="empty-state-icon" style={{ color: canvasTokens.inkMuted }}>
                    <i className="fas fa-project-diagram text-2xl" aria-hidden="true" />
                  </div>
                  {/* Names the likely cause rather than only the absence. */}
                  <h3 className="es-title" style={{ color: canvasTokens.ink }}>Nothing to map yet</h3>
                  <p className="es-lede" style={{ color: canvasTokens.inkMuted }}>
                    We reached this account but found no VPCs or resources to draw. That is
                    usually one of the four things below.
                  </p>
                  <div className="empty-state-tips" style={{ borderColor: canvasTokens.hairline }}>
                    <h4 className="es-tips-title" style={{ color: canvasTokens.ink }}>Worth checking</h4>
                    <ul className="es-tips">
                      {[
                        { icon: 'fa-server', text: 'The account genuinely has VPCs and resources deployed' },
                        { icon: 'fa-key', text: 'The XamOps IAM role still has read permission on EC2 and VPC' },
                        { icon: 'fa-globe', text: 'A specific VPC is selected above, not an empty region' },
                        { icon: 'fa-rotate', text: 'Topology has been refreshed since the resources were created' },
                      ].map(({ icon, text }) => (
                        <li key={text} style={{ color: canvasTokens.inkMuted }}>
                          <i className={`fas ${icon}`} aria-hidden="true" style={{ color: canvasTokens.inkMuted }} />
                          <span>{text}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                  <button
                    onClick={handleRefreshTopology}
                    disabled={refreshingTopology}
                    className="es-retry"
                    style={{
                      background: canvasTokens.accent,
                      color: canvasThemeName === 'dark' ? '#04120b' : '#ffffff',
                    }}
                  >
                    <i className={`fas fa-sync-alt${refreshingTopology ? ' fa-spin' : ''}`} aria-hidden="true" />
                    {refreshingTopology ? 'Refreshing…' : 'Refresh topology'}
                  </button>
                </div>
              </div>
            )}

          </div>
        </div>{/* end column wrapper */}

        {/* ── Right: Properties Panel (design) | Info Panel (live) ── */}
        {mode === 'design' ? (
          <DesignPropertiesPanel
            selected={selectedElement}
            onLabelChange={handleLabelChange}
            onFillColorChange={handleFillColorChange}
            onFillColor2Change={handleFillColor2Change}
            onGradientDirChange={handleGradientDirChange}
            onNodeShadowChange={handleNodeShadowChange}
            onShadowDetailChange={handleShadowDetailChange}
            onBorderColorChange={handleBorderColorChange}
            onBorderWidthChange={handleBorderWidthChange}
            onBorderDashChange={handleBorderDashChange}
            onShapeChange={handleShapeChange}
            onEdgeStyleChange={handleEdgeStyleChange}
            onArrowTypeChange={handleArrowTypeChange}
            onFontSizeChange={handleFontSizeChange}
            onFontBoldChange={handleFontBoldChange}
            onFontItalicChange={handleFontItalicChange}
            onFontColorChange={handleFontColorChange}
            onFontFamilyChange={handleFontFamilyChange}
            onTextValignChange={handleTextValignChange}
            onTextHalignChange={handleTextHalignChange}
            onSizeChange={handleSizeChange}
            onOpacityChange={handleOpacityChange}
            onEdgeWidthChange={handleEdgeWidthChange}
            onEdgeDashChange={handleEdgeDashChange}
            onLockToggle={handleLockToggle}
            onTooltipChange={handleTooltipChange}
            onPulseChange={handlePulseChange}
            onFlowAnimChange={handleFlowAnimChange}
            onFlowSpeedChange={handleFlowSpeedChange}
            onDelete={handleDeleteSelected}
            onDuplicate={handleDuplicateSelected}
            onSourceLabelChange={handleSourceLabelChange}
            onTargetLabelChange={handleTargetLabelChange}
            onMidArrowShapeChange={handleMidArrowShapeChange}
            onSourcePortChange={handleSourcePortChange}
            onTargetPortChange={handleTargetPortChange}
            onEdgeReverseDirection={handleEdgeReverseDirection}
            onTaxiDirChange={handleTaxiDirChange}
            onRotationChange={handleRotationChange}
            onFlipChange={handleFlipChange}
            onHyperlinkChange={handleHyperlinkChange}
            onAddTableRow={handleAddTableRow}
            onRawStyleApply={handleRawStyleApply}
            onMetadataChange={handleMetadataChange}
            onCommentsChange={handleCommentsChange}
          />
        ) : (
          renderInfoPanel()
        )}
      </div>

      {/* Context Menu */}
      {contextMenu && (
        <DesignContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          elementType={contextMenu.elementType}
          locked={!!cyRef.current?.getElementById(contextMenu.elementId)?.data('locked')}
          onEditLabel={() => openLabelEditorFor(contextMenu.elementId)}
          onDuplicate={() => {
            if (!cyRef.current) return;
            const el = cyRef.current.getElementById(contextMenu.elementId);
            if (el.length > 0) { el.select(); handleDuplicateSelected(); }
          }}
          onDelete={() => {
            if (!cyRef.current) return;
            const el = cyRef.current.getElementById(contextMenu.elementId);
            if (el.data('locked')) return;
            takeSnapshotRef.current();
            el.remove();
            setSelectedElement(null);
          }}
          onBringToFront={() => bringToFront(contextMenu.elementId)}
          onSendToBack={() => sendToBack(contextMenu.elementId)}
          onBringForward={() => bringForward(contextMenu.elementId)}
          onSendBackward={() => sendBackward(contextMenu.elementId)}
          onLockToggle={() => handleLockToggle(contextMenu.elementId)}
          onClose={() => setContextMenu(null)}
          onReverseDirection={contextMenu.elementType === 'edge'
            ? () => { handleEdgeReverseDirection(contextMenu.elementId); setContextMenu(null); }
            : undefined
          }
          onSendToScratchpad={contextMenu.elementType === 'node' ? () => {
            if (!cyRef.current) return;
            const el = cyRef.current.getElementById(contextMenu.elementId);
            setScratchpadItems(prev => [...prev, { id: Date.now().toString(), label: el.data('label') || 'Untitled', data: { ...el.data(), position: el.position() } }]);
            setContextMenu(null);
          } : undefined}
        />
      )}

      {/* Save Modal */}
      <DesignSaveModal
        isOpen={showSaveModal}
        initialName={currentBlueprintName}
        saving={savingBlueprint}
        onSave={handleSaveConfirm}
        onClose={() => setShowSaveModal(false)}
      />

      {/* Help Modal */}
      {showHelp && <DesignHelpModal onClose={() => setShowHelp(false)} />}

      {/* Templates Modal */}
      {showTemplates && (
        <DesignTemplatesModal
          onClose={() => setShowTemplates(false)}
          onApply={(elements) => {
            if (!cyRef.current) return;
            takeSnapshotRef.current();
            const offset = Date.now().toString(36);
            const idMap: Record<string, string> = {};
            elements.forEach((el) => { if (el.data.id) idMap[el.data.id] = `${el.data.id}-${offset}`; });
            const remapped = elements.map((el) => ({
              ...el,
              data: {
                ...el.data,
                id: idMap[el.data.id] || el.data.id,
                source: el.data.source ? (idMap[el.data.source] || el.data.source) : undefined,
                target: el.data.target ? (idMap[el.data.target] || el.data.target) : undefined,
              },
            }));
            const added = cyRef.current.add(remapped);
            added.forEach((el: any) => applyElementStyle(el));
            cyRef.current.fit(added, 80);
          }}
        />
      )}

      {/* Page Format dropdown — rendered outside toolbar to avoid overflow clipping */}
      {showPageFormatMenu && pageFormatMenuAnchor && createPortal(
        <div
          className="fixed bg-white rounded-xl shadow-2xl border border-gray-100 py-1.5 min-w-[170px]"
          style={{ top: pageFormatMenuAnchor.top, left: pageFormatMenuAnchor.left, zIndex: 9999 }}
          onMouseDown={(e) => e.stopPropagation()}
        >
          <button
            onClick={() => { setPageFormat('none'); setShowPageBoundary(false); setShowPageFormatMenu(false); }}
            className={`w-full px-3.5 py-1.5 text-left text-xs flex items-center gap-2 hover:bg-gray-50 transition-colors ${pageFormat === 'none' ? 'font-bold text-gray-900' : 'text-gray-700'}`}
          >
            None
          </button>
          {Object.entries(PAGE_FORMATS).map(([id, fmt]) => (
            <button
              key={id}
              onClick={() => { setPageFormat(id); setShowPageBoundary(true); setShowPageFormatMenu(false); }}
              className={`w-full px-3.5 py-1.5 text-left text-xs flex items-center gap-2 hover:bg-gray-50 transition-colors ${pageFormat === id ? 'font-bold text-gray-900' : 'text-gray-700'}`}
            >
              {fmt.label}
            </button>
          ))}
          <div className="border-t border-gray-100 mt-1 pt-1">
            <button
              onClick={() => { window.print(); setShowPageFormatMenu(false); }}
              className="w-full px-3.5 py-1.5 text-left text-xs text-gray-700 hover:bg-gray-50 flex items-center gap-2"
            >
              <i className="fas fa-print text-gray-400 w-3" /> Print / PDF
            </button>
          </div>
        </div>,
        document.body,
      )}

      {/* Auto Layout dropdown — rendered outside toolbar to avoid overflow clipping */}
      {showLayoutMenu && layoutMenuAnchor && createPortal(
        <div
          className="fixed bg-white rounded-xl shadow-2xl border border-gray-100 py-1.5 min-w-[160px]"
          style={{ top: layoutMenuAnchor.top, left: layoutMenuAnchor.left, zIndex: 9999 }}
          onMouseDown={(e) => e.stopPropagation()}
        >
          {[
            { id: 'dagre_tb', label: 'Hierarchical ↓', icon: 'fa-arrow-down' },
            { id: 'dagre_lr', label: 'Hierarchical →', icon: 'fa-arrow-right' },
            { id: 'fcose', label: 'Organic', icon: 'fa-circle-nodes' },
            { id: 'breadthfirst', label: 'Tree', icon: 'fa-sitemap' },
            { id: 'circle', label: 'Circle', icon: 'fa-circle-dot' },
            { id: 'grid', label: 'Grid', icon: 'fa-th' },
          ].map(opt => (
            <button
              key={opt.id}
              onClick={() => runLayout(opt.id)}
              className="w-full px-3.5 py-1.5 text-left text-xs text-gray-700 hover:bg-gray-50 hover:text-gray-900 flex items-center gap-2 transition-colors"
            >
              <i className={`fas ${opt.icon} text-gray-400 w-3`} />
              {opt.label}
            </button>
          ))}
        </div>,
        document.body,
      )}

      {/* ⋯ More dropdown — Page and Export groups */}
      {showMoreMenu && moreMenuAnchor && createPortal(
        <div
          className="fixed bg-white rounded-xl shadow-2xl border border-gray-100 py-2 min-w-[200px]"
          style={{ top: moreMenuAnchor.top, left: moreMenuAnchor.left, zIndex: 9999 }}
          onMouseDown={(e) => e.stopPropagation()}
        >
          {/* Export section */}
          <p className="px-3.5 pt-1 pb-1.5 text-[8px] font-bold text-gray-400 uppercase tracking-wider">Export</p>
          {[
            { label: 'Export as PNG',     icon: 'fa-image',       action: handleExportPng },
            { label: 'Export as SVG',     icon: 'fa-file-export', action: handleExportSvg },
            { label: 'Export as PDF',     icon: 'fa-file-pdf',    action: handleExportPdf },
            { label: 'Export as XML',     icon: 'fa-code',        action: handleExportXml },
          ].map(item => (
            <button key={item.label}
              onClick={() => { item.action(); setShowMoreMenu(false); }}
              className="w-full px-3.5 py-1.5 text-left text-xs text-gray-700 hover:bg-gray-50 flex items-center gap-2.5"
            >
              <i className={`fas ${item.icon} text-gray-400 w-3.5 text-center`} /> {item.label}
            </button>
          ))}
          <div className="border-t border-gray-100 my-1.5" />
          {/* Page section */}
          <p className="px-3.5 pb-1.5 text-[8px] font-bold text-gray-400 uppercase tracking-wider">Page</p>
          <div ref={pageFormatBtnRef}>
            <button
              onClick={() => {
                if (pageFormatBtnRef.current) {
                  const r = pageFormatBtnRef.current.getBoundingClientRect();
                  setPageFormatMenuAnchor({ top: r.top, left: r.right + 4 });
                }
                setShowMoreMenu(false);
                setShowPageFormatMenu(true);
              }}
              className="w-full px-3.5 py-1.5 text-left text-xs text-gray-700 hover:bg-gray-50 flex items-center gap-2.5"
            >
              <i className="fas fa-file-alt text-gray-400 w-3.5 text-center" /> Page Format{pageFormat !== 'none' ? ` (${pageFormat})` : ''}
            </button>
          </div>
          {pageFormat !== 'none' && (
            <button onClick={() => { handleFitPage(); setShowMoreMenu(false); }}
              className="w-full px-3.5 py-1.5 text-left text-xs text-gray-700 hover:bg-gray-50 flex items-center gap-2.5"
            >
              <i className="fas fa-compress-arrows-alt text-gray-400 w-3.5 text-center" /> Fit to Page
            </button>
          )}
          <button onClick={() => { handleSaveVersion(); setShowMoreMenu(false); }}
            className="w-full px-3.5 py-1.5 text-left text-xs text-gray-700 hover:bg-gray-50 flex items-center gap-2.5"
          >
            <i className="fas fa-history text-gray-400 w-3.5 text-center" /> Save Version Snapshot
          </button>
          <button onClick={() => { setShowVersionHistory(v => !v); setShowMoreMenu(false); }}
            className={`w-full px-3.5 py-1.5 text-left text-xs hover:bg-gray-50 flex items-center gap-2.5 ${showVersionHistory ? 'text-blue-600' : 'text-gray-700'}`}
          >
            <i className={`fas fa-clock w-3.5 text-center ${showVersionHistory ? 'text-blue-500' : 'text-gray-400'}`} />
            Version History {versionHistory.length > 0 ? `(${versionHistory.length})` : ''}
          </button>
        </div>,
        document.body,
      )}
    </div>
  );
};

export default CloudMapPage;
