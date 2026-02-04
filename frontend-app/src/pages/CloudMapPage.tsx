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
import { useTopbar } from '../contexts/TopbarContext';
import { useVPC } from '../contexts/VPCContext';
import { useCloudMap } from '../hooks/useCloudMap';
import CloudMapTopbarControls from '../components/CloudMap/CloudMapTopbarControls';
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
  const { setActions } = useTopbar();

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
          setSelectedVpcValue('ALL');
          // Load graph handled separately
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

    // Create Cytoscape instance
    cyRef.current = cytoscape({
      container: containerRef.current,
      elements: elements,
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
          'background-opacity': 0,
          'background-image': (node: any) => AWS_ICONS[node.data('type')] || '/icons/default-icon.png',
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
          'background-opacity': 0.05,
          'background-color': '#f8fafc',
          'padding': '45px',
          'border-width': 2,
          'border-style': 'dashed',
          'background-image': (node: any) => AWS_ICONS[node.data('type')] || '',
          'background-fit': 'none',
          'background-width': '25px',
          'background-height': '25px',
          'background-position-x': '15px',
          'background-position-y': '15px',
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
      clearBlueprint();
    }
  }, [clearBlueprint]);

  /**
   * Handle VPC selection change
   */
  const handleVpcChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    setSelectedVpcValue(value);
    loadGraph(value);
  }, [setSelectedVpcValue, loadGraph]);

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
      const graphData = JSON.stringify(cyRef.current.json().elements);
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
    if (!type) return;

    // Get position relative to canvas
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;

    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const pos = cyRef.current.renderer().projectIntoViewport(x, y);

    // Add node to graph
    cyRef.current.add({
      group: 'nodes',
      data: {
        id: 'node-' + Date.now(),
        label: type,
        type: type,
      },
      position: { x: pos.x, y: pos.y },
    });

    setDraggedType(null);
  };

  /**
   * Render legend
   */
  const renderLegend = () => {
    const categories = [...new Set(Object.values(NODE_TYPE_DATA).map(d => d.category))];

    return (
      <div className="legend">
        <h4 className="text-sm font-bold text-gray-800 mb-3">Legend</h4>
        {categories.sort().map((category) => (
          <div key={category}>
            <h5 className="text-xs font-semibold text-gray-600 mt-3 mb-1">{category}</h5>
            {Object.entries(NODE_TYPE_DATA)
              .filter(([, data]) => data.category === category)
              .map(([type, data]) => {
                const bgColor = TAILWIND_COLORS[data.color as keyof typeof TAILWIND_COLORS]?.[100] || '#f3f4f6';
                const borderColor = TAILWIND_COLORS[data.color as keyof typeof TAILWIND_COLORS]?.[500] || '#6b7280';
                const iconColor = TAILWIND_COLORS[data.color as keyof typeof TAILWIND_COLORS]?.[600] || '#4b5563';

                return (
                  <div key={type} className="legend-item">
                    <div
                      className="legend-icon"
                      style={{
                        backgroundColor: bgColor,
                        border: `1px solid ${borderColor}`,
                      }}
                    >
                      <i className={`fas fa-${data.icon} text-xs`} style={{ color: iconColor }} />
                    </div>
                    <span className="text-gray-700">{type}</span>
                  </div>
                );
              })}
          </div>
        ))}
      </div>
    );
  };

  /**
   * Render design palette
   */
  const renderDesignPalette = () => {
    return (
      <div className={`design-palette ${mode === 'design' ? '' : 'hidden'}`}>
        <h3 className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-4">Toolbox</h3>
        <div className="text-xs text-gray-500 mb-3 bg-blue-50 p-2 rounded border border-blue-100 flex items-start gap-2">
          <i className="fas fa-info-circle text-blue-500 mt-0.5" />
          <span>Drag icons to canvas. Click & drag from a node's handle to connect.</span>
        </div>
        <div className="grid grid-cols-2 gap-2">
          {Object.entries(NODE_TYPE_DATA).map(([type, data]) => {
            const color = TAILWIND_COLORS[data.color as keyof typeof TAILWIND_COLORS]?.[500] || '#6b7280';

            return (
              <div
                key={type}
                className="palette-item"
                draggable
                onDragStart={(e) => handlePaletteDragStart(e, type)}
              >
                <div
                  className="w-8 h-8 rounded-full flex items-center justify-center mb-1"
                  style={{ backgroundColor: `${color}20`, color: color }}
                >
                  <i className={`fas fa-${data.icon}`} />
                </div>
                <span className="text-[10px] font-medium text-gray-600 leading-tight">{type}</span>
              </div>
            );
          })}
        </div>
      </div>
    );
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
    <div className="flex flex-col h-full relative overflow-hidden">
      {/* Main Content */}
      <div className="flex flex-1 relative overflow-hidden">
        {/* Design Palette (Design Mode Only) */}
        {renderDesignPalette()}

        {/* Canvas */}
        <div
          className="canvas-wrapper"
          onDragOver={(e) => e.preventDefault()}
          onDrop={handleCanvasDrop}
        >
          <div ref={containerRef} className="cytoscape-container" />

          {/* Message Overlay */}
          {messageOverlay.show && (
            <div className="message-overlay">
              <div className="message-box">
                <div className="spinner" />
                <h3 className="font-bold text-gray-800 mb-2">{messageOverlay.title}</h3>
                {messageOverlay.subtitle && (
                  <p className="text-sm text-gray-500">{messageOverlay.subtitle}</p>
                )}
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

          {/* Legend (Design Mode Only) */}
          {mode === 'design' && renderLegend()}
        </div>

        {/* Info Panel */}
        {renderInfoPanel()}
      </div>
    </div>
  );
};

export default CloudMapPage;
