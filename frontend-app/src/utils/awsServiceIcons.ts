/**
 * Single source of truth for AWS service icons.
 *
 * The backend hands the frontend a *display label*, not a type token:
 * `AwsResourceTypeMapper.displayTypeFor()` turns Resource Explorer's
 * `glue:table` into `"AWS Glue Table"`, and only that string reaches the API
 * (`ResourceDto.type` -> `ServiceGroupDto.serviceType`). So icons have to be
 * keyed on the label.
 *
 * That used to be a flat exact-match dictionary inside CloudListPage, which is
 * why so many rows rendered the generic AWS logo: the mapper emits ~115 distinct
 * labels, the dictionary held 63, and the mapper's `humanise()` fallback means
 * the label set has an *open tail* — a type AWS ships next month arrives as
 * `"Aoss Collection"` with nobody having written a mapping for it. A dictionary
 * alone can therefore never be complete.
 *
 * Resolution is three passes, cheapest first:
 *
 *   1. exact label    — `"AWS Glue Table"`
 *   2. normalised     — case/space/punctuation-insensitive, so `"Route53 Zone"`,
 *                       `"route 53 zone"` and `"Route 53 Zone"` all land
 *   3. service family — ordered substring rules, so an unmapped sub-resource
 *                       inherits its parent service's icon (`"RDS Option Group"`
 *                       -> the RDS icon) and new labels degrade to something
 *                       recognisable instead of the generic logo
 *
 * Only pass 3 can be wrong, and it is wrong in a useful direction: a Glue
 * sub-resource showing the Glue icon still tells you which service you are
 * looking at.
 */

/** Shown only when all three passes miss. */
export const DEFAULT_AWS_ICON = '/icons/default-icon.png';

const BASE = '/icons';
/** Icons authored for this repo, in a subfolder to keep them apart from vendor assets. */
const OWN = '/icons/aws';

/**
 * Exact display label -> icon path.
 *
 * The AWS entries are `AwsResourceTypeMapper.TYPE_TO_DISPLAY` values; the
 * trailing block is labels other pages (CloudMap, Waste, the dashboard
 * inventory widget) built their own maps around. Sub-resources deliberately
 * point at their parent service's icon where AWS ships no distinct one — that is
 * a decision, not a gap.
 */
