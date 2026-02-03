/**
 * EKS Integration Guide Modal
 *
 * Complete EKS Monitoring Setup Guide with copy buttons
 * OTel + Falco + Kube-State-Metrics + cAdvisor + cost exporter + OpenCost + Kubescape + Trivy
 */

import React, { useState } from 'react';

interface EksIntegrationModalProps {
  isOpen: boolean;
  onClose: () => void;
}

interface CodeBlockProps {
  code: string;
  title?: string;
}

const CodeBlock: React.FC<CodeBlockProps> = ({ code, title }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Failed to copy:', err);
    }
  };

  return (
    <div className="relative group">
      {title && (
        <div className="bg-gray-800 text-gray-300 px-4 py-2 text-xs font-medium rounded-t-lg border-b border-gray-700">
          {title}
        </div>
      )}
      <div className={`bg-gray-900 text-gray-100 p-4 font-mono text-sm overflow-x-auto ${title ? 'rounded-b-lg' : 'rounded-lg'}`}>
        <button
          onClick={handleCopy}
          className="absolute top-2 right-2 px-2 py-1 bg-gray-700 hover:bg-gray-600 text-gray-300 hover:text-white rounded text-xs transition-all opacity-0 group-hover:opacity-100"
        >
          {copied ? (
            <span className="flex items-center gap-1">
              <i className="fas fa-check"></i> Copied
            </span>
          ) : (
            <span className="flex items-center gap-1">
              <i className="fas fa-copy"></i> Copy
            </span>
          )}
        </button>
        <pre className="whitespace-pre-wrap break-words">{code}</pre>
      </div>
    </div>
  );
};

interface CollapsibleSectionProps {
  title: string;
  icon?: string;
  iconColor?: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
}

const CollapsibleSection: React.FC<CollapsibleSectionProps> = ({
  title,
  icon,
  iconColor = 'text-blue-500',
  defaultOpen = false,
  children
}) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);

  return (
    <div className="border border-gray-200 rounded-lg overflow-hidden">
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center justify-between p-4 bg-gray-50 hover:bg-gray-100 transition-colors"
      >
        <div className="flex items-center gap-3">
          {icon && <i className={`${icon} ${iconColor}`}></i>}
          <span className="font-semibold text-gray-800">{title}</span>
        </div>
        <i className={`fas fa-chevron-down text-gray-400 transition-transform ${isOpen ? 'rotate-180' : ''}`}></i>
      </button>
      {isOpen && <div className="p-4 space-y-4 bg-white">{children}</div>}
    </div>
  );
};

const VariableNote: React.FC<{ variables: string[] }> = ({ variables }) => (
  <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mb-4">
    <p className="text-sm text-amber-800">
      <i className="fas fa-exclamation-triangle mr-2"></i>
      <strong>Variables to change:</strong> {variables.join(', ')}
    </p>
  </div>
);

const EksIntegrationModal: React.FC<EksIntegrationModalProps> = ({ isOpen, onClose }) => {
  const [activeTab, setActiveTab] = useState<'overview' | 'part1' | 'part2' | 'part3' | 'part4' | 'part5'>('overview');

  if (!isOpen) return null;

  const tabs = [
    { id: 'overview', label: 'Overview', icon: 'fas fa-info-circle' },
    { id: 'part1', label: 'Part 1: Monitoring Instance', icon: 'fas fa-server' },
    { id: 'part2', label: 'Part 2: Cluster Setup', icon: 'fas fa-dharmachakra' },
    { id: 'part3', label: 'Part 3: Security Tools', icon: 'fas fa-shield-alt' },
    { id: 'part4', label: 'Part 4: Verification', icon: 'fas fa-check-circle' },
    { id: 'part5', label: 'Part 5: Variables Summary', icon: 'fas fa-list' },
  ];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div onClick={onClose} className="fixed inset-0 bg-black/50 backdrop-blur-sm" />

      <div
        className="bg-white rounded-2xl w-full max-w-6xl mx-auto max-h-[95vh] overflow-hidden z-50 shadow-2xl relative flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b border-gray-200 bg-gradient-to-r from-slate-50 to-blue-50">
          <div className="flex items-center space-x-4">
            <div className="w-14 h-14 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center shadow-lg">
              <i className="fas fa-book text-white text-2xl"></i>
            </div>
            <div>
              <h3 className="font-bold text-xl text-gray-800">Complete EKS Monitoring Setup Guide</h3>
              <p className="text-sm text-gray-600">OTel + Falco + Kube-State-Metrics + cAdvisor + OpenCost + Kubescape + Trivy</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-3xl leading-none hover:bg-gray-100 w-10 h-10 rounded-full flex items-center justify-center transition-colors"
          >
            &times;
          </button>
        </div>

        {/* Tab Navigation */}
        <div className="flex border-b border-gray-200 bg-gray-50 overflow-x-auto">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as any)}
              className={`flex items-center gap-2 px-4 py-3 text-sm font-medium whitespace-nowrap transition-colors border-b-2 ${activeTab === tab.id
                  ? 'text-blue-600 border-blue-600 bg-white'
                  : 'text-gray-600 border-transparent hover:text-gray-800 hover:bg-gray-100'
                }`}
            >
              <i className={tab.icon}></i>
              {tab.label}
            </button>
          ))}
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-6">
          {activeTab === 'overview' && <OverviewTab />}
          {activeTab === 'part1' && <Part1Tab />}
          {activeTab === 'part2' && <Part2Tab />}
          {activeTab === 'part3' && <Part3Tab />}
          {activeTab === 'part4' && <Part4Tab />}
          {activeTab === 'part5' && <Part5Tab />}
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-200 bg-gray-50 flex justify-end">
          <button
            onClick={onClose}
            className="px-6 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg font-semibold transition-all shadow-sm"
          >
            Close Guide
          </button>
        </div>
      </div>
    </div>
  );
};

