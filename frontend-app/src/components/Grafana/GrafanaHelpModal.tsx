/**
 * Grafana Help Modal Component
 *
 * Complete Terraform & SSL Configuration guide for setting up Grafana monitoring
 */

import React, { useEffect } from 'react';

interface GrafanaHelpModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const CodeBlock: React.FC<{ children: string }> = ({ children }) => (
  <pre className="grafana-code-block">{children}</pre>
);

const GrafanaHelpModal: React.FC<GrafanaHelpModalProps> = ({ isOpen, onClose }) => {
  // Handle escape key
  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isOpen) {
        onClose();
      }
    };
    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [isOpen, onClose]);

  // Prevent body scroll when modal is open
  useEffect(() => {
    if (isOpen) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => {
      document.body.style.overflow = '';
    };
  }, [isOpen]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      {/* Overlay */}
      <div
        className="grafana-modal-overlay fixed inset-0"
        onClick={onClose}
      />

      {/* Modal Content */}
      <div
        className="grafana-modal-content rounded-2xl p-0 w-full max-w-5xl mx-auto h-[85vh] flex flex-col grafana-fade-in"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b bg-gradient-to-r from-gray-50 to-white rounded-t-2xl">
          <div>
            <h3 className="font-bold text-xl text-gray-800">
              <i className="fab fa-aws text-orange-500 mr-2"></i>
              Grafana Monitoring Setup Guide
            </h3>
            <p className="text-sm text-gray-500 mt-1">Complete Terraform & SSL Configuration</p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-2xl w-10 h-10 flex items-center justify-center rounded-full hover:bg-gray-100 transition-all"
            aria-label="Close modal"
          >
            <i className="fas fa-times"></i>
          </button>
        </div>

        {/* Scrollable Content */}
        <div className="p-8 overflow-y-auto flex-1 grafana-modal-scroll">
          {/* Introduction */}
          <div className="mb-8 p-6 bg-gradient-to-r from-blue-50 to-indigo-50 rounded-xl border border-blue-100">
            <p className="text-gray-700 leading-relaxed">
              This guide will help you set up a complete monitoring infrastructure with Grafana, Prometheus, Loki,
              and SSL-secured domains using Terraform on AWS.
            </p>
          </div>

          {/* Prerequisites */}
          <h2 className="grafana-guide-h2">
            <i className="fas fa-clipboard-check text-green-500 mr-3"></i>
            Prerequisites
          </h2>
          <ul className="grafana-guide-list">
            <li><strong>AWS Account</strong> - An active Amazon Web Services account</li>
            <li><strong>AWS CLI</strong> - Installed and configured with your credentials</li>
            <li><strong>Terraform</strong> - Version 1.0 or higher installed</li>
            <li><strong>Domain Name</strong> - A registered domain that you can configure DNS records for</li>
            <li><strong>SSH Key Pair</strong> - An existing EC2 Key Pair in your AWS region</li>
          </ul>

          {/* Step 1 */}
          <h2 className="grafana-guide-h2">
            <i className="fas fa-folder-plus text-blue-500 mr-3"></i>
            Step 1: Create Project Structure
          </h2>
          <p className="grafana-guide-text">Create a new directory for your project and the necessary file structure:</p>
          <CodeBlock>{`mkdir grafana-monitoring-terraform
cd grafana-monitoring-terraform

# Create empty files
touch main.tf variables.tf outputs.tf user_data.sh`}</CodeBlock>

          {/* Step 2 */}
          <h2 className="grafana-guide-h2">
            <i className="fas fa-file-code text-purple-500 mr-3"></i>
            Step 2: Create Configuration Files
          </h2>

          {/* 2.1 variables.tf */}
          <h3 className="grafana-guide-h3">2.1 variables.tf</h3>
          <p className="grafana-guide-text">
            Define your environment variables.
            <span className="ml-2 bg-yellow-100 text-yellow-800 text-xs font-bold px-2 py-1 rounded">ACTION REQUIRED</span>
            <span className="ml-2 text-gray-600">Replace the default values with your own details.</span>
          </p>
          <CodeBlock>{`variable "aws_region" {
  description = "AWS region to deploy resources"
  type        = string
  default     = "ap-south-1"
}

variable "key_name" {
  description = "Name of your existing EC2 Key Pair"
  type        = string
  default     = "your-key-pair-name"
}

variable "instance_type" {
  description = "EC2 instance type for monitoring server"
  type        = string
  default     = "t2.medium"
}

variable "monitoring_server_name" {
  description = "Name tag for the monitoring server"
  type        = string
  default     = "Grafana-Monitoring-Server"
}

variable "uat_domain" {
  description = "UAT environment domain (e.g., grafana.uat.example.com)"
  type        = string
  default     = "grafana.uat.example.com"
}

variable "live_domain" {
  description = "Production environment domain (e.g., grafana.live.example.com)"
  type        = string
  default     = "grafana.live.example.com"
}

variable "ssl_email" {
  description = "Email for Let's Encrypt SSL certificate notifications"
  type        = string
  default     = "admin@example.com"
}`}</CodeBlock>

          {/* 2.2 main.tf */}
          <h3 className="grafana-guide-h3">2.2 main.tf</h3>
          <p className="grafana-guide-text">This file defines the VPC, Security Groups, and EC2 instance resources.</p>
          <CodeBlock>{`terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# Get the latest Ubuntu 22.04 AMI
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# VPC Configuration
resource "aws_vpc" "main" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "Monitoring-VPC"
  }
}

# Internet Gateway
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "Monitoring-IGW"
  }
}

# Public Subnet
resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.20.1.0/24"
  availability_zone       = "\${var.aws_region}a"
  map_public_ip_on_launch = true

  tags = {
    Name = "Monitoring-Public-Subnet"
  }
}

# Route Table
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "Monitoring-Public-RT"
  }
}

# Route Table Association
resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# Security Group for Monitoring Server
resource "aws_security_group" "monitoring" {
  name        = "monitoring-server-sg"
  description = "Security group for Grafana monitoring server"
  vpc_id      = aws_vpc.main.id

  # SSH access
  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # HTTP for Let's Encrypt
  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # HTTPS
  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Grafana
  ingress {
    description = "Grafana"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Prometheus
  ingress {
    description = "Prometheus"
    from_port   = 9090
    to_port     = 9090
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Node Exporter
  ingress {
    description = "Node Exporter"
    from_port   = 9100
    to_port     = 9100
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Loki
  ingress {
    description = "Loki"
    from_port   = 3100
    to_port     = 3100
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # All outbound traffic
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "Monitoring-Server-SG"
  }
}

# EC2 Instance for Monitoring Server
resource "aws_instance" "monitoring_server" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  key_name               = var.key_name
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.monitoring.id]

  user_data = templatefile("\${path.module}/user_data.sh", {
    uat_domain  = var.uat_domain
    live_domain = var.live_domain
    ssl_email   = var.ssl_email
  })

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
  }

  tags = {
    Name = var.monitoring_server_name
  }
}`}</CodeBlock>

          {/* 2.3 outputs.tf */}
          <h3 className="grafana-guide-h3">2.3 outputs.tf</h3>
          <p className="grafana-guide-text">Displays connection info after deployment.</p>
          <CodeBlock>{`output "monitoring_server_public_ip" {
  description = "Public IP address of the monitoring server"
  value       = aws_instance.monitoring_server.public_ip
}

output "monitoring_server_private_ip" {
  description = "Private IP address of the monitoring server"
  value       = aws_instance.monitoring_server.private_ip
}

output "grafana_direct_access" {
  description = "Direct access to Grafana (before DNS setup)"
  value       = "http://\${aws_instance.monitoring_server.public_ip}:3000"
}

output "next_steps" {
  description = "Next steps after deployment"
  value       = <<-EOT

    NEXT STEPS:
    ===========

    1. Configure DNS Records:
       - Add A record: \${var.uat_domain} -> \${aws_instance.monitoring_server.public_ip}
       - Add A record: \${var.live_domain} -> \${aws_instance.monitoring_server.public_ip}

    2. Wait 5-10 minutes for the server to complete setup

    3. SSH into the server to check progress:
       ssh -i your-key.pem ubuntu@\${aws_instance.monitoring_server.public_ip}

    4. Check setup logs:
       tail -f /var/log/cloud-init-output.log

    5. Once DNS propagates, access your dashboards:
       - UAT: https://\${var.uat_domain}
       - LIVE: https://\${var.live_domain}

    6. Default Grafana credentials:
       - Username: admin
       - Password: admin
  EOT
}`}</CodeBlock>

          {/* Step 3 */}
          <h2 className="grafana-guide-h2">
            <i className="fas fa-globe text-cyan-500 mr-3"></i>
            Step 3: Configure DNS Records
          </h2>
          <p className="grafana-guide-text">
            Before deploying, or immediately after, create <strong>A records</strong> in your DNS provider pointing to your server's public IP:
          </p>
          <ul className="grafana-guide-list">
            <li><code className="grafana-inline-code">grafana.uat.example.com</code> → Server Public IP</li>
            <li><code className="grafana-inline-code">grafana.live.example.com</code> → Server Public IP</li>
          </ul>

          {/* Step 4 */}
          <h2 className="grafana-guide-h2">
            <i className="fas fa-rocket text-indigo-500 mr-3"></i>
            Step 4: Deploy Infrastructure
          </h2>
          <CodeBlock>{`# Initialize Terraform
terraform init

# Review Plan
terraform plan

# Deploy (Type 'yes' when prompted)
terraform apply`}</CodeBlock>
          <div className="mt-4 p-4 bg-blue-50 rounded-xl border border-blue-100 flex items-center">
            <i className="fas fa-clock text-blue-500 mr-3 text-lg"></i>
            <span className="text-gray-700"><strong>Wait 5-10 minutes</strong> for the server to install all software.</span>
          </div>

          {/* Step 5 */}
          <h2 className="grafana-guide-h2">
            <i className="fas fa-check-circle text-green-500 mr-3"></i>
            Step 5: Verify & Access
          </h2>
          <p className="grafana-guide-text">1. Get your IP and instructions:</p>
          <CodeBlock>{`terraform output`}</CodeBlock>
          <p className="grafana-guide-text mt-4">2. Access Grafana (after DNS propagation):</p>
          <ul className="grafana-guide-list">
            <li><strong>UAT:</strong> https://grafana.uat.example.com</li>
            <li><strong>LIVE:</strong> https://grafana.live.example.com</li>
          </ul>
          <div className="mt-4 p-4 bg-gray-50 rounded-xl border border-gray-200">
            <p className="font-semibold text-gray-800 mb-2">Default Credentials:</p>
            <ul className="text-gray-600 space-y-1">
              <li>Username: <code className="grafana-inline-code">admin</code></li>
              <li>Password: <code className="grafana-inline-code">admin</code></li>
            </ul>
          </div>

          {/* Architecture Components */}
          <h2 className="grafana-guide-h2">
            <i className="fas fa-sitemap text-purple-500 mr-3"></i>
            Architecture Components
          </h2>
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse mt-4 mb-8 grafana-table">
              <thead>
                <tr className="bg-gradient-to-r from-gray-50 to-gray-100">
                  <th className="p-4 font-semibold text-gray-700 border-b-2 border-gray-200">Component</th>
                  <th className="p-4 font-semibold text-gray-700 border-b-2 border-gray-200">Port</th>
                  <th className="p-4 font-semibold text-gray-700 border-b-2 border-gray-200">Purpose</th>
                </tr>
              </thead>
              <tbody>
                <tr className="hover:bg-gray-50 transition-colors">
                  <td className="p-4 border-b border-gray-100 font-medium">Grafana</td>
                  <td className="p-4 border-b border-gray-100"><code className="grafana-inline-code">3000</code></td>
                  <td className="p-4 border-b border-gray-100 text-gray-600">Dashboard & Visualization</td>
                </tr>
                <tr className="hover:bg-gray-50 transition-colors">
                  <td className="p-4 border-b border-gray-100 font-medium">Prometheus</td>
                  <td className="p-4 border-b border-gray-100"><code className="grafana-inline-code">9090</code></td>
                  <td className="p-4 border-b border-gray-100 text-gray-600">Metrics Collection</td>
                </tr>
                <tr className="hover:bg-gray-50 transition-colors">
                  <td className="p-4 border-b border-gray-100 font-medium">Loki</td>
                  <td className="p-4 border-b border-gray-100"><code className="grafana-inline-code">3100</code></td>
                  <td className="p-4 border-b border-gray-100 text-gray-600">Log Aggregation</td>
                </tr>
                <tr className="hover:bg-gray-50 transition-colors">
                  <td className="p-4 font-medium">Node Exporter</td>
                  <td className="p-4"><code className="grafana-inline-code">9100</code></td>
                  <td className="p-4 text-gray-600">System Metrics</td>
                </tr>
              </tbody>
            </table>
          </div>

          {/* Troubleshooting */}
          <h2 className="grafana-guide-h2">
            <i className="fas fa-tools text-orange-500 mr-3"></i>
            Troubleshooting
          </h2>
          <div className="space-y-4">
            <div className="p-5 bg-gradient-to-r from-red-50 to-orange-50 rounded-xl border border-red-100">
              <h4 className="font-bold text-red-700 flex items-center">
                <i className="fas fa-exclamation-circle mr-2"></i>
                Issue: DNS not resolving / SSL Failed
              </h4>
              <p className="text-sm text-red-600 mt-2">
                Wait longer for DNS propagation (up to 1 hour). If the script timed out, run Certbot manually via SSH:
              </p>
              <code className="block mt-3 bg-white p-3 rounded-lg border border-red-200 text-xs font-mono text-gray-700">
                sudo certbot certonly --webroot -w /var/www/html -d your.domain.com
              </code>
            </div>
            <div className="p-5 bg-gradient-to-r from-blue-50 to-indigo-50 rounded-xl border border-blue-100">
              <h4 className="font-bold text-blue-700 flex items-center">
                <i className="fas fa-docker mr-2"></i>
                Issue: Docker containers not running
              </h4>
              <p className="text-sm text-blue-600 mt-2">SSH into the server and check container logs:</p>
              <code className="block mt-3 bg-white p-3 rounded-lg border border-blue-200 text-xs font-mono text-gray-700 whitespace-pre">
{`docker ps -a
docker logs grafana`}
              </code>
            </div>
          </div>

          {/* Cleanup */}
          <h2 className="grafana-guide-h2">
            <i className="fas fa-trash-alt text-gray-500 mr-3"></i>
            Cleanup
          </h2>
          <p className="grafana-guide-text">To remove all resources created by Terraform:</p>
          <CodeBlock>{`terraform destroy`}</CodeBlock>
        </div>
      </div>
    </div>
  );
};

export default GrafanaHelpModal;
