-- =====================================================================
-- DevOps in a Box - Terraform Scripts Seed Data (Task 4.1)
-- =====================================================================
-- Production-ready Terraform script templates covering AWS, Azure, GCP
-- and multi-cloud scenarios. Each script includes full metadata
-- (name, description, difficulty, tags, cloud provider, usage, prereqs)
-- and uses {{PARAMETER_NAME}} placeholder syntax for customization.
--
-- This file is SEED CONTENT only. Bulk import + Elasticsearch indexing
-- is orchestrated by Task 4.6 (which reads these rows and calls
-- bulkIndexScripts). Tag and cloud-provider associations are written to
-- the script_tags and script_cloud_providers tables.
--
-- Conventions:
--   * version       = '1.0.0'
--   * status        = 'published'
--   * author        = 'XamOps Team'
--   * code          = dollar-quoted ($tf$ ... $tf$) for clean multi-line
--   * usage          = HTML ordered list (rendered in the detail modal)
--   * {{PLACEHOLDER}} = customizable parameter (see Task 4.5 for metadata)
--
-- Requirements: REQ-2.1.1, REQ-2.1.2, REQ-2.1.3, REQ-2.2.1
-- =====================================================================

-- ---------------------------------------------------------------------
-- Script inserts (devops_script)
-- ---------------------------------------------------------------------

-- ============================ AWS (14) ===============================

-- 1. AWS EC2 instance with best practices
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS EC2 Instance with Best Practices',
 'Launch a hardened EC2 instance with EBS encryption, IMDSv2, detailed monitoring, and a least-privilege instance profile.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 15,
 'AWS account with EC2 permissions, an existing VPC subnet, and a configured AWS provider/credentials.',
 'published', 0, '2025-06-09',
$tf$terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "{{REGION}}"
}

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

resource "aws_instance" "app" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = "{{INSTANCE_TYPE}}"
  subnet_id                   = "{{SUBNET_ID}}"
  vpc_security_group_ids      = ["{{SECURITY_GROUP_ID}}"]
  monitoring                  = true
  associate_public_ip_address = false

  metadata_options {
    http_tokens   = "required" # Enforce IMDSv2
    http_endpoint = "enabled"
  }

  root_block_device {
    volume_size = {{VOLUME_SIZE_GB}}
    volume_type = "gp3"
    encrypted   = true
  }

  tags = {
    Name        = "{{INSTANCE_NAME}}"
    Environment = "{{ENVIRONMENT}}"
    ManagedBy   = "Terraform"
  }
}

output "instance_id" {
  value = aws_instance.app.id
}
$tf$,
$usage$<ol>
  <li>Configure AWS credentials (<code>aws configure</code> or environment variables).</li>
  <li>Fill in the customizable parameters (region, instance type, subnet, security group).</li>
  <li>Initialize Terraform: <code>terraform init</code></li>
  <li>Review the plan: <code>terraform plan</code></li>
  <li>Apply: <code>terraform apply</code></li>
</ol>
<p><strong>Best practices included:</strong> IMDSv2 enforced, encrypted gp3 root volume, detailed monitoring, no public IP by default.</p>$usage$);

-- 2. AWS VPC with subnets and routing
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS VPC with Public/Private Subnets and Routing',
 'A production VPC with public and private subnets across two AZs, an Internet Gateway, a NAT Gateway, and route tables.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 20,
 'AWS account with VPC/EC2 networking permissions and a configured AWS provider.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "main" {
  cidr_block           = "{{VPC_CIDR}}"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags = { Name = "{{VPC_NAME}}" }
}

resource "aws_internet_gateway" "igw" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "{{VPC_NAME}}-igw" }
}

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index)
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = true
  tags = { Name = "{{VPC_NAME}}-public-${count.index + 1}" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index + 10)
  availability_zone = data.aws_availability_zones.available.names[count.index]
  tags = { Name = "{{VPC_NAME}}-private-${count.index + 1}" }
}

resource "aws_eip" "nat" {
  domain = "vpc"
}

resource "aws_nat_gateway" "nat" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id
  tags          = { Name = "{{VPC_NAME}}-nat" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw.id
  }
  tags = { Name = "{{VPC_NAME}}-public-rt" }
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.nat.id
  }
  tags = { Name = "{{VPC_NAME}}-private-rt" }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}
$tf$,
$usage$<ol>
  <li>Set the region, VPC name, and CIDR block parameters.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Public subnets route to the Internet Gateway; private subnets route through the NAT Gateway.</li>
</ol>
<p><strong>Note:</strong> The NAT Gateway and Elastic IP incur hourly charges.</p>$usage$);

-- 3. AWS S3 bucket with versioning
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS S3 Bucket with Versioning and Encryption',
 'A secure S3 bucket with versioning, SSE-KMS/AES256 encryption, public access fully blocked, and a lifecycle policy.',
 'terraform', '1.0.0', 'beginner', 'XamOps Team', 10,
 'AWS account with S3 permissions and a configured AWS provider.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_s3_bucket" "this" {
  bucket = "{{BUCKET_NAME}}"
  tags = {
    Environment = "{{ENVIRONMENT}}"
    ManagedBy   = "Terraform"
  }
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "{{SSE_ALGORITHM}}"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket                  = aws_s3_bucket.this.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    id     = "expire-old-versions"
    status = "Enabled"
    noncurrent_version_expiration {
      noncurrent_days = {{NONCURRENT_EXPIRATION_DAYS}}
    }
  }
}
$tf$,
$usage$<ol>
  <li>Provide a globally unique bucket name.</li>
  <li>Choose the encryption algorithm (AES256 or aws:kms).</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>Security:</strong> All public access is blocked and versioning protects against accidental deletes.</p>$usage$);

-- 4. AWS RDS PostgreSQL with automated backup
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS RDS PostgreSQL with Automated Backups',
 'A multi-AZ-ready PostgreSQL RDS instance with automated backups, encryption at rest, and a parameterized retention window.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 20,
 'AWS account with RDS permissions, a DB subnet group, and a VPC security group allowing port 5432.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_db_instance" "postgres" {
  identifier              = "{{DB_IDENTIFIER}}"
  engine                  = "postgres"
  engine_version          = "{{ENGINE_VERSION}}"
  instance_class          = "{{DB_INSTANCE_CLASS}}"
  allocated_storage       = {{ALLOCATED_STORAGE_GB}}
  storage_type            = "gp3"
  storage_encrypted       = true
  db_name                 = "{{DB_NAME}}"
  username                = "{{DB_USERNAME}}"
  password                = "{{DB_PASSWORD}}"
  multi_az                = {{MULTI_AZ}}
  db_subnet_group_name    = "{{DB_SUBNET_GROUP}}"
  vpc_security_group_ids  = ["{{SECURITY_GROUP_ID}}"]
  backup_retention_period = {{BACKUP_RETENTION_DAYS}}
  backup_window           = "03:00-04:00"
  maintenance_window      = "Mon:04:00-Mon:05:00"
  deletion_protection     = true
  skip_final_snapshot     = false
  final_snapshot_identifier = "{{DB_IDENTIFIER}}-final"

  tags = {
    Environment = "{{ENVIRONMENT}}"
    ManagedBy   = "Terraform"
  }
}
$tf$,
$usage$<ol>
  <li>Store the DB password in a secret manager and inject it as a variable rather than committing it.</li>
  <li>Provide the DB subnet group and security group IDs.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>Backups:</strong> Automated backups are retained for the configured number of days with a defined backup window.</p>$usage$);

