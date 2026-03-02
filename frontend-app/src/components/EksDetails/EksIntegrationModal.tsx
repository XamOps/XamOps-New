/**
 * EKS Integration Guide Modal
 *
 * Complete EKS Monitoring Setup Guide with copy buttons
 * OTel + Falco + Kube-State-Metrics + cAdvisor + cost exporter + OpenCost + Kubescape + Trivy
 */

import React, { useState, useEffect } from 'react';

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
  const [activeTab, setActiveTab] = useState<'overview' | 'part1' | 'part2' | 'part3' | 'part4' | 'part5' | 'autoscaler'>('overview');

  // Prevent main content scroll when modal is open
  useEffect(() => {
    const mainContent = document.getElementById('main-content');
    if (isOpen) {
      if (mainContent) mainContent.style.overflow = 'hidden';
    } else {
      if (mainContent) mainContent.style.overflow = '';
    }
    return () => {
      if (mainContent) mainContent.style.overflow = '';
    };
  }, [isOpen]);

  if (!isOpen) return null;

  const tabs = [
    { id: 'overview', label: 'Overview', icon: 'fas fa-info-circle' },
    { id: 'part1', label: 'Part 1: Monitoring Instance', icon: 'fas fa-server' },
    { id: 'part2', label: 'Part 2: Cluster Setup', icon: 'fas fa-dharmachakra' },
    { id: 'part3', label: 'Part 3: Security Tools', icon: 'fas fa-shield-alt' },
    { id: 'part4', label: 'Part 4: Verification', icon: 'fas fa-check-circle' },
    { id: 'part5', label: 'Part 5: Variables Summary', icon: 'fas fa-list' },
    // { id: 'autoscaler', label: 'Autoscaler Setup', icon: 'fas fa-robot' }, // XAMOPS-AUTOSCALER: Hidden until ready
  ];

  // Handle wheel event to prevent scroll propagation
  const handleWheel = (e: React.WheelEvent<HTMLDivElement>) => {
    const target = e.currentTarget;
    const { scrollTop, scrollHeight, clientHeight } = target;
    const isAtTop = scrollTop === 0 && e.deltaY < 0;
    const isAtBottom = scrollTop + clientHeight >= scrollHeight && e.deltaY > 0;

    // Only stop propagation if we're not at the boundaries or if we're scrolling within bounds
    if (!isAtTop && !isAtBottom) {
      e.stopPropagation();
    } else {
      // Prevent the scroll from propagating to parent when at boundaries
      e.preventDefault();
      e.stopPropagation();
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      onWheel={(e) => e.stopPropagation()}
    >
      <div onClick={onClose} className="fixed inset-0 bg-black/50 backdrop-blur-sm" />

      <div
        className="bg-white rounded-2xl w-full max-w-6xl mx-auto max-h-[95vh] overflow-hidden z-50 shadow-2xl relative flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header - Fixed */}
        <div className="flex-shrink-0 flex items-center justify-between p-6 border-b border-gray-200 bg-gradient-to-r from-slate-50 to-blue-50">
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

        {/* Tab Navigation - Fixed/Sticky */}
        <div className="flex-shrink-0 flex border-b border-gray-200 bg-gray-50 overflow-x-auto sticky top-0 z-10">
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

        {/* Content - Scrollable with contained overscroll */}
        <div
          className="flex-1 overflow-y-auto p-6"
          style={{ overscrollBehavior: 'contain' }}
          onWheel={handleWheel}
        >
          {activeTab === 'overview' && <OverviewTab />}
          {activeTab === 'part1' && <Part1Tab />}
          {activeTab === 'part2' && <Part2Tab />}
          {activeTab === 'part3' && <Part3Tab />}
          {activeTab === 'part4' && <Part4Tab />}
          {activeTab === 'part5' && <Part5Tab />}
          {/* XAMOPS-AUTOSCALER: Hidden until ready */}
          {/* {activeTab === 'autoscaler' && <AutoscalerTab />} */}
        </div>

        {/* Footer - Fixed */}
        <div className="flex-shrink-0 p-4 border-t border-gray-200 bg-gray-50 flex justify-end">
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
        <li>EC2 instance for monitoring (t3.large, 50GB storage, <strong>same VPC and region as your EKS cluster</strong>)</li>
      </ul>
    </div>

    <div className="bg-red-50 border border-red-300 rounded-lg p-6">
      <h4 className="font-bold text-red-800 mb-4 flex items-center gap-2">
        <i className="fas fa-network-wired text-red-600"></i>
        Networking Requirements — Complete Before Starting
      </h4>

      <div className="space-y-4">
        <div className="bg-white border border-red-200 rounded-lg p-4">
          <h5 className="font-semibold text-red-700 mb-2">
            <i className="fas fa-server mr-2"></i>Monitoring Instance Placement
          </h5>
          <ul className="text-sm text-gray-700 space-y-1 list-disc list-inside ml-2">
            <li><strong>Must be in the same VPC and same region as your EKS cluster</strong> — this ensures OTel metrics can reach Prometheus via private IP without any VPC peering or internet routing</li>
            <li>Launch in a <strong>public subnet</strong> with an <strong>Elastic IP (static)assigned</strong> — XamOps backend needs a stable public IP to pull Prometheus metrics</li>
            <li>If cluster and monitoring instance are in <strong>different VPCs</strong>: set up VPC Peering and update route tables before proceeding</li>
            <li>If in <strong>different regions</strong>: AWS Transit Gateway is required — strongly recommended to deploy monitoring instance in the same region as the cluster to avoid this</li>
          </ul>
        </div>

        <div className="bg-white border border-red-200 rounded-lg p-4">
          <h5 className="font-semibold text-red-700 mb-3">
            <i className="fas fa-shield-alt mr-2"></i>Security Group Rules Required
          </h5>

          <p className="text-sm font-semibold text-gray-700 mb-2">Monitoring Instance SG — Inbound:</p>
          <div className="overflow-x-auto mb-4">
            <table className="w-full text-xs border border-gray-200 rounded">
              <thead className="bg-gray-100">
                <tr>
                  <th className="px-2 py-1 text-left">Port</th>
                  <th className="px-2 py-1 text-left">Source</th>
                  <th className="px-2 py-1 text-left">Purpose</th>
                </tr>
              </thead>
              <tbody className="text-gray-700">
                <tr className="border-t border-gray-200"><td className="px-2 py-1">4317</td><td className="px-2 py-1">VPC CIDR (e.g. 10.0.0.0/16)</td><td className="px-2 py-1">OTel gRPC from cluster nodes</td></tr>
                <tr className="border-t border-gray-200"><td className="px-2 py-1">4318</td><td className="px-2 py-1">VPC CIDR (e.g. 10.0.0.0/16)</td><td className="px-2 py-1">OTel HTTP from cluster nodes</td></tr>
                <tr className="border-t border-gray-200"><td className="px-2 py-1">9090</td><td className="px-2 py-1">XamOps server IP (provided by XamOps)</td><td className="px-2 py-1">Prometheus pull by XamOps backend</td></tr>
                <tr className="border-t border-gray-200"><td className="px-2 py-1">22</td><td className="px-2 py-1">Your admin IP only</td><td className="px-2 py-1">SSH access — never open to 0.0.0.0/0</td></tr>
              </tbody>
            </table>
          </div>

          <p className="text-sm font-semibold text-gray-700 mb-2">EKS Worker Node SG — Outbound (add these rules):</p>
          <div className="overflow-x-auto mb-4">
            <table className="w-full text-xs border border-gray-200 rounded">
              <thead className="bg-gray-100">
                <tr>
                  <th className="px-2 py-1 text-left">Port</th>
                  <th className="px-2 py-1 text-left">Destination</th>
                  <th className="px-2 py-1 text-left">Purpose</th>
                </tr>
              </thead>
              <tbody className="text-gray-700">
                <tr className="border-t border-gray-200"><td className="px-2 py-1">443</td><td className="px-2 py-1">0.0.0.0/0</td><td className="px-2 py-1">XamOps Agent → live.xamops.com (HTTPS)</td></tr>
                <tr className="border-t border-gray-200"><td className="px-2 py-1">4317</td><td className="px-2 py-1">Monitoring Instance SG</td><td className="px-2 py-1">OTel gRPC to monitoring instance</td></tr>
                <tr className="border-t border-gray-200"><td className="px-2 py-1">4318</td><td className="px-2 py-1">Monitoring Instance SG</td><td className="px-2 py-1">OTel HTTP to monitoring instance</td></tr>
              </tbody>
            </table>
          </div>

          <div className="bg-green-50 border border-green-200 rounded p-3 text-sm text-green-800">
            <i className="fas fa-lock mr-2"></i>
            <strong>Cluster inbound rules:</strong> No changes needed. XamOps Agent only makes outbound connections — your cluster receives zero inbound traffic from XamOps at any point.
          </div>
        </div>

        <div className="bg-white border border-red-200 rounded-lg p-4">
          <h5 className="font-semibold text-red-700 mb-2">
            <i className="fas fa-exchange-alt mr-2"></i>Two IPs to Note After Creating Monitoring Instance
          </h5>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
            <div className="bg-blue-50 border border-blue-200 rounded p-3">
              <p className="font-semibold text-blue-800">Private IP (e.g. 10.0.1.x)</p>
              <p className="text-blue-700 mt-1">Use in cluster configs for OTel DaemonSet and OpenCost — traffic stays within VPC</p>
            </div>
            <div className="bg-orange-50 border border-orange-200 rounded p-3">
              <p className="font-semibold text-orange-800">Public Elastic IP</p>
              <p className="text-orange-700 mt-1">Use as the <strong>Prometheus URL</strong> when registering cluster in XamOps dashboard — XamOps backend queries this from outside your VPC</p>
            </div>
          </div>
        </div>
      </div>
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
        <p className="text-sm text-gray-600">Security Tools - Trivy + Kubescape + XamOps Agent / Kubeconfig</p>
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

    <div className="bg-gray-50 border border-gray-200 rounded-lg px-4 py-3 mb-2">
      <p className="text-sm text-gray-700">
        <i className="fas fa-info-circle text-blue-500 mr-2"></i>
        <strong>Why Prometheus runs outside the cluster:</strong> Running Prometheus inside the cluster consumes node CPU and memory that belongs to your workloads — on large clusters this overhead is significant. More importantly, keeping the monitoring stack on a dedicated instance means a cluster crash, node pressure, or OOM event does not take your observability down with it; you always have visibility into what went wrong.
      </p>
    </div>

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
          <strong>Save the PRIVATE_IP</strong> — used in OTel DaemonSet and OpenCost configs (in-cluster traffic stays private).
        </p>
      </div>
      <div className="bg-orange-50 border border-orange-200 rounded-lg p-3 mt-2">
        <p className="text-sm text-orange-800">
          <i className="fas fa-exclamation-circle mr-2"></i>
          <strong>Assign an Elastic IP to this instance</strong> — the PUBLIC_IP from the script above may change on restart. Go to AWS Console → EC2 → Elastic IPs → Allocate → Associate to this instance. Use this Elastic IP as the <strong>Prometheus URL</strong> when registering your cluster in XamOps dashboard: <code className="bg-orange-100 px-1 rounded">http://&lt;ELASTIC_IP&gt;:9090</code>
        </p>
      </div>
      <div className="bg-red-50 border border-red-200 rounded-lg p-3 mt-2">
        <p className="text-sm text-red-800">
          <i className="fas fa-shield-alt mr-2"></i>
          <strong>Port 9090 Security:</strong> The script restricts port 9090 to your current IP. You also need to add the XamOps production server IP — contact XamOps support for this IP so they can whitelist it. Do not open port 9090 to <code>0.0.0.0/0</code> as this exposes all cluster metrics to the public internet.
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
helm upgrade --install kubescape kubescape/kubescape-operator \\
  -n kubescape --create-namespace \\
  --set clusterName=<CLUSTER_NAME> \\
  --set capabilities.configurationScan=enable \\
  --set capabilities.continuousScan=enable \\
  --set capabilities.vulnerabilityScan=enable \\
  --set storage.persistence.enable=true \\
  --set storage.persistence.size=2Gi \\
  --set nodeAgent.affinity=null \\
  --set nodeAgent.tolerations[0].operator=Exists \\
  --set nodeAgent.env[0].name=STARTUP_TIMEOUT \\
  --set nodeAgent.env[0].value="120s" \\
  --wait --timeout=10m

# Wait for storage to be ready before node-agent tries to connect
echo "Waiting for Kubescape storage to be ready..."
kubectl wait --for=condition=Ready pod \\
  -l app.kubernetes.io/component=storage \\
  -n kubescape \\
  --timeout=300s

# Restart node-agent so it reconnects cleanly to storage
kubectl rollout restart ds node-agent -n kubescape
kubectl rollout status ds node-agent -n kubescape --timeout=120s

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

    <CollapsibleSection title="Step 3.4.1: Test Falco Alerts (Demo)" icon="fas fa-exclamation-triangle" iconColor="text-red-500">
      <div className="bg-red-50 border border-red-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-red-800">
          <i className="fas fa-flask mr-2"></i>
          <strong>Demo Purpose Only:</strong> These commands trigger Falco security alerts to verify detection is working.
        </p>
      </div>

      <h5 className="font-semibold text-gray-700 mb-2">Test 1: Shell Spawned in Container (Most Common)</h5>
      <CodeBlock
        title="Trigger shell detection alert"
        code={`# Create a test pod and exec into it
kubectl run falco-test --image=busybox --restart=Never -- sleep 3600

# Wait for pod to be ready
kubectl wait --for=condition=Ready pod/falco-test --timeout=60s

# Execute shell - this triggers "Terminal shell in container" alert
kubectl exec -it falco-test -- /bin/sh -c "whoami && ls -la /etc"

# Clean up
kubectl delete pod falco-test --force --grace-period=0`}
      />

      <h5 className="font-semibold text-gray-700 mt-4 mb-2">Test 2: Read Sensitive Files</h5>
      <CodeBlock
        title="Trigger sensitive file read alert"
        code={`kubectl run falco-test2 --image=busybox --restart=Never -- sh -c "cat /etc/shadow; sleep 10"

# Wait and check logs
sleep 15
kubectl delete pod falco-test2 --force --grace-period=0`}
      />

      <h5 className="font-semibold text-gray-700 mt-4 mb-2">Test 3: Network Tools in Container</h5>
      <CodeBlock
        title="Trigger network tool alert"
        code={`kubectl run falco-test3 --image=nicolaka/netshoot --restart=Never -- sh -c "nmap --version; sleep 10"

# Clean up
sleep 15
kubectl delete pod falco-test3 --force --grace-period=0`}
      />

      <h5 className="font-semibold text-gray-700 mt-4 mb-2">Test 4: Privileged Container (Warning)</h5>
      <CodeBlock
        title="Trigger privileged container alert"
        code={`kubectl run falco-priv-test --image=busybox --restart=Never --overrides='{
  "spec": {
    "containers": [{
      "name": "falco-priv-test",
      "image": "busybox",
      "command": ["sleep", "30"],
      "securityContext": {"privileged": true}
    }]
  }
}'

# Clean up after 30 seconds
sleep 35
kubectl delete pod falco-priv-test --force --grace-period=0`}
      />

      <h5 className="font-semibold text-gray-700 mt-4 mb-2">Verify Falco Detected the Events</h5>
      <CodeBlock
        title="Check Falco logs and metrics"
        code={`# Check Falco detected the events
kubectl logs -n falco -l app.kubernetes.io/name=falco --tail=20

# Check Falcosidekick received events
kubectl logs -n falco -l app.kubernetes.io/name=falcosidekick --tail=20

# Check metrics endpoint (from within cluster)
kubectl run curl-test --image=curlimages/curl --restart=Never --rm -it -- \\
  curl -s http://falco-falcosidekick.falco.svc.cluster.local:2801/metrics | grep falco_events

# Verify in Prometheus (on monitoring instance)
curl -s 'http://localhost:9090/api/v1/query?query=falcosecurity_falcosidekick_falco_events_total' | jq`}
      />

      <div className="bg-green-50 border border-green-200 rounded-lg p-3 mt-4">
        <p className="text-sm text-green-800">
          <i className="fas fa-check-circle mr-2"></i>
          <strong>Expected Result:</strong> Falco should detect these events within seconds. Check the Falco tab in XamOps dashboard after running tests.
        </p>
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.5: Install XamOps Agent (Recommended for Private Clusters)" icon="fas fa-robot" iconColor="text-indigo-500">
      <div className="bg-indigo-50 border border-indigo-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-indigo-800">
          <i className="fas fa-info-circle mr-2"></i>
          <strong>XamOps Agent</strong> is the recommended approach for private clusters. It pushes data outbound, so no inbound cluster access is needed. The agent collects Trivy, Kubescape, OpenCost, Karpenter, and cluster metadata.
        </p>
      </div>

      <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 mb-4">
        <h5 className="font-semibold text-blue-800 mb-2 flex items-center gap-2">
          <i className="fas fa-docker"></i>
          Docker Image (Auto-pulled by Kubernetes)
        </h5>
        <p className="text-sm text-blue-700 mb-2">
          Image: <code className="bg-blue-100 px-2 py-1 rounded">docker.io/xamops/agent:latest</code>
        </p>
        <p className="text-xs text-blue-600">
          <i className="fas fa-info-circle mr-1"></i>
          <strong>Note:</strong> You do NOT need to run <code>docker pull</code> manually. When you run <code>kubectl apply</code>, Kubernetes automatically pulls the image from Docker Hub.
        </p>
      </div>

      <h5 className="font-semibold text-gray-700 mb-2">YAML-based Installation</h5>
      <VariableNote variables={['<CLUSTER_NAME>', '<CLOUD_ACCOUNT_ID>']} />

      <p className="text-sm text-gray-600 mb-2">Step 1: Create namespace and RBAC</p>
      <CodeBlock
        title="xamops-agent-rbac.yaml"
        code={`kubectl apply -f - << 'EOF'
# Namespace
apiVersion: v1
kind: Namespace
metadata:
  name: xamops-system
  labels:
    app.kubernetes.io/name: xamops-agent
---
# ServiceAccount
apiVersion: v1
kind: ServiceAccount
metadata:
  name: xamops-agent-sa
  namespace: xamops-system
---
# ClusterRole - READ-ONLY permissions
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: xamops-agent-role
rules:
  # Core K8s resources
  - apiGroups: [""]
    resources: [pods, nodes, namespaces, services, events]
    verbs: [get, list, watch]
  - apiGroups: [apps]
    resources: [deployments, daemonsets, statefulsets, replicasets]
    verbs: [get, list, watch]
  - apiGroups: [batch]
    resources: [jobs, cronjobs]
    verbs: [get, list, watch]
  # Trivy
  - apiGroups: [aquasecurity.github.io]
    resources: [vulnerabilityreports, configauditreports, exposedsecretreports]
    verbs: [get, list, watch]
  # Kubescape
  - apiGroups: [spdx.softwarecomposition.kubescape.io]
    resources: [configurationscansummaries, vulnerabilitymanifestsummaries, workloadconfigurationscans]
    verbs: [get, list, watch]
  - apiGroups: [kubescape.io]
    resources: [workloadconfigurationscans, workloadconfigurationscansummaries]
    verbs: [get, list, watch]
  # Karpenter
  - apiGroups: [karpenter.sh]
    resources: [nodepools, nodeclaims]
    verbs: [get, list, watch]
  - apiGroups: [karpenter.k8s.aws]
    resources: [ec2nodeclasses]
    verbs: [get, list, watch]
  # CRD Discovery
  - apiGroups: [apiextensions.k8s.io]
    resources: [customresourcedefinitions]
    verbs: [get, list]
  # Metrics
  - apiGroups: [metrics.k8s.io]
    resources: [nodes, pods]
    verbs: [get, list]
---
# ClusterRoleBinding
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: xamops-agent-binding
subjects:
  - kind: ServiceAccount
    name: xamops-agent-sa
    namespace: xamops-system
roleRef:
  kind: ClusterRole
  name: xamops-agent-role
  apiGroup: rbac.authorization.k8s.io
EOF`}
      />

      <p className="text-sm text-gray-600 mt-4 mb-2">Step 2: Create ConfigMap and Secret</p>
      <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mb-3">
        <p className="text-sm text-amber-800">
          <i className="fas fa-edit mr-2"></i>
          <strong>Replace these 2 values:</strong>
        </p>
        <ul className="text-sm text-amber-700 mt-1 ml-4 list-disc">
          <li><code>&lt;CLUSTER_NAME&gt;</code> → Your EKS cluster name</li>
          <li><code>&lt;CLOUD_ACCOUNT_ID&gt;</code> → Your AWS account ID (12 digits)</li>
        </ul>
      </div>
      <CodeBlock
        title="xamops-agent-config.yaml"
        code={`kubectl apply -f - << 'EOF'
# ConfigMap
apiVersion: v1
kind: ConfigMap
metadata:
  name: xamops-agent-config
  namespace: xamops-system
data:
  COLLECT_INTERVAL: "300"
  PUSH_INTERVAL: "300"
  HEARTBEAT_INTERVAL: "60"
  COLLECTOR_TRIVY: "true"
  COLLECTOR_KUBESCAPE: "true"
  COLLECTOR_OPENCOST: "true"
  COLLECTOR_KARPENTER: "true"
  COLLECTOR_METRICS: "true"
  OPENCOST_SERVICE_URL: "http://opencost.opencost.svc.cluster.local:9003"
  LOG_LEVEL: "info"
---
# Secret - CHANGE CLUSTER_NAME and CLOUD_ACCOUNT_ID below
apiVersion: v1
kind: Secret
metadata:
  name: xamops-agent-credentials
  namespace: xamops-system
type: Opaque
stringData:
  # DO NOT CHANGE this API key (pre-configured for all clusters):
  XAMOPS_API_KEY: "xamops_SecureAgentKey2024XamOps"

  # CHANGE these values to match your environment and cluster:
  XAMOPS_SERVER_URL: "<XAMOPS_SERVER_URL>"   # See environment URLs below
  CLUSTER_NAME: "<CLUSTER_NAME>"
  CLOUD_ACCOUNT_ID: "<CLOUD_ACCOUNT_ID>"
EOF`}
      />

      <p className="text-sm text-gray-600 mt-4 mb-2">Step 3: Deploy the Agent</p>
      <CodeBlock
        title="xamops-agent-deployment.yaml"
        code={`kubectl apply -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: xamops-agent
  namespace: xamops-system
  labels:
    app: xamops-agent
spec:
  replicas: 1
  selector:
    matchLabels:
      app: xamops-agent
  template:
    metadata:
      labels:
        app: xamops-agent
    spec:
      serviceAccountName: xamops-agent-sa
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
      containers:
        - name: agent
          image: xamops/agent:latest
          imagePullPolicy: Always
          envFrom:
            - configMapRef:
                name: xamops-agent-config
            - secretRef:
                name: xamops-agent-credentials
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
          ports:
            - containerPort: 8080
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 30
---
apiVersion: v1
kind: Service
metadata:
  name: xamops-agent
  namespace: xamops-system
spec:
  type: ClusterIP
  ports:
    - port: 8080
      targetPort: 8080
  selector:
    app: xamops-agent
EOF`}
      />

      <p className="text-sm text-gray-600 mt-4 mb-2">Step 4: Verify installation</p>
      <CodeBlock code={`# Check pod is running
kubectl get pods -n xamops-system

# Check logs for successful data push
kubectl logs -n xamops-system -l app=xamops-agent --tail=50

# Look for "Data pushed successfully" messages
kubectl logs -n xamops-system -l app=xamops-agent | grep -i "push\\|success\\|sent"`} />

      <div className="bg-green-50 border border-green-200 rounded-lg p-4 mt-4">
        <h5 className="font-semibold text-green-800 mb-2">What the Agent Collects:</h5>
        <ul className="text-sm text-green-700 space-y-1">
          <li><i className="fas fa-bug mr-2"></i><strong>Trivy:</strong> VulnerabilityReports (security vulnerabilities)</li>
          <li><i className="fas fa-clipboard-check mr-2"></i><strong>Kubescape:</strong> ConfigurationScanSummaries (compliance)</li>
          <li><i className="fas fa-coins mr-2"></i><strong>OpenCost:</strong> Allocation data (cost per namespace)</li>
          <li><i className="fas fa-expand-arrows-alt mr-2"></i><strong>Karpenter:</strong> NodePools, NodeClaims, EC2NodeClasses</li>
          <li><i className="fas fa-server mr-2"></i><strong>Metadata:</strong> Nodes, namespaces, pods summary</li>
        </ul>
      </div>

      <div className="bg-gray-50 border border-gray-200 rounded-lg p-4 mt-4">
        <h5 className="font-semibold text-gray-800 mb-2">How It Works:</h5>
        <div className="text-sm text-gray-700 space-y-2">
          <p>1. Agent runs in your cluster as a Deployment (1 replica)</p>
          <p>2. Every 5 minutes, it reads Trivy CRDs, Kubescape CRDs, and calls OpenCost API</p>
          <p>3. Agent POSTs data to <code className="bg-gray-100 px-1 rounded">&lt;XAMOPS_SERVER_URL&gt;/api/v1/agent/ingest</code></p>
          <p>4. XamOps backend identifies your cluster using CLUSTER_NAME + CLOUD_ACCOUNT_ID</p>
          <p>5. Data appears in XamOps Dashboard (Security, Compliance, Cost tabs)</p>
        </div>
      </div>

      <div className="bg-indigo-50 border border-indigo-200 rounded-lg p-4 mt-4">
        <h5 className="font-semibold text-indigo-800 mb-2">Environment URLs:</h5>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left border-b border-indigo-200">
              <th className="py-1">Environment</th>
              <th className="py-1">XAMOPS_SERVER_URL</th>
            </tr>
          </thead>
          <tbody className="text-indigo-700">
            <tr><td className="py-1">Production</td><td><code className="bg-indigo-100 px-1 rounded">https://live.xamops.com</code></td></tr>
          </tbody>
        </table>
      </div>

      <div className="bg-amber-50 border border-amber-200 rounded-lg p-4 mt-4">
        <h5 className="font-semibold text-amber-800 mb-2">Variables Summary:</h5>
        <div className="text-sm text-amber-700 space-y-2">
          <p><strong>Client must change (3 values):</strong></p>
          <p className="ml-4"><code className="bg-amber-100 px-1 rounded">XAMOPS_SERVER_URL</code> → Select from environment table above</p>
          <p className="ml-4"><code className="bg-amber-100 px-1 rounded">CLUSTER_NAME</code> → Your EKS cluster name (e.g., my-production-cluster)</p>
          <p className="ml-4"><code className="bg-amber-100 px-1 rounded">CLOUD_ACCOUNT_ID</code> → Your AWS account ID (e.g., 123456789012)</p>
          <p className="mt-2"><strong>Pre-configured (do NOT change):</strong></p>
          <p className="ml-4"><code className="bg-amber-100 px-1 rounded">XAMOPS_API_KEY</code> → xamops_SecureAgentKey2024XamOps (same for all clusters)</p>
        </div>
      </div>
    </CollapsibleSection>

    {/* XAMOPS-AUTOSCALER: Hidden until ready — uncomment when autoscaler is fully built */}
    {(false as boolean) && (
    <CollapsibleSection title="Step 3.5.1: Install XamOps Autoscaler (Optional — for automated cost savings)" icon="fas fa-robot" iconColor="text-purple-500">
      <div className="bg-purple-50 border border-purple-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-purple-800">
          <i className="fas fa-info-circle mr-2"></i>
          <strong>XamOps Autoscaler</strong> is a separate, optional agent that enables automated cluster cost optimization.
          It runs alongside <code>xamops-agent</code> in the same namespace and requires the base agent to already be running.
          Install this only after Step 3.5 is verified working.
        </p>
      </div>

      <div className="bg-red-50 border border-red-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-red-800">
          <i className="fas fa-shield-alt mr-2"></i>
          <strong>Safety first:</strong> The autoscaler starts in <code>DRY_RUN=true</code> mode — it logs all actions but makes
          zero changes to your cluster. Enable features individually from the XamOps Autoscaling tab after verifying data is flowing.
        </p>
      </div>

      <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 mb-4">
        <h5 className="font-semibold text-blue-800 mb-2 flex items-center gap-2">
          <i className="fas fa-docker"></i>
          Docker Image
        </h5>
        <p className="text-sm text-blue-700 mb-2">
          Image: <code className="bg-blue-100 px-2 py-1 rounded">docker.io/xamops/autoscaler:latest</code>
        </p>
        <p className="text-xs text-blue-600">
          <i className="fas fa-info-circle mr-1"></i>
          Kubernetes pulls this automatically on <code>kubectl apply</code> — no manual docker pull needed.
        </p>
      </div>

      <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-amber-800">
          <i className="fas fa-edit mr-2"></i>
          <strong>Replace these 2 values (same as xamops-agent):</strong>
        </p>
        <ul className="text-sm text-amber-700 mt-1 ml-4 list-disc">
          <li><code>&lt;CLUSTER_NAME&gt;</code> → Your EKS cluster name</li>
          <li><code>&lt;CLOUD_ACCOUNT_ID&gt;</code> → Your AWS account ID (12 digits)</li>
        </ul>
      </div>

      <p className="text-sm text-gray-600 mb-2">Step 1: Create RBAC (write permissions for autoscaling actions)</p>
      <CodeBlock
        title="xamops-autoscaler-rbac.yaml"
        code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: xamops-autoscaler
  namespace: xamops-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: xamops-autoscaler
rules:
  # Read access — needed for safe decisions
  - apiGroups: [""]
    resources: [nodes, pods, namespaces, services, persistentvolumeclaims]
    verbs: [get, list, watch]
  - apiGroups: [apps]
    resources: [deployments, replicasets, statefulsets, daemonsets]
    verbs: [get, list, watch, patch, update]
  - apiGroups: [batch]
    resources: [jobs, cronjobs]
    verbs: [get, list, watch]
  - apiGroups: [policy]
    resources: [poddisruptionbudgets]
    verbs: [get, list, watch]
  - apiGroups: [metrics.k8s.io]
    resources: [nodes, pods]
    verbs: [get, list]
  # Write access — autoscaling actions
  - apiGroups: [""]
    resources: [nodes]
    verbs: [patch, update]
  - apiGroups: [""]
    resources: [pods/eviction]
    verbs: [create]
  - apiGroups: [""]
    resources: [pods]
    verbs: [delete]
  - apiGroups: [""]
    resources: [events]
    verbs: [create, patch, update]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: xamops-autoscaler
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: xamops-autoscaler
subjects:
  - kind: ServiceAccount
    name: xamops-autoscaler
    namespace: xamops-system
EOF`}
      />

      <p className="text-sm text-gray-600 mt-4 mb-2">Step 2: Create ConfigMap and Secret</p>
      <CodeBlock
        title="xamops-autoscaler-config.yaml"
        code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: xamops-autoscaler-config
  namespace: xamops-system
data:
  # Safety: dry-run logs everything but makes no changes
  DRY_RUN: "true"
  # Features (enable individually from XamOps UI after verifying data)
  ENABLE_EVICTOR: "false"
  ENABLE_RIGHTSIZER: "false"
  ENABLE_REBALANCER: "false"
  # Intervals (seconds)
  POLICY_POLL_INTERVAL: "60"
  METRICS_PUSH_INTERVAL: "120"
  HEARTBEAT_INTERVAL: "60"
  DRAIN_TIMEOUT_SECONDS: "1200"
  PROMETHEUS_URL: "http://prometheus-server.monitoring.svc.cluster.local:9090"
---
apiVersion: v1
kind: Secret
metadata:
  name: xamops-autoscaler-secret
  namespace: xamops-system
type: Opaque
stringData:
  # Same API key as xamops-agent — do NOT change:
  XAMOPS_API_KEY: "xamops_SecureAgentKey2024XamOps"
  # Change these 3 values:
  XAMOPS_SERVER_URL: "<XAMOPS_SERVER_URL>"
  CLUSTER_NAME: "<CLUSTER_NAME>"
  CLOUD_ACCOUNT_ID: "<CLOUD_ACCOUNT_ID>"
EOF`}
      />

      <p className="text-sm text-gray-600 mt-4 mb-2">Step 3: Deploy the Autoscaler</p>
      <CodeBlock
        title="xamops-autoscaler-deployment.yaml"
        code={`kubectl apply -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: xamops-autoscaler
  namespace: xamops-system
  labels:
    app: xamops-autoscaler
spec:
  replicas: 1
  selector:
    matchLabels:
      app: xamops-autoscaler
  template:
    metadata:
      labels:
        app: xamops-autoscaler
    spec:
      serviceAccountName: xamops-autoscaler
      securityContext:
        runAsNonRoot: true
        runAsUser: 65534
      containers:
        - name: autoscaler
          image: xamops/autoscaler:latest
          imagePullPolicy: Always
          ports:
            - containerPort: 8081
          envFrom:
            - configMapRef:
                name: xamops-autoscaler-config
            - secretRef:
                name: xamops-autoscaler-secret
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 500m
              memory: 256Mi
          livenessProbe:
            httpGet:
              path: /healthz
              port: 8081
            initialDelaySeconds: 15
            periodSeconds: 30
EOF`}
      />

      <p className="text-sm text-gray-600 mt-4 mb-2">Step 4: Verify both agents are running</p>
      <CodeBlock code={`# Both agents should show Running status
kubectl get pods -n xamops-system

# Check autoscaler logs — look for "Policy fetched" or "Heartbeat sent"
kubectl logs -n xamops-system -l app=xamops-autoscaler --tail=50

# Expected output:
# {"level":"info","msg":"Policy fetched from backend","mode":"disabled"}
# {"level":"info","msg":"Heartbeat sent","cluster":"your-cluster"}`} />

      <p className="text-sm text-gray-600 mt-4 mb-2">Step 5: Enable autoscaling in XamOps UI</p>
      <div className="bg-green-50 border border-green-200 rounded-lg p-4 mt-2">
        <ol className="text-sm text-green-800 space-y-1 list-decimal ml-4">
          <li>Go to <strong>EKS Details → Autoscaling tab</strong> for your cluster</li>
          <li>Verify the agent shows as <span className="font-mono bg-green-100 px-1 rounded">active</span> in the last-seen timestamp</li>
          <li>Click the <strong>"Enable Autoscaling for this Cluster"</strong> button that appears on the Autoscaling tab</li>
          <li>Enable individual features: <strong>Rightsizer → Evictor → Rebalancer</strong> (in that order)</li>
          <li>When ready to apply real changes: set <code>DRY_RUN=false</code> via:<br/>
            <code className="bg-green-100 px-2 py-0.5 rounded text-xs">kubectl set env deployment/xamops-autoscaler -n xamops-system DRY_RUN=false</code>
          </li>
        </ol>
      </div>

      <div className="bg-gray-50 border border-gray-200 rounded-lg p-4 mt-4">
        <h5 className="font-semibold text-gray-800 mb-2">What the Autoscaler Does:</h5>
        <ul className="text-sm text-gray-700 space-y-1">
          <li><i className="fas fa-compress-arrows-alt mr-2 text-red-500"></i><strong>Evictor:</strong> Bin-packing — consolidates underused nodes (scale-in)</li>
          <li><i className="fas fa-weight mr-2 text-indigo-500"></i><strong>Rightsizer:</strong> Analyzes Prometheus metrics, right-sizes Deployment CPU/memory requests</li>
          <li><i className="fas fa-sync-alt mr-2 text-cyan-500"></i><strong>Rebalancer:</strong> Replaces old on-demand nodes with optimal instances (relies on Karpenter)</li>
          <li><i className="fas fa-shield-alt mr-2 text-green-500"></i><strong>Safety Guards:</strong> Always respects PDBs, protects single-replica workloads and StatefulSets</li>
        </ul>
      </div>

      <div className="bg-amber-50 border border-amber-200 rounded-lg p-4 mt-4">
        <h5 className="font-semibold text-amber-800 mb-2">Variables Summary (identical to xamops-agent):</h5>
        <div className="text-sm text-amber-700 space-y-2">
          <p><strong>Client must change (3 values):</strong></p>
          <p className="ml-4"><code className="bg-amber-100 px-1 rounded">XAMOPS_SERVER_URL</code> → Your XamOps backend URL</p>
          <p className="ml-4"><code className="bg-amber-100 px-1 rounded">CLUSTER_NAME</code> → Your EKS cluster name</p>
          <p className="ml-4"><code className="bg-amber-100 px-1 rounded">CLOUD_ACCOUNT_ID</code> → Your AWS account ID</p>
          <p className="mt-2"><strong>Pre-configured (do NOT change):</strong></p>
          <p className="ml-4"><code className="bg-amber-100 px-1 rounded">XAMOPS_API_KEY</code> → xamops_SecureAgentKey2024XamOps (same for all clusters)</p>
        </div>
      </div>
    </CollapsibleSection>
    )} {/* end XAMOPS-AUTOSCALER hidden section */}

    <CollapsibleSection title="Step 3.6: Generate Kubeconfig (Alternative for Public Clusters)" icon="fas fa-key" iconColor="text-yellow-500">
      <div className="bg-blue-50 border border-blue-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-blue-800">
          <i className="fas fa-info-circle mr-2"></i>
          Use this method <strong>ONLY if</strong> your cluster API is publicly accessible. For private clusters, use the XamOps Agent (Step 3.5) instead.
        </p>
      </div>
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
kubectl get pods -l app=cost-exporter
kubectl get pods -n xamops-system  # XamOps Agent (if installed)`} />
    </CollapsibleSection>

    <CollapsibleSection title="Test 1.5: Verify XamOps Agent (if using agent mode)" icon="fas fa-robot" iconColor="text-indigo-500">
      <CodeBlock code={`# Check XamOps Agent pods
kubectl get pods -n xamops-system

# Check agent logs
kubectl logs -n xamops-system -l app=xamops-agent --tail=50

# Verify agent is pushing data (check for successful push messages)
kubectl logs -n xamops-system -l app=xamops-agent | grep -i "push\\|success\\|sent"

# Check agent status in XamOps Dashboard
# Go to: EKS Details -> Integration Status
# Agent should show as "active" with recent check-in time`} />
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

      <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mt-3">
        <p className="text-xs font-semibold text-amber-800 mb-2">
          <i className="fas fa-exclamation-triangle mr-1"></i> Kubescape pod startup order — storage must be Ready BEFORE node-agent
        </p>
        <CodeBlock code={`# Verify storage is ready BEFORE node-agent
kubectl get pods -n kubescape -l app.kubernetes.io/component=storage
# storage pod must show Running and Ready 1/1 BEFORE node-agent shows Ready

# If node-agent shows 0/1 Running with restarts, check:
kubectl logs -n kubescape -l app=node-agent --previous | tail -5
# "waiting for storage to be ready" = restart node-agent after storage is up:
kubectl rollout restart ds node-agent -n kubescape`} />
      </div>
    </CollapsibleSection>

    <div className="bg-green-50 border border-green-200 rounded-lg p-6 mt-6">
      <h4 className="font-bold text-green-800 mb-4 flex items-center gap-2">
        <i className="fas fa-clipboard-check"></i>
        Completion Checklist
      </h4>
      <p className="text-sm text-green-700 mb-4">Before handing off to application team:</p>

      <h5 className="font-semibold text-green-800 mb-2">Core Monitoring:</h5>
      <ul className="space-y-2 text-sm text-green-700 mb-4">
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> All pods in Running state</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> container_cpu_usage_seconds_total returns &gt; 0 in Prometheus</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> container_memory_working_set_bytes returns &gt; 0 in Prometheus</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> kube_pod_info returns &gt; 0 in Prometheus</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> OpenCost API returns cost data</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> Trivy vulnerability reports exist</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> Kubescape scan summaries exist</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> Cost exporter showing daily costs</li>
      </ul>

      <h5 className="font-semibold text-green-800 mb-2">Data Source (Choose ONE):</h5>
      <ul className="space-y-2 text-sm text-green-700 mb-4">
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> <strong>Option A (Agent):</strong> XamOps Agent running and pushing data (check status in XamOps Dashboard)</li>
        <li className="flex items-center gap-2"><input type="checkbox" className="rounded" /> <strong>Option B (Kubeconfig):</strong> xamops-kubeconfig.yaml file generated and uploaded</li>
      </ul>

      <div className="bg-indigo-100 border border-indigo-300 rounded p-3 mt-3">
        <p className="text-sm text-indigo-800">
          <i className="fas fa-lightbulb mr-2"></i>
          <strong>Recommendation:</strong> Use XamOps Agent (Option A) for private clusters or hybrid mode for best coverage.
        </p>
      </div>
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

      <h4 className="font-bold text-blue-800 mt-6 mb-4 flex items-center gap-2">
        <i className="fas fa-robot"></i>
        XamOps Agent Endpoints (If Using Agent Mode)
      </h4>
      <div className="space-y-3 text-sm text-blue-700">
        <p>
          <strong>Agent Ingest API:</strong><br />
          <code className="bg-blue-100 px-2 py-1 rounded">POST &lt;XAMOPS_SERVER_URL&gt;/api/v1/agent/ingest</code><br />
          <span className="text-xs text-gray-500">Authorization: Bearer &lt;API_KEY&gt;</span>
        </p>
        <p>
          <strong>Agent Heartbeat:</strong><br />
          <code className="bg-blue-100 px-2 py-1 rounded">POST &lt;XAMOPS_SERVER_URL&gt;/api/v1/agent/heartbeat</code><br />
          <span className="text-xs text-gray-500">Authorization: Bearer &lt;API_KEY&gt;</span>
        </p>
        <p>
          <strong>Agent Status Check:</strong><br />
          <code className="bg-blue-100 px-2 py-1 rounded">GET &lt;XAMOPS_SERVER_URL&gt;/api/v1/agent/status?clusterName=&lt;NAME&gt;&amp;cloudAccountId=&lt;ID&gt;</code>
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

        <h5 className="font-semibold text-gray-700 mt-6 mb-3 flex items-center gap-2">
          <i className="fas fa-robot text-indigo-500"></i>
          XamOps Agent (if using agent mode)
        </h5>
        <ul className="list-disc list-inside space-y-2 text-gray-700 ml-4">
          <li><code className="bg-gray-100 px-1 rounded">xamops-agent-credentials Secret</code>: <span className="text-amber-600 font-medium">&lt;XAMOPS_SERVER_URL&gt;</span>, <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span>, <span className="text-amber-600 font-medium">&lt;CLOUD_ACCOUNT_ID&gt;</span></li>
        </ul>

        <div className="bg-indigo-50 border border-indigo-200 rounded-lg p-3 mt-4">
          <p className="text-sm text-indigo-800">
            <i className="fas fa-info-circle mr-2"></i>
            <strong>Pre-configured (do NOT change):</strong> XAMOPS_API_KEY is the same for all clusters.
          </p>
        </div>
        <div className="bg-blue-50 border border-blue-200 rounded-lg p-3 mt-2">
          <p className="text-sm text-blue-800">
            <i className="fas fa-globe mr-2"></i>
            <strong>Environment URL:</strong> All client deployments use <code>https://live.xamops.com</code> — this is the only URL to use in production agent configurations.
          </p>
        </div>
        <div className="bg-green-50 border border-green-200 rounded-lg p-3 mt-2">
          <p className="text-sm text-green-800">
            <i className="fas fa-check-circle mr-2"></i>
            <strong>3 values to change:</strong> XAMOPS_SERVER_URL (environment), CLUSTER_NAME (your EKS cluster name), CLOUD_ACCOUNT_ID (12-digit AWS account ID)
          </p>
        </div>
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
        <li><strong>Wait 5 minutes after Trivy/Kubescape install</strong> before generating kubeconfig or installing agent</li>
        <li><strong>Use PRIVATE IP</strong> for monitoring instance in OTel DaemonSet and OpenCost configs (in-cluster traffic) — use <strong>Elastic IP (public)</strong> as the Prometheus URL when registering in XamOps dashboard (XamOps backend queries from outside your VPC)</li>
        <li><strong>XamOps Agent</strong> is recommended for private clusters — zero inbound ports required on cluster, agent only makes outbound HTTPS connections</li>
        <li><strong>XamOps Agent API key security</strong> — A single global key is used for all clusters but is NOT a security risk: the key only allows data to be pushed IN to XamOps (write-only). It cannot be used to read other clients' data, access any cluster, or query the XamOps database. Each cluster is isolated by CLUSTER_NAME + CLOUD_ACCOUNT_ID in the payload. Even if the key were leaked, an attacker could only push garbage data for a cluster name they control — they cannot read, delete, or access any real cluster or any other client's data.</li>
        <li><strong>Agent identifies cluster</strong> by CLUSTER_NAME + CLOUD_ACCOUNT_ID — both must be unique per cluster and must match exactly what is registered in XamOps</li>
        <li><strong>Data sync</strong> happens every 5 minutes automatically via agent push — no polling or inbound connections from XamOps to your cluster</li>
        <li><strong>Prometheus port 9090</strong> — restrict to XamOps server IP only (provided by XamOps support). Never open to 0.0.0.0/0 as Prometheus has no authentication by default</li>
        <li><strong>OTel ports 4317/4318</strong> — restrict to VPC CIDR only (same-VPC traffic). If cluster and monitoring instance are in different VPCs, restrict to the peered VPC CIDR</li>
        <li><strong>Kubescape node-agent and Karpenter</strong> — Always install Kubescape with <code className="bg-amber-100 px-1 rounded">--set nodeAgent.affinity=null --set nodeAgent.tolerations[0].operator=Exists</code>. The default chart may inject node affinity that blocks Karpenter consolidation. After install, wait for the storage pod to be Ready before node-agent starts, otherwise node-agent crashes with startup timeout and AVAILABLE=0 on the DaemonSet — which can trigger Karpenter to think nodes are unhealthy.</li>
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

        <div>
          <h5 className="font-semibold text-red-700">Kubescape node-agent 0/1 Running with restarts</h5>
          <CodeBlock code={`# Check node-agent crash logs
kubectl logs -n kubescape -l app=node-agent --previous | tail -10

# If you see "waiting for storage to be ready":
kubectl get pods -n kubescape -l app.kubernetes.io/component=storage
# Wait for storage to be 1/1 Running, then restart node-agent:
kubectl rollout restart ds node-agent -n kubescape`} />
        </div>

        <div>
          <h5 className="font-semibold text-red-700">XamOps Agent not pushing data</h5>
          <CodeBlock code={`# Check agent pod status
kubectl get pods -n xamops-system
kubectl describe pod -n xamops-system -l app=xamops-agent

# Check agent logs for errors
kubectl logs -n xamops-system -l app=xamops-agent --tail=100

# Verify environment variables are set correctly
kubectl exec -n xamops-system -l app=xamops-agent -- env | grep -E 'CLUSTER_NAME|CLOUD_ACCOUNT_ID|XAMOPS'

# Test connectivity to XamOps server
kubectl exec -n xamops-system -l app=xamops-agent -- wget -qO- --timeout=5 https://live.xamops.com/health || echo "Cannot reach server"

# Check OpenCost is accessible (required for cost data)
kubectl exec -n xamops-system -l app=xamops-agent -- wget -qO- --timeout=5 http://opencost.opencost.svc.cluster.local:9003/allocation/compute?window=1h || echo "OpenCost not accessible"`} />
        </div>

        <div>
          <h5 className="font-semibold text-red-700">Falco not showing alerts</h5>
          <CodeBlock code={`# Check Falco pods are running
kubectl get pods -n falco

# Check Falcosidekick is running
kubectl get deployment -n falco falco-falcosidekick

# Check Falco is detecting events (run a test)
kubectl run falco-test --image=busybox --restart=Never -- sh -c "cat /etc/shadow"
sleep 5
kubectl logs -n falco -l app.kubernetes.io/name=falco --tail=20 | grep -i "shadow\\|sensitive"
kubectl delete pod falco-test --force

# Check Falcosidekick metrics endpoint
kubectl port-forward -n falco svc/falco-falcosidekick 2801:2801 &
curl -s http://localhost:2801/metrics | grep falco
pkill -f 'port-forward.*falcosidekick'`} />
        </div>

        <div>
          <h5 className="font-semibold text-red-700">Container metrics empty (Containers tab)</h5>
          <CodeBlock code={`# Check cAdvisor metrics are being scraped
curl -s 'http://localhost:9090/api/v1/query?query=container_cpu_usage_seconds_total' | jq '.data.result | length'

# If 0, check OTel collector has cAdvisor job
kubectl get configmap -n default otel-collector-opentelemetry-collector -o yaml | grep -A30 "kubernetes-cadvisor"

# Check RBAC for node/stats access
kubectl get clusterrolebinding otel-collector-stats-reader -o yaml

# Verify OTel collector can access node metrics
kubectl logs -l app.kubernetes.io/name=opentelemetry-collector --tail=50 | grep -i "cadvisor\\|error"`} />
        </div>
      </div>
    </div>
  </div>
);