// Overview Tab
const OverviewTab: React.FC = () => (
  <div className="space-y-6">
    <div className="bg-blue-50 border-l-4 border-blue-500 p-4 rounded-r-lg">
      <h4 className="font-bold text-blue-800 mb-2">This guide installs:</h4>
      <ul className="list-disc list-inside space-y-1 text-blue-700 text-sm">
        <li><strong>System & Kubernetes Metrics</strong> (OTel + Kube-State-Metrics)</li>
        <li><strong>Container Metrics</strong> (cAdvisor for CPU/memory)</li>
        <li><strong>Security Events</strong> (Falco)</li>
        <li><strong>Cost Tracking</strong> (Cost Exporter + OpenCost)</li>
        <li><strong>Vulnerability Scanning</strong> (Trivy)</li>
        <li><strong>Compliance Scanning</strong> (Kubescape)</li>
      </ul>
    </div>

    <div className="bg-white border border-gray-200 rounded-lg p-6">
      <h4 className="font-bold text-gray-800 mb-4 flex items-center gap-2">
        <i className="fas fa-clipboard-list text-indigo-500"></i>
        Prerequisites
      </h4>
      <ul className="list-disc list-inside space-y-2 text-gray-700">
        <li>AWS Account with EKS cluster access</li>
        <li>kubectl (v1.28+), helm (v3.12+), aws-cli (v2.x)</li>
        <li>EC2 instance for monitoring (t3.large, 50GB storage)</li>
      </ul>
    </div>

    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      <div className="bg-gradient-to-br from-blue-50 to-indigo-50 border border-blue-200 rounded-lg p-4">
        <div className="flex items-center gap-3 mb-2">
          <div className="w-10 h-10 bg-blue-500 rounded-lg flex items-center justify-center">
            <i className="fas fa-server text-white"></i>
          </div>
          <h5 className="font-semibold text-gray-800">Part 1</h5>
        </div>
        <p className="text-sm text-gray-600">Monitoring Instance Setup - EC2 + Docker + Prometheus + OTel Gateway</p>
      </div>
      <div className="bg-gradient-to-br from-green-50 to-emerald-50 border border-green-200 rounded-lg p-4">
        <div className="flex items-center gap-3 mb-2">
          <div className="w-10 h-10 bg-green-500 rounded-lg flex items-center justify-center">
            <i className="fas fa-dharmachakra text-white"></i>
          </div>
          <h5 className="font-semibold text-gray-800">Part 2</h5>
        </div>
        <p className="text-sm text-gray-600">Cluster Setup - OTel Agent + KSM + Falco + Cost Exporter + OpenCost</p>
      </div>
      <div className="bg-gradient-to-br from-purple-50 to-violet-50 border border-purple-200 rounded-lg p-4">
        <div className="flex items-center gap-3 mb-2">
          <div className="w-10 h-10 bg-purple-500 rounded-lg flex items-center justify-center">
            <i className="fas fa-shield-alt text-white"></i>
          </div>
          <h5 className="font-semibold text-gray-800">Part 3</h5>
        </div>
        <p className="text-sm text-gray-600">Security Tools - Trivy + Kubescape + Kubeconfig Generation</p>
      </div>
      <div className="bg-gradient-to-br from-orange-50 to-amber-50 border border-orange-200 rounded-lg p-4">
        <div className="flex items-center gap-3 mb-2">
          <div className="w-10 h-10 bg-orange-500 rounded-lg flex items-center justify-center">
            <i className="fas fa-check-circle text-white"></i>
          </div>
          <h5 className="font-semibold text-gray-800">Part 4</h5>
        </div>
        <p className="text-sm text-gray-600">Verification - Test all components and metrics</p>
      </div>
    </div>
  </div>
);