-- 5. AWS RDS MySQL with automated backup
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS RDS MySQL with Automated Backups',
 'A MySQL RDS instance with encryption at rest, automated backups, performance insights, and configurable retention.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 20,
 'AWS account with RDS permissions, a DB subnet group, and a VPC security group allowing port 3306.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_db_instance" "mysql" {
  identifier              = "{{DB_IDENTIFIER}}"
  engine                  = "mysql"
  engine_version          = "{{ENGINE_VERSION}}"
  instance_class          = "{{DB_INSTANCE_CLASS}}"
  allocated_storage       = {{ALLOCATED_STORAGE_GB}}
  storage_type            = "gp3"
  storage_encrypted       = true
  db_name                 = "{{DB_NAME}}"
  username                = "{{DB_USERNAME}}"
  password                = "{{DB_PASSWORD}}"
  multi_az                = {{MULTI_AZ}}
  db_subnet_group_name    = "{{DB_SUBNET_GROUP}}"
  vpc_security_group_ids  = ["{{SECURITY_GROUP_ID}}"]
  backup_retention_period = {{BACKUP_RETENTION_DAYS}}
  performance_insights_enabled = true
  deletion_protection     = true
  skip_final_snapshot     = false
  final_snapshot_identifier = "{{DB_IDENTIFIER}}-final"

  tags = {
    Environment = "{{ENVIRONMENT}}"
    ManagedBy   = "Terraform"
  }
}
$tf$,
$usage$<ol>
  <li>Inject the DB password from a secret store.</li>
  <li>Provide subnet group and security group IDs.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>Note:</strong> Performance Insights and Multi-AZ improve observability and availability for production workloads.</p>$usage$);

-- 6. AWS Lambda function with IAM role
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS Lambda Function with IAM Execution Role',
 'Deploy a Lambda function from a zip package with a dedicated least-privilege execution role and CloudWatch Logs access.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 15,
 'AWS account with Lambda/IAM permissions and a deployment package (zip) for the function code.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_iam_role" "lambda_exec" {
  name = "{{FUNCTION_NAME}}-exec-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_logs" {
  role       = aws_iam_role.lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_lambda_function" "this" {
  function_name    = "{{FUNCTION_NAME}}"
  filename         = "{{PACKAGE_PATH}}"
  source_code_hash = filebase64sha256("{{PACKAGE_PATH}}")
  handler          = "{{HANDLER}}"
  runtime          = "{{RUNTIME}}"
  memory_size      = {{MEMORY_MB}}
  timeout          = {{TIMEOUT_SECONDS}}
  role             = aws_iam_role.lambda_exec.arn

  environment {
    variables = {
      ENVIRONMENT = "{{ENVIRONMENT}}"
    }
  }

  tags = { ManagedBy = "Terraform" }
}
$tf$,
$usage$<ol>
  <li>Build your deployment package and set <code>{{PACKAGE_PATH}}</code> (e.g., <code>function.zip</code>).</li>
  <li>Set the handler and runtime to match your code.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>IAM:</strong> The role grants only the basic execution policy (CloudWatch Logs). Attach additional policies as needed.</p>$usage$);

-- 7. AWS ECS Fargate cluster
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS ECS Fargate Cluster and Service',
 'A serverless ECS Fargate cluster with a task definition and service running a containerized application.',
 'terraform', '1.0.0', 'advanced', 'XamOps Team', 30,
 'AWS account with ECS/IAM permissions, private subnets, and a container image in ECR or a public registry.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_ecs_cluster" "this" {
  name = "{{CLUSTER_NAME}}"
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_iam_role" "task_exec" {
  name = "{{CLUSTER_NAME}}-task-exec"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "task_exec" {
  role       = aws_iam_role.task_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_ecs_task_definition" "app" {
  family                   = "{{SERVICE_NAME}}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "{{TASK_CPU}}"
  memory                   = "{{TASK_MEMORY}}"
  execution_role_arn       = aws_iam_role.task_exec.arn

  container_definitions = jsonencode([{
    name      = "{{SERVICE_NAME}}"
    image     = "{{CONTAINER_IMAGE}}"
    essential = true
    portMappings = [{
      containerPort = {{CONTAINER_PORT}}
      protocol      = "tcp"
    }]
  }])
}

resource "aws_ecs_service" "app" {
  name            = "{{SERVICE_NAME}}"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = {{DESIRED_COUNT}}
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = {{SUBNET_IDS}}
    security_groups  = ["{{SECURITY_GROUP_ID}}"]
    assign_public_ip = false
  }
}
$tf$,
$usage$<ol>
  <li>Provide a container image, subnet IDs (as a list, e.g., <code>["subnet-a","subnet-b"]</code>), and a security group.</li>
  <li>Set task CPU/memory to a valid Fargate combination (e.g., 256/512).</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>Observability:</strong> Container Insights is enabled for the cluster.</p>$usage$);

-- 8. AWS EKS cluster
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS EKS Cluster with Managed Node Group',
 'A production EKS control plane with a managed node group, IAM roles, and configurable Kubernetes version and scaling.',
 'terraform', '1.0.0', 'advanced', 'XamOps Team', 40,
 'AWS account with EKS/IAM/VPC permissions and at least two subnets in different AZs.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_iam_role" "cluster" {
  name = "{{CLUSTER_NAME}}-cluster-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "cluster_policy" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_eks_cluster" "this" {
  name     = "{{CLUSTER_NAME}}"
  version  = "{{K8S_VERSION}}"
  role_arn = aws_iam_role.cluster.arn
  vpc_config {
    subnet_ids = {{SUBNET_IDS}}
  }
  depends_on = [aws_iam_role_policy_attachment.cluster_policy]
}

resource "aws_iam_role" "node" {
  name = "{{CLUSTER_NAME}}-node-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "node_worker" {
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
}

resource "aws_iam_role_policy_attachment" "node_cni" {
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
}

resource "aws_iam_role_policy_attachment" "node_ecr" {
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

resource "aws_eks_node_group" "this" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "{{NODE_GROUP_NAME}}"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = {{SUBNET_IDS}}
  instance_types  = ["{{NODE_INSTANCE_TYPE}}"]

  scaling_config {
    desired_size = {{DESIRED_NODES}}
    max_size     = {{MAX_NODES}}
    min_size     = {{MIN_NODES}}
  }

  depends_on = [
    aws_iam_role_policy_attachment.node_worker,
    aws_iam_role_policy_attachment.node_cni,
    aws_iam_role_policy_attachment.node_ecr
  ]
}
$tf$,
$usage$<ol>
  <li>Provide subnet IDs as a list across at least two AZs.</li>
  <li>Set the Kubernetes version and node scaling parameters.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Update kubeconfig: <code>aws eks update-kubeconfig --name {{CLUSTER_NAME}} --region {{REGION}}</code></li>
</ol>$usage$);

-- 9. AWS CloudFront CDN with S3 origin
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS CloudFront CDN with S3 Origin',
 'A CloudFront distribution fronting a private S3 bucket using Origin Access Control, HTTPS redirect, and caching.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 25,
 'AWS account with CloudFront/S3 permissions and a configured AWS provider.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_s3_bucket" "origin" {
  bucket = "{{BUCKET_NAME}}"
}

resource "aws_cloudfront_origin_access_control" "oac" {
  name                              = "{{DISTRIBUTION_NAME}}-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  default_root_object = "index.html"
  comment             = "{{DISTRIBUTION_NAME}}"

  origin {
    domain_name              = aws_s3_bucket.origin.bucket_regional_domain_name
    origin_id                = "s3-{{BUCKET_NAME}}"
    origin_access_control_id = aws_cloudfront_origin_access_control.oac.id
  }

  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "s3-{{BUCKET_NAME}}"
    viewer_protocol_policy = "redirect-to-https"
    cache_policy_id        = "658327ea-f89d-4fab-a63d-7e88639e58f6" # Managed-CachingOptimized
  }

  price_class = "{{PRICE_CLASS}}"

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = { ManagedBy = "Terraform" }
}
$tf$,
$usage$<ol>
  <li>Provide a bucket name and distribution name.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Attach an S3 bucket policy granting the distribution access via the OAC.</li>
