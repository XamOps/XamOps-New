/**
 * EKS Integration Guide Modal — Agent-Based Architecture
 *
 * In-cluster monitoring stack + XamOps push-only agents.
 * No external EC2 instance required — Prometheus runs inside the cluster.
 */

import React, { useState, useEffect, useMemo } from 'react';
import { DialogRoot, DialogPanel } from '../ui';
import { useAgentInstallConfig } from '../../hooks/useAgentInstallConfig';
import {
  GuideTokensContext,
  GuideVariableBar,
  GuideVarNote,
  substituteTokens,
  useGuideTokens,
  type GuideTokenValues,
  type GuideVarSpec,
} from '../K8sGuides/guideVars';

interface EksIntegrationModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Prefills the Cluster Name field — passed from the EKS details page. */
  clusterName?: string;
  /** Prefills the Region field. */
  region?: string;
}

/*
 * AWS Account ID is deliberately not prefilled. The `accountIds` URL parameter on the details
 * page is XamOps's own cloud-account row id, not the 12-digit AWS account number the agent
 * ConfigMap needs. Substituting it would produce a plausible-looking value that keys the
 * cluster wrongly — worse than an empty field the user has to fill.
 */

/**
 * Tokens that appear across this guide's command blocks.
 *
 * The first four are typed by the user once and applied everywhere. The last two are filled
 * from the running backend profile, because hardcoding them meant a user on local or UAT
 * copied a manifest that pointed their cluster at production.
 *
 * Note `<AWS_ACCOUNT_ID>` is used for the agent ConfigMap too. It was previously written as
 * `<CLOUD_ACCOUNT_ID>` there and `<AWS_ACCOUNT_ID>` everywhere else, which reads as two
 * different values when it is one.
 */
export const EKS_PLACEHOLDERS = {
  clusterName: '<CLUSTER_NAME>',
  region: '<REGION>',
  accountId: '<AWS_ACCOUNT_ID>',
  nodegroupRole: '<NODEGROUP_ROLE_NAME>',
  serverUrl: '<XAMOPS_SERVER_URL>',
  apiKey: '<XAMOPS_API_KEY>',
} as const;

const EKS_VAR_SPECS: GuideVarSpec[] = [
  {
    token: EKS_PLACEHOLDERS.clusterName,
    label: 'Cluster Name',
    placeholder: 'my-prod-cluster',
    hint: 'Plain EKS cluster name — not the ARN or kubectl context',
  },
  {
    token: EKS_PLACEHOLDERS.region,
    label: 'Region',
    placeholder: 'ap-south-1',
  },
  {
    token: EKS_PLACEHOLDERS.accountId,
    label: 'AWS Account ID',
    placeholder: '123456789012',
    hint: '12 digits',
  },
  {
    token: EKS_PLACEHOLDERS.nodegroupRole,
    label: 'Node Group IAM Role Name',
    placeholder: 'eksctl-my-cluster-nodegroup-NodeInstanceRole',
    hint: 'aws eks describe-nodegroup … --query "nodegroup.nodeRole"',
  },
];

/** Host portion of a base URL, for prose like "outbound HTTPS to <host>:443". */
function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url.replace(/^https?:\/\//, '').replace(/\/.*$/, '');
  }
}

/** Reads the backend URL resolved for the current environment out of the token map. */
function useServerUrl(): string {
  return useGuideTokens()[EKS_PLACEHOLDERS.serverUrl] || 'https://live.xamops.com';
}

interface CodeBlockProps {
  code: string;
  title?: string;
}

