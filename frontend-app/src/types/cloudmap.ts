/**
 * CloudMap Types
 *
 * Type definitions for the CloudMap network visualization feature
 */

export interface VPC {
  id: string;
  name: string;
  region: string;
}

export interface NodeData {
  id: string;
  label: string;
  type: string;
  parent?: string;
  [key: string]: any; // Additional properties from backend
}

export interface EdgeData {
  id: string;
  source: string;
  target: string;
  label?: string;
}

export interface GraphElement {
  group: 'nodes' | 'edges';
  data: NodeData | EdgeData;
  position?: { x: number; y: number };
}

export interface Blueprint {
  id: string;
  name: string;
  graphData: string; // JSON string of graph elements
  createdAt?: string;
  updatedAt?: string;
}

export type CloudMapMode = 'live' | 'design';

export interface NodeTypeInfo {
  icon: string;
  color: string;
  style: 'solid' | 'dashed';
  category: string;
}

export const NODE_TYPE_DATA: Record<string, NodeTypeInfo> = {
  'VPC': { icon: 'cloud', color: 'green', style: 'solid', category: 'Network' },
  'Availability Zone': { icon: 'layer-group', color: 'blue', style: 'dashed', category: 'Network' },
  'Subnet': { icon: 'network-wired', color: 'emerald', style: 'solid', category: 'Network' },
  'Security Group': { icon: 'shield-alt', color: 'yellow', style: 'solid', category: 'Security' },
  'Internet Gateway': { icon: 'globe', color: 'cyan', style: 'solid', category: 'Network' },
  'NAT Gateway': { icon: 'exchange-alt', color: 'indigo', style: 'solid', category: 'Network' },
  'Route 53 Zone': { icon: 'route', color: 'orange', style: 'solid', category: 'Network' },
  'EC2 Instance': { icon: 'server', color: 'orange', style: 'solid', category: 'Compute' },
  'Lambda Function': { icon: 'bolt', color: 'amber', style: 'solid', category: 'Compute' },
  'Auto Scaling Group': { icon: 'expand-arrows-alt', color: 'rose', style: 'solid', category: 'Compute' },
  'EKS Cluster': { icon: 'cubes', color: 'indigo', style: 'solid', category: 'Compute' },
  'Lightsail Instance': { icon: 'lightbulb', color: 'yellow', style: 'solid', category: 'Compute' },
  'SSM Managed Instance': { icon: 'tasks', color: 'slate', style: 'solid', category: 'Management' },
  'S3 Bucket': { icon: 'archive', color: 'teal', style: 'solid', category: 'Storage' },
  'EBS Volume': { icon: 'compact-disc', color: 'gray', style: 'solid', category: 'Storage' },
  'EFS File System': { icon: 'folder-open', color: 'lime', style: 'solid', category: 'Storage' },
  'RDS Instance': { icon: 'database', color: 'sky', style: 'solid', category: 'Database' },
  'DynamoDB Table': { icon: 'table', color: 'blue', style: 'solid', category: 'Database' },
  'ElastiCache Cluster': { icon: 'memory', color: 'red', style: 'solid', category: 'Database' },
  'Load Balancer': { icon: 'balance-scale', color: 'purple', style: 'solid', category: 'Network' },
  'SNS Topic': { icon: 'bullhorn', color: 'pink', style: 'solid', category: 'Messaging' },
  'SQS Queue': { icon: 'list-ol', color: 'fuchsia', style: 'solid', category: 'Messaging' },
  'Amplify App': { icon: 'mobile-alt', color: 'violet', style: 'solid', category: 'Application' },
  'AWS Step Functions': { icon: 'project-diagram', color: 'purple', style: 'solid', category: 'Application' },
  'Pinpoint Application': { icon: 'map-pin', color: 'red', style: 'solid', category: 'Application' },
  'CloudWatch Log Group': { icon: 'file-alt', color: 'gray', style: 'solid', category: 'Management' },
  'CloudTrail': { icon: 'shoe-prints', color: 'gray', style: 'solid', category: 'Management' },
  'Certificate Manager': { icon: 'certificate', color: 'green', style: 'solid', category: 'Security' },
  'AWS Config': { icon: 'cogs', color: 'slate', style: 'solid', category: 'Management' },
  'Security Hub': { icon: 'shield-virus', color: 'red', style: 'solid', category: 'Security' },
  'KMS': { icon: 'key', color: 'amber', style: 'solid', category: 'Security' },
  'AWS Glue': { icon: 'puzzle-piece', color: 'cyan', style: 'solid', category: 'Analytics' },
  'Amazon Athena': { icon: 'feather-alt', color: 'indigo', style: 'solid', category: 'Analytics' },
  'Bedrock': { icon: 'brain', color: 'violet', style: 'solid', category: 'AI/ML' },
  'SageMaker': { icon: 'flask', color: 'emerald', style: 'solid', category: 'AI/ML' },
  'Amazon Cognito': { icon: 'users-cog', color: 'orange', style: 'solid', category: 'Security' },
  'CloudFront': { icon: 'rocket', color: 'blue', style: 'solid', category: 'Network' },
  'ECR Repository': { icon: 'box', color: 'slate', style: 'solid', category: 'Storage' },
  'CloudFormation Stack': { icon: 'cubes', color: 'orange', style: 'solid', category: 'Management' },
  'DataZone Domain': { icon: 'sitemap', color: 'teal', style: 'solid', category: 'Analytics' },
  'Textract Project': { icon: 'file-invoice', color: 'indigo', style: 'solid', category: 'AI/ML' },
  'EventBridge Bus': { icon: 'bus', color: 'rose', style: 'solid', category: 'Application' },
  'Kinesis Stream': { icon: 'stream', color: 'cyan', style: 'solid', category: 'Analytics' },
  'CodePipeline': { icon: 'road', color: 'blue', style: 'solid', category: 'DevOps' },
  'CodeBuild Project': { icon: 'hammer', color: 'slate', style: 'solid', category: 'DevOps' },
  'CodeCommit Repository': { icon: 'code-branch', color: 'gray', style: 'solid', category: 'DevOps' },
  'AWS Shield Protection': { icon: 'shield-alt', color: 'green', style: 'solid', category: 'Security' },
  'Organization Account': { icon: 'sitemap', color: 'indigo', style: 'solid', category: 'Management' },
  'Control Tower Control': { icon: 'university', color: 'purple', style: 'solid', category: 'Management' },
  'Elastic Beanstalk Environment': { icon: 'seedling', color: 'lime', style: 'solid', category: 'Compute' },
  'API Gateway': { icon: 'server', color: 'amber', style: 'solid', category: 'Network' },
  'ECS Cluster': { icon: 'cubes', color: 'orange', style: 'solid', category: 'Compute' },
  'Snapshot': { icon: 'camera', color: 'gray', style: 'solid', category: 'Storage' },
  'AMI': { icon: 'copy', color: 'gray', style: 'solid', category: 'Storage' },
  'WAF Web ACL': { icon: 'shield-alt', color: 'red', style: 'solid', category: 'Security' },
};

