import type { XpBucketCandidate, XpInstanceCandidate, XpRetention, XpSetupPlanRequest, XpTargetMode } from '../../../types/xampulse';

/** Pure onboarding rules. They mirror the server validation in impl-plan 2.9; the server stays authoritative. */
export const DEFAULT_RETENTION: XpRetention = { hotDays: 14, iaAfterDays: 30, glacierIrAfterDays: 90, expireAfterDays: 400 };

export const REGION_RE = /^[a-z]{2}(-[a-z]+)+-\d$/;
export const BUCKET_RE = /^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/;
export const INSTANCE_RE = /^i-[0-9a-f]{8,17}$/;
export const MAX_INSTANCES_PER_REGION = 500;
export const STACK_CHUNK = 50;

export function validateRetention(r: XpRetention): Partial<Record<keyof XpRetention, string>> {
  const e: Partial<Record<keyof XpRetention, string>> = {};
  if (!Number.isInteger(r.hotDays) || r.hotDays < 3 || r.hotDays > 30) e.hotDays = 'Hot storage must be between 3 and 30 days.';
  if (!Number.isInteger(r.iaAfterDays) || r.iaAfterDays < 30) e.iaAfterDays = 'Infrequent access starts at 30 days or later.';
  if (!Number.isInteger(r.glacierIrAfterDays) || r.glacierIrAfterDays < 90 || r.glacierIrAfterDays < r.iaAfterDays + 30) e.glacierIrAfterDays = 'Glacier starts at 90 days or later, and at least 30 days after infrequent access.';
  if (!Number.isInteger(r.expireAfterDays) || r.expireAfterDays < r.glacierIrAfterDays + 90 || r.expireAfterDays > 3650) e.expireAfterDays = 'Logs expire at least 90 days after Glacier, and within 3650 days.';
  return e;
}

export const recommendedBucketName = (awsAccountId: string, region: string): string => `xamops-xampulse-${awsAccountId}-${region}`;

export interface WizardState {
  awsAccountId: string;
  bucketMode: 'CREATE' | 'EXISTING';
  bucketName: string;
  bucketRegion: string;
  kmsMode: 'CREATE' | 'CUSTOMER_KEY' | 'AWS_MANAGED';
  kmsArn: string;
  homeRegion: string;
  /** Per region: selected instance ids, or an all-managed choice. */
  regions: Record<string, { mode: XpTargetMode; ids: string[]; confirmAll: boolean; tagKey: string; tagValue: string }>;
  retention: XpRetention;
  docker: boolean;
}

export function validateWizard(s: WizardState, buckets: XpBucketCandidate[]): string[] {
  const errs: string[] = [];
  if (!REGION_RE.test(s.bucketRegion)) errs.push('Choose a region for the archive bucket.');
  if (!REGION_RE.test(s.homeRegion)) errs.push('Choose a home region.');
  if (s.bucketMode === 'CREATE') {
    if (s.bucketName !== recommendedBucketName(s.awsAccountId, s.bucketRegion)) errs.push(`A new bucket must be named ${recommendedBucketName(s.awsAccountId, s.bucketRegion)}.`);
  } else {
    if (!BUCKET_RE.test(s.bucketName)) errs.push('Choose an existing bucket.');
    const b = buckets.find((x) => x.name === s.bucketName);
    const block = b?.issues.find((i) => i.level === 'BLOCK');
    if (block) errs.push(block.message);
  }
  if (s.kmsMode === 'CUSTOMER_KEY' && !/^arn:aws[a-z-]*:kms:[a-z0-9-]+:\d{12}:key\/[0-9a-f-]+$/.test(s.kmsArn.trim())) errs.push('Enter a valid KMS key ARN.');
  if (s.kmsMode === 'AWS_MANAGED' && s.bucketMode !== 'EXISTING') errs.push('AWS managed encryption is only available with an existing bucket.');
  const re = validateRetention(s.retention);
  (Object.keys(re) as (keyof XpRetention)[]).forEach((k) => { const m = re[k]; if (m) errs.push(m); });
  let any = false;
  Object.keys(s.regions).forEach((region) => {
    const r = s.regions[region];
    if (r.mode === 'ALL_MANAGED') { any = true; if (!r.confirmAll) errs.push(`Confirm that every managed instance in ${region}, including future ones, may send data.`); }
    else if (r.mode === 'TAG') { any = true; if (!r.tagKey.trim()) errs.push(`Enter a tag key for ${region}.`); }
    else if (r.ids.length > 0) {
      any = true;
      if (r.ids.length > MAX_INSTANCES_PER_REGION) errs.push(`${region} has more than ${MAX_INSTANCES_PER_REGION} instances selected. Use tag targeting instead.`);
      if (r.ids.some((id) => !INSTANCE_RE.test(id))) errs.push(`${region} contains an invalid instance id.`);
    }
  });
  if (!any) errs.push('Select at least one instance.');
  return errs;
}

export function toPlanRequest(s: WizardState): XpSetupPlanRequest {
  return {
    bucket: { mode: s.bucketMode, name: s.bucketName, region: s.bucketRegion },
    kms: { mode: s.kmsMode, ...(s.kmsMode === 'CUSTOMER_KEY' ? { keyArn: s.kmsArn.trim() } : {}) },
    homeRegion: s.homeRegion,
    regions: Object.keys(s.regions).filter((region) => { const r = s.regions[region]; return r.mode !== 'INSTANCES' || r.ids.length > 0; }).map((region) => {
      const r = s.regions[region];
      return r.mode === 'ALL_MANAGED' ? { region, targetMode: r.mode, confirmAllManaged: r.confirmAll }
        : r.mode === 'TAG' ? { region, targetMode: r.mode, tagKey: r.tagKey.trim(), tagValue: r.tagValue.trim() }
        : { region, targetMode: r.mode, instanceIds: r.ids };
    }),
    retention: s.retention,
    sources: { system: true, docker: s.docker, custom: [] },
  };
}

/** Default selection: running and SSM-managed instances are ticked, grouped by region. */
export function defaultRegions(instances: XpInstanceCandidate[]): WizardState['regions'] {
  const out: WizardState['regions'] = {};
  instances.forEach((i) => {
    const r = out[i.region] ?? { mode: 'INSTANCES' as XpTargetMode, ids: [], confirmAll: false, tagKey: '', tagValue: '' };
    if (i.state === 'running' && i.ssmManaged === true) r.ids.push(i.instanceId);
    out[i.region] = r;
  });
  return out;
}

/** How many CloudFormation parameter groups a selection needs (50 ids per group). */
export const stackChunks = (count: number): number => Math.ceil(count / STACK_CHUNK);