export const AWS_SERVICE_ICONS: Record<string, string> = {
  // ── Compute ────────────────────────────────────────────────────────────────
  'EC2 Instance': `${BASE}/ec2-instances.png`,
  'EBS Volume': `${BASE}/aws-ebs.png`,
  'EBS Snapshot': `${BASE}/snapshot.png`,
  'AMI': `${BASE}/ami.png`,
  'Network Interface': `${BASE}/eni.png`,
  'Elastic IP': `${BASE}/elastic-ip.png`,
  'Security Group': `${BASE}/security-group.png`,
  'Launch Template': `${OWN}/launch-template.svg`,
  'EC2 Key Pair': `${OWN}/key-pair.svg`,
  'Lambda Function': `${BASE}/aws-lambda.png`,
  'Auto Scaling Group': `${BASE}/autoscaling.png`,
  'Lightsail Instance': `${BASE}/lightsail.png`,
  'Elastic Beanstalk Environment': `${BASE}/elasticbeanstalk.png`,
  'App Runner Autoscaling Config': `${OWN}/app-runner.svg`,

  // ── Containers ─────────────────────────────────────────────────────────────
  'EKS Cluster': `${BASE}/aws-eks.png`,
  'ECS Cluster': `${BASE}/aws-ecs.png`,
  'ECS Service': `${BASE}/aws-ecs.png`,
  'ECS Task Definition': `${BASE}/aws-ecs.png`,
  'ECR Repository': `${BASE}/ecr-repository.png`,

  // ── Networking & content delivery ──────────────────────────────────────────
  'VPC': `${BASE}/vpc.png`,
  'Subnet': `${OWN}/subnet.svg`,
  'Internet Gateway': `${BASE}/internet-gateway.png`,
  'NAT Gateway': `${BASE}/nat-gateway.png`,
  'Route Table': `${OWN}/route-table.svg`,
  'Network ACL': `${OWN}/network-acl.svg`,
  'VPC Endpoint': `${OWN}/vpc-endpoint.svg`,
  'Transit Gateway': `${OWN}/transit-gateway.svg`,
  'VPC Flow Log': `${OWN}/vpc-flow-log.svg`,
  'DHCP Options Set': `${OWN}/dhcp-options.svg`,
  'Load Balancer': `${BASE}/load-balanceraws.png`,
  'Load Balancer Listener': `${BASE}/load-balanceraws.png`,
  'Load Balancer Listener Rule': `${BASE}/load-balanceraws.png`,
  'Target Group': `${OWN}/target-group.svg`,
  'CloudFront': `${BASE}/cloudfront.png`,
  'Route 53 Zone': `${BASE}/aws-route53.png`,
  'API Gateway': `${BASE}/api-gateway.png`,
  'Global Accelerator': `${OWN}/global-accelerator.svg`,

  // ── Storage ────────────────────────────────────────────────────────────────
  'S3 Bucket': `${BASE}/s3-bucket.png`,
  'EFS File System': `${BASE}/efs.png`,
  'FSx File System': `${OWN}/fsx.svg`,
  'Backup Vault': `${OWN}/backup-vault.svg`,

  // ── Databases ──────────────────────────────────────────────────────────────
  'RDS Instance': `${BASE}/rds-instance.png`,
  'RDS Cluster (Aurora)': `${OWN}/aurora.svg`,
  'RDS Snapshot': `${BASE}/rds-instance.png`,
  'RDS Cluster Snapshot': `${BASE}/rds-instance.png`,
  'RDS Subnet Group': `${BASE}/rds-instance.png`,
  'RDS Parameter Group': `${BASE}/rds-instance.png`,
  'RDS Option Group': `${BASE}/rds-instance.png`,
  'RDS Security Group': `${BASE}/rds-instance.png`,
  'DynamoDB Table': `${BASE}/dynamodb.png`,
  'ElastiCache Cluster': `${BASE}/elasticache-cluster.png`,
  'ElastiCache Replication Group': `${BASE}/elasticache.png`,
  'ElastiCache User': `${BASE}/elasticache.png`,
  'MemoryDB Cluster': `${OWN}/memorydb.svg`,
  'MemoryDB User': `${OWN}/memorydb.svg`,
  'MemoryDB ACL': `${OWN}/memorydb.svg`,
  'MemoryDB Parameter Group': `${OWN}/memorydb.svg`,
  'Redshift Cluster': `${OWN}/redshift.svg`,
  'Neptune Cluster': `${OWN}/neptune.svg`,
  'DocumentDB Cluster': `${OWN}/documentdb.svg`,
  'OpenSearch Domain': `${OWN}/opensearch.svg`,
  'MSK Cluster': `${OWN}/msk.svg`,

  // ── Messaging & integration ────────────────────────────────────────────────
  'SNS Topic': `${BASE}/sns-topic.png`,
  'SQS Queue': `${BASE}/sqs-queue.png`,
  'EventBridge Bus': `${BASE}/eventbridge.png`,
  'EventBridge Rule': `${BASE}/eventbridge.png`,
  'EventBridge API Destination': `${BASE}/eventbridge.png`,
  'EventBridge Connection': `${BASE}/eventbridge.png`,
  'AWS Step Functions': `${BASE}/step-functions.png`,
  'Kinesis Stream': `${BASE}/kinesis.png`,
  'Kinesis Firehose': `${OWN}/firehose.svg`,

  // ── Security, identity & compliance ────────────────────────────────────────
  'KMS': `${BASE}/kms.png`,
  'Secrets Manager Secret': `${OWN}/secrets-manager.svg`,
  'Certificate Manager': `${BASE}/certificate-manager.png`,
  'WAF': `${BASE}/waf.png`,
  'Amazon Cognito': `${BASE}/cognito.png`,
  'IAM Role': `${OWN}/iam-role.svg`,
  'IAM User': `${OWN}/iam-user.svg`,
  'IAM Policy': `${OWN}/iam-policy.svg`,
  'IAM Group': `${OWN}/iam-group.svg`,
  'IAM MFA Device': `${OWN}/iam-mfa.svg`,
  'IAM Instance Profile': `${OWN}/iam.svg`,

  // ── Management, governance & observability ─────────────────────────────────
  'CloudWatch Log Group': `${BASE}/cloudwatch-log-group.png`,
  'CloudWatch Alarm': `${OWN}/cloudwatch-alarm.svg`,
  'SSM Managed Instance': `${BASE}/ssm.png`,
  'SSM Parameter': `${BASE}/ssm.png`,
  'SSM Session': `${BASE}/ssm.png`,
  'CloudTrail': `${BASE}/cloudtrail.png`,
  'AWS Config': `${BASE}/Config.png`,
  'CloudFormation Stack': `${BASE}/cloudformation.png`,
  'CloudFormation StackSet': `${BASE}/cloudformation.png`,
  'X-Ray Sampling Rule': `${OWN}/xray.svg`,
  'Auditmanager Assessment': `${OWN}/audit-manager.svg`,
  'Budget': `${OWN}/budgets.svg`,

  // ── Data, analytics & AI ───────────────────────────────────────────────────
  'AWS Glue': `${BASE}/glue.png`,
  'AWS Glue Table': `${OWN}/glue-table.svg`,
  'Amazon Athena': `${BASE}/athena.png`,
  'Athena Data Catalog': `${OWN}/athena-catalog.svg`,
  'Aoss Collection': `${OWN}/opensearch.svg`,
  'DataZone Domain': `${BASE}/datazone.png`,
  'SageMaker': `${BASE}/sagemaker.png`,
  'SageMaker Endpoint': `${BASE}/sagemaker.png`,
  'Bedrock Provisioned Model': `${BASE}/bedrock.png`,

  // ── Developer tooling & front-end ──────────────────────────────────────────
  'CodeCommit Repository': `${BASE}/codecommit.png`,
  'CodeBuild Project': `${BASE}/codebuild.png`,
  'CodePipeline': `${BASE}/codepipeline.png`,
  'Amplify App': `${BASE}/amplify.png`,
  'Pinpoint Application': `${OWN}/pinpoint.svg`,

  // ── Labels other pages key on ──────────────────────────────────────────────
  // CloudMap, Waste and the dashboard inventory widget grew their own vocabulary
  // before the inventory plane settled on the mapper's. Keeping the aliases here
  // is what lets those pages share this map instead of forking it again.
  'Network Interface (ENI)': `${BASE}/eni.png`,
  'Snapshot': `${BASE}/snapshot.png`,
  'DB Snapshot': `${BASE}/rds-instance.png`,
  'Log Group': `${BASE}/cloudwatch-log-group.png`,
  'Lambda': `${BASE}/aws-lambda.png`,
  'EventBridge': `${BASE}/eventbridge.png`,
  'DynamoDB': `${BASE}/dynamodb.png`,
  'IAM': `${OWN}/iam.svg`,
  'Amazon ElastiCache': `${BASE}/elasticache.png`,
  'Security Hub': `${BASE}/security-hub.png`,
  'AWS Security Hub': `${BASE}/security-hub.png`,
  'Amazon CloudWatch Events': `${BASE}/cloudwatch-events.png`,
  'AWS WAF': `${BASE}/waf.png`,
  'WAF Web ACL': `${BASE}/waf.png`,
  'Bedrock': `${BASE}/bedrock.png`,
  'Amazon Textract': `${BASE}/textract.png`,
  'Textract Project': `${BASE}/textract.png`,
  'Amazon Elastic File System': `${BASE}/efs.png`,
  // The file on disk is system-manager.png. The old map pointed at
  // systems-manager.png and rendered a broken image, not even the default.
  'AWS Systems Manager': `${BASE}/system-manager.png`,
  'Amazon DataZone': `${BASE}/datazone.png`,
  'AWS Cloud Map': `${OWN}/cloud-map.svg`,
  'AWS Shield Protection': `${BASE}/shield.png`,
  'Organization Account': `${BASE}/organizations.png`,
  'Control Tower Control': `${BASE}/controltower.png`,
};