const CodeBlock: React.FC<CodeBlockProps> = ({ code, title }) => {
  const [copied, setCopied] = useState(false);
  const tokens = useGuideTokens();
  // Substituted centrally here so no command block can be missed, and so Copy always
  // yields exactly what is displayed.
  const resolved = substituteTokens(code, tokens);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(resolved);
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
        <pre className="whitespace-pre">{resolved}</pre>
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

/**
 * Reports only the variables a step still needs. Backed by the shared implementation so it
 * disappears once the bar at the top is filled, rather than telling the user to replace
 * values that the block below already shows substituted.
 */
const VariableNote: React.FC<{ variables: string[] }> = ({ variables }) => (
  <GuideVarNote tokens={variables} />
);

const EksIntegrationModal: React.FC<EksIntegrationModalProps> = ({
  isOpen,
  onClose,
  clusterName,
  region,
}) => {
  const [activeTab, setActiveTab] = useState<'overview' | 'part1' | 'part2' | 'part3' | 'part4' | 'part5'>('overview');

  // Only requested once the guide is actually opened.
  const { config } = useAgentInstallConfig(isOpen);
  const [tunnelUrl, setTunnelUrl] = useState('');

  const [vars, setVars] = useState<GuideTokenValues>({
    [EKS_PLACEHOLDERS.clusterName]: '',
    [EKS_PLACEHOLDERS.region]: '',
    [EKS_PLACEHOLDERS.accountId]: '',
    [EKS_PLACEHOLDERS.nodegroupRole]: '',
  });

  // Prefill from the details page when it knows the cluster, without clobbering anything the
  // user has already typed.
  useEffect(() => {
    setVars(prev => ({
      ...prev,
      [EKS_PLACEHOLDERS.clusterName]: prev[EKS_PLACEHOLDERS.clusterName] || clusterName || '',
      [EKS_PLACEHOLDERS.region]: prev[EKS_PLACEHOLDERS.region] || region || '',
    }));
  }, [clusterName, region]);

  // A tunnel URL, when supplied, wins over the backend's own address: on local dev the
  // backend URL is one the cluster cannot resolve.
  const effectiveServerUrl = tunnelUrl.trim() || config.serverUrl;

  const tokens = useMemo<GuideTokenValues>(() => ({
    ...vars,
    [EKS_PLACEHOLDERS.serverUrl]: effectiveServerUrl,
    [EKS_PLACEHOLDERS.apiKey]: config.apiKey,
  }), [vars, effectiveServerUrl, config.apiKey]);

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
        <GuideTokensContext.Provider value={tokens}>
          <GuideVariableBar
            specs={EKS_VAR_SPECS}
            values={vars}
            onChange={setVars}
            tunnel={
              config.reachableFromCluster
                ? undefined
                : { url: tunnelUrl, onChange: setTunnelUrl, backendUrl: config.serverUrl }
            }
          />

          <div className="overflow-y-auto max-h-[60vh]" style={{ overscrollBehavior: 'contain' }}>
            {activeTab === 'overview' && <OverviewTab />}
            {activeTab === 'part1' && <Part1Tab />}
            {activeTab === 'part2' && <Part2Tab />}
            {activeTab === 'part3' && <Part3Tab />}
            {activeTab === 'part5' && <Part5Tab />}
          </div>
        </GuideTokensContext.Provider>
      </DialogPanel>
    </DialogRoot>
  );
};

// ─── Overview Tab ──────────────────────────────────────────────────────────────
const OverviewTab: React.FC = () => {
  const serverUrl = useServerUrl();
  const host = hostOf(serverUrl);

  return (
  <div className="space-y-6">
    {/* Architecture banner */}
    <div className="bg-gradient-to-r from-blue-600 to-indigo-700 rounded-xl p-6 text-white">
      <h3 className="text-xl font-bold mb-2 flex items-center gap-2">
        <i className="fas fa-shield-alt"></i> Push-Only Architecture — Zero Inbound Required
      </h3>
      <p className="text-blue-100 text-sm">
        Both agents run inside your cluster and push data outbound to <code className="bg-blue-800 px-1 rounded">{host}:443</code>.
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
├── falco namespace
│   └── falco + falcosidekick    ← security events
├── opencost namespace
│   └── opencost                 ← cost allocation
├── xamops-system namespace
│   └── xamops-agent             ← reads K8s API + queries Prometheus
│       │                           PUSH → ${host}:443
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
          { n: '1', color: 'bg-blue-500', text: 'Prometheus scrapes KSM, node-exporter, cadvisor, Falco, and OpenCost — all inside the cluster.' },
          { n: '2', color: 'bg-green-500', text: 'XamOps Agent queries Prometheus in-cluster and reads the Kubernetes API (nodes, pods, deployments, HPAs, PVCs, events…).' },
          { n: '3', color: 'bg-purple-500', text: `Agent POSTs a JSON payload to ${host}/api/v1/agent/ingest (HTTPS, outbound port 443).` },
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
            <li><i className="fas fa-check-circle mr-2 text-green-500"></i>Outbound HTTPS (port 443) from cluster nodes to <code className="bg-green-100 px-1 rounded">{host}</code></li>
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
        { part: 'Part 1', color: 'blue', icon: 'fas fa-chart-line', title: 'Monitoring Stack', desc: 'Prometheus + KSM + Metrics Server + Falco + OpenCost (all in-cluster)' },
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
        <li>Outbound HTTPS (443) from cluster to <code className="bg-gray-100 px-1 rounded">{host}</code></li>
        <li>Cluster name and AWS Account ID (12 digits) — needed in ConfigMaps</li>
      </ul>
    </div>
  </div>
  );
};

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
        Extra scrape configs cover Falco and OpenCost.
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

