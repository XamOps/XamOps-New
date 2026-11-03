import type { AccessGraphDocDto, AgMeta } from '../../../hooks/useDbOpsData';

/**
 * A cytoscape element ({ data, classes }). The backend already emits cytoscape-ready node/edge
 * shapes (§6.4), so this builder is a thin map: flatten `data` and turn `kind`/`severity` into
 * cytoscape `classes`. Kept pure (no cytoscape import) so it is unit-testable without a canvas.
 */
export interface CyElement {
  data: Record<string, unknown>;
  classes?: string;
}

export function buildAccessGraphElements(doc: AccessGraphDocDto): CyElement[] {
  const elements: CyElement[] = [];
  for (const n of doc.nodes) {
    elements.push({
      data: { id: n.id, label: n.label, kind: n.kind, ...n.data },
      classes: [n.kind, n.severity ?? ''].filter(Boolean).join(' '),
    });
  }
  for (const e of doc.edges) {
    elements.push({
      data: { id: e.id, source: e.source, target: e.target, kind: e.kind },
      classes: e.kind,
    });
  }
  return elements;
}

/** "N more principals — raise topN or search" affordance, or null when nothing is hidden. */
export function accessHiddenLabel(meta: AgMeta | null | undefined): string | null {
  if (!meta || meta.hiddenPrincipalCount <= 0) return null;
  return `${meta.hiddenPrincipalCount} more principal${meta.hiddenPrincipalCount === 1 ? '' : 's'} — raise topN or search`;
}
