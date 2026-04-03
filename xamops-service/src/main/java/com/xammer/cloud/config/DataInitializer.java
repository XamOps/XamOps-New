package com.xammer.cloud.config;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.Optional;
import org.springframework.context.annotation.Lazy;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final @Lazy PasswordEncoder passwordEncoder;

    @Value("${xamops.superadmin.password:SuperAdmin@123}")
    private String superAdminPassword;

    public DataInitializer(ClientRepository clientRepository, UserRepository userRepository, @Lazy PasswordEncoder passwordEncoder) {
        this.clientRepository = clientRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        // 1. Ensure a default client exists (Required for foreign key constraints)
        Client defaultClient;
        if (clientRepository.count() == 0) {
            logger.info("Initializing default client...");
            defaultClient = new Client("Default Client");
            clientRepository.save(defaultClient);
        } else {
            // Fetch the first available client to assign to the superadmin
            defaultClient = clientRepository.findAll().get(0);
        }

        // 2. Create SuperAdmin User (The Master User)
        createSuperAdminIfMissing(defaultClient);

        // 3. Create Standard Users (Only if DB is empty)
        if (userRepository.count() <= 1) { // <= 1 because superadmin might have just been created
            logger.info("Initializing default demo users...");

            // Admin User
            // createUserIfMissing("admin", "password", "ROLE_BILLOPS_ADMIN", defaultClient);

            // XamOps User
            // createUserIfMissing("xamopsuser", "password", "ROLE_XAMOPS", defaultClient);

            // BillOps User
            // createUserIfMissing("billopsuser", "password", "ROLE_BILLOPS", defaultClient);

            logger.info("Default users created successfully.");
        }
    }

    private void createSuperAdminIfMissing(Client client) {
        Optional<User> existingSuperAdmin = userRepository.findByUsername("superadmin");
        if (existingSuperAdmin.isEmpty()) {
            logger.info("Creating Default SuperAdmin User...");
            User superAdmin = new User();
            superAdmin.setUsername("superadmin");
            superAdmin.setPassword(passwordEncoder.encode(superAdminPassword)); // Initial Password
            superAdmin.setRole("ROLE_SUPER_ADMIN");
            superAdmin.setClient(client);
            superAdmin.setEmail("superadmin@xamops.com");
            userRepository.save(superAdmin);
            logger.info(">>> SUPERADMIN CREATED | User: superadmin <<<");
        }
    }

    private void createUserIfMissing(String username, String password, String role, Client client) {
        if (userRepository.findByUsername(username).isEmpty()) {
            User user = new User(username, passwordEncoder.encode(password), client);
            user.setRole(role);
            userRepository.save(user);
        }
    }

    @Bean
    public CommandLineRunner loadDevOpsScripts(DevOpsScriptRepository repository) {
        return args -> {
            if (repository.count() == 0) {
                logger.info("Loading ~50 sample DevOps scripts into database...");

                // --- AWS Terraform Scripts ---

                // 1. Basic S3 Bucket (Public Read)
                repository.save(new DevOpsScript("AWS S3 Public Bucket", "Terraform script for a public-read S3 bucket.", "terraform",
                    """
                    resource "aws_s3_bucket" "public_bucket" {
                      bucket = "my-unique-public-bucket-name-12345" # CHANGE THIS
                      acl    = "public-read"
                      tags = { Name = "Public Web Bucket", Environment = "Dev" }
                    }
                    """,
                    "<ol><li>Change bucket name.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 2. Basic S3 Bucket (Private)
                repository.save(new DevOpsScript("AWS S3 Private Bucket", "Terraform script for a private S3 bucket.", "terraform",
                    """
                    resource "aws_s3_bucket" "private_bucket" {
                      bucket = "my-unique-private-bucket-name-67890" # CHANGE THIS
                      acl    = "private"
                      tags = { Name = "Private Data Bucket", Environment = "Prod" }
                    }
                    """,
                    "<ol><li>Change bucket name.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 3. S3 Bucket with Versioning
                repository.save(new DevOpsScript("AWS S3 Bucket with Versioning", "Creates an S3 bucket with versioning enabled.", "terraform",
                    """
                    resource "aws_s3_bucket" "versioned_bucket" {
                      bucket = "my-versioned-bucket-unique-name" # CHANGE THIS
                      acl    = "private"
                      versioning {
                        enabled = true
                      }
                      tags = { Name = "Versioned Artifacts" }
                    }
                    """,
                    "<ol><li>Change bucket name.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 4. Basic EC2 Instance (Ubuntu)
                repository.save(new DevOpsScript("AWS EC2 Ubuntu Instance", "Simple Terraform script for an AWS EC2 t3.micro (Ubuntu).", "terraform",
                    """
                    provider "aws" { region = "us-east-1" } # Specify region
                    data "aws_ami" "ubuntu" {
                      most_recent = true
                      filter { name = "name"; values = ["ubuntu/images/hvm-ssd/ubuntu-focal-20.04-amd64-server-*"] }
                      filter { name = "virtualization-type"; values = ["hvm"] }
                      owners = ["099720109477"] # Canonical
                    }
                    resource "aws_instance" "web" {
                      ami           = data.aws_ami.ubuntu.id
                      instance_type = "t3.micro"
                      tags = { Name = "HelloWorld" }
                      # Note: Assumes default VPC/Subnet. Add network config for specifics.
                    }
                    """,
                    "<ol><li>Ensure AWS credentials are configured.</li><li>Run `terraform init && terraform apply`. Requires default VPC.</li></ol>",
                    "1.0", 0, "N/A"));

                // 5. Basic EC2 Instance (Amazon Linux 2)
                repository.save(new DevOpsScript("AWS EC2 Amazon Linux 2", "Simple Terraform for EC2 t3.micro (Amazon Linux 2).", "terraform",
                     """
                     provider "aws" { region = "us-west-2" } # Specify region
                     data "aws_ami" "amzlinux2" {
                       most_recent = true
                       owners      = ["amazon"]
                       filter { name = "name"; values = ["amzn2-ami-hvm-*-x86_64-gp2"] }
                       filter { name = "virtualization-type"; values = ["hvm"] }
                     }
                     resource "aws_instance" "app_server" {
                       ami           = data.aws_ami.amzlinux2.id
                       instance_type = "t3.micro"
                       tags = { Name = "AppServer" }
                       # Note: Assumes default VPC/Subnet.
                     }
                     """,
                    "<ol><li>Set AWS region.</li><li>Run `terraform init && terraform apply`. Requires default VPC.</li></ol>",
                    "1.0", 0, "N/A"));

                // 6. AWS Security Group (Allow SSH & HTTP)
                repository.save(new DevOpsScript("AWS Security Group SSH/HTTP", "Creates a security group allowing SSH (22) and HTTP (80) inbound.", "terraform",
                    """
                    resource "aws_security_group" "allow_web_ssh" {
                      name        = "allow_web_ssh"
                      description = "Allow SSH and HTTP inbound traffic"
                      vpc_id      = "vpc-xxxxxxxx" # CHANGE THIS to your VPC ID

                      ingress {
                        description = "SSH from anywhere"
                        from_port   = 22
                        to_port     = 22
                        protocol    = "tcp"
                        cidr_blocks = ["0.0.0.0/0"]
                      }
                      ingress {
                        description = "HTTP from anywhere"
                        from_port   = 80
                        to_port     = 80
                        protocol    = "tcp"
                        cidr_blocks = ["0.0.0.0/0"]
                      }
                      egress {
                        from_port   = 0
                        to_port     = 0
                        protocol    = "-1" # Allow all outbound
                        cidr_blocks = ["0.0.0.0/0"]
                      }
                      tags = { Name = "web-ssh-sg" }
                    }
                    """,
                    "<ol><li>Replace `vpc-xxxxxxxx` with your actual VPC ID.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 7. AWS VPC
                repository.save(new DevOpsScript("AWS Basic VPC", "Creates a simple AWS VPC with a CIDR block.", "terraform",
                    """
                    resource "aws_vpc" "main" {
                      cidr_block       = "10.0.0.0/16"
                      enable_dns_support = true
                      enable_dns_hostnames = true
                      tags = {
                        Name = "main-vpc"
                      }
                    }
                    """,
                    "<ol><li>Adjust CIDR block if needed.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 8. AWS Subnet
                repository.save(new DevOpsScript("AWS Public Subnet", "Creates a public subnet within a VPC.", "terraform",
                    """
                    resource "aws_subnet" "public_subnet" {
                      vpc_id     = aws_vpc.main.id # Assumes VPC defined elsewhere (e.g., previous script)
                      cidr_block = "10.0.1.0/24"
                      map_public_ip_on_launch = true # Make it public
                      availability_zone = "us-east-1a" # Change AZ if needed
                      tags = {
                        Name = "public-subnet-1"
                      }
                    }
                    # Requires aws_vpc.main to be defined
                    """,
                    "<ol><li>Ensure an `aws_vpc` resource named `main` exists in your config.</li><li>Set correct AZ.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 9. AWS Internet Gateway
                repository.save(new DevOpsScript("AWS Internet Gateway", "Creates an Internet Gateway for a VPC.", "terraform",
                    """
                    resource "aws_internet_gateway" "gw" {
                      vpc_id = aws_vpc.main.id # Assumes VPC 'main' exists
                      tags = {
                        Name = "main-igw"
                      }
                    }
                    # Requires aws_vpc.main to be defined
                    """,
                     "<ol><li>Ensure an `aws_vpc` resource named `main` exists.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 10. AWS Route Table & Association
                repository.save(new DevOpsScript("AWS Public Route Table", "Creates a route table for public subnet traffic.", "terraform",
                    """
                    resource "aws_route_table" "public_rt" {
                      vpc_id = aws_vpc.main.id # Assumes VPC 'main' exists
                      route {
                        cidr_block = "0.0.0.0/0"
                        gateway_id = aws_internet_gateway.gw.id # Assumes IGW 'gw' exists
                      }
                      tags = { Name = "public-route-table" }
                    }
                    resource "aws_route_table_association" "public_assoc" {
                      subnet_id      = aws_subnet.public_subnet.id # Assumes subnet 'public_subnet' exists
                      route_table_id = aws_route_table.public_rt.id
                    }
                    # Requires aws_vpc.main, aws_internet_gateway.gw, aws_subnet.public_subnet
                    """,
                    "<ol><li>Ensure VPC, IGW, and Subnet resources exist.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 11. AWS IAM User
                repository.save(new DevOpsScript("AWS IAM User", "Creates a basic IAM user.", "terraform",
                    """
                    resource "aws_iam_user" "user" {
                      name = "test-user"
                      path = "/system/"
                      tags = { TagKey = "TagValue" }
                    }
                    """,
                    "<ol><li>Change username if desired.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 12. AWS IAM Policy (S3 Read Only)
                repository.save(new DevOpsScript("AWS IAM Policy (S3 ReadOnly)", "Creates an IAM policy for S3 read-only access.", "terraform",
                     """
                     resource "aws_iam_policy" "s3_readonly_policy" {
                       name        = "S3ReadOnlyAccessPolicy"
                       description = "Policy for read-only access to S3"
                       policy = jsonencode({
                         Version = "2012-10-17"
                         Statement = [
                           {
                             Action = [
                               "s3:Get*",
                               "s3:List*"
                             ]
                             Effect   = "Allow"
                             Resource = "*"
                           },
                         ]
                       })
                     }
                     """,
                    "<ol><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 13. AWS IAM Role (EC2 Assume Role)
                repository.save(new DevOpsScript("AWS IAM Role for EC2", "Creates an IAM role that EC2 instances can assume.", "terraform",
                    """
                    resource "aws_iam_role" "ec2_role" {
                      name = "ec2_assume_role"
                      assume_role_policy = jsonencode({
                        Version = "2012-10-17"
                        Statement = [
                          {
                            Action = "sts:AssumeRole"
                            Effect = "Allow"
                            Principal = {
                              Service = "ec2.amazonaws.com"
                            }
                          },
                        ]
                      })
                      tags = { role = "ec2-service-role" }
                    }
                    """,
                    "<ol><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                 // 14. Attach IAM Policy to Role
                repository.save(new DevOpsScript("AWS Attach Policy to Role", "Attaches an IAM policy to an IAM role.", "terraform",
                    """
                    resource "aws_iam_role_policy_attachment" "attach_s3_read" {
                      role       = aws_iam_role.ec2_role.name       # Assumes role 'ec2_role' exists
                      policy_arn = aws_iam_policy.s3_readonly_policy.arn # Assumes policy 's3_readonly_policy' exists
                    }
                    # Requires aws_iam_role.ec2_role and aws_iam_policy.s3_readonly_policy
                    """,
                    "<ol><li>Ensure the specified role and policy resources exist.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 15. AWS RDS Instance (PostgreSQL)
                repository.save(new DevOpsScript("AWS RDS PostgreSQL Instance", "Basic RDS PostgreSQL instance (db.t3.micro).", "terraform",
                    """
                    resource "aws_db_instance" "default" {
                      allocated_storage    = 10
                      engine               = "postgres"
                      engine_version       = "13.7"
                      instance_class       = "db.t3.micro"
                      name                 = "mydb" # DB Name
                      username             = "foo"
                      password             = "foobarbaz" # CHANGE THIS - use variables/secrets in real use
                      parameter_group_name = "default.postgres13"
                      skip_final_snapshot  = true
                      # Note: Needs DB Subnet Group and VPC Security Group for connectivity
                    }
                    """,
                    "<ol><li>Change the password (use secrets management ideally).</li><li>Configure DB Subnet Group and Security Groups.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 16. AWS DynamoDB Table
                repository.save(new DevOpsScript("AWS DynamoDB Table", "Simple DynamoDB table with a hash key.", "terraform",
                    """
                    resource "aws_dynamodb_table" "basic-dynamodb-table" {
                      name           = "GameScores"
                      billing_mode   = "PAY_PER_REQUEST"
                      hash_key       = "UserId"

                      attribute {
                        name = "UserId"
                        type = "S"
                      }
                      tags = { Name = "dynamodb-table-1" }
                    }
                    """,
                    "<ol><li>Change table name if needed.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 17. AWS SQS Queue
                repository.save(new DevOpsScript("AWS SQS Standard Queue", "Creates a standard SQS queue.", "terraform",
                    """
                    resource "aws_sqs_queue" "terraform_queue" {
                      name                      = "terraform-example-queue" # CHANGE THIS if needed
                      delay_seconds             = 90
                      max_message_size          = 2048
                      message_retention_seconds = 86400 # 1 day
                      receive_wait_time_seconds = 10
                      tags = { Environment = "dev" }
                    }
                    """,
                    "<ol><li>Change queue name if desired.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 18. AWS SNS Topic
                repository.save(new DevOpsScript("AWS SNS Topic", "Creates a standard SNS topic.", "terraform",
                    """
                    resource "aws_sns_topic" "user_updates" {
                      name = "user-updates-topic" # CHANGE THIS if needed
                      tags = { Environment = "production" }
                    }
                    """,
                    "<ol><li>Change topic name if desired.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 19. AWS Lambda Function (Placeholder)
                repository.save(new DevOpsScript("AWS Lambda Function (Basic)", "Basic structure for deploying a Lambda function from a zip file.", "terraform",
                    """
                    # Assume lambda_function.zip exists containing your code (e.g., index.handler)
                    resource "aws_lambda_function" "test_lambda" {
                      filename      = "lambda_function.zip" # Needs to be created separately
                      function_name = "my_lambda_function"
                      role          = aws_iam_role.lambda_exec_role.arn # Assumes IAM role 'lambda_exec_role' exists
                      handler       = "index.handler" # Adjust for your code (e.g., main.lambda_handler for Python)
                      runtime       = "nodejs18.x" # Or python3.9, java11, etc.
                      source_code_hash = filebase64sha256("lambda_function.zip")

                      tags = { Function = "test-lambda" }
                    }
                    # Requires a ZIP file and an IAM execution role for Lambda
                    """,
                    "<ol><li>Create the `lambda_function.zip` deployment package.</li><li>Define an IAM role (`lambda_exec_role`) for Lambda execution.</li><li>Adjust handler and runtime.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 20. AWS ECR Repository
                repository.save(new DevOpsScript("AWS ECR Repository", "Creates an Elastic Container Registry (ECR) repository.", "terraform",
                    """
                    resource "aws_ecr_repository" "foo" {
                      name                 = "bar" # Repository name
                      image_tag_mutability = "MUTABLE"

                      image_scanning_configuration {
                        scan_on_push = true
                      }
                      tags = { Team = "infra" }
                    }
                    """,
                     "<ol><li>Change repository name (`bar`) if desired.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // --- GCP Terraform Scripts ---

                // 21. GCP Project (Requires Org permissions)
                repository.save(new DevOpsScript("GCP Project Creation", "Creates a new GCP project (requires Organization permissions).", "terraform",
                    """
                    # Note: Requires Organization level permissions to create projects.
                    resource "google_project" "my_project" {
                      project_id      = "your-unique-project-id" # CHANGE THIS
                      name            = "My Terraform Project"
                      org_id          = "YOUR_ORG_ID"            # CHANGE THIS
                      billing_account = "YOUR_BILLING_ACCOUNT_ID" # CHANGE THIS
                    }
                    """,
                    "<ol><li>Requires Organization Admin/Project Creator roles.</li><li>Replace placeholders with your Org ID, Billing Account ID, and a unique Project ID.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 22. GCP Service Account
                repository.save(new DevOpsScript("GCP Service Account", "Creates a GCP Service Account.", "terraform",
                    """
                    resource "google_service_account" "service_account" {
                      account_id   = "my-custom-sa" # CHANGE THIS if needed
                      display_name = "My Custom Service Account"
                      project      = "your-gcp-project-id" # CHANGE THIS
                    }
                    """,
                    "<ol><li>Replace `your-gcp-project-id` and optionally `account_id`.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 23. GCP Compute Engine Instance (Debian)
                repository.save(new DevOpsScript("GCP Compute Engine Instance (Debian)", "Basic GCE e2-micro instance (Debian).", "terraform",
                     """
                     provider "google" { project = "your-gcp-project-id"; region = "us-central1" } # CHANGE THESE
                     resource "google_compute_instance" "default" {
                       name         = "test-instance"
                       machine_type = "e2-micro"
                       zone         = "us-central1-c"
                       boot_disk { initialize_params { image = "debian-cloud/debian-11" } }
                       network_interface { network = "default"; access_config {} } # Assumes 'default' network
                       tags = ["web", "dev"]
                     }
                     """,
                    "<ol><li>Set your `project` and `region`/`zone`.</li><li>Ensure the 'default' network exists or specify yours.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 24. GCP Cloud Storage Bucket (Standard)
                repository.save(new DevOpsScript("GCP Storage Bucket", "Creates a standard regional Cloud Storage bucket.", "terraform",
                    """
                    resource "google_storage_bucket" "static-site" {
                      name          = "my-unique-gcs-bucket-name-12345" # CHANGE THIS
                      location      = "US-CENTRAL1"
                      storage_class = "STANDARD"
                      force_destroy = true # Allows deletion even if not empty (use carefully)
                      labels = { env = "dev" }
                    }
                    """,
                    "<ol><li>Change bucket name to be globally unique.</li><li>Set desired location.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 25. GCP Cloud Storage Bucket IAM Binding (Object Viewer)
                repository.save(new DevOpsScript("GCP Bucket IAM (Object Viewer)", "Grants a service account roles/storage.objectViewer on a bucket.", "terraform",
                    """
                    resource "google_storage_bucket_iam_member" "viewer_binding" {
                      bucket = google_storage_bucket.static-site.name # Assumes bucket 'static-site' exists
                      role   = "roles/storage.objectViewer"
                      member = "serviceAccount:${google_service_account.service_account.email}" # Assumes SA 'service_account' exists
                    }
                    # Requires google_storage_bucket.static-site and google_service_account.service_account
                    """,
                     "<ol><li>Ensure the bucket and service account resources exist.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 26. GCP VPC Network
                repository.save(new DevOpsScript("GCP VPC Network", "Creates a custom GCP VPC network.", "terraform",
                    """
                    resource "google_compute_network" "custom_vpc" {
                      name                    = "my-custom-network"
                      auto_create_subnetworks = false # Recommend false for custom control
                      routing_mode            = "REGIONAL"
                    }
                    """,
                    "<ol><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 27. GCP Subnetwork
                repository.save(new DevOpsScript("GCP Subnetwork", "Creates a subnetwork within a custom VPC.", "terraform",
                    """
                    resource "google_compute_subnetwork" "custom_subnet" {
                      name          = "my-custom-subnet"
                      ip_cidr_range = "10.2.0.0/16"
                      region        = "us-central1" # Change region if needed
                      network       = google_compute_network.custom_vpc.id # Assumes VPC 'custom_vpc' exists
                    }
                    # Requires google_compute_network.custom_vpc
                    """,
                    "<ol><li>Ensure the custom VPC resource exists.</li><li>Adjust CIDR range and region.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 28. GCP Firewall Rule (Allow Internal SSH/ICMP)
                repository.save(new DevOpsScript("GCP Firewall Rule (Internal)", "Allows SSH and ICMP within a VPC network.", "terraform",
                    """
                    resource "google_compute_firewall" "allow_internal" {
                      name    = "allow-internal-ssh-icmp"
                      network = google_compute_network.custom_vpc.name # Assumes VPC 'custom_vpc' exists

                      allow { protocol = "icmp" }
                      allow { protocol = "tcp"; ports = ["22"] }

                      source_ranges = ["10.2.0.0/16"] # Matches subnet CIDR
                    }
                    # Requires google_compute_network.custom_vpc
                    """,
                    "<ol><li>Ensure the custom VPC resource exists.</li><li>Match `source_ranges` to your subnet CIDR.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 29. GCP Cloud SQL Instance (PostgreSQL)
                repository.save(new DevOpsScript("GCP Cloud SQL PostgreSQL", "Basic Cloud SQL PostgreSQL instance.", "terraform",
                    """
                    resource "google_sql_database_instance" "main" {
                      name             = "my-postgres-instance" # CHANGE THIS if needed
                      database_version = "POSTGRES_13"
                      region           = "us-central1"

                      settings {
                        tier = "db-f1-micro" # Smallest tier
                      }
                      deletion_protection = false # Set to true for production
                    }
                    """,
                    "<ol><li>Change instance name if needed.</li><li>Choose appropriate tier and version.</li><li>Consider enabling deletion protection.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 30. GCP Cloud Function (HTTP Trigger - Placeholder)
                repository.save(new DevOpsScript("GCP Cloud Function (HTTP)", "Basic structure for an HTTP-triggered Cloud Function.", "terraform",
                    """
                    # Assume function source code is zipped in 'function_source.zip'
                    # and contains a function named 'helloHttp' (e.g., in index.js for Node.js)

                    resource "google_storage_bucket" "function_bucket" {
                      name     = "my-cloud-functions-source-bucket" # CHANGE THIS
                      location = "US"
                    }

                    resource "google_storage_bucket_object" "archive" {
                      name   = "function_source.zip"
                      bucket = google_storage_bucket.function_bucket.name
                      source = "function_source.zip" # Path to your zip file
                    }

                    resource "google_cloudfunctions_function" "function" {
                      name        = "my-http-function"
                      runtime     = "nodejs18" # Or python39, go116, etc.

                      available_memory_mb   = 128
                      source_archive_bucket = google_storage_bucket.function_bucket.name
                      source_archive_object = google_storage_bucket_object.archive.name
                      trigger_http          = true
                      entry_point           = "helloHttp" # Name of the exported function in your code
                      project               = "your-gcp-project-id" # CHANGE THIS
                      region                = "us-central1"
                    }
                    """,
                    "<ol><li>Create and zip your function code (e.g., `index.js`).</li><li>Change bucket names and project ID.</li><li>Update runtime and entry point.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // --- Add More Scripts (Continue Pattern) ---
                // Examples:
                // AWS: ELB, Auto Scaling Group, CloudFront, Route53 Record, Lambda Alias, Step Function
                // GCP: GKE Cluster, Pub/Sub Topic, Pub/Sub Subscription, Cloud DNS Record, Load Balancer
                // Azure: Resource Group, VNet, Subnet, VM, Storage Account, SQL Database

                // 31. AWS Application Load Balancer (ALB)
                repository.save(new DevOpsScript("AWS Application Load Balancer", "Creates a basic internet-facing ALB.", "terraform",
                    """
                    resource "aws_lb" "test_alb" {
                      name               = "test-alb-tf"
                      internal           = false
                      load_balancer_type = "application"
                      security_groups    = [aws_security_group.allow_web_ssh.id] # Assumes SG exists
                      subnets            = [aws_subnet.public_subnet.id]       # Assumes Subnet exists

                      enable_deletion_protection = false
                      tags = { Environment = "dev" }
                    }
                    # Requires security group and subnets
                    """,
                    "<ol><li>Ensure referenced security group and subnets exist.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                 // 32. AWS ALB Target Group
                repository.save(new DevOpsScript("AWS ALB Target Group", "Creates an ALB Target Group for instances.", "terraform",
                    """
                    resource "aws_lb_target_group" "test_tg" {
                      name     = "tf-example-alb-tg"
                      port     = 80
                      protocol = "HTTP"
                      vpc_id   = aws_vpc.main.id # Assumes VPC exists

                      health_check {
                        path                = "/"
                        protocol            = "HTTP"
                        matcher             = "200"
                        interval            = 15
                        timeout             = 3
                        healthy_threshold   = 2
                        unhealthy_threshold = 2
                      }
                      tags = { Environment = "dev" }
                    }
                    # Requires VPC
                    """,
                    "<ol><li>Ensure referenced VPC exists.</li><li>Adjust health check as needed.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 33. AWS ALB Listener (HTTP)
                repository.save(new DevOpsScript("AWS ALB Listener (HTTP)", "Creates an HTTP listener for an ALB.", "terraform",
                    """
                    resource "aws_lb_listener" "front_end" {
                      load_balancer_arn = aws_lb.test_alb.arn # Assumes ALB exists
                      port              = "80"
                      protocol          = "HTTP"

                      default_action {
                        type             = "forward"
                        target_group_arn = aws_lb_target_group.test_tg.arn # Assumes Target Group exists
                      }
                    }
                    # Requires ALB and Target Group
                    """,
                     "<ol><li>Ensure referenced ALB and Target Group exist.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                 // 34. AWS Auto Scaling Group (Basic)
                repository.save(new DevOpsScript("AWS Auto Scaling Group", "Basic ASG using a launch configuration (older method).", "terraform",
                    """
                    # Note: Launch Templates are preferred over Launch Configurations now.
                    resource "aws_launch_configuration" "example_lc" {
                      name          = "example-launch-config-tf"
                      image_id      = data.aws_ami.amzlinux2.id # Assumes AMI data source exists
                      instance_type = "t2.micro"
                      # Requires an AMI data source
                    }

                    resource "aws_autoscaling_group" "example_asg" {
                      name                 = "example-asg-tf"
                      launch_configuration = aws_launch_configuration.example_lc.name
                      vpc_zone_identifier  = [aws_subnet.public_subnet.id] # Assumes Subnet exists
                      min_size             = 1
                      max_size             = 3
                      desired_capacity     = 2

                      tag {
                        key                 = "Name"
                        value               = "tf-asg-instance"
                        propagate_at_launch = true
                      }
                      # Requires Launch Config and Subnet
                    }
                    """,
                     "<ol><li>Ensure AMI data source and Subnet exist.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 35. GCP Google Kubernetes Engine (GKE) Cluster
                repository.save(new DevOpsScript("GCP GKE Cluster (Basic)", "Creates a basic GKE cluster.", "terraform",
                    """
                    resource "google_container_cluster" "primary" {
                      name     = "my-gke-cluster"
                      location = "us-central1-a" # Change location if needed

                      # Use default node pool for simplicity here
                      initial_node_count = 1
                      node_config {
                        machine_type = "e2-small"
                        oauth_scopes = [
                          "https://www.googleapis.com/auth/cloud-platform"
                        ]
                      }
                      # Assumes default network/subnetwork
                    }
                    """,
                    "<ol><li>Set location.</li><li>Ensure necessary GCP APIs (Compute, Container) are enabled.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));


                // 36. GCP Pub/Sub Topic
                repository.save(new DevOpsScript("GCP Pub/Sub Topic", "Creates a GCP Pub/Sub topic.", "terraform",
                    """
                    resource "google_pubsub_topic" "example" {
                      name = "example-topic" # CHANGE THIS if needed
                      project = "your-gcp-project-id" # CHANGE THIS
                      labels = { foo = "bar" }
                    }
                    """,
                    "<ol><li>Set topic name and project ID.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));

                // 37. GCP Pub/Sub Subscription
                repository.save(new DevOpsScript("GCP Pub/Sub Subscription", "Creates a pull subscription to a Pub/Sub topic.", "terraform",
                    """
                    resource "google_pubsub_subscription" "example_sub" {
                      name  = "example-subscription" # CHANGE THIS if needed
                      topic = google_pubsub_topic.example.name # Assumes topic 'example' exists
                      project = "your-gcp-project-id" # CHANGE THIS

                      ack_deadline_seconds = 20
                      message_retention_duration = "604800s" # 7 days
                    }
                    # Requires google_pubsub_topic.example
                    """,
                    "<ol><li>Ensure the topic resource exists.</li><li>Set subscription name and project ID.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                    "1.0", 0, "N/A"));


                // --- Add many more diverse scripts here (up to 50+) ---
                // Consider adding:
                // AWS: CloudFront, Route53, API Gateway, EFS, ElastiCache, Secrets Manager, KMS Key
                // GCP: Cloud DNS, Load Balancer (HTTP/TCP), Cloud Armor, Filestore, Memorystore, Secret Manager
                // AZURE: Resource Group, VNet, Subnet, VM, Storage Account, SQL DB, Key Vault, AKS
                // Bash/Python: Simple scripts for tasks like backups, log rotation, health checks (less common for IaC repo)

                // Example: AWS CloudFront Distribution (Simplified for S3)
                repository.save(new DevOpsScript("AWS CloudFront for S3", "Simplified CloudFront distribution for an S3 bucket.", "terraform",
                """
                resource "aws_cloudfront_distribution" "s3_distribution" {
                  origin {
                    domain_name = aws_s3_bucket.public_bucket.bucket_regional_domain_name # Assumes public S3 bucket exists
                    origin_id   = "S3-${aws_s3_bucket.public_bucket.id}"
                    # Use OAI for private buckets
                  }

                  enabled             = true
                  is_ipv6_enabled     = true
                  comment             = "Some comment"
                  default_root_object = "index.html"

                  default_cache_behavior {
                    allowed_methods  = ["GET", "HEAD"]
                    cached_methods   = ["GET", "HEAD"]
                    target_origin_id = "S3-${aws_s3_bucket.public_bucket.id}"

                    forwarded_values {
                      query_string = false
                      cookies { forward = "none" }
                    }

                    viewer_protocol_policy = "redirect-to-https"
                    min_ttl                = 0
                    default_ttl            = 3600
                    max_ttl                = 86400
                  }

                  # Viewer certificate for HTTPS (using default CloudFront cert here)
                  viewer_certificate {
                    cloudfront_default_certificate = true
                  }

                  restrictions {
                    geo_restriction {
                      restriction_type = "none"
                    }
                  }
                  tags = { Environment = "production" }
                  # Requires aws_s3_bucket.public_bucket
                }
                """,
                "<ol><li>Ensure the S3 bucket resource exists.</li><li>For private buckets, configure Origin Access Identity (OAI).</li><li>Run `terraform init && terraform apply`.</li></ol>",
                "1.0", 0, "N/A"));


                // Example: GCP Cloud DNS Managed Zone
                repository.save(new DevOpsScript("GCP Cloud DNS Zone", "Creates a managed DNS zone in GCP.", "terraform",
                """
                resource "google_dns_managed_zone" "prod_zone" {
                  name        = "production-zone" # Internal name
                  dns_name    = "yourdomain.com."  # CHANGE THIS (note trailing dot)
                  description = "Managed by Terraform"
                  project     = "your-gcp-project-id" # CHANGE THIS
                }
                """,
                "<ol><li>Replace `yourdomain.com.` with your domain name (include trailing dot).</li><li>Set your project ID.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                "1.0", 0, "N/A"));

                // Example: GCP Cloud DNS Record Set (A Record)
                repository.save(new DevOpsScript("GCP Cloud DNS A Record", "Creates an A record in a GCP DNS zone.", "terraform",
                """
                resource "google_dns_record_set" "a_record" {
                  name         = "www.${google_dns_managed_zone.prod_zone.dns_name}" # Assumes zone 'prod_zone' exists
                  managed_zone = google_dns_managed_zone.prod_zone.name
                  type         = "A"
                  ttl          = 300
                  rrdatas      = ["8.8.8.8"] # CHANGE THIS to your server's IP address
                  project      = "your-gcp-project-id" # CHANGE THIS
                }
                # Requires google_dns_managed_zone.prod_zone
                """,
                "<ol><li>Ensure the DNS zone resource exists.</li><li>Replace `8.8.8.8` with the actual IP address.</li><li>Set your project ID.</li><li>Run `terraform init && terraform apply`.</li></ol>",
                "1.0", 0, "N/A"));


                // --- BASH SCRIPTS ---

                // Bash: System Health Check
                repository.save(new DevOpsScript("System Health Check", "Bash script to check system health (CPU, memory, disk).", "bash",
                    """
                    #!/bin/bash
                    # System Health Check Script

                    echo "====== SYSTEM HEALTH CHECK ======"
                    echo "Date: $(date)"
                    echo ""

                    # CPU Usage
                    echo "--- CPU Usage ---"
                    top -bn1 | grep "Cpu(s)" | awk '{print "CPU Load: " $2 + $4 "%"}'

                    # Memory Usage
                    echo ""
                    echo "--- Memory Usage ---"
                    free -h | awk '/^Mem:/ {print "Total: " $2 ", Used: " $3 ", Free: " $4}'

                    # Disk Usage
                    echo ""
                    echo "--- Disk Usage ---"
                    df -h | grep -E '^/dev/' | awk '{print $1 ": " $5 " used (" $3 " of " $2 ")"}'

                    # Load Average
                    echo ""
                    echo "--- Load Average ---"
                    uptime | awk -F'load average:' '{print "Load:" $2}'

                    # Top 5 processes by memory
                    echo ""
                    echo "--- Top 5 Memory Processes ---"
                    ps aux --sort=-%mem | head -6 | tail -5 | awk '{print $11 " - " $4 "% MEM"}'

                    echo ""
                    echo "====== END HEALTH CHECK ======"
                    """,
                    "<ol><li>Save as <code>health_check.sh</code></li><li>Run <code>chmod +x health_check.sh</code></li><li>Execute with <code>./health_check.sh</code></li></ol>",
                    "1.0", 0, "N/A"));

                // Bash: Log Rotation
                repository.save(new DevOpsScript("Log Rotation Script", "Bash script to rotate and compress old logs.", "bash",
                    """
                    #!/bin/bash
                    # Log Rotation Script

                    LOG_DIR="/var/log/myapp"
                    MAX_AGE_DAYS=7
                    ARCHIVE_DIR="/var/log/myapp/archive"

                    # Create archive directory if not exists
                    mkdir -p "$ARCHIVE_DIR"

                    echo "Starting log rotation at $(date)"

                    # Find and compress logs older than MAX_AGE_DAYS
                    find "$LOG_DIR" -maxdepth 1 -name "*.log" -mtime +$MAX_AGE_DAYS -exec gzip {} \\;

                    # Move compressed files to archive
                    find "$LOG_DIR" -maxdepth 1 -name "*.gz" -exec mv {} "$ARCHIVE_DIR/" \\;

                    # Delete archives older than 30 days
                    find "$ARCHIVE_DIR" -name "*.gz" -mtime +30 -delete

                    echo "Log rotation completed at $(date)"
                    echo "Archived files in $ARCHIVE_DIR:"
                    ls -lh "$ARCHIVE_DIR" | tail -10
                    """,
                    "<ol><li>Modify LOG_DIR path as needed</li><li>Save and make executable</li><li>Add to crontab: <code>0 2 * * * /path/to/log_rotation.sh</code></li></ol>",
                    "1.0", 0, "N/A"));

                // Bash: Docker Cleanup
                repository.save(new DevOpsScript("Docker Cleanup Script", "Bash script to clean up unused Docker resources.", "bash",
                    """
                    #!/bin/bash
                    # Docker Cleanup Script

                    echo "====== DOCKER CLEANUP ======"
                    echo "Date: $(date)"

                    # Show current disk usage
                    echo ""
                    echo "--- Before Cleanup ---"
                    docker system df

                    # Remove stopped containers
                    echo ""
                    echo "Removing stopped containers..."
                    docker container prune -f

                    # Remove unused images
                    echo ""
                    echo "Removing dangling images..."
                    docker image prune -f

                    # Remove unused volumes
                    echo ""
                    echo "Removing unused volumes..."
                    docker volume prune -f

                    # Remove unused networks
                    echo ""
                    echo "Removing unused networks..."
                    docker network prune -f

                    # Show disk usage after cleanup
                    echo ""
                    echo "--- After Cleanup ---"
                    docker system df

                    echo ""
                    echo "====== CLEANUP COMPLETE ======"
                    """,
                    "<ol><li>Save as <code>docker_cleanup.sh</code></li><li>Run <code>chmod +x docker_cleanup.sh</code></li><li>Execute with <code>./docker_cleanup.sh</code></li></ol>",
                    "1.0", 0, "N/A"));

                // Bash: Database Backup
                repository.save(new DevOpsScript("PostgreSQL Backup Script", "Bash script to backup PostgreSQL database.", "bash",
                    """
                    #!/bin/bash
                    # PostgreSQL Backup Script

                    # Configuration
                    DB_NAME="your_database"
                    DB_USER="your_user"
                    DB_HOST="localhost"
                    BACKUP_DIR="/backups/postgres"
                    DATE=$(date +%Y%m%d_%H%M%S)
                    BACKUP_FILE="$BACKUP_DIR/${DB_NAME}_${DATE}.sql.gz"
                    RETENTION_DAYS=7

                    # Create backup directory
                    mkdir -p "$BACKUP_DIR"

                    echo "Starting backup of $DB_NAME at $(date)"

                    # Create backup with compression
                    PGPASSWORD="$DB_PASSWORD" pg_dump -h "$DB_HOST" -U "$DB_USER" "$DB_NAME" | gzip > "$BACKUP_FILE"

                    if [ $? -eq 0 ]; then
                        echo "Backup successful: $BACKUP_FILE"
                        echo "Size: $(ls -lh $BACKUP_FILE | awk '{print $5}')"
                    else
                        echo "Backup FAILED!"
                        exit 1
                    fi

                    # Remove old backups
                    echo "Removing backups older than $RETENTION_DAYS days..."
                    find "$BACKUP_DIR" -name "*.sql.gz" -mtime +$RETENTION_DAYS -delete

                    echo "Backup completed at $(date)"
                    """,
                    "<ol><li>Set DB_NAME, DB_USER, and export DB_PASSWORD</li><li>Modify BACKUP_DIR as needed</li><li>Add to crontab for scheduled backups</li></ol>",
                    "1.0", 0, "N/A"));

                // Bash: Service Monitor
                repository.save(new DevOpsScript("Service Monitor & Restart", "Bash script to monitor services and restart if down.", "bash",
                    """
                    #!/bin/bash
                    # Service Monitor and Auto-Restart Script

                    SERVICES=("nginx" "docker" "postgresql")
                    SLACK_WEBHOOK="https://hooks.slack.com/services/YOUR/WEBHOOK/URL"

                    send_alert() {
                        local message="$1"
                        curl -s -X POST -H 'Content-type: application/json' \\
                            --data "{\\"text\\":\\"$message\\"}" \\
                            "$SLACK_WEBHOOK" > /dev/null 2>&1
                    }

                    for SERVICE in "${SERVICES[@]}"; do
                        if systemctl is-active --quiet "$SERVICE"; then
                            echo "[OK] $SERVICE is running"
                        else
                            echo "[ALERT] $SERVICE is DOWN! Attempting restart..."
                            systemctl restart "$SERVICE"
                            sleep 5

                            if systemctl is-active --quiet "$SERVICE"; then
                                echo "[RECOVERED] $SERVICE restarted successfully"
                                send_alert "⚠️ Service $SERVICE was down and has been restarted on $(hostname)"
                            else
                                echo "[CRITICAL] $SERVICE failed to restart!"
                                send_alert "🚨 CRITICAL: Service $SERVICE failed to restart on $(hostname)"
                            fi
                        fi
                    done
                    """,
                    "<ol><li>Modify SERVICES array with your services</li><li>Set SLACK_WEBHOOK for alerts (optional)</li><li>Run via cron every 5 minutes: <code>*/5 * * * * /path/to/monitor.sh</code></li></ol>",
                    "1.0", 0, "N/A"));

                // Bash: SSL Certificate Check
                repository.save(new DevOpsScript("SSL Certificate Expiry Check", "Bash script to check SSL certificate expiration dates.", "bash",
                    """
                    #!/bin/bash
                    # SSL Certificate Expiry Checker

                    DOMAINS=("example.com" "api.example.com" "www.example.com")
                    WARN_DAYS=30

                    echo "====== SSL CERTIFICATE CHECK ======"
                    echo "Date: $(date)"
                    echo ""

                    for DOMAIN in "${DOMAINS[@]}"; do
                        EXPIRY=$(echo | openssl s_client -servername "$DOMAIN" -connect "$DOMAIN:443" 2>/dev/null | \\
                                 openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)

                        if [ -z "$EXPIRY" ]; then
                            echo "[$DOMAIN] ERROR: Could not retrieve certificate"
                            continue
                        fi

                        EXPIRY_EPOCH=$(date -d "$EXPIRY" +%s)
                        NOW_EPOCH=$(date +%s)
                        DAYS_LEFT=$(( (EXPIRY_EPOCH - NOW_EPOCH) / 86400 ))

                        if [ $DAYS_LEFT -lt 0 ]; then
                            echo "[$DOMAIN] ❌ EXPIRED! (Expired $EXPIRY)"
                        elif [ $DAYS_LEFT -lt $WARN_DAYS ]; then
                            echo "[$DOMAIN] ⚠️  EXPIRES SOON: $DAYS_LEFT days left (Expires: $EXPIRY)"
                        else
                            echo "[$DOMAIN] ✅ OK: $DAYS_LEFT days left (Expires: $EXPIRY)"
                        fi
                    done

                    echo ""
                    echo "====== CHECK COMPLETE ======"
                    """,
                    "<ol><li>Modify DOMAINS array with your domains</li><li>Adjust WARN_DAYS threshold as needed</li><li>Run weekly via cron for monitoring</li></ol>",
                    "1.0", 0, "N/A"));

                // --- PYTHON SCRIPTS ---

                // Python: AWS EC2 Instance Report
                repository.save(new DevOpsScript("AWS EC2 Instance Report", "Python script to list all EC2 instances with details.", "python",
                    """
                    #!/usr/bin/env python3
                    \"\"\"AWS EC2 Instance Report Script\"\"\"

                    import boto3
                    from tabulate import tabulate
                    from datetime import datetime

                    def get_ec2_instances():
                        ec2 = boto3.client('ec2')
                        instances = []

                        response = ec2.describe_instances()
                        for reservation in response['Reservations']:
                            for instance in reservation['Instances']:
                                name = 'N/A'
                                for tag in instance.get('Tags', []):
                                    if tag['Key'] == 'Name':
                                        name = tag['Value']
                                        break

                                instances.append({
                                    'Name': name,
                                    'Instance ID': instance['InstanceId'],
                                    'Type': instance['InstanceType'],
                                    'State': instance['State']['Name'],
                                    'Private IP': instance.get('PrivateIpAddress', 'N/A'),
                                    'Public IP': instance.get('PublicIpAddress', 'N/A'),
                                    'Launch Time': instance['LaunchTime'].strftime('%Y-%m-%d %H:%M')
                                })

                        return instances

                    if __name__ == '__main__':
                        print(f"\\n=== EC2 Instance Report - {datetime.now().strftime('%Y-%m-%d %H:%M')} ===\\n")
                        instances = get_ec2_instances()

                        if instances:
                            print(tabulate(instances, headers='keys', tablefmt='grid'))
                            print(f"\\nTotal Instances: {len(instances)}")
                        else:
                            print("No EC2 instances found.")
                    """,
                    "<ol><li>Install dependencies: <code>pip install boto3 tabulate</code></li><li>Configure AWS credentials</li><li>Run: <code>python ec2_report.py</code></li></ol>",
                    "1.0", 0, "N/A"));

                // Python: CloudWatch Metrics Fetcher
                repository.save(new DevOpsScript("CloudWatch Metrics Fetcher", "Python script to fetch and display CloudWatch metrics.", "python",
                    """
                    #!/usr/bin/env python3
                    \"\"\"CloudWatch Metrics Fetcher\"\"\"

                    import boto3
                    from datetime import datetime, timedelta

                    def get_cpu_utilization(instance_id, hours=24):
                        cloudwatch = boto3.client('cloudwatch')

                        response = cloudwatch.get_metric_statistics(
                            Namespace='AWS/EC2',
                            MetricName='CPUUtilization',
                            Dimensions=[{'Name': 'InstanceId', 'Value': instance_id}],
                            StartTime=datetime.utcnow() - timedelta(hours=hours),
                            EndTime=datetime.utcnow(),
                            Period=3600,  # 1 hour intervals
                            Statistics=['Average', 'Maximum']
                        )

                        return sorted(response['Datapoints'], key=lambda x: x['Timestamp'])

                    def display_metrics(instance_id):
                        print(f"\\n=== CPU Utilization for {instance_id} (Last 24 hours) ===\\n")
                        metrics = get_cpu_utilization(instance_id)

                        if not metrics:
                            print("No metrics found.")
                            return

                        for point in metrics:
                            timestamp = point['Timestamp'].strftime('%Y-%m-%d %H:%M')
                            avg = point['Average']
                            max_val = point['Maximum']
                            bar = '█' * int(avg / 5) + '░' * (20 - int(avg / 5))
                            print(f"{timestamp} | {bar} | Avg: {avg:.1f}% Max: {max_val:.1f}%")

                    if __name__ == '__main__':
                        import sys
                        instance_id = sys.argv[1] if len(sys.argv) > 1 else 'i-xxxxxxxxx'
                        display_metrics(instance_id)
                    """,
                    "<ol><li>Install boto3: <code>pip install boto3</code></li><li>Configure AWS credentials</li><li>Run: <code>python cloudwatch_metrics.py i-instanceid</code></li></ol>",
                    "1.0", 0, "N/A"));

                // Python: Kubernetes Pod Monitor
                repository.save(new DevOpsScript("Kubernetes Pod Monitor", "Python script to monitor Kubernetes pods status.", "python",
                    """
                    #!/usr/bin/env python3
                    \"\"\"Kubernetes Pod Monitor\"\"\"

                    from kubernetes import client, config
                    from tabulate import tabulate
                    from datetime import datetime

                    def get_pod_status(namespace='default'):
                        config.load_kube_config()  # Use load_incluster_config() for in-cluster
                        v1 = client.CoreV1Api()

                        pods = v1.list_namespaced_pod(namespace)
                        pod_info = []

                        for pod in pods.items:
                            restarts = sum(cs.restart_count for cs in (pod.status.container_statuses or []))
                            ready = sum(1 for cs in (pod.status.container_statuses or []) if cs.ready)
                            total = len(pod.spec.containers)

                            status_icon = '✅' if pod.status.phase == 'Running' and ready == total else '⚠️'

                            pod_info.append({
                                'Status': status_icon,
                                'Name': pod.metadata.name[:40],
                                'Ready': f'{ready}/{total}',
                                'Phase': pod.status.phase,
                                'Restarts': restarts,
                                'Age': get_age(pod.metadata.creation_timestamp)
                            })

                        return pod_info

                    def get_age(timestamp):
                        if not timestamp:
                            return 'N/A'
                        delta = datetime.now(timestamp.tzinfo) - timestamp
                        if delta.days > 0:
                            return f'{delta.days}d'
                        hours = delta.seconds // 3600
                        if hours > 0:
                            return f'{hours}h'
                        return f'{delta.seconds // 60}m'

                    if __name__ == '__main__':
                        import sys
                        namespace = sys.argv[1] if len(sys.argv) > 1 else 'default'
                        print(f"\\n=== Pod Status in '{namespace}' namespace ===\\n")
                        pods = get_pod_status(namespace)
                        print(tabulate(pods, headers='keys', tablefmt='simple'))
                    """,
                    "<ol><li>Install: <code>pip install kubernetes tabulate</code></li><li>Ensure kubectl is configured</li><li>Run: <code>python pod_monitor.py [namespace]</code></li></ol>",
                    "1.0", 0, "N/A"));

                // Python: Log Analyzer
                repository.save(new DevOpsScript("Log File Analyzer", "Python script to analyze log files for errors and patterns.", "python",
                    """
                    #!/usr/bin/env python3
                    \"\"\"Log File Analyzer\"\"\"

                    import re
                    from collections import Counter
                    from datetime import datetime
                    import sys

                    def analyze_log(filepath, patterns=None):
                        if patterns is None:
                            patterns = {
                                'ERROR': r'\\b(ERROR|FATAL|CRITICAL)\\b',
                                'WARNING': r'\\bWARN(ING)?\\b',
                                '5xx': r'\\b5[0-9]{2}\\b',
                                '4xx': r'\\b4[0-9]{2}\\b'
                            }

                        results = {name: [] for name in patterns}
                        line_count = 0

                        with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
                            for line in f:
                                line_count += 1
                                for name, pattern in patterns.items():
                                    if re.search(pattern, line, re.IGNORECASE):
                                        results[name].append(line.strip()[:100])

                        return results, line_count

                    def print_report(filepath):
                        print(f"\\n{'='*60}")
                        print(f"LOG ANALYSIS REPORT")
                        print(f"File: {filepath}")
                        print(f"Time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
                        print(f"{'='*60}\\n")

                        results, total_lines = analyze_log(filepath)

                        print(f"Total Lines Analyzed: {total_lines:,}\\n")

                        for category, matches in results.items():
                            count = len(matches)
                            pct = (count / total_lines * 100) if total_lines > 0 else 0
                            print(f"[{category}] Found: {count} ({pct:.2f}%)")
                            if matches and count <= 5:
                                for m in matches[:3]:
                                    print(f"  └─ {m}")

                        print(f"\\n{'='*60}")

                    if __name__ == '__main__':
                        logfile = sys.argv[1] if len(sys.argv) > 1 else '/var/log/syslog'
                        print_report(logfile)
                    """,
                    "<ol><li>Save as <code>log_analyzer.py</code></li><li>Run: <code>python log_analyzer.py /path/to/logfile.log</code></li></ol>",
                    "1.0", 0, "N/A"));

                // Python: API Health Checker
                repository.save(new DevOpsScript("API Health Checker", "Python script to check multiple API endpoints health.", "python",
                    """
                    #!/usr/bin/env python3
                    \"\"\"API Health Checker\"\"\"

                    import requests
                    import time
                    from concurrent.futures import ThreadPoolExecutor
                    from datetime import datetime

                    ENDPOINTS = [
                        {'name': 'API Gateway', 'url': 'https://api.example.com/health'},
                        {'name': 'Auth Service', 'url': 'https://auth.example.com/health'},
                        {'name': 'User Service', 'url': 'https://users.example.com/health'},
                    ]

                    def check_endpoint(endpoint):
                        name, url = endpoint['name'], endpoint['url']
                        try:
                            start = time.time()
                            response = requests.get(url, timeout=10)
                            latency = (time.time() - start) * 1000

                            return {
                                'name': name,
                                'status': '✅' if response.status_code == 200 else '⚠️',
                                'code': response.status_code,
                                'latency': f'{latency:.0f}ms'
                            }
                        except requests.exceptions.Timeout:
                            return {'name': name, 'status': '❌', 'code': 'TIMEOUT', 'latency': '>10s'}
                        except Exception as e:
                            return {'name': name, 'status': '❌', 'code': 'ERROR', 'latency': str(e)[:20]}

                    def run_health_check():
                        print(f"\\n{'='*60}")
                        print(f"API HEALTH CHECK - {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
                        print(f"{'='*60}\\n")

                        with ThreadPoolExecutor(max_workers=10) as executor:
                            results = list(executor.map(check_endpoint, ENDPOINTS))

                        for r in results:
                            print(f"{r['status']} {r['name']:<20} | Code: {r['code']:<6} | Latency: {r['latency']}")

                        healthy = sum(1 for r in results if r['status'] == '✅')
                        print(f"\\n{'='*60}")
                        print(f"Summary: {healthy}/{len(results)} endpoints healthy")

                    if __name__ == '__main__':
                        run_health_check()
                    """,
                    "<ol><li>Install requests: <code>pip install requests</code></li><li>Modify ENDPOINTS list with your APIs</li><li>Run: <code>python api_health.py</code></li></ol>",
                    "1.0", 0, "N/A"));

                // --- S3 BUCKET OPTIMIZATION SCRIPTS ---

                // S3: Lifecycle Policy for Cost Optimization
                repository.save(new DevOpsScript("S3 Lifecycle Policy (Cost Optimization)", "Terraform script for S3 lifecycle rules to optimize storage costs.", "terraform",
                    """
                    resource "aws_s3_bucket" "optimized_bucket" {
                      bucket = "my-cost-optimized-bucket-12345" # CHANGE THIS
                    }

                    resource "aws_s3_bucket_lifecycle_configuration" "lifecycle" {
                      bucket = aws_s3_bucket.optimized_bucket.id

                      # Move infrequently accessed data to IA after 30 days
                      rule {
                        id     = "move-to-ia"
                        status = "Enabled"

                        transition {
                          days          = 30
                          storage_class = "STANDARD_IA"
                        }

                        # Move to Glacier after 90 days
                        transition {
                          days          = 90
                          storage_class = "GLACIER"
                        }

                        # Move to Deep Archive after 180 days
                        transition {
                          days          = 180
                          storage_class = "DEEP_ARCHIVE"
                        }
                      }

                      # Clean up incomplete multipart uploads
                      rule {
                        id     = "cleanup-incomplete-uploads"
                        status = "Enabled"

                        abort_incomplete_multipart_upload {
                          days_after_initiation = 7
                        }
                      }

                      # Delete old versions after 90 days (if versioning enabled)
                      rule {
                        id     = "cleanup-old-versions"
                        status = "Enabled"

                        noncurrent_version_expiration {
                          noncurrent_days = 90
                        }
                      }
                    }
                    """,
                    "<ol><li>Change bucket name to be unique</li><li>Adjust transition days based on access patterns</li><li>Run <code>terraform init && terraform apply</code></li></ol>",
                    "1.0", 0, "N/A"));

                // S3: Intelligent Tiering
                repository.save(new DevOpsScript("S3 Intelligent Tiering Configuration", "Terraform script to enable S3 Intelligent-Tiering.", "terraform",
                    """
                    resource "aws_s3_bucket" "intelligent_bucket" {
                      bucket = "my-intelligent-tiering-bucket-12345" # CHANGE THIS
                    }

                    resource "aws_s3_bucket_intelligent_tiering_configuration" "tiering" {
                      bucket = aws_s3_bucket.intelligent_bucket.id
                      name   = "EntireBucket"

                      # Enable Archive Access tier (objects not accessed for 90 days)
                      tiering {
                        access_tier = "ARCHIVE_ACCESS"
                        days        = 90
                      }

                      # Enable Deep Archive Access tier (objects not accessed for 180 days)
                      tiering {
                        access_tier = "DEEP_ARCHIVE_ACCESS"
                        days        = 180
                      }
                    }

                    # Set default storage class for new objects
                    resource "aws_s3_bucket_lifecycle_configuration" "default_intelligent" {
                      bucket = aws_s3_bucket.intelligent_bucket.id

                      rule {
                        id     = "default-intelligent-tiering"
                        status = "Enabled"

                        transition {
                          days          = 0
                          storage_class = "INTELLIGENT_TIERING"
                        }
                      }
                    }
                    """,
                    "<ol><li>Intelligent-Tiering automatically moves data between tiers</li><li>Best for unpredictable access patterns</li><li>Run <code>terraform init && terraform apply</code></li></ol>",
                    "1.0", 0, "N/A"));

                // Python: S3 Bucket Analyzer
                repository.save(new DevOpsScript("S3 Bucket Cost Analyzer", "Python script to analyze S3 bucket storage and costs.", "python",
                    """
                    #!/usr/bin/env python3
                    \"\"\"S3 Bucket Cost Analyzer\"\"\"

                    import boto3
                    from datetime import datetime

                    # Storage class pricing (US East, approximate $/GB/month)
                    PRICING = {
                        'STANDARD': 0.023,
                        'STANDARD_IA': 0.0125,
                        'ONEZONE_IA': 0.01,
                        'GLACIER': 0.004,
                        'DEEP_ARCHIVE': 0.00099,
                        'INTELLIGENT_TIERING': 0.023,
                    }

                    def analyze_bucket(bucket_name):
                        s3 = boto3.client('s3')
                        paginator = s3.get_paginator('list_objects_v2')

                        storage_classes = {}
                        total_size = 0
                        total_objects = 0

                        for page in paginator.paginate(Bucket=bucket_name):
                            for obj in page.get('Contents', []):
                                sc = obj.get('StorageClass', 'STANDARD')
                                size = obj['Size']

                                if sc not in storage_classes:
                                    storage_classes[sc] = {'count': 0, 'size': 0}

                                storage_classes[sc]['count'] += 1
                                storage_classes[sc]['size'] += size
                                total_size += size
                                total_objects += 1

                        return storage_classes, total_size, total_objects

                    def print_analysis(bucket_name):
                        print(f"\\n{'='*70}")
                        print(f"S3 BUCKET COST ANALYSIS: {bucket_name}")
                        print(f"{'='*70}\\n")

                        storage_classes, total_size, total_objects = analyze_bucket(bucket_name)

                        total_cost = 0
                        print(f"{'Storage Class':<20} {'Objects':>10} {'Size (GB)':>12} {'Monthly Cost':>14}")
                        print("-" * 60)

                        for sc, data in sorted(storage_classes.items()):
                            size_gb = data['size'] / (1024**3)
                            cost = size_gb * PRICING.get(sc, 0.023)
                            total_cost += cost
                            print(f"{sc:<20} {data['count']:>10,} {size_gb:>12.2f} ${cost:>13.2f}")

                        print("-" * 60)
                        print(f"{'TOTAL':<20} {total_objects:>10,} {total_size/(1024**3):>12.2f} ${total_cost:>13.2f}")

                        # Optimization suggestions
                        print(f"\\n{'='*70}")
                        print("OPTIMIZATION SUGGESTIONS:")
                        standard_size = storage_classes.get('STANDARD', {}).get('size', 0)
                        if standard_size > 100 * 1024**3:  # > 100GB in STANDARD
                            potential_savings = (standard_size / (1024**3)) * (0.023 - 0.0125)
                            print(f"• Move {standard_size/(1024**3):.0f}GB to IA = save ~${potential_savings:.2f}/month")

                    if __name__ == '__main__':
                        import sys
                        bucket = sys.argv[1] if len(sys.argv) > 1 else 'my-bucket'
                        print_analysis(bucket)
                    """,
                    "<ol><li>Install boto3: <code>pip install boto3</code></li><li>Configure AWS credentials</li><li>Run: <code>python s3_analyzer.py bucket-name</code></li></ol>",
                    "1.0", 0, "N/A"));

                // Python: S3 Cleanup Script
                repository.save(new DevOpsScript("S3 Old Objects Cleanup", "Python script to identify and delete old S3 objects.", "python",
                    """
                    #!/usr/bin/env python3
                    \"\"\"S3 Old Objects Cleanup Script\"\"\"

                    import boto3
                    from datetime import datetime, timezone, timedelta
                    import sys

                    def find_old_objects(bucket_name, days_old=90, prefix='', dry_run=True):
                        s3 = boto3.client('s3')
                        paginator = s3.get_paginator('list_objects_v2')

                        cutoff_date = datetime.now(timezone.utc) - timedelta(days=days_old)
                        old_objects = []
                        total_size = 0

                        print(f"Scanning bucket: {bucket_name}")
                        print(f"Finding objects older than {days_old} days (before {cutoff_date.date()})\\n")

                        for page in paginator.paginate(Bucket=bucket_name, Prefix=prefix):
                            for obj in page.get('Contents', []):
                                if obj['LastModified'] < cutoff_date:
                                    old_objects.append({
                                        'Key': obj['Key'],
                                        'Size': obj['Size'],
                                        'LastModified': obj['LastModified']
                                    })
                                    total_size += obj['Size']

                        print(f"Found {len(old_objects)} objects ({total_size/(1024**3):.2f} GB)")

                        if old_objects and not dry_run:
                            confirm = input(f"\\nDelete {len(old_objects)} objects? (yes/no): ")
                            if confirm.lower() == 'yes':
                                for i, obj in enumerate(old_objects):
                                    s3.delete_object(Bucket=bucket_name, Key=obj['Key'])
                                    if (i + 1) % 100 == 0:
                                        print(f"Deleted {i + 1}/{len(old_objects)} objects...")
                                print(f"\\nDeleted {len(old_objects)} objects successfully!")
                            else:
                                print("Deletion cancelled.")
                        elif dry_run:
                            print("\\n[DRY RUN] No objects deleted. Run with --execute to delete.")
                            print("\\nSample objects that would be deleted:")
                            for obj in old_objects[:10]:
                                print(f"  - {obj['Key']} ({obj['Size']/1024:.1f} KB)")

                        return old_objects

                    if __name__ == '__main__':
                        if len(sys.argv) < 2:
                            print("Usage: python s3_cleanup.py <bucket-name> [days] [--execute]")
                            sys.exit(1)

                        bucket = sys.argv[1]
                        days = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else 90
                        dry_run = '--execute' not in sys.argv

                        find_old_objects(bucket, days, dry_run=dry_run)
                    """,
                    "<ol><li>Install boto3: <code>pip install boto3</code></li><li>Dry run: <code>python s3_cleanup.py bucket-name 90</code></li><li>Execute: <code>python s3_cleanup.py bucket-name 90 --execute</code></li></ol>",
                    "1.0", 0, "N/A"));

                // S3: Bucket Policy for Cost Control
                repository.save(new DevOpsScript("S3 Bucket Policy (Enforce IA Storage)", "Terraform to enforce cost-effective storage class on uploads.", "terraform",
                    """
                    resource "aws_s3_bucket" "enforced_storage" {
                      bucket = "my-enforced-storage-bucket-12345" # CHANGE THIS
                    }

                    # Deny uploads that don't specify STANDARD_IA or cheaper storage classes
                    resource "aws_s3_bucket_policy" "enforce_storage_class" {
                      bucket = aws_s3_bucket.enforced_storage.id

                      policy = jsonencode({
                        Version = "2012-10-17"
                        Statement = [
                          {
                            Sid       = "DenyExpensiveStorageClass"
                            Effect    = "Deny"
                            Principal = "*"
                            Action    = "s3:PutObject"
                            Resource  = "${aws_s3_bucket.enforced_storage.arn}/*"
                            Condition = {
                              StringNotEqualsIfExists = {
                                "s3:x-amz-storage-class" = [
                                  "STANDARD_IA",
                                  "ONEZONE_IA",
                                  "INTELLIGENT_TIERING",
                                  "GLACIER",
                                  "DEEP_ARCHIVE"
                                ]
                              }
                            }
                          }
                        ]
                      })
                    }

                    # Note: This policy requires uploads to specify a storage class
                    # Remove this if you want to allow STANDARD uploads
                    """,
                    "<ol><li>This policy blocks uploads that don't specify a cost-effective storage class</li><li>Modify allowed storage classes as needed</li><li>Run <code>terraform init && terraform apply</code></li></ol>",
                    "1.0", 0, "N/A"));

                // S3: Replication for Disaster Recovery
                repository.save(new DevOpsScript("S3 Cross-Region Replication", "Terraform for S3 cross-region replication (DR).", "terraform",
                    """
                    # Source bucket (primary region)
                    resource "aws_s3_bucket" "source" {
                      bucket = "my-source-bucket-primary-12345" # CHANGE THIS
                    }

                    resource "aws_s3_bucket_versioning" "source" {
                      bucket = aws_s3_bucket.source.id
                      versioning_configuration {
                        status = "Enabled"  # Required for replication
                      }
                    }

                    # Destination bucket (DR region)
                    resource "aws_s3_bucket" "destination" {
                      provider = aws.dr_region  # Requires provider alias for different region
                      bucket   = "my-dest-bucket-dr-12345" # CHANGE THIS
                    }

                    resource "aws_s3_bucket_versioning" "destination" {
                      provider = aws.dr_region
                      bucket   = aws_s3_bucket.destination.id
                      versioning_configuration {
                        status = "Enabled"
                      }
                    }

                    # IAM role for replication
                    resource "aws_iam_role" "replication" {
                      name = "s3-bucket-replication-role"
                      assume_role_policy = jsonencode({
                        Version = "2012-10-17"
                        Statement = [{
                          Action = "sts:AssumeRole"
                          Effect = "Allow"
                          Principal = { Service = "s3.amazonaws.com" }
                        }]
                      })
                    }

                    # Replication configuration
                    resource "aws_s3_bucket_replication_configuration" "replication" {
                      depends_on = [aws_s3_bucket_versioning.source]
                      bucket     = aws_s3_bucket.source.id
                      role       = aws_iam_role.replication.arn

                      rule {
                        id     = "replicate-all"
                        status = "Enabled"

                        destination {
                          bucket        = aws_s3_bucket.destination.arn
                          storage_class = "STANDARD_IA"  # Cost optimization for DR
                        }
                      }
                    }
                    """,
                    "<ol><li>Configure provider alias for DR region</li><li>Change bucket names to be unique</li><li>Attach replication policy to IAM role</li><li>Run <code>terraform init && terraform apply</code></li></ol>",
                    "1.0", 0, "N/A"));

                logger.info("Finished loading sample DevOps scripts into database.");
            } else {
                logger.info("Database already contains DevOps scripts. Skipping initialization.");
            }
        };
    

    }
}