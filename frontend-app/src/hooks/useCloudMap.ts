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
import { useQueryClient } from '@tanstack/react-query';
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
  /** `force` bypasses the cached result; the Refresh control uses it. */
  loadGraph: (vpcValue: string, force?: boolean) => Promise<void>;
  loadAllGraphs: (vpcsData?: VPC[], force?: boolean) => Promise<void>;

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

/*
 * Cache keys for the shared react-query cache.
 *
 * The graph is keyed by account and selection because those are the only inputs to
 * the request. The QueryClient lives at App level, so entries outlive this hook and
 * survive navigating away from Cloud Map and back — which is the whole point: a
 * single VPC still costs a full account scan on the backend, so re-opening the page
 * used to pay for the entire topology again.
 */
const vpcsKey = (accountId?: string) => ['cloudmap', 'vpcs', accountId ?? ''];
const graphKey = (accountId: string | undefined, selection: string) =>
  ['cloudmap', 'graph', accountId ?? '', selection];

export const useCloudMap = ({ accountId, mode }: UseCloudMapOptions): UseCloudMapReturn => {
  /*
   * Used as a store rather than through useQuery. The loaders here are imperative —
   * they are called from event handlers, and loadAllGraphs deliberately sequences a
   * cache-warming request before the rest — so converting them to declarative
   * queries would rewrite that control flow. Reading and writing the same cache
   * gets the persistence without touching the fetch ordering.
   */
  const queryClient = useQueryClient();

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
  const loadAllGraphs = useCallback(async (vpcsData?: VPC[], force = false) => {
    if (!accountId) {
      setGraphError('No account selected');
      return;
    }

    // The merged all-VPCs canvas is the most expensive view and the default one, so
    // it is the request most worth not repeating on every visit.
    if (!force) {
      const cached = queryClient.getQueryData<GraphElement[]>(graphKey(accountId, ALL_VPCS_VALUE));
      if (cached && cached.length > 0) {
        setGraphElements(cached);
        setGraphError(null);
        setLoadingGraph(false);
        return;
      }
    }

    const list = vpcsData ?? vpcs;
    if (list.length === 0) {
      setGraphElements([]);
      return;
    }

    setLoadingGraph(true);
    setGraphError(null);

    try {
      /*
       * These requests are fanned out carefully rather than all at once.
       *
       * Every /cloudmap/graph call runs CloudListService.getAllResources, which is
       * a ~1000-call full account scan behind a Redis cache. Promise.all over 30
       * VPCs meant 30 simultaneous cache misses — roughly 30,000 AWS calls at once
       * — which throttled, timed out, and came back as empty arrays from the
       * service's exceptionally() handler. The canvas then showed "30 VPCs, 0
       * Subnets, 0 Resources" because the only surviving nodes were the synthetic
       * roots created below.
       *
       * So the first request goes alone to populate the cache, and the rest follow
       * in small batches that hit it. One cold scan instead of thirty.
       */
      const BATCH_SIZE = 4;
      /* The first, cache-warming request pays for the whole scan. The Vite proxy
       * and nginx both allow 300s for /api/xamops, so the client must not give up
       * sooner — apiFetch's 45s default would abort a legitimately slow scan. */
      const COLD_TIMEOUT_MS = 240_000;
      const WARM_TIMEOUT_MS = 60_000;

      let failures = 0;

      const fetchOne = async (vpc: VPC, timeoutMs: number): Promise<GraphElement[]> => {
        try {
          const response = await apiFetch(
            `/api/xamops/cloudmap/graph?${new URLSearchParams({ accountId: accountId!, vpcId: vpc.id, region: vpc.region }).toString()}`,
            { timeoutMs }
          );
          if (!response.ok) {
            failures++;
            return [];
          }
          const data = await response.json();
          return Array.isArray(data) ? (data as GraphElement[]) : [];
        } catch {
          failures++;
          return [];
        }
      };

      const results: GraphElement[][] = [];
      results.push(await fetchOne(list[0], COLD_TIMEOUT_MS));

      for (let i = 1; i < list.length; i += BATCH_SIZE) {
        const batch = list.slice(i, i + BATCH_SIZE);
        results.push(...await Promise.all(batch.map(vpc => fetchOne(vpc, WARM_TIMEOUT_MS))));
      }

      /*
       * Report a total failure as a failure.
       *
       * CloudMapService returns an empty list when a scan fails, so an error and an
       * empty account look identical over the wire. Without this the canvas claims
       * "we reached this account and found nothing", which is a stronger statement
       * than the data supports and sends people looking for missing resources
       * instead of a broken call.
       */
      if (failures === list.length) {
        setGraphError(
          `Could not load topology for any of the ${list.length} VPCs. The account scan may have timed out or been throttled.`);
        setGraphElements([]);
        return;
      }
      if (failures > 0) {
        setGraphError(`Topology for ${failures} of ${list.length} VPCs could not be loaded.`);
      }

      const merged: GraphElement[] = [];

      list.forEach((vpc, i) => {
        const elements = results[i];
        const prefix = `${vpc.id}::`;

        /*
         * Reuse the VPC node the backend already sent instead of wrapping it.
         *
         * CloudMapService returns a real VPC node for the requested VPC, parentless.
         * Adding a synthetic root and then reparenting everything parentless to it
         * caught that real node too, so each VPC rendered as a box labelled
         * "<name> (<region>)" containing a second, identical box labelled "<name>" —
         * a VPC inside itself, with the subnets pushed a level deeper than they are.
         *
         * The real node is used as the container and its label is widened to carry
         * the region, which is the only thing the synthetic root added. A synthetic
         * root is still emitted when the backend sends no VPC node, so a VPC whose
         * describe failed keeps a labelled box rather than scattering its resources.
         */
        const realVpcNode = elements.find((el) => {
          const d = el.data as any;
          const isEdge = el.group === 'edges' || (!!d.source && !!d.target);
          return !isEdge && d.type === 'VPC' && !d.parent;
        });
        const rootId = realVpcNode
          ? `${prefix}${(realVpcNode.data as any).id}`
          : `${prefix}vpc-root`;

        if (!realVpcNode) {
          merged.push({
            group: 'nodes',
            data: {
              id: rootId,
              label: `${vpc.name} (${vpc.region})`,
              type: 'VPC',
            },
          });
        }

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
            const scopedId = `${prefix}${nd.id}`;
            const isRealVpcRoot = scopedId === rootId;
            merged.push({
              ...el,
              data: {
                ...nd,
                id: scopedId,
                // The VPC node is the container, so it must stay parentless —
                // parenting it to itself is what produced the nested box.
                ...(isRealVpcRoot
                  ? { label: `${vpc.name} (${vpc.region})`, parent: undefined }
                  : { parent: nd.parent ? `${prefix}${nd.parent}` : rootId }),
              },
            });
          }
        });
      });

      setGraphElements(merged);
      if (merged.length > 0) {
        queryClient.setQueryData(graphKey(accountId, ALL_VPCS_VALUE), merged);
      }
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

    /*
     * Reuse a known VPC list before showing any spinner.
     *
     * This is what the user actually sees on revisiting the page: loadVpcs runs from a
     * mount effect, so every navigation to Cloud Map raised "Finding your VPCs…" and
     * then re-fetched the topology behind it. With both lists cached the page paints
     * from cache and no overlay appears at all.
     */
    const cachedVpcs = queryClient.getQueryData<VPC[]>(vpcsKey(accountId));
    if (cachedVpcs && cachedVpcs.length > 0) {
      setVpcs(cachedVpcs);
      setVpcError(null);
      setSelectedVpcValue(ALL_VPCS_VALUE);
      await loadAllGraphs(cachedVpcs);
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
      if (Array.isArray(data) && data.length > 0) {
        queryClient.setQueryData(vpcsKey(accountId), data);
      }

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
  const loadGraph = useCallback(async (vpcValue: string, force = false) => {
    if (!accountId) {
      setGraphError('No account selected');
      return;
    }

    // Serve a previous result straight away. Refresh passes force to bypass this.
    if (!force) {
      const cached = queryClient.getQueryData<GraphElement[]>(graphKey(accountId, vpcValue));
      if (cached && cached.length > 0) {
        setGraphElements(cached);
        setGraphError(null);
        setLoadingGraph(false);
        return;
      }
    }

    setLoadingGraph(true);
    setGraphError(null);

    try {
      const [vpcId, region] = (vpcValue || '|').split('|');

      /* A single VPC still triggers the same ~1000-call account scan, so this
       * needs far longer than apiFetch's 45s default when the cache is cold. */
      const response = await apiFetch(
        `/api/xamops/cloudmap/graph?accountId=${accountId}&vpcId=${vpcId || ''}&region=${region || ''}`,
        { timeoutMs: 240_000 }
      );

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setGraphElements(data || []);
      // Only a non-empty result is cached: caching an empty graph would make a
      // transient failure look permanent until the tab is reloaded.
      if (Array.isArray(data) && data.length > 0) {
        queryClient.setQueryData(graphKey(accountId, vpcValue), data);
      }
    } catch (error) {
      console.error('Error loading graph:', error);
      setGraphError(error instanceof Error ? error.message : 'Failed to load graph');
      setGraphElements([]);
    } finally {
      setLoadingGraph(false);
    }
  }, [accountId, queryClient]);

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
