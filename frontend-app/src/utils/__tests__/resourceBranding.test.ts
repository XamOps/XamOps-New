import { describe, it, expect } from 'vitest';
import {
  resourceMark, categoryMark, packageMark, osMark, telemetryMark,
} from '../resourceBranding';

/**
 * These assertions encode two rules that are easy to break by adding a plausible-looking entry.
 */
describe('resourceBranding', () => {
  it('brands the AWS services that actually dominate the estate', () => {
    // Measured 2026-08-06: these 6 types are ~70% of a 1,414-asset account.
    expect(resourceMark('aws_bedrock_inference_profile').label).toBe('Bedrock inference profile');
    expect(resourceMark('aws_security_group').label).toBe('Security group');
    expect(resourceMark('aws_iam_role').icon).toBe('logos:aws-iam');
    expect(resourceMark('aws_subnet').label).toBe('Subnet');
    expect(resourceMark('aws_cloudwatch_log_group').icon).toBe('logos:aws-cloudwatch');
    expect(resourceMark('aws_s3_bucket').icon).toBe('logos:aws-s3');
  });

  it('never borrows another product\'s logo for a service that has none', () => {
    // AWS publishes no mark for security groups, EBS, flow logs or Bedrock. Standing in the
    // AWS Shield or Glacier logo would name the wrong product on screen.
    for (const type of ['aws_security_group', 'aws_ebs_volume', 'aws_ebs_snapshot',
      'aws_flow_log', 'aws_bedrock_inference_profile']) {
      expect(resourceMark(type).icon.startsWith('mdi:')).toBe(true);
      // ...but they still carry AWS's own palette so they read as AWS resources.
      expect(resourceMark(type).color).toBeTruthy();
    }
  });

  it('matches the same vocabulary in either case, since findings shout and the catalog does not', () => {
    // Upwind sends `aws_security_group` on catalog assets and `AWS_SECURITY_GROUP` on findings.
    expect(resourceMark('AWS_SECURITY_GROUP')).toEqual(resourceMark('aws_security_group'));
    expect(resourceMark('KUBERNETES_DEPLOYMENT').icon).toBe('logos:kubernetes');
  });

  it('degrades an unmapped type to its provider mark and a readable label', () => {
    const unknown = resourceMark('aws_some_future_service');
    expect(unknown.icon).toBe('logos:aws');
    expect(unknown.label).toBe('Some future service');
    expect(resourceMark('kubernetes_future_thing').icon).toBe('logos:kubernetes');
    expect(resourceMark(null).icon).toBe('mdi:cube-outline');
  });

  it('matches packages on prefix, because real names carry suffixes', () => {
    // All observed live in one account; exact-name matching would miss every one of them.
    expect(packageMark('busybox-binsh').label).toBe('BusyBox');
    expect(packageMark('alpine-baselayout-data').icon).toBe('simple-icons:alpinelinux');
    expect(packageMark('apk-tools').icon).toBe('simple-icons:alpinelinux');
    expect(packageMark('libssl3').label).toBe('OpenSSL');
    expect(packageMark('libcrypto3').label).toBe('OpenSSL');
    expect(packageMark('musl-utils').label).toBe('musl libc');
    expect(packageMark('python3.12').icon).toBe('logos:python');
    expect(packageMark('scanelf').icon).toBe('mdi:package-variant-closed');
  });

  it('brands base-image distros, with the AWS wordmark for Amazon Linux', () => {
    expect(osMark('alpine').icon).toBe('simple-icons:alpinelinux');
    expect(osMark('Ubuntu').icon).toBe('logos:ubuntu');
    // Amazon Linux has no dedicated mark, so the AWS wordmark is the correct attribution.
    expect(osMark('amazon linux').icon).toBe('logos:aws');
    expect(osMark('amzn2023').icon).toBe('logos:aws');
    expect(osMark(null).label).toBe('Unknown OS');
  });

  it('names the actual telemetry product, which is the point of that panel', () => {
    expect(telemetryMark('AWS CloudTrail').icon).toBe('logos:aws-cloudtrail');
    expect(telemetryMark('Azure Logs').icon).toBe('logos:microsoft-azure');
    expect(telemetryMark('GCP Logs').icon).toBe('logos:google-cloud');
    expect(telemetryMark('Sensor').label).toBe('Sensor');
    expect(telemetryMark('Cloud Scanner').label).toBe('Cloud Scanner');
  });

  it('labels inventory categories including the uncategorised case', () => {
    expect(categoryMark('ai').label).toBe('AI');
    expect(categoryMark('compute_platform').label).toBe('Compute');
    // Null is a real value: legacy rows from the pre-catalog endpoint carry no category.
    expect(categoryMark(null).label).toBe('Uncategorised');
  });
});
