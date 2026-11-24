/**
 * XamLens Misconfigurations page demo data (ROLE_XAMOPS).
 *
 * Built the way the backend builds it: every cloud and on-prem demo datastore (the list rows, severity
 * first), each one's drawer `/risks` findings, grouped by policy. Findings are grouped by title, the
 * demo's findings carrying no policy id: a catalogue title takes the catalogue's id, frameworks and Learn
 * More link; any other title (the hand-written showcases) gets `demo-` plus a slug and no frameworks. So
 * the page, its drawer and each datastore's Risk tab always agree.
 */
import type { DatastoreRisks, DatastoreRow } from '../types/dspmDatastores';
import type {
  AffectedDatastore, MisconfigurationDetail, MisconfigurationList, MisconfigurationRow,
} from '../types/dspmMisconfigurations';
import { demoDatastoreResource } from './xamlensDrawerMockData';
import { demoDatastores, demoDatastoreSource } from './xamlensMockData';
import { demoCataloguePolicy } from './xamlensRiskCatalogue';

const SOURCE_ID = /^[0-9a-f]{24}$/;
const SEVERITY_RANK: Readonly<Record<string, number>> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };
const rankOf = (severity: string | null) => SEVERITY_RANK[severity ?? ''] ?? 0;

/** "demo-" and the title in lower case, other characters as "-", within the backend's 128 characters. */
const slugId = (title: string) =>
  `demo-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')}`.slice(0, 128);

/** One policy across the demo datastores, as the backend's snapshot holds it. */
interface DemoPolicy {
  detail: Omit<MisconfigurationDetail, 'affected'>;
  affected: AffectedDatastore[];
}

/** The cloud then on-prem list rows, severity first, as the backend reads them. */
function scannedRows(): DatastoreRow[] {
  return ['cloud', 'on_prem'].flatMap(tab =>
    demoDatastores(`/api/dspm/datastores?tab=${tab}&page=1&pageSize=100&sort=-severity`).data);
}

function policies(): DemoPolicy[] {
  const byTitle = new Map<string, DemoPolicy>();
  for (const row of scannedRows()) {
    const sourceId = row.sourceId;
    if (sourceId === null || !SOURCE_ID.test(sourceId)) continue;
    const risks = demoDatastoreResource(sourceId, 'risks', `/api/dspm/datastores/${sourceId}/risks`) as DatastoreRisks | null;
    const lastScannedAt = demoDatastoreSource(sourceId)?.lastScannedAt ?? null;
    for (const finding of risks?.items ?? []) {
      if (finding.title === null) continue;
      let policy = byTitle.get(finding.title);
      if (!policy) {
        const catalogue = demoCataloguePolicy(finding.title);
        policy = {
          detail: {
            policyId: catalogue?.policyId ?? slugId(finding.title),
            title: finding.title,
            source: finding.source,
            severity: finding.severity,
            severityLabel: finding.severityLabel,
            description: finding.description,
            remediation: finding.remediation,
            cli: finding.cli,
            learnMore: catalogue?.learnMore ?? null,
            frameworks: catalogue?.frameworks ?? [],
          },
          affected: [],
        };
        byTitle.set(finding.title, policy);
      }
      if (!policy.affected.some(item => item.datastore.sourceId === sourceId)) {
        policy.affected.push({ datastore: row, lastScannedAt });
      }
    }
  }
  return [...byTitle.values()];
}

function rowOf({ detail, affected }: DemoPolicy): MisconfigurationRow {
  const labels: string[] = [];
  for (const item of affected) {
    for (const label of item.datastore.sensitives.typeLabels) if (!labels.includes(label)) labels.push(label);
  }
  const cloudAccounts = [...new Set(affected
    .filter(item => item.datastore.category === 'cloud')
    .map(item => item.datastore.source.accountLabel)
    .filter((label): label is string => label !== null))];
  return {
    policyId: detail.policyId,
    title: detail.title,
    source: detail.source,
    severity: detail.severity,
    severityLabel: detail.severityLabel,
    affectedResources: affected.length,
    sensitiveRecords: affected.reduce((sum, item) => sum + item.datastore.sensitives.recordCount, 0),
    labels,
    frameworks: detail.frameworks,
    cloudAccounts,
  };
}

/** Severity, then Affected Resources descending, then title A to Z, then policy id. */
function byRowOrder(a: MisconfigurationRow, b: MisconfigurationRow): number {
  return rankOf(b.severity) - rankOf(a.severity)
    || b.affectedResources - a.affectedResources
    || (a.title ?? '').toLowerCase().localeCompare((b.title ?? '').toLowerCase())
    || a.policyId.localeCompare(b.policyId);
}

/** `GET /api/dspm/misconfigurations`. */
export function demoMisconfigurationList(): MisconfigurationList {
  const all = policies();
  const rows = all.map(rowOf).sort(byRowOrder);
  const count = (severity: string) => rows.filter(row => row.severity === severity).length;
  return {
    totalAffectedResources: new Set(all.flatMap(p => p.affected.map(item => item.datastore.sourceId))).size,
    open: { total: rows.length, critical: count('CRITICAL'), high: count('HIGH'), medium: count('MEDIUM'), low: count('LOW') },
    datastoresRead: scannedRows().filter(row => row.sourceId !== null && SOURCE_ID.test(row.sourceId)).length,
    capped: false,
    rows,
  };
}

/** `GET /api/dspm/misconfigurations/policies/{policyId}`, or null for a policy the demo does not report. */
export function demoMisconfigurationDetail(policyId: string): MisconfigurationDetail | null {
  const policy = policies().find(p => p.detail.policyId === policyId);
  return policy ? { ...policy.detail, affected: policy.affected } : null;
}
