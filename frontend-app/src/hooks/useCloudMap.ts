/*
 * NOTE: these calls go through apiFetch, not bare fetch.
 *
 * They used bare fetch, which silently cost three things. Demo mode is
 * implemented inside apiFetch, and demoRouter.ts already maps
 * /api/xamops/cloudmap/vpcs and /graph to real fixture data — so a demo session
 * bypassed its own mocks and rendered an empty canvas. The tenant and CSRF
 * headers apiFetch injects were also absent, as was its request deadline and
 * session-expiry handling.
 */
/**
 * useCloudMap Hook
 *
 * Custom hook for managing CloudMap state and data fetching
 */

import { useState, useEffect, useCallback } from 'react';
import { ALL_VPCS_VALUE } from '../types/cloudmap';
import type { VPC, GraphElement, Blueprint } from '../types/cloudmap';
import { apiFetch } from '../utils/apiClient';

interface UseCloudMapOptions {
  accountId: string | null;
  mode: 'live' | 'design';
}

interface UseCloudMapReturn {
  // VPC Data
  vpcs: VPC[];
  selectedVpcValue: string;
  setSelectedVpcValue: (value: string) => void;
  loadingVpcs: boolean;
  vpcError: string | null;

  // Graph Data
  graphElements: GraphElement[];
  loadingGraph: boolean;
  graphError: string | null;
  loadGraph: (vpcValue: string) => Promise<void>;
  loadAllGraphs: (vpcsData?: VPC[]) => Promise<void>;

  // Design Mode
  blueprints: Blueprint[];
  selectedBlueprintId: string | null;
  setSelectedBlueprintId: (id: string | null) => void;
  loadingBlueprints: boolean;
  savingBlueprint: boolean;
  saveBlueprint: (name: string, graphData: string) => Promise<void>;
  loadBlueprint: (id: string) => Promise<void>;
  clearBlueprint: () => void;

  // Export
  exportToPng: (cy: any, vpcSelector: HTMLSelectElement | null) => void;
}