# Extra scrape jobs for Falco and OpenCost
extraScrapeConfigs: |
  - job_name: falco-sidekick
    scrape_interval: 30s
    static_configs:
      - targets: ['falco-falcosidekick.falco.svc.cluster.local:2801']
  - job_name: opencost
    scrape_interval: 60s
    static_configs:
      - targets: ['opencost.opencost.svc.cluster.local:9003']
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
  --set 'args[0]=--kubelet-insecure-tls'`} />
      <p className="text-xs text-gray-500 mt-1">Or via manifest: <code>kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml</code></p>
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.5: Install Falco + FalcoSidekick" icon="fas fa-eye" iconColor="text-purple-500">
      <p className="text-sm text-gray-600 mb-3">
        Falco is a CNCF runtime security tool — it detects syscall-level threats in real time (container escapes, privilege escalations, unexpected network connections). FalcoSidekick exposes events as Prometheus metrics (<code>falcosecurity_falcosidekick_falco_events_total</code>) which the XamOps agent collects.
      </p>
      <CodeBlock code={`helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update

helm upgrade --install falco falcosecurity/falco \\
  --namespace falco \\
  --set driver.kind=modern_ebpf \\
  --set falco.json_output=true \\
  --set falcosidekick.enabled=true \\
  --set falcosidekick.webui.enabled=false \\
  --set falcosidekick.config.prometheus.enabled=true \\
  --set falcosidekick.service.port=2801 \\
  --set falcosidekick.replicaCount=1 \\
  --set 'tolerations[0].operator=Exists'`} />

      <div className="bg-amber-50 border border-amber-200 rounded p-3 text-sm text-amber-800 mt-2">
        <i className="fas fa-info-circle mr-2"></i>
        <strong>Driver compatibility:</strong>
        <ul className="list-disc list-inside mt-1 space-y-1">
          <li><strong>EKS with AL2 (kernel 5.10+) or AL2023 (kernel 6.1+):</strong> <code>modern_ebpf</code> is the right choice — it uses CO-RE and needs no kernel headers.</li>
          <li><strong>Do not use <code>driver.kind=ebpf</code>.</strong> Falco 0.44 removed the legacy eBPF probe, and the chart now rejects that value at render time, so the install fails before anything is applied.</li>
          <li><strong>Kernels older than 5.8:</strong> <code>modern_ebpf</code> is unavailable; fall back to <code>--set driver.kind=module</code> (requires kernel headers on nodes).</li>
        </ul>
      </div>

      <div className="bg-blue-50 border border-blue-200 rounded p-3 text-sm text-blue-800 mt-2">
        <i className="fas fa-info-circle mr-2"></i>
        <strong>Why pin falcosidekick to one replica?</strong> The Prometheus scrape job targets the sidekick
        Service by DNS. With more than one replica the event counters are split across pods and each scrape
        lands on whichever one the Service picks, so the totals jump around instead of accumulating.
      </div>

      <p className="text-sm text-gray-600 mt-3 mb-1">Verify Falco is running on all nodes:</p>
      <CodeBlock code={`# Falco runs as a DaemonSet — one pod per node
kubectl get pods -n falco -o wide   # all should be Running

# FalcoSidekick should be Running (1-2 replicas)
kubectl get pods -n falco -l app.kubernetes.io/name=falcosidekick

