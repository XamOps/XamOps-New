/**
 * Remediation guides for the checks `SecurityService` runs itself: CIS AWS Foundations, SOC2 and the
 * custom IAM checks. Each says what the finding means, how to fix it, the AWS CLI fix, where AWS documents
 * it, and what kind of resource it is about, with that resource's page in the AWS console.
 *
 * Those checks send none of this. `DashboardData.SecurityFinding` has no remediation field, so only live
 * findings (Security Hub, GuardDuty, Config, the XamOps detectors) carry a remediation, appended to their
 * description as "Remediation: …". A guide is found by the finding's description exactly as
 * `SecurityService` writes it, or by pattern for the open security group check, whose text ends with the
 * port. A new or reworded check therefore has no guide until it is added here.
 *
 * CLI commands are Bash. Values taken from the finding are shell-quoted when they need it; what the
 * finding cannot supply is an `<angle-bracket>` placeholder, to replace before running.
 */

export interface GuideContext {
  /** The affected resource. Absent on a misconfiguration, which spans many resources. */
  resourceId?: string | null;
  region?: string | null;
  accountId?: string | null;
}

export interface CheckGuide {
  /** The kind of resource the check is about: "Security Group", "IAM User"… */
  resourceType: string;
  /** What the finding means and why it matters. */
  description: string;
  /** How to fix it, in order. */
  steps: readonly string[];
  /** The AWS CLI fix for the context, or null when the fix has no CLI form. */
  cli: string | null;
  /** True when the CLI names the resource, so a resource's own drawer can fill it in. */
  cliPerResource: boolean;
  learnMore: string | null;
  /** The resource's page in the AWS console, or null when the context can't name one. */
  consoleUrl: string | null;
}

interface CliValues {
  /** The resource id, shell-quoted, or `<placeholder>`. */
  id: (placeholder: string) => string;
  /** The raw resource id when it matches `pattern`, else `<placeholder>`: for an id inside quoted text. */
  rawId: (placeholder: string, pattern: RegExp) => string;
  /** The region, or `<region>`. */
  region: () => string;
  account: string;
  /** The port(s) the description names ("22", "30000-32767", "ALL"), or null. */
  port: string | null;
}

interface GuideDef {
  descriptions: ReadonlyArray<string | RegExp>;
  resourceType: string;
  description: string;
  steps: readonly string[];
  cli?: (values: CliValues) => string;
  learnMore?: string;
  console?: (resourceId: string | null, region: string | null) => string | null;
}

// ─── Values ──────────────────────────────────────────────────────────────────

const SHELL_SAFE = /^[A-Za-z0-9_@%+=:,./-]+$/;