export const useCloudMap = ({ accountId, mode }: UseCloudMapOptions): UseCloudMapReturn => {
  // VPC State
  const [vpcs, setVpcs] = useState<VPC[]>([]);
  const [selectedVpcValue, setSelectedVpcValue] = useState<string>('');
  const [loadingVpcs, setLoadingVpcs] = useState(false);
  const [vpcError, setVpcError] = useState<string | null>(null);

  // Graph State
  const [graphElements, setGraphElements] = useState<GraphElement[]>([]);
  const [loadingGraph, setLoadingGraph] = useState(false);
  const [graphError, setGraphError] = useState<string | null>(null);

  // Design Mode State
  const [blueprints, setBlueprints] = useState<Blueprint[]>([]);
  const [selectedBlueprintId, setSelectedBlueprintId] = useState<string | null>(null);
  const [loadingBlueprints, setLoadingBlueprints] = useState(false);
  const [savingBlueprint, setSavingBlueprint] = useState(false);

  // Load VPCs when account changes (Live Mode only)
  useEffect(() => {
    if (mode === 'live' && accountId) {
      loadVpcs();
    }
  }, [accountId, mode]);

  // Load Blueprints when in Design Mode
  useEffect(() => {
    if (mode === 'design') {
      loadBlueprintsList();
    }
  }, [mode]);

  /**
   * Load and merge graph data for all VPCs into a single canvas
   */
  const loadAllGraphs = useCallback(async (vpcsData?: VPC[]) => {
    if (!accountId) {
      setGraphError('No account selected');
      return;
    }

    const list = vpcsData ?? vpcs;
    if (list.length === 0) {
      setGraphElements([]);
      return;
    }

    setLoadingGraph(true);
    setGraphError(null);

    try {
      const results = await Promise.all(
        list.map(async (vpc) => {
          try {
            const response = await apiFetch(
              `/api/xamops/cloudmap/graph?${new URLSearchParams({ accountId: accountId!, vpcId: vpc.id, region: vpc.region }).toString()}`,
              {}
            );
            if (!response.ok) return [] as GraphElement[];
            const data = await response.json();
            return Array.isArray(data) ? (data as GraphElement[]) : [];
          } catch {
            return [] as GraphElement[];
          }
        })
      );

      const merged: GraphElement[] = [];

      list.forEach((vpc, i) => {
        const elements = results[i];
        const prefix = `${vpc.id}::`;

        // Synthetic compound root node for this VPC
        merged.push({
          group: 'nodes',
          data: {
            id: `${prefix}vpc-root`,
            label: `${vpc.name} (${vpc.region})`,
            type: 'VPC',
          },
        });

        elements.forEach((el) => {
          const isEdge = el.group === 'edges' || (!!(el.data as any).source && !!(el.data as any).target);
          if (isEdge) {
            merged.push({
              ...el,
              data: {
                ...el.data,
                id: `${prefix}${el.data.id}`,
                source: `${prefix}${(el.data as any).source}`,
                target: `${prefix}${(el.data as any).target}`,
              },
            });
          } else {
            const nd = el.data as any;
            merged.push({
              ...el,
              data: {
                ...nd,
                id: `${prefix}${nd.id}`,
                parent: nd.parent ? `${prefix}${nd.parent}` : `${prefix}vpc-root`,
              },
            });
          }
        });
      });

      setGraphElements(merged);
    } catch (error) {
      console.error('Error loading all VPC graphs:', error);
      setGraphError(error instanceof Error ? error.message : 'Failed to load all VPC graphs');
      setGraphElements([]);
    } finally {
      setLoadingGraph(false);
    }
  }, [accountId, vpcs]);

  /**
   * Load VPCs for the selected account
   */
  const loadVpcs = async () => {
    if (!accountId) {
      setVpcError('No account selected');
      return;
    }

    setLoadingVpcs(true);
    setVpcError(null);

    try {
      const response = await apiFetch(`/api/xamops/cloudmap/vpcs?accountId=${accountId}`, {
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setVpcs(data);

      // Default: load all VPCs merged on one canvas
      if (data.length > 0) {
        setSelectedVpcValue(ALL_VPCS_VALUE);
        await loadAllGraphs(data);
      } else {
        setSelectedVpcValue('');
        await loadGraph('');
      }
    } catch (error) {
      console.error('Error loading VPCs:', error);
      setVpcError(error instanceof Error ? error.message : 'Failed to load VPCs');
      setVpcs([]);
    } finally {
      setLoadingVpcs(false);
    }
  };

  /**
   * Load graph data for a selected VPC
   */
  const loadGraph = useCallback(async (vpcValue: string) => {
    if (!accountId) {
      setGraphError('No account selected');
      return;
    }

    setLoadingGraph(true);
    setGraphError(null);

    try {
      const [vpcId, region] = (vpcValue || '|').split('|');

      const response = await apiFetch(
        `/api/xamops/cloudmap/graph?accountId=${accountId}&vpcId=${vpcId || ''}&region=${region || ''}`,
        {
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setGraphElements(data || []);
    } catch (error) {
      console.error('Error loading graph:', error);
      setGraphError(error instanceof Error ? error.message : 'Failed to load graph');
      setGraphElements([]);
    } finally {
      setLoadingGraph(false);
    }
  }, [accountId]);

  /**
   * Load list of design blueprints
   */
  const loadBlueprintsList = async () => {
    setLoadingBlueprints(true);

    try {
      const response = await apiFetch('/api/xamops/design/list', {
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setBlueprints(Array.isArray(data) ? data : []);
    } catch (error) {
      console.error('Error loading blueprints:', error);
      setBlueprints([]);
    } finally {
      setLoadingBlueprints(false);
    }
  };

  /**
   * Load a specific design blueprint
   */
  const loadBlueprint = useCallback(async (id: string) => {
    setLoadingGraph(true);
    setGraphError(null);

    try {
      const response = await apiFetch(`/api/xamops/design/${id}`, {
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const blueprint = await response.json();
      setSelectedBlueprintId(blueprint.id);

      if (blueprint.graphData) {
        const parsed = JSON.parse(blueprint.graphData);
        // Handle legacy object format {nodes:[...], edges:[...]} from cy.json().elements
        const elements = Array.isArray(parsed)
          ? parsed
          : [...(parsed.nodes || []), ...(parsed.edges || [])];
        setGraphElements(elements);
      } else {
        setGraphElements([]);
      }
    } catch (error) {
      console.error('Error loading blueprint:', error);
      setGraphError(error instanceof Error ? error.message : 'Failed to load blueprint');
      setGraphElements([]);
    } finally {
      setLoadingGraph(false);
    }
  }, []);

  /**
   * Save a design blueprint
   */
  const saveBlueprint = useCallback(async (name: string, graphData: string) => {
    setSavingBlueprint(true);

    try {
      const payload = {
        id: selectedBlueprintId,
        name: name,
        graphData: graphData,
      };

      const response = await apiFetch('/api/xamops/design/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setSelectedBlueprintId(data.id);

      // Reload blueprints list
      await loadBlueprintsList();

      return data;
    } catch (error) {
      console.error('Error saving blueprint:', error);
      throw error;
    } finally {
      setSavingBlueprint(false);
    }
  }, [selectedBlueprintId]);

  /**
   * Clear the current blueprint
   */
  const clearBlueprint = useCallback(() => {
    setSelectedBlueprintId(null);
    setGraphElements([]);
  }, []);

  /**
   * Export graph to PNG
   */
  const exportToPng = useCallback((cy: any, vpcSelector: HTMLSelectElement | null) => {
    if (!cy) {
      alert('No graph to export');
      return;
    }

    try {
      const selectedVpc = (vpcSelector && vpcSelector.options[vpcSelector.selectedIndex]?.text) || 'cloudmap';
      const filename = `aws-cloudmap-${selectedVpc.replace(/[^a-z0-9]/gi, '_').toLowerCase()}-${new Date().toISOString().split('T')[0]}.png`;

      const png = cy.png({
        output: 'blob',
        bg: '#f8fafc',
        full: true,
        scale: 2,
      });

      const link = document.createElement('a');
      link.href = URL.createObjectURL(png);
      link.download = filename;
      link.click();
    } catch (error) {
      console.error('Export failed:', error);
      alert('Failed to export graph: ' + (error instanceof Error ? error.message : 'Unknown error'));
    }
  }, []);

  return {
    // VPC Data
    vpcs,
    selectedVpcValue,
    setSelectedVpcValue,
    loadingVpcs,
    vpcError,

    // Graph Data
    graphElements,
    loadingGraph,
    graphError,
    loadGraph,
    loadAllGraphs,

    // Design Mode
    blueprints,
    selectedBlueprintId,
    setSelectedBlueprintId,
    loadingBlueprints,
    savingBlueprint,
    saveBlueprint,
    loadBlueprint,
    clearBlueprint,

    // Export
    exportToPng,
  };
};
