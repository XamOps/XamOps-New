import { describe, expect, it } from 'vitest';
import { DEFAULT_RETENTION, defaultRegions, recommendedBucketName, stackChunks, toPlanRequest, validateRetention, validateWizard, type WizardState } from './setupLogic';
import type { XpInstanceCandidate } from '../../../types/xampulse';

const inst = (id: string, region: string, state: string, ssm: boolean | null): XpInstanceCandidate => ({ instanceId: id, name: id, region, state, tags: {}, ssmManaged: ssm, inScope: true, collectionLevel: 'T1', agent: { status: 'NONE' } });

const base = (): WizardState => ({
  awsAccountId: '111122223333', bucketMode: 'CREATE', bucketName: recommendedBucketName('111122223333', 'us-east-1'), bucketRegion: 'us-east-1', kmsMode: 'CREATE', kmsArn: '', homeRegion: 'us-east-1',
  regions: { 'us-east-1': { mode: 'INSTANCES', ids: ['i-0123456789abcdef0'], confirmAll: false, tagKey: '', tagValue: '' } }, retention: DEFAULT_RETENTION, docker: false,
});

describe('retention boundaries', () => {
  it('accepts the defaults and the edges', () => {
    expect(validateRetention(DEFAULT_RETENTION)).toEqual({});
    expect(validateRetention({ hotDays: 3, iaAfterDays: 30, glacierIrAfterDays: 90, expireAfterDays: 180 })).toEqual({});
    expect(validateRetention({ hotDays: 30, iaAfterDays: 30, glacierIrAfterDays: 90, expireAfterDays: 3650 })).toEqual({});
  });
  it('rejects values just outside', () => {
    expect(validateRetention({ ...DEFAULT_RETENTION, hotDays: 2 }).hotDays).toBeDefined();
    expect(validateRetention({ ...DEFAULT_RETENTION, hotDays: 31 }).hotDays).toBeDefined();
    expect(validateRetention({ ...DEFAULT_RETENTION, iaAfterDays: 29 }).iaAfterDays).toBeDefined();
    expect(validateRetention({ ...DEFAULT_RETENTION, glacierIrAfterDays: 89 }).glacierIrAfterDays).toBeDefined();
    expect(validateRetention({ ...DEFAULT_RETENTION, iaAfterDays: 70, glacierIrAfterDays: 90 }).glacierIrAfterDays).toBeDefined();
    expect(validateRetention({ ...DEFAULT_RETENTION, expireAfterDays: 179 }).expireAfterDays).toBeDefined();
    expect(validateRetention({ ...DEFAULT_RETENTION, expireAfterDays: 3651 }).expireAfterDays).toBeDefined();
  });
});

describe('wizard validation', () => {
  it('passes a valid default and builds the plan request', () => {
    expect(validateWizard(base(), [])).toEqual([]);
    const req = toPlanRequest(base());
    expect(req.regions).toEqual([{ region: 'us-east-1', targetMode: 'INSTANCES', instanceIds: ['i-0123456789abcdef0'] }]);
    expect(req.bucket).toEqual({ mode: 'CREATE', name: 'xamops-xampulse-111122223333-us-east-1', region: 'us-east-1' });
  });
  it('requires confirmation for ALL_MANAGED', () => {
    const s = base();
    s.regions['us-east-1'] = { mode: 'ALL_MANAGED', ids: [], confirmAll: false, tagKey: '', tagValue: '' };
    expect(validateWizard(s, []).join(' ')).toMatch(/Confirm/);
    s.regions['us-east-1'].confirmAll = true;
    expect(validateWizard(s, [])).toEqual([]);
  });
  it('requires a KMS ARN for a customer key and blocks unusable buckets', () => {
    const s = { ...base(), kmsMode: 'CUSTOMER_KEY' as const, kmsArn: 'nope' };
    expect(validateWizard(s, []).join(' ')).toMatch(/KMS key ARN/);
    const e = { ...base(), bucketMode: 'EXISTING' as const, bucketName: 'public-bucket', kmsMode: 'AWS_MANAGED' as const };
    const blocked = validateWizard(e, [{ name: 'public-bucket', region: 'us-east-1', encryption: 'NONE', versioning: false, publicAccessBlocked: false, lifecycleHasXamPulseRule: false, recommended: false, issues: [{ code: 'PUBLIC', level: 'BLOCK', message: 'Public access is not blocked.' }] }]);
    expect(blocked).toContain('Public access is not blocked.');
  });
  it('rejects an empty selection and AWS-managed keys with a new bucket', () => {
    const s = base();
    s.regions['us-east-1'].ids = [];
    expect(validateWizard(s, [])).toContain('Select at least one instance.');
    expect(validateWizard({ ...base(), kmsMode: 'AWS_MANAGED' }, []).join(' ')).toMatch(/only available with an existing bucket/);
  });
  it('ticks running SSM-managed instances by default and chunks by 50', () => {
    const r = defaultRegions([inst('i-aaaaaaaa', 'us-east-1', 'running', true), inst('i-bbbbbbbb', 'us-east-1', 'stopped', true), inst('i-cccccccc', 'eu-west-1', 'running', false)]);
    expect(r['us-east-1'].ids).toEqual(['i-aaaaaaaa']);
    expect(r['eu-west-1'].ids).toEqual([]);
    expect(stackChunks(51)).toBe(2);
    expect(stackChunks(50)).toBe(1);
  });
});
