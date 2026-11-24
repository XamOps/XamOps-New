import { describe, expect, it } from 'vitest';
import { guideFor, liveLearnMore, resourceTypeFor, shellQuote } from '../checkGuides';

/** Every description `SecurityService` writes, with the resource type its guide names. */
const SCAN_CHECKS: ReadonlyArray<[string, string]> = [
  ['User has console access but MFA is not enabled.', 'IAM User'],
  ['Public Access Block is not fully enabled.', 'S3 Bucket'],
  ['Bucket ACL grants public access.', 'S3 Bucket'],
  ['Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) 22', 'Security Group'],
  ['Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) 30000-32767', 'Security Group'],
  ['Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) ALL', 'Security Group'],
  ['VPC does not have Flow Logs enabled.', 'VPC'],
  ['No CloudTrail trails are configured for the account.', 'AWS Account'],
  ['No active, multi-region CloudTrail trail found.', 'AWS Account'],
  ['Role has never been used and was created over 90 days ago.', 'IAM Role'],
  ['Role has not been used in over 90 days.', 'IAM Role'],
  ['Root user has active access keys, violating SOC2 access control requirements.', 'Root User'],
  ['User has both console and programmatic access, violating SOC2 access segregation principles.', 'IAM User'],
  ['S3 bucket does not have encryption enabled, violating SOC2 data protection requirements.', 'S3 Bucket'],
  ['S3 bucket encryption configuration could not be verified, violating SOC2 data protection requirements.', 'S3 Bucket'],
  ['S3 bucket does not enforce HTTPS-only access, violating SOC2 data transmission requirements.', 'S3 Bucket'],
  ['S3 bucket does not have a secure transport policy, violating SOC2 data transmission requirements.', 'S3 Bucket'],
  ['RDS instance does not have storage encryption enabled, violating SOC2 data protection requirements.', 'RDS Instance'],
  ['No CloudTrail trails configured, violating SOC2 monitoring requirements.', 'AWS Account'],
  ['CloudTrail log file validation is not enabled, reducing SOC2 monitoring integrity.', 'CloudTrail Trail'],
  ['No properly configured multi-region CloudTrail with log file validation found, violating SOC2 monitoring requirements.', 'AWS Account'],
  ['KMS key does not have automatic rotation enabled, violating SOC2 key management requirements.', 'KMS Key'],
  ['Password policy minimum length is less than 14 characters, not meeting SOC2 access security standards.', 'AWS Account'],
  ['Password policy does not require character complexity, violating SOC2 access security requirements.', 'AWS Account'],
  ['Password policy does not enforce password expiration within 90 days, violating SOC2 access security requirements.', 'AWS Account'],
  ['No password policy is configured, violating SOC2 access security requirements.', 'AWS Account'],
  ['Custom IAM policy grants overly broad permissions (*), violating SOC2 least privilege principles.', 'IAM Policy'],
];

const SSH = 'Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) 22';

