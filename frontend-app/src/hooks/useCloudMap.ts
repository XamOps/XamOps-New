/**
 * useCloudMap Hook
 *
 * Custom hook for managing CloudMap state and data fetching
 */

import { useState, useEffect, useCallback } from 'react';
import type { VPC, GraphElement, Blueprint } from '../types/cloudmap';

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
      const response = await fetch(`/api/xamops/cloudmap/vpcs?accountId=${accountId}`, {
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setVpcs(data);

      // Auto-select first VPC if available
      if (data.length > 0) {
        const firstVpc = data[0];
        const vpcValue = `${firstVpc.id}|${firstVpc.region}`;
        setSelectedVpcValue(vpcValue);
        // Auto-load graph for first VPC
        await loadGraph(vpcValue);
      } else {
        // No VPCs found, load global resources
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

      const response = await fetch(
        `/api/xamops/cloudmap/graph?accountId=${accountId}&vpcId=${vpcId || ''}&region=${region || ''}`,
        {
          credentials: 'include',
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
      const response = await fetch('/api/xamops/design/list', {
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setBlueprints(data);
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
      const response = await fetch(`/api/xamops/design/${id}`, {
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const blueprint = await response.json();
      setSelectedBlueprintId(blueprint.id);

      if (blueprint.graphData) {
        const elements = JSON.parse(blueprint.graphData);
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

      const response = await fetch('/api/xamops/design/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
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
