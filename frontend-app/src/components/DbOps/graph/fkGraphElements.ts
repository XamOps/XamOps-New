import type { FkGraphDocDto, FkMeta } from '../../../hooks/useDbOpsData';
import type { CyElement } from './accessGraphElements';

/**
 * Map the backend FkGraphDoc (§6.4) to cytoscape elements. The backend owns the edges[] ⊆ nodes[]
 * invariant (§7.3 backfill-then-drop), but this builder also skips any stray edge defensively so
 * cytoscape can never throw "nonexistant source/target". Boundary nodes get a `boundary` class for
 * the muted style.
 */
export function buildFkGraphElements(doc: FkGraphDocDto): CyElement[] {
  const nodeIds = new Set(doc.nodes.map((n) => n.id));
  const elements: CyElement[] = [];
  for (const n of doc.nodes) {
    elements.push({
      data: {
        id: n.id, label: n.table, schema: n.schema, table: n.table,
        sizeBytes: n.sizeBytes, rowEstimate: n.rowEstimate, activityScore: n.activityScore,
        boundary: n.boundary,
      },
      classes: n.boundary ? 'table boundary' : 'table',
    });
  }
  for (const e of doc.edges) {
    if (!nodeIds.has(e.source) || !nodeIds.has(e.target)) continue; // defensive: never a dangling edge
    elements.push({
      data: {
        id: e.id, source: e.source, target: e.target,
        columns: e.columns, onDelete: e.onDelete, onUpdate: e.onUpdate,
      },
      classes: 'fk',
    });
  }
  return elements;
}

/**
 * The honest sparse/empty message: a catalog-only FK graph with few/no edges is the truthful result,
 * not a bug. Returns null when the graph has edges to show.
 */
export function fkSparseMessage(doc: FkGraphDocDto): string | null {
  if (doc.edges.length > 0) return null;
  return `This schema declares ${doc.meta.declaredFkCount} foreign key${doc.meta.declaredFkCount === 1 ? '' : 's'} `
    + `across ${doc.meta.tableCount} table${doc.meta.tableCount === 1 ? '' : 's'}; `
    + `relationships that exist only in application code are not shown.`;
}

/** "N relationships hidden — narrow the schema or raise topN" banner, or null when none dropped. */
export function fkHiddenLabel(meta: FkMeta | null | undefined): string | null {
  if (!meta || meta.droppedEdgeCount <= 0) return null;
  return `${meta.droppedEdgeCount} relationship${meta.droppedEdgeCount === 1 ? '' : 's'} hidden — narrow the schema or raise topN`;
}
