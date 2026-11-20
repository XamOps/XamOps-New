/** Synthetic Security Center findings shared by the Findings tab tests. No tenant values. */
import type { SecurityFinding } from '../../../../types/security';

export function finding(over: Partial<SecurityFinding> & Pick<SecurityFinding, 'resourceId'>): SecurityFinding {
  return {
    region: 'Global',
    category: 'IAM',
    severity: 'High',
    description: 'User has console access but MFA is not enabled.',
    complianceFramework: 'CIS AWS Foundations',
    controlId: '1.2',
    ...over,
  };
}

/** One scan check on two users. */
export const MFA_ALICE = finding({ resourceId: 'alice' });
export const MFA_BOB = finding({ resourceId: 'bob' });

/** One scan check on two security groups in two regions. */
const OPEN_SSH = {
  category: 'VPC', severity: 'Critical', controlId: '4.1',
  description: 'Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) 22',
};
export const SSH_A = finding({ resourceId: 'sg-0aaa1111', region: 'us-east-1', ...OPEN_SSH });
export const SSH_B = finding({ resourceId: 'sg-0bbb2222', region: 'ap-south-1', ...OPEN_SSH });

export const PUBLIC_BUCKET = finding({
  resourceId: 'orders-archive', region: 'eu-west-1', category: 'S3', severity: 'Critical',
  description: 'Bucket ACL grants public access.', controlId: '2.1.2',
});

/** Two different SOC2 checks that share control CC6.1. */
export const ROOT_KEYS = finding({
  resourceId: 'root', severity: 'Critical', complianceFramework: 'SOC2', controlId: 'CC6.1',
  description: 'Root user has active access keys, violating SOC2 access control requirements.',
});
export const SEGREGATION = finding({
  resourceId: 'carol', severity: 'Medium', complianceFramework: 'SOC2', controlId: 'CC6.1',
  description: 'User has both console and programmatic access, violating SOC2 access segregation principles.',
});

export const FLOW_LOGS = finding({
  resourceId: 'vpc-0123abcd', region: 'us-east-1', category: 'VPC', severity: 'Medium',
  description: 'VPC does not have Flow Logs enabled.', controlId: '2.9',
});

export const UNUSED_ROLE = finding({
  resourceId: 'legacy-role', severity: 'Low', complianceFramework: 'Custom Best Practice', controlId: 'IAM-002',
  description: 'Role has not been used in over 90 days.',
});

/** A live finding: title, description and remediation folded as the projection writes them. */
export const LIVE_BUCKET = finding({
  resourceId: 'arn:aws:s3:::exports-staging', region: 'us-east-1', category: 'AwsS3Bucket', severity: 'HIGH',
  complianceFramework: 'Security Hub', controlId: 'S3.8',
  description: 'S3 buckets should block public access \u2014 This control checks whether Block Public Access is on.'
    + ' Remediation: Turn on Block Public Access for the bucket.',
});

/** Every fixture, in no particular order. */
export const FINDINGS: SecurityFinding[] = [
  MFA_ALICE, FLOW_LOGS, SSH_A, UNUSED_ROLE, PUBLIC_BUCKET, MFA_BOB, ROOT_KEYS, SEGREGATION, SSH_B, LIVE_BUCKET,
];

/** The rows FINDINGS groups into, in table order. */
export const TITLES = [
  'Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) 22',
  'Bucket ACL grants public access.',
  'Root user has active access keys, violating SOC2 access control requirements.',
  'User has console access but MFA is not enabled.',
  'S3 buckets should block public access',
  'User has both console and programmatic access, violating SOC2 access segregation principles.',
  'VPC does not have Flow Logs enabled.',
  'Role has not been used in over 90 days.',
];
