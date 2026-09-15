/**
 * Loads the resource catalog that drives the properties form.
 *
 * Fetched from the backend rather than duplicated in TypeScript. The catalog is
 * the single description of what a resource type has — attributes, kinds,
 * defaults, validation, legal references — and a second copy here would drift.
 * The failure mode of drift is quiet: a form that collects a field the emitter
 * ignores, or omits one it requires.
 *
 * Cached at module scope because it is product metadata, identical for every user
 * and every design, so remounting a panel should not refetch it.
 */

import { useEffect, useState } from 'react';
import { apiFetchJson } from '../utils/apiClient';

export type AttributeKind =
  | 'STRING' | 'NUMBER' | 'BOOL' | 'CIDR' | 'AZ'
  | 'INSTANCE_TYPE' | 'ENUM' | 'TAGS' | 'BLOCK_LIST';

export type ReferenceVia = 'CONTAINMENT' | 'EDGE';

export interface AttributeSpec {
  name: string;
  kind: AttributeKind;
  required: boolean;
  defaultValue: string | null;
  description: string | null;
  options: string[];
  promoteToVariable: boolean;
  nested: AttributeSpec[];
}

export interface ReferenceSpec {
  field: string;
  targetTfType: string;
  via: ReferenceVia;
  required: boolean;
  list: boolean;
}

export interface ResourceTypeSpec {
  tfType: string;
  displayName: string;
  canvasType: string;
  idAttribute: string;
  attributes: AttributeSpec[];
  references: ReferenceSpec[];
  outputs: string[];
}

/** Resolved once per page load and shared by every consumer. */
let cache: ResourceTypeSpec[] | null = null;
let inFlight: Promise<ResourceTypeSpec[]> | null = null;

async function loadCatalog(): Promise<ResourceTypeSpec[]> {
  if (cache) return cache;
  if (!inFlight) {
    inFlight = apiFetchJson<ResourceTypeSpec[]>('/api/xamops/design/catalog')
      .then((specs) => {
        cache = Array.isArray(specs) ? specs : [];
        return cache;
      })
      .finally(() => {
        // Clear the latch either way, so a failed load can be retried.
        inFlight = null;
      });
  }
  return inFlight;
}

export interface DesignCatalog {
  specs: ResourceTypeSpec[];
  /** Look up by the canvas NODE_TYPE_DATA key, e.g. "EC2 Instance". */
  byCanvasType: (canvasType: string | undefined | null) => ResourceTypeSpec | undefined;
  loading: boolean;
  error: string | null;
}

export function useDesignCatalog(enabled = true): DesignCatalog {
  const [specs, setSpecs] = useState<ResourceTypeSpec[]>(cache ?? []);
  const [loading, setLoading] = useState(enabled && !cache);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!enabled || cache) {
      if (cache) setSpecs(cache);
      setLoading(false);
      return;
    }
    let alive = true;
    setLoading(true);
    loadCatalog()
      .then((loaded) => {
        if (!alive) return;
        setSpecs(loaded);
        setError(null);
      })
      .catch((err) => {
        if (!alive) return;
        setError(err instanceof Error ? err.message : 'Could not load the resource catalog');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => { alive = false; };
  }, [enabled]);

  return {
    specs,
    byCanvasType: (canvasType) =>
      canvasType ? specs.find((s) => s.canvasType === canvasType || s.tfType === canvasType) : undefined,
    loading,
    error,
  };
}
