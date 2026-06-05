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
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import { useVPC } from '../contexts/VPCContext';
import { useCloudMap } from '../hooks/useCloudMap';
import CloudMapTopbarControls from '../components/CloudMap/CloudMapTopbarControls';
import EnhancedDesignPalette from '../components/CloudMap/EnhancedDesignPalette';
import type { GenericShape } from '../components/CloudMap/EnhancedDesignPalette';
import type { IconData } from '../components/CloudMap/IconPicker';
import DesignPropertiesPanel from '../components/CloudMap/DesignPropertiesPanel';
import type { ElementProperties } from '../components/CloudMap/DesignPropertiesPanel';
import DesignContextMenu from '../components/CloudMap/DesignContextMenu';
import DesignSaveModal from '../components/CloudMap/DesignSaveModal';
import Tooltip from '../components/CloudMap/Tooltip';
import {
  ALL_VPCS_VALUE,
  NODE_TYPE_DATA,
  TAILWIND_COLORS,
  AWS_ICONS,
  type CloudMapMode,
  type GraphElement,
  type NodeData,
} from '../types/cloudmap';

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

// ─── Helpers ────────────────────────────────────────────────────────────────

/** Apply stored style data to a Cytoscape element (node or edge). */
const applyElementStyle = (el: any) => {
  if (el.isEdge()) {
    const c = el.data('fillColor');
    const s = el.data('edgeStyle');
    const a = el.data('arrowType');
    if (c) { el.style('line-color', c); el.style('target-arrow-color', c); el.style('source-arrow-color', c); }
    if (s) el.style('curve-style', s);
    if (a) el.style('target-arrow-shape', a);
  } else {
    const f = el.data('fillColor');
    const b = el.data('borderColor');
    const sh = el.data('nodeShape');
    const fs = el.data('fontSize');
    const type = el.data('type');
    const customIcon = el.data('customIcon');

    // AWS icon nodes: transparent background + icon image
    if (!customIcon && type && AWS_ICONS[type]) {
      el.style('background-image', AWS_ICONS[type]);
      el.style('background-opacity', 0);
      el.style('background-fit', 'contain');
    } else {
      // Non-icon nodes: solid fill
      el.style('background-image', 'none');
      if (f === 'transparent' || f === 'none') {
        el.style('background-opacity', 0);
      } else if (f) {
        el.style('background-color', f);
        el.style('background-opacity', 1);
      }
    }

    if (b === 'none') {
      el.style('border-width', 0);
    } else if (b) {
      el.style('border-color', b);
    }
    if (sh) el.style('shape', sh);
    if (fs) el.style('font-size', `${fs}px`);
    const nw = el.data('nodeWidth');
    const nh = el.data('nodeHeight');
    if (nw && nh) el.style({ width: nw, height: nh });
  }
};

// ─── Component ──────────────────────────────────────────────────────────────

const CloudMapPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const { awsVpcs, awsVpcsLoading, fetchAwsVpcs } = useVPC();
  const { setActions } = useTopbarActions();

  const accountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Mode
  const [mode, setMode] = useState<CloudMapMode>('live');

  // Cytoscape refs
  const cyRef = useRef<any>(null);
  const ehRef = useRef<any>(null);
  const containerRef = useRef<HTMLDivElement>(null);

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
  const cdndRef = useRef<any>(null);
  const snapToGridRef = useRef(false);
  const drawEdgeModeRef = useRef(false);

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
    undoStack.current.push(JSON.stringify(cyRef.current.json()));
    if (undoStack.current.length > 50) undoStack.current.shift();
    redoStack.current = [];
  }, []);

  useEffect(() => { takeSnapshotRef.current = takeSnapshot; }, [takeSnapshot]);
  useEffect(() => { snapToGridRef.current = snapToGrid; }, [snapToGrid]);
  useEffect(() => { drawEdgeModeRef.current = drawEdgeMode; }, [drawEdgeMode]);

  const undo = useCallback(() => {
    if (!cyRef.current || undoStack.current.length === 0) return;
    redoStack.current.push(JSON.stringify(cyRef.current.json()));
    cyRef.current.json(JSON.parse(undoStack.current.pop()!));
    cyRef.current.elements().forEach(applyElementStyle);
    cyRef.current.style().update();
    setSelectedElement(null);
  }, []);

  const redo = useCallback(() => {
    if (!cyRef.current || redoStack.current.length === 0) return;
    undoStack.current.push(JSON.stringify(cyRef.current.json()));
    cyRef.current.json(JSON.parse(redoStack.current.pop()!));
    cyRef.current.elements().forEach(applyElementStyle);
    cyRef.current.style().update();
    setSelectedElement(null);
  }, []);

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

  const getCytoscapeStyle = (): any[] => {
    const base: any[] = [
      {
        selector: 'node',
        style: {
          shape: 'rectangle',
          label: 'data(label)',
          'font-size': '10px',
          'font-weight': '600',
          'text-valign': 'bottom',
          'text-halign': 'center',
          color: '#374151',
          'text-margin-y': 6,
          width: 50,
          height: 50,
          'background-opacity': 1,
          'background-color': '#f3f4f6',
          'background-image': 'none',
          'background-fit': 'contain',
          'background-clip': 'none',
          'border-width': 2,
          'border-color': '#e5e7eb',
          'border-opacity': 0.8,
          'text-wrap': 'wrap',
          'text-max-width': '70px',
        },
      },
      {
        selector: 'node:selected',
        style: { 'border-color': '#667eea', 'border-width': 3 },
      },
      {
        selector: 'edge',
        style: {
          width: 2,
          'line-color': '#9ca3af',
          'target-arrow-color': '#9ca3af',
          'target-arrow-shape': 'triangle',
          'curve-style': 'bezier',
          opacity: 0.8,
          label: 'data(label)',
          'font-size': '9px',
          color: '#555',
        },
      },
      {
        selector: 'edge:selected',
        style: { 'line-color': '#667eea', 'target-arrow-color': '#667eea', width: 3 },
      },
      {
        selector: ':parent',
        style: {
          'text-valign': 'top',
          'text-halign': 'center',
          'font-weight': 'bold',
          'font-size': '11px',
          color: '#1f2937',
          'background-opacity': 0.1,
          'background-color': '#f8fafc',
          padding: '45px',
          'border-width': 2,
          'border-style': 'dashed',
          'background-image': 'none',
        },
      },
      {
        selector: 'node[isContainer]', style: {
          'background-opacity': 0.08, 'border-width': 2, 'border-style': 'dashed',
          'text-valign': 'top', 'text-halign': 'left', 'font-size': '11px', 'font-weight': 'bold',
          'padding': '40px', 'min-width': '200px', 'min-height': '120px',
          'background-image': 'none',
        }
      },
      { selector: 'node[containerType="vpc"]', style: { 'border-color': '#6366f1', 'background-color': '#eef2ff' } },
      { selector: 'node[containerType="subnet"]', style: { 'border-color': '#0ea5e9', 'background-color': '#e0f2fe' } },
      { selector: 'node[containerType="az"]', style: { 'border-color': '#f97316', 'background-color': '#fff7ed' } },
      { selector: 'node[containerType="sg"]', style: { 'border-color': '#ef4444', 'background-color': '#fef2f2' } },
      { selector: 'node[containerType="region"]', style: { 'border-color': '#8b5cf6', 'background-color': '#faf5ff' } },
      { selector: 'node[type="VPC"]:parent', style: { 'border-color': '#6366f1', 'background-color': '#eef2ff', 'background-opacity': 0.3 } },
      { selector: 'node[type="Subnet"]:parent', style: { 'border-color': '#0ea5e9', 'background-color': '#e0f2fe', 'background-opacity': 0.4 } },
      // Shape-type nodes (generic shapes from Shapes tab) — use inline styles applied by applyElementStyle
      {
        selector: 'node[type="shape"]',
        style: {
          'background-color': '#dbeafe',
          'border-color': '#3b82f6',
          'border-width': 2,
          'background-image': 'none',
          'background-opacity': 1,
          'text-valign': 'center',
          'text-halign': 'center',
          width: 80,
          height: 50,
        },
      },
      // Custom icons
      {
        selector: 'node[customIcon]',
        style: {
          shape: 'roundrectangle',
          'background-color': '#dbeafe',
          'border-color': '#3b82f6',
          'border-width': 2,
          'background-opacity': 1,
          'background-image': 'none',
          label: 'data(label)',
          'text-valign': 'center',
          'text-halign': 'center',
          'font-size': '11px',
          'font-weight': '600',
          color: '#1e40af',
          width: 70,
          height: 50,
          'text-wrap': 'wrap',
          'text-max-width': '60px',
        },
      },
      // Edge handles — large enough to click easily
      {
        selector: '.eh-handle', style: {
          'background-color': '#667eea',
          'border-color': '#ffffff',
          'border-width': 2,
          'border-opacity': 1,
          width: 20,
          height: 20,
          shape: 'ellipse',
          'overlay-opacity': 0,
          'z-index': 9999,
        }
      },
      { selector: '.eh-hover', style: { 'background-color': '#667eea', 'border-width': 3, 'border-color': '#4338ca' } },
      { selector: '.eh-source', style: { 'border-width': 3, 'border-color': '#667eea' } },
      { selector: '.eh-target', style: { 'border-width': 3, 'border-color': '#667eea', 'border-opacity': 1 } },
      {
        selector: '.eh-preview, .eh-ghost-edge', style: {
          'background-color': '#667eea', 'line-color': '#667eea',
          'target-arrow-color': '#667eea', 'source-arrow-color': '#667eea',
          'line-style': 'dashed',
        }
      },
    ];

    // Type-based colors
    for (const [type, data] of Object.entries(NODE_TYPE_DATA)) {
      base.push({
        selector: `node[type="${type}"]`,
        style: {
          'background-color': TAILWIND_COLORS[data.color as keyof typeof TAILWIND_COLORS]?.[100] || '#f3f4f6',
          'border-color': TAILWIND_COLORS[data.color as keyof typeof TAILWIND_COLORS]?.[500] || '#6b7280',
          'border-style': data.style || 'solid',
        },
      });
    }

    return base;
  };

  // ── Read selected element props from Cytoscape ────────────────────────────

  const readElementProps = useCallback((el: any): ElementProperties => {
    const isEdge = el.isEdge();
    return {
      id: el.id(),
      elementType: isEdge ? 'edge' : 'node',
      label: el.data('label') || '',
      fillColor: el.data('fillColor') || (isEdge ? '#9ca3af' : '#f3f4f6'),
      borderColor: el.data('borderColor') || '#e5e7eb',
      nodeShape: el.data('nodeShape') || 'rectangle',
      edgeStyle: el.data('edgeStyle') || 'bezier',
      arrowType: el.data('arrowType') || 'triangle',
      fontSize: el.data('fontSize') || 11,
      width: Math.round(isEdge ? 0 : (el.width() || 50)),
      height: Math.round(isEdge ? 0 : (el.height() || 50)),
    };
  }, []);

  // ── Render graph ──────────────────────────────────────────────────────────

  const renderGraph = useCallback((elements: GraphElement[], isEditable: boolean) => {
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
        : { name: 'dagre', nodeSep: 60, rankSep: 100, rankDir: 'TB', fit: false } as any,
      style: getCytoscapeStyle(),
      wheelSensitivity: 0.2,
    });

    const cy = cyRef.current;

    // Restore stored styles after load (must set inline bypasses AND update stylesheet)
    cy.elements().forEach(applyElementStyle);
    cy.style().update();

    // Multi-VPC grid layout (live mode only) — dagre stacks disconnected VPCs
    // horizontally; rearrange into a readable grid instead
    if (!isEditable) {
      const vpcRoots = cy.nodes('[type="VPC"]');
      if (vpcRoots.length > 1) {
        const COLS  = vpcRoots.length > 4 ? 3 : 2;
        const GAP_X = 120, GAP_Y = 120;
        let maxW = 0, maxH = 0;
        vpcRoots.forEach((n: any) => {
          const bb = n.boundingBox({});
          maxW = Math.max(maxW, bb.w);
          maxH = Math.max(maxH, bb.h);
        });
        vpcRoots.forEach((n: any, i: number) => {
          const col = i % COLS, row = Math.floor(i / COLS);
          const bb = n.boundingBox({});
          const curCx = (bb.x1 + bb.x2) / 2, curCy = (bb.y1 + bb.y2) / 2;
          const targetCx = col * (maxW + GAP_X) + maxW / 2;
          const targetCy = row * (maxH + GAP_Y) + maxH / 2;
          n.descendants().union(n).shift({ x: targetCx - curCx, y: targetCy - curCy });
        });
      }
      cy.fit(undefined, 50);
      if (cy.zoom() < 0.55) { cy.zoom(0.55); cy.center(); }
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

    // Snap to grid on drag end
    cy.on('dragfree', 'node', (evt: any) => {
      if (!snapToGridRef.current) return;
      const node = evt.target;
      const pos = node.position();
      const G = 24;
      node.position({ x: Math.round(pos.x / G) * G, y: Math.round(pos.y / G) * G });
    });

    // Zoom level indicator
    cy.on('zoom', () => {
      setZoomLevel(Math.round(cy.zoom() * 100));
    });

    // Node tap (live mode: info panel; design mode: select for properties)
    cy.on('tap', 'node', (evt: any) => {
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

    // Select → update properties panel
    cy.on('select', 'node, edge', (evt: any) => {
      if (isEditable) setSelectedElement(readElementProps(evt.target));
    });

    // Unselect → clear properties if nothing selected
    cy.on('unselect', () => {
      if (isEditable && cy.$(':selected').length === 0) setSelectedElement(null);
    });

    // Track selected node count for alignment toolbar
    cy.on('select unselect', () => {
      setSelectedCount(cy.$(':selected').filter('node').length);
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

    // Resize observer
    if (window.ResizeObserver && containerRef.current) {
      const ro = new ResizeObserver(() => {
        if (cyRef.current) {
          cyRef.current.resize();
          if (cyRef.current.elements().length > 0) cyRef.current.fit();
        }
      });
      ro.observe(containerRef.current);
    }
  }, [readElementProps]);

  // ── Graph effect ──────────────────────────────────────────────────────────

  useEffect(() => {
    if (graphElements.length > 0) {
      setShowEmptyState(false);
      renderGraph(graphElements, mode === 'design');
      setTimeout(() => { if (cyRef.current) { cyRef.current.resize(); cyRef.current.fit(); } }, 300);
    } else if (!loadingGraph && mode === 'live') {
      setShowEmptyState(true);
      renderGraph([], false);
    } else if (mode === 'design') {
      renderGraph([], true);
    }
  }, [graphElements, mode, renderGraph, loadingGraph]);

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

      if (e.key === 'Delete' || e.key === 'Backspace') {
        if (!cyRef.current) return;
        const sel = cyRef.current.$(':selected');
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

      if (e.key === 'Escape') {
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

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [mode, undo, redo]);

  // Auto-focus label input when editor appears
  useEffect(() => {
    if (labelEditor) setTimeout(() => labelInputRef.current?.focus(), 30);
  }, [labelEditor]);

  // ── Property change handlers ───────────────────────────────────────────────

  const handleLabelChange = useCallback((id: string, label: string) => {
    if (!cyRef.current) return;
    takeSnapshotRef.current();
    cyRef.current.getElementById(id).data('label', label);
    setSelectedElement(prev => prev?.id === id ? { ...prev, label } : prev);
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

  const handleSizeChange = useCallback((id: string, width: number, height: number) => {
    if (!cyRef.current) return;
    takeSnapshotRef.current();
    const el = cyRef.current.getElementById(id);
    el.style({ width, height });
    el.data('nodeWidth', width);
    el.data('nodeHeight', height);
    setSelectedElement(prev => prev?.id === id ? { ...prev, width, height } : prev);
  }, []);

  const handleDeleteSelected = useCallback(() => {
    if (!cyRef.current) return;
    const sel = cyRef.current.$(':selected');
    if (sel.length === 0 && selectedElement) {
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
  }, [selectedElement]);

  // ── Inline label commit ────────────────────────────────────────────────────

  const commitLabelEdit = useCallback(() => {
    if (!labelEditor || !cyRef.current) { setLabelEditor(null); return; }
    const el = cyRef.current.getElementById(labelEditor.elementId);
    if (el.length > 0) {
      takeSnapshotRef.current();
      el.data('label', labelEditor.value);
      setSelectedElement(prev => prev?.id === labelEditor.elementId ? { ...prev, label: labelEditor.value } : prev);
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

  // ── Export SVG ────────────────────────────────────────────────────────────

  const handleExportSvg = useCallback(() => {
    if (!cyRef.current) return;
    const svgStr = cyRef.current.svg({ full: true, bg: '#f8fafc' });
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
    const opts: Record<string, any> = {
      dagre_tb: { name: 'dagre', rankDir: 'TB', nodeSep: 60, rankSep: 80, animate: true },
      dagre_lr: { name: 'dagre', rankDir: 'LR', nodeSep: 60, rankSep: 80, animate: true },
      fcose: { name: 'fcose', animate: true, randomize: false, nodeSeparation: 75, idealEdgeLength: 100 },
      breadthfirst: { name: 'breadthfirst', directed: true, animate: true, padding: 20 },
      circle: { name: 'breadthfirst', circular: true, animate: true, padding: 20 },
      grid: { name: 'grid', animate: true, padding: 20 },
    };
    cyRef.current.layout(opts[name] || opts.dagre_tb).run();
    setShowLayoutMenu(false);
  }, []);

  // Close layout menu on outside click
  useEffect(() => {
    if (!showLayoutMenu) return;
    const close = () => setShowLayoutMenu(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [showLayoutMenu]);

  // ── Draw-edge mode ────────────────────────────────────────────────────────

  const toggleDrawEdgeMode = useCallback(() => {
    if (!ehRef.current) return;
    setDrawEdgeMode(prev => {
      const next = !prev;
      if (next) ehRef.current.enableDrawMode();
      else ehRef.current.disableDrawMode();
      return next;
    });
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

    const type = e.dataTransfer.getData('type');
    const iconDataStr = e.dataTransfer.getData('iconData');
    const shapeDataStr = e.dataTransfer.getData('shapeData');

    if (!type && !iconDataStr) return;

    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;

    const zoom = cyRef.current.zoom();
    const pan = cyRef.current.pan();
    const pos = {
      x: (e.clientX - rect.left - pan.x) / zoom,
      y: (e.clientY - rect.top - pan.y) / zoom,
    };

    takeSnapshotRef.current();

    if (type === 'generic-shape' && shapeDataStr) {
      const shape: GenericShape = JSON.parse(shapeDataStr);
      const nodeData: any = {
        id: 'node-' + Date.now(),
        label: shape.defaultLabel ?? shape.label,
        type: 'shape',
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

    } else if (type === 'custom-icon' && iconDataStr) {
      const iconData: IconData = JSON.parse(iconDataStr);
      const newNode = cyRef.current.add({
        group: 'nodes',
        data: { id: 'node-' + Date.now(), label: iconData.name, type: 'custom-icon', iconId: iconData.id, customIcon: true },
        position: pos,
      });
      reparentIfInsideContainer(newNode);
      applyElementStyle(newNode);

    } else if (type) {
      const newNode = cyRef.current.add({
        group: 'nodes',
        data: { id: 'node-' + Date.now(), label: type, type },
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

  const handleIconSelect = (icon: IconData) => {
    if (mode !== 'design' || !cyRef.current) return;
    takeSnapshotRef.current();
    const pos = getCanvasCenter();
    const newNode = cyRef.current.add({
      group: 'nodes',
      data: { id: 'node-' + Date.now(), label: icon.name, type: 'custom-icon', iconId: icon.id, customIcon: true },
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
      data: { id: 'node-' + Date.now(), label: type, type },
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
      'RDS Instance': 'from-blue-500 to-indigo-600',
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

  return (
    <div className="flex flex-col h-full max-h-full relative overflow-hidden">
      <div className="flex flex-1 min-h-0 relative overflow-hidden">

        {/* ── Left Palette (design mode) ── */}
        <EnhancedDesignPalette
          mode={mode}
          onDragStart={handlePaletteDragStart}
          onShapeDragStart={handleShapeDragStart}
          onIconSelect={handleIconSelect}
          onShapeSelect={handleShapeSelect}
          onAwsTypeSelect={handleAwsTypeSelect}
        />

        {/* ── Canvas ── */}
        <div
          className="canvas-wrapper"
          onDragOver={(e) => e.preventDefault()}
          onDrop={handleCanvasDrop}
        >
          <div
            ref={containerRef}
            className={`cytoscape-container${mode === 'design' && showGrid ? ' design-grid' : ''}`}
          />

          {/* Inline label editor */}
          {mode === 'design' && labelEditor && (
            <div
              className="absolute z-50 pointer-events-auto"
              style={{ left: labelEditor.x, top: labelEditor.y, transform: 'translate(-50%, -50%)' }}
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

          {/* Message Overlay */}
          {messageOverlay.show && (
            <div className="absolute inset-0 flex items-center justify-center z-20 bg-white/80 backdrop-blur-sm">
              <div className="text-center p-8 rounded-2xl bg-white shadow-xl border border-gray-100 max-w-sm">
                <div className="relative mx-auto mb-6 w-20 h-20">
                  <div className="absolute inset-0 rounded-full border-4 border-gray-200" />
                  <div className="absolute inset-0 rounded-full border-4 border-indigo-500 border-t-transparent animate-spin" />
                  <div className="absolute inset-3 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center">
                    <i className="fas fa-cloud text-white text-xl" />
                  </div>
                </div>
                <h3 className="text-xl font-bold text-gray-800 mb-2">{messageOverlay.title}</h3>
                <div className="mt-4 flex justify-center gap-1">
                  {[0, 150, 300].map(d => (
                    <div key={d} className="w-2 h-2 bg-indigo-500 rounded-full animate-bounce" style={{ animationDelay: `${d}ms` }} />
                  ))}
                </div>
              </div>
            </div>
          )}

          {/* Empty State */}
          {showEmptyState && (
            <div className="empty-state">
              <div className="empty-state-content">
                <div className="empty-state-icon"><i className="fas fa-project-diagram text-white text-3xl" /></div>
                <h3 className="text-2xl font-bold text-gray-800 mb-2">No Resources Found</h3>
                <p className="text-gray-600 mb-4">Your cloud infrastructure visualization will appear here once resources are detected.</p>
                <div className="empty-state-tips">
                  <h4 className="font-semibold text-blue-800 mb-2 flex items-center gap-2"><i className="fas fa-info-circle" /> What to check:</h4>
                  <ul className="text-sm text-blue-700 space-y-1">
                    {['Verify your AWS account has resources', 'Check if credentials are properly configured', 'Try selecting a different VPC or region', 'Refresh the page to reload data'].map(t => (
                      <li key={t}><i className="fas fa-check-circle text-blue-500" /> {t}</li>
                    ))}
                  </ul>
                </div>
              </div>
            </div>
          )}

          {/* Toolbar */}
          <div className="toolbar">
            {mode === 'design' && (
              <>
                <Tooltip text="Undo (Ctrl+Z)">
                  <button
                    className="toolbar-button"
                    onClick={undo}
                    disabled={undoStack.current.length === 0}
                  >
                    <i className="fas fa-undo" />
                  </button>
                </Tooltip>
                <Tooltip text="Redo (Ctrl+Y)">
                  <button
                    className="toolbar-button"
                    onClick={redo}
                    disabled={redoStack.current.length === 0}
                  >
                    <i className="fas fa-redo" />
                  </button>
                </Tooltip>
                <div className="toolbar-separator" />
                <Tooltip text={showGrid ? 'Hide Grid' : 'Show Grid'}>
                  <button
                    className={`toolbar-button ${showGrid ? 'toolbar-button-active' : ''}`}
                    onClick={() => setShowGrid(g => !g)}
                  >
                    <i className="fas fa-border-all" />
                  </button>
                </Tooltip>
                <Tooltip text={drawEdgeMode ? 'Stop Drawing (Esc)' : 'Draw Connection'}>
                  <button
                    className={`toolbar-button ${drawEdgeMode ? 'toolbar-button-active' : ''}`}
                    onClick={toggleDrawEdgeMode}
                  >
                    <i className="fas fa-bezier-curve" />
                  </button>
                </Tooltip>
                <div className="toolbar-separator" />
                <Tooltip text="Export as PNG">
                  <button className="toolbar-button" onClick={handleExportPng}>
                    <i className="fas fa-image" />
                  </button>
                </Tooltip>
                <Tooltip text="Export as SVG">
                  <button className="toolbar-button" onClick={handleExportSvg}>
                    <i className="fas fa-file-export" />
                  </button>
                </Tooltip>
                <div className="toolbar-separator" />

                {/* Layout menu */}
                <div className="relative">
                  <Tooltip text="Auto Layout">
                    <button
                      className="toolbar-button"
                      onClick={() => setShowLayoutMenu(v => !v)}
                    >
                      <i className="fas fa-sitemap" />
                    </button>
                  </Tooltip>
                  {showLayoutMenu && (
                    <div className="absolute top-full left-0 mt-1 bg-white rounded-xl shadow-2xl border border-gray-100 py-1.5 z-[200] min-w-[160px]">
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
                          className="w-full px-3.5 py-1.5 text-left text-xs text-gray-700 hover:bg-indigo-50 hover:text-indigo-700 flex items-center gap-2 transition-colors"
                        >
                          <i className={`fas ${opt.icon} text-gray-400 w-3`} />
                          {opt.label}
                        </button>
                      ))}
                    </div>
                  )}
                </div>

                <Tooltip text={snapToGrid ? 'Snap to Grid: On' : 'Snap to Grid: Off'}>
                  <button
                    className={`toolbar-button ${snapToGrid ? 'toolbar-button-active' : ''}`}
                    onClick={() => setSnapToGrid(v => !v)}
                  >
                    <i className="fas fa-magnet" />
                  </button>
                </Tooltip>

                {/* Alignment toolbar — shown when 2+ nodes selected */}
                {selectedCount >= 2 && (
                  <>
                    <div className="toolbar-separator" />
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
              </>
            )}
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
            <Tooltip text="Zoom In">
              <button className="toolbar-button" onClick={() => cyRef.current?.zoom(cyRef.current.zoom() * 1.2)}>
                <i className="fas fa-plus" />
              </button>
            </Tooltip>
            <Tooltip text="Zoom Out">
              <button className="toolbar-button" onClick={() => cyRef.current?.zoom(cyRef.current.zoom() * 0.8)}>
                <i className="fas fa-minus" />
              </button>
            </Tooltip>
            <div className="toolbar-zoom-badge">{zoomLevel}%</div>
          </div>
        </div>

        {/* ── Right: Properties Panel (design) | Info Panel (live) ── */}
        {mode === 'design' ? (
          <DesignPropertiesPanel
            selected={selectedElement}
            onLabelChange={handleLabelChange}
            onFillColorChange={handleFillColorChange}
            onBorderColorChange={handleBorderColorChange}
            onShapeChange={handleShapeChange}
            onEdgeStyleChange={handleEdgeStyleChange}
            onArrowTypeChange={handleArrowTypeChange}
            onFontSizeChange={handleFontSizeChange}
            onSizeChange={handleSizeChange}
            onDelete={handleDeleteSelected}
            onDuplicate={handleDuplicateSelected}
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
          onEditLabel={() => openLabelEditorFor(contextMenu.elementId)}
          onDuplicate={() => {
            if (!cyRef.current) return;
            const el = cyRef.current.getElementById(contextMenu.elementId);
            if (el.length > 0) { el.select(); handleDuplicateSelected(); }
          }}
          onDelete={() => {
            if (!cyRef.current) return;
            takeSnapshotRef.current();
            cyRef.current.getElementById(contextMenu.elementId).remove();
            setSelectedElement(null);
          }}
          onBringToFront={() => bringToFront(contextMenu.elementId)}
          onSendToBack={() => sendToBack(contextMenu.elementId)}
          onClose={() => setContextMenu(null)}
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
    </div>
  );
};

export default CloudMapPage;