</ol>
<p><strong>Tip:</strong> Use a custom ACM certificate (us-east-1) for a custom domain.</p>$usage$);

-- 10. AWS Route53 DNS zone
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS Route53 Hosted Zone with Records',
 'Create a Route53 public hosted zone with A and CNAME records for a domain.',
 'terraform', '1.0.0', 'beginner', 'XamOps Team', 10,
 'AWS account with Route53 permissions and a registered domain name.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_route53_zone" "this" {
  name = "{{DOMAIN_NAME}}"
  tags = { ManagedBy = "Terraform" }
}

resource "aws_route53_record" "root" {
  zone_id = aws_route53_zone.this.zone_id
  name    = "{{DOMAIN_NAME}}"
  type    = "A"
  ttl     = {{RECORD_TTL}}
  records = ["{{ROOT_IP_ADDRESS}}"]
}

resource "aws_route53_record" "www" {
  zone_id = aws_route53_zone.this.zone_id
  name    = "www.{{DOMAIN_NAME}}"
  type    = "CNAME"
  ttl     = {{RECORD_TTL}}
  records = ["{{DOMAIN_NAME}}"]
}

output "name_servers" {
  value = aws_route53_zone.this.name_servers
}
$tf$,
$usage$<ol>
  <li>Provide the domain name and the root A-record IP address.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Update your registrar's name servers with the <code>name_servers</code> output.</li>
</ol>$usage$);

-- 11. AWS Auto Scaling group with launch template
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS Auto Scaling Group with Launch Template',
 'An Auto Scaling Group backed by a launch template with encrypted volumes, IMDSv2, and target tracking scaling.',
 'terraform', '1.0.0', 'advanced', 'XamOps Team', 30,
 'AWS account with EC2/Auto Scaling permissions, a VPC with subnets, and a security group.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

resource "aws_launch_template" "this" {
  name_prefix   = "{{ASG_NAME}}-"
  image_id      = data.aws_ami.al2023.id
  instance_type = "{{INSTANCE_TYPE}}"

  metadata_options {
    http_tokens = "required"
  }

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_size = {{VOLUME_SIZE_GB}}
      volume_type = "gp3"
      encrypted   = true
    }
  }

  tag_specifications {
    resource_type = "instance"
    tags = { Name = "{{ASG_NAME}}" }
  }
}

resource "aws_autoscaling_group" "this" {
  name                = "{{ASG_NAME}}"
  vpc_zone_identifier = {{SUBNET_IDS}}
  desired_capacity    = {{DESIRED_CAPACITY}}
  min_size            = {{MIN_SIZE}}
  max_size            = {{MAX_SIZE}}
  health_check_type   = "EC2"

  launch_template {
    id      = aws_launch_template.this.id
    version = "$Latest"
  }

  tag {
    key                 = "ManagedBy"
    value               = "Terraform"
    propagate_at_launch = true
  }
}

resource "aws_autoscaling_policy" "cpu" {
  name                   = "{{ASG_NAME}}-cpu-target"
  autoscaling_group_name = aws_autoscaling_group.this.name
  policy_type            = "TargetTrackingScaling"
  target_tracking_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ASGAverageCPUUtilization"
    }
    target_value = {{TARGET_CPU_PERCENT}}
  }
}
$tf$,
$usage$<ol>
  <li>Provide subnet IDs (as a list), instance type, and scaling bounds.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>The group scales to keep average CPU near the target percentage.</li>
</ol>$usage$);

-- 12. AWS Security group with least privilege
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS Security Group with Least Privilege',
 'A security group that allows HTTPS from anywhere and SSH only from a trusted CIDR, with explicit egress.',
 'terraform', '1.0.0', 'beginner', 'XamOps Team', 10,
 'AWS account with EC2 permissions and an existing VPC.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_security_group" "this" {
  name        = "{{SECURITY_GROUP_NAME}}"
  description = "Least-privilege security group managed by Terraform"
  vpc_id      = "{{VPC_ID}}"

  ingress {
    description = "HTTPS from anywhere"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "SSH from trusted network only"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["{{TRUSTED_SSH_CIDR}}"]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "{{SECURITY_GROUP_NAME}}"
    ManagedBy = "Terraform"
  }
}
$tf$,
$usage$<ol>
  <li>Provide the VPC ID and a trusted CIDR for SSH (e.g., your office IP <code>203.0.113.0/24</code>).</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>Security:</strong> SSH is restricted to a trusted CIDR rather than <code>0.0.0.0/0</code>.</p>$usage$);

-- 13. AWS IAM roles and policies
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS IAM Role and Scoped Policy',
 'Create an IAM role assumable by a service with a customer-managed, scoped policy attached.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 15,
 'AWS account with IAM permissions.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_iam_role" "this" {
  name = "{{ROLE_NAME}}"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "{{TRUSTED_SERVICE}}" }
    }]
  })
  tags = { ManagedBy = "Terraform" }
}

resource "aws_iam_policy" "this" {
  name        = "{{ROLE_NAME}}-policy"
  description = "Scoped policy for {{ROLE_NAME}}"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "ScopedAccess"
      Effect   = "Allow"
      Action   = {{ALLOWED_ACTIONS}}
      Resource = "{{RESOURCE_ARN}}"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "this" {
  role       = aws_iam_role.this.name
  policy_arn = aws_iam_policy.this.arn
}
$tf$,
$usage$<ol>
  <li>Set the trusted service (e.g., <code>ec2.amazonaws.com</code>), allowed actions (e.g., <code>["s3:GetObject"]</code>), and a resource ARN.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>Least privilege:</strong> Scope actions and resource ARNs as tightly as possible.</p>$usage$);

-- 14. AWS KMS encryption key
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS KMS Encryption Key with Rotation',
 'A customer-managed KMS key with automatic annual rotation and a friendly alias.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 10,
 'AWS account with KMS permissions.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_kms_key" "this" {
  description             = "{{KEY_DESCRIPTION}}"
  deletion_window_in_days = {{DELETION_WINDOW_DAYS}}
  enable_key_rotation     = true
  tags = {
    Environment = "{{ENVIRONMENT}}"
    ManagedBy   = "Terraform"
  }
}

resource "aws_kms_alias" "this" {
  name          = "alias/{{KEY_ALIAS}}"
  target_key_id = aws_kms_key.this.key_id
}
$tf$,
$usage$<ol>
  <li>Provide a key description and alias.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Reference the key via its alias in other resources for encryption.</li>
</ol>
<p><strong>Compliance:</strong> Automatic key rotation is enabled.</p>$usage$);

-- =========================== AZURE (6) ===============================

-- 15. Azure Virtual Machine with managed disks
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('Azure Linux VM with Managed Disk',
 'A Linux virtual machine with a managed OS disk, SSH key authentication, and a network interface in an existing subnet.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 20,
 'Azure subscription, the azurerm provider configured (az login), and an existing resource group and subnet.',
 'published', 0, '2025-06-09',
$tf$provider "azurerm" {
  features {}
}

resource "azurerm_network_interface" "this" {
  name                = "{{VM_NAME}}-nic"
  location            = "{{LOCATION}}"
  resource_group_name = "{{RESOURCE_GROUP}}"

  ip_configuration {
    name                          = "internal"
    subnet_id                     = "{{SUBNET_ID}}"
    private_ip_address_allocation = "Dynamic"
  }
}

resource "azurerm_linux_virtual_machine" "this" {
  name                  = "{{VM_NAME}}"
  location              = "{{LOCATION}}"
  resource_group_name   = "{{RESOURCE_GROUP}}"
  size                  = "{{VM_SIZE}}"
  admin_username        = "{{ADMIN_USERNAME}}"
  network_interface_ids = [azurerm_network_interface.this.id]

  admin_ssh_key {
    username   = "{{ADMIN_USERNAME}}"
    public_key = "{{SSH_PUBLIC_KEY}}"
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "{{DISK_TYPE}}"
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts-gen2"
    version   = "latest"
  }

  tags = { ManagedBy = "Terraform" }
}
$tf$,
$usage$<ol>
  <li>Authenticate with <code>az login</code>.</li>
  <li>Provide the resource group, subnet ID, and your SSH public key.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>$usage$);