// Autoscaler Setup Tab — exported to suppress unused-local warning (referenced in commented code)
export const AutoscalerTab: React.FC = () => (
  <div className="space-y-6">
    <div className="flex items-center gap-3 mb-2">
      <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-indigo-600 rounded-xl flex items-center justify-center shadow">
        <i className="fas fa-robot text-white"></i>
      </div>
      <div>
        <h3 className="text-xl font-bold text-gray-800">XamOps Autoscaler Setup</h3>
        <p className="text-sm text-gray-500">Automated cluster cost optimization — Evictor · Rightsizer · Rebalancer</p>
      </div>
    </div>

    {/* What it does */}
    <div className="bg-purple-50 border border-purple-200 rounded-lg p-4">
      <h4 className="font-semibold text-purple-800 mb-3 flex items-center gap-2">
        <i className="fas fa-info-circle"></i> What XamOps Autoscaler Does
      </h4>
      <ul className="text-sm text-purple-700 space-y-2">
        <li><i className="fas fa-compress-arrows-alt mr-2 text-purple-500"></i><strong>Evictor (Bin-Packing):</strong> Consolidates underused nodes by safely migrating pods, then removes empty nodes to save cost</li>
        <li><i className="fas fa-weight mr-2 text-indigo-500"></i><strong>Workload Rightsizer:</strong> Analyses actual CPU/memory usage via Prometheus and patches Deployment resource requests to match real usage</li>
        <li><i className="fas fa-sync-alt mr-2 text-cyan-500"></i><strong>Rebalancer:</strong> Replaces old on-demand nodes with optimal instance types (relies on Karpenter being installed)</li>
        <li><i className="fas fa-shield-alt mr-2 text-green-500"></i><strong>Safety Guards:</strong> Always respects PodDisruptionBudgets, protects single-replica workloads and StatefulSets</li>
      </ul>
    </div>

    {/* Safety note */}
    <div className="bg-red-50 border border-red-200 rounded-lg p-4">
      <p className="text-sm text-red-800">
        <i className="fas fa-shield-alt mr-2"></i>
        <strong>Safety first:</strong> The autoscaler always starts with <code className="bg-red-100 px-1 rounded">DRY_RUN=true</code> — it
        logs every action it <em>would</em> take but makes <strong>zero changes</strong> to your cluster. Enable real actions only
        after you have verified data is flowing in the XamOps UI.
      </p>
    </div>

    {/* Prerequisites */}
    <div className="bg-amber-50 border border-amber-200 rounded-lg p-4">
      <h4 className="font-semibold text-amber-800 mb-2 flex items-center gap-2">
        <i className="fas fa-exclamation-triangle"></i> Prerequisites
      </h4>
      <ul className="text-sm text-amber-700 space-y-1 list-disc ml-5">
        <li><strong>xamops-agent</strong> must already be installed and running (Part 3 – Step 3.5)</li>
        <li><strong>metrics-server</strong> must be installed in the cluster (provides CPU/memory data)</li>
        <li><strong>Prometheus</strong> (optional but recommended) — enables accurate rightsizing recommendations</li>
        <li><strong>Karpenter</strong> (optional) — required for Rebalancer feature only</li>
      </ul>
    </div>

    {/* Variables to change */}
    <VariableNote variables={['<CLUSTER_NAME>', '<CLOUD_ACCOUNT_ID>', '<XAMOPS_SERVER_URL>']} />

    {/* Step 1: RBAC */}
    <CollapsibleSection title="Step 1: Create RBAC (write permissions for autoscaling actions)" icon="fas fa-user-shield" iconColor="text-purple-500" defaultOpen>
      <p className="text-sm text-gray-600 mb-3">
        The autoscaler needs write access to drain pods, cordon nodes, and patch deployment resources.
        These permissions are separate from the read-only <code>xamops-agent-role</code>.
      </p>
      <CodeBlock
        title="xamops-autoscaler-rbac.yaml"
        code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: xamops-autoscaler
  namespace: xamops-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: xamops-autoscaler
rules:
  # Read — needed for safe scheduling decisions
  - apiGroups: [""]
    resources: [nodes, pods, namespaces, services, persistentvolumeclaims, events]
    verbs: [get, list, watch]
  - apiGroups: [apps]
    resources: [deployments, replicasets, statefulsets, daemonsets]
    verbs: [get, list, watch, patch, update]
  - apiGroups: [batch]
    resources: [jobs, cronjobs]
    verbs: [get, list, watch]
  - apiGroups: [policy]
    resources: [poddisruptionbudgets]
    verbs: [get, list, watch]
  - apiGroups: [metrics.k8s.io]
    resources: [nodes, pods]
    verbs: [get, list]
  - apiGroups: [autoscaling.k8s.io]
    resources: [verticalpodautoscalers]
    verbs: [get, list, watch]
  # Write — autoscaling actions
  - apiGroups: [""]
    resources: [nodes]
    verbs: [patch, update]
  - apiGroups: [""]
    resources: [pods/eviction]
    verbs: [create]
  - apiGroups: [""]
    resources: [pods]
    verbs: [delete]
  - apiGroups: [""]
    resources: [events]
    verbs: [create, patch, update]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: xamops-autoscaler
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: xamops-autoscaler
subjects:
  - kind: ServiceAccount
    name: xamops-autoscaler
    namespace: xamops-system
EOF`}
      />
    </CollapsibleSection>

    {/* Step 2: ConfigMap + Secret */}
    <CollapsibleSection title="Step 2: Create ConfigMap and Secret" icon="fas fa-cog" iconColor="text-blue-500" defaultOpen>
      <div className="bg-blue-50 border border-blue-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-blue-800">
          <i className="fas fa-info-circle mr-2"></i>
          Features start disabled. Enable them one-by-one from the XamOps Autoscaling tab after verifying data is flowing.
          Keep <code>DRY_RUN=true</code> until you are confident the autoscaler is making correct decisions.
        </p>
      </div>
      <CodeBlock
        title="xamops-autoscaler-config.yaml"
        code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: xamops-autoscaler-config
  namespace: xamops-system
data:
  # Safety: dry-run logs all actions but makes zero cluster changes
  DRY_RUN: "true"

  # Features — start all false, enable individually from XamOps UI
  ENABLE_EVICTOR: "false"      # Bin-packing / node consolidation
  ENABLE_RIGHTSIZER: "false"   # Workload CPU/memory rightsizing
  ENABLE_REBALANCER: "false"   # Node replacement (requires Karpenter)

  # Intervals (seconds)
  POLICY_POLL_INTERVAL: "60"       # How often to fetch policy from XamOps
  METRICS_PUSH_INTERVAL: "120"     # How often to push KPI metrics to XamOps
  HEARTBEAT_INTERVAL: "60"         # Heartbeat check-in interval
  DRAIN_TIMEOUT_SECONDS: "1200"    # Max time to drain a node (20 min)

  # Prometheus (used for rightsizing CPU/memory recommendations)
  # Change if your Prometheus is in a different namespace/service name
  PROMETHEUS_URL: "http://prometheus-server.monitoring.svc.cluster.local:9090"

  LOG_LEVEL: "info"
---
apiVersion: v1
kind: Secret
metadata:
  name: xamops-autoscaler-secret
  namespace: xamops-system
type: Opaque
stringData:
  # Same global API key as xamops-agent — do NOT change:
  XAMOPS_API_KEY: "xamops_SecureAgentKey2024XamOps"
  # CHANGE these 3 values to match your cluster:
  XAMOPS_SERVER_URL: "<XAMOPS_SERVER_URL>"
  CLUSTER_NAME: "<CLUSTER_NAME>"
  CLOUD_ACCOUNT_ID: "<CLOUD_ACCOUNT_ID>"
EOF`}
      />
    </CollapsibleSection>

    {/* Step 3: Deploy */}
    <CollapsibleSection title="Step 3: Deploy the Autoscaler" icon="fas fa-rocket" iconColor="text-green-500" defaultOpen>
      <CodeBlock
        title="xamops-autoscaler-deployment.yaml"
        code={`kubectl apply -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: xamops-autoscaler
  namespace: xamops-system
  labels:
    app: xamops-autoscaler
spec:
  replicas: 1
  selector:
    matchLabels:
      app: xamops-autoscaler
  template:
    metadata:
      labels:
        app: xamops-autoscaler
    spec:
      serviceAccountName: xamops-autoscaler
      securityContext:
        runAsNonRoot: true
        runAsUser: 65534
      containers:
        - name: autoscaler
          image: xamops/autoscaler:latest
          imagePullPolicy: Always
          ports:
            - containerPort: 8081
          envFrom:
            - configMapRef:
                name: xamops-autoscaler-config
            - secretRef:
                name: xamops-autoscaler-secret
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 500m
              memory: 256Mi
          livenessProbe:
            httpGet:
              path: /healthz
              port: 8081
            initialDelaySeconds: 15
            periodSeconds: 30
EOF`}
      />
      <p className="text-sm text-gray-600 mt-4 mb-2">Verify both agents are running:</p>
      <CodeBlock code={`kubectl get pods -n xamops-system
# Expected: xamops-agent and xamops-autoscaler both Running

kubectl logs -n xamops-system -l app=xamops-autoscaler --tail=30
# Look for: "Policy fetched" or "Heartbeat sent" messages`} />
    </CollapsibleSection>

    {/* Step 4: Enable metrics in xamops-agent */}
    <CollapsibleSection title="Step 4: Enable Autoscaling Metrics in xamops-agent" icon="fas fa-chart-bar" iconColor="text-orange-500" defaultOpen>
      <div className="bg-orange-50 border border-orange-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-orange-800">
          <i className="fas fa-info-circle mr-2"></i>
          The <strong>KPI cards</strong> (Nodes Saved, Cost Saved, CPU utilization etc.) on the Autoscaling tab are populated
          by the <code>xamops-agent</code> with <code>COLLECTOR_AUTOSCALING=true</code>. Enable this after the autoscaler is running.
        </p>
      </div>
      <CodeBlock code={`# Enable autoscaling metrics collection in xamops-agent
kubectl patch configmap xamops-agent-config -n xamops-system \\
  --type merge \\
  -p '{"data":{"COLLECTOR_AUTOSCALING":"true"}}'

# Restart agent to pick up the change
kubectl rollout restart deployment/xamops-agent -n xamops-system
kubectl rollout status deployment/xamops-agent -n xamops-system`} />
    </CollapsibleSection>

    {/* Step 5: Enable autoscaling in UI + features */}
    <CollapsibleSection title="Step 5: Enable Autoscaling in XamOps UI" icon="fas fa-toggle-on" iconColor="text-indigo-500" defaultOpen>
      <div className="bg-green-50 border border-green-200 rounded-lg p-4">
        <ol className="text-sm text-green-800 space-y-2 list-decimal ml-4">
          <li>Go to <strong>EKS Details → Autoscaling tab</strong> for your cluster in XamOps</li>
          <li>Verify the <strong>Agent</strong> status shows <span className="font-mono bg-green-100 px-1 rounded text-xs">Healthy</span> with a recent last-seen time</li>
          <li>Click <strong>"Enable Autoscaling for this Cluster"</strong> in the banner at the top of the tab</li>
          <li>KPI cards will start populating within 2–3 minutes once the agent pushes its first metrics batch</li>
          <li>Enable individual features in this order (safest first):
            <ul className="mt-1 ml-4 list-disc space-y-1">
              <li><strong>Workload Rightsizer</strong> — observation only, no node changes</li>
              <li><strong>Evictor</strong> — bin-packing, respects all PDBs</li>
              <li><strong>Rebalancer</strong> — requires Karpenter; replaces on-demand nodes</li>
            </ul>
          </li>
          <li>When ready to apply real changes, disable dry-run:
            <div className="mt-1">
              <code className="bg-green-100 px-2 py-0.5 rounded text-xs">
                kubectl set env deployment/xamops-autoscaler -n xamops-system DRY_RUN=false
              </code>
            </div>
          </li>
        </ol>
      </div>
    </CollapsibleSection>

    {/* Troubleshooting */}
    <CollapsibleSection title="Troubleshooting" icon="fas fa-wrench" iconColor="text-red-500">
      <div className="space-y-4">
        <div>
          <h5 className="font-semibold text-red-700 mb-1">Components show "Offline" in Autoscaling tab</h5>
          <p className="text-sm text-gray-600 mb-2">The backend needs <code>autoscaling-metrics</code> data in cache. Check:</p>
          <CodeBlock code={`# 1. Confirm autoscaler is running
kubectl get pods -n xamops-system -l app=xamops-autoscaler

# 2. Check autoscaler logs for push errors
kubectl logs -n xamops-system -l app=xamops-autoscaler --tail=50 | grep -i "metric\\|push\\|error"

# 3. Confirm xamops-agent has COLLECTOR_AUTOSCALING=true
kubectl get configmap xamops-agent-config -n xamops-system -o jsonpath='{.data.COLLECTOR_AUTOSCALING}'`} />
        </div>

        <div>
          <h5 className="font-semibold text-red-700 mb-1">Autoscaler shows "Policy fetch failed"</h5>
          <p className="text-sm text-gray-600 mb-2">The autoscaler can't reach the XamOps backend. Check XAMOPS_SERVER_URL and API key:</p>
          <CodeBlock code={`# Check secret values
kubectl get secret xamops-autoscaler-secret -n xamops-system \\
  -o jsonpath='{.data.XAMOPS_SERVER_URL}' | base64 -d && echo

# Test connectivity from inside the cluster
kubectl exec -n xamops-system deploy/xamops-autoscaler -- \\
  wget -qO- --header="Authorization: Bearer \$(cat /etc/secret/XAMOPS_API_KEY)" \\
  \$XAMOPS_SERVER_URL/api/v1/agent/heartbeat || echo "Connection failed"`} />
        </div>

        <div>
          <h5 className="font-semibold text-red-700 mb-1">Rightsizer shows no recommendations</h5>
          <p className="text-sm text-gray-600 mb-2">Requires Prometheus with node metrics. Verify:</p>
          <CodeBlock code={`# Check Prometheus is reachable from autoscaler pod
kubectl exec -n xamops-system deploy/xamops-autoscaler -- \\
  wget -qO- "http://prometheus-server.monitoring.svc.cluster.local:9090/api/v1/query?query=up" | grep -o '"status":"[^"]*"'

# If unreachable, update PROMETHEUS_URL in ConfigMap to match your setup
kubectl patch configmap xamops-autoscaler-config -n xamops-system \\
  --type merge \\
  -p '{"data":{"PROMETHEUS_URL":"http://<your-prometheus-svc>.<namespace>.svc.cluster.local:9090"}}'
kubectl rollout restart deployment/xamops-autoscaler -n xamops-system`} />
        </div>

        <div>
          <h5 className="font-semibold text-red-700 mb-1">Quick reset to safe defaults</h5>
          <CodeBlock code={`kubectl patch configmap xamops-autoscaler-config -n xamops-system \\
  --type merge \\
  -p '{"data":{"DRY_RUN":"true","ENABLE_EVICTOR":"false","ENABLE_RIGHTSIZER":"false","ENABLE_REBALANCER":"false"}}'
kubectl rollout restart deployment/xamops-autoscaler -n xamops-system`} />
        </div>
      </div>
    </CollapsibleSection>

    {/* Env vars reference */}
    <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
      <h4 className="font-semibold text-gray-800 mb-3 flex items-center gap-2">
        <i className="fas fa-table text-gray-500"></i>
        Environment Variables Reference
      </h4>
      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="bg-gray-100">
            <th className="text-left p-2 border border-gray-200 font-semibold">Variable</th>
            <th className="text-left p-2 border border-gray-200 font-semibold">Default</th>
            <th className="text-left p-2 border border-gray-200 font-semibold">Description</th>
          </tr>
        </thead>
        <tbody className="text-gray-700">
          <tr><td className="p-2 border border-gray-200"><code>DRY_RUN</code></td><td className="p-2 border border-gray-200 text-amber-600">true</td><td className="p-2 border border-gray-200">Log actions only — no cluster changes</td></tr>
          <tr className="bg-gray-50"><td className="p-2 border border-gray-200"><code>ENABLE_EVICTOR</code></td><td className="p-2 border border-gray-200">false</td><td className="p-2 border border-gray-200">Bin-packing / node consolidation</td></tr>
          <tr><td className="p-2 border border-gray-200"><code>ENABLE_RIGHTSIZER</code></td><td className="p-2 border border-gray-200">false</td><td className="p-2 border border-gray-200">Workload CPU/memory request rightsizing</td></tr>
          <tr className="bg-gray-50"><td className="p-2 border border-gray-200"><code>ENABLE_REBALANCER</code></td><td className="p-2 border border-gray-200">false</td><td className="p-2 border border-gray-200">Node replacement via Karpenter</td></tr>
          <tr><td className="p-2 border border-gray-200"><code>POLICY_POLL_INTERVAL</code></td><td className="p-2 border border-gray-200">60</td><td className="p-2 border border-gray-200">Seconds between policy fetches from XamOps</td></tr>
          <tr className="bg-gray-50"><td className="p-2 border border-gray-200"><code>METRICS_PUSH_INTERVAL</code></td><td className="p-2 border border-gray-200">120</td><td className="p-2 border border-gray-200">Seconds between metric pushes to XamOps</td></tr>
          <tr><td className="p-2 border border-gray-200"><code>PROMETHEUS_URL</code></td><td className="p-2 border border-gray-200 text-xs">prometheus-server:9090</td><td className="p-2 border border-gray-200">Prometheus endpoint for rightsizer</td></tr>
          <tr className="bg-gray-50"><td className="p-2 border border-gray-200"><code>XAMOPS_SERVER_URL</code></td><td className="p-2 border border-gray-200 text-red-500">required</td><td className="p-2 border border-gray-200">XamOps backend URL (from Secret)</td></tr>
          <tr><td className="p-2 border border-gray-200"><code>CLUSTER_NAME</code></td><td className="p-2 border border-gray-200 text-red-500">required</td><td className="p-2 border border-gray-200">Your EKS cluster name (from Secret)</td></tr>
          <tr className="bg-gray-50"><td className="p-2 border border-gray-200"><code>CLOUD_ACCOUNT_ID</code></td><td className="p-2 border border-gray-200 text-red-500">required</td><td className="p-2 border border-gray-200">Your AWS account ID (from Secret)</td></tr>
        </tbody>
      </table>
    </div>
  </div>
);

export default EksIntegrationModal;
