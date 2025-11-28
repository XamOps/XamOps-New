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
import org.springframework.context.annotation.Bean;

import java.util.Optional;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(ClientRepository clientRepository, UserRepository userRepository, PasswordEncoder passwordEncoder) {
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
            superAdmin.setPassword(passwordEncoder.encode("SuperAdmin@123")); // Default Password
            superAdmin.setRole("ROLE_SUPER_ADMIN");
            superAdmin.setClient(client);
            superAdmin.setEmail("superadmin@xamops.com");
            userRepository.save(superAdmin);
            logger.info(">>> SUPERADMIN CREATED | User: superadmin | Pass: SuperAdmin@123 <<<");
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


                // ... Continue adding repository.save(...) calls for more scripts ...
                // Aim to reach ~50 entries covering various common services.

                logger.info("Finished loading sample DevOps scripts into database.");
            } else {
                logger.info("Database already contains DevOps scripts. Skipping initialization.");
            }
        };
    

    }
}