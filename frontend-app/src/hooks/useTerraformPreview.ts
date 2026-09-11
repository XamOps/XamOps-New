/**
 * Generates Terraform from the current canvas.
 *
 * Talks to POST /api/xamops/design/terraform/preview, which persists nothing —
 * generation is a pure function of the graph, so the panel can update while the
 * design is still being drawn rather than forcing a save first.
 *
 * Uses apiFetch rather than raw fetch, unlike the older useCloudMap hook, so
 * tenant and CSRF headers are actually sent.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { apiFetchJson } from '../utils/apiClient';

export interface TerraformFinding {
  severity: 'ERROR' | 'WARNING';
  nodeId: string | null;
  field: string | null;
  message: string;
}

export interface TerraformSkipped {
  nodeId: string;
  label: string;
  canvasType: string | null;
  reason: string;
}

export interface GeneratedTerraform {
  generatable: boolean;
  resourceCount: number;
  files: Record<string, string>;
  findings: TerraformFinding[];
  skipped: TerraformSkipped[];
  notes: string[];
}

const EMPTY: GeneratedTerraform = {
  generatable: false,
  resourceCount: 0,
  files: {},
  findings: [],
  skipped: [],
  notes: [],
};

interface Options {
  /** Serialised Cytoscape elements. Null pauses generation. */
  graphData: string | null;
  name: string;
  region: string;
  enabled: boolean;
  /**
   * Debounce before calling. Dragging a node fires continuously and each call
   * walks the whole graph, so coalescing keeps typing and dragging smooth.
   */
  debounceMs?: number;
}

export function useTerraformPreview({ graphData, name, region, enabled, debounceMs = 450 }: Options) {
  const [result, setResult] = useState<GeneratedTerraform>(EMPTY);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const inFlight = useRef<AbortController | null>(null);
  /** Guards against an earlier response landing after a later one. */
  const generation = useRef(0);

  const run = useCallback(async (graph: string, designName: string, designRegion: string) => {
    const mine = ++generation.current;
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;

    setLoading(true);
    setError(null);
    try {
      const data = await apiFetchJson<GeneratedTerraform>('/api/xamops/design/terraform/preview', {
        method: 'POST',
        body: JSON.stringify({ name: designName, region: designRegion, graphData: graph }),
        signal: controller.signal,
      });
      if (mine !== generation.current) return;
      setResult({ ...EMPTY, ...data });
    } catch (err) {
      if (mine !== generation.current) return;
      if (err instanceof DOMException && err.name === 'AbortError') return;
      setError(err instanceof Error ? err.message : 'Could not generate Terraform');
      setResult(EMPTY);
    } finally {
      if (mine === generation.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!enabled || !graphData) {
      setResult(EMPTY);
      setError(null);
      return;
    }
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => run(graphData, name, region), debounceMs);
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
  }, [graphData, name, region, enabled, debounceMs, run]);

  useEffect(() => () => inFlight.current?.abort(), []);

  return { ...result, loading, error };
}
