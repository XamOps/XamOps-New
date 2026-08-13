import { describe, it, expect } from 'vitest';
import {
  resourceMark, categoryMark, packageMark, osMark, telemetryMark, referenceMark,
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

  it('reads a relationship reference from its id shape, since it carries no type', () => {
    // Asset relationships give a field name and a list of strings. The shape is the only signal.
    expect(referenceMark('sg-0df6aaaa1111').label).toBe('Security group');
    expect(referenceMark('subnet-0a1b2c3d4e').label).toBe('Subnet');
    expect(referenceMark('vpc-0abc123456').icon).toBe('logos:aws-vpc');
    expect(referenceMark('i-0aaa111222').icon).toBe('logos:aws-ec2');
    // `igw-` must not fall through to the `i-` rule.
    expect(referenceMark('igw-0abc123456').label).toBe('Internet gateway');
    expect(referenceMark('arn:aws:iam::111:role/deploy').icon).toBe('logos:aws-iam');
    expect(referenceMark('arn:aws:iam::111:instance-profile/app').label)
      .toBe('IAM instance profile');
    // Target groups are part of ELB, so the ELB mark is the correct product to name.
    expect(referenceMark('arn:aws:elasticloadbalancing:us-east-1:111:targetgroup/tg-1/73e2').label)
      .toBe('Target group');
    expect(referenceMark('arn:aws:elasticloadbalancing:us-east-1:111:loadbalancer/app/x/1').label)
      .toBe('Load balancer');
  });

  it('stays neutral for a reference it cannot identify rather than guessing a product', () => {
    // An unmapped ARN is still definitely AWS; a bare string is not identifiable at all.
    expect(referenceMark('arn:aws:some-future-service:us-east-1:111:thing/1').icon)
      .toBe('logos:aws');
    expect(referenceMark('running').icon).toBe('mdi:cube-outline');
    expect(referenceMark('').icon).toBe('mdi:cube-outline');
    expect(referenceMark(null).icon).toBe('mdi:cube-outline');
  });

  it('labels inventory categories including the uncategorised case', () => {
    expect(categoryMark('ai').label).toBe('AI');
    expect(categoryMark('compute_platform').label).toBe('Compute');
    // Null is a real value: legacy rows from the pre-catalog endpoint carry no category.
    expect(categoryMark(null).label).toBe('Uncategorised');
  });
});
