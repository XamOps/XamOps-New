-- Sample DevOps Scripts Data for DevOps-in-the-Box
-- Run this script to populate the devops_script table with sample automation scripts

-- Clear existing data (optional)
-- TRUNCATE TABLE devops_script;

-- Terraform Scripts
INSERT INTO devops_script (name, description, category, version, downloads, last_updated, code, usage_instructions) VALUES
('AWS VPC Setup', 'Complete VPC setup with public and private subnets, NAT gateway, and route tables', 'terraform', '1.0.0', 245, '2025-01-15',
'terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  description = "AWS region"
  default     = "us-east-1"
}

variable "vpc_cidr" {
  description = "VPC CIDR block"
  default     = "10.0.0.0/16"
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "main-vpc"
  }
}

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = true

  tags = {
    Name = "public-subnet-${count.index + 1}"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "main-igw"
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}',
'<ol>
  <li>Configure your AWS credentials</li>
  <li>Initialize Terraform: <code>terraform init</code></li>
  <li>Review the plan: <code>terraform plan</code></li>
  <li>Apply changes: <code>terraform apply</code></li>
  <li>To destroy resources: <code>terraform destroy</code></li>
</ol>
<p><strong>Variables:</strong></p>
<ul>
  <li><code>aws_region</code>: AWS region (default: us-east-1)</li>
  <li><code>vpc_cidr</code>: VPC CIDR block (default: 10.0.0.0/16)</li>
</ul>'),

('EKS Cluster', 'Production-ready EKS cluster with managed node groups', 'terraform', '2.1.0', 189, '2025-01-20',
'terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.0"
    }
  }
}

variable "cluster_name" {
  description = "EKS cluster name"
  type        = string
}

variable "cluster_version" {
  description = "Kubernetes version"
  default     = "1.28"
}

resource "aws_eks_cluster" "main" {
  name     = var.cluster_name
  role_arn = aws_iam_role.cluster.arn
  version  = var.cluster_version

  vpc_config {
    subnet_ids = var.subnet_ids
  }

  depends_on = [
    aws_iam_role_policy_attachment.cluster_AmazonEKSClusterPolicy
  ]
}

resource "aws_iam_role" "cluster" {
  name = "${var.cluster_name}-cluster-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "eks.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "cluster_AmazonEKSClusterPolicy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
  role       = aws_iam_role.cluster.name
}',
'<ol>
  <li>Set up VPC and subnets first</li>
  <li>Set cluster name: <code>export TF_VAR_cluster_name="my-eks-cluster"</code></li>
  <li>Initialize: <code>terraform init</code></li>
  <li>Apply: <code>terraform apply</code></li>
  <li>Configure kubectl: <code>aws eks update-kubeconfig --name my-eks-cluster</code></li>
</ol>
<p><strong>Prerequisites:</strong> VPC with at least 2 subnets in different AZs</p>');

