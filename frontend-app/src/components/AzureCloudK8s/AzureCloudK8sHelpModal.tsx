import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import { DialogRoot, DialogPanel } from '../ui';

interface AzureCloudK8sHelpModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const CodeBlock: React.FC<{ code: string; title?: string }> = ({ code, title }) => {
  const [copied, setCopied] = useState(false);
  const handleCopy = async () => {
    try { await navigator.clipboard.writeText(code); setCopied(true); setTimeout(() => setCopied(false), 2000); }
    catch {}
  };
  return (
    <div className="relative group my-3">
      {title && (
        <div className="bg-[#1a1d27] text-[rgba(255,255,255,0.5)] px-4 py-2 text-[11px] font-medium rounded-t-xl border-b border-[rgba(255,255,255,0.06)]">
          {title}
        </div>
      )}
      <div className={`bg-[#1a1d27] text-[#e2e8f0] p-4 font-mono text-[12px] overflow-x-auto ${title ? 'rounded-b-xl' : 'rounded-xl'}`}>
        <button onClick={handleCopy}
          className="absolute top-2.5 right-2.5 flex items-center gap-1.5 px-2.5 py-1 bg-[rgba(255,255,255,0.1)] hover:bg-[rgba(255,255,255,0.18)] text-white text-[11px] font-semibold rounded-lg border border-[rgba(255,255,255,0.12)] transition-colors opacity-0 group-hover:opacity-100">
          <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={12} className={copied ? 'text-emerald-400' : ''} />
          {copied ? 'Copied!' : 'Copy'}
        </button>
        <pre className="whitespace-pre leading-relaxed">{code}</pre>
      </div>
    </div>
  );
};

const CollapsibleSection: React.FC<{ title: string; icon: string; iconColor?: string; defaultOpen?: boolean; children: React.ReactNode }> = ({
  title, icon, iconColor = '#6366F1', defaultOpen = false, children,
}) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);
  return (
    <div className="border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
      <button onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center justify-between p-4 bg-[rgba(0,0,0,0.02)] hover:bg-[rgba(0,0,0,0.04)] transition-colors text-left">
        <div className="flex items-center gap-3">
          <Icon icon={icon} width={15} color={iconColor} />
          <span className="font-semibold text-[rgba(0,0,0,0.75)] text-[13px]">{title}</span>
        </div>
        <Icon icon="mdi:chevron-down" width={16} color="rgba(0,0,0,0.35)" className={`transition-transform ${isOpen ? 'rotate-180' : ''}`} />
      </button>
      {isOpen && <div className="p-4 space-y-3 bg-white">{children}</div>}
    </div>
  );
};

const VarNote: React.FC<{ vars: string[] }> = ({ vars }) => (
  <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mb-3 flex items-start gap-2">
    <Icon icon="mdi:alert-outline" width={14} color="#D97706" className="mt-0.5 flex-shrink-0" />
    <p className="text-[12px] text-amber-800">
      <strong>Variables to replace:</strong> {vars.join(', ')}
    </p>
  </div>
);