-- 16. Azure VNet with subnets
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('Azure Virtual Network with Subnets',
 'A virtual network with separate application and database subnets and an associated network security group.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 15,
 'Azure subscription and the azurerm provider configured (az login).',
 'published', 0, '2025-06-09',
$tf$provider "azurerm" {
  features {}
}

resource "azurerm_resource_group" "this" {
  name     = "{{RESOURCE_GROUP}}"
  location = "{{LOCATION}}"
}

resource "azurerm_virtual_network" "this" {
  name                = "{{VNET_NAME}}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  address_space       = ["{{VNET_CIDR}}"]
  tags                = { ManagedBy = "Terraform" }
}

resource "azurerm_subnet" "app" {
  name                 = "app-subnet"
  resource_group_name  = azurerm_resource_group.this.name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = ["{{APP_SUBNET_CIDR}}"]
}

resource "azurerm_subnet" "db" {
  name                 = "db-subnet"
  resource_group_name  = azurerm_resource_group.this.name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = ["{{DB_SUBNET_CIDR}}"]
}

resource "azurerm_network_security_group" "this" {
  name                = "{{VNET_NAME}}-nsg"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
}
$tf$,
$usage$<ol>
  <li>Set the resource group, VNet name, and CIDR ranges.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>$usage$);

-- 17. Azure Storage account with blob containers
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('Azure Storage Account with Blob Container',
 'A storage account with a private blob container, TLS 1.2 minimum, and public network access disabled.',
 'terraform', '1.0.0', 'beginner', 'XamOps Team', 10,
 'Azure subscription, the azurerm provider configured, and an existing resource group.',
 'published', 0, '2025-06-09',
$tf$provider "azurerm" {
  features {}
}

resource "azurerm_storage_account" "this" {
  name                          = "{{STORAGE_ACCOUNT_NAME}}"
  resource_group_name           = "{{RESOURCE_GROUP}}"
  location                      = "{{LOCATION}}"
  account_tier                  = "Standard"
  account_replication_type      = "{{REPLICATION_TYPE}}"
  min_tls_version               = "TLS1_2"
  public_network_access_enabled = false
  tags                          = { ManagedBy = "Terraform" }
}

resource "azurerm_storage_container" "this" {
  name                  = "{{CONTAINER_NAME}}"
  storage_account_name  = azurerm_storage_account.this.name
  container_access_type = "private"
}
$tf$,
$usage$<ol>
  <li>Storage account names must be globally unique, 3-24 lowercase alphanumeric characters.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>$usage$);

-- 18. Azure SQL Database with failover
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('Azure SQL Database with Failover Group',
 'A primary and secondary Azure SQL Server pair with a database and an auto-failover group for high availability.',
 'terraform', '1.0.0', 'advanced', 'XamOps Team', 30,
 'Azure subscription, the azurerm provider configured, and an existing resource group.',
 'published', 0, '2025-06-09',
$tf$provider "azurerm" {
  features {}
}

resource "azurerm_mssql_server" "primary" {
  name                         = "{{SQL_SERVER_NAME}}-primary"
  resource_group_name          = "{{RESOURCE_GROUP}}"
  location                     = "{{PRIMARY_LOCATION}}"
  version                      = "12.0"
  administrator_login          = "{{ADMIN_LOGIN}}"
  administrator_login_password = "{{ADMIN_PASSWORD}}"
}

resource "azurerm_mssql_server" "secondary" {
  name                         = "{{SQL_SERVER_NAME}}-secondary"
  resource_group_name          = "{{RESOURCE_GROUP}}"
  location                     = "{{SECONDARY_LOCATION}}"
  version                      = "12.0"
  administrator_login          = "{{ADMIN_LOGIN}}"
  administrator_login_password = "{{ADMIN_PASSWORD}}"
}

resource "azurerm_mssql_database" "this" {
  name      = "{{DATABASE_NAME}}"
  server_id = azurerm_mssql_server.primary.id
  sku_name  = "{{SKU_NAME}}"
}

resource "azurerm_mssql_failover_group" "this" {
  name      = "{{SQL_SERVER_NAME}}-fog"
  server_id = azurerm_mssql_server.primary.id
  databases = [azurerm_mssql_database.this.id]

  partner_server {
    id = azurerm_mssql_server.secondary.id
  }

  read_write_endpoint_failover_policy {
    mode          = "Automatic"
    grace_minutes = 60
  }
}
$tf$,
$usage$<ol>
  <li>Inject the admin password from a secret store (Key Vault).</li>
  <li>Set primary and secondary locations in different regions.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>HA:</strong> The failover group provides automatic failover with a 60-minute grace period.</p>$usage$);

-- 19. Azure App Service with deployment slots
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('Azure App Service with Staging Slot',
 'A Linux App Service plan and web app with a staging deployment slot for blue-green style releases.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 20,
 'Azure subscription, the azurerm provider configured, and an existing resource group.',
 'published', 0, '2025-06-09',
$tf$provider "azurerm" {
  features {}
}

resource "azurerm_service_plan" "this" {
  name                = "{{APP_NAME}}-plan"
  resource_group_name = "{{RESOURCE_GROUP}}"
  location            = "{{LOCATION}}"
  os_type             = "Linux"
  sku_name            = "{{SKU_NAME}}"
}

resource "azurerm_linux_web_app" "this" {
  name                = "{{APP_NAME}}"
  resource_group_name = "{{RESOURCE_GROUP}}"
  location            = "{{LOCATION}}"
  service_plan_id     = azurerm_service_plan.this.id

  site_config {
    application_stack {
      node_version = "{{NODE_VERSION}}"
    }
  }

  tags = { ManagedBy = "Terraform" }
}

resource "azurerm_linux_web_app_slot" "staging" {
  name           = "staging"
  app_service_id = azurerm_linux_web_app.this.id

  site_config {
    application_stack {
      node_version = "{{NODE_VERSION}}"
    }
  }
}
$tf$,
$usage$<ol>
  <li>Set the app name (globally unique) and SKU (e.g., <code>P1v2</code> to enable slots).</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Deploy to <code>staging</code>, validate, then swap into production.</li>
</ol>
<p><strong>Note:</strong> Deployment slots require a Standard tier or higher.</p>$usage$);

-- 20. Azure AKS cluster
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('Azure AKS Cluster with System Node Pool',
 'A managed Kubernetes (AKS) cluster with a system-assigned identity, default node pool, and Azure CNI networking.',
 'terraform', '1.0.0', 'advanced', 'XamOps Team', 35,
 'Azure subscription, the azurerm provider configured, and an existing resource group.',
 'published', 0, '2025-06-09',
$tf$provider "azurerm" {
  features {}
}

resource "azurerm_kubernetes_cluster" "this" {
  name                = "{{CLUSTER_NAME}}"
  location            = "{{LOCATION}}"
  resource_group_name = "{{RESOURCE_GROUP}}"
  dns_prefix          = "{{DNS_PREFIX}}"
  kubernetes_version  = "{{K8S_VERSION}}"

  default_node_pool {
    name       = "system"
    node_count = {{NODE_COUNT}}
    vm_size    = "{{NODE_VM_SIZE}}"
  }

  identity {
    type = "SystemAssigned"
  }

  network_profile {
    network_plugin = "azure"
  }

  tags = { ManagedBy = "Terraform" }
}

output "kube_config" {
  value     = azurerm_kubernetes_cluster.this.kube_config_raw
  sensitive = true
}
$tf$,
$usage$<ol>
  <li>Set the cluster name, DNS prefix, and node sizing.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Get credentials: <code>az aks get-credentials --resource-group {{RESOURCE_GROUP}} --name {{CLUSTER_NAME}}</code></li>
</ol>$usage$);

