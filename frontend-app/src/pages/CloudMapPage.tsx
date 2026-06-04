/**
 * CloudMap Page
 *
 * Converted from: cloudmap.html
 *
 * Network visualization tool with two modes:
 * 1. Live View - Displays real AWS infrastructure topology
 * 2. Design Studio - Drag-and-drop architecture designer
 *
 * Features:
 * - Interactive graph visualization using Cytoscape.js
 * - VPC filtering and multi-region support
 * - Real-time AWS resource mapping
 * - Export to PNG
 * - Design mode for architecture planning
 */

import React, { useEffect, useRef, useState, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import { useVPC } from '../contexts/VPCContext';
import { useCloudMap } from '../hooks/useCloudMap';
import CloudMapTopbarControls from '../components/CloudMap/CloudMapTopbarControls';
import EnhancedDesignPalette from '../components/CloudMap/EnhancedDesignPalette';
import type { IconData } from '../components/CloudMap/IconPicker';
import {
  NODE_TYPE_DATA,
  TAILWIND_COLORS,
  AWS_ICONS,
  type CloudMapMode,
  type GraphElement,
  type NodeData,
} from '../types/cloudmap';

// Import Cytoscape and extensions
import cytoscape from 'cytoscape';
// @ts-ignore
import cytoscapeDagre from 'cytoscape-dagre';
// @ts-ignore
import cytoscapeEdgehandles from 'cytoscape-edgehandles';

// Register extensions
cytoscape.use(cytoscapeDagre);
cytoscape.use(cytoscapeEdgehandles);

const CloudMapPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const { awsVpcs, awsVpcsLoading, fetchAwsVpcs } = useVPC();
  const { setActions } = useTopbarActions();

  // Get the first selected account ID
  const accountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Mode state
  const [mode, setMode] = useState<CloudMapMode>('live');

  // Cytoscape instance
  const cyRef = useRef<any>(null);
  const ehRef = useRef<any>(null); // Edge handles for design mode
  const containerRef = useRef<HTMLDivElement>(null);

  // UI State
  const [infoPanelOpen, setInfoPanelOpen] = useState(false);
  const [selectedNodeData, setSelectedNodeData] = useState<NodeData | null>(null);
  const [showEmptyState, setShowEmptyState] = useState(false);
  const [messageOverlay, setMessageOverlay] = useState<{
    show: boolean;
    title: string;
    subtitle?: string;
  }>({ show: false, title: '' });

  // Drag & drop state
  const [, setDraggedType] = useState<string | null>(null);

  // Local UI state
  const [selectedVpcValue, setSelectedVpcValue] = useState<string>('');

  // Use custom hook for data management
  const {
    vpcs,
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
  } = useCloudMap({
    accountId,
    mode,
  });

  // Load VPCs when account changes (Live Mode only)
  useEffect(() => {
    if (mode === 'live' && accountId) {
      fetchAwsVpcs(accountId).then(vpcsData => {
        if (vpcsData.length > 0) {
          setSelectedVpcValue('__ALL__');
        }
      }).catch(err => {
        console.error('Error loading VPCs:', err);
      });
    }
  }, [accountId, mode, fetchAwsVpcs]);

  /**
   * Initialize or update Cytoscape graph
   */
  const renderGraph = useCallback((elements: GraphElement[], isEditable: boolean) => {
    // Destroy existing instance
    if (cyRef.current) {
      if (ehRef.current) {
        ehRef.current.destroy();
        ehRef.current = null;
      }
      cyRef.current.destroy();
      cyRef.current = null;
    }

    if (!containerRef.current) return;

    // Deduplicate nodes by ID and filter out edges that reference non-existent nodes
    const seenIds = new Set<string>();
    const deduplicatedElements = elements.filter((el) => {
      const id = el.data.id;
      if (seenIds.has(id)) {
        return false; // Skip duplicate
      }
      seenIds.add(id);
      return true;
    });

    // Check for edges by presence of source/target (not just group property)
    const nodeIds = new Set(
      deduplicatedElements
        .filter((el) => el.group === 'nodes' || (!el.data.source && !el.data.target))
        .map((el) => el.data.id)
    );
    const validElements = deduplicatedElements.filter((el) => {
      const isEdge = el.group === 'edges' || (el.data.source && el.data.target);
      if (isEdge) {
        const edgeData = el.data as { source: string; target: string };
        const hasSource = nodeIds.has(edgeData.source);
        const hasTarget = nodeIds.has(edgeData.target);
        return hasSource && hasTarget;
      }
      return true;
    });

    // Create Cytoscape instance
    cyRef.current = cytoscape({
      container: containerRef.current,
      elements: validElements,
      layout: isEditable
        ? { name: 'preset' }
        : {
          name: 'dagre',
          nodeSep: 80,
          rankDir: 'TB',
        } as any,
      style: getCytoscapeStyle(),
      wheelSensitivity: 0.2,
    });

    // Node tap: Show info
    cyRef.current.on('tap', 'node', (evt: any) => {
      const data = evt.target.data();
      setSelectedNodeData(data);
      setInfoPanelOpen(true);
    });

    // Background tap: Close panel
    cyRef.current.on('tap', (evt: any) => {
      if (evt.target === cyRef.current) {
        setInfoPanelOpen(false);
      }
    });

    // Enable edge handles in Design Mode
    if (isEditable && cytoscapeEdgehandles) {
      ehRef.current = cyRef.current.edgehandles({
        snap: true,
        edgeParams: () => ({ data: { label: '' } }),
      });
    }

    // Auto-resize when container changes
    if (window.ResizeObserver) {
      const ro = new ResizeObserver(() => {
        if (cyRef.current) {
          cyRef.current.resize();
          if (cyRef.current.elements().length > 0) {
            cyRef.current.fit();
          }
        }
      });
      ro.observe(containerRef.current);
    }
  }, []);

  /**
   * Get Cytoscape style configuration
   */
  const getCytoscapeStyle = (): any[] => {
    const baseStyle: any[] = [
      {
        selector: 'node',
        style: {
          'shape': 'rectangle',
          'label': 'data(label)',
          'font-size': '10px',
          'font-weight': '600',
          'text-valign': 'bottom',
          'text-halign': 'center',
          'color': '#374151',
          'text-margin-y': 6,
          'width': 50,
          'height': 50,
          'background-opacity': (node: any) => {
            // Show background for nodes without icons
            const hasIcon = AWS_ICONS[node.data('type')] && !node.data('customIcon');
            return hasIcon ? 0 : 1;
          },
          'background-color': '#f3f4f6',
          'background-image': (node: any) => {
            // Handle custom icons (they don't have background images, rendered via React)
            if (node.data('customIcon')) {
              return 'none';
            }
            // Only show icon if it exists in AWS_ICONS, otherwise no background
            return AWS_ICONS[node.data('type')] || 'none';
          },
          'background-fit': 'contain',
          'background-clip': 'none',
          'border-width': 2,
          'border-color': '#e5e7eb',
          'border-opacity': 0.8,
        },
      },
      {
        selector: 'node:selected',
        style: {
          'border-color': '#667eea',
          'border-width': 3,
        },
      },
      {
        selector: 'edge',
        style: {
          'width': 2,
          'line-color': '#9ca3af',
          'target-arrow-color': '#9ca3af',
          'target-arrow-shape': 'triangle',
          'curve-style': 'bezier',
          'opacity': 0.8,
          'label': 'data(label)',
          'font-size': '9px',
          'color': '#555',
        },
      },
      {
        selector: ':parent',
        style: {
          'text-valign': 'top',
          'text-halign': 'center',
          'font-weight': 'bold',
          'font-size': '11px',
          'color': '#1f2937',
          'background-opacity': 0.1,
          'background-color': '#f8fafc',
          'padding': '45px',
          'border-width': 2,
          'border-style': 'dashed',
          'background-image': 'none',
        },
      },
      // VPC parent node style - blue/indigo
      {
        selector: 'node[type="VPC"]:parent',
        style: {
          'border-color': '#6366f1',
          'border-width': 2,
          'background-color': '#eef2ff',
          'background-opacity': 0.3,
        },
      },
      // Subnet parent node style - light blue/sky
      {
        selector: 'node[type="Subnet"]:parent',
        style: {
          'border-color': '#0ea5e9',
          'border-width': 2,
          'background-color': '#e0f2fe',
          'background-opacity': 0.4,
        },
      },
      // Edge handles styles
      {
        selector: '.eh-handle',
        style: {
          'background-color': '#667eea',
          'width': 12,
          'height': 12,
          'shape': 'ellipse',
          'overlay-opacity': 0,
          'border-width': 12,
          'border-opacity': 0,
        },
      },
      {
        selector: '.eh-hover',
        style: {
          'background-color': '#667eea',
        },
      },
      {
        selector: '.eh-source',
        style: {
          'border-width': 2,
          'border-color': '#667eea',
        },
      },
      {
        selector: '.eh-target',
        style: {
          'border-width': 2,
          'border-color': '#667eea',
        },
      },
      {
        selector: '.eh-preview, .eh-ghost-edge',
        style: {
          'background-color': '#667eea',
          'line-color': '#667eea',
          'target-arrow-color': '#667eea',
          'source-arrow-color': '#667eea',
        },
      },
    ];

    // Add dynamic colors based on node types
    for (const [type, data] of Object.entries(NODE_TYPE_DATA)) {
      baseStyle.push({
        selector: `node[type="${type}"]`,
        style: {
          'background-color': TAILWIND_COLORS[data.color as keyof typeof TAILWIND_COLORS]?.[100] || '#f3f4f6',
          'border-color': TAILWIND_COLORS[data.color as keyof typeof TAILWIND_COLORS]?.[500] || '#6b7280',
          'border-style': data.style || 'solid',
        },
      });
    }

    // Add style for custom icons from icon picker
    baseStyle.push({
      selector: 'node[customIcon]',
      style: {
        'shape': 'roundrectangle',
        'background-color': '#dbeafe',
        'border-color': '#3b82f6',
        'border-width': 2,
        'border-style': 'solid',
        'background-opacity': 1,
        'background-image': 'none',
        'label': 'data(label)',
        'text-valign': 'center',
        'text-halign': 'center',
        'font-size': '11px',
        'font-weight': '600',
        'color': '#1e40af',
        'width': 70,
        'height': 50,
        'text-wrap': 'wrap',
        'text-max-width': '60px',
      },
    });

    return baseStyle;
  };

  /**
   * Update graph when elements change
   */
  useEffect(() => {
    if (graphElements.length > 0) {
      setShowEmptyState(false);
      renderGraph(graphElements, mode === 'design');

      // Trigger resize after render
      setTimeout(() => {
        if (cyRef.current) {
          cyRef.current.resize();
          cyRef.current.fit();
        }
      }, 300);
    } else if (!loadingGraph && mode === 'live') {
      setShowEmptyState(true);
      renderGraph([], false);
    } else if (mode === 'design') {
      renderGraph([], true);
    }
  }, [graphElements, mode, renderGraph, loadingGraph]);

  /**
   * Show/hide message overlay
   */
  useEffect(() => {
    if (loadingVpcs) {
      setMessageOverlay({ show: true, title: 'Loading VPCs...' });
    } else if (loadingGraph) {
      setMessageOverlay({ show: true, title: 'Loading AWS Topology...' });
    } else if (vpcError) {
      setMessageOverlay({ show: true, title: `Error: ${vpcError}` });
    } else if (graphError) {
      setMessageOverlay({ show: true, title: `Error: ${graphError}` });
    } else {
      setMessageOverlay({ show: false, title: '' });
    }
  }, [loadingVpcs, loadingGraph, vpcError, graphError]);

  /**
   * Switch between Live and Design modes
   */
  const handleModeSwitch = useCallback((newMode: CloudMapMode) => {
    setMode(newMode);
    setInfoPanelOpen(false);
    setShowEmptyState(false);

    if (newMode === 'design') {
      // Clear overlays and reset state for design mode
      setMessageOverlay({ show: false, title: '' });
      clearBlueprint();
    }
  }, [clearBlueprint]);

  /**
   * Handle VPC selection change
   */
  const handleVpcChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    setSelectedVpcValue(value);
    if (value === '__ALL__') {
      loadAllGraphs();
    } else {
      loadGraph(value);
    }
  }, [setSelectedVpcValue, loadGraph, loadAllGraphs]);

  /**
   * Handle blueprint selection change
   */
  const handleBlueprintChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    if (value) {
      loadBlueprint(value);
    } else {
      clearBlueprint();
      renderGraph([], true);
    }
  }, [loadBlueprint, clearBlueprint, renderGraph]);

  /**
   * Handle save design
   */
  const handleSaveDesign = useCallback(async () => {
    if (!cyRef.current) return;

    const name = prompt('Name your design:', 'My Cloud Architecture');
    if (!name) return;

    try {
      const graphData = JSON.stringify(cyRef.current.elements().jsons());
      await saveBlueprint(name, graphData);
      alert('Design saved successfully!');
    } catch (error) {
      alert('Save failed: ' + (error instanceof Error ? error.message : 'Unknown error'));
    }
  }, [saveBlueprint]);

  /**
   * Handle clear canvas
   */
  const handleClearCanvas = useCallback(() => {
    if (confirm('Clear everything? Unsaved changes will be lost.')) {
      clearBlueprint();
      if (cyRef.current) {
        cyRef.current.elements().remove();
      }
    }
  }, [clearBlueprint]);

  /**
   * Handle export to PNG
   */
  const handleExportPng = useCallback(() => {
    const vpcSelector = document.getElementById('vpc-selector') as HTMLSelectElement | null;
    exportToPng(cyRef.current, vpcSelector);
  }, [exportToPng]);

  /**
   * Inject CloudMap controls into topbar
   */
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

    // Cleanup: Remove controls when component unmounts
    return () => setActions(null);
  }, [
    mode,
    vpcs,
    selectedVpcValue,
    loadingVpcs,
    blueprints,
    selectedBlueprintId,
    savingBlueprint,
    setActions,
    handleModeSwitch,
    handleVpcChange,
    handleBlueprintChange,
    handleSaveDesign,
    handleClearCanvas,
    handleExportPng,
  ]);

  /**
   * Handle drag start from palette
   */
  const handlePaletteDragStart = (e: React.DragEvent<HTMLDivElement>, type: string) => {
    e.dataTransfer.setData('type', type);
    setDraggedType(type);
  };

  /**
   * Handle drop on canvas
   */
  const handleCanvasDrop = (e: React.DragEvent<HTMLDivElement>) => {
    if (mode !== 'design' || !cyRef.current) return;
    e.preventDefault();

    const type = e.dataTransfer.getData('type');
    const iconDataStr = e.dataTransfer.getData('iconData');

    if (!type && !iconDataStr) return;

    // Get position relative to canvas in graph coordinates
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;

    // Calculate position relative to container
    const containerX = e.clientX - rect.left;
    const containerY = e.clientY - rect.top;

    // Convert from rendered position to model (graph) position
    const zoom = cyRef.current.zoom();
    const pan = cyRef.current.pan();

    const pos = {
      x: (containerX - pan.x) / zoom,
      y: (containerY - pan.y) / zoom
    };

    // Handle custom icon from icon picker
    if (iconDataStr && type === 'custom-icon') {
      try {
        const iconData: IconData = JSON.parse(iconDataStr);
        cyRef.current.add({
          group: 'nodes',
          data: {
            id: 'node-' + Date.now(),
            label: iconData.name,
            type: 'custom-icon',
            iconId: iconData.id,
            customIcon: true,
          },
          position: { x: pos.x, y: pos.y },
        });
      } catch (error) {
        console.error('Error parsing icon data:', error);
      }
    } else if (type) {
      // Handle AWS resource type
      cyRef.current.add({
        group: 'nodes',
        data: {
          id: 'node-' + Date.now(),
          label: type,
          type: type,
        },
        position: { x: pos.x, y: pos.y },
      });
    }

    setDraggedType(null);
  };

  /**
   * Handle icon selection (click to add at center)
   */
  const handleIconSelect = (icon: IconData) => {
    if (mode !== 'design' || !cyRef.current) return;

    // Add at center of viewport
    const extent = cyRef.current.extent();
    const centerX = (extent.x1 + extent.x2) / 2;
    const centerY = (extent.y1 + extent.y2) / 2;

    cyRef.current.add({
      group: 'nodes',
      data: {
        id: 'node-' + Date.now(),
        label: icon.name,
        type: 'custom-icon',
        iconId: icon.id,
        customIcon: true,
      },
      position: { x: centerX, y: centerY },
    });
  };

  /**
   * Render info panel
   */
  const renderInfoPanel = () => {
    if (!selectedNodeData) return null;

    const typeInfo = NODE_TYPE_DATA[selectedNodeData.type] || { icon: 'cube', color: 'gray' };
    const nodeTypeColors: Record<string, string> = {
      'VPC': 'from-green-500 to-emerald-600',
      'EC2 Instance': 'from-orange-500 to-red-600',
      'RDS Instance': 'from-blue-500 to-indigo-600',
    };
    const gradientClass = nodeTypeColors[selectedNodeData.type] || 'from-gray-500 to-gray-600';

    return (
      <div className={`info-panel ${infoPanelOpen ? 'open' : ''}`}>
        <button onClick={() => setInfoPanelOpen(false)} className="close-btn">
          <i className="fas fa-times" />
        </button>
        <div className="info-content">
          <div className="slide-in">
            <div className={`w-12 h-12 bg-gradient-to-br ${gradientClass} rounded-xl flex items-center justify-center mb-4`}>
              <i className={`fas fa-${typeInfo.icon} text-white text-lg`} />
            </div>
            <h2 className="text-2xl font-bold text-gray-800 mb-2">{selectedNodeData.label}</h2>
            <div className="vpc-badge mb-4 inline-block">{selectedNodeData.type}</div>
            <p className="text-xs text-gray-400 font-mono break-all mb-4">{selectedNodeData.id}</p>
            <div className="space-y-3">
              {Object.entries(selectedNodeData)
                .filter(
                  ([key]) =>
                    !['id', 'label', 'parent', 'type', 'source', 'target'].includes(key)
                )
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

  return (
    <div className="flex flex-col h-full max-h-full relative overflow-hidden">
      {/* Main Content */}
      <div className="flex flex-1 min-h-0 relative overflow-hidden">
        {/* Enhanced Design Palette (Design Mode Only) */}
        <EnhancedDesignPalette
          mode={mode}
          onDragStart={handlePaletteDragStart}
          onIconSelect={handleIconSelect}
        />

        {/* Canvas */}
        <div
          className="canvas-wrapper"
          onDragOver={(e) => e.preventDefault()}
          onDrop={handleCanvasDrop}
        >
          <div ref={containerRef} className="cytoscape-container" />

          {/* Message Overlay */}
          {messageOverlay.show && (
            <div className="absolute inset-0 flex items-center justify-center z-20 bg-white/80 backdrop-blur-sm">
              <div className="text-center p-8 rounded-2xl bg-white shadow-xl border border-gray-100 max-w-sm">
                <div className="relative mx-auto mb-6 w-20 h-20">
                  <div className="absolute inset-0 rounded-full border-4 border-gray-200"></div>
                  <div className="absolute inset-0 rounded-full border-4 border-indigo-500 border-t-transparent animate-spin"></div>
                  <div className="absolute inset-3 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center">
                    <i className="fas fa-cloud text-white text-xl"></i>
                  </div>
                </div>
                <h3 className="text-xl font-bold text-gray-800 mb-2">{messageOverlay.title}</h3>
                {messageOverlay.subtitle && (
                  <p className="text-gray-500 text-sm">{messageOverlay.subtitle}</p>
                )}
                <div className="mt-4 flex justify-center gap-1">
                  <div className="w-2 h-2 bg-indigo-500 rounded-full animate-bounce" style={{ animationDelay: '0ms' }}></div>
                  <div className="w-2 h-2 bg-indigo-500 rounded-full animate-bounce" style={{ animationDelay: '150ms' }}></div>
                  <div className="w-2 h-2 bg-indigo-500 rounded-full animate-bounce" style={{ animationDelay: '300ms' }}></div>
                </div>
              </div>
            </div>
          )}

          {/* Empty State */}
          {showEmptyState && (
            <div className="empty-state">
              <div className="empty-state-content">
                <div className="empty-state-icon">
                  <i className="fas fa-project-diagram text-white text-3xl" />
                </div>
                <h3 className="text-2xl font-bold text-gray-800 mb-2">No Resources Found</h3>
                <p className="text-gray-600 mb-4">
                  Your cloud infrastructure visualization will appear here once resources are detected.
                </p>
                <div className="empty-state-tips">
                  <h4 className="font-semibold text-blue-800 mb-2 flex items-center gap-2">
                    <i className="fas fa-info-circle" /> What to check:
                  </h4>
                  <ul className="text-sm text-blue-700 space-y-1">
                    <li>
                      <i className="fas fa-check-circle text-blue-500" /> Verify your AWS account has resources
                    </li>
                    <li>
                      <i className="fas fa-check-circle text-blue-500" /> Check if credentials are properly configured
                    </li>
                    <li>
                      <i className="fas fa-check-circle text-blue-500" /> Try selecting a different VPC or region
                    </li>
                    <li>
                      <i className="fas fa-check-circle text-blue-500" /> Refresh the page to reload data
                    </li>
                  </ul>
                </div>
              </div>
            </div>
          )}

          {/* Toolbar */}
          <div className="toolbar">
            <button
              className="toolbar-button"
              onClick={() => cyRef.current?.fit()}
              title="Fit"
            >
              <i className="fas fa-expand-arrows-alt" />
            </button>
            <button
              className="toolbar-button"
              onClick={() => cyRef.current?.center()}
              title="Center"
            >
              <i className="fas fa-crosshairs" />
            </button>
            <button
              className="toolbar-button"
              onClick={() => cyRef.current && cyRef.current.zoom(cyRef.current.zoom() * 1.2)}
              title="Zoom In"
            >
              <i className="fas fa-plus" />
            </button>
            <button
              className="toolbar-button"
              onClick={() => cyRef.current && cyRef.current.zoom(cyRef.current.zoom() * 0.8)}
              title="Zoom Out"
            >
              <i className="fas fa-minus" />
            </button>
          </div>

        </div>

        {/* Info Panel */}
        {renderInfoPanel()}
      </div>
    </div>
  );
};

export default CloudMapPage;