const AzureCloudK8sHelpModal: React.FC<AzureCloudK8sHelpModalProps> = ({ isOpen, onClose }) => {
  const [activeTab, setActiveTab] = useState<'overview' | 'part1' | 'part2' | 'part3' | 'part4'>('overview');

  const tabs = [
    { id: 'overview', label: 'Overview',                icon: 'mdi:information-outline' },
    { id: 'part1',    label: 'Part 1: Monitoring Stack', icon: 'mdi:chart-line' },
    { id: 'part2',    label: 'Part 2: Security Tools',   icon: 'mdi:shield-check-outline' },
    { id: 'part3',    label: 'Part 3: XamOps Agent',     icon: 'mdi:robot-outline' },
    { id: 'part4',    label: 'Part 4: Verification',     icon: 'mdi:check-circle-outline' },
  ] as const;

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-6xl">
      <DialogPanel
        title={
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-indigo-600 rounded-xl flex items-center justify-center">
              <Icon icon="mdi:book-open-outline" width={20} color="white" />
            </div>
            <div>
              <div className="font-bold text-[15px] text-[rgba(0,0,0,0.88)]">AKS Setup Guide — XamOps Agent</div>
              <div className="text-[12px] text-[rgba(0,0,0,0.45)]">In-cluster monitoring · Push-only · No inbound connections required</div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={
          <button onClick={onClose}
            className="px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-xl transition-colors">
            Close Guide
          </button>
        }
      >
        {/* Tab Navigation */}
        <div className="flex border-b border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] overflow-x-auto -mx-6 px-6 mb-4">
          {tabs.map(tab => (
            <button key={tab.id} onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 px-4 py-3 text-[12px] font-semibold whitespace-nowrap transition-colors border-b-2 ${
                activeTab === tab.id
                  ? 'text-indigo-600 border-indigo-600 bg-white'
                  : 'text-[rgba(0,0,0,0.45)] border-transparent hover:text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.02)]'}`}>
              <Icon icon={tab.icon} width={13} />
              {tab.label}
            </button>
          ))}
        </div>

        <div className="overflow-y-auto max-h-[60vh]" style={{ overscrollBehavior: 'contain' }}>
          {activeTab === 'overview' && <OverviewTab />}
          {activeTab === 'part1'    && <Part1Tab />}
          {activeTab === 'part2'    && <Part2Tab />}
          {activeTab === 'part3'    && <Part3Tab />}
          {activeTab === 'part4'    && <Part4Tab />}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

const OverviewTab: React.FC = () => (
  <div className="space-y-5">
    <div className="p-5 bg-blue-50 border border-blue-100 rounded-xl">
      <h3 className="text-[14px] font-bold text-blue-800 mb-2 flex items-center gap-2">
        <Icon icon="mdi:shield-check-outline" width={15} color="#1D4ED8" />
        Push-Only Architecture — Zero Inbound Required
      </h3>
      <p className="text-[13px] text-blue-700">
        The XamOps agent runs inside your AKS cluster and pushes data outbound to{' '}
        <code className="bg-blue-100 px-1 rounded">live.xamops.com:443</code>.
        Your cluster never receives inbound connections from XamOps. Works for private AKS clusters,
        VNet-isolated environments, and any Azure network security posture.
      </p>
    </div>

    <div className="bg-[#1a1d27] text-[#86efac] p-5 rounded-xl font-mono text-[12px] overflow-x-auto">
      <pre>{`YOUR AKS CLUSTER
├── monitoring namespace
│   ├── prometheus-server        ← scrapes all in-cluster metrics
│   ├── kube-state-metrics       ← kube_* metrics
│   ├── node-exporter (DaemonSet)← node_cpu, node_memory
│   ├── falco + falcosidekick    ← security events → Prometheus
│   └── opencost                 ← cost allocation
├── default namespace
│   └── cost-exporter            ← Azure VM pricing metrics
├── xamops-system namespace
│   └── xamops-agent             ← reads K8s API + queries Prometheus
│       │                           PUSH → live.xamops.com:443
│       └── collectors: k8s-resources, prometheus-metrics, trivy, kubescape, opencost`}</pre>
    </div>

    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-5">
      <h4 className="font-bold text-[rgba(0,0,0,0.75)] mb-4 flex items-center gap-2 text-[13px]">
        <Icon icon="mdi:sync" width={14} color="#6366F1" /> How Data Flows
      </h4>
      <div className="space-y-3">
        {[
          { n: '1', color: 'bg-blue-500',   text: 'Prometheus scrapes KSM, node-exporter, cadvisor, Falco, OpenCost, and Azure cost-exporter — all inside the cluster.' },
          { n: '2', color: 'bg-indigo-500', text: 'XamOps Agent queries Prometheus in-cluster and reads the Kubernetes API (nodes, pods, deployments, HPAs, PVCs, events…).' },
          { n: '3', color: 'bg-purple-500', text: 'Agent POSTs a JSON payload to live.xamops.com/api/v1/agent/ingest (HTTPS, outbound port 443).' },
          { n: '4', color: 'bg-emerald-500',text: 'XamOps backend stores data keyed by cluster. Dashboard reads from cache — no direct cluster access needed.' },
        ].map(({ n, color, text }) => (
          <div key={n} className="flex items-start gap-3">
            <div className={`${color} text-white rounded-full w-6 h-6 flex items-center justify-center text-[11px] font-bold flex-shrink-0 mt-0.5`}>{n}</div>
            <p className="text-[13px] text-[rgba(0,0,0,0.65)]">{text}</p>
          </div>
        ))}
      </div>
    </div>

    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
      {[
        { part: 'Part 1', color: 'blue',   icon: 'mdi:chart-line',             title: 'Monitoring Stack',  desc: 'Prometheus + KSM + node-exporter + Falco + Cost Exporter + OpenCost' },
        { part: 'Part 2', color: 'purple', icon: 'mdi:shield-check-outline',   title: 'Security Tools',    desc: 'Trivy (image vuln scanning) + Kubescape (compliance)' },
        { part: 'Part 3', color: 'emerald',icon: 'mdi:robot-outline',           title: 'XamOps Agent',      desc: 'Read-only observability agent — pushes cluster data to XamOps every 5 min' },
        { part: 'Part 4', color: 'indigo', icon: 'mdi:check-circle-outline',   title: 'Verification',      desc: 'Confirm all pods are running and data appears in XamOps dashboard' },
      ].map(({ part, icon, title, desc }) => (
        <div key={part} className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-4">
          <p className="text-[11px] font-bold text-[rgba(0,0,0,0.35)] mb-1">{part}</p>
          <div className="flex items-center gap-1.5 mb-1">
            <Icon icon={icon} width={13} color="#6366F1" />
            <p className="font-semibold text-[rgba(0,0,0,0.75)] text-[13px]">{title}</p>
          </div>
          <p className="text-[11px] text-[rgba(0,0,0,0.45)]">{desc}</p>
        </div>
      ))}
    </div>
  </div>
);

const Part1Tab: React.FC = () => (
  <div className="space-y-4">
    <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Part 1: In-Cluster Monitoring Stack</h3>
    <div className="p-3 bg-blue-50 border border-blue-100 rounded-xl text-[12px] text-blue-800 flex items-start gap-2">
      <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
      All monitoring tools run in the <code className="bg-blue-100 px-1 rounded">monitoring</code> namespace. Prometheus scrapes KSM, node-exporter, Falco, OpenCost, and the Azure cost-exporter.
    </div>

    <CollapsibleSection title="Step 1.1: Get AKS Credentials" icon="mdi:key-outline" iconColor="#0891B2">
      <VarNote vars={['<RESOURCE_GROUP>', '<CLUSTER_NAME>']} />
      <CodeBlock code={`az aks get-credentials \\
  --resource-group <RESOURCE_GROUP> \\
  --name <CLUSTER_NAME>

kubectl get nodes`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.2: Create Namespaces" icon="mdi:folder-outline">
      <CodeBlock code={`kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace falco      --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace opencost   --dry-run=client -o yaml | kubectl apply -f -`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.3: Install Prometheus" icon="mdi:fire" iconColor="#EA580C">
      <CodeBlock code={`helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update`} />
      <CodeBlock title="prometheus-values.yaml" code={`cat > prometheus-values.yaml << 'EOF'
server:
  persistentVolume:
    enabled: true
    size: 20Gi
  retention: "15d"
alertmanager:
  enabled: false
pushgateway:
  enabled: false
nodeExporter:
  enabled: true
kube-state-metrics:
  enabled: true
extraScrapeConfigs: |
  - job_name: falco-sidekick
    static_configs:
      - targets: ['falco-falcosidekick.falco.svc.cluster.local:2801']
  - job_name: opencost
    static_configs:
      - targets: ['opencost.opencost.svc.cluster.local:9003']
  - job_name: cost-exporter
    static_configs:
      - targets: ['cost-exporter.default.svc.cluster.local:8080']
EOF`} />
      <CodeBlock code={`helm upgrade --install prometheus prometheus-community/prometheus \\
  --namespace monitoring \\
  --values prometheus-values.yaml \\
  --set server.persistentVolume.storageClass=managed-csi`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.4: Install Falco + FalcoSidekick" icon="mdi:eye-outline" iconColor="#7C3AED">
      <CodeBlock code={`helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update

helm upgrade --install falco falcosecurity/falco \\
  --namespace falco \\
  --set driver.kind=ebpf \\
  --set falco.json_output=true \\
  --set falcosidekick.enabled=true \\
  --set falcosidekick.config.prometheus.enabled=true \\
  --set falcosidekick.service.port=2801 \\
  --set tolerations[0].operator=Exists`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.5: Install Azure Cost Exporter" icon="mdi:currency-usd" iconColor="#16A34A">
      <VarNote vars={['<CLUSTER_NAME>', '<AZURE_SUBSCRIPTION_ID>', '<RESOURCE_GROUP>']} />
      <CodeBlock title="Deploy cost-exporter" code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: Service
metadata:
  name: cost-exporter
  namespace: default
spec:
  selector:
    app: cost-exporter
  ports:
    - port: 8080
      targetPort: 8080
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cost-exporter
  namespace: default
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
      containers:
        - name: cost-exporter
          image: docker.io/xamops/cost-exporter-azure:latest
          ports:
            - containerPort: 8080
          env:
            - name: CLUSTER_NAME
              value: "<CLUSTER_NAME>"
            - name: AZURE_SUBSCRIPTION_ID
              value: "<AZURE_SUBSCRIPTION_ID>"
            - name: RESOURCE_GROUP
              value: "<RESOURCE_GROUP>"
            - name: CLOUD_PROVIDER
              value: "AZURE"
            - name: PORT
              value: "8080"
EOF`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.6: Install OpenCost" icon="mdi:cash-multiple" iconColor="#D97706">
      <VarNote vars={['<AZURE_SUBSCRIPTION_ID>']} />
      <CodeBlock title="opencost-values.yaml" code={`cat > opencost-values.yaml << 'EOF'
opencost:
  prometheus:
    internal:
      enabled: true
      serviceName: prometheus-server
      namespaceName: monitoring
      port: 80
  cloudCost:
    enabled: true
  exporter:
    azure:
      subscriptionId: "<AZURE_SUBSCRIPTION_ID>"
EOF`} />
      <CodeBlock code={`helm repo add opencost https://opencost.github.io/opencost-helm-chart
helm repo update
helm upgrade --install opencost opencost/opencost \\
  --namespace opencost \\
  --values opencost-values.yaml`} />
    </CollapsibleSection>
  </div>
);

const Part2Tab: React.FC = () => (
  <div className="space-y-4">
    <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Part 2: Security Tools</h3>
    <div className="p-3 bg-purple-50 border border-purple-100 rounded-xl text-[12px] text-purple-800 flex items-start gap-2">
      <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
      Trivy creates <code className="bg-purple-100 px-1 rounded">VulnerabilityReport</code> CRDs. Kubescape creates <code className="bg-purple-100 px-1 rounded">WorkloadConfigurationScanSummary</code> CRDs. The XamOps Agent reads these and pushes them to the dashboard.
    </div>

    <CollapsibleSection title="Step 2.1: Install Trivy Operator" icon="mdi:bug-outline" iconColor="#DC2626">
      <CodeBlock code={`helm repo add aquasecurity https://aquasecurity.github.io/helm-charts/
helm repo update

helm upgrade --install trivy-operator aquasecurity/trivy-operator \\
  --namespace trivy-system --create-namespace \\
  --set trivy.ignoreUnfixed=false \\
  --set trivy.severity=UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL \\
  --set operator.vulnerabilityScanner.enabled=true \\
  --set operator.configAuditScannerEnabled=true

# Verify (wait 5–10 min for initial scan)
kubectl get vulnerabilityreports -A --no-headers | head -20`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.2: Install Kubescape" icon="mdi:clipboard-check-outline" iconColor="#3B82F6">
      <VarNote vars={['<CLUSTER_NAME>']} />
      <CodeBlock code={`helm repo add kubescape https://kubescape.github.io/helm-charts/
helm repo update

helm upgrade --install kubescape kubescape/kubescape-operator \\
  --namespace kubescape --create-namespace \\
  --set clusterName=<CLUSTER_NAME> \\
  --set capabilities.continuousScan=enable \\
  --set storage.enabled=true

# REQUIRED: without an account, kubescape auto-enables cloud submission, which
# fails on 'chmod /home/nonroot/.kubescape: operation not permitted' and aborts
# the scan before results reach the in-cluster storage. Keeping it local skips
# that path entirely. Re-apply after every 'helm upgrade' — the chart has no
# env passthrough for this component.
# Do NOT set --set account=<uuid> instead: an account makes kubescape ship
# results to Kubescape Cloud and stop writing them to in-cluster storage.
kubectl set env deployment/kubescape -n kubescape KS_KEEP_LOCAL=true
kubectl rollout status deploy/kubescape -n kubescape

# Verify (wait 3–5 min)
kubectl get pods -n kubescape
kubectl get workloadconfigurationscansummaries -A --no-headers | head -10`} />
    </CollapsibleSection>
  </div>
);

const Part3Tab: React.FC = () => (
  <div className="space-y-4">
    <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Part 3: XamOps Observability Agent</h3>
    <div className="p-3 bg-emerald-50 border border-emerald-100 rounded-xl text-[12px] text-emerald-800 flex items-start gap-2">
      <Icon icon="mdi:robot-outline" width={14} className="mt-0.5 flex-shrink-0" />
      Same agent binary as EKS and GKE. Set <code className="bg-emerald-100 px-1 rounded">CLOUD_PROVIDER: "AZURE"</code> and <code className="bg-emerald-100 px-1 rounded">COLLECTOR_KARPENTER: "false"</code> (Karpenter is AWS-only).
    </div>

    <CollapsibleSection title="Step 3.1: Create RBAC (read-only)" icon="mdi:key-outline" iconColor="#D97706">
      <CodeBlock title="xamops-agent-rbac.yaml" code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: Namespace
metadata:
  name: xamops-system
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
  - apiGroups: [""]
    resources: [pods, nodes, namespaces, services, endpoints, persistentvolumes,
                persistentvolumeclaims, resourcequotas, limitranges, serviceaccounts,
                events, configmaps]
    verbs: [get, list, watch]
  - apiGroups: [apps]
    resources: [deployments, daemonsets, statefulsets, replicasets]
    verbs: [get, list, watch]
  - apiGroups: [batch]
    resources: [jobs, cronjobs]
    verbs: [get, list, watch]
  - apiGroups: [networking.k8s.io]
    resources: [ingresses, networkpolicies]
    verbs: [get, list, watch]
  - apiGroups: [autoscaling]
    resources: [horizontalpodautoscalers]
    verbs: [get, list, watch]
  - apiGroups: [autoscaling.k8s.io]
    resources: [verticalpodautoscalers]
    verbs: [get, list, watch]
  - apiGroups: [metrics.k8s.io]
    resources: [nodes, pods]
    verbs: [get, list]
  - apiGroups: [storage.k8s.io]
    resources: [storageclasses]
    verbs: [get, list, watch]
  - apiGroups: [apiextensions.k8s.io]
    resources: [customresourcedefinitions]
    verbs: [get, list]
  - apiGroups: [aquasecurity.github.io]
    resources: [vulnerabilityreports, configauditreports, exposedsecretreports,
                rbacassessmentreports, infraassessmentreports, clustercompliancereports,
                clusterconfigauditreports, clusterinfraassessmentreports,
                clusterrbacassessmentreports]
    verbs: [get, list, watch]
  - apiGroups: [spdx.softwarecomposition.kubescape.io]
    resources: [configurationscansummaries, vulnerabilitymanifestsummaries,
                workloadconfigurationscans, workloadconfigurationscansummaries,
                vulnerabilitymanifests, applicationprofiles, applicationactivities,
                networkneighborhoods, generatednetworkpolicies, knownservers,
                openvulnerabilityexchangecontainers, seccompprofiles]
    verbs: [get, list, watch]
  - apiGroups: [kubescape.io]
    resources: [workloadconfigurationscans, workloadconfigurationscansummaries]
    verbs: [get, list, watch]
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
EOF`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.2: Create ConfigMap and Secret" icon="mdi:cog-outline" iconColor="#3B82F6">
      <VarNote vars={['<CLUSTER_NAME>', '<AZURE_SUBSCRIPTION_ID>']} />
      <CodeBlock title="xamops-agent-config.yaml" code={`kubectl apply -f - << 'EOF'
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
  CLOUD_ACCOUNT_ID: "<AZURE_SUBSCRIPTION_ID>"
  CLOUD_PROVIDER: "AZURE"
  COLLECTOR_K8S_RESOURCES: "true"
  COLLECTOR_PROMETHEUS_METRICS: "true"
  # The prometheus-community chart's server Service listens on 80, not 9090.
  PROMETHEUS_URL: "http://prometheus-server.monitoring.svc.cluster.local:80"
  COLLECTOR_TRIVY: "true"
  COLLECTOR_KUBESCAPE: "true"
  COLLECTOR_OPENCOST: "true"
  COLLECTOR_KARPENTER: "false"
---
apiVersion: v1
kind: Secret
metadata:
  name: xamops-agent-secret
  namespace: xamops-system
type: Opaque
stringData:
  XAMOPS_API_KEY: "xamops_SecureAgentKey2024XamOps"
EOF`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.3: Deploy XamOps Agent" icon="mdi:rocket-launch-outline" iconColor="#16A34A">
      <CodeBlock title="xamops-agent-deployment.yaml" code={`kubectl apply -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: xamops-agent
  namespace: xamops-system
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
EOF`} />
      <CodeBlock code={`kubectl get pods -n xamops-system
kubectl logs -n xamops-system -l app=xamops-agent --tail=50`} />
    </CollapsibleSection>
  </div>
);

const Part4Tab: React.FC = () => (
  <div className="space-y-4">
    <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Part 4: Verification</h3>

    <CollapsibleSection title="Step 4.1: Verify All Pods Are Running" icon="mdi:check-circle-outline" iconColor="#16A34A">
      <CodeBlock code={`kubectl get pods -n monitoring
kubectl get pods -n falco
kubectl get pods -n opencost
kubectl get pods -n trivy-system
kubectl get pods -n kubescape
kubectl get pods -n xamops-system`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 4.2: Verify Prometheus Has Data" icon="mdi:fire" iconColor="#EA580C">
      <CodeBlock code={`kubectl port-forward -n monitoring svc/prometheus-server 9090:80`} />
      <p className="text-[12px] text-[rgba(0,0,0,0.55)] mt-2">Open <code>http://localhost:9090</code> and query:</p>
      <div className="space-y-1.5 mt-2">
        {[
          'node_cpu_seconds_total',
          'container_cpu_usage_seconds_total',
          'kube_node_status_capacity',
          'falco_events_total',
          'xamops_aks_node_hourly_cost',
        ].map(m => (
          <div key={m} className="flex items-center gap-2 bg-[rgba(0,0,0,0.02)] rounded-lg p-2 text-[12px]">
            <Icon icon="mdi:check" width={12} color="#16A34A" />
            <code className="text-orange-700">{m}</code>
          </div>
        ))}
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 4.3: Verify Agent Is Pushing Data" icon="mdi:robot-outline" iconColor="#3B82F6">
      <CodeBlock code={`kubectl logs -n xamops-system -l app=xamops-agent --tail=100 | grep -i "push\\|ingest\\|success"
# Expected: INFO: Successfully processed N items from agent for cluster: <CLUSTER_NAME>`} />
    </CollapsibleSection>

    <div className="p-4 bg-indigo-50 border border-indigo-100 rounded-xl">
      <h5 className="font-semibold text-indigo-800 mb-3 flex items-center gap-2 text-[13px]">
        <Icon icon="mdi:view-dashboard-outline" width={14} color="#4F46E5" />
        After agent pushes, check these dashboard sections:
      </h5>
      <ul className="space-y-1.5 text-[12px] text-indigo-700">
        {['Cluster Overview — Node count, pod count, CPU/memory %',
          'Nodes tab — Per-node utilization, agent pool info',
          'Pods tab — Pod list with status, restarts, resource usage',
          'Security tab — Trivy CVEs + Kubescape compliance + Falco events',
          'Cost tab — Azure VM node cost, namespace cost allocation (OpenCost)'].map(item => (
          <li key={item} className="flex items-start gap-2">
            <Icon icon="mdi:check-circle-outline" width={13} color="#4F46E5" className="mt-0.5 flex-shrink-0" />
            {item}
          </li>
        ))}
      </ul>
      <p className="text-[11px] text-indigo-500 mt-3 flex items-center gap-1">
        <Icon icon="mdi:clock-outline" width={12} />
        First data appears within ~5 min. Cost data may take 15–20 min.
      </p>
    </div>
  </div>
);

export default AzureCloudK8sHelpModal;
