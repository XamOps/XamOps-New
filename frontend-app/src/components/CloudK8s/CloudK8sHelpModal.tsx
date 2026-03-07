/**
 * CloudK8s Help Modal Component
 *
 * Complete observability setup guide for EKS clusters
 */

import React, { useState } from 'react';
import { DialogRoot, DialogPanel } from '../ui';

interface CloudK8sHelpModalProps {
  isOpen: boolean;
  onClose: () => void;
}

interface CopyButtonProps {
  codeRef: React.RefObject<HTMLElement | null>;
}

const CopyButton: React.FC<CopyButtonProps> = ({ codeRef }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    if (codeRef.current) {
      navigator.clipboard.writeText(codeRef.current.innerText);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <button
      onClick={handleCopy}
      className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
    >
      {copied ? (
        <span className="text-green-400">
          <i className="fas fa-check"></i> Copied!
        </span>
      ) : (
        <span>
          <i className="far fa-copy"></i> Copy
        </span>
      )}
    </button>
  );
};

interface CodeBlockProps {
  children: string;
}

const CodeBlock: React.FC<CodeBlockProps> = ({ children }) => {
  const codeRef = React.useRef<HTMLElement>(null);

  return (
    <div className="relative group my-4">
      <div className="absolute top-2 right-2 z-10">
        <CopyButton codeRef={codeRef} />
      </div>
      <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
        <code ref={codeRef} className="language-bash">{children}</code>
      </pre>
    </div>
  );
};

