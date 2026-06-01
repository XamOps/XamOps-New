import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import { DialogRoot, DialogPanel } from '../ui';

interface CloudK8sHelpModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const CopyButton: React.FC<{ codeRef: React.RefObject<HTMLElement | null> }> = ({ codeRef }) => {
  const [copied, setCopied] = useState(false);
  const handleCopy = () => {
    if (codeRef.current) {
      navigator.clipboard.writeText(codeRef.current.innerText);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };
  return (
    <button onClick={handleCopy}
      className="flex items-center gap-1.5 px-2.5 py-1 bg-[rgba(255,255,255,0.1)] hover:bg-[rgba(255,255,255,0.18)] text-white text-[11px] font-semibold rounded-lg border border-[rgba(255,255,255,0.12)] transition-colors">
      <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={12} className={copied ? 'text-emerald-400' : ''} />
      {copied ? 'Copied!' : 'Copy'}
    </button>
  );
};

const CodeBlock: React.FC<{ children: string }> = ({ children }) => {
  const codeRef = React.useRef<HTMLElement>(null);
  return (
    <div className="relative my-4">
      <div className="absolute top-2.5 right-2.5 z-10">
        <CopyButton codeRef={codeRef} />
      </div>
      <pre className="bg-[#1a1d27] text-[#e2e8f0] p-4 pt-10 rounded-xl overflow-x-auto text-[12px] font-mono leading-relaxed border border-[rgba(255,255,255,0.06)]">
        <code ref={codeRef}>{children}</code>
      </pre>
    </div>
  );
};

const PhaseHeader: React.FC<{ num: number; title: string; color: string }> = ({ num, title, color }) => (
  <h2 className="text-[14px] font-extrabold text-[rgba(0,0,0,0.88)] mb-4 uppercase tracking-wide flex items-center gap-3">
    <span className={`w-7 h-7 ${color} text-white rounded-lg flex items-center justify-center text-[12px] font-bold flex-shrink-0`}>{num}</span>
    {title}
  </h2>
);

const CloudK8sHelpModal: React.FC<CloudK8sHelpModalProps> = ({ isOpen, onClose }) => {
  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-4xl">
      <DialogPanel
        title={
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-indigo-600 rounded-xl flex items-center justify-center">
              <Icon icon="mdi:rocket-launch-outline" width={20} color="white" />
            </div>
            <div>
              <div className="font-bold text-[15px] text-[rgba(0,0,0,0.88)]">Complete Observability Setup Guide</div>
              <div className="text-[12px] text-[rgba(0,0,0,0.45)]">
                Install Falco + OTel Collector on EKS → Forward Metrics → EC2 → Prometheus
              </div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={
          <button onClick={onClose}
            className="px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-xl transition-colors">
            Done
          </button>
        }
      >
        <div className="overflow-y-auto max-h-[60vh] pr-2 space-y-6">
          {/* Overview */}
          <div className="p-4 bg-blue-50 border border-blue-100 rounded-xl flex items-start gap-3">
            <Icon icon="mdi:information-outline" width={15} color="#3B82F6" className="mt-0.5 flex-shrink-0" />
            <p className="text-[13px] text-blue-800">
              <strong>Overview:</strong> This guide deploys a complete observability stack including
              Falco (Security), Node Exporter, Kube State Metrics, and OpenTelemetry Collector.
              Metrics are forwarded to a dedicated EC2 monitoring instance running Prometheus.
            </p>
          </div>

          <hr className="border-[rgba(0,0,0,0.06)]" />

          {/* Phase 1 */}
          <PhaseHeader num={1} title="EC2 Monitoring Instance Setup" color="bg-blue-500" />
          <div>
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-orange-50 text-orange-700 border border-orange-100 rounded-full text-[11px] font-semibold mb-3">
              <Icon icon="mdi:aws" width={12} color="#FF9900" />
              AWS CloudShell
            </span>
            <p className="text-[13px] text-[rgba(0,0,0,0.55)] mb-4">
              Run these steps in AWS CloudShell to prepare the environment and launch the monitoring instance.
            </p>

            <h3 className="text-[13px] font-bold text-[rgba(0,0,0,0.75)] mb-3 flex items-center gap-2">
              <Icon icon="mdi:cog-outline" width={14} color="#6366F1" />
              Step 1: Configure Environment &amp; Variables
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

            <h3 className="text-[13px] font-bold text-[rgba(0,0,0,0.75)] mb-3 mt-5 flex items-center gap-2">
              <Icon icon="mdi:server-outline" width={14} color="#6366F1" />
              Step 2: Detect VPC &amp; Create Resources
            </h3>
            <p className="text-[13px] text-[rgba(0,0,0,0.55)] mb-4">
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
INSTANCE_ID=$(aws ec2 run-instances \\
  --image-id \${EC2_AMI_ID} \\
  --instance-type \${EC2_INSTANCE_TYPE} \\
  --key-name \${EC2_KEY_NAME} \\
  --security-group-ids $SG_ID \\
  --subnet-id \${SUBNET_ID} \\
  --associate-public-ip-address \\
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=\${EC2_INSTANCE_NAME}}]" \\
  --region \${AWS_REGION} \\
  --query 'Instances[0].InstanceId' --output text)

aws ec2 wait instance-running --instance-ids $INSTANCE_ID --region \${AWS_REGION}
PUBLIC_IP=$(aws ec2 describe-instances --instance-ids $INSTANCE_ID --region \${AWS_REGION} --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
echo "EC2 Setup Complete. IP: $PUBLIC_IP"`}</CodeBlock>
          </div>

          <hr className="border-[rgba(0,0,0,0.06)]" />

          {/* Phase 2 */}
          <PhaseHeader num={2} title="Deploy Monitoring Stack on EC2" color="bg-emerald-500" />
          <div>
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.55)] border border-[rgba(0,0,0,0.08)] rounded-full text-[11px] font-semibold mb-3">
              <Icon icon="mdi:server-outline" width={12} />
              EC2 Instance
            </span>
            <p className="text-[13px] text-[rgba(0,0,0,0.55)] mb-4">
              SSH into the new instance and deploy the OTel Gateway and Prometheus.
            </p>

            <h3 className="text-[13px] font-bold text-[rgba(0,0,0,0.75)] mb-3 flex items-center gap-2">
              <Icon icon="mdi:console" width={14} color="#059669" />
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
    volumes:
      - ./otel-gateway-config.yaml:/etc/otelcol/config.yaml
    ports:
      - "4318:4318"
      - "4317:4317"
      - "8889:8889"
    command: ["--config=/etc/otelcol/config.yaml"]

  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    ports:
      - "9090:9090"

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

          <hr className="border-[rgba(0,0,0,0.06)]" />

          {/* Phase 3 */}
          <PhaseHeader num={3} title="Install Cluster Agents" color="bg-purple-500" />
          <div>
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-orange-50 text-orange-700 border border-orange-100 rounded-full text-[11px] font-semibold mb-3">
              <Icon icon="mdi:aws" width={12} color="#FF9900" />
              AWS CloudShell
            </span>
            <p className="text-[13px] text-[rgba(0,0,0,0.55)] mb-4">
              Back in CloudShell, install the components that will run on your EKS cluster.
            </p>

            <h3 className="text-[13px] font-bold text-[rgba(0,0,0,0.75)] mb-3 flex items-center gap-2">
              <Icon icon="mdi:download-outline" width={14} color="#7C3AED" />
              Step 1: Install Exporters &amp; Falco
            </h3>
            <CodeBlock>{`# Install Helm if needed
curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
chmod 700 get_helm.sh && ./get_helm.sh

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
  --namespace falco --create-namespace \\
  --set falcosidekick.enabled=true \\
  --set falcosidekick.config.prometheus.enabled=true \\
  --wait`}</CodeBlock>

            <h3 className="text-[13px] font-bold text-[rgba(0,0,0,0.75)] mb-3 mt-5 flex items-center gap-2">
              <Icon icon="mdi:satellite-uplink" width={14} color="#7C3AED" />
              Step 2: Deploy OTel Collector Agent
            </h3>
            <p className="text-[13px] text-[rgba(0,0,0,0.55)] mb-4">
              This agent runs on every node, scrapes metrics, and forwards them to your EC2 instance.
            </p>
            <CodeBlock>{`# Add OTel Repo
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
helm repo update

EC2_PUBLIC_IP="<REPLACE_WITH_EC2_PUBLIC_IP>"

cat > otel-collector-values.yaml << VALUESEOF
mode: daemonset
config:
  receivers:
    prometheus:
      config:
        scrape_configs:
          - job_name: 'falco-sidekick'
            static_configs:
              - targets: ['falco-falcosidekick.falco.svc.cluster.local:2801']
          - job_name: 'kube-state-metrics'
            static_configs:
              - targets: ['kube-state-metrics.kube-system.svc.cluster.local:8080']
          - job_name: 'node-exporter'
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

helm upgrade --install otel-agent open-telemetry/opentelemetry-collector --namespace default -f otel-collector-values.yaml --wait`}</CodeBlock>
          </div>

          <hr className="border-[rgba(0,0,0,0.06)]" />

          {/* Validation */}
          <PhaseHeader num={4} title="Validation" color="bg-emerald-500" />
          <p className="text-[13px] text-[rgba(0,0,0,0.55)] mb-4">
            Once setup is complete, access your Prometheus dashboard:
          </p>
          <div className="bg-emerald-50 border border-emerald-200 rounded-xl p-5 text-center mb-5">
            <p className="font-bold text-emerald-800 text-[15px] font-mono">http://&lt;EC2_PUBLIC_IP&gt;:9090</p>
          </div>

          <h4 className="text-[13px] font-bold text-[rgba(0,0,0,0.75)] mb-3">Essential Queries</h4>
          <ul className="space-y-2">
            {[
              { icon: 'mdi:shield-alert-outline', color: '#DC2626', label: 'Falco Events', query: 'cluster_metrics_falcosecurity_falcosidekick_falco_events_total' },
              { icon: 'mdi:chip', color: '#3B82F6', label: 'Node CPU', query: 'cluster_metrics_node_cpu_seconds_total' },
              { icon: 'mdi:hexagon-multiple-outline', color: '#7C3AED', label: 'Pod Info', query: 'cluster_metrics_kube_pod_info' },
            ].map(item => (
              <li key={item.label} className="flex items-start gap-2">
                <Icon icon={item.icon} width={14} color={item.color} className="mt-0.5 flex-shrink-0" />
                <div className="text-[13px]">
                  <strong className="text-[rgba(0,0,0,0.75)]">{item.label}:</strong>
                  <code className="ml-2 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)] px-2 py-0.5 rounded text-[11px] font-mono">
                    {item.query}
                  </code>
                </div>
              </li>
            ))}
          </ul>
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default CloudK8sHelpModal;