describe('guideFor', () => {
  it.each(SCAN_CHECKS)('has a full guide for "%s"', (description, resourceType) => {
    const guide = guideFor(description, { resourceId: 'example', region: 'ap-south-1', accountId: '123456789012' });
    expect(guide).not.toBeNull();
    expect(guide!.resourceType).toBe(resourceType);
    expect(guide!.description.length).toBeGreaterThan(20);
    expect(guide!.steps.length).toBeGreaterThan(0);
    expect(guide!.cli).toMatch(/^(# .*\n)?aws /);
    expect(guide!.learnMore).toMatch(/^https:\/\/docs\.aws\.amazon\.com\//);
  });

  it('has no guide for a live finding or a check it does not list', () => {
    expect(guideFor('S3 buckets should block public access')).toBeNull();
    expect(guideFor('')).toBeNull();
    expect(guideFor(null)).toBeNull();
    expect(guideFor(`${SSH} `)).not.toBeNull();   // trimmed
    expect(guideFor(`${SSH} extra`)).toBeNull();
  });

  it('names the resource kind from the guide, else the category', () => {
    expect(resourceTypeFor(SSH, 'VPC')).toBe('Security Group');
    expect(resourceTypeFor('S3 buckets should block public access', 'AwsS3Bucket')).toBe('AwsS3Bucket');
    expect(resourceTypeFor(null, ' ')).toBe('Resource');
  });
});

describe('the open security group CLI', () => {
  it('fills in the group, the region and the port for one resource', () => {
    const guide = guideFor(SSH, { resourceId: 'sg-0aaa1111', region: 'ap-south-1' })!;
    expect(guide.cli!.split('\n')).toEqual([
      '# Inbound rules open to 0.0.0.0/0 on port(s) 22, with their rule IDs',
      'aws ec2 describe-security-group-rules --region ap-south-1 --filters Name=group-id,Values=sg-0aaa1111 --query \'SecurityGroupRules[?IsEgress==`false` && CidrIpv4==`"0.0.0.0/0"` && FromPort==`22` && ToPort==`22`].[SecurityGroupRuleId,IpProtocol,FromPort,ToPort]\' --output table',
      '# Remove a rule by its ID',
      'aws ec2 revoke-security-group-ingress --region ap-south-1 --group-id sg-0aaa1111 --security-group-rule-ids <rule-id>',
    ]);
    expect(guide.cliPerResource).toBe(true);
    expect(guide.consoleUrl).toBe('https://ap-south-1.console.aws.amazon.com/ec2/home?region=ap-south-1#SecurityGroup:groupId=sg-0aaa1111');
  });

  it('filters a port range and all traffic', () => {
    expect(guideFor('Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) 30000-32767')!.cli)
      .toContain('&& FromPort==`30000` && ToPort==`32767`]');
    expect(guideFor('Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) ALL')!.cli)
      .toContain('&& IpProtocol==`"-1"`]');
  });

  it('keeps placeholders, and no console link, without a resource', () => {
    const guide = guideFor(SSH)!;
    expect(guide.cli).toContain('--region <region> --group-id <security-group-id>');
    expect(guide.consoleUrl).toBeNull();
  });
});

describe('values in commands and links', () => {
  it('quotes a value only when Bash needs it', () => {
    expect(shellQuote('sg-0aaa1111')).toBe('sg-0aaa1111');
    expect(shellQuote('alice+ops@example.com')).toBe('alice+ops@example.com');
    expect(shellQuote('bad name')).toBe("'bad name'");
    expect(shellQuote("it's;rm")).toBe("'it'\\''s;rm'");
  });

  it('quotes a resource id that is not plain', () => {
    const guide = guideFor('Role has not been used in over 90 days.', { resourceId: 'odd role$(id)' })!;
    expect(guide.cli).toContain("--role-name 'odd role$(id)'");
  });

  it('treats "Global" as no region', () => {
    const guide = guideFor('Public Access Block is not fully enabled.', { resourceId: 'orders-archive', region: 'Global' })!;
    expect(guide.cli).toContain('--region <region> --bucket orders-archive');
    expect(guide.consoleUrl).toBe('https://s3.console.aws.amazon.com/s3/buckets/orders-archive');
  });

  it('puts the account in the MFA device ARN, and links the user', () => {
    const guide = guideFor('User has console access but MFA is not enabled.', { resourceId: 'alice', accountId: '123456789012' })!;
    expect(guide.cli).toContain('--serial-number arn:aws:iam::123456789012:mfa/alice');
    expect(guide.consoleUrl).toBe('https://console.aws.amazon.com/iam/home#/users/details/alice?section=security_credentials');
    expect(guideFor('User has console access but MFA is not enabled.')!.cli).toContain('arn:aws:iam::<account-id>:mfa/<user-name>');
  });

  it('writes a name into quoted text only when it is a valid name', () => {
    const transport = 'S3 bucket does not enforce HTTPS-only access, violating SOC2 data transmission requirements.';
    expect(guideFor(transport, { resourceId: 'orders-archive' })!.cli).toContain('"arn:aws:s3:::orders-archive/*"');
    expect(guideFor(transport, { resourceId: 'Not_A_Bucket' })!.cli).toContain('"arn:aws:s3:::<bucket-name>/*"');
    const policy = 'Custom IAM policy grants overly broad permissions (*), violating SOC2 least privilege principles.';
    expect(guideFor(policy, { resourceId: 'AdminAll' })!.cli).toContain("Policies[?PolicyName=='AdminAll']");
    expect(guideFor(policy, { resourceId: "x'y" })!.cli).toContain("Policies[?PolicyName=='<policy-name>']");
  });

  it('links a Security Hub control to its own remediation section, and GuardDuty to its guide', () => {
    expect(liveLearnMore('Security Hub', 'S3.8'))
      .toBe('https://docs.aws.amazon.com/securityhub/latest/userguide/s3-controls.html#s3-8-remediation');
    expect(liveLearnMore('Security Hub', 'Config.1'))
      .toBe('https://docs.aws.amazon.com/securityhub/latest/userguide/config-controls.html#config-1-remediation');
    expect(liveLearnMore('Security Hub', 'aws-foundational-security-best-practices/v/1.0.0/S3.8')).toBeNull();
    expect(liveLearnMore('GuardDuty', 'Recon:EC2/PortProbeUnprotectedPort'))
      .toBe('https://docs.aws.amazon.com/guardduty/latest/ug/guardduty_remediate.html');
    expect(liveLearnMore('AWS Config', 's3-bucket-logging-enabled')).toBeNull();
    expect(liveLearnMore('CIS AWS Foundations', '4.1')).toBeNull();
  });

  it('says when the CLI does not depend on the resource', () => {
    const guide = guideFor('No password policy is configured, violating SOC2 access security requirements.', { resourceId: 'Account' })!;
    expect(guide.cliPerResource).toBe(false);
    expect(guide.consoleUrl).toBe('https://console.aws.amazon.com/iam/home#/account_settings');
  });
});