# Verify Prometheus metrics endpoint is reachable from within cluster
kubectl run -it --rm debug --image=curlimages/curl:latest --restart=Never -- \\
  curl -s http://falco-falcosidekick.falco.svc.cluster.local:2801/metrics | grep falco_events | head -5

# The metric is named falcosecurity_falcosidekick_falco_events_total, with labels
# k8s_ns_name / k8s_pod_name. A count of 0 right after install is normal — Falco only
# emits an event when a rule actually fires.`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.6: Install OpenCost" icon="fas fa-coins" iconColor="text-yellow-500">
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
  --set 'trivy.severity=UNKNOWN\\,LOW\\,MEDIUM\\,HIGH\\,CRITICAL' \\
  --set operator.scanJobTimeout=10m \\
  --set operator.vulnerabilityScanner.enabled=true \\
  --set operator.configAuditScannerEnabled=true \\
  --set operator.rbacAssessmentScannerEnabled=true`} />

      <div className="bg-amber-50 border border-amber-200 rounded p-3 text-sm text-amber-800 mt-3">
        <i className="fas fa-exclamation-triangle mr-2"></i>
        <strong>Note the quoting on <code>trivy.severity</code>.</strong> Helm reads an unescaped comma as a
        separator between <code>key=value</code> pairs. Passing the severity list to <code>--set</code> with plain
        commas therefore parses as one key plus four malformed ones and the install errors out. The commas must be
        backslash-escaped and the whole argument single-quoted so the shell hands the backslashes to Helm intact.
      </div>

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
const Part3Tab: React.FC = () => {
  const serverUrl = useServerUrl();
  const host = hostOf(serverUrl);

  return (
  <div className="space-y-6">
    <h3 className="text-xl font-bold text-gray-800 mb-2">Part 3: XamOps Observability Agent</h3>
    <div className="bg-green-50 border-l-4 border-green-500 p-4 rounded-r-lg">
      <p className="text-sm text-green-800">
        <i className="fas fa-robot mr-2"></i>
        <strong>Read-only agent</strong> — runs as a single Deployment with minimal CPU/memory.
        Collects full K8s resource snapshots + Prometheus metrics + security scan results,
        then pushes to <code className="bg-green-100 px-1 rounded">{host}/api/v1/agent/ingest</code> every 5 minutes.
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
      <VariableNote variables={[EKS_PLACEHOLDERS.clusterName, EKS_PLACEHOLDERS.accountId]} />
      <div className="bg-indigo-50 border border-indigo-200 rounded-lg p-3 mb-3 text-sm text-indigo-800">
        <i className="fas fa-server mr-2"></i>
        <code className="bg-indigo-100 px-1 rounded">XAMOPS_SERVER_URL</code> and the API key below are filled in for
        the XamOps instance you are currently signed in to (<code className="bg-indigo-100 px-1 rounded">{serverUrl}</code>),
        so a cluster set up from a local or UAT instance does not end up reporting into production.
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
  XAMOPS_SERVER_URL: "<XAMOPS_SERVER_URL>"
  CLUSTER_NAME: "<CLUSTER_NAME>"
  CLOUD_ACCOUNT_ID: "<AWS_ACCOUNT_ID>"
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
  XAMOPS_API_KEY: "<XAMOPS_API_KEY>"
EOF`}
      />
      <div className="bg-indigo-50 border border-indigo-200 rounded p-3 text-sm text-indigo-800 mt-2">
        <i className="fas fa-lock mr-2"></i>
        The API key is a global shared key pre-configured for all clusters in this environment. Your cluster is
        identified by CLUSTER_NAME + CLOUD_ACCOUNT_ID, so those two values must match what you used for Kubescape.
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
          image: docker.io/xamops/agent:v2.1.0
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
        In the <strong>agent</strong> log expect <code>Starting XamOps Agent</code> on boot, then a successful push
        roughly every 5 minutes reporting an HTTP 200 from the ingest endpoint.
      </div>
      <div className="bg-amber-50 border border-amber-200 rounded p-3 text-sm text-amber-800 mt-2">
        <i className="fas fa-exclamation-triangle mr-2"></i>
        <code>Successfully processed N items from agent for cluster</code> is logged by the XamOps
        <strong> server</strong>, not the agent, so grepping the agent's own logs for it will always come up empty.
        Confirm ingestion from the dashboard (Step 4.4) instead.
      </div>
    </CollapsibleSection>
  </div>
  );
};