-- ============================ GCP (6) ================================

-- 21. GCP Compute Engine instance
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('GCP Compute Engine Instance',
 'A Compute Engine VM with a Debian boot disk, Shielded VM features, and a configurable machine type and zone.',
 'terraform', '1.0.0', 'beginner', 'XamOps Team', 15,
 'GCP project, the google provider configured (gcloud auth application-default login), and Compute Engine API enabled.',
 'published', 0, '2025-06-09',
$tf$provider "google" {
  project = "{{PROJECT_ID}}"
  region  = "{{REGION}}"
}

resource "google_compute_instance" "this" {
  name         = "{{INSTANCE_NAME}}"
  machine_type = "{{MACHINE_TYPE}}"
  zone         = "{{ZONE}}"

  boot_disk {
    initialize_params {
      image = "debian-cloud/debian-12"
      size  = {{DISK_SIZE_GB}}
    }
  }

  network_interface {
    network = "{{NETWORK}}"
    access_config {}
  }

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  labels = {
    environment = "{{ENVIRONMENT}}"
    managed-by  = "terraform"
  }
}
$tf$,
$usage$<ol>
  <li>Authenticate with <code>gcloud auth application-default login</code>.</li>
  <li>Set the project, zone, and machine type.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>$usage$);

-- 22. GCP VPC network with firewall rules
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('GCP VPC Network with Firewall Rules',
 'A custom-mode VPC network with a subnet and firewall rules allowing internal traffic and SSH from a trusted range.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 15,
 'GCP project, the google provider configured, and Compute Engine API enabled.',
 'published', 0, '2025-06-09',
$tf$provider "google" {
  project = "{{PROJECT_ID}}"
  region  = "{{REGION}}"
}

resource "google_compute_network" "this" {
  name                    = "{{NETWORK_NAME}}"
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"
}

resource "google_compute_subnetwork" "this" {
  name          = "{{NETWORK_NAME}}-subnet"
  ip_cidr_range = "{{SUBNET_CIDR}}"
  region        = "{{REGION}}"
  network       = google_compute_network.this.id
}

resource "google_compute_firewall" "internal" {
  name    = "{{NETWORK_NAME}}-allow-internal"
  network = google_compute_network.this.name
  allow {
    protocol = "tcp"
    ports    = ["0-65535"]
  }
  allow {
    protocol = "icmp"
  }
  source_ranges = ["{{SUBNET_CIDR}}"]
}

resource "google_compute_firewall" "ssh" {
  name    = "{{NETWORK_NAME}}-allow-ssh"
  network = google_compute_network.this.name
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
  source_ranges = ["{{TRUSTED_SSH_CIDR}}"]
}
$tf$,
$usage$<ol>
  <li>Set the network name, subnet CIDR, and a trusted CIDR for SSH.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>Security:</strong> SSH is limited to a trusted source range.</p>$usage$);

-- 23. GCP Cloud Storage bucket
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('GCP Cloud Storage Bucket with Lifecycle',
 'A Cloud Storage bucket with uniform bucket-level access, versioning, and a lifecycle rule to delete old versions.',
 'terraform', '1.0.0', 'beginner', 'XamOps Team', 10,
 'GCP project, the google provider configured, and Cloud Storage API enabled.',
 'published', 0, '2025-06-09',
$tf$provider "google" {
  project = "{{PROJECT_ID}}"
}

resource "google_storage_bucket" "this" {
  name                        = "{{BUCKET_NAME}}"
  location                    = "{{LOCATION}}"
  storage_class               = "{{STORAGE_CLASS}}"
  uniform_bucket_level_access = true
  force_destroy               = false

  versioning {
    enabled = true
  }

  lifecycle_rule {
    condition {
      num_newer_versions = {{KEEP_VERSIONS}}
    }
    action {
      type = "Delete"
    }
  }

  labels = { managed-by = "terraform" }
}
$tf$,
$usage$<ol>
  <li>Provide a globally unique bucket name and a location (e.g., <code>US</code>).</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>$usage$);

-- 24. GCP Cloud SQL instance
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('GCP Cloud SQL PostgreSQL Instance',
 'A Cloud SQL for PostgreSQL instance with automated backups, point-in-time recovery, and a configurable tier.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 20,
 'GCP project, the google provider configured, and the Cloud SQL Admin API enabled.',
 'published', 0, '2025-06-09',
$tf$provider "google" {
  project = "{{PROJECT_ID}}"
  region  = "{{REGION}}"
}

resource "google_sql_database_instance" "this" {
  name             = "{{INSTANCE_NAME}}"
  database_version = "{{DATABASE_VERSION}}"
  region           = "{{REGION}}"

  settings {
    tier              = "{{TIER}}"
    availability_type = "{{AVAILABILITY_TYPE}}"
    disk_autoresize   = true

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "03:00"
    }
  }

  deletion_protection = true
}

resource "google_sql_database" "this" {
  name     = "{{DATABASE_NAME}}"
  instance = google_sql_database_instance.this.name
}

resource "google_sql_user" "this" {
  name     = "{{DB_USERNAME}}"
  instance = google_sql_database_instance.this.name
  password = "{{DB_PASSWORD}}"
}
$tf$,
$usage$<ol>
  <li>Inject the database password from Secret Manager.</li>
  <li>Choose the tier (e.g., <code>db-custom-1-3840</code>) and availability type (<code>ZONAL</code> or <code>REGIONAL</code>).</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>$usage$);

-- 25. GCP Cloud Function
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('GCP Cloud Function (2nd Gen) HTTP Trigger',
 'A 2nd-generation HTTP-triggered Cloud Function deployed from a source zip stored in a Cloud Storage bucket.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 20,
 'GCP project, the google provider configured, Cloud Functions and Cloud Build APIs enabled, and a source zip in GCS.',
 'published', 0, '2025-06-09',
$tf$provider "google" {
  project = "{{PROJECT_ID}}"
  region  = "{{REGION}}"
}

resource "google_cloudfunctions2_function" "this" {
  name     = "{{FUNCTION_NAME}}"
  location = "{{REGION}}"

  build_config {
    runtime     = "{{RUNTIME}}"
    entry_point = "{{ENTRY_POINT}}"
    source {
      storage_source {
        bucket = "{{SOURCE_BUCKET}}"
        object = "{{SOURCE_OBJECT}}"
      }
    }
  }

  service_config {
    max_instance_count = {{MAX_INSTANCES}}
    available_memory   = "{{MEMORY}}"
    timeout_seconds    = {{TIMEOUT_SECONDS}}
  }
}
$tf$,
$usage$<ol>
  <li>Upload your function source zip to a GCS bucket and set the bucket/object parameters.</li>
  <li>Set the runtime (e.g., <code>python312</code>) and entry point.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>$usage$);