export const TAILWIND_COLORS = {
  green: { 100: '#f0fdf4', 500: '#22c55e', 600: '#16a34a' },
  blue: { 100: '#eff6ff', 500: '#3b82f6', 600: '#2563eb' },
  emerald: { 100: '#ecfdf5', 500: '#10b981', 600: '#059669' },
  yellow: { 100: '#fefce8', 500: '#eab308', 600: '#ca8a04' },
  cyan: { 100: '#ecfeff', 500: '#06b6d4', 600: '#0891b2' },
  indigo: { 100: '#eef2ff', 500: '#6366f1', 600: '#4f46e5' },
  orange: { 100: '#fff7ed', 500: '#f97316', 600: '#ea580c' },
  amber: { 100: '#fffbeb', 500: '#f59e0b', 600: '#d97706' },
  rose: { 100: '#fff1f2', 500: '#f43f5e', 600: '#e11d48' },
  slate: { 100: '#f1f5f9', 500: '#64748b', 600: '#475569' },
  teal: { 100: '#f0fdfa', 500: '#14b8a6', 600: '#0d9488' },
  gray: { 100: '#f3f4f6', 500: '#6b7280', 600: '#4b5563' },
  lime: { 100: '#f7fee7', 500: '#84cc16', 600: '#65a30d' },
  sky: { 100: '#f0f9ff', 500: '#0ea5e9', 600: '#0284c7' },
  red: { 100: '#fef2f2', 500: '#ef4444', 600: '#dc2626' },
  purple: { 100: '#faf5ff', 500: '#a855f7', 600: '#9333ea' },
  pink: { 100: '#fdf2f8', 500: '#ec4899', 600: '#db2777' },
  fuchsia: { 100: '#fdf4ff', 500: '#d946ef', 600: '#c026d3' },
  violet: { 100: '#f5f3ff', 500: '#8b5cf6', 600: '#7c3aed' }
};