/** A value as one Bash word: unchanged when it is plain, else single-quoted. */
export function shellQuote(value: string): string {
  return SHELL_SAFE.test(value) ? value : `'${value.replace(/'/g, `'\\''`)}'`;
}

/** An AWS region code. "Global", the scan's label for IAM and the account, is not one. */
const REGION = /^[a-z]{2}(-[a-z]+)+-\d+$/;
const regionOf = (region: string | null | undefined): string | null => {
  const value = (region ?? '').trim();
  return REGION.test(value) ? value : null;
};

const blankToNull = (value: string | null | undefined): string | null => {
  const trimmed = (value ?? '').trim();
  return trimmed === '' ? null : trimmed;
};

const S3_BUCKET = /^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/;
const IAM_NAME = /^[\w+=,.@-]{1,128}$/;

// ─── Console pages ───────────────────────────────────────────────────────────

const enc = encodeURIComponent;
const IAM_CONSOLE = 'https://console.aws.amazon.com/iam/home';

const regional = (path: (base: string, region: string, id: string) => string) =>
  (id: string | null, region: string | null): string | null =>
    (id && region ? path(`https://${region}.console.aws.amazon.com`, region, enc(id)) : null);

const CONSOLE = {
  securityGroup: regional((base, r, id) => `${base}/ec2/home?region=${r}#SecurityGroup:groupId=${id}`),
  vpc: regional((base, r, id) => `${base}/vpcconsole/home?region=${r}#VpcDetails:VpcId=${id}`),
  rds: regional((base, r, id) => `${base}/rds/home?region=${r}#database:id=${id}`),
  kms: regional((base, r, id) => `${base}/kms/home?region=${r}#/kms/keys/${id}`),
  s3: (id: string | null, region: string | null) =>
    (id ? `https://s3.console.aws.amazon.com/s3/buckets/${enc(id)}${region ? `?region=${region}` : ''}` : null),
  iamUser: (id: string | null) => (id ? `${IAM_CONSOLE}#/users/details/${enc(id)}?section=security_credentials` : null),
  iamRole: (id: string | null) => (id ? `${IAM_CONSOLE}#/roles/details/${enc(id)}` : null),
  iamPolicies: () => `${IAM_CONSOLE}#/policies`,
  accountSettings: () => `${IAM_CONSOLE}#/account_settings`,
  rootCredentials: () => `${IAM_CONSOLE}#/security_credentials`,
  trails: (_id: string | null, region: string | null) => (region
    ? `https://${region}.console.aws.amazon.com/cloudtrail/home?region=${region}#/trails`
    : 'https://console.aws.amazon.com/cloudtrail/home#/trails'),
};

// ─── Shared CLI pieces ───────────────────────────────────────────────────────

const BLOCK_PUBLIC_ACCESS = 'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true';
const SSE_S3 = `'{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'`;

/** The JMESPath filter that narrows a security group's rules to the port(s) the finding names. */
function portFilter(port: string | null): string {
  if (port === 'ALL') return ' && IpProtocol==`"-1"`';
  const single = port?.match(/^(\d+)$/);
  if (single) return ` && FromPort==\`${single[1]}\` && ToPort==\`${single[1]}\``;
  const range = port?.match(/^(\d+)-(\d+)$/);
  if (range) return ` && FromPort==\`${range[1]}\` && ToPort==\`${range[2]}\``;
  return '';
}

// ─── The guides ──────────────────────────────────────────────────────────────

const CLOUDTRAIL_LEARN_MORE = 'https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-create-and-update-a-trail.html';

const GUIDES: readonly GuideDef[] = [
  {
    // CIS 1.2
    descriptions: ['User has console access but MFA is not enabled.'],
    resourceType: 'IAM User',
    description: 'The user can sign in to the AWS console with a password alone, so a leaked or guessed password '
      + 'is enough to act with all of the user\'s permissions.',
    steps: [
      'Open IAM > Users > the user > Security credentials.',
      'Under Multi-factor authentication (MFA), choose Assign MFA device and register a passkey, an authenticator app or a hardware key.',
      'To require MFA for everyone, attach a policy that denies actions when aws:MultiFactorAuthPresent is false.',
    ],
    cli: v => [
      `aws iam create-virtual-mfa-device --virtual-mfa-device-name ${v.id('user-name')} --outfile ${v.id('user-name')}-mfa.png --bootstrap-method QRCodePNG`,
      `aws iam enable-mfa-device --user-name ${v.id('user-name')} --serial-number arn:aws:iam::${v.account}:mfa/${v.id('user-name')} --authentication-code1 <code-1> --authentication-code2 <code-2>`,
    ].join('\n'),
    learnMore: 'https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_mfa_enable_virtual.html',
    console: CONSOLE.iamUser,
  },
  {
    // CIS 2.1.2
    descriptions: ['Public Access Block is not fully enabled.'],
    resourceType: 'S3 Bucket',
    description: 'At least one of the bucket\'s four Block Public Access settings is off, so a bucket policy or ACL '
      + 'can make the bucket or its objects public.',
    steps: [
      'Confirm the bucket does not need to be public. To serve a website, put CloudFront with origin access control in front of a private bucket instead.',
      'Turn on all four Block Public Access settings on the bucket\'s Permissions tab.',
      'Consider turning them on for the whole account too, under S3 > Block Public Access settings for this account.',
    ],
    cli: v => `aws s3api put-public-access-block --region ${v.region()} --bucket ${v.id('bucket-name')} --public-access-block-configuration ${BLOCK_PUBLIC_ACCESS}`,
    learnMore: 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-control-block-public-access.html',
    console: CONSOLE.s3,
  },
  {
    // CIS 2.1.2
    descriptions: ['Bucket ACL grants public access.'],
    resourceType: 'S3 Bucket',
    description: 'The bucket ACL grants access to AllUsers (anyone on the internet) or AuthenticatedUsers (anyone '
      + 'with an AWS account).',
    steps: [
      'Move any access that is really needed into the bucket policy, then reset the bucket ACL to private.',
      'Set Object Ownership to Bucket owner enforced, which turns ACLs off for the bucket.',
      'Turn on Block Public Access for the bucket.',
    ],
    cli: v => [
      `aws s3api put-bucket-acl --region ${v.region()} --bucket ${v.id('bucket-name')} --acl private`,
      `aws s3api put-bucket-ownership-controls --region ${v.region()} --bucket ${v.id('bucket-name')} --ownership-controls 'Rules=[{ObjectOwnership=BucketOwnerEnforced}]'`,
      `aws s3api put-public-access-block --region ${v.region()} --bucket ${v.id('bucket-name')} --public-access-block-configuration ${BLOCK_PUBLIC_ACCESS}`,
    ].join('\n'),
    learnMore: 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/about-object-ownership.html',
    console: CONSOLE.s3,
  },
  {
    // CIS 4.1. The description ends with the port(s), the pattern's one group.
    descriptions: [/^Allows inbound traffic from anywhere \(0\.0\.0\.0\/0\) on port\(s\) (\S+)$/],
    resourceType: 'Security Group',
    description: 'An inbound rule accepts traffic from every IPv4 address (0.0.0.0/0), so whatever listens on that '
      + 'port is reachable from the whole internet. Internet-wide scanners find open ports within minutes.',
    steps: [
      'Find the inbound rule that allows 0.0.0.0/0 on the port. The first command lists such rules with their IDs.',
      'If the port does not need to be public, delete the rule, or narrow its source to known IP ranges or to another security group.',
      'For remote administration (SSH 22, RDP 3389), use Systems Manager Session Manager or a VPN instead of an open port.',
    ],
    cli: v => [
      `# Inbound rules open to 0.0.0.0/0 on port(s) ${v.port ?? '<port>'}, with their rule IDs`,
      `aws ec2 describe-security-group-rules --region ${v.region()} --filters Name=group-id,Values=${v.id('security-group-id')} --query 'SecurityGroupRules[?IsEgress==\`false\` && CidrIpv4==\`"0.0.0.0/0"\`${portFilter(v.port)}].[SecurityGroupRuleId,IpProtocol,FromPort,ToPort]' --output table`,
      '# Remove a rule by its ID',
      `aws ec2 revoke-security-group-ingress --region ${v.region()} --group-id ${v.id('security-group-id')} --security-group-rule-ids <rule-id>`,
    ].join('\n'),
    learnMore: 'https://docs.aws.amazon.com/vpc/latest/userguide/security-group-rules.html',
    console: CONSOLE.securityGroup,
  },
  {
    // CIS 2.9
    descriptions: ['VPC does not have Flow Logs enabled.'],
    resourceType: 'VPC',
    description: 'No flow log records the VPC\'s traffic, so there is no record of accepted or rejected connections '
      + 'to investigate an incident with.',
    steps: [
      'Choose a destination: an S3 bucket, or a CloudWatch Logs group with an IAM role that can write to it.',
      'Create a flow log for the VPC that captures ALL traffic (CIS asks for REJECT at least).',
      'Set a retention period or a lifecycle rule on the destination.',
    ],
    cli: v => `aws ec2 create-flow-logs --region ${v.region()} --resource-type VPC --resource-ids ${v.id('vpc-id')} --traffic-type ALL --log-destination-type s3 --log-destination arn:aws:s3:::<log-bucket>`,
    learnMore: 'https://docs.aws.amazon.com/vpc/latest/userguide/flow-logs.html',
    console: CONSOLE.vpc,
  },
  {
    // CIS 2.1 and SOC2 CC7.1
    descriptions: [
      'No CloudTrail trails are configured for the account.',
      'No CloudTrail trails configured, violating SOC2 monitoring requirements.',
    ],
    resourceType: 'AWS Account',
    description: 'The account has no CloudTrail trail. AWS keeps only 90 days of event history, which can\'t be '
      + 'retained longer, searched across accounts or alerted on.',
    steps: [
      'Create a trail that applies to all regions and delivers to an S3 bucket. The console creates the bucket and its policy for you; with the CLI, the bucket needs a policy that lets CloudTrail write to it.',
      'Turn on log file validation, and send the trail to CloudWatch Logs if you want alerts on it.',
      'Check that the trail\'s status shows Logging: on.',
    ],
    cli: () => [
      'aws cloudtrail create-trail --region <home-region> --name <trail-name> --s3-bucket-name <log-bucket> --is-multi-region-trail --enable-log-file-validation',
      'aws cloudtrail start-logging --region <home-region> --name <trail-name>',
    ].join('\n'),
    learnMore: CLOUDTRAIL_LEARN_MORE,
    console: CONSOLE.trails,
  },
  {
    // CIS 2.1 and SOC2 CC7.1
    descriptions: [
      'No active, multi-region CloudTrail trail found.',
      'No properly configured multi-region CloudTrail with log file validation found, violating SOC2 monitoring requirements.',
    ],
    resourceType: 'AWS Account',
    description: 'No trail is at once logging, applied to all regions and validating its log files, so activity in '
      + 'some regions goes unrecorded or its record can\'t be proven intact.',
    steps: [
      'Pick the trail to keep, or create one.',
      'Make it apply to all regions and turn on log file validation. A single-region trail can only be converted with the CLI, from its home region.',
      'Start logging if it is stopped.',
    ],
    cli: () => [
      'aws cloudtrail update-trail --region <home-region> --name <trail-name> --is-multi-region-trail --enable-log-file-validation',
      'aws cloudtrail start-logging --region <home-region> --name <trail-name>',
    ].join('\n'),
    learnMore: 'https://docs.aws.amazon.com/awscloudtrail/latest/userguide/receive-cloudtrail-log-files-from-multiple-regions.html',
    console: CONSOLE.trails,
  },
  {
    // SOC2 CC7.1
    descriptions: ['CloudTrail log file validation is not enabled, reducing SOC2 monitoring integrity.'],
    resourceType: 'CloudTrail Trail',
    description: 'Without log file validation the trail writes no digest files, so nobody can prove its logs were '
      + 'not changed or deleted after delivery.',
    steps: [
      'Turn on log file validation for the trail (Edit > General details > Log file validation).',
      'From then on, aws cloudtrail validate-logs can check any period of the trail\'s logs.',
    ],
    cli: v => `aws cloudtrail update-trail --region ${v.region()} --name ${v.id('trail-name')} --enable-log-file-validation`,
    learnMore: 'https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-log-file-validation-enabling.html',
    console: CONSOLE.trails,
  },
  {
    // Custom IAM-001 and IAM-002
    descriptions: [
      'Role has never been used and was created over 90 days ago.',
      'Role has not been used in over 90 days.',
    ],
    resourceType: 'IAM Role',
    description: 'The role has not been used for more than 90 days, yet keeps its permissions and its trust policy, '
      + 'which widen what a compromised principal could reach.',
    steps: [
      'Check the role\'s last activity and its trust policy to confirm nothing still assumes it, such as a CloudFormation stack, a Lambda function or an EC2 instance profile.',
      'If it is unused, remove it from its instance profiles, detach its policies, then delete it.',
      'If it has to stay, tag it with the reason so later reviews can skip it.',
    ],
    cli: v => [
      `aws iam get-role --role-name ${v.id('role-name')} --query 'Role.RoleLastUsed'`,
      `aws iam list-instance-profiles-for-role --role-name ${v.id('role-name')}`,
      `aws iam list-attached-role-policies --role-name ${v.id('role-name')}`,
    ].join('\n'),
    learnMore: 'https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_manage_delete.html',
    console: CONSOLE.iamRole,
  },
  {
    // SOC2 CC6.1
    descriptions: ['Root user has active access keys, violating SOC2 access control requirements.'],
    resourceType: 'Root User',
    description: 'The root user has access keys. No IAM policy can limit the root user, so whoever holds the keys '
      + 'has full control of the account.',
    steps: [
      'Find out what uses the keys. The IAM credential report shows when each root key was last used.',
      'Move that workload to an IAM role, or a user with only the permissions it needs.',
      'Sign in as the root user, open Security credentials, then deactivate and delete the access keys.',
    ],
    cli: () => [
      'aws iam generate-credential-report',
      '# The first row after the header is the root user',
      'aws iam get-credential-report --query Content --output text | base64 --decode | head -n 2',
    ].join('\n'),
    learnMore: 'https://docs.aws.amazon.com/IAM/latest/UserGuide/id_root-user_manage_delete-key.html',
    console: CONSOLE.rootCredentials,
  },
  {
    // SOC2 CC6.1
    descriptions: ['User has both console and programmatic access, violating SOC2 access segregation principles.'],
    resourceType: 'IAM User',
    description: 'The same user has a console password and access keys, so one identity serves a person and a '
      + 'program, and leaking either credential exposes both kinds of access.',
    steps: [
      'Decide whether the user is a person or a workload.',
      'For a person: delete the access keys and use IAM Identity Center (aws sso login) or other temporary credentials for the CLI.',
      'For a workload: delete the console password, and move it to a role or to a user without console access.',
    ],
    cli: v => [
      `aws iam list-access-keys --user-name ${v.id('user-name')}`,
      '# A person: deactivate, then delete, each key',
      `aws iam update-access-key --user-name ${v.id('user-name')} --access-key-id <access-key-id> --status Inactive`,
      `aws iam delete-access-key --user-name ${v.id('user-name')} --access-key-id <access-key-id>`,
      '# A workload: remove the console password instead',
      `aws iam delete-login-profile --user-name ${v.id('user-name')}`,
    ].join('\n'),
    learnMore: 'https://docs.aws.amazon.com/IAM/latest/UserGuide/best-practices.html',
    console: CONSOLE.iamUser,
  },
  {
    // SOC2 CC6.7
    descriptions: ['S3 bucket does not have encryption enabled, violating SOC2 data protection requirements.'],
    resourceType: 'S3 Bucket',
    description: 'The bucket has no default encryption configuration, so objects can be stored unencrypted at rest.',
    steps: [
      'Set default encryption on the bucket (Properties > Default encryption): SSE-S3, or SSE-KMS when you need key-level access control and audit.',
      'Default encryption applies to new objects. Copy existing objects onto themselves if they must be encrypted too.',
    ],
    cli: v => `aws s3api put-bucket-encryption --region ${v.region()} --bucket ${v.id('bucket-name')} --server-side-encryption-configuration ${SSE_S3}`,
    learnMore: 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/default-bucket-encryption.html',
    console: CONSOLE.s3,
  },
  {
    // SOC2 CC6.7
    descriptions: ['S3 bucket encryption configuration could not be verified, violating SOC2 data protection requirements.'],
    resourceType: 'S3 Bucket',
    description: 'The scan could not read the bucket\'s encryption configuration, so it can\'t confirm that the '
      + 'bucket encrypts its objects.',
    steps: [
      'Check the bucket\'s default encryption (Properties > Default encryption).',
      'If it is off, turn it on (SSE-S3 or SSE-KMS).',
      'If it is on, allow s3:GetEncryptionConfiguration on the bucket for the role XamOps scans with, so the next scan can confirm it.',
    ],
    cli: v => [
      `aws s3api get-bucket-encryption --region ${v.region()} --bucket ${v.id('bucket-name')}`,
      `aws s3api put-bucket-encryption --region ${v.region()} --bucket ${v.id('bucket-name')} --server-side-encryption-configuration ${SSE_S3}`,
    ].join('\n'),
    learnMore: 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/default-bucket-encryption.html',
    console: CONSOLE.s3,
  },
  {
    // SOC2 CC6.7. The second text is written when the scan could not read a bucket policy at all.
    descriptions: [
      'S3 bucket does not enforce HTTPS-only access, violating SOC2 data transmission requirements.',
      'S3 bucket does not have a secure transport policy, violating SOC2 data transmission requirements.',
    ],
    resourceType: 'S3 Bucket',
    description: 'The bucket policy does not deny plain HTTP requests, so data can travel to and from the bucket '
      + 'unencrypted.',
    steps: [
      'Add a statement to the bucket policy that denies every request where aws:SecureTransport is false.',
      'Add it to the existing policy rather than replacing it: put-bucket-policy overwrites the whole policy. If the bucket has no policy yet, create one with only this statement.',
    ],
    cli: v => {
      const bucket = v.rawId('bucket-name', S3_BUCKET);
      return [
        `aws s3api get-bucket-policy --region ${v.region()} --bucket ${v.id('bucket-name')} --query Policy --output text > policy.json`,
        '# Add this statement to the Statement list in policy.json:',
        '#   {"Sid": "DenyInsecureTransport", "Effect": "Deny", "Principal": "*", "Action": "s3:*",',
        `#    "Resource": ["arn:aws:s3:::${bucket}", "arn:aws:s3:::${bucket}/*"],`,
        '#    "Condition": {"Bool": {"aws:SecureTransport": "false"}}}',
        `aws s3api put-bucket-policy --region ${v.region()} --bucket ${v.id('bucket-name')} --policy file://policy.json`,
      ].join('\n');
    },
    learnMore: 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/security-best-practices.html',
    console: CONSOLE.s3,
  },
  {
    // SOC2 CC6.7
    descriptions: ['RDS instance does not have storage encryption enabled, violating SOC2 data protection requirements.'],
    resourceType: 'RDS Instance',
    description: 'The database\'s storage, automated backups, read replicas and snapshots are not encrypted at rest.',
    steps: [
      'Encryption can\'t be turned on for an existing instance, so encrypt a copy of it.',
      'Take a snapshot, copy it with a KMS key (the copy is encrypted), and restore a new instance from the copy into the same subnet group and security groups.',
      'Point applications at the new instance, then delete the old one once nothing uses it.',
    ],
    cli: v => {
      const db = v.id('db-instance-id');
      return [
        `aws rds create-db-snapshot --region ${v.region()} --db-instance-identifier ${db} --db-snapshot-identifier ${db}-unencrypted`,
        `aws rds wait db-snapshot-available --region ${v.region()} --db-snapshot-identifier ${db}-unencrypted`,
        `aws rds copy-db-snapshot --region ${v.region()} --source-db-snapshot-identifier ${db}-unencrypted --target-db-snapshot-identifier ${db}-encrypted --kms-key-id alias/aws/rds`,
        `aws rds wait db-snapshot-available --region ${v.region()} --db-snapshot-identifier ${db}-encrypted`,
        `aws rds restore-db-instance-from-db-snapshot --region ${v.region()} --db-instance-identifier ${db}-encrypted --db-snapshot-identifier ${db}-encrypted --db-subnet-group-name <db-subnet-group> --vpc-security-group-ids <security-group-id>`,
      ].join('\n');
    },
    learnMore: 'https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.Encryption.html',
    console: CONSOLE.rds,
  },
  {
    // SOC2 CC6.8
    descriptions: ['KMS key does not have automatic rotation enabled, violating SOC2 key management requirements.'],
    resourceType: 'KMS Key',
    description: 'Automatic rotation is off for the key, so the same key material stays in use indefinitely.',
    steps: [
      'Turn on automatic key rotation on the key\'s Key rotation tab. AWS keeps the earlier key material, so data encrypted before a rotation stays readable.',
      'Automatic rotation covers symmetric keys with AWS-generated material. For other keys, create a new key and move the alias to it.',
    ],
    cli: v => `aws kms enable-key-rotation --region ${v.region()} --key-id ${v.id('key-id')}`,
    learnMore: 'https://docs.aws.amazon.com/kms/latest/developerguide/rotate-keys.html',
    console: CONSOLE.kms,
  },
  {
    // SOC2 CC6.3
    descriptions: [
      'Password policy minimum length is less than 14 characters, not meeting SOC2 access security standards.',
      'Password policy does not require character complexity, violating SOC2 access security requirements.',
      'Password policy does not enforce password expiration within 90 days, violating SOC2 access security requirements.',
      'No password policy is configured, violating SOC2 access security requirements.',
    ],
    resourceType: 'AWS Account',
    description: 'The account password policy is missing or weaker than the standard: at least 14 characters, '
      + 'upper and lower case letters, numbers and symbols, and expiry within 90 days.',
    steps: [
      'Set the account password policy under IAM > Account settings to the standard above, and prevent reuse of recent passwords.',
      'The command below sets every value at once, because it replaces the whole policy.',
    ],
    cli: () => 'aws iam update-account-password-policy --minimum-password-length 14 --require-uppercase-characters --require-lowercase-characters --require-numbers --require-symbols --max-password-age 90 --password-reuse-prevention 24 --allow-users-to-change-password',
    learnMore: 'https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_passwords_account-policy.html',
    console: CONSOLE.accountSettings,
  },
  {
    // SOC2 CC6.3
    descriptions: ['Custom IAM policy grants overly broad permissions (*), violating SOC2 least privilege principles.'],
    resourceType: 'IAM Policy',
    description: 'A customer managed policy allows "*", so everyone it is attached to can do far more than their '
      + 'work needs.',
    steps: [
      'List the users, groups and roles the policy is attached to.',
      'Replace the "*" actions and resources with the ones they actually use. IAM Access Analyzer can generate a policy from their CloudTrail activity.',
      'Save the narrowed policy as a new version and set it as the default.',
    ],
    cli: v => [
      `aws iam list-policies --scope Local --query "Policies[?PolicyName=='${v.rawId('policy-name', IAM_NAME)}'].[Arn,DefaultVersionId]" --output text`,
      'aws iam list-entities-for-policy --policy-arn <policy-arn>',
      'aws iam get-policy-version --policy-arn <policy-arn> --version-id <version-id>',
    ].join('\n'),
    learnMore: 'https://docs.aws.amazon.com/IAM/latest/UserGuide/best-practices.html#grant-least-privilege',
    console: CONSOLE.iamPolicies,
  },
];

// ─── Lookup ──────────────────────────────────────────────────────────────────

/** The guide for a description, with the port the open security group text ends with. */
function match(description: string | null | undefined): { def: GuideDef; port: string | null } | null {
  const text = (description ?? '').trim();
  if (text === '') return null;
  for (const def of GUIDES) {
    for (const candidate of def.descriptions) {
      if (typeof candidate === 'string') {
        if (candidate === text) return { def, port: null };
        continue;
      }
      const found = candidate.exec(text);
      if (found) return { def, port: found[1] ?? null };
    }
  }
  return null;
}

/**
 * The guide for a finding's description, filled in from the context: its CLI names the resource, its
 * region and the account where they are known, and `<placeholders>` where not. Null when no guide covers
 * the description (a live finding, a check this file does not list).
 */
export function guideFor(description: string | null | undefined, context: GuideContext = {}): CheckGuide | null {
  const found = match(description);
  if (!found) return null;
  const { def, port } = found;
  const resourceId = blankToNull(context.resourceId);
  const region = regionOf(context.region);
  let perResource = false;
  const values: CliValues = {
    id: placeholder => {
      perResource = true;
      return resourceId === null ? `<${placeholder}>` : shellQuote(resourceId);
    },
    rawId: (placeholder, pattern) => {
      perResource = true;
      return resourceId !== null && pattern.test(resourceId) ? resourceId : `<${placeholder}>`;
    },
    region: () => {
      perResource = true;
      return region ?? '<region>';
    },
    account: blankToNull(context.accountId) ?? '<account-id>',
    port,
  };
  const cli = def.cli ? def.cli(values) : null;
  return {
    resourceType: def.resourceType,
    description: def.description,
    steps: def.steps,
    cli,
    cliPerResource: perResource,
    learnMore: def.learnMore ?? null,
    consoleUrl: def.console ? def.console(resourceId, region) : null,
  };
}

/** The kind of resource a finding is about: its guide's, else its category, else "Resource". */
export function resourceTypeFor(description: string | null | undefined, category: string | null | undefined): string {
  return match(description)?.def.resourceType ?? blankToNull(category) ?? 'Resource';
}

/** A Security Hub control id, "S3.8" or "Config.1": the service, then the control's number. */
const SECURITY_HUB_CONTROL = /^([A-Za-z][A-Za-z0-9]*)\.(\d+)$/;

/**
 * Where AWS documents the fix for a live finding, which brings at most a one-line remediation. A
 * Security Hub control links to its own Remediation section (`s3-controls.html#s3-8-remediation`, the
 * pattern of every controls page); a GuardDuty finding, which brings none, to GuardDuty's remediation
 * guide. Null for other sources, whose control ids are rule names the docs don't key on.
 */
export function liveLearnMore(framework: string | null | undefined, controlId: string | null | undefined): string | null {
  const source = blankToNull(framework);
  if (source === 'Security Hub') {
    const control = SECURITY_HUB_CONTROL.exec(blankToNull(controlId) ?? '');
    if (!control) return null;
    const service = control[1].toLowerCase();
    return `https://docs.aws.amazon.com/securityhub/latest/userguide/${service}-controls.html#${service}-${control[2]}-remediation`;
  }
  if (source === 'GuardDuty') return 'https://docs.aws.amazon.com/guardduty/latest/ug/guardduty_remediate.html';
  return null;
}