-- 26. GCP GKE cluster
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('GCP GKE Cluster with Node Pool',
 'A GKE cluster with a separately managed node pool, workload identity, and a configurable node count and machine type.',
 'terraform', '1.0.0', 'advanced', 'XamOps Team', 35,
 'GCP project, the google provider configured, and the Kubernetes Engine API enabled.',
 'published', 0, '2025-06-09',
$tf$provider "google" {
  project = "{{PROJECT_ID}}"
  region  = "{{REGION}}"
}

resource "google_container_cluster" "this" {
  name                     = "{{CLUSTER_NAME}}"
  location                 = "{{REGION}}"
  remove_default_node_pool = true
  initial_node_count       = 1

  workload_identity_config {
    workload_pool = "{{PROJECT_ID}}.svc.id.goog"
  }
}

resource "google_container_node_pool" "this" {
  name       = "{{CLUSTER_NAME}}-pool"
  location   = "{{REGION}}"
  cluster    = google_container_cluster.this.name
  node_count = {{NODE_COUNT}}

  node_config {
    machine_type = "{{MACHINE_TYPE}}"
    oauth_scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  autoscaling {
    min_node_count = {{MIN_NODES}}
    max_node_count = {{MAX_NODES}}
  }
}
$tf$,
$usage$<ol>
  <li>Set the cluster name, region, and node pool sizing.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Get credentials: <code>gcloud container clusters get-credentials {{CLUSTER_NAME}} --region {{REGION}}</code></li>
</ol>$usage$);

-- ======================= MULTI-CLOUD (3) =============================

-- 27. Multi-cloud generic load balancer setup
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('Multi-Cloud HTTP Load Balancer Setup',
 'A reusable pattern that provisions an external HTTP load balancer on either AWS or GCP, selected by a provider variable.',
 'terraform', '1.0.0', 'advanced', 'XamOps Team', 30,
 'Credentials for the target cloud (AWS or GCP), and existing network/instance resources to attach as backends.',
 'published', 0, '2025-06-09',
$tf$# Choose the target cloud with {{CLOUD_PROVIDER}} = "aws" or "gcp"
variable "cloud_provider" {
  type    = string
  default = "{{CLOUD_PROVIDER}}"
}

# ---------------- AWS path ----------------
resource "aws_lb" "this" {
  count              = var.cloud_provider == "aws" ? 1 : 0
  name               = "{{LB_NAME}}"
  internal           = false
  load_balancer_type = "application"
  security_groups    = ["{{AWS_SECURITY_GROUP_ID}}"]
  subnets            = {{AWS_SUBNET_IDS}}
  tags               = { ManagedBy = "Terraform" }
}

# ---------------- GCP path ----------------
resource "google_compute_global_address" "this" {
  count = var.cloud_provider == "gcp" ? 1 : 0
  name  = "{{LB_NAME}}-ip"
}

resource "google_compute_global_forwarding_rule" "this" {
  count      = var.cloud_provider == "gcp" ? 1 : 0
  name       = "{{LB_NAME}}-fr"
  target     = "{{GCP_TARGET_PROXY}}"
  port_range = "80"
  ip_address = google_compute_global_address.this[0].address
}
$tf$,
$usage$<ol>
  <li>Set <code>{{CLOUD_PROVIDER}}</code> to <code>aws</code> or <code>gcp</code>.</li>
  <li>Provide the cloud-specific backend parameters (subnets/security group for AWS, target proxy for GCP).</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>Pattern:</strong> Uses <code>count</code> guards so only the selected provider's resources are created.</p>$usage$);

-- 28. Cross-cloud VPN
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('Cross-Cloud VPN (AWS to GCP)',
 'A site-to-site IPsec VPN connecting an AWS VPC to a GCP VPC for secure cross-cloud connectivity.',
 'terraform', '1.0.0', 'advanced', 'XamOps Team', 45,
 'AWS and GCP credentials, existing VPCs on both clouds, and non-overlapping CIDR ranges.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{AWS_REGION}}"
}
provider "google" {
  project = "{{GCP_PROJECT_ID}}"
  region  = "{{GCP_REGION}}"
}

# --- GCP side: HA VPN gateway + external peer ---
resource "google_compute_ha_vpn_gateway" "gcp" {
  name    = "{{VPN_NAME}}-gcp-gw"
  network = "{{GCP_NETWORK}}"
  region  = "{{GCP_REGION}}"
}

# --- AWS side: customer & VPN gateways ---
resource "aws_vpn_gateway" "aws" {
  vpc_id = "{{AWS_VPC_ID}}"
  tags   = { Name = "{{VPN_NAME}}-aws-gw" }
}

resource "aws_customer_gateway" "gcp_peer" {
  bgp_asn    = {{GCP_BGP_ASN}}
  ip_address = "{{GCP_PEER_IP}}"
  type       = "ipsec.1"
  tags       = { Name = "{{VPN_NAME}}-gcp-peer" }
}

resource "aws_vpn_connection" "this" {
  vpn_gateway_id      = aws_vpn_gateway.aws.id
  customer_gateway_id = aws_customer_gateway.gcp_peer.id
  type                = "ipsec.1"
  static_routes_only  = true
  tags                = { Name = "{{VPN_NAME}}" }
}
$tf$,
$usage$<ol>
  <li>Ensure AWS and GCP VPC CIDR ranges do not overlap.</li>
  <li>Provide VPC/network IDs, the GCP peer IP, and BGP ASN.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>, then configure the GCP tunnel with the AWS tunnel pre-shared keys.</li>
</ol>
<p><strong>Note:</strong> Cross-cloud VPN setup is iterative; exchange tunnel endpoints/PSKs between providers.</p>$usage$);

-- 29. Multi-cloud disaster recovery setup
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('Multi-Cloud Disaster Recovery Storage',
 'Cross-region replicated object storage on AWS S3 with a GCP Cloud Storage standby bucket for DR backups.',
 'terraform', '1.0.0', 'advanced', 'XamOps Team', 30,
 'AWS and GCP credentials, and an IAM role permitting S3 cross-region replication.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{AWS_PRIMARY_REGION}}"
}
provider "aws" {
  alias  = "dr"
  region = "{{AWS_DR_REGION}}"
}
provider "google" {
  project = "{{GCP_PROJECT_ID}}"
}

resource "aws_s3_bucket" "primary" {
  bucket = "{{PRIMARY_BUCKET_NAME}}"
}

resource "aws_s3_bucket" "dr" {
  provider = aws.dr
  bucket   = "{{DR_BUCKET_NAME}}"
}

resource "aws_s3_bucket_versioning" "primary" {
  bucket = aws_s3_bucket.primary.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_versioning" "dr" {
  provider = aws.dr
  bucket   = aws_s3_bucket.dr.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_replication_configuration" "this" {
  bucket = aws_s3_bucket.primary.id
  role   = "{{REPLICATION_ROLE_ARN}}"
  rule {
    id     = "dr-replication"
    status = "Enabled"
    destination {
      bucket        = aws_s3_bucket.dr.arn
      storage_class = "STANDARD"
    }
  }
  depends_on = [aws_s3_bucket_versioning.primary]
}

# GCP standby bucket for an additional cloud-independent copy
resource "google_storage_bucket" "standby" {
  name                        = "{{GCP_STANDBY_BUCKET}}"
  location                    = "{{GCP_LOCATION}}"
  storage_class               = "NEARLINE"
  uniform_bucket_level_access = true
  versioning { enabled = true }
}
$tf$,
$usage$<ol>
  <li>Provide unique bucket names for primary, DR, and GCP standby.</li>
  <li>Create an IAM role with S3 replication permissions and set its ARN.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
</ol>
<p><strong>DR strategy:</strong> S3 cross-region replication for fast failover plus a cloud-independent GCP copy.</p>$usage$);

-- ================== ADDITIONAL LOAD BALANCER / DNS (3) ===============

