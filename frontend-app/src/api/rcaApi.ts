/**
 * One read of the RCA sub-route (`GET /api/eks/automation/vertical/{cluster}/rca`) for the Activity
 * page's RootCausePanel (FEAT-002).
 *
 * Mirrors `fetchAutomationRead`/`fetchVerticalEngine` exactly: a single `GET`, the same status
 * discipline — a `401` is a lapsed session, a typed `403` is a refusal with its gate code, a `404`
 * is a cluster this environment doesn't know, anything else is an error with the server's message.
 * Read-only; it never writes and never touches the cluster.
 */

import apiClient from '../utils/apiClient';
import { parseCode } from './verticalRightsizerAdapter';
import type { CodeView, VerticalRefusalWire } from '../types/verticalRightsizer';

const BASE = '/api/eks/automation/vertical';

const seg = (value: string): string => encodeURIComponent(value);

/** The RCA URL. `windowHours`, when given, is a sane hint the server clamps to `[1,168]`. */
export const rcaUrl = (
  cluster: string,
  accountId: string,
  region: string,
  windowHours?: number,
): string => {
  const base = `${BASE}/${seg(cluster)}/rca?accountId=${seg(accountId)}&region=${seg(region)}`;
  return windowHours === undefined ? base : `${base}&windowHours=${seg(String(windowHours))}`;
};

// ─── Wire shape ───────────────────────────────────────────────────────────────

export interface RcaWorkloadRefWire {
  namespace?: string | null;
  kind?: string | null;
  name?: string | null;
}

export interface RcaEvidenceWire {
  at?: string | null;
  outcome?: string | null;
  detail?: string | null;
  correlationId?: string | null;
}

export interface RcaCauseWire {
  kind?: string | null;
  title?: string | null;
  confidence?: number | null;
  workload?: RcaWorkloadRefWire | null;
  occurrences?: number | null;
  windowHours?: number | null;
  remediation?: string | null;
  evidence?: RcaEvidenceWire[] | null;
}

export interface RcaDependencyWire {
  kind?: string | null;
  needs?: string | null;
}

export interface RcaResponseWire {
  generatedAt?: string | null;
  windowHours?: number | null;
  analyzed?: { actionRows?: number | null; workloads?: number | null } | null;
  causes?: RcaCauseWire[] | null;
  dependencies?: RcaDependencyWire[] | null;
}

// ─── View shape ───────────────────────────────────────────────────────────────

export interface RcaWorkloadRefView {
  namespace: string;
  kind: string;
  name: string;
}

export interface RcaEvidenceView {
  at: string | null;
  outcome: string | null;
  detail: string | null;
  correlationId: string | null;
}

export interface RcaCauseView {
  kind: string;
  title: string;
  /** `null` when the server did not report a finite score, so the UI renders a dash, not a zero. */
  confidence: number | null;
  workload: RcaWorkloadRefView;
  occurrences: number | null;
  windowHours: number | null;
  remediation: string;
  evidence: RcaEvidenceView[];
}

export interface RcaDependencyView {
  kind: string;
  needs: string;
}

export interface RcaView {
  generatedAt: string | null;
  windowHours: number | null;
  analyzed: { actionRows: number; workloads: number };
  causes: RcaCauseView[];
  dependencies: RcaDependencyView[];
}

/** The kinds `fetchRca` answers with, carrying the view on success. */
export type RcaResult =
  | { kind: 'ok'; view: RcaView }
  | { kind: 'cluster-not-registered'; message: string }
  | { kind: 'refused'; code: CodeView; message: string }
  | { kind: 'unauthenticated' }
  | { kind: 'error'; message: string };

const readJson = async <T>(response: Response): Promise<T | null> => {
  try {
    const text = await response.text();
    if (!text || text.trim() === '') return null;
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
};

const refusalMessage = (body: VerticalRefusalWire | null, fallback: string): string => {
  const message = body?.message;
  return message && message.trim() !== '' ? message : fallback;
};

const finiteOrNull = (value: number | null | undefined): number | null =>
  typeof value === 'number' && Number.isFinite(value) ? value : null;

const nonNegIntOrZero = (value: number | null | undefined): number =>
  typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.trunc(value) : 0;

/** `kind`/`name` are the only parts a cause cannot be rendered without; a blank ref is dropped. */
const adaptRef = (wire: RcaWorkloadRefWire | null | undefined): RcaWorkloadRefView => ({
  namespace: wire?.namespace ?? '',
  kind: wire?.kind ?? '',
  name: wire?.name ?? '',
});

const adaptEvidence = (wire: RcaEvidenceWire[] | null | undefined): RcaEvidenceView[] =>
  (wire ?? []).map((e) => ({
    at: e.at ?? null,
    outcome: e.outcome ?? null,
    detail: e.detail ?? null,
    correlationId: e.correlationId ?? null,
  }));

const adaptCause = (wire: RcaCauseWire): RcaCauseView => ({
  kind: wire.kind ?? 'UNKNOWN',
  title: wire.title ?? '',
  confidence: finiteOrNull(wire.confidence),
  workload: adaptRef(wire.workload),
  occurrences: finiteOrNull(wire.occurrences),
  windowHours: finiteOrNull(wire.windowHours),
  remediation: wire.remediation ?? '',
  evidence: adaptEvidence(wire.evidence),
});

/** Adapts the wire body into the view, dropping nothing the server measured. */
export const adaptRca = (body: RcaResponseWire): RcaView => ({
  generatedAt: body.generatedAt ?? null,
  windowHours: finiteOrNull(body.windowHours),
  analyzed: {
    actionRows: nonNegIntOrZero(body.analyzed?.actionRows),
    workloads: nonNegIntOrZero(body.analyzed?.workloads),
  },
  causes: (body.causes ?? []).map(adaptCause),
  dependencies: (body.dependencies ?? [])
    .filter((d): d is RcaDependencyWire => !!d && !!d.kind)
    .map((d) => ({ kind: d.kind as string, needs: d.needs ?? '' })),
});

/** Ranked root causes over this cluster's durable history. Read-only. */
export const fetchRca = async (
  cluster: string,
  accountId: string,
  region: string,
  windowHours?: number,
): Promise<RcaResult> => {
  let response: Response;
  try {
    response = await apiClient.fetch(rcaUrl(cluster, accountId, region, windowHours), { method: 'GET' });
  } catch (e) {
    return { kind: 'error', message: e instanceof Error ? e.message : 'The request could not be sent.' };
  }

  if (response.status === 401) return { kind: 'unauthenticated' };

  if (response.ok) {
    const body = await readJson<RcaResponseWire>(response);
    if (!body) {
      return { kind: 'error', message: 'The engine returned no analysis.' };
    }
    return { kind: 'ok', view: adaptRca(body) };
  }

  const refusal = await readJson<VerticalRefusalWire>(response);

  if (response.status === 404) {
    return {
      kind: 'cluster-not-registered',
      message: refusalMessage(refusal, 'This cluster is not registered with XamOps in this environment.'),
    };
  }
  if (response.status === 403) {
    const code = parseCode(refusal?.code);
    if (code) {
      return { kind: 'refused', code, message: refusalMessage(refusal, 'This cluster is not available.') };
    }
  }
  return {
    kind: 'error',
    message: refusalMessage(refusal, `The analysis returned HTTP ${response.status}.`),
  };
};