/**
 * A label reduced to its alphanumeric words, keeping track of where each word
 * begins in the concatenation.
 *
 * `key` is what pass 2 looks up. `wordStarts` is what stops pass 3 matching
 * across word seams: `"Glue Crawler"` concatenates to `gluecrawler`, which
 * contains `ecr` at offset 3 and would otherwise resolve to the ECR icon.
 * Requiring a rule to begin at a word start makes that impossible while still
 * letting multi-word service names match (`natgateway` over `["nat","gateway"]`).
 *
 * CamelCase counts as a word break, so `"DocumentDB Cluster"` yields
 * `["document", "db", "cluster"]` and `documentdb` still matches at offset 0.
 */
interface LabelKey {
  key: string;
  wordStarts: number[];
}

const labelKey = (label: string): LabelKey => {
  const words = label
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean);

  const wordStarts: number[] = [];
  let key = '';
  for (const word of words) {
    wordStarts.push(key.length);
    key += word;
  }
  return { key, wordStarts };
};

/** Pass 2 index, built once. Earlier keys win, so canonical labels beat aliases. */
const NORMALISED_INDEX: Record<string, string> = {};
for (const [label, icon] of Object.entries(AWS_SERVICE_ICONS)) {
  const { key } = labelKey(label);
  if (!(key in NORMALISED_INDEX)) {
    NORMALISED_INDEX[key] = icon;
  }
}

