/**
 * How the AWS Alerts page arranges its rows: by category, with each category shaped for what a
 * reader needs from it. Pure functions, so the arrangement is testable without rendering.
 */

import type {
  Alert,
  AlertError,
  AlertSeverity,
  AnomalyAlert,
  QuotaAlert,
  SecurityAlert,
} from '../../types/alerts';

export type AlertCategory = 'LIMITS' | 'SECURITY' | 'COST';

export type SeverityFilter = 'ALL' | 'CRITICAL' | 'WARNING';

const RANK: Record<AlertSeverity, number> = {
  CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3, INFORMATIONAL: 4,
};

export const severityRank = (s: AlertSeverity): number => RANK[s] ?? 5;

/** Critical is "act now"; high and medium are worth a look. Low and informational show under All only. */
export const matchesSeverity = (severity: AlertSeverity, filter: SeverityFilter): boolean => {
  if (filter === 'CRITICAL') return severity === 'CRITICAL';
  if (filter === 'WARNING') return severity === 'HIGH' || severity === 'MEDIUM';
  return true;
};

// ── Service limits ──────────────────────────────────────────────────────────

/** One region's — or zone's — reading of a limit. */
export interface QuotaUsage {
  /** The region, or the Availability Zone for a per-zone limit. */
  label: string;
  region: string;
  usage: number;
  limit: number;
  percent: number;
  severity: AlertSeverity;
}

/** One limit, with a reading for every region where it is close to full. */
export interface QuotaGroup {
  key: string;
  name: string;
  service: string;
  /** The worst reading's severity. */
  severity: AlertSeverity;
  usages: QuotaUsage[];
}

/** "NAT gateways per Availability Zone (ap-south-1a)" — a per-zone limit names its zone. */
const SCOPED_NAME = /^(.*) \(([^)]+)\)$/;

/**
 * Collapses quota alerts into one row per limit.
 *
 * The same limit is checked in every region, so a flat list repeated "VPCs per Region" once per
 * region that was close to it — three rows, three buttons, for what a reader thinks of as one limit.
 */
export const groupQuotaAlerts = (alerts: QuotaAlert[]): QuotaGroup[] => {
  const groups = new Map<string, QuotaGroup>();
  for (const a of alerts) {
    const scoped = a.name.match(SCOPED_NAME);
    const name = scoped ? scoped[1] : a.name;
    const key = `${a.service}|${name}`;

    let group = groups.get(key);
    if (!group) {
      group = { key, name, service: a.service, severity: a.severity, usages: [] };
      groups.set(key, group);
    }
    group.usages.push({
      label: scoped ? scoped[2] : a.region,
      region: a.region,
      usage: a.usage,
      limit: a.limit,
      percent: a.limit > 0 ? (a.usage / a.limit) * 100 : 0,
      severity: a.severity,
    });
    if (severityRank(a.severity) < severityRank(group.severity)) {
      group.severity = a.severity;
    }
  }

  const result = [...groups.values()];
  for (const g of result) {
    g.usages.sort((x, y) => y.percent - x.percent || x.label.localeCompare(y.label));
  }
  return result.sort((a, b) =>
    severityRank(a.severity) - severityRank(b.severity)
    || b.usages[0].percent - a.usages[0].percent
    || a.name.localeCompare(b.name));
};

// ── Security findings ───────────────────────────────────────────────────────

export interface FindingText {
  summary: string;
  /** The "how to fix" part, when the finding carries one. */
  remediation: string | null;
}

/**
 * Separates what a finding is from how to fix it.
 *
 * Both sources pack the two into one string — Security Hub as "… | Remediation: …", XamOps's
 * own detectors as "<title> — … Remediation: …" — which rendered as a paragraph per row.
 */
export const splitFinding = (title: string, description: string): FindingText => {
  let text = (description || '').trim();
  const titlePrefix = `${title} — `;
  if (title && text.startsWith(titlePrefix)) {
    text = text.slice(titlePrefix.length);
  }
  const marker = text.match(/\s*\|?\s*Remediation:\s*/);
  if (!marker || marker.index === undefined) {
    return { summary: text, remediation: null };
  }
  return {
    summary: text.slice(0, marker.index).trim(),
    remediation: text.slice(marker.index + marker[0].length).trim() || null,
  };
};

// ── Cost spikes ─────────────────────────────────────────────────────────────

/** The anomaly's description without the service name it opens with, which the row already shows. */
export const costSummary = (a: AnomalyAlert): string => {
  const prefix = `${a.service}: `;
  if (!a.description.startsWith(prefix)) return a.description;
  const rest = a.description.slice(prefix.length);
  return rest.charAt(0).toUpperCase() + rest.slice(1);
};

// ── Categories ──────────────────────────────────────────────────────────────

export interface CategorisedAlerts {
  limits: QuotaAlert[];
  security: SecurityAlert[];
  cost: AnomalyAlert[];
}

export const categorise = (alerts: Alert[]): CategorisedAlerts => ({
  limits: alerts.filter((a): a is QuotaAlert => a.type === 'QUOTA'),
  security: alerts.filter((a): a is SecurityAlert => a.type === 'SECURITY'),
  cost: alerts.filter((a): a is AnomalyAlert => a.type === 'ANOMALY'),
});

/** Error-row service names, as the backend reports them, by the section they belong to. */
const SOURCES: Record<AlertCategory, string[]> = {
  LIMITS: ['VPC quotas', 'Network quotas', 'VPC', 'Network'],
  SECURITY: ['Security Hub', 'SecurityHub', 'Live security findings', 'LiveFindings'],
  COST: ['Cost anomalies', 'FinOps'],
};

/**
 * Which sections have a check that did not run. An error naming no known source — a timeout, or
 * the whole scan failing — leaves every section unchecked.
 */
export const incompleteCategories = (errors: AlertError[]): Set<AlertCategory> => {
  const incomplete = new Set<AlertCategory>();
  for (const error of errors) {
    const owner = (Object.keys(SOURCES) as AlertCategory[]).find((c) => SOURCES[c].includes(error.service));
    if (owner) {
      incomplete.add(owner);
    } else {
      return new Set<AlertCategory>(['LIMITS', 'SECURITY', 'COST']);
    }
  }
  return incomplete;
};