const CloudK8sHelpModal: React.FC<CloudK8sHelpModalProps> = ({ isOpen, onClose }) => {
  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-4xl">
      <DialogPanel
        title={
          <div className="flex items-center space-x-4">
            <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center shadow-lg">
              <i className="fas fa-rocket text-white text-xl"></i>
            </div>
            <div>
              <div className="font-bold text-xl text-gray-800">COMPLETE OBSERVABILITY SETUP GUIDE</div>
              <div className="text-sm text-gray-600">
                Install Falco + OTel Collector on EKS → Forward Metrics → EC2 → Prometheus
              </div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={<button onClick={onClose} className="px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors">Done</button>}
      >
        <div className="overflow-y-auto max-h-[60vh] pr-2">
          {/* Overview */}
          <div className="bg-blue-50 border-l-4 border-blue-500 p-4 mb-6 rounded-r-lg">
            <p className="text-sm text-blue-700">
              <strong>Overview:</strong> This guide deploys a complete observability stack including
              Falco (Security), Node Exporter, Kube State Metrics, and OpenTelemetry Collector.
              Metrics are forwarded to a dedicated EC2 monitoring instance running Prometheus.
            </p>
          </div>

          <hr className="my-6 border-gray-200" />

          {/* Phase 1 */}
          <h2 className="text-lg font-extrabold text-gray-900 mb-4 uppercase tracking-wide flex items-center">
            <span className="w-8 h-8 bg-blue-500 text-white rounded-full flex items-center justify-center text-sm mr-3">1</span>
            EC2 Monitoring Instance Setup
          </h2>

          <div className="mb-8">
            <span className="inline-block px-3 py-1 bg-blue-100 text-blue-700 rounded-full text-xs font-semibold mb-3 border border-blue-200">
              <i className="fab fa-aws mr-1"></i> AWS CloudShell
            </span>
            <p className="text-gray-600 mb-4">
              Run these steps in AWS CloudShell to prepare the environment and launch the monitoring instance.
            </p>

            <h3 className="text-base font-bold text-gray-800 mb-3 flex items-center">
              <i className="fas fa-cog text-blue-500 mr-2"></i>
              Step 1: Configure Environment & Variables
            </h3>
            <CodeBlock>{`# Create setup directory
mkdir -p ~/cluster-monitoring-setup
cd ~/cluster-monitoring-setup

# 1. Create Config File
cat > setup-config.env << 'EOF'
# === CLUSTER CONFIGURATION ===
export CLUSTER_NAME="YOUR_CLUSTER_NAME"   # REPLACE THIS
export AWS_REGION="us-east-1"             # REPLACE THIS
export AWS_ACCOUNT_ID="YOUR_ACCOUNT_ID"   # REPLACE THIS

# === EC2 INSTANCE CONFIGURATION ===
export EC2_INSTANCE_TYPE="t3.small"       # Recommended: t3.small (2GB RAM)
export EC2_KEY_NAME="monitoring-key"      # SSH key name to create
export EC2_INSTANCE_NAME="cluster-monitoring"

# === AMI CONFIGURATION (Region Specific - AL2023) ===
# us-east-1: ami-0c02fb55c47d5d30e
# us-west-2: ami-08d8ac128e0a1b91c
export EC2_AMI_ID="ami-0c02fb55c47d5d30e" # CHANGE based on your region
EOF

# Load the configuration
source setup-config.env`}</CodeBlock>

            <h3 className="text-base font-bold text-gray-800 mb-3 mt-6 flex items-center">
              <i className="fas fa-server text-blue-500 mr-2"></i>
              Step 2: Detect VPC & Create Resources
            </h3>
            <p className="text-gray-600 mb-4">
              This script auto-detects your EKS VPC settings, creates an SSH key, and launches the monitoring EC2 instance.
            </p>
            <CodeBlock>{`# 1. Update Kubeconfig
aws eks update-kubeconfig --region \${AWS_REGION} --name \${CLUSTER_NAME}

# 2. Get VPC Details
VPC_INFO=$(aws eks describe-cluster --name \${CLUSTER_NAME} --region \${AWS_REGION} --query 'cluster.resourcesVpcConfig' --output json)
VPC_ID=$(echo $VPC_INFO | jq -r '.vpcId')
CLUSTER_SG=$(echo $VPC_INFO | jq -r '.clusterSecurityGroupId')
# Find a public subnet
SUBNET_ID=$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC_ID" --region \${AWS_REGION} --query 'Subnets[?MapPublicIpOnLaunch==\`true\`].SubnetId | [0]' --output text)

echo "VPC: $VPC_ID | Subnet: $SUBNET_ID"

# 3. Create SSH Key
aws ec2 create-key-pair --key-name \${EC2_KEY_NAME} --region \${AWS_REGION} --query 'KeyMaterial' --output text > ~/\${EC2_KEY_NAME}.pem
chmod 400 ~/\${EC2_KEY_NAME}.pem

# 4. Create Security Group for Monitoring
SG_ID=$(aws ec2 create-security-group --group-name \${EC2_INSTANCE_NAME}-sg --description "Prometheus OTel" --vpc-id \${VPC_ID} --region \${AWS_REGION} --output text --query 'GroupId')

# Open Ports: 22(SSH), 9090(Prometheus), 4317/4318(OTel), 8889(Gateway)
for port in 22 9090 4318 4317 8889; do
  aws ec2 authorize-security-group-ingress --group-id $SG_ID --protocol tcp --port $port --cidr 0.0.0.0/0 --region \${AWS_REGION}
done

# 5. Launch EC2 Instance with Docker
cat > user-data.sh << 'USERDATA'
#!/bin/bash
yum update -y
yum install docker -y
systemctl enable docker
systemctl start docker
usermod -aG docker ec2-user
curl -L "https://github.com/docker/compose/releases/download/v2.24.5/docker-compose-linux-x86_64" -o /usr/local/bin/docker-compose
chmod +x /usr/local/bin/docker-compose
mkdir -p /home/ec2-user/monitoring
chown ec2-user:ec2-user /home/ec2-user/monitoring
USERDATA

INSTANCE_ID=$(aws ec2 run-instances \\
  --image-id \${EC2_AMI_ID} \\
  --instance-type \${EC2_INSTANCE_TYPE} \\
  --key-name \${EC2_KEY_NAME} \\
  --security-group-ids $SG_ID \\
  --subnet-id \${SUBNET_ID} \\
  --associate-public-ip-address \\
  --user-data file://user-data.sh \\
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=\${EC2_INSTANCE_NAME}}]" \\
  --region \${AWS_REGION} \\
  --query 'Instances[0].InstanceId' --output text)

echo "Waiting for instance..."
aws ec2 wait instance-running --instance-ids $INSTANCE_ID --region \${AWS_REGION}
PUBLIC_IP=$(aws ec2 describe-instances --instance-ids $INSTANCE_ID --region \${AWS_REGION} --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)

echo "============================================="
echo "EC2 Setup Complete. IP: $PUBLIC_IP"
echo "============================================="`}</CodeBlock>
          </div>

          <hr className="my-6 border-gray-200" />

          {/* Phase 2 */}
          <h2 className="text-lg font-extrabold text-gray-900 mb-4 uppercase tracking-wide flex items-center">
            <span className="w-8 h-8 bg-green-500 text-white rounded-full flex items-center justify-center text-sm mr-3">2</span>
            Deploy Monitoring Stack on EC2
          </h2>

          <div className="mb-8">
            <span className="inline-block px-3 py-1 bg-yellow-100 text-yellow-700 rounded-full text-xs font-semibold mb-3 border border-yellow-200">
              <i className="fas fa-server mr-1"></i> EC2 Instance
            </span>
            <p className="text-gray-600 mb-4">
              SSH into the new instance and deploy the OTel Gateway and Prometheus.
            </p>

            <h3 className="text-base font-bold text-gray-800 mb-3 flex items-center">
              <i className="fas fa-terminal text-green-500 mr-2"></i>
              Step 1: Connect and Configure
            </h3>
            <CodeBlock>{`# SSH into the instance (From CloudShell)
ssh -i ~/monitoring-key.pem ec2-user@<PUBLIC_IP>

# Inside EC2:
cd ~/monitoring

# 1. Create docker-compose.yml
cat > docker-compose.yml << 'EOF'
version: '3.8'
services:
  otel-gateway:
    image: otel/opentelemetry-collector-contrib:latest
    container_name: otel-gateway
    volumes:
      - ./otel-gateway-config.yaml:/etc/otelcol/config.yaml
    ports:
      - "4318:4318" # HTTP Receiver
      - "4317:4317" # gRPC Receiver
      - "8889:8889" # Prometheus Exporter
    command: ["--config=/etc/otelcol/config.yaml"]
    restart: unless-stopped

  prometheus:
    image: prom/prometheus:latest
    container_name: prometheus
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    ports:
      - "9090:9090"
    command: --config.file=/etc/prometheus/prometheus.yml
    restart: unless-stopped

volumes:
  prometheus-data:
EOF

# 2. Create OTel Gateway Config
cat > otel-gateway-config.yaml << 'EOF'
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: "0.0.0.0:4317"
      http:
        endpoint: "0.0.0.0:4318"

exporters:
  prometheus:
    endpoint: "0.0.0.0:8889"
    namespace: cluster_metrics

processors:
  batch:

service:
  pipelines:
    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [prometheus]
EOF

# 3. Create Prometheus Config
cat > prometheus.yml << 'EOF'
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'otel-gateway'
    static_configs:
      - targets: ['otel-gateway:8889']
EOF

# 4. Start Services
docker-compose up -d`}</CodeBlock>
          </div>

          <hr className="my-6 border-gray-200" />

          {/* Phase 3 */}
          <h2 className="text-lg font-extrabold text-gray-900 mb-4 uppercase tracking-wide flex items-center">
            <span className="w-8 h-8 bg-purple-500 text-white rounded-full flex items-center justify-center text-sm mr-3">3</span>
            Install Cluster Agents
          </h2>

          <div className="mb-8">
            <span className="inline-block px-3 py-1 bg-blue-100 text-blue-700 rounded-full text-xs font-semibold mb-3 border border-blue-200">
              <i className="fab fa-aws mr-1"></i> AWS CloudShell
            </span>
            <p className="text-gray-600 mb-4">
              Back in CloudShell, install the components that will run on your EKS cluster.
            </p>

            <h3 className="text-base font-bold text-gray-800 mb-3 flex items-center">
              <i className="fas fa-download text-purple-500 mr-2"></i>
              Step 1: Install Exporters & Falco
            </h3>
            <CodeBlock>{`# Install Helm if needed
curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
chmod 700 get_helm.sh
./get_helm.sh

# 1. Install Node Exporter
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install node-exporter prometheus-community/prometheus-node-exporter --namespace default --create-namespace --wait

# 2. Install Kube State Metrics
helm upgrade --install kube-state-metrics prometheus-community/kube-state-metrics --namespace kube-system --wait

# 3. Install Falco (Security)
helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update
helm upgrade --install falco falcosecurity/falco \\
  --namespace falco \\
  --create-namespace \\
  --set falcosidekick.enabled=true \\
  --set falcosidekick.config.prometheus.enabled=true \\
  --wait`}</CodeBlock>

            <h3 className="text-base font-bold text-gray-800 mb-3 mt-6 flex items-center">
              <i className="fas fa-satellite-dish text-purple-500 mr-2"></i>
              Step 2: Deploy OTel Collector Agent
            </h3>
            <p className="text-gray-600 mb-4">
              This agent runs on every node, scrapes metrics, and forwards them to your EC2 instance.
            </p>
            <CodeBlock>{`# Add OTel Repo
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
helm repo update

# Configure Collector to point to your EC2 IP
# NOTE: Replace <PUBLIC_IP> with the IP from Phase 1
EC2_PUBLIC_IP="<REPLACE_WITH_EC2_PUBLIC_IP>"

cat > otel-collector-values.yaml << VALUESEOF
mode: daemonset
image:
  repository: otel/opentelemetry-collector-contrib
  tag: "0.88.0"
config:
  receivers:
    prometheus:
      config:
        scrape_configs:
          - job_name: 'falco-sidekick'
            scrape_interval: 15s
            static_configs:
              - targets: ['falco-falcosidekick.falco.svc.cluster.local:2801']
          - job_name: 'kube-state-metrics'
            scrape_interval: 15s
            static_configs:
              - targets: ['kube-state-metrics.kube-system.svc.cluster.local:8080']
          - job_name: 'node-exporter'
            scrape_interval: 15s
            static_configs:
              - targets: ['node-exporter-prometheus-node-exporter.default.svc.cluster.local:9100']
  processors:
    batch: {}
    attributes:
      actions:
        - key: cluster_name
          action: insert
          value: "\${CLUSTER_NAME}"
  exporters:
    otlphttp:
      endpoint: "http://\${EC2_PUBLIC_IP}:4318"
      tls:
        insecure: true
  service:
    pipelines:
      metrics:
        receivers: [prometheus]
        processors: [batch, attributes]
        exporters: [otlphttp]
VALUESEOF

# Deploy Collector
helm upgrade --install otel-agent open-telemetry/opentelemetry-collector --namespace default -f otel-collector-values.yaml --wait`}</CodeBlock>
          </div>

          <hr className="my-6 border-gray-200" />

          {/* Validation */}
          <h2 className="text-lg font-extrabold text-gray-900 mb-4 uppercase tracking-wide flex items-center">
            <span className="w-8 h-8 bg-emerald-500 text-white rounded-full flex items-center justify-center text-sm mr-3">
              <i className="fas fa-check text-sm"></i>
            </span>
            Validation
          </h2>
          <p className="text-gray-600 mb-4">
            Once setup is complete, access your Prometheus dashboard:
          </p>
          <div className="bg-gradient-to-r from-green-50 to-emerald-50 border border-green-200 rounded-xl p-6 text-center mb-6">
            <p className="font-bold text-green-800 text-lg font-mono">http://&lt;EC2_PUBLIC_IP&gt;:9090</p>
          </div>

          <h4 className="text-base font-bold text-gray-800 mb-3">Essential Queries</h4>
          <ul className="space-y-2 text-gray-700 text-sm">
            <li className="flex items-start">
              <i className="fas fa-shield-alt text-red-500 mr-2 mt-1"></i>
              <div>
                <strong>Falco Events:</strong>
                <code className="ml-2 bg-gray-100 px-2 py-1 rounded text-xs">cluster_metrics_falcosecurity_falcosidekick_falco_events_total</code>
              </div>
            </li>
            <li className="flex items-start">
              <i className="fas fa-microchip text-blue-500 mr-2 mt-1"></i>
              <div>
                <strong>Node CPU:</strong>
                <code className="ml-2 bg-gray-100 px-2 py-1 rounded text-xs">cluster_metrics_node_cpu_seconds_total</code>
              </div>
            </li>
            <li className="flex items-start">
              <i className="fas fa-cubes text-purple-500 mr-2 mt-1"></i>
              <div>
                <strong>Pod Info:</strong>
                <code className="ml-2 bg-gray-100 px-2 py-1 rounded text-xs">cluster_metrics_kube_pod_info</code>
              </div>
            </li>
          </ul>
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default CloudK8sHelpModal;