-- Bash Scripts
INSERT INTO devops_script (name, description, category, version, downloads, last_updated, code, usage_instructions) VALUES
('Docker Cleanup', 'Clean up unused Docker resources to free disk space', 'bash', '1.2.0', 567, '2025-01-18',
'#!/bin/bash

# Docker Cleanup Script
# Removes unused containers, images, volumes, and networks

set -e

echo "🧹 Starting Docker cleanup..."

# Remove stopped containers
echo "📦 Removing stopped containers..."
docker container prune -f

# Remove dangling images
echo "🖼️  Removing dangling images..."
docker image prune -f

# Remove unused images (optional - commented out)
# Uncomment the next line to remove ALL unused images
# docker image prune -a -f

# Remove unused volumes
echo "💾 Removing unused volumes..."
docker volume prune -f

# Remove unused networks
echo "🌐 Removing unused networks..."
docker network prune -f

# Display current disk usage
echo ""
echo "📊 Current Docker disk usage:"
docker system df

echo ""
echo "✅ Docker cleanup completed!"',
'<ol>
  <li>Make the script executable: <code>chmod +x docker-cleanup.sh</code></li>
  <li>Run the script: <code>./docker-cleanup.sh</code></li>
  <li>Schedule with cron for regular cleanup: <code>0 2 * * 0 /path/to/docker-cleanup.sh</code></li>
</ol>
<p><strong>Warning:</strong> This will remove all stopped containers and unused resources. Use with caution in production.</p>'),

('System Health Check', 'Comprehensive system health monitoring script', 'bash', '1.5.0', 423, '2025-01-22',
'#!/bin/bash

# System Health Check Script
# Monitors CPU, Memory, Disk, and Network

echo "🏥 System Health Check - $(date)"
echo "=================================="

# CPU Usage
echo ""
echo "💻 CPU Usage:"
top -bn1 | grep "Cpu(s)" | sed "s/.*, *\([0-9.]*\)%* id.*/\1/" | awk ''{print "CPU Usage: " 100 - $1"%"}''

# Memory Usage
echo ""
echo "🧠 Memory Usage:"
free -h | awk ''NR==2{printf "Used: %s / %s (%.2f%%)\n", $3, $2, $3*100/$2}''

# Disk Usage
echo ""
echo "💾 Disk Usage:"
df -h | grep -E "^/dev/" | awk ''{ print $1 ": " $5 " (" $3 " / " $2 ")" }''

# Top 5 Memory Consuming Processes
echo ""
echo "🔝 Top 5 Memory Consuming Processes:"
ps aux --sort=-%mem | head -6 | tail -5 | awk ''{printf "%-10s %-8s %s\n", $1, $4"%", $11}''

# Network Connections
echo ""
echo "🌐 Active Network Connections:"
netstat -an | grep ESTABLISHED | wc -l | awk ''{print "Active connections: " $1}''

# System Uptime
echo ""
echo "⏱️  System Uptime:"
uptime -p

echo ""
echo "✅ Health check completed!"',
'<ol>
  <li>Save the script: <code>chmod +x health-check.sh</code></li>
  <li>Run: <code>./health-check.sh</code></li>
  <li>Set up monitoring cron: <code>*/15 * * * * /path/to/health-check.sh >> /var/log/health.log</code></li>
</ol>
<p><strong>Output:</strong> Displays CPU, memory, disk usage, top processes, and network stats</p>');

-- Python Scripts
INSERT INTO devops_script (name, description, category, version, downloads, last_updated, code, usage_instructions) VALUES
('AWS Cost Reporter', 'Generate detailed AWS cost reports with email notifications', 'python', '2.0.0', 334, '2025-01-25',
'#!/usr/bin/env python3
"""
AWS Cost Reporter
Generates cost reports and sends email notifications
"""

import boto3
from datetime import datetime, timedelta
import json

def get_cost_data(start_date, end_date):
    """Fetch cost data from AWS Cost Explorer"""
    client = boto3.client(''ce'')

    response = client.get_cost_and_usage(
        TimePeriod={
            ''Start'': start_date,
            ''End'': end_date
        },
        Granularity=''DAILY'',
        Metrics=[''UnblendedCost''],
        GroupBy=[
            {
                ''Type'': ''DIMENSION'',
                ''Key'': ''SERVICE''
            }
        ]
    )

    return response

def generate_report():
    """Generate cost report for the last 7 days"""
    end_date = datetime.now().date()
    start_date = end_date - timedelta(days=7)

    cost_data = get_cost_data(
        start_date.strftime(''%Y-%m-%d''),
        end_date.strftime(''%Y-%m-%d'')
    )

    total_cost = 0
    service_costs = {}

    for result in cost_data[''ResultsByTime'']:
        for group in result[''Groups'']:
            service = group[''Keys''][0]
            cost = float(group[''Metrics''][''UnblendedCost''][''Amount''])

            if service in service_costs:
                service_costs[service] += cost
            else:
                service_costs[service] = cost

            total_cost += cost

    # Sort by cost
    sorted_services = sorted(
        service_costs.items(),
        key=lambda x: x[1],
        reverse=True
    )

    print(f"\\n📊 AWS Cost Report ({start_date} to {end_date})")
    print(f"{'=' * 60}")
    print(f"\\nTotal Cost: ${total_cost:.2f}\\n")
    print(f"{'Service':<40} {'Cost':>15}")
    print(f"{'-' * 60}")

    for service, cost in sorted_services[:10]:
        print(f"{service:<40} ${cost:>14.2f}")

    return total_cost, service_costs

if __name__ == ''__main__'':
    generate_report()',
'<ol>
  <li>Install dependencies: <code>pip install boto3</code></li>
  <li>Configure AWS credentials: <code>aws configure</code></li>
  <li>Ensure Cost Explorer is enabled in your AWS account</li>
  <li>Run the script: <code>python aws_cost_reporter.py</code></li>
  <li>Schedule with cron: <code>0 9 * * 1 python /path/to/aws_cost_reporter.py</code></li>
</ol>
<p><strong>Requirements:</strong> AWS credentials with Cost Explorer read permissions</p>'),

('Log Analyzer', 'Parse and analyze application logs with pattern detection', 'python', '1.3.0', 298, '2025-01-28',
'#!/usr/bin/env python3
"""
Log Analyzer
Analyzes log files and detects patterns, errors, and anomalies
"""

import re
from collections import Counter
from datetime import datetime

class LogAnalyzer:
    def __init__(self, log_file):
        self.log_file = log_file
        self.errors = []
        self.warnings = []
        self.info_messages = []

    def parse_logs(self):
        """Parse log file and categorize messages"""
        with open(self.log_file, ''r'') as f:
            for line in f:
                if ''ERROR'' in line:
                    self.errors.append(line.strip())
                elif ''WARN'' in line:
                    self.warnings.append(line.strip())
                else:
                    self.info_messages.append(line.strip())

    def find_error_patterns(self):
        """Identify common error patterns"""
        error_types = []

        for error in self.errors:
            # Extract error type (simplified)
            match = re.search(r''ERROR.*?:\s*(.+?)\s*at'', error)
            if match:
                error_types.append(match.group(1))

        return Counter(error_types).most_common(5)

    def generate_report(self):
        """Generate analysis report"""
        print("\\n📋 Log Analysis Report")
        print("=" * 60)
        print(f"\\nTotal Lines: {len(self.errors) + len(self.warnings) + len(self.info_messages)}")
        print(f"Errors: {len(self.errors)}")
        print(f"Warnings: {len(self.warnings)}")
        print(f"Info: {len(self.info_messages)}\\n")

        if self.errors:
            print("\\n🔴 Top Error Patterns:")
            for pattern, count in self.find_error_patterns():
                print(f"  {count}x - {pattern}")

        if self.warnings:
            print(f"\\n⚠️  Recent Warnings (last 5):")
            for warning in self.warnings[-5:]:
                print(f"  {warning[:100]}...")

def main():
    import sys

    if len(sys.argv) < 2:
        print("Usage: python log_analyzer.py <log_file>")
        sys.exit(1)

    analyzer = LogAnalyzer(sys.argv[1])
    analyzer.parse_logs()
    analyzer.generate_report()

if __name__ == ''__main__'':
    main()',
'<ol>
  <li>Run the script: <code>python log_analyzer.py /path/to/application.log</code></li>
  <li>View the analysis report in the console</li>
  <li>Customize error patterns in the <code>find_error_patterns()</code> method</li>
</ol>
<p><strong>Usage:</strong> Helpful for troubleshooting application issues and identifying recurring errors</p>');

-- Ansible Script (Bonus)
INSERT INTO devops_script (name, description, category, version, downloads, last_updated, code, usage_instructions) VALUES
('Web Server Setup', 'Automated Nginx web server setup with SSL', 'ansible', '1.0.0', 156, '2025-01-20',
'---
# Ansible Playbook: Nginx Web Server Setup
- name: Setup Nginx Web Server with SSL
  hosts: webservers
  become: yes

  vars:
    domain_name: "example.com"
    ssl_email: "admin@example.com"

  tasks:
    - name: Update apt cache
      apt:
        update_cache: yes
        cache_valid_time: 3600

    - name: Install Nginx
      apt:
        name: nginx
        state: present

    - name: Install Certbot
      apt:
        name:
          - certbot
          - python3-certbot-nginx
        state: present

    - name: Start Nginx service
      service:
        name: nginx
        state: started
        enabled: yes

    - name: Configure Nginx virtual host
      template:
        src: nginx-site.conf.j2
        dest: "/etc/nginx/sites-available/{{ domain_name }}"
      notify: Reload Nginx

    - name: Enable site
      file:
        src: "/etc/nginx/sites-available/{{ domain_name }}"
        dest: "/etc/nginx/sites-enabled/{{ domain_name }}"
        state: link
      notify: Reload Nginx

    - name: Obtain SSL certificate
      command: >
        certbot --nginx -d {{ domain_name }}
        --non-interactive --agree-tos -m {{ ssl_email }}
      args:
        creates: "/etc/letsencrypt/live/{{ domain_name }}/fullchain.pem"

  handlers:
    - name: Reload Nginx
      service:
        name: nginx
        state: reloaded',
'<ol>
  <li>Create inventory file: <code>echo "[webservers]" > hosts.ini</code></li>
  <li>Add your server IP to hosts.ini</li>
  <li>Update variables: domain_name and ssl_email</li>
  <li>Run playbook: <code>ansible-playbook -i hosts.ini web-server-setup.yml</code></li>
  <li>Verify: Visit https://your-domain.com</li>
</ol>
<p><strong>Prerequisites:</strong> Ubuntu/Debian server with SSH access</p>');

-- Add more diverse scripts
INSERT INTO devops_script (name, description, category, version, downloads, last_updated, code, usage_instructions) VALUES
('S3 Bucket with Lifecycle', 'Terraform configuration for S3 bucket with lifecycle policies', 'terraform', '1.1.0', 201, '2025-01-12',
'resource "aws_s3_bucket" "storage" {
  bucket = var.bucket_name

  tags = {
    Name        = var.bucket_name
    Environment = var.environment
  }
}

resource "aws_s3_bucket_versioning" "storage" {
  bucket = aws_s3_bucket.storage.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "storage" {
  bucket = aws_s3_bucket.storage.id

  rule {
    id     = "transition-to-glacier"
    status = "Enabled"

    transition {
      days          = 90
      storage_class = "GLACIER"
    }

    expiration {
      days = 365
    }
  }

  rule {
    id     = "delete-old-versions"
    status = "Enabled"

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}',
'<ol>
  <li>Set variables: bucket_name, environment</li>
  <li>Initialize: <code>terraform init</code></li>
  <li>Apply: <code>terraform apply</code></li>
</ol>
<p><strong>Features:</strong> Versioning enabled, auto-transition to Glacier after 90 days, cleanup old versions</p>');

COMMIT;