// Part 1: Monitoring Instance Setup
const Part1Tab: React.FC = () => (
  <div className="space-y-6">
    <h3 className="text-xl font-bold text-gray-800 mb-4">Part 1: Monitoring Instance Setup</h3>

    <CollapsibleSection title="Step 1.1: Create EC2 Instance Script" icon="fas fa-cloud" defaultOpen>
      <VariableNote variables={['<VPC_ID>', '<INSTANCE_TYPE>', '<REGION>']} />
      <CodeBlock
        title="create-instance.sh"
        code={`nano create-instance.sh
#!/bin/bash
set -e
VPC_ID="<VPC_ID>"
INSTANCE_TYPE="<INSTANCE_TYPE>"
INSTANCE_NAME="Xamops-EKS-Instance"
STORAGE_SIZE=50
REGION="<REGION>"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
AMI_ID=$(aws ec2 describe-images --region $REGION --owners 099720109477 --filters "Name=name,Values=ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*" --query 'Images | sort_by(@, &CreationDate) | [-1].ImageId' --output text)
SUBNET_ID=$(aws ec2 describe-subnets --region $REGION --filters "Name=vpc-id,Values=$VPC_ID" "Name=map-public-ip-on-launch,Values=true" --query 'Subnets[0].SubnetId' --output text)
if [ "$SUBNET_ID" == "None" ]; then
  SUBNET_ID=$(aws ec2 describe-subnets --region $REGION --filters "Name=vpc-id,Values=$VPC_ID" --query 'Subnets[0].SubnetId' --output text)
fi
MY_IP=$(curl -s https://checkip.amazonaws.com)
VPC_CIDR=$(aws ec2 describe-vpcs --region $REGION --vpc-ids $VPC_ID --query 'Vpcs[0].CidrBlock' --output text)
SG_NAME="xamops-monitoring-sg-\${TIMESTAMP}"
SG_ID=$(aws ec2 create-security-group --region $REGION --group-name "$SG_NAME" --description "Security group for Xamops monitoring instance" --vpc-id $VPC_ID --query 'GroupId' --output text)
echo "Security Group: $SG_ID"
sleep 3
aws ec2 authorize-security-group-ingress --region $REGION --group-id $SG_ID --ip-permissions IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges="[{CidrIp=$MY_IP/32}]" IpProtocol=tcp,FromPort=4317,ToPort=4317,IpRanges="[{CidrIp=$VPC_CIDR}]" IpProtocol=tcp,FromPort=4318,ToPort=4318,IpRanges="[{CidrIp=$VPC_CIDR}]" IpProtocol=tcp,FromPort=9090,ToPort=9090,IpRanges="[{CidrIp=$MY_IP/32}]"
sleep 2
KEY_NAME="xamops-eks-key-\${TIMESTAMP}"
aws ec2 create-key-pair --region $REGION --key-name $KEY_NAME --query 'KeyMaterial' --output text > \${KEY_NAME}.pem
chmod 400 \${KEY_NAME}.pem
echo "Key Pair: \${KEY_NAME}.pem"
sleep 2
INSTANCE_ID=$(aws ec2 run-instances --region $REGION --image-id $AMI_ID --instance-type $INSTANCE_TYPE --key-name $KEY_NAME --security-group-ids $SG_ID --subnet-id $SUBNET_ID --associate-public-ip-address --block-device-mappings "[{\\"DeviceName\\":\\"/dev/sda1\\",\\"Ebs\\":{\\"VolumeSize\\":$STORAGE_SIZE,\\"VolumeType\\":\\"gp3\\",\\"DeleteOnTermination\\":true}}]" --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$INSTANCE_NAME}]" --query 'Instances[0].InstanceId' --output text)
echo "Instance ID: $INSTANCE_ID"
aws ec2 wait instance-running --region $REGION --instance-ids $INSTANCE_ID
PUBLIC_IP=$(aws ec2 describe-instances --region $REGION --instance-ids $INSTANCE_ID --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
PRIVATE_IP=$(aws ec2 describe-instances --region $REGION --instance-ids $INSTANCE_ID --query 'Reservations[0].Instances[0].PrivateIpAddress' --output text)
echo "Public IP: $PUBLIC_IP"
echo "Private IP: $PRIVATE_IP"
echo "SSH Command: ssh -i \${KEY_NAME}.pem ubuntu@\${PUBLIC_IP}"`}
      />
      <CodeBlock code={`chmod +x create-instance.sh
./create-instance.sh`} />
      <div className="bg-green-50 border border-green-200 rounded-lg p-3 mt-4">
        <p className="text-sm text-green-800">
          <i className="fas fa-info-circle mr-2"></i>
          <strong>Save the PRIVATE_IP</strong> - you'll need it later for cluster configuration.
        </p>
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.2: Install Docker" icon="fab fa-docker" iconColor="text-blue-500">
      <CodeBlock code={`sudo apt-get update
sudo apt-get install -y docker.io docker-compose
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker $USER
exit`} />
      <p className="text-sm text-gray-600 mt-2">Re-login and verify:</p>
      <CodeBlock code={`docker --version`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.3: Install Docker Compose" icon="fab fa-docker" iconColor="text-blue-500">
      <CodeBlock code={`sudo curl -L "https://github.com/docker/compose/releases/download/v2.24.0/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
docker-compose --version`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.4: Create Monitoring Directory" icon="fas fa-folder">
      <CodeBlock code={`mkdir -p ~/monitoring
cd ~/monitoring`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.5: Create Prometheus Configuration" icon="fas fa-fire" iconColor="text-orange-500">
      <CodeBlock
        title="prometheus.yml"
        code={`cat > ~/monitoring/prometheus.yml << 'EOF'
global:
  scrape_interval: 30s
  evaluation_interval: 30s

scrape_configs:
  - job_name: 'otel-gateway'
    static_configs:
      - targets: ['otel-gateway:8889']

  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']

  - job_name: 'kube-state-metrics'
    static_configs:
      - targets: ['otel-gateway:8889']
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: 'xamops_eks_kube.*'
        action: keep
      - source_labels: [__name__]
        regex: 'xamops_eks_(.*)'
        target_label: __name__
        replacement: '\${1}'

  - job_name: 'kubernetes-nodes'
    static_configs:
      - targets: ['otel-gateway:8889']
    metric_relabel_configs:
      - source_labels: [exported_job]
        regex: 'kubernetes-nodes'
        action: keep
      - source_labels: [__name__]
        regex: 'node_.*'
        action: keep

  - job_name: 'opencost'
    static_configs:
      - targets: ['otel-gateway:8889']
    metric_relabel_configs:
      - source_labels: [exported_job]
        regex: 'opencost'
        action: keep
      - source_labels: [__name__]
        regex: 'opencost_.*'
        action: keep

  # CRITICAL: cAdvisor job strips xamops_eks_ prefix for OpenCost
  - job_name: 'cadvisor'
    static_configs:
      - targets: ['otel-gateway:8889']
    metric_relabel_configs:
      - source_labels: [exported_job]
        regex: 'kubernetes-cadvisor'
        action: keep
      - source_labels: [__name__]
        regex: 'xamops_eks_container_(cpu_usage_seconds_total|memory_working_set_bytes|memory_usage_bytes|network_receive_bytes_total|network_transmit_bytes_total|fs_usage_bytes|fs_limit_bytes)'
        action: keep
      - source_labels: [__name__]
        regex: 'xamops_eks_(.*)'
        target_label: __name__
        replacement: '\${1}'

  - job_name: 'container-metrics-raw'
    static_configs:
      - targets: ['otel-gateway:8889']
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: 'container_cpu_usage_seconds_total|container_memory_working_set_bytes|container_memory_usage_bytes|container_fs_usage_bytes|container_fs_limit_bytes|container_network_.*'
        action: keep

  - job_name: 'kube-state-metrics-raw'
    static_configs:
      - targets: ['otel-gateway:8889']
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: 'kube_pod_info|kube_pod_container_resource_requests|kube_pod_container_resource_limits|kube_pod_status_phase|kube_pod_owner|kube_node_info|kube_node_status_capacity|kube_node_status_allocatable'
        action: keep

  - job_name: 'falco'
    static_configs:
      - targets: ['otel-gateway:8889']
    metric_relabel_configs:
      - source_labels: [exported_job]
        regex: 'falco-sidekick'
        action: keep
EOF`}
      />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.6: Create OTel Gateway Configuration" icon="fas fa-network-wired" iconColor="text-purple-500">
      <VariableNote variables={['<CLUSTER_NAME>']} />
      <CodeBlock
        title="otel-gateway-config.yaml"
        code={`cat > ~/monitoring/otel-gateway-config.yaml << 'EOF'
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: "0.0.0.0:4317"
      http:
        endpoint: "0.0.0.0:4318"

exporters:
  prometheusremotewrite:
    endpoint: "http://prometheus:9090/api/v1/write"
    namespace: xamops_eks
    tls:
      insecure: true
  prometheus:
    endpoint: "0.0.0.0:8889"
    namespace: xamops_eks

processors:
  batch:
    timeout: 10s
    send_batch_size: 1024
  resource:
    attributes:
      - key: cluster_name
        value: <CLUSTER_NAME>
        action: insert
  attributes:
    actions:
      - key: cluster_name
        value: <CLUSTER_NAME>
        action: insert

service:
  pipelines:
    metrics:
      receivers: [otlp]
      processors: [batch, resource, attributes]
      exporters: [prometheusremotewrite, prometheus]
EOF`}
      />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.7: Create Docker Compose File" icon="fab fa-docker" iconColor="text-blue-500">
      <CodeBlock
        title="docker-compose.yml"
        code={`cat > ~/monitoring/docker-compose.yml << 'EOF'
version: '3.8'
services:
  otel-gateway:
    image: otel/opentelemetry-collector-contrib:latest
    container_name: otel-gateway
    volumes:
      - ./otel-gateway-config.yaml:/etc/otelcol/config.yaml
    ports:
      - "4318:4318"
      - "4317:4317"
      - "8889:8889"
    command: ["--config=/etc/otelcol/config.yaml"]
    restart: unless-stopped
    networks:
      - monitoring

  prometheus:
    image: prom/prometheus:latest
    container_name: prometheus
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    ports:
      - "9090:9090"
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.retention.time=2d'
      - '--storage.tsdb.retention.size=10GB'
      - '--web.enable-remote-write-receiver'
      - '--enable-feature=native-histograms'
      - '--storage.tsdb.allow-overlapping-blocks'
      - '--storage.tsdb.out-of-order.time-window=30m'
    restart: unless-stopped
    networks:
      - monitoring

networks:
  monitoring:
    driver: bridge

volumes:
  prometheus-data:
EOF`}
      />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.8: Start Monitoring Stack" icon="fas fa-play" iconColor="text-green-500">
      <CodeBlock code={`cd ~/monitoring
docker-compose up -d
docker ps
curl http://localhost:9090/api/v1/status/config`} />
      <div className="bg-blue-50 border border-blue-200 rounded-lg p-3 mt-4">
        <p className="text-sm text-blue-800">
          <i className="fas fa-globe mr-2"></i>
          Access Prometheus UI: <code className="bg-blue-100 px-2 py-0.5 rounded">http://&lt;MONITORING_PUBLIC_IP&gt;:9090</code>
        </p>
      </div>
    </CollapsibleSection>
  </div>
);

// Part 2: Cluster Setup
const Part2Tab: React.FC = () => (
  <div className="space-y-6">
    <h3 className="text-xl font-bold text-gray-800 mb-4">Part 2: Cluster Setup</h3>

    <CollapsibleSection title="Step 2.1: Add Helm Repositories" icon="fas fa-archive" defaultOpen>
      <CodeBlock code={`helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo add opencost https://opencost.github.io/opencost-helm-chart
helm repo update`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.2: Create OTel Collector Values File" icon="fas fa-cogs" iconColor="text-purple-500">
      <VariableNote variables={['<CLUSTER_NAME>', '<MONITORING_INSTANCE_PRIVATE_IP>']} />
      <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-amber-800">
          <i className="fas fa-exclamation-circle mr-2"></i>
          <strong>IMPORTANT:</strong> This config includes cAdvisor scrape job for CPU/memory metrics
        </p>
      </div>
      <CodeBlock code={`mkdir -p ~/cluster-monitoring
cd ~/cluster-monitoring`} />
      <CodeBlock
        title="otel-agent-values.yaml"
        code={`cat > otel-agent-values.yaml << 'EOF'
mode: daemonset

image:
  repository: otel/opentelemetry-collector-contrib
  tag: "0.142.0"

resources:
  requests:
    cpu: 500m
    memory: 512Mi
  limits:
    cpu: 2000m
    memory: 2Gi

serviceAccount:
  create: true

clusterRole:
  create: true
  rules:
    - apiGroups: [""]
      resources: ["pods", "namespaces", "nodes"]
      verbs: ["get", "list", "watch"]
    - apiGroups: ["apps"]
      resources: ["replicasets", "daemonsets", "deployments", "statefulsets"]
      verbs: ["get", "list", "watch"]
    - apiGroups: ["batch"]
      resources: ["jobs", "cronjobs"]
      verbs: ["get", "list", "watch"]

config:
  receivers:
    hostmetrics:
      collection_interval: 60s
      scrapers:
        cpu: {}
        disk: {}
        filesystem: {}
        memory: {}
        network: {}

    kubeletstats:
      auth_type: serviceAccount
      collection_interval: 60s
      endpoint: \${env:K8S_NODE_IP}:10250
      insecure_skip_verify: true
      metric_groups:
        - node
        - pod
        - container

    prometheus:
      config:
        scrape_configs:
          - job_name: cost-exporter
            scrape_interval: 60s
            static_configs:
              - targets:
                  - cost-exporter.default.svc.cluster.local:8080
            metric_relabel_configs:
              - action: keep
                regex: xamops_eks_.*
                source_labels:
                  - __name__

          - job_name: kube-state-metrics
            scrape_interval: 60s
            static_configs:
              - targets:
                  - kube-state-metrics.kube-system.svc.cluster.local:8080

          - job_name: falco-sidekick
            scrape_interval: 60s
            static_configs:
              - targets:
                  - falco-falcosidekick.falco.svc.cluster.local:2801

          - job_name: opencost
            scrape_interval: 60s
            static_configs:
              - targets:
                  - opencost.opencost.svc.cluster.local:9003

          - job_name: kubernetes-nodes
            scrape_interval: 60s
            scheme: https
            tls_config:
              ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
            bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
            kubernetes_sd_configs:
              - role: node
            relabel_configs:
              - action: labelmap
                regex: __meta_kubernetes_node_label_(.+)
              - target_label: __address__
                replacement: kubernetes.default.svc:443
              - source_labels: [__meta_kubernetes_node_name]
                regex: (.+)
                target_label: __metrics_path__
                replacement: /api/v1/nodes/\$\${1}/proxy/metrics

          # CRITICAL: cAdvisor job for OpenCost CPU/memory efficiency
          - job_name: kubernetes-cadvisor
            scrape_interval: 60s
            scheme: https
            tls_config:
              ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
              insecure_skip_verify: true
            bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
            kubernetes_sd_configs:
              - role: node
            relabel_configs:
              - action: labelmap
                regex: __meta_kubernetes_node_label_(.+)
              - target_label: __address__
                replacement: kubernetes.default.svc:443
              - source_labels: [__meta_kubernetes_node_name]
                regex: (.+)
                target_label: __metrics_path__
                replacement: /api/v1/nodes/\$\${1}/proxy/metrics/cadvisor
            metric_relabel_configs:
              - source_labels: [__name__]
                regex: 'container_(cpu_usage_seconds_total|memory_working_set_bytes|memory_usage_bytes|network_receive_bytes_total|network_transmit_bytes_total|fs_usage_bytes|fs_limit_bytes)'
                action: keep

  processors:
    batch:
      send_batch_size: 1024
      timeout: 10s
    k8sattributes:
      auth_type: serviceAccount
      passthrough: false
      filter:
        node_from_env_var: K8S_NODE_NAME
      extract:
        metadata:
          - k8s.namespace.name
          - k8s.pod.name
          - k8s.pod.uid
          - k8s.node.name
          - k8s.deployment.name
          - k8s.container.name
    memory_limiter:
      check_interval: 5s
      limit_percentage: 80
      spike_limit_percentage: 25
    resource:
      attributes:
        - action: insert
          key: cluster_name
          value: <CLUSTER_NAME>

  exporters:
    debug:
      verbosity: detailed
    otlphttp:
      endpoint: http://<MONITORING_INSTANCE_PRIVATE_IP>:4318
      tls:
        insecure: true

  extensions:
    health_check:
      endpoint: \${env:MY_POD_IP}:13133

  service:
    extensions:
      - health_check
    pipelines:
      metrics:
        receivers: [prometheus, hostmetrics, kubeletstats]
        processors: [k8sattributes, batch, resource]
        exporters: [otlphttp, debug]

extraEnvs:
  - name: MY_POD_IP
    valueFrom:
      fieldRef:
        apiVersion: v1
        fieldPath: status.podIP
  - name: K8S_NODE_NAME
    valueFrom:
      fieldRef:
        apiVersion: v1
        fieldPath: spec.nodeName
  - name: K8S_NODE_IP
    valueFrom:
      fieldRef:
        apiVersion: v1
        fieldPath: status.hostIP
EOF`}
      />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.3: Create RBAC for Container Metrics" icon="fas fa-key" iconColor="text-yellow-500">
      <div className="bg-red-50 border border-red-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-red-800">
          <i className="fas fa-exclamation-triangle mr-2"></i>
          <strong>CRITICAL:</strong> Required for cAdvisor to work
        </p>
      </div>
      <CodeBlock code={`kubectl apply -f - <<EOF
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: otel-collector-stats-reader
rules:
- apiGroups: [""]
  resources: ["nodes/stats", "nodes/proxy"]
  verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: otel-collector-stats-reader
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: otel-collector-stats-reader
subjects:
- kind: ServiceAccount
  name: otel-collector-opentelemetry-collector
  namespace: default
EOF`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.4: Deploy OTel Collector" icon="fas fa-rocket" iconColor="text-blue-500">
      <CodeBlock code={`helm upgrade --install otel-collector open-telemetry/opentelemetry-collector --namespace default --values otel-agent-values.yaml --wait
kubectl get daemonset -l app.kubernetes.io/name=opentelemetry-collector
kubectl get pods -l app.kubernetes.io/name=opentelemetry-collector`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.5: Install Kube-State-Metrics" icon="fas fa-chart-bar" iconColor="text-green-500">
      <CodeBlock code={`helm install kube-state-metrics prometheus-community/kube-state-metrics --namespace kube-system --set resources.requests.cpu=200m --set resources.requests.memory=256Mi --set resources.limits.cpu=500m --set resources.limits.memory=512Mi --wait
kubectl get deployment -n kube-system kube-state-metrics`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.6: Install Falco" icon="fas fa-shield-alt" iconColor="text-red-500">
      <VariableNote variables={['<CLUSTER_NAME>']} />
      <CodeBlock code={`helm install falco falcosecurity/falco --namespace falco --create-namespace --set tty=true --set falco.grpc.enabled=true --set falco.grpcOutput.enabled=true --set falcosidekick.enabled=true --set falcosidekick.webui.enabled=false --set falcosidekick.config.prometheus.extralabels="cluster_name:<CLUSTER_NAME>" --wait
kubectl get daemonset -n falco
kubectl get deployment -n falco falco-falcosidekick`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.7: Create IAM Role for Cost Exporter" icon="fas fa-user-shield" iconColor="text-orange-500">
      <VariableNote variables={['<CLUSTER_NAME>', '<REGION>']} />
      <div className="bg-blue-50 border border-blue-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-blue-800">
          <i className="fas fa-info-circle mr-2"></i>
          The cost exporter needs AWS permissions to describe EC2 instances and retrieve pricing information
        </p>
      </div>
      <CodeBlock
        title="create-cost-exporter-role.sh"
        code={`cat > create-cost-exporter-role.sh << 'SCRIPT'
#!/bin/bash
CLUSTER_NAME="<CLUSTER_NAME>"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
REGION="<REGION>"
OIDC_PROVIDER=$(aws eks describe-cluster --name $CLUSTER_NAME --region $REGION --query "cluster.identity.oidc.issuer" --output text | sed -e "s/^https:\\/\\///")
cat > /tmp/trust-policy.json << TRUST
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Federated": "arn:aws:iam::\${ACCOUNT_ID}:oidc-provider/\${OIDC_PROVIDER}"},
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {"StringEquals": {"\${OIDC_PROVIDER}:sub": "system:serviceaccount:default:cost-exporter", "\${OIDC_PROVIDER}:aud": "sts.amazonaws.com"}}
  }]
}
TRUST
cat > /tmp/cost-exporter-policy.json << POLICY
{
  "Version": "2012-10-17",
  "Statement": [{"Effect": "Allow", "Action": ["ec2:DescribeInstances", "ec2:DescribeTags", "ce:GetCostAndUsage"], "Resource": "*"}]
}
POLICY
aws iam create-role --role-name DemoEKSCostExporterRole --assume-role-policy-document file:///tmp/trust-policy.json
aws iam put-role-policy --role-name DemoEKSCostExporterRole --policy-name CostExporterPolicy --policy-document file:///tmp/cost-exporter-policy.json
echo "ARN: arn:aws:iam::\${ACCOUNT_ID}:role/DemoEKSCostExporterRole"
SCRIPT
chmod +x create-cost-exporter-role.sh
./create-cost-exporter-role.sh`}
      />
      <div className="bg-green-50 border border-green-200 rounded-lg p-3 mt-4">
        <p className="text-sm text-green-800">
          <i className="fas fa-save mr-2"></i>
          <strong>Save the ARN output</strong> - you'll need it in the next step
        </p>
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.8: Deploy Cost Exporter" icon="fas fa-dollar-sign" iconColor="text-green-500">
      <VariableNote variables={['<AWS_ACCOUNT_ID>']} />
      <CodeBlock
        title="cost-exporter-sa.yaml"
        code={`cat > cost-exporter-sa.yaml << 'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: cost-exporter
  namespace: default
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::<AWS_ACCOUNT_ID>:role/DemoEKSCostExporterRole
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: cost-exporter
rules:
- apiGroups: [""]
  resources: ["nodes", "pods"]
  verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: cost-exporter
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cost-exporter
subjects:
- kind: ServiceAccount
  name: cost-exporter
  namespace: default
EOF
kubectl apply -f cost-exporter-sa.yaml`}
      />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.8 Continued: Cost Exporter Script ConfigMap" icon="fas fa-file-code" iconColor="text-blue-500">
      <VariableNote variables={['<CLUSTER_NAME>', '<REGION>']} />
      <CodeBlock
        title="cost-exporter-script.yaml"
        code={`cat > cost-exporter-script.yaml << 'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: cost-exporter-script
  namespace: default
data:
  exporter.py: |
    #!/usr/bin/env python3
    import boto3
    import os
    from datetime import datetime, timedelta
    from prometheus_client import start_http_server, Gauge
    import time

    NODE_COST_GAUGE = Gauge('xamops_eks_node_cost_per_hour', 'Cost per hour for each node', ['cluster_name', 'node_name', 'instance_type', 'instance_id'])
    NODE_DAILY_COST_GAUGE = Gauge('xamops_eks_node_daily_cost', 'Daily cost for each node', ['cluster_name', 'node_name', 'instance_type', 'instance_id'])
    CLUSTER_TOTAL_COST_GAUGE = Gauge('xamops_eks_cluster_total_daily_cost', 'Total cluster daily cost', ['cluster_name'])

    CLUSTER_NAME = os.getenv('CLUSTER_NAME', '<CLUSTER_NAME>')
    REGION = os.getenv('AWS_REGION', '<REGION>')

    INSTANCE_PRICING = {
        't3.micro': 0.0104, 't3.small': 0.0208, 't3.medium': 0.0416, 't3.large': 0.0832,
        't3.xlarge': 0.1664, 't3.2xlarge': 0.3328, 't2.small': 0.023, 't2.medium': 0.046,
        't2.large': 0.093, 'm5.large': 0.096, 'm5.xlarge': 0.192, 'm5.2xlarge': 0.384,
        'm5.4xlarge': 0.768, 'c5.large': 0.085, 'c5.xlarge': 0.17, 'c5.2xlarge': 0.34,
        'c5.4xlarge': 0.68, 'm6i.large': 0.096, 'm6i.xlarge': 0.192, 'm6i.2xlarge': 0.384,
        'c6i.large': 0.085, 'c6i.xlarge': 0.17
    }

    def get_instance_cost(instance_type):
        return INSTANCE_PRICING.get(instance_type, 0.10)

    def get_cluster_nodes():
        ec2 = boto3.client('ec2', region_name=REGION)
        response = ec2.describe_instances(
            Filters=[
                {'Name': 'tag:eks:cluster-name', 'Values': [CLUSTER_NAME]},
                {'Name': 'instance-state-name', 'Values': ['running']}
            ]
        )
        nodes = []
        for reservation in response['Reservations']:
            for instance in reservation['Instances']:
                node_name = None
                for tag in instance.get('Tags', []):
                    if tag['Key'] == 'Name':
                        node_name = tag['Value']
                        break
                if not node_name:
                    node_name = instance['PrivateDnsName']
                nodes.append({
                    'node_name': node_name,
                    'instance_id': instance['InstanceId'],
                    'instance_type': instance['InstanceType']
                })
        return nodes

    def update_metrics():
        try:
            nodes = get_cluster_nodes()
            total_daily_cost = 0
            for node in nodes:
                cost_per_hour = get_instance_cost(node['instance_type'])
                daily_cost = cost_per_hour * 24
                NODE_COST_GAUGE.labels(
                    cluster_name=CLUSTER_NAME,
                    node_name=node['node_name'],
                    instance_type=node['instance_type'],
                    instance_id=node['instance_id']
                ).set(cost_per_hour)
                NODE_DAILY_COST_GAUGE.labels(
                    cluster_name=CLUSTER_NAME,
                    node_name=node['node_name'],
                    instance_type=node['instance_type'],
                    instance_id=node['instance_id']
                ).set(daily_cost)
                total_daily_cost += daily_cost
            CLUSTER_TOTAL_COST_GAUGE.labels(cluster_name=CLUSTER_NAME).set(total_daily_cost)
            print(f'Updated metrics for {len(nodes)} nodes. Total daily cost: \${total_daily_cost:.2f}')
        except Exception as e:
            print(f'Error updating metrics: {e}')

    if __name__ == '__main__':
        start_http_server(8080)
        print('Cost exporter started on port 8080')
        while True:
            update_metrics()
            time.sleep(600)
EOF
kubectl apply -f cost-exporter-script.yaml`}
      />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.9: Deploy Cost Exporter Deployment" icon="fas fa-cube" iconColor="text-indigo-500">
      <VariableNote variables={['<CLUSTER_NAME>', '<REGION>']} />
      <CodeBlock
        title="cost-exporter-deployment.yaml"
        code={`cat > cost-exporter-deployment.yaml << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cost-exporter
  namespace: default
  labels:
    app: cost-exporter
spec:
  replicas: 1
  selector:
    matchLabels:
      app: cost-exporter
  template:
    metadata:
      labels:
        app: cost-exporter
    spec:
      serviceAccountName: cost-exporter
      containers:
      - name: cost-exporter
        image: python:3.9-slim
        command:
        - /bin/bash
        - -c
        - |
          pip install boto3 prometheus-client
          python /scripts/exporter.py
        env:
        - name: CLUSTER_NAME
          value: '<CLUSTER_NAME>'
        - name: AWS_REGION
          value: '<REGION>'
        ports:
        - containerPort: 8080
          name: metrics
        volumeMounts:
        - name: scripts
          mountPath: /scripts
      volumes:
      - name: scripts
        configMap:
          name: cost-exporter-script
EOF
kubectl apply -f cost-exporter-deployment.yaml`}
      />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.10: Create Cost Exporter Service" icon="fas fa-plug" iconColor="text-teal-500">
      <CodeBlock
        title="cost-exporter-svc.yaml"
        code={`cat > cost-exporter-svc.yaml << 'EOF'
apiVersion: v1
kind: Service
metadata:
  name: cost-exporter
  namespace: default
  labels:
    app: cost-exporter
spec:
  type: ClusterIP
  ports:
  - name: metrics
    port: 8080
    targetPort: 8080
  selector:
    app: cost-exporter
EOF
kubectl apply -f cost-exporter-svc.yaml
kubectl get pods -l app=cost-exporter
kubectl logs -l app=cost-exporter --tail=50`}
      />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.11: Install OpenCost" icon="fas fa-coins" iconColor="text-yellow-500">
      <VariableNote variables={['<MONITORING_INSTANCE_PRIVATE_IP>', '<REGION>']} />
      <CodeBlock
        title="opencost-values.yaml"
        code={`cat > opencost-values.yaml << 'EOF'
opencost:
  logLevel: info
  prometheus:
    internal:
      enabled: false
    external:
      enabled: true
      url: http://<MONITORING_INSTANCE_PRIVATE_IP>:9090
  ui:
    enabled: true
  cloudProvider:
    aws:
      enabled: true
      region: <REGION>
  exporter:
    enabled: true

serviceAccount:
  create: true
  name: opencost

resources:
  requests:
    cpu: 200m
    memory: 256Mi
  limits:
    cpu: 1000m
    memory: 1Gi
EOF
helm install opencost opencost/opencost --namespace opencost --create-namespace --values opencost-values.yaml --wait
kubectl get pods -n opencost
kubectl logs -n opencost -l app.kubernetes.io/name=opencost --tail=50`}
      />
    </CollapsibleSection>
  </div>
);

// Part 3: Security Tools Setup
const Part3Tab: React.FC = () => (
  <div className="space-y-6">
    <h3 className="text-xl font-bold text-gray-800 mb-4">Part 3: Security Tools Setup</h3>

    <CollapsibleSection title="Step 3.1: Enable EBS CSI Driver for Storage" icon="fas fa-hdd" defaultOpen>
      <VariableNote variables={['<CLUSTER_NAME>', '<REGION>']} />
      <CodeBlock code={`eksctl utils associate-iam-oidc-provider --cluster <CLUSTER_NAME> --region <REGION> --approve
eksctl create iamserviceaccount --name ebs-csi-controller-sa --namespace kube-system --cluster <CLUSTER_NAME> --attach-policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy --approve --role-only
eksctl create addon --name aws-ebs-csi-driver --cluster <CLUSTER_NAME> --force
cat <<EOF | kubectl apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: gp3
  annotations:
    storageclass.kubernetes.io/is-default-class: 'true'
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
reclaimPolicy: Delete
volumeBindingMode: WaitForFirstConsumer
EOF`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.2: Install Trivy" icon="fas fa-bug" iconColor="text-green-500">
      <CodeBlock code={`helm repo add aqua https://aquasecurity.github.io/helm-charts/
helm repo update
helm upgrade --install trivy-operator aqua/trivy-operator --namespace trivy-system --create-namespace --set trivy.ignoreUnfixed=true --set operator.scanJobTimeout=15m --set operator.scanJobsConcurrentLimit=3 --wait
kubectl get pods -n trivy-system`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.3: Install Kubescape" icon="fas fa-clipboard-check" iconColor="text-purple-500">
      <VariableNote variables={['<CLUSTER_NAME>']} />
      <CodeBlock code={`helm repo add kubescape https://kubescape.github.io/helm-charts/
helm repo update
helm upgrade --install kubescape kubescape/kubescape-operator -n kubescape --create-namespace --set clusterName=<CLUSTER_NAME> --set capabilities.configurationScan=enable --set capabilities.continuousScan=enable --set capabilities.vulnerabilityScan=enable --set storage.persistence.enable=true --set storage.persistence.size=2Gi --wait
kubectl get pods -n kubescape`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.4: Verify Security Tools Data" icon="fas fa-check-double" iconColor="text-blue-500">
      <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-amber-800">
          <i className="fas fa-clock mr-2"></i>
          Wait 5 minutes after installation before running these commands
        </p>
      </div>
      <CodeBlock code={`sleep 300
kubectl get vulnerabilityreports -A
kubectl get configurationscansummaries -A
kubectl get vulnerabilitymanifestsummaries -A`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.5: Generate Kubeconfig for Application" icon="fas fa-key" iconColor="text-yellow-500">
      <div className="bg-red-50 border border-red-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-red-800">
          <i className="fas fa-exclamation-triangle mr-2"></i>
          Run <strong>ONLY after Step 3.4</strong> shows data
        </p>
      </div>
      <CodeBlock code={`cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ServiceAccount
metadata:
  name: xamops-dashboard-sa
  namespace: kube-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: xamops-dashboard-role
rules:
- apiGroups: ['']
  resources: ['pods', 'nodes', 'namespaces', 'services', 'deployments', 'daemonsets', 'replicationcontrollers', 'statefulsets', 'jobs', 'cronjobs']
  verbs: ['get', 'list', 'watch']
- apiGroups: ['spdx.softwarecomposition.kubescape.io', 'kubescape.io']
  resources: ['configurationscansummaries', 'vulnerabilitymanifestsummaries']
  verbs: ['get', 'list', 'watch']
- apiGroups: ['aquasecurity.github.io']
  resources: ['vulnerabilityreports', 'configauditreports', 'exposedsecretreports', 'rbacassessmentreports']
  verbs: ['get', 'list', 'watch']
- apiGroups: ['apiextensions.k8s.io']
  resources: ['customresourcedefinitions']
  verbs: ['get', 'list']
- apiGroups: ['karpenter.sh', 'karpenter.k8s.aws']
  resources: ['nodepools', 'nodeclaims', 'ec2nodeclasses', 'machines', 'provisioners']
  verbs: ['get', 'list', 'watch']
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: xamops-dashboard-binding
subjects:
- kind: ServiceAccount
  name: xamops-dashboard-sa
  namespace: kube-system
roleRef:
  kind: ClusterRole
  name: xamops-dashboard-role
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: v1
kind: Secret
metadata:
  name: xamops-dashboard-token
  namespace: kube-system
  annotations:
    kubernetes.io/service-account.name: xamops-dashboard-sa
type: kubernetes.io/service-account-token
EOF`} />

      <p className="text-sm text-gray-600 mt-4 mb-2">Generate the kubeconfig file:</p>
      <CodeBlock code={`TOKEN=$(kubectl get secret xamops-dashboard-token -n kube-system -o jsonpath='{.data.token}' | base64 --decode)
CA_CERT=$(kubectl get secret xamops-dashboard-token -n kube-system -o jsonpath='{.data.ca\\.crt}')
SERVER=$(kubectl config view --minify --output jsonpath='{.clusters[0].cluster.server}')
cat <<EOF > xamops-kubeconfig.yaml
apiVersion: v1
kind: Config
clusters:
- cluster:
    certificate-authority-data: $CA_CERT
    server: $SERVER
  name: cluster-data
contexts:
- context:
    cluster: cluster-data
    user: xamops-user
  name: xamops-context
current-context: xamops-context
users:
- name: xamops-user
  user:
    token: $TOKEN
EOF
cat xamops-kubeconfig.yaml`} />
    </CollapsibleSection>
  </div>
);

// Part 4: Verification
const Part4Tab: React.FC = () => (
  <div className="space-y-6">
    <h3 className="text-xl font-bold text-gray-800 mb-4">Part 4: Verification</h3>

    <CollapsibleSection title="Test 1: Check All Pods Running" icon="fas fa-heartbeat" iconColor="text-green-500" defaultOpen>
      <CodeBlock code={`kubectl get pods -n default -l app.kubernetes.io/name=opentelemetry-collector
kubectl get pods -n kube-system -l app.kubernetes.io/name=kube-state-metrics
kubectl get pods -n falco
kubectl get pods -n opencost
kubectl get pods -n trivy-system
kubectl get pods -n kubescape
kubectl get pods -l app=cost-exporter`} />
    </CollapsibleSection>

    <CollapsibleSection title="Test 2: Verify Metrics in Prometheus" icon="fas fa-chart-line" iconColor="text-orange-500">
      <p className="text-sm text-gray-600 mb-4">Run these on the monitoring instance:</p>

      <CodeBlock
        title="Container CPU metrics (for OpenCost efficiency)"
        code={`curl -s 'http://localhost:9090/api/v1/query?query=container_cpu_usage_seconds_total' | jq '.data.result | length'
# Expected: > 0`}
      />

      <CodeBlock
        title="Container memory metrics (for OpenCost efficiency)"
        code={`curl -s 'http://localhost:9090/api/v1/query?query=container_memory_working_set_bytes' | jq '.data.result | length'
# Expected: > 0`}
      />

      <CodeBlock
        title="Kube-state-metrics"
        code={`curl -s 'http://localhost:9090/api/v1/query?query=kube_pod_info' | jq '.data.result | length'
# Expected: > 0`}
      />

      <CodeBlock
        title="Node metrics"
        code={`curl -s 'http://localhost:9090/api/v1/query?query=node_cpu_seconds_total' | jq '.data.result | length'
# Expected: > 0`}
      />

      <CodeBlock
        title="OpenCost metrics"
        code={`curl -s 'http://localhost:9090/api/v1/query?query=opencost_build_info' | jq '.data.result | length'
# Expected: >= 0 (may be empty initially)`}
      />

      <CodeBlock
        title="Cost exporter metrics"
        code={`curl -s 'http://localhost:9090/api/v1/query?query=xamops_eks_xamops_eks_cluster_total_daily_cost' | jq '.data.result | length'
# Expected: > 0`}
      />

      <CodeBlock
        title="Falco metrics"
        code={`curl -s 'http://localhost:9090/api/v1/query?query=xamops_eks_falcosecurity_falcosidekick_falco_events_total' | jq '.data.result | length'
# Expected: >= 0`}
      />
    </CollapsibleSection>

    <CollapsibleSection title="Test 3: OpenCost API" icon="fas fa-coins" iconColor="text-yellow-500">
      <CodeBlock code={`kubectl port-forward -n opencost svc/opencost 9003:9003 &
sleep 5
curl 'http://localhost:9003/allocation/compute?window=1h&aggregate=namespace' | jq
pkill -f 'port-forward.*opencost'`} />
    </CollapsibleSection>

    <CollapsibleSection title="Test 4: Trivy Vulnerability Reports" icon="fas fa-bug" iconColor="text-red-500">
      <CodeBlock code={`kubectl get vulnerabilityreports -A | head -10`} />
    </CollapsibleSection>

    <CollapsibleSection title="Test 5: Kubescape Compliance" icon="fas fa-clipboard-check" iconColor="text-purple-500">
      <CodeBlock code={`kubectl get configurationscansummaries -A | head -10
kubectl get vulnerabilitymanifestsummaries -A | head -10`} />
    </CollapsibleSection>

    <div className="bg-green-50 border border-green-200 rounded-lg p-6 mt-6">
      <h4 className="font-bold text-green-800 mb-4 flex items-center gap-2">
        <i className="fas fa-clipboard-check"></i>
        Completion Checklist
      </h4>
      <p className="text-sm text-green-700 mb-4">Before handing off to application team:</p>
      <ul className="space-y-2 text-sm text-green-700">
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> All pods in Running state</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> container_cpu_usage_seconds_total returns &gt; 0 in Prometheus</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> container_memory_working_set_bytes returns &gt; 0 in Prometheus</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> kube_pod_info returns &gt; 0 in Prometheus</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> OpenCost API returns cost data</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> Trivy vulnerability reports exist</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> Kubescape scan summaries exist</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> xamops-kubeconfig.yaml file generated</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> Cost exporter showing daily costs</li>
      </ul>
    </div>

    <div className="bg-blue-50 border border-blue-200 rounded-lg p-6">
      <h4 className="font-bold text-blue-800 mb-4 flex items-center gap-2">
        <i className="fas fa-server"></i>
        API Endpoints for Application
      </h4>
      <div className="space-y-3 text-sm text-blue-700">
        <p>
          <strong>Prometheus (from application):</strong><br />
          <code className="bg-blue-100 px-2 py-1 rounded">http://&lt;MONITORING_PUBLIC_IP&gt;:9090/api/v1/query</code>
        </p>
        <p>
          <strong>OpenCost (from within cluster):</strong><br />
          <code className="bg-blue-100 px-2 py-1 rounded">http://opencost.opencost.svc.cluster.local:9003/allocation/compute</code>
        </p>
        <p>
          <strong>Kubernetes API (using kubeconfig):</strong><br />
          Use the generated <code className="bg-blue-100 px-2 py-1 rounded">xamops-kubeconfig.yaml</code> file
        </p>
      </div>
    </div>
  </div>
);

// Part 5: Variables Summary
const Part5Tab: React.FC = () => (
  <div className="space-y-6">
    <h3 className="text-xl font-bold text-gray-800 mb-4">Part 5: Variables Summary</h3>

    <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
      <div className="bg-gray-50 px-4 py-3 border-b border-gray-200">
        <h4 className="font-semibold text-gray-800">All variables that MUST be changed:</h4>
      </div>
      <div className="p-4">
        <h5 className="font-semibold text-gray-700 mb-3 flex items-center gap-2">
          <i className="fas fa-server text-blue-500"></i>
          Monitoring Instance Files
        </h5>
        <ul className="list-disc list-inside space-y-2 text-gray-700 ml-4 mb-6">
          <li><code className="bg-gray-100 px-1 rounded">otel-gateway-config.yaml</code>: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span> (2 occurrences)</li>
        </ul>

        <h5 className="font-semibold text-gray-700 mb-3 flex items-center gap-2">
          <i className="fas fa-dharmachakra text-green-500"></i>
          Cluster Files
        </h5>
        <ul className="list-disc list-inside space-y-2 text-gray-700 ml-4">
          <li><code className="bg-gray-100 px-1 rounded">otel-agent-values.yaml</code>: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span>, <span className="text-amber-600 font-medium">&lt;MONITORING_INSTANCE_PRIVATE_IP&gt;</span></li>
          <li><code className="bg-gray-100 px-1 rounded">create-cost-exporter-role.sh</code>: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span>, <span className="text-amber-600 font-medium">&lt;REGION&gt;</span></li>
          <li><code className="bg-gray-100 px-1 rounded">cost-exporter-sa.yaml</code>: <span className="text-amber-600 font-medium">&lt;AWS_ACCOUNT_ID&gt;</span></li>
          <li><code className="bg-gray-100 px-1 rounded">cost-exporter-script.yaml</code>: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span>, <span className="text-amber-600 font-medium">&lt;REGION&gt;</span></li>
          <li><code className="bg-gray-100 px-1 rounded">cost-exporter-deployment.yaml</code>: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span>, <span className="text-amber-600 font-medium">&lt;REGION&gt;</span></li>
          <li><code className="bg-gray-100 px-1 rounded">opencost-values.yaml</code>: <span className="text-amber-600 font-medium">&lt;MONITORING_INSTANCE_PRIVATE_IP&gt;</span>, <span className="text-amber-600 font-medium">&lt;REGION&gt;</span></li>
          <li>Falco helm install: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span></li>
          <li>Kubescape helm install: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span></li>
          <li>EBS CSI Driver: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span>, <span className="text-amber-600 font-medium">&lt;REGION&gt;</span></li>
        </ul>
      </div>
    </div>

    <div className="bg-amber-50 border border-amber-200 rounded-lg p-6">
      <h4 className="font-bold text-amber-800 mb-4 flex items-center gap-2">
        <i className="fas fa-exclamation-triangle"></i>
        Important Notes
      </h4>
      <ol className="list-decimal list-inside space-y-2 text-amber-800 text-sm">
        <li><strong>cAdvisor job in Prometheus strips xamops_eks_ prefix</strong> - Required for OpenCost CPU/memory</li>
        <li><strong>RBAC permissions for otel-collector-stats-reader</strong> - Required for container metrics</li>
        <li><strong>Cost metrics have double prefix xamops_eks_xamops_eks_</strong> - This is expected</li>
        <li><strong>Wait 5 minutes after Trivy/Kubescape install</strong> before generating kubeconfig</li>
        <li><strong>Use PRIVATE IP</strong> for monitoring instance in all cluster configs</li>
      </ol>
    </div>

    <div className="bg-red-50 border border-red-200 rounded-lg p-6">
      <h4 className="font-bold text-red-800 mb-4 flex items-center gap-2">
        <i className="fas fa-wrench"></i>
        Troubleshooting
      </h4>

      <div className="space-y-4">
        <div>
          <h5 className="font-semibold text-red-700">CPU/Memory showing 0%</h5>
          <CodeBlock code={`# Check container metrics exist
curl -s 'http://localhost:9090/api/v1/query?query=container_cpu_usage_seconds_total' | jq '.data.result | length'
# If returns 0, check cAdvisor job in Prometheus and RBAC permissions`} />
        </div>

        <div>
          <h5 className="font-semibold text-red-700">OpenCost showing no data</h5>
          <CodeBlock code={`# Check required metrics
curl -s 'http://localhost:9090/api/v1/query?query=kube_node_info' | jq '.data.result | length'
curl -s 'http://localhost:9090/api/v1/query?query=kube_pod_info' | jq '.data.result | length'
# Wait 10 minutes after installation`} />
        </div>

        <div>
          <h5 className="font-semibold text-red-700">Cost Exporter not showing data</h5>
          <CodeBlock code={`kubectl logs -l app=cost-exporter
# Check IAM role is correctly attached
kubectl describe sa cost-exporter`} />
        </div>

        <div>
          <h5 className="font-semibold text-red-700">Trivy/Kubescape no reports</h5>
          <CodeBlock code={`kubectl get pods -n trivy-system
kubectl get pods -n kubescape
# Wait 5-10 minutes for initial scans`} />
        </div>
      </div>
    </div>
  </div>
);

export default EksIntegrationModal;