/**
 * Pass 3: service-family rules, matched as substrings of the normalised label.
 *
 * A rule matches only where it begins at a word start (see {@link labelKey}), so
 * short needles like `ecr` cannot fire from the middle of another word.
 *
 * Two things decide the winner, in this order:
 *
 *   1. **position** — the earliest matching word wins, because AWS labels lead
 *      with the service and trail with the qualifier. `"S3 Website Config"` is an
 *      S3 thing, not an AWS Config thing.
 *   2. **rule order** — breaks ties within the same word, so more specific rules
 *      must be listed first. `"App Runner Autoscaling Config"` starts with
 *      `apprunner`, which has to precede `autoscal`; `launchconfig` has to
 *      precede the bare `config`.
 */
const FAMILY_RULES: ReadonlyArray<readonly [string, string]> = [
  // Compound names first — each is a substring trap for a rule further down.
  ['apprunner', `${OWN}/app-runner.svg`],
  ['auditmanager', `${OWN}/audit-manager.svg`],
  ['gluetable', `${OWN}/glue-table.svg`],
  ['datacatalog', `${OWN}/athena-catalog.svg`],
  ['launchtemplate', `${OWN}/launch-template.svg`],
  ['keypair', `${OWN}/key-pair.svg`],
  ['targetgroup', `${OWN}/target-group.svg`],
  ['globalaccelerator', `${OWN}/global-accelerator.svg`],
  ['transitgateway', `${OWN}/transit-gateway.svg`],
  ['natgateway', `${BASE}/nat-gateway.png`],
  ['internetgateway', `${BASE}/internet-gateway.png`],
  ['vpcendpoint', `${OWN}/vpc-endpoint.svg`],
  ['privatelink', `${OWN}/vpc-endpoint.svg`],
  ['flowlog', `${OWN}/vpc-flow-log.svg`],
  ['routetable', `${OWN}/route-table.svg`],
  ['networkacl', `${OWN}/network-acl.svg`],
  ['networkinterface', `${BASE}/eni.png`],
  ['elasticip', `${BASE}/elastic-ip.png`],
  ['dhcp', `${OWN}/dhcp-options.svg`],
  ['secretsmanager', `${OWN}/secrets-manager.svg`],
  ['cloudwatchalarm', `${OWN}/cloudwatch-alarm.svg`],
  ['cloudwatchlog', `${BASE}/cloudwatch-log-group.png`],
  ['loggroup', `${BASE}/cloudwatch-log-group.png`],
  ['cloudwatchevent', `${BASE}/cloudwatch-events.png`],
  ['securityhub', `${BASE}/security-hub.png`],
  ['securitygroup', `${BASE}/security-group.png`],
  ['elasticbeanstalk', `${BASE}/elasticbeanstalk.png`],
  ['elasticfilesystem', `${BASE}/efs.png`],
  ['elasticache', `${BASE}/elasticache.png`],
  ['elasticloadbalanc', `${BASE}/load-balanceraws.png`],
  ['loadbalancer', `${BASE}/load-balanceraws.png`],
  ['cloudformation', `${BASE}/cloudformation.png`],
  ['cloudfront', `${BASE}/cloudfront.png`],
  ['cloudtrail', `${BASE}/cloudtrail.png`],
  ['cloudmap', `${OWN}/cloud-map.svg`],
  ['servicediscovery', `${OWN}/cloud-map.svg`],
  ['controltower', `${BASE}/controltower.png`],
  ['stepfunction', `${BASE}/step-functions.png`],
  ['statemachine', `${BASE}/step-functions.png`],
  ['codecommit', `${BASE}/codecommit.png`],
  ['codebuild', `${BASE}/codebuild.png`],
  ['codepipeline', `${BASE}/codepipeline.png`],
  ['documentdb', `${OWN}/documentdb.svg`],
  ['docdb', `${OWN}/documentdb.svg`],
  ['memorydb', `${OWN}/memorydb.svg`],
  ['opensearch', `${OWN}/opensearch.svg`],
  ['elasticsearch', `${OWN}/opensearch.svg`],
  ['aoss', `${OWN}/opensearch.svg`],
  ['route53', `${BASE}/aws-route53.png`],
  ['hostedzone', `${BASE}/aws-route53.png`],
  ['apigateway', `${BASE}/api-gateway.png`],
  ['eventbridge', `${BASE}/eventbridge.png`],
  ['firehose', `${OWN}/firehose.svg`],
  ['deliverystream', `${OWN}/firehose.svg`],
  ['certificate', `${BASE}/certificate-manager.png`],
  ['organization', `${BASE}/organizations.png`],
  ['mobiletargeting', `${OWN}/pinpoint.svg`],
  ['pinpoint', `${OWN}/pinpoint.svg`],
  ['sagemaker', `${BASE}/sagemaker.png`],
  ['textract', `${BASE}/textract.png`],
  ['bedrock', `${BASE}/bedrock.png`],
  ['datazone', `${BASE}/datazone.png`],
  ['lightsail', `${BASE}/lightsail.png`],
  ['snapshot', `${BASE}/snapshot.png`],
  // A launch configuration is an Auto Scaling concept, not AWS Config; this has
  // to precede the bare `config` rule below.
  ['launchconfig', `${BASE}/autoscaling.png`],
  ['autoscal', `${BASE}/autoscaling.png`],

  // Parent services. `rds` precedes `subnet` / `securitygroup` / `parametergroup`
  // so RDS sub-resources stay on the RDS icon.
  ['aurora', `${OWN}/aurora.svg`],
  ['rds', `${BASE}/rds-instance.png`],
  ['redshift', `${OWN}/redshift.svg`],
  ['neptune', `${OWN}/neptune.svg`],
  ['dynamodb', `${BASE}/dynamodb.png`],
  ['kafka', `${OWN}/msk.svg`],
  ['msk', `${OWN}/msk.svg`],
  ['backup', `${OWN}/backup-vault.svg`],
  ['fsx', `${OWN}/fsx.svg`],
  ['lambda', `${BASE}/aws-lambda.png`],
  ['eks', `${BASE}/aws-eks.png`],
  ['ecr', `${BASE}/ecr-repository.png`],
  ['ecs', `${BASE}/aws-ecs.png`],
  ['fargate', `${BASE}/aws-ecs.png`],
  ['subnet', `${OWN}/subnet.svg`],
  ['iam', `${OWN}/iam.svg`],
  ['cognito', `${BASE}/cognito.png`],
  ['kinesis', `${BASE}/kinesis.png`],
  ['athena', `${BASE}/athena.png`],
  ['glue', `${BASE}/glue.png`],
  ['budget', `${OWN}/budgets.svg`],
  ['xray', `${OWN}/xray.svg`],
  ['amplify', `${BASE}/amplify.png`],
  ['shield', `${BASE}/shield.png`],
  ['waf', `${BASE}/waf.png`],
  ['kms', `${BASE}/kms.png`],
  ['systemsmanager', `${BASE}/system-manager.png`],
  ['systemmanager', `${BASE}/system-manager.png`],
  ['ssm', `${BASE}/ssm.png`],
  ['config', `${BASE}/Config.png`],
  ['sns', `${BASE}/sns-topic.png`],
  ['sqs', `${BASE}/sqs-queue.png`],
  ['ebs', `${BASE}/aws-ebs.png`],
  ['s3', `${BASE}/s3-bucket.png`],
  ['efs', `${BASE}/efs.png`],
  ['vpc', `${BASE}/vpc.png`],
  ['ec2', `${BASE}/ec2-instances.png`],
];

/**
 * Icon for a service label, or `undefined` when nothing matches.
 *
 * Use this where the caller has a better fallback than a generic logo — CloudMap
 * draws a coloured initials glyph, the design palette hides the image entirely.
 */
export function lookupAwsServiceIcon(serviceType: string | null | undefined): string | undefined {
  if (!serviceType) {
    return undefined;
  }

  const exact = AWS_SERVICE_ICONS[serviceType];
  if (exact) {
    return exact;
  }

  const { key, wordStarts } = labelKey(serviceType);
  if (!key) {
    return undefined;
  }

  const normalised = NORMALISED_INDEX[key];
  if (normalised) {
    return normalised;
  }

  // Position first, rule order second — see the FAMILY_RULES doc comment.
  for (const start of wordStarts) {
    for (const [needle, icon] of FAMILY_RULES) {
      if (key.startsWith(needle, start)) {
        return icon;
      }
    }
  }

  return undefined;
}

/** Icon for a service label, falling back to the generic AWS mark. */
export function resolveAwsServiceIcon(serviceType: string | null | undefined): string {
  return lookupAwsServiceIcon(serviceType) ?? DEFAULT_AWS_ICON;
}
