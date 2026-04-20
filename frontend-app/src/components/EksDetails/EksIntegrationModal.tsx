/**
 * EKS Integration Guide Modal — Agent-Based Architecture
 *
 * In-cluster monitoring stack + XamOps push-only agents.
 * No external EC2 instance required — Prometheus runs inside the cluster.
 */

import React, { useState, useEffect } from 'react';
import { DialogRoot, DialogPanel } from '../ui';

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
          className="absolute top-2 right-2 px-2 py-1 bg-gray-700 hover:bg-gray-600 text-gray-300 hover:text-white rounded text-xs transition-opacity opacity-0 group-hover:opacity-100"
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
        <pre className="whitespace-pre">{code}</pre>
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

  const tabs = [
    { id: 'overview', label: 'Overview', icon: 'fas fa-info-circle' },
    { id: 'part1', label: 'Part 1: Monitoring Stack', icon: 'fas fa-chart-line' },
    { id: 'part2', label: 'Part 2: Security Tools', icon: 'fas fa-shield-alt' },
    { id: 'part3', label: 'Part 3: XamOps Agent', icon: 'fas fa-robot' },
    { id: 'part5', label: 'Part 4: Verification', icon: 'fas fa-check-circle' },
  ];

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-6xl">
      <DialogPanel
        title={
          <div className="flex items-center space-x-4">
            <div className="w-14 h-14 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center shadow-lg">
              <i className="fas fa-book text-white text-2xl"></i>
            </div>
            <div>
              <div className="font-bold text-xl text-gray-800">EKS Setup Guide — XamOps Agent</div>
              <div className="text-sm text-gray-600">In-cluster monitoring · Push-only · No inbound connections required</div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={<button onClick={onClose} className="px-6 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg font-semibold transition-all shadow-sm">Close Guide</button>}
      >
        {/* Tab Navigation */}
        <div className="flex border-b border-gray-200 bg-gray-50 overflow-x-auto -mx-6 px-6 mb-4">
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
        <div className="overflow-y-auto max-h-[60vh]" style={{ overscrollBehavior: 'contain' }}>
          {activeTab === 'overview' && <OverviewTab />}
          {activeTab === 'part1' && <Part1Tab />}
          {activeTab === 'part2' && <Part2Tab />}
          {activeTab === 'part3' && <Part3Tab />}
          {/* {activeTab === 'part4' && <Part4Tab />} */}
          {activeTab === 'part5' && <Part5Tab />}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

// ─── Overview Tab ──────────────────────────────────────────────────────────────
const OverviewTab: React.FC = () => (
  <div className="space-y-6">
    {/* Architecture banner */}
    <div className="bg-gradient-to-r from-blue-600 to-indigo-700 rounded-xl p-6 text-white">
      <h3 className="text-xl font-bold mb-2 flex items-center gap-2">
        <i className="fas fa-shield-alt"></i> Push-Only Architecture — Zero Inbound Required
      </h3>
      <p className="text-blue-100 text-sm">
        Both agents run inside your cluster and push data outbound to <code className="bg-blue-800 px-1 rounded">live.xamops.com:443</code>.
        Your cluster never receives inbound connections from XamOps. Works for private clusters, VPN-only environments, and any security posture.
      </p>
    </div>

    {/* Architecture diagram */}
    <div className="bg-gray-900 text-green-300 p-5 rounded-xl font-mono text-sm overflow-x-auto">
      <pre>{`YOUR EKS CLUSTER
├── monitoring namespace
│   ├── prometheus-server        ← scrapes all in-cluster metrics
│   ├── kube-state-metrics       ← kube_* metrics
│   ├── node-exporter (DaemonSet)← node_cpu, node_memory
│   ├── falco + falcosidekick    ← security events
│   └── opencost                 ← cost allocation
├── default namespace
│   └── cost-exporter (CronJob)  ← node hourly cost
├── xamops-system namespace
│   └── xamops-agent             ← reads K8s API + queries Prometheus
│       │                           PUSH → live.xamops.com:443
│       └── collectors enabled:
│           ├── k8s-resources    ← full cluster snapshot (K8s API)
│           ├── prometheus-metrics← utilization, cost, Falco
│           ├── trivy            ← vuln reports (CRDs)
│           ├── kubescape        ← compliance (CRDs)
│           └── karpenter        ← node provisioner data
`}</pre>
    </div>

    {/* How it works */}
    <div className="bg-white border border-gray-200 rounded-lg p-6">
      <h4 className="font-bold text-gray-800 mb-4 flex items-center gap-2">
        <i className="fas fa-sync-alt text-indigo-500"></i> How Data Flows
      </h4>
      <div className="space-y-3">
        {[
          { n: '1', color: 'bg-blue-500', text: 'Prometheus scrapes KSM, node-exporter, cadvisor, Falco, OpenCost, cost-exporter — all inside the cluster.' },
          { n: '2', color: 'bg-green-500', text: 'XamOps Agent queries Prometheus in-cluster and reads the Kubernetes API (nodes, pods, deployments, HPAs, PVCs, events…).' },
          { n: '3', color: 'bg-purple-500', text: 'Agent POSTs a JSON payload to live.xamops.com/api/v1/agent/ingest (HTTPS, outbound port 443).' },
          { n: '4', color: 'bg-orange-500', text: 'XamOps backend stores data in agent_data_cache table, keyed by cluster. Dashboard reads from cache — no direct cluster access needed.' },
        ].map(({ n, color, text }) => (
          <div key={n} className="flex items-start gap-3">
            <div className={`${color} text-white rounded-full w-6 h-6 flex items-center justify-center text-xs font-bold flex-shrink-0 mt-0.5`}>{n}</div>
            <p className="text-sm text-gray-700">{text}</p>
          </div>
        ))}
      </div>
    </div>

    {/* Security model */}
    <div className="bg-green-50 border border-green-300 rounded-lg p-5">
      <h4 className="font-bold text-green-800 mb-3 flex items-center gap-2">
        <i className="fas fa-lock text-green-600"></i> Security Model
      </h4>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-sm">
        <div>
          <p className="font-semibold text-green-700 mb-2">What XamOps NEVER does:</p>
          <ul className="space-y-1 text-green-700">
            <li><i className="fas fa-times-circle mr-2 text-red-500"></i>Opens inbound connections to your cluster</li>
            <li><i className="fas fa-times-circle mr-2 text-red-500"></i>Stores your kubeconfig on XamOps servers</li>
            <li><i className="fas fa-times-circle mr-2 text-red-500"></i>Requires VPN, peering, or NAT gateway changes</li>
            <li><i className="fas fa-times-circle mr-2 text-red-500"></i>Exposes Prometheus to the internet</li>
          </ul>
        </div>
        <div>
          <p className="font-semibold text-green-700 mb-2">What you need:</p>
          <ul className="space-y-1 text-green-700">
            <li><i className="fas fa-check-circle mr-2 text-green-500"></i>Outbound HTTPS (port 443) from cluster nodes to <code className="bg-green-100 px-1 rounded">live.xamops.com</code></li>
            <li><i className="fas fa-check-circle mr-2 text-green-500"></i>kubectl + helm (v3.12+) to deploy</li>
            <li><i className="fas fa-check-circle mr-2 text-green-500"></i>Cluster Admin to create RBAC</li>
          </ul>
        </div>
      </div>
      <div className="mt-3 bg-green-100 rounded p-2 text-xs text-green-800">
        <i className="fas fa-info-circle mr-1"></i>
        <strong>Private clusters:</strong> Works identically — outbound HTTPS on port 443 is always open in EKS by default (NAT gateway / internet gateway). No security group changes needed.
      </div>
    </div>

    {/* 4-step overview cards */}
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      {[
        { part: 'Part 1', color: 'blue', icon: 'fas fa-chart-line', title: 'Monitoring Stack', desc: 'Prometheus + KSM + Metrics Server + Falco + Cost Exporter + OpenCost (all in-cluster)' },
        { part: 'Part 2', color: 'purple', icon: 'fas fa-shield-alt', title: 'Security Tools', desc: 'Trivy (image vuln scanning) + Kubescape (compliance)' },
        { part: 'Part 3', color: 'green', icon: 'fas fa-robot', title: 'XamOps Agent', desc: 'Read-only observability agent — pushes cluster data to XamOps every 5 min' },
        { part: 'Part 4', color: 'teal', icon: 'fas fa-check-circle', title: 'Verification', desc: 'Confirm all pods are running and data appears in XamOps dashboard' },
      ].map(({ part, color, icon, title, desc }) => (
        <div key={part} className={`bg-gradient-to-br from-${color}-50 to-${color}-100 border border-${color}-200 rounded-lg p-4`}>
          <div className="flex items-center gap-2 mb-1">
            <i className={`${icon} text-${color}-600`}></i>
            <span className="text-xs font-bold text-gray-500">{part}</span>
          </div>
          <p className="font-semibold text-gray-800 text-sm">{title}</p>
          <p className="text-xs text-gray-600 mt-1">{desc}</p>
        </div>
      ))}
    </div>

    {/* Prerequisites */}
    <div className="bg-white border border-gray-200 rounded-lg p-5">
      <h4 className="font-bold text-gray-800 mb-3 flex items-center gap-2">
        <i className="fas fa-clipboard-list text-indigo-500"></i> Prerequisites
      </h4>
      <ul className="list-disc list-inside space-y-2 text-gray-700 text-sm">
        <li>EKS cluster (k8s v1.24+) with cluster-admin kubectl access</li>
        <li>Helm v3.12+ and kubectl v1.28+</li>
        <li>Outbound HTTPS (443) from cluster to <code className="bg-gray-100 px-1 rounded">live.xamops.com</code></li>
        <li>Cluster name and AWS Account ID (12 digits) — needed in ConfigMaps</li>
      </ul>
    </div>
  </div>
);

// ─── Part 1: In-Cluster Monitoring Stack ──────────────────────────────────────
const Part1Tab: React.FC = () => (
  <div className="space-y-6">
    <h3 className="text-xl font-bold text-gray-800 mb-2">Part 1: In-Cluster Monitoring Stack</h3>
    <div className="bg-blue-50 border-l-4 border-blue-500 p-4 rounded-r-lg text-sm text-blue-800">
      <i className="fas fa-info-circle mr-2"></i>
      All monitoring tools run inside your cluster in the <code className="bg-blue-100 px-1 rounded">monitoring</code> namespace.
      Prometheus scrapes metrics from KSM, node-exporter (cadvisor), Falco, OpenCost, and your cost exporter.
      The XamOps Agent queries this Prometheus at <code className="bg-blue-100 px-1 rounded">http://prometheus-server.monitoring.svc.cluster.local:9090</code>.
    </div>

    <CollapsibleSection title="Step 1.1: Create Namespaces" icon="fas fa-folder">
      <CodeBlock code={`kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace falco      --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace opencost   --dry-run=client -o yaml | kubectl apply -f -`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.2: Enable EBS CSI Driver + StorageClass (required for Prometheus PVC)" icon="fas fa-hdd" iconColor="text-yellow-600" defaultOpen={false}>
      <div className="bg-red-50 border border-red-200 rounded p-3 text-sm text-red-800 mb-4">
        <i className="fas fa-exclamation-circle mr-2"></i>
        <strong>Do this before installing Prometheus.</strong> Prometheus needs a PersistentVolume for storage. Without the EBS CSI driver the PVC will stay <code className="bg-red-100 px-1 rounded">Pending</code> forever and the server pod will never start.
      </div>

      <p className="text-sm text-gray-700 font-semibold mb-1">Step A — Attach IAM policy to your node group role</p>
      <div className="bg-blue-50 border border-blue-200 rounded p-3 text-sm text-blue-800 mb-3">
        <i className="fas fa-shield-alt mr-2"></i>
        <strong>What access does this grant?</strong> <code>AmazonEBSCSIDriverPolicy</code> only allows the EBS CSI driver to manage EBS volumes as Kubernetes PersistentVolumes — it can create/attach/detach/delete EBS volumes on behalf of PVC requests. It does <strong>not</strong> grant access to your workload data, application secrets, or any other AWS services. Without it, PVCs will stay in <code>Pending</code> state permanently.
        <br /><br />
        <i className="fas fa-info-circle mr-2"></i>
        <strong>Production alternative (IRSA):</strong> Instead of attaching to the node role, you can create a dedicated IAM role with IRSA (IAM Roles for Service Accounts) and annotate the <code>ebs-csi-controller-sa</code> ServiceAccount. This is more secure — only the CSI driver pod gets the permission, not all pods on the node. See AWS docs for IRSA setup.
      </div>
      <VariableNote variables={['<NODEGROUP_ROLE_NAME>']} />
      <CodeBlock code={`# Find your node group IAM role name:
# AWS Console → EKS → your cluster → Compute → Node groups → IAM role ARN
# Or: aws eks describe-nodegroup --cluster-name <CLUSTER_NAME> --nodegroup-name <NODEGROUP_NAME> --query "nodegroup.nodeRole"

aws iam attach-role-policy \\
  --role-name <NODEGROUP_ROLE_NAME> \\
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy`} />

      <p className="text-sm text-gray-700 font-semibold mt-4 mb-1">Step B — Install the EBS CSI driver addon</p>
      <VariableNote variables={['<CLUSTER_NAME>', '<REGION>']} />
      <CodeBlock code={`aws eks create-addon \\
  --region <REGION> \\
  --cluster-name <CLUSTER_NAME> \\
  --addon-name aws-ebs-csi-driver \\
  --resolve-conflicts OVERWRITE

# Wait for it to become active (~60s)
aws eks wait addon-active \\
  --region <REGION> \\
  --cluster-name <CLUSTER_NAME> \\
  --addon-name aws-ebs-csi-driver

# Verify controller pods are 6/6 Running
kubectl get pods -n kube-system -l app=ebs-csi-controller`} />

      <p className="text-sm text-gray-700 font-semibold mt-4 mb-1">Step C — Create gp3 StorageClass as cluster default</p>
      <div className="bg-amber-50 border border-amber-200 rounded p-3 text-sm text-amber-800 mb-2">
        <i className="fas fa-exclamation-triangle mr-2"></i>
        <strong>Safe approach — never delete existing StorageClasses.</strong> If you already have a default StorageClass (e.g. <code className="bg-amber-100 px-1 rounded">gp2</code>), existing PVCs and workloads continue to use it unchanged. We only remove its <em>default</em> annotation so Kubernetes won't have two defaults — the gp2 StorageClass itself stays intact.
      </div>
      <CodeBlock code={`# Step 1: Remove the "default" annotation from any existing default StorageClass
# This is safe — it does NOT delete the StorageClass or affect existing PVCs/workloads.
# If no default exists this is a no-op (|| true suppresses the error).
kubectl patch storageclass gp2 \\
  -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}' \\
  2>/dev/null || true

# Also patch gp3 if it already exists with the old in-tree provisioner
kubectl patch storageclass gp3 \\
  -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}' \\
  2>/dev/null || true

# Step 2: Create the new gp3 StorageClass backed by the EBS CSI driver
kubectl apply -f - <<'EOF'
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: gp3
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  fsType: ext4
  encrypted: "true"
volumeBindingMode: WaitForFirstConsumer
reclaimPolicy: Delete
allowVolumeExpansion: true
EOF`} />

      <div className="bg-green-50 border border-green-200 rounded p-3 text-sm text-green-800 mt-2">
        <i className="fas fa-check-circle mr-2"></i>
        Verify: <code className="bg-green-100 px-1 rounded">kubectl get storageclass</code> — <code>gp3</code> should show <code>(default)</code> with provisioner <code>ebs.csi.aws.com</code>. Existing <code>gp2</code> StorageClass remains listed (non-default) and all current PVCs using it are unaffected.
      </div>
      <div className="bg-blue-50 border border-blue-200 rounded p-3 text-sm text-blue-800 mt-2">
        <i className="fas fa-info-circle mr-2"></i>
        <strong>Why gp3?</strong> gp3 provides 3,000 IOPS and 125 MB/s throughput baseline at no extra cost (gp2 only gives 3 IOPS/GB — a 20Gi volume gets just 60 IOPS). gp3 is also ~20% cheaper. <code>allowVolumeExpansion: true</code> lets you resize PVCs without recreating them. <code>encrypted: "true"</code> ensures data at rest is encrypted by default.
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.3: Install Prometheus (in-cluster)" icon="fas fa-fire" iconColor="text-orange-500">
      <p className="text-sm text-gray-600 mb-3">
        Installs Prometheus with node-exporter (DaemonSet) and kube-state-metrics subchart.
        Extra scrape configs cover Falco, OpenCost, and the cost exporter.
      </p>
      <CodeBlock code={`helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update`} />

      <CodeBlock
        title="prometheus-values.yaml"
        code={`cat > prometheus-values.yaml << 'EOF'
server:
  persistentVolume:
    enabled: true
    size: 20Gi
  retention: "15d"
  global:
    scrape_interval: 30s
    evaluation_interval: 30s

alertmanager:
  enabled: false
pushgateway:
  enabled: false

# Node-exporter DaemonSet — provides node_cpu_seconds_total, node_memory_* etc.
nodeExporter:
  enabled: true

# Kube-State-Metrics — provides kube_pod_*, kube_node_status_capacity etc.
kube-state-metrics:
  enabled: true

# Extra scrape jobs for Falco, OpenCost, and cost-exporter
extraScrapeConfigs: |
  - job_name: falco-sidekick
    scrape_interval: 30s
    static_configs:
      - targets: ['falco-falcosidekick.falco.svc.cluster.local:2801']
  - job_name: opencost
    scrape_interval: 60s
    static_configs:
      - targets: ['opencost.opencost.svc.cluster.local:9003']
  - job_name: cost-exporter
    scrape_interval: 60s
    static_configs:
      - targets: ['cost-exporter.default.svc.cluster.local:8080']
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: 'xamops_eks_.*'
        action: keep
EOF`}
      />

      <CodeBlock code={`helm upgrade --install prometheus prometheus-community/prometheus \\
  --namespace monitoring \\
  --values prometheus-values.yaml \\
  --set server.persistentVolume.storageClass=gp3`} />

      <div className="bg-green-50 border border-green-200 rounded p-3 text-sm text-green-800 mt-2">
        <i className="fas fa-check-circle mr-2"></i>
        Wait ~60s then verify: <code className="bg-green-100 px-1 rounded">kubectl get pods -n monitoring</code> and <code className="bg-green-100 px-1 rounded">kubectl get pvc -n monitoring</code>.
        Expect <code>prometheus-server-*</code> as <code>2/2 Running</code> and PVC as <code>Bound</code> with storageClass <code>gp3</code>.
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.4: Install Metrics Server" icon="fas fa-tachometer-alt" iconColor="text-blue-500">
      <p className="text-sm text-gray-600 mb-3">
        Enables <code>kubectl top nodes/pods</code> and provides real-time CPU/memory resource usage to the XamOps agent via the metrics-server API.
        Skip if already installed (check: <code>kubectl get apiservice v1beta1.metrics.k8s.io</code>).
      </p>
      <CodeBlock code={`helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/
helm repo update

helm upgrade --install metrics-server metrics-server/metrics-server \\
  --namespace kube-system \\
  --set args[0]="--kubelet-insecure-tls"`} />
      <p className="text-xs text-gray-500 mt-1">Or via manifest: <code>kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml</code></p>
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.5: Install Falco + FalcoSidekick" icon="fas fa-eye" iconColor="text-purple-500">
      <p className="text-sm text-gray-600 mb-3">
        Falco is a CNCF runtime security tool — it detects syscall-level threats in real time (container escapes, privilege escalations, unexpected network connections). FalcoSidekick exposes events as Prometheus metrics (<code>falco_events_total</code>) which the XamOps agent collects.
      </p>
      <CodeBlock code={`helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update

helm upgrade --install falco falcosecurity/falco \\
  --namespace falco \\
  --set driver.kind=ebpf \\
  --set falco.json_output=true \\
  --set falco.grpc.enabled=false \\
  --set falco.grpc_output.enabled=false \\
  --set falcosidekick.enabled=true \\
  --set falcosidekick.webui.enabled=false \\
  --set falcosidekick.config.prometheus.enabled=true \\
  --set falcosidekick.service.port=2801 \\
  --set tolerations[0].operator=Exists`} />

      <div className="bg-amber-50 border border-amber-200 rounded p-3 text-sm text-amber-800 mt-2">
        <i className="fas fa-info-circle mr-2"></i>
        <strong>Driver compatibility:</strong>
        <ul className="list-disc list-inside mt-1 space-y-1">
          <li><strong>EKS with AL2 (kernel 5.10+) or AL2023 (kernel 6.1+):</strong> <code>--set driver.kind=ebpf</code> works</li>
          <li><strong>Older kernels / custom AMIs:</strong> try <code>--set driver.kind=modern_ebpf</code></li>
          <li><strong>If both fail:</strong> use <code>--set driver.kind=module</code> (requires kernel headers on nodes)</li>
        </ul>
      </div>

      <p className="text-sm text-gray-600 mt-3 mb-1">Verify Falco is running on all nodes:</p>
      <CodeBlock code={`# Falco runs as a DaemonSet — one pod per node
kubectl get pods -n falco -o wide   # all should be Running

# FalcoSidekick should be Running (1-2 replicas)
kubectl get pods -n falco -l app.kubernetes.io/name=falcosidekick

# Verify Prometheus metrics endpoint is reachable from within cluster
kubectl run -it --rm debug --image=curlimages/curl:latest --restart=Never -- \\
  curl -s http://falco-falcosidekick.falco.svc.cluster.local:2801/metrics | grep falco_events | head -5`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.6: Install Cost Exporter" icon="fas fa-dollar-sign" iconColor="text-green-500">
      <p className="text-sm text-gray-600 mb-3">
        Runs a CronJob that exports hourly node costs as <code>xamops_eks_*</code> Prometheus metrics using EC2 Spot/On-Demand pricing.
      </p>
      <VariableNote variables={['<CLUSTER_NAME>', '<REGION>', '<AWS_ACCOUNT_ID>']} />
      <CodeBlock
        title="Step 1: Create IAM Role for cost-exporter"
        code={`CLUSTER_NAME="<CLUSTER_NAME>"
REGION="<REGION>"
ACCOUNT_ID="<AWS_ACCOUNT_ID>"

# Get OIDC issuer
OIDC=$(aws eks describe-cluster --name $CLUSTER_NAME --region $REGION \\
  --query "cluster.identity.oidc.issuer" --output text | sed 's|https://||')

# Create trust policy
cat > cost-exporter-trust.json << EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::\${ACCOUNT_ID}:oidc-provider/\${OIDC}" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "\${OIDC}:sub": "system:serviceaccount:default:cost-exporter-sa",
        "\${OIDC}:aud": "sts.amazonaws.com"
      }
    }
  }]
}
EOF

aws iam create-role --role-name xamops-cost-exporter-role \\
  --assume-role-policy-document file://cost-exporter-trust.json

aws iam attach-role-policy --role-name xamops-cost-exporter-role \\
  --policy-arn arn:aws:iam::aws:policy/AmazonEC2ReadOnlyAccess`}
      />

      <CodeBlock
        title="Step 2: Deploy cost-exporter"
        code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: cost-exporter-sa
  namespace: default
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::<AWS_ACCOUNT_ID>:role/xamops-cost-exporter-role
---
# Service — allows Prometheus to scrape cost-exporter metrics continuously
apiVersion: v1
kind: Service
metadata:
  name: cost-exporter
  namespace: default
  labels:
    app: cost-exporter
spec:
  selector:
    app: cost-exporter
  ports:
    - port: 8080
      targetPort: 8080
---
# Deployment (long-running) — NOT a CronJob.
# Must be a Deployment so the Service always has an endpoint for Prometheus to scrape.
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
      serviceAccountName: cost-exporter-sa
      containers:
        - name: cost-exporter
          image: docker.io/xamops/cost-exporter:latest
          ports:
            - containerPort: 8080
          env:
            - name: CLUSTER_NAME
              value: "<CLUSTER_NAME>"
            - name: REGION
              value: "<REGION>"
            - name: PORT
              value: "8080"
            - name: REFRESH_INTERVAL
              value: "900"
          resources:
            requests:
              cpu: 10m
              memory: 32Mi
            limits:
              cpu: 100m
              memory: 128Mi
EOF`}
      />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.7: Install OpenCost" icon="fas fa-coins" iconColor="text-yellow-500">
      <p className="text-sm text-gray-600 mb-3">
        OpenCost provides namespace-level cost allocation. It reads Prometheus in-cluster for efficiency data.
      </p>
      <VariableNote variables={['<REGION>']} />
      <CodeBlock code={`helm repo add opencost https://opencost.github.io/opencost-helm-chart
helm repo update`} />
      <CodeBlock
        title="opencost-values.yaml"
        code={`cat > opencost-values.yaml << 'EOF'
opencost:
  prometheus:
    external:
      enabled: false
    internal:
      enabled: true
      # In-cluster Prometheus — no external IP needed
      serviceName: prometheus-server
      namespaceName: monitoring
      port: 80
  cloudCost:
    enabled: true
  exporter:
    aws:
      region: "<REGION>"
EOF`}
      />
      <CodeBlock code={`helm upgrade --install opencost opencost/opencost \\
  --namespace opencost \\
  --values opencost-values.yaml`} />
    </CollapsibleSection>
  </div>
);

// ─── Part 2: Security Tools ───────────────────────────────────────────────────
const Part2Tab: React.FC = () => (
  <div className="space-y-6">
    <h3 className="text-xl font-bold text-gray-800 mb-2">Part 2: Security Tools</h3>
    <div className="bg-purple-50 border-l-4 border-purple-500 p-4 rounded-r-lg text-sm text-purple-800">
      <i className="fas fa-info-circle mr-2"></i>
      Trivy creates <code>VulnerabilityReport</code> CRDs. Kubescape creates <code>ConfigurationScanSummary</code> CRDs.
      The XamOps Agent reads these CRDs via the Kubernetes API and pushes them to the dashboard.
    </div>

    <CollapsibleSection title="Step 2.1: Install Trivy Operator" icon="fas fa-bug" iconColor="text-red-500">
      <p className="text-sm text-gray-600 mb-3">
        Trivy Operator continuously scans all workload images and generates <code>VulnerabilityReport</code> CRDs in each namespace. The XamOps agent reads these CRDs and pushes CVE data to your dashboard.
      </p>
      <CodeBlock code={`helm repo add aquasecurity https://aquasecurity.github.io/helm-charts/
helm repo update

helm upgrade --install trivy-operator aquasecurity/trivy-operator \\
  --namespace trivy-system --create-namespace \\
  --set trivy.ignoreUnfixed=false \\
  --set trivy.severity=UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL \\
  --set operator.scanJobTimeout=10m \\
  --set operator.vulnerabilityScanner.enabled=true \\
  --set operator.configAuditScannerEnabled=true \\
  --set operator.rbacAssessmentScannerEnabled=true`} />

      <div className="bg-blue-50 border border-blue-200 rounded p-3 text-sm text-blue-800 mt-3">
        <i className="fas fa-info-circle mr-2"></i>
        Trivy scans images automatically as new pods are scheduled. Initial scan of all existing workloads takes 5–15 minutes depending on cluster size.
        <code className="block bg-blue-100 px-2 py-1 rounded mt-1">kubectl get pods -n trivy-system   # trivy-operator-* should be Running</code>
      </div>
      <p className="text-sm text-gray-600 mt-3 mb-1">Verify reports are being generated (wait 5–10 min after install):</p>
      <CodeBlock code={`# Should list VulnerabilityReports for each workload
kubectl get vulnerabilityreports -A --no-headers | head -20

# Check a specific report
kubectl get vulnerabilityreports -A -o wide | head -5

# See CVE count summary
kubectl get vulnerabilityreports -A -o jsonpath='{range .items[*]}{.metadata.name}{": C="}{.report.summary.criticalCount}{" H="}{.report.summary.highCount}{"\n"}{end}' | head -10`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.2: Install Kubescape" icon="fas fa-clipboard-check" iconColor="text-blue-500">
      <p className="text-sm text-gray-600 mb-3">
        Kubescape continuously scans your cluster against NSA-CISA, MITRE ATT&CK, and CIS Benchmark frameworks. It writes <code>ConfigurationScanSummary</code> CRDs that the XamOps agent reads for the compliance dashboard.
      </p>
      <VariableNote variables={['<CLUSTER_NAME>']} />
      <CodeBlock code={`helm repo add kubescape https://kubescape.github.io/helm-charts/
helm repo update

helm upgrade --install kubescape kubescape/kubescape-operator \\
  --namespace kubescape --create-namespace \\
  --set clusterName=<CLUSTER_NAME> \\
  --set capabilities.continuousScan=enable \\
  --set capabilities.relevancyEnabled=false \\
  --set storage.enabled=true`} />

      <div className="bg-amber-50 border border-amber-200 rounded p-3 text-sm text-amber-800 mt-3">
        <i className="fas fa-exclamation-triangle mr-2"></i>
        <strong>Important:</strong> <code>&lt;CLUSTER_NAME&gt;</code> must match exactly what you use in the XamOps agent ConfigMap — use the plain cluster name (e.g. <code>my-eks-cluster</code>), not the full ARN or kubectl context name.
      </div>

      <p className="text-sm text-gray-600 mt-3 mb-1">Verify pods are running and scans are complete (wait 3–5 min):</p>
      <CodeBlock code={`kubectl get pods -n kubescape   # all pods should be Running/Completed

# Should show scan summaries per namespace
kubectl get configurationscansummaries -A --no-headers | head -10

# Check compliance score
kubectl get configurationscansummaries -A -o jsonpath='{range .items[*]}{.metadata.name}{": "}{.spec.summaryDetails.frameworks[0].name}{" score="}{.spec.summaryDetails.frameworks[0].complianceScore}{"\n"}{end}' | head -5`} />
    </CollapsibleSection>
  </div>
);

// ─── Part 3: XamOps Observability Agent ───────────────────────────────────────
const Part3Tab: React.FC = () => (
  <div className="space-y-6">
    <h3 className="text-xl font-bold text-gray-800 mb-2">Part 3: XamOps Observability Agent</h3>
    <div className="bg-green-50 border-l-4 border-green-500 p-4 rounded-r-lg">
      <p className="text-sm text-green-800">
        <i className="fas fa-robot mr-2"></i>
        <strong>Read-only agent</strong> — runs as a single Deployment with minimal CPU/memory.
        Collects full K8s resource snapshots + Prometheus metrics + security scan results,
        then pushes to <code className="bg-green-100 px-1 rounded">live.xamops.com/api/v1/agent/ingest</code> every 5 minutes.
      </p>
    </div>

    <div className="bg-white border border-gray-200 rounded-lg p-4">
      <h5 className="font-semibold text-gray-800 mb-3">What the Agent Collects:</h5>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm text-gray-700">
        <div className="space-y-1">
          <p><i className="fas fa-server mr-2 text-blue-500"></i><strong>k8s-resources:</strong> Nodes, Pods, Deployments, StatefulSets, DaemonSets, Services, Ingresses, HPAs, PVCs, Jobs, Events, Node/Pod metrics</p>
          <p><i className="fas fa-chart-bar mr-2 text-orange-500"></i><strong>prometheus-metrics:</strong> CPU/mem %, per-node utilization, namespace usage, cost metrics, Falco events, top pods by CPU/memory, network I/O</p>
        </div>
        <div className="space-y-1">
          <p><i className="fas fa-bug mr-2 text-red-500"></i><strong>trivy:</strong> VulnerabilityReports, ConfigAuditReports</p>
          <p><i className="fas fa-clipboard-check mr-2 text-purple-500"></i><strong>kubescape:</strong> ConfigurationScanSummaries, WorkloadConfigurationScans</p>
          <p><i className="fas fa-expand-arrows-alt mr-2 text-teal-500"></i><strong>karpenter:</strong> NodePools, NodeClaims, EC2NodeClasses</p>
          <p><i className="fas fa-coins mr-2 text-yellow-500"></i><strong>opencost:</strong> Namespace cost allocation</p>
        </div>
      </div>
    </div>

    <CollapsibleSection title="Step 3.1: Create RBAC (read-only)" icon="fas fa-key" iconColor="text-yellow-500">
      <CodeBlock
        title="xamops-agent-rbac.yaml"
        code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: Namespace
metadata:
  name: xamops-system
  labels:
    app.kubernetes.io/name: xamops-agent
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: xamops-agent-sa
  namespace: xamops-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: xamops-agent-role
rules:
  # Core K8s resources
  - apiGroups: [""]
    resources: [pods, nodes, namespaces, services, events, persistentvolumeclaims, replicationcontrollers]
    verbs: [get, list, watch]
  - apiGroups: [apps]
    resources: [deployments, daemonsets, statefulsets, replicasets]
    verbs: [get, list, watch]
  - apiGroups: [batch]
    resources: [jobs, cronjobs]
    verbs: [get, list, watch]
  - apiGroups: [networking.k8s.io]
    resources: [ingresses]
    verbs: [get, list, watch]
  - apiGroups: [autoscaling]
    resources: [horizontalpodautoscalers]
    verbs: [get, list, watch]
  # Metrics server
  - apiGroups: [metrics.k8s.io]
    resources: [nodes, pods]
    verbs: [get, list]
  # Trivy CRDs
  - apiGroups: [aquasecurity.github.io]
    resources: [vulnerabilityreports, configauditreports, exposedsecretreports]
    verbs: [get, list, watch]
  # Kubescape CRDs
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
  # CRD discovery
  - apiGroups: [apiextensions.k8s.io]
    resources: [customresourcedefinitions]
    verbs: [get, list]
---
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
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.2: Create ConfigMap and Secret" icon="fas fa-cog" iconColor="text-blue-500">
      <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mb-3">
        <p className="text-sm text-amber-800">
          <i className="fas fa-edit mr-2"></i>
          <strong>Replace these 2 values:</strong>
        </p>
        <ul className="text-sm text-amber-700 mt-1 ml-4 list-disc">
          <li><code>&lt;CLUSTER_NAME&gt;</code> → Your EKS cluster name (e.g. <code>my-prod-cluster</code>)</li>
          <li><code>&lt;CLOUD_ACCOUNT_ID&gt;</code> → Your AWS account ID (12 digits, e.g. <code>123456789012</code>)</li>
        </ul>
      </div>
      <CodeBlock
        title="xamops-agent-config.yaml"
        code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: xamops-agent-config
  namespace: xamops-system
data:
  COLLECT_INTERVAL: "300"
  PUSH_INTERVAL: "300"
  HEARTBEAT_INTERVAL: "60"
  XAMOPS_SERVER_URL: "https://live.xamops.com"
  CLUSTER_NAME: "<CLUSTER_NAME>"
  CLOUD_ACCOUNT_ID: "<CLOUD_ACCOUNT_ID>"
  # New in-cluster collectors (Tier 1)
  COLLECTOR_K8S_RESOURCES: "true"
  COLLECTOR_PROMETHEUS_METRICS: "true"
  PROMETHEUS_URL: "http://prometheus-server.monitoring.svc.cluster.local:9090"
  # Legacy collector disabled (replaced by k8s-resources)
  COLLECTOR_METRICS: "false"
  # Security scanners
  COLLECTOR_TRIVY: "true"
  COLLECTOR_KUBESCAPE: "true"
  COLLECTOR_KARPENTER: "true"
  COLLECTOR_OPENCOST: "true"
---
apiVersion: v1
kind: Secret
metadata:
  name: xamops-agent-secret
  namespace: xamops-system
type: Opaque
stringData:
  XAMOPS_API_KEY: "xamops_SecureAgentKey2024XamOps"
EOF`}
      />
      <div className="bg-indigo-50 border border-indigo-200 rounded p-3 text-sm text-indigo-800 mt-2">
        <i className="fas fa-lock mr-2"></i>
        The API key is a global shared key pre-configured for all clusters. Your cluster is identified by CLUSTER_NAME + CLOUD_ACCOUNT_ID.
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.3: Deploy XamOps Agent" icon="fas fa-rocket" iconColor="text-green-500">
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
    tier: observability
spec:
  replicas: 1
  selector:
    matchLabels:
      app: xamops-agent
  template:
    metadata:
      labels:
        app: xamops-agent
        tier: observability
    spec:
      serviceAccountName: xamops-agent-sa
      containers:
        - name: xamops-agent
          image: docker.io/xamops/agent:v2.0.0
          imagePullPolicy: Always
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 500m
              memory: 256Mi
          envFrom:
            - configMapRef:
                name: xamops-agent-config
            - secretRef:
                name: xamops-agent-secret
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 30
          readinessProbe:
            httpGet:
              path: /ready
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 15
      restartPolicy: Always
EOF`}
      />
      <p className="text-sm text-gray-600 mt-3 mb-2">Verify the agent is running and pushing:</p>
      <CodeBlock code={`kubectl get pods -n xamops-system
kubectl logs -n xamops-system -l app=xamops-agent --tail=50`} />
      <div className="bg-green-50 border border-green-200 rounded p-3 text-sm text-green-800 mt-2">
        <i className="fas fa-check-circle mr-2"></i>
        Look for log lines like <code>Successfully processed N items from agent for cluster</code> and <code>HTTP 200</code> from the ingest endpoint.
      </div>
    </CollapsibleSection>
  </div>
);

// ─── Part 4: Verification ─────────────────────────────────────────────────────
const Part5Tab: React.FC = () => (
  <div className="space-y-6">
    <h3 className="text-xl font-bold text-gray-800 mb-2">Part 4: Verification</h3>

    <CollapsibleSection title="Step 4.1: Verify All Pods Are Running" icon="fas fa-check-circle" iconColor="text-green-500">
      <CodeBlock code={`# Monitoring stack
kubectl get pods -n monitoring

# Falco
kubectl get pods -n falco

# OpenCost
kubectl get pods -n opencost

# Security tools
kubectl get pods -n trivy-system
kubectl get pods -n kubescape

# XamOps agent
kubectl get pods -n xamops-system`} />
      <div className="bg-green-50 border border-green-200 rounded-lg p-4 mt-4">
        <h5 className="font-semibold text-green-800 mb-2">Expected pods:</h5>
        <ul className="space-y-1 text-sm text-green-700">
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <code>prometheus-server-*</code> — Running</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <code>prometheus-node-exporter-*</code> — Running on every node</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <code>prometheus-kube-state-metrics-*</code> — Running</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <code>falco-*</code> and <code>falco-falcosidekick-*</code> — Running</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <code>opencost-*</code> — Running</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <code>trivy-operator-*</code> — Running</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <code>kubescape-*</code> — Running</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <code>xamops-agent-*</code> — Running</li>
        </ul>
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 4.2: Verify Prometheus Has Data" icon="fas fa-fire" iconColor="text-orange-500">
      <p className="text-sm text-gray-600 mb-3">Port-forward Prometheus UI to check metrics are being scraped:</p>
      <CodeBlock code={`kubectl port-forward -n monitoring svc/prometheus-server 9090:80`} />
      <p className="text-sm text-gray-600 mt-3 mb-1">Then open <code>http://localhost:9090</code> and query these metrics:</p>
      <div className="space-y-2">
        {[
          { metric: 'node_cpu_seconds_total', source: 'node-exporter', desc: 'Node CPU seconds' },
          { metric: 'container_cpu_usage_seconds_total', source: 'kubelet/cadvisor', desc: 'Container CPU usage' },
          { metric: 'kube_node_status_capacity', source: 'kube-state-metrics', desc: 'Node capacity' },
          { metric: 'kube_pod_container_status_restarts_total', source: 'kube-state-metrics', desc: 'Pod restart counts' },
          { metric: 'falco_events_total', source: 'falco-sidekick', desc: 'Falco security events' },
        ].map(({ metric, source, desc }) => (
          <div key={metric} className="flex items-center gap-3 bg-gray-50 rounded p-2 text-sm">
            <input type="checkbox" className="rounded flex-shrink-0" readOnly />
            <code className="text-orange-700 flex-shrink-0">{metric}</code>
            <span className="text-gray-500 text-xs">({source})</span>
            <span className="text-gray-600">{desc}</span>
          </div>
        ))}
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 4.3: Verify Agent Is Pushing Data" icon="fas fa-robot" iconColor="text-blue-500">
      <CodeBlock code={`# Check agent logs for successful pushes
kubectl logs -n xamops-system -l app=xamops-agent --tail=100 | grep -i "push\\|ingest\\|success"

# Expected log pattern:
# INFO: Pushing 7 batches to live.xamops.com
# INFO: Successfully processed 1250 items from agent for cluster: my-cluster`} />

      <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 mt-4">
        <h5 className="font-semibold text-blue-800 mb-2">API Endpoints:</h5>
        <div className="space-y-2 text-sm text-blue-700">
          <p><strong>Agent Ingest:</strong> <code className="bg-blue-100 px-1 rounded">POST https://live.xamops.com/api/v1/agent/ingest</code></p>
          <p><strong>Policy (autoscaler):</strong> <code className="bg-blue-100 px-1 rounded">GET https://live.xamops.com/api/v1/agent/autoscaling-policy</code></p>
        </div>
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 4.4: Check XamOps Dashboard" icon="fas fa-tachometer-alt" iconColor="text-indigo-500">
      <div className="bg-indigo-50 border border-indigo-200 rounded-lg p-4">
        <h5 className="font-semibold text-indigo-800 mb-3">After agent pushes, check these dashboard sections:</h5>
        <ul className="space-y-2 text-sm text-indigo-700">
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <strong>Cluster Overview</strong> — Node count, pod count, CPU/memory %</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <strong>Nodes tab</strong> — Per-node utilization, spot vs on-demand, ready status</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <strong>Pods tab</strong> — Pod list with status, restarts, resource usage</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <strong>Security tab</strong> — Trivy vulnerability reports + Kubescape compliance + Falco runtime events</li>
          <li className="flex items-center gap-2"><input type="checkbox" className="rounded" readOnly /> <strong>Cost tab</strong> — Per-node cost, namespace cost allocation (OpenCost), cluster total</li>
        </ul>
        <div className="bg-indigo-100 rounded p-2 mt-3 text-xs text-indigo-800">
          <i className="fas fa-clock mr-1"></i>
          First data appears within ~5 minutes of deploying the agent (PUSH_INTERVAL=300s). Cost data may take 15–20 minutes for the cost-exporter CronJob to run.
        </div>
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Variables Summary" icon="fas fa-list" iconColor="text-gray-500">
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        <div className="bg-gray-50 px-4 py-3 border-b">
          <h5 className="font-semibold text-gray-800">All variables required across the setup:</h5>
        </div>
        <div className="p-4 space-y-4">
          <div>
            <h6 className="font-semibold text-gray-700 mb-2 flex items-center gap-2">
              <i className="fas fa-robot text-green-500"></i> XamOps Agent (Part 3)
            </h6>
            <ul className="list-disc list-inside space-y-1 text-gray-700 text-sm ml-4">
              <li><code className="bg-gray-100 px-1 rounded">xamops-agent-config.yaml</code>: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span>, <span className="text-amber-600 font-medium">&lt;CLOUD_ACCOUNT_ID&gt;</span></li>
            </ul>
          </div>
          <div>
            <h6 className="font-semibold text-gray-700 mb-2 flex items-center gap-2">
              <i className="fas fa-clipboard-check text-blue-500"></i> Kubescape (Part 2, Step 2.2)
            </h6>
            <ul className="list-disc list-inside space-y-1 text-gray-700 text-sm ml-4">
              <li>Helm <code className="bg-gray-100 px-1 rounded">--set clusterName=</code>: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span> — must match XamOps agent ConfigMap exactly</li>
            </ul>
          </div>
          <div>
            <h6 className="font-semibold text-gray-700 mb-2 flex items-center gap-2">
              <i className="fas fa-dollar-sign text-green-500"></i> Cost Exporter (Part 1, Step 1.6)
            </h6>
            <ul className="list-disc list-inside space-y-1 text-gray-700 text-sm ml-4">
              <li><code className="bg-gray-100 px-1 rounded">cost-exporter deployment</code>: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span>, <span className="text-amber-600 font-medium">&lt;REGION&gt;</span>, <span className="text-amber-600 font-medium">&lt;AWS_ACCOUNT_ID&gt;</span></li>
              <li><code className="bg-gray-100 px-1 rounded">IAM trust policy</code>: <span className="text-amber-600 font-medium">&lt;AWS_ACCOUNT_ID&gt;</span></li>
            </ul>
          </div>
          <div>
            <h6 className="font-semibold text-gray-700 mb-2 flex items-center gap-2">
              <i className="fas fa-coins text-yellow-500"></i> OpenCost (Part 1, Step 1.7)
            </h6>
            <ul className="list-disc list-inside space-y-1 text-gray-700 text-sm ml-4">
              <li><code className="bg-gray-100 px-1 rounded">opencost-values.yaml</code>: <span className="text-amber-600 font-medium">&lt;REGION&gt;</span></li>
            </ul>
          </div>
          <div className="bg-blue-50 border border-blue-200 rounded p-3 text-sm text-blue-800">
            <i className="fas fa-info-circle mr-2"></i>
            <strong>Tip:</strong> Use the same <code>&lt;CLUSTER_NAME&gt;</code> value across all steps — agent ConfigMap, Kubescape install, and cost-exporter. Mismatches will cause data to appear under the wrong cluster.
          </div>
        </div>
      </div>
    </CollapsibleSection>
  </div>
);

export default EksIntegrationModal;