// ─── Part 4: Verification ─────────────────────────────────────────────────────
const Part5Tab: React.FC = () => {
  const serverUrl = useServerUrl();

  return (
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
          { metric: 'falcosecurity_falcosidekick_falco_events_total', source: 'falco-sidekick', desc: 'Falco security events (0 until a rule fires)' },
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
kubectl logs -n xamops-system -l app=xamops-agent --tail=100 | grep -iE "push|ingest|error|status"

# Confirm which build is running and that config was accepted:
kubectl logs -n xamops-system -l app=xamops-agent | grep "Starting XamOps Agent"

# Confirm the agent can actually reach this XamOps instance from inside the cluster.
# A 401/404 here means it reached a server but the URL or key is wrong; a timeout or
# DNS failure means egress is blocked.
kubectl run -it --rm neturl --image=curlimages/curl:latest --restart=Never -- \\
  curl -s -o /dev/null -w "%{http_code}\\n" <XAMOPS_SERVER_URL>/actuator/health`} />

      <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 mt-4">
        <h5 className="font-semibold text-blue-800 mb-2">API Endpoints:</h5>
        <div className="space-y-2 text-sm text-blue-700">
          <p><strong>Agent Ingest:</strong> <code className="bg-blue-100 px-1 rounded">POST {serverUrl}/api/v1/agent/ingest</code></p>
          <p><strong>Policy (autoscaler):</strong> <code className="bg-blue-100 px-1 rounded">GET {serverUrl}/api/v1/agent/autoscaling-policy</code></p>
        </div>
      </div>

      <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mt-3 text-sm text-amber-800">
        <i className="fas fa-exclamation-triangle mr-2"></i>
        The agent serves its own health endpoint on port 8080. If <code className="bg-amber-100 px-1 rounded">XAMOPS_SERVER_URL</code>
        {' '}points at <code className="bg-amber-100 px-1 rounded">localhost:8080</code> the agent POSTs to itself and logs a
        confusing HTTP 404 rather than a connection error — so a 404 in the logs is worth checking the URL over.
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
          First data appears within ~5 minutes of deploying the agent (PUSH_INTERVAL=300s). Cost data takes longer — OpenCost needs roughly 15–30 minutes of Prometheus history before its allocation figures settle.
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
              <i className="fas fa-hdd text-yellow-600"></i> EBS CSI Driver (Part 1, Step 1.2)
            </h6>
            <ul className="list-disc list-inside space-y-1 text-gray-700 text-sm ml-4">
              <li><code className="bg-gray-100 px-1 rounded">aws iam attach-role-policy</code>: <span className="text-amber-600 font-medium">&lt;NODEGROUP_ROLE_NAME&gt;</span></li>
              <li><code className="bg-gray-100 px-1 rounded">aws eks create-addon</code>: <span className="text-amber-600 font-medium">&lt;CLUSTER_NAME&gt;</span>, <span className="text-amber-600 font-medium">&lt;REGION&gt;</span></li>
            </ul>
          </div>
          <div>
            <h6 className="font-semibold text-gray-700 mb-2 flex items-center gap-2">
              <i className="fas fa-coins text-yellow-500"></i> OpenCost (Part 1, Step 1.6)
            </h6>
            <ul className="list-disc list-inside space-y-1 text-gray-700 text-sm ml-4">
              <li><code className="bg-gray-100 px-1 rounded">opencost-values.yaml</code>: <span className="text-amber-600 font-medium">&lt;REGION&gt;</span></li>
            </ul>
          </div>
          <div className="bg-blue-50 border border-blue-200 rounded p-3 text-sm text-blue-800">
            <i className="fas fa-info-circle mr-2"></i>
            <strong>You do not need to type any of these by hand.</strong> Fill the fields at the top of this guide
            once and every command block above is substituted for you. That matters most for
            <code className="bg-blue-100 px-1 rounded">&lt;CLUSTER_NAME&gt;</code>: it has to match between the
            Kubescape install and the agent ConfigMap, and a mismatch silently files the data under a different
            cluster rather than failing.
          </div>
        </div>
      </div>
    </CollapsibleSection>
  </div>
  );
};

export default EksIntegrationModal;
