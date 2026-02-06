/**
 * GCP CloudMap Page
 *
 * Converted from: gcp_cloudmap.html
 *
 * Network visualization tool for GCP infrastructure with Cytoscape.js
 *
 * Features:
 * - Interactive graph visualization using Cytoscape.js with Dagre layout
 * - VPC filtering and selection
 * - Real-time GCP resource mapping
 * - Export to PNG
 * - Info panel with resource details
 * - Responsive toolbar with zoom/fit controls
 * - Professional glass-morphism UI
 *
 * Backend API endpoints:
 * - GET /api/xamops/gcp/cloudmap/vpcs?accountId={accountId} - Fetch VPCs
 * - GET /api/xamops/gcp/cloudmap/graph?accountId={accountId}&vpcId={vpcId} - Fetch graph data
 */

import React, { useEffect, useRef, useState, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useVPC } from '../contexts/VPCContext';
import GcpCloudMapTopbarControls from '../components/CloudMap/GcpCloudMapTopbarControls';

// Import Cytoscape and extensions
import cytoscape from 'cytoscape';
// @ts-ignore
import cytoscapeDagre from 'cytoscape-dagre';

// Register extensions
cytoscape.use(cytoscapeDagre);

// Types
interface NodeData {
  id: string;
  label: string;
  type: string;
  parent?: string;
  [key: string]: any;
}

interface GraphElement {
  group: 'nodes' | 'edges';
  data: NodeData;
}

// GCP Icons mapping
const GCP_ICONS: Record<string, string> = {
  'VPC': '/icons/gcp-vpc.png',
  'Instance': '/icons/gcp-compute-engine.png',
  'Subnet': '/icons/gcp-vpc.png',
  'Firewall': '/icons/gcp-firewall.png',
  'Region': '/icons/gcp-icon.png',
};

const GcpCloudMapPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const { gcpVpcs, gcpVpcsLoading, fetchGcpVpcs } = useVPC();
  const { setActions } = useTopbar();

  // Get the first selected account ID
  const accountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Cytoscape instance
  const cyRef = useRef<any>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // UI State
  const [selectedVpcValue, setSelectedVpcValue] = useState<string>('');
  const [, setLoadingGraph] = useState<boolean>(false);
  const [, setGraphError] = useState<string | null>(null);
  const [infoPanelOpen, setInfoPanelOpen] = useState(false);
  const [selectedNodeData, setSelectedNodeData] = useState<NodeData | null>(null);
  const [messageOverlay, setMessageOverlay] = useState<{
    show: boolean;
    title: string;
    subtitle?: string;
    type?: 'loading' | 'error' | 'info';
  }>({ show: true, title: 'Ready to Visualize', subtitle: 'Please select a VPC to begin mapping your infrastructure.', type: 'info' });



  /**
   * Render Cytoscape graph
   */
  const renderGraph = useCallback((graphData: GraphElement[]) => {
    if (!containerRef.current) return;

    // Deduplicate nodes by ID and filter out edges that reference non-existent nodes
    const seenIds = new Set<string>();
    const deduplicatedData = graphData.filter((el) => {
      const id = el.data.id;
      if (seenIds.has(id)) {
        return false; // Skip duplicate
      }
      seenIds.add(id);
      return true;
    });

    const nodeIds = new Set(
      deduplicatedData
        .filter((el) => el.group === 'nodes' || (!el.data.source && !el.data.target))
        .map((el) => el.data.id)
    );
    const validElements = deduplicatedData.filter((el) => {
      const isEdge = el.group === 'edges' || (el.data.source && el.data.target);
      if (isEdge) {
        const edgeData = el.data as unknown as { source: string; target: string };
        return nodeIds.has(edgeData.source) && nodeIds.has(edgeData.target);
      }
      return true;
    });

    try {
      cyRef.current = cytoscape({
        container: containerRef.current,
        elements: validElements,
        minZoom: 0.3,
        maxZoom: 2.5,
        wheelSensitivity: 0.2,
        layout: {
          name: 'dagre',
          // @ts-ignore - dagre-specific options not in base LayoutOptions type
          nodeSep: 60,
          edgeSep: 15,
          rankSep: 80,
          rankDir: 'TB',
          align: 'UL',
          spacingFactor: 1.2,
        } as any,
        style: [
          {
            selector: 'node',
            style: {
              'shape': 'rectangle',
              'label': 'data(label)',
              'font-size': 11,
              'font-weight': 600,
              'text-valign': 'bottom',
              'text-halign': 'center',
              'color': '#374151',
              'text-margin-y': 8 as any,
              'width': 60,
              'height': 60,
              'background-opacity': 0,
              'background-image': (node: any) => GCP_ICONS[node.data('type')] || '/icons/gcp-icon.png',
              'background-fit': 'contain',
              'background-clip': 'none',
              'border-width': 2,
              'border-color': '#e5e7eb',
              'border-opacity': 0.8,
              'transition-property': 'background-color, border-color, width, height',
              'transition-duration': 300
            }
          },
          {
            selector: 'edge',
            style: {
              'width': 2,
              'line-color': '#9ca3af',
              'target-arrow-color': '#9ca3af',
              'target-arrow-shape': 'triangle',
              'curve-style': 'bezier',
              'opacity': 0.8
            }
          },
          {
            selector: ':parent',
            style: {
              'text-valign': 'top',
              'text-halign': 'center',
              'font-weight': 700,
              'color': '#1f2937',
              'font-size': 16,
              'background-opacity': 1,
              'background-image': 'none',
              'padding': '20px'
            }
          },
          // VNet parent node style - indigo (same as AWS VPC)
          {
            selector: 'node[type="VPC"]:parent',
            style: {
              'border-color': '#6366f1',
              'border-width': 2,
              'background-color': '#eef2ff',
              'background-opacity': 0.3,
              'border-style': 'dashed'
            }
          },
          {
            selector: 'node[type="VPC"]',
            style: {
              'background-color': '#eef2ff',
              'border-width': 2,
              'border-color': '#6366f1',
              'border-style': 'dashed'
            }
          },
          {
            selector: 'node[type="Region"]',
            style: {
              'background-color': '#f0f9ff',
              'border-width': 2,
              'border-color': '#60a5fa',
              'border-style': 'dashed'
            }
          },
          // Subnet parent node style - light blue/sky (same as AWS Subnet)
          {
            selector: 'node[type="Subnet"]:parent',
            style: {
              'border-color': '#0ea5e9',
              'border-width': 2,
              'background-color': '#e0f2fe',
              'background-opacity': 0.4,
              'border-style': 'solid'
            }
          },
          {
            selector: 'node[type="Subnet"]',
            style: {
              'background-color': '#e0f2fe',
              'border-width': 2,
              'border-color': '#0ea5e9',
              'border-style': 'solid'
            }
          },
          {
            selector: 'node[type="Firewall"]',
            style: {
              'width': 40,
              'height': 40,
              'background-color': '#fef3c7',
              'border-color': '#f59e0b'
            }
          },
        ]
      });

      // Node tap: Show info panel
      cyRef.current.on('tap', 'node', (evt: any) => {
        const data = evt.target.data();
        setSelectedNodeData(data);
        setInfoPanelOpen(true);
      });

      // Background tap: Close info panel
      cyRef.current.on('tap', (evt: any) => {
        if (evt.target === cyRef.current) {
          setInfoPanelOpen(false);
        }
      });

      // Hover effects
      cyRef.current.on('mouseover', 'node', (event: any) => {
        event.target.style({
          'border-width': 3,
          'border-color': '#6366f1'
        });
        if (containerRef.current) {
          containerRef.current.style.cursor = 'pointer';
        }
      });

      cyRef.current.on('mouseout', 'node', (event: any) => {
        event.target.removeStyle('border-width');
        event.target.removeStyle('border-color');
        if (containerRef.current) {
          containerRef.current.style.cursor = 'default';
        }
      });

      // Fit to view with padding and ensure minimum zoom
      cyRef.current.fit(undefined, 50);
      if (cyRef.current.zoom() < 0.5) {
        cyRef.current.zoom(0.5);
        cyRef.current.center();
      }
    } catch (error) {
      console.error('Error rendering graph:', error);
    }
  }, []);

  /**
   * Load graph data for selected VPC
   */
  const loadGraph = useCallback(async (vpcId: string) => {
    if (!accountId) {
      console.error('No account ID found for loading graph.');
      return;
    }

    // Destroy existing graph
    if (cyRef.current) {
      cyRef.current.destroy();
      cyRef.current = null;
    }
    setInfoPanelOpen(false);

    const url = `/api/xamops/gcp/cloudmap/graph?accountId=${accountId}&vpcId=${vpcId || ''}`;

    setMessageOverlay({
      show: true,
      title: 'Loading Infrastructure',
      subtitle: 'Analyzing your GCP topology...',
      type: 'loading'
    });
    setLoadingGraph(true);
    setGraphError(null);

    try {
      const response = await fetch(url, { credentials: 'include' });
      if (!response.ok) {
        throw new Error('Failed to fetch graph data');
      }

      const graphData = await response.json();
      setMessageOverlay({ show: false, title: '' });
      renderGraph(graphData);
    } catch (error) {
      console.error('Error fetching graph data:', error);
      setGraphError('Could not load graph data. Please try again.');
      setMessageOverlay({
        show: true,
        title: 'Load Error',
        subtitle: 'Could not load graph data. Please try again.',
        type: 'error'
      });
    } finally {
      setLoadingGraph(false);
    }
  }, [accountId, renderGraph]);

  /**
   * Load VPCs when account changes
   */
  const loadVpcs = useCallback(async () => {
    if (!accountId) return;

    try {
      const vpcsData = await fetchGcpVpcs(accountId);
      // Auto-select first VPC if available
      if (vpcsData.length > 0) {
        setSelectedVpcValue(vpcsData[0].id);
        loadGraph(vpcsData[0].id);
      }
    } catch (error) {
      console.error('Error loading VPCs:', error);
    }
  }, [accountId, fetchGcpVpcs, loadGraph]);

  /**
   * Initialize component
   */
  useEffect(() => {
    if (accountId) {
      loadVpcs();
    }
  }, [accountId, loadVpcs]);

  /**
   * Get icon for node type
   */
  const getIconForType = (type: string): string => {
    const iconMap: Record<string, string> = {
      'VPC': 'cloud',
      'Instance': 'server',
      'Subnet': 'network-wired',
      'Firewall': 'shield-alt',
      'Region': 'layer-group'
    };
    return iconMap[type] || 'cube';
  };



  /**
   * Handle VPC selection change
   */
  const handleVpcChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    setSelectedVpcValue(value);
    loadGraph(value);
  }, [setSelectedVpcValue, loadGraph]);

  /**
   * Export graph to PNG
   */
  const handleExportPng = useCallback(() => {
    if (cyRef.current) {
      const png64 = cyRef.current.png({ output: 'base64uri', bg: '#fff', full: true });
      const link = document.createElement('a');
      link.download = `gcp-cloudmap-${selectedVpcValue || 'global'}-${new Date().toISOString().split('T')[0]}.png`;
      link.href = png64;
      link.click();
    }
  }, [selectedVpcValue]);

  /**
   * Inject GCP CloudMap controls into topbar
   */
  useEffect(() => {
    setActions(
      <GcpCloudMapTopbarControls
        vpcs={gcpVpcs}
        selectedVpcValue={selectedVpcValue}
        onVpcChange={handleVpcChange}
        loadingVpcs={gcpVpcsLoading}
        onExportPng={handleExportPng}
        cyRef={cyRef}
      />
    );

    // Cleanup: Remove controls when component unmounts
    return () => setActions(null);
  }, [gcpVpcs, selectedVpcValue, gcpVpcsLoading, setActions, handleVpcChange, handleExportPng]);

  /**
   * Render info panel
   */
  const renderInfoPanel = () => {
    if (!selectedNodeData) return null;

    const nodeTypeColors: Record<string, string> = {
      'VPC': 'from-indigo-500 to-purple-600',
      'Instance': 'from-orange-500 to-red-600',
      'Subnet': 'from-sky-500 to-blue-600',
      'Firewall': 'from-yellow-500 to-orange-600'
    };
    const gradientClass = nodeTypeColors[selectedNodeData.type] || 'from-gray-500 to-gray-600';

    return (
      <div
        id="info-panel"
        className={`gcp-cloudmap-info-panel ${infoPanelOpen ? 'open' : ''}`}
      >
        <button
          onClick={() => setInfoPanelOpen(false)}
          className="absolute top-6 right-6 text-gray-400 hover:text-gray-600 transition-colors"
        >
          <i className="fas fa-times text-xl"></i>
        </button>
        <div className="mt-4 slide-in">
          <div className={`w-12 h-12 bg-gradient-to-br ${gradientClass} rounded-xl flex items-center justify-center mb-4`}>
            <i className={`fas fa-${getIconForType(selectedNodeData.type)} text-white text-lg`}></i>
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">{selectedNodeData.label}</h2>
          <div className="vpc-badge mb-4">{selectedNodeData.type}</div>
          <p className="text-sm text-gray-500 font-mono mb-6 bg-gray-50 p-2 rounded">{selectedNodeData.id}</p>
          <div className="space-y-3">
            {Object.entries(selectedNodeData)
              .filter(([key]) => !['id', 'label', 'parent', 'type'].includes(key))
              .map(([key, value]) => (
                <div key={key} className="network-node">
                  <div className="flex justify-between items-center">
                    <span className="font-semibold text-gray-700">{key.replace(/([A-Z])/g, ' $1').trim()}</span>
                    <span className="font-mono text-gray-900 text-sm">{String(value)}</span>
                  </div>
                </div>
              ))}
          </div>
        </div>
      </div>
    );
  };

  return (
    <div className="flex flex-col h-full bg-gray-100">
      {/* Main Content */}
      <div className="flex-1 relative" id="cloudmap-container">
        <div id="graph-container" className="w-full h-full p-4">
          {/* Message Overlay */}
          {messageOverlay.show && (
            <div className="absolute inset-0 flex items-center justify-center z-20 bg-white/80 backdrop-blur-sm">
              <div className="text-center p-8 rounded-2xl bg-white shadow-xl border border-gray-100 max-w-sm">
                {messageOverlay.type === 'loading' && (
                  <div className="relative mx-auto mb-6 w-20 h-20">
                    <div className="absolute inset-0 rounded-full border-4 border-gray-200"></div>
                    <div className="absolute inset-0 rounded-full border-4 border-indigo-500 border-t-transparent animate-spin"></div>
                    <div className="absolute inset-3 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center">
                      <i className="fas fa-cloud text-white text-xl"></i>
                    </div>
                  </div>
                )}
                {messageOverlay.type === 'error' && (
                  <div className="w-20 h-20 bg-gradient-to-br from-red-500 to-pink-600 rounded-full flex items-center justify-center mx-auto mb-6 shadow-lg">
                    <i className="fas fa-exclamation-triangle text-white text-2xl"></i>
                  </div>
                )}
                {messageOverlay.type === 'info' && (
                  <div className="w-20 h-20 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-full flex items-center justify-center mx-auto mb-6 shadow-lg">
                    <i className="fas fa-network-wired text-white text-2xl"></i>
                  </div>
                )}
                <h3 className="text-xl font-bold text-gray-800 mb-2">{messageOverlay.title}</h3>
                {messageOverlay.subtitle && (
                  <p className="text-gray-500 text-sm">{messageOverlay.subtitle}</p>
                )}
                {messageOverlay.type === 'loading' && (
                  <div className="mt-4 flex justify-center gap-1">
                    <div className="w-2 h-2 bg-indigo-500 rounded-full animate-bounce" style={{ animationDelay: '0ms' }}></div>
                    <div className="w-2 h-2 bg-indigo-500 rounded-full animate-bounce" style={{ animationDelay: '150ms' }}></div>
                    <div className="w-2 h-2 bg-indigo-500 rounded-full animate-bounce" style={{ animationDelay: '300ms' }}></div>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Cytoscape Container */}
          <div ref={containerRef} id="cy" className="gcp-cloudmap-cy"></div>

          {/* Toolbar */}
          <div className="toolbar">
            <div className="flex items-center space-x-2">
              <button
                className="toolbar-button"
                onClick={() => cyRef.current && cyRef.current.fit()}
                title="Fit to view"
              >
                <i className="fas fa-expand-arrows-alt"></i>
              </button>
              <button
                className="toolbar-button"
                onClick={() => cyRef.current && cyRef.current.center()}
                title="Center view"
              >
                <i className="fas fa-crosshairs"></i>
              </button>
              <button
                className="toolbar-button"
                onClick={() => cyRef.current && cyRef.current.zoom(cyRef.current.zoom() * 1.2)}
                title="Zoom in"
              >
                <i className="fas fa-search-plus"></i>
              </button>
              <button
                className="toolbar-button"
                onClick={() => cyRef.current && cyRef.current.zoom(cyRef.current.zoom() * 0.8)}
                title="Zoom out"
              >
                <i className="fas fa-search-minus"></i>
              </button>
            </div>
          </div>

          {/* Legend */}
          <div className="legend">
            <h4 className="text-sm font-bold text-gray-800 mb-3">Legend</h4>
            <div className="legend-item">
              <div className="legend-icon bg-indigo-100 border-2 border-indigo-500" style={{ borderStyle: 'dashed' }}>
                <i className="fas fa-cloud text-indigo-600 text-xs"></i>
              </div>
              <span className="text-gray-700">VNet</span>
            </div>
            <div className="legend-item">
              <div className="legend-icon bg-blue-100 border border-blue-400" style={{ borderStyle: 'dashed' }}>
                <i className="fas fa-layer-group text-blue-600 text-xs"></i>
              </div>
              <span className="text-gray-700">Region/Zone</span>
            </div>
            <div className="legend-item">
              <div className="legend-icon bg-sky-100 border-2 border-sky-500">
                <i className="fas fa-network-wired text-sky-600 text-xs"></i>
              </div>
              <span className="text-gray-700">Subnet</span>
            </div>
            <div className="legend-item">
              <div className="legend-icon bg-orange-100">
                <i className="fas fa-server text-orange-600 text-xs"></i>
              </div>
              <span className="text-gray-700">Resources</span>
            </div>
          </div>
        </div>

        {/* Info Panel */}
        {renderInfoPanel()}
      </div>
    </div>
  );
};

export default GcpCloudMapPage;
