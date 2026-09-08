/**
 * Where an AWS account's cost data is read from.
 *
 * Mirrors com.xammer.cloud.domain.AwsBillingSource. Four states rather than a
 * boolean deliberately: an account that cannot have a CUR needs its own value, or
 * it ends up pointed at a report that does not contain it and every cost widget
 * renders $0 — indistinguishable from an account that spent nothing.
 */
export type AwsBillingSource =
  | 'XAMMER_MPA_CUR'
  | 'OWN_CUR'
  | 'EXISTING_CUR'
  | 'COST_EXPLORER_ONLY';

export interface CurSource {
  id: number;
  name: string;
  ownerAccountId: string;
  xamopsOwned: boolean;
  assumeRoleArn?: string | null;
  assumeRoleExternalId?: string | null;
  s3Bucket: string;
  s3Prefix?: string | null;
  reportName: string;
  region: string;
  athenaDatabase?: string | null;
  athenaTable?: string | null;
  athenaWorkgroup?: string | null;
  athenaResultsLocation?: string | null;
  /** HOURLY, DAILY or MONTHLY — discovered, not configured. A MONTHLY report cannot serve daily charts. */
  timeUnit?: string | null;
  format?: string | null;
  curVersion?: string | null;
  resourceIdsEnabled?: boolean | null;
  splitCostDataEnabled?: boolean | null;
  /** Earliest month present. A legacy CUR does not backfill, so history starts here. */
  earliestBillingPeriod?: string | null;
  status: 'PENDING' | 'ACTIVE' | 'FAILED';
  statusDetail?: string | null;
  lastValidatedAt?: string | null;
}

/** Payload for registering a report. Athena names default server-side. */
export interface CurSourceRegistration {
  name: string;
  ownerAccountId: string;
  xamopsOwned: boolean;
  s3Bucket: string;
  s3Prefix?: string;
  reportName: string;
  region: string;
  assumeRoleArn?: string;
  assumeRoleExternalId?: string;
  curVersion?: string;
}

/** Result of comparing a CUR against Cost Explorer for one closed month. */
export interface CurReconciliation {
  awsAccountId: string;
  period: string;
  curTotal: number;
  costExplorerTotal: number;
  difference: number;
  relativeDifferencePercent: number;
  withinTolerance: boolean;
}