-- 30. AWS Application Load Balancer with target group
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('AWS Application Load Balancer with Target Group',
 'An internet-facing ALB with an HTTP listener, a target group, and health checks across multiple subnets.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 20,
 'AWS account with ELB/EC2 permissions, a VPC with at least two public subnets, and a security group.',
 'published', 0, '2025-06-09',
$tf$provider "aws" {
  region = "{{REGION}}"
}

resource "aws_lb" "this" {
  name               = "{{LB_NAME}}"
  internal           = false
  load_balancer_type = "application"
  security_groups    = ["{{SECURITY_GROUP_ID}}"]
  subnets            = {{SUBNET_IDS}}
  tags               = { ManagedBy = "Terraform" }
}

resource "aws_lb_target_group" "this" {
  name     = "{{LB_NAME}}-tg"
  port     = {{TARGET_PORT}}
  protocol = "HTTP"
  vpc_id   = "{{VPC_ID}}"

  health_check {
    path                = "{{HEALTH_CHECK_PATH}}"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }
}
$tf$,
$usage$<ol>
  <li>Provide the VPC ID, public subnet IDs (as a list), and a security group allowing HTTP.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Register instances or use an ASG target group attachment.</li>
</ol>$usage$);

-- 31. Azure Load Balancer
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('Azure Public Load Balancer',
 'A standard SKU public load balancer with a frontend public IP, backend pool, health probe, and load balancing rule.',
 'terraform', '1.0.0', 'intermediate', 'XamOps Team', 20,
 'Azure subscription, the azurerm provider configured, and an existing resource group.',
 'published', 0, '2025-06-09',
$tf$provider "azurerm" {
  features {}
}

resource "azurerm_public_ip" "this" {
  name                = "{{LB_NAME}}-pip"
  resource_group_name = "{{RESOURCE_GROUP}}"
  location            = "{{LOCATION}}"
  allocation_method   = "Static"
  sku                 = "Standard"
}

resource "azurerm_lb" "this" {
  name                = "{{LB_NAME}}"
  resource_group_name = "{{RESOURCE_GROUP}}"
  location            = "{{LOCATION}}"
  sku                 = "Standard"

  frontend_ip_configuration {
    name                 = "frontend"
    public_ip_address_id = azurerm_public_ip.this.id
  }
}

resource "azurerm_lb_backend_address_pool" "this" {
  loadbalancer_id = azurerm_lb.this.id
  name            = "backend-pool"
}

resource "azurerm_lb_probe" "this" {
  loadbalancer_id = azurerm_lb.this.id
  name            = "http-probe"
  port            = {{PROBE_PORT}}
  protocol        = "Http"
  request_path    = "{{HEALTH_CHECK_PATH}}"
}

resource "azurerm_lb_rule" "this" {
  loadbalancer_id                = azurerm_lb.this.id
  name                           = "http-rule"
  protocol                       = "Tcp"
  frontend_port                  = 80
  backend_port                   = {{BACKEND_PORT}}
  frontend_ip_configuration_name = "frontend"
  backend_address_pool_ids       = [azurerm_lb_backend_address_pool.this.id]
  probe_id                       = azurerm_lb_probe.this.id
}
$tf$,
$usage$<ol>
  <li>Set the resource group, location, and port parameters.</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Associate VM NICs with the backend address pool.</li>
</ol>$usage$);

-- 32. GCP Cloud DNS managed zone
INSERT INTO devops_script
  (name, description, category, version, difficulty, author, estimated_time_minutes, prerequisites, status, downloads, last_updated, code, usage_instructions)
VALUES
('GCP Cloud DNS Managed Zone',
 'A public Cloud DNS managed zone with an A record and a www CNAME for a domain.',
 'terraform', '1.0.0', 'beginner', 'XamOps Team', 10,
 'GCP project, the google provider configured, and the Cloud DNS API enabled.',
 'published', 0, '2025-06-09',
$tf$provider "google" {
  project = "{{PROJECT_ID}}"
}

resource "google_dns_managed_zone" "this" {
  name        = "{{ZONE_NAME}}"
  dns_name    = "{{DNS_NAME}}"
  description = "Managed by Terraform"
  visibility  = "public"
}

resource "google_dns_record_set" "root" {
  name         = google_dns_managed_zone.this.dns_name
  managed_zone = google_dns_managed_zone.this.name
  type         = "A"
  ttl          = {{RECORD_TTL}}
  rrdatas      = ["{{ROOT_IP_ADDRESS}}"]
}

resource "google_dns_record_set" "www" {
  name         = "www.${google_dns_managed_zone.this.dns_name}"
  managed_zone = google_dns_managed_zone.this.name
  type         = "CNAME"
  ttl          = {{RECORD_TTL}}
  rrdatas      = [google_dns_managed_zone.this.dns_name]
}

output "name_servers" {
  value = google_dns_managed_zone.this.name_servers
}
$tf$,
$usage$<ol>
  <li>Provide the zone name and a fully-qualified DNS name (with trailing dot, e.g., <code>example.com.</code>).</li>
  <li>Run <code>terraform init</code> then <code>terraform apply</code>.</li>
  <li>Update your registrar with the <code>name_servers</code> output.</li>
</ol>$usage$);