export const AWS_ICONS: Record<string, string> = {
  'VPC': '/icons/vpc.png',
  'EC2 Instance': '/icons/ec2-instances.png',
  'EBS Volume': '/icons/aws-ebs.png',
  'RDS Instance': '/icons/rds-instance.png',
  'Lambda Function': '/icons/aws-lambda.png',
  'Security Group': '/icons/security-group.png',
  'Load Balancer': '/icons/load-balanceraws.png',
  'Auto Scaling Group': '/icons/autoscaling.png',
  'ElastiCache Cluster': '/icons/elasticache-cluster.png',
  'DynamoDB Table': '/icons/dynamodb-table.png',
  'ECR Repository': '/icons/ecr-repository.png',
  'SNS Topic': '/icons/sns-topic.png',
  'SQS Queue': '/icons/sqs-queue.png',
  'CloudWatch Log Group': '/icons/cloudwatch-log-group.png',
  'CloudTrail': '/icons/cloudtrail.png',
  'S3 Bucket': '/icons/s3-bucket.png',
  'Route 53 Zone': '/icons/aws-route53.png',
  'Certificate Manager': '/icons/certificate-manager.png',
  'EKS Cluster': '/icons/aws-eks.png',
  'Lightsail Instance': '/icons/lightsail.png',
  'Amplify App': '/icons/amplify.png',
  'EFS File System': '/icons/efs.png',
  'SSM Managed Instance': '/icons/ssm.png',
  'AWS Step Functions': '/icons/step-functions.png',
  'AWS Config': '/icons/Config.png',
  'Security Hub': '/icons/security-hub.png',
  'AWS Glue': '/icons/glue.png',
  'Amazon Athena': '/icons/athena.png',
  'Amazon Cognito': '/icons/cognito.png',
  'CloudFront': '/icons/cloudfront.png',
  'Bedrock': '/icons/bedrock.png',
  'SageMaker': '/icons/sagemaker.png',
  'KMS': '/icons/kms.png',
  'Internet Gateway': '/icons/internet-gateway.png',
  'NAT Gateway': '/icons/nat-gateway.png',
  'Network Interface (ENI)': '/icons/eni.png',
  'Elastic IP': '/icons/elastic-ip.png',
  'CloudFormation Stack': '/icons/cloudformation.png',
  'DataZone Domain': '/icons/datazone.png',
  'Textract Project': '/icons/textract.png',
  'EventBridge Bus': '/icons/eventbridge.png',
  'Kinesis Stream': '/icons/kinesis.png',
  'CodePipeline': '/icons/codepipeline.png',
  'CodeBuild Project': '/icons/codebuild.png',
  'CodeCommit Repository': '/icons/codecommit.png',
  'AWS Shield Protection': '/icons/shield.png',
  'Organization Account': '/icons/organizations.png',
  'Control Tower Control': '/icons/controltower.png',
  'Elastic Beanstalk Environment': '/icons/elasticbeanstalk.png',
  'API Gateway': '/icons/api-gateway.png',
  'ECS Cluster': '/icons/aws-ecs.png',
  'Snapshot': '/icons/snapshot.png',
  'AMI': '/icons/ami.png',
  'WAF Web ACL': '/icons/waf.png',
};