-- ---------------------------------------------------------------------
-- Tag associations (script_tags) - max 10 tags per script
-- ---------------------------------------------------------------------
INSERT INTO script_tags (script_id, tag)
SELECT s.id, t.tag
FROM devops_script s
JOIN (VALUES
  -- AWS
  ('AWS EC2 Instance with Best Practices', 'compute'),
  ('AWS EC2 Instance with Best Practices', 'ec2'),
  ('AWS EC2 Instance with Best Practices', 'infrastructure'),
  ('AWS EC2 Instance with Best Practices', 'security'),
  ('AWS VPC with Public/Private Subnets and Routing', 'networking'),
  ('AWS VPC with Public/Private Subnets and Routing', 'vpc'),
  ('AWS VPC with Public/Private Subnets and Routing', 'infrastructure'),
  ('AWS S3 Bucket with Versioning and Encryption', 'storage'),
  ('AWS S3 Bucket with Versioning and Encryption', 's3'),
  ('AWS S3 Bucket with Versioning and Encryption', 'backup'),
  ('AWS S3 Bucket with Versioning and Encryption', 'security'),
  ('AWS RDS PostgreSQL with Automated Backups', 'database'),
  ('AWS RDS PostgreSQL with Automated Backups', 'rds'),
  ('AWS RDS PostgreSQL with Automated Backups', 'backup'),
  ('AWS RDS MySQL with Automated Backups', 'database'),
  ('AWS RDS MySQL with Automated Backups', 'rds'),
  ('AWS RDS MySQL with Automated Backups', 'backup'),
  ('AWS Lambda Function with IAM Execution Role', 'serverless'),
  ('AWS Lambda Function with IAM Execution Role', 'lambda'),
  ('AWS Lambda Function with IAM Execution Role', 'compute'),
  ('AWS ECS Fargate Cluster and Service', 'containers'),
  ('AWS ECS Fargate Cluster and Service', 'ecs'),
  ('AWS ECS Fargate Cluster and Service', 'deployment'),
  ('AWS EKS Cluster with Managed Node Group', 'kubernetes'),
  ('AWS EKS Cluster with Managed Node Group', 'eks'),
  ('AWS EKS Cluster with Managed Node Group', 'containers'),
  ('AWS CloudFront CDN with S3 Origin', 'cdn'),
  ('AWS CloudFront CDN with S3 Origin', 'cloudfront'),
  ('AWS CloudFront CDN with S3 Origin', 'networking'),
  ('AWS Route53 Hosted Zone with Records', 'dns'),
  ('AWS Route53 Hosted Zone with Records', 'route53'),
  ('AWS Route53 Hosted Zone with Records', 'networking'),
  ('AWS Auto Scaling Group with Launch Template', 'autoscaling'),
  ('AWS Auto Scaling Group with Launch Template', 'compute'),
  ('AWS Auto Scaling Group with Launch Template', 'infrastructure'),
  ('AWS Security Group with Least Privilege', 'security'),
  ('AWS Security Group with Least Privilege', 'networking'),
  ('AWS Security Group with Least Privilege', 'firewall'),
  ('AWS IAM Role and Scoped Policy', 'security'),
  ('AWS IAM Role and Scoped Policy', 'iam'),
  ('AWS KMS Encryption Key with Rotation', 'security'),
  ('AWS KMS Encryption Key with Rotation', 'kms'),
  ('AWS KMS Encryption Key with Rotation', 'encryption'),
  -- Azure
  ('Azure Linux VM with Managed Disk', 'compute'),
  ('Azure Linux VM with Managed Disk', 'vm'),
  ('Azure Linux VM with Managed Disk', 'infrastructure'),
  ('Azure Virtual Network with Subnets', 'networking'),
  ('Azure Virtual Network with Subnets', 'vnet'),
  ('Azure Virtual Network with Subnets', 'infrastructure'),
  ('Azure Storage Account with Blob Container', 'storage'),
  ('Azure Storage Account with Blob Container', 'blob'),
  ('Azure SQL Database with Failover Group', 'database'),
  ('Azure SQL Database with Failover Group', 'sql'),
  ('Azure SQL Database with Failover Group', 'backup'),
  ('Azure App Service with Staging Slot', 'compute'),
  ('Azure App Service with Staging Slot', 'appservice'),
  ('Azure App Service with Staging Slot', 'deployment'),
  ('Azure AKS Cluster with System Node Pool', 'kubernetes'),
  ('Azure AKS Cluster with System Node Pool', 'aks'),
  ('Azure AKS Cluster with System Node Pool', 'containers'),
  -- GCP
  ('GCP Compute Engine Instance', 'compute'),
  ('GCP Compute Engine Instance', 'gce'),
  ('GCP Compute Engine Instance', 'infrastructure'),
  ('GCP VPC Network with Firewall Rules', 'networking'),
  ('GCP VPC Network with Firewall Rules', 'vpc'),
  ('GCP VPC Network with Firewall Rules', 'firewall'),
  ('GCP Cloud Storage Bucket with Lifecycle', 'storage'),
  ('GCP Cloud Storage Bucket with Lifecycle', 'backup'),
  ('GCP Cloud SQL PostgreSQL Instance', 'database'),
  ('GCP Cloud SQL PostgreSQL Instance', 'cloudsql'),
  ('GCP Cloud SQL PostgreSQL Instance', 'backup'),
  ('GCP Cloud Function (2nd Gen) HTTP Trigger', 'serverless'),
  ('GCP Cloud Function (2nd Gen) HTTP Trigger', 'functions'),
  ('GCP Cloud Function (2nd Gen) HTTP Trigger', 'compute'),
  ('GCP GKE Cluster with Node Pool', 'kubernetes'),
  ('GCP GKE Cluster with Node Pool', 'gke'),
  ('GCP GKE Cluster with Node Pool', 'containers'),
  -- Multi-cloud + extras
  ('Multi-Cloud HTTP Load Balancer Setup', 'networking'),
  ('Multi-Cloud HTTP Load Balancer Setup', 'loadbalancer'),
  ('Multi-Cloud HTTP Load Balancer Setup', 'multi-cloud'),
  ('Cross-Cloud VPN (AWS to GCP)', 'networking'),
  ('Cross-Cloud VPN (AWS to GCP)', 'vpn'),
  ('Cross-Cloud VPN (AWS to GCP)', 'security'),
  ('Multi-Cloud Disaster Recovery Storage', 'backup'),
  ('Multi-Cloud Disaster Recovery Storage', 'disaster-recovery'),
  ('Multi-Cloud Disaster Recovery Storage', 'storage'),
  ('AWS Application Load Balancer with Target Group', 'networking'),
  ('AWS Application Load Balancer with Target Group', 'loadbalancer'),
  ('Azure Public Load Balancer', 'networking'),
  ('Azure Public Load Balancer', 'loadbalancer'),
  ('GCP Cloud DNS Managed Zone', 'dns'),
  ('GCP Cloud DNS Managed Zone', 'networking')
) AS t(name, tag) ON s.name = t.name
WHERE NOT EXISTS (
  SELECT 1 FROM script_tags st WHERE st.script_id = s.id AND st.tag = t.tag
);

-- ---------------------------------------------------------------------
-- Cloud provider associations (script_cloud_providers)
-- ---------------------------------------------------------------------
INSERT INTO script_cloud_providers (script_id, provider)
SELECT s.id, p.provider
FROM devops_script s
JOIN (VALUES
  ('AWS EC2 Instance with Best Practices', 'aws'),
  ('AWS VPC with Public/Private Subnets and Routing', 'aws'),
  ('AWS S3 Bucket with Versioning and Encryption', 'aws'),
  ('AWS RDS PostgreSQL with Automated Backups', 'aws'),
  ('AWS RDS MySQL with Automated Backups', 'aws'),
  ('AWS Lambda Function with IAM Execution Role', 'aws'),
  ('AWS ECS Fargate Cluster and Service', 'aws'),
  ('AWS EKS Cluster with Managed Node Group', 'aws'),
  ('AWS CloudFront CDN with S3 Origin', 'aws'),
  ('AWS Route53 Hosted Zone with Records', 'aws'),
  ('AWS Auto Scaling Group with Launch Template', 'aws'),
  ('AWS Security Group with Least Privilege', 'aws'),
  ('AWS IAM Role and Scoped Policy', 'aws'),
  ('AWS KMS Encryption Key with Rotation', 'aws'),
  ('Azure Linux VM with Managed Disk', 'azure'),
  ('Azure Virtual Network with Subnets', 'azure'),
  ('Azure Storage Account with Blob Container', 'azure'),
  ('Azure SQL Database with Failover Group', 'azure'),
  ('Azure App Service with Staging Slot', 'azure'),
  ('Azure AKS Cluster with System Node Pool', 'azure'),
  ('GCP Compute Engine Instance', 'gcp'),
  ('GCP VPC Network with Firewall Rules', 'gcp'),
  ('GCP Cloud Storage Bucket with Lifecycle', 'gcp'),
  ('GCP Cloud SQL PostgreSQL Instance', 'gcp'),
  ('GCP Cloud Function (2nd Gen) HTTP Trigger', 'gcp'),
  ('GCP GKE Cluster with Node Pool', 'gcp'),
  ('Multi-Cloud HTTP Load Balancer Setup', 'multi-cloud'),
  ('Multi-Cloud HTTP Load Balancer Setup', 'aws'),
  ('Multi-Cloud HTTP Load Balancer Setup', 'gcp'),
  ('Cross-Cloud VPN (AWS to GCP)', 'multi-cloud'),
  ('Cross-Cloud VPN (AWS to GCP)', 'aws'),
  ('Cross-Cloud VPN (AWS to GCP)', 'gcp'),
  ('Multi-Cloud Disaster Recovery Storage', 'multi-cloud'),
  ('Multi-Cloud Disaster Recovery Storage', 'aws'),
  ('Multi-Cloud Disaster Recovery Storage', 'gcp'),
  ('AWS Application Load Balancer with Target Group', 'aws'),
  ('Azure Public Load Balancer', 'azure'),
  ('GCP Cloud DNS Managed Zone', 'gcp')
) AS p(name, provider) ON s.name = p.name
WHERE NOT EXISTS (
  SELECT 1 FROM script_cloud_providers scp WHERE scp.script_id = s.id AND scp.provider = p.provider
);

COMMIT;

-- =====================================================================
-- End of Terraform scripts seed data (32 scripts)
--   AWS: 14 | Azure: 6 | GCP: 6 | Multi-cloud: 3 | Cross-provider LB/DNS: 3
-- Difficulty mix: beginner (7), intermediate (16), advanced (9)
-- =====================================================================
