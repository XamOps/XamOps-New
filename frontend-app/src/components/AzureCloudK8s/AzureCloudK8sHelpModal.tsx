import React, { useState } from 'react';
import { DialogRoot, DialogPanel } from '../ui';

interface AzureCloudK8sHelpModalProps {
  isOpen: boolean;
  onClose: () => void;
}

interface CodeBlockProps {
  code: string;
  language?: string;
}

const CodeBlock: React.FC<CodeBlockProps> = ({ code }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="relative group">
      <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg text-xs font-mono overflow-x-auto whitespace-pre-wrap">
        {code}
      </pre>
      <button
        onClick={handleCopy}
        className="absolute top-2 right-2 px-2 py-1 text-xs bg-gray-700 text-gray-300 rounded hover:bg-gray-600 transition-colors opacity-0 group-hover:opacity-100"
      >
        {copied ? (
          <><i className="fas fa-check mr-1 text-green-400"></i>Copied</>
        ) : (
          <><i className="fas fa-copy mr-1"></i>Copy</>
        )}
      </button>
    </div>
  );
};

const AzureCloudK8sHelpModal: React.FC<AzureCloudK8sHelpModalProps> = ({ isOpen, onClose }) => {
  const [activePhase, setActivePhase] = useState<1 | 2 | 3>(1);

  const getCredentialsCmd = `az aks get-credentials --resource-group <your-resource-group> --name <your-cluster-name>

# Verify connection
kubectl get nodes`;

  const installPrometheusCmd = `helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

helm install kube-prom prometheus-community/kube-prometheus-stack -n monitoring --create-namespace --set prometheus.service.type=LoadBalancer --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false`;

  const getPrometheusUrlCmd = `# Wait ~2 minutes after install, then:
kubectl get svc -n monitoring kube-prom-kube-prometheus-prometheus

# Look for the EXTERNAL-IP column
# Your Prometheus URL = http://<EXTERNAL-IP>:9090`;

  const opencostCmd = `helm repo add opencost https://opencost.github.io/opencost-helm-chart
helm repo update

helm install opencost opencost/opencost -n opencost --create-namespace --set opencost.prometheus.internal.serviceName=kube-prom-kube-prometheus-prometheus --set opencost.prometheus.internal.namespaceName=monitoring`;

  const falcoCmd = `helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update

helm install falco falcosecurity/falco -n falco --create-namespace --set falcosidekick.enabled=true`;

  const falcoServiceMonitorCmd = `helm upgrade falco falcosecurity/falco -n falco --reuse-values --set falcosidekick.serviceMonitor.enabled=true --set "falcosidekick.serviceMonitor.labels.release=kube-prom"`;

  const kubescapeCmd = `helm repo add kubescape https://kubescape.github.io/helm-charts/
helm repo update

helm install kubescape kubescape/kubescape-operator -n kubescape --create-namespace --set clusterName=<your-cluster-name>`;

  const trivyCmd = `helm repo add aqua https://aquasecurity.github.io/helm-charts/
helm repo update

helm install trivy-operator aqua/trivy-operator -n trivy-system --create-namespace`;

  const trivyServiceMonitorCmd = `helm upgrade trivy-operator aqua/trivy-operator -n trivy-system --reuse-values --set serviceMonitor.enabled=true`;

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-3xl">
      <DialogPanel
        title={
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-cyan-600 rounded-xl flex items-center justify-center">
              <i className="fas fa-dharmachakra text-white text-lg"></i>
            </div>
            <div>
              <div className="text-xl font-bold text-gray-800">AKS Setup Guide</div>
              <div className="text-sm text-gray-500">Connect Azure Kubernetes Service to XamOps</div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={
          <div className="flex items-center justify-between w-full">
            <button
              onClick={() => setActivePhase(prev => Math.max(1, prev - 1) as 1 | 2 | 3)}
              disabled={activePhase === 1}
              className="px-4 py-2 text-sm text-gray-600 font-medium rounded-lg hover:bg-gray-200 disabled:opacity-30 transition-colors"
            >
              <i className="fas fa-arrow-left mr-1"></i> Previous
            </button>
            <span className="text-xs text-gray-400">Phase {activePhase} of 3</span>
            <button
              onClick={() => activePhase < 3 ? setActivePhase(prev => (prev + 1) as 1 | 2 | 3) : onClose()}
              className="px-4 py-2 text-sm bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors"
            >
              {activePhase < 3 ? <>Next <i className="fas fa-arrow-right ml-1"></i></> : 'Done'}
            </button>
          </div>
        }
      >
        {/* Phase tabs */}
        <div className="flex gap-1 mb-4 p-1 bg-gray-100 rounded-lg">
          {([1, 2, 3] as const).map((phase) => (
            <button
              key={phase}
              onClick={() => setActivePhase(phase)}
              className={`flex-1 py-2 px-3 text-xs font-semibold rounded-lg transition-all ${
                activePhase === phase
                  ? 'bg-blue-600 text-white shadow-sm'
                  : 'text-gray-600 hover:bg-gray-200'
              }`}
            >
              {phase === 1 && 'Phase 1: Connect to Cluster'}
              {phase === 2 && 'Phase 2: Install Prometheus'}
              {phase === 3 && 'Phase 3: Add-ons (Optional)'}
            </button>
          ))}
        </div>

        {/* Content */}
        <div className="overflow-y-auto max-h-[55vh] space-y-6">
          {activePhase === 1 && (
            <>
              <div className="bg-blue-50 border border-blue-200 rounded-xl p-4">
                <div className="flex items-center gap-2 mb-2">
                  <i className="fas fa-info-circle text-blue-500"></i>
                  <span className="text-sm font-semibold text-blue-800">Prerequisites</span>
                </div>
                <ul className="text-sm text-blue-700 space-y-1">
                  <li>• Azure CLI installed and logged in (<code className="bg-blue-100 px-1 rounded">az login</code>)</li>
                  <li>• kubectl installed</li>
                  <li>• Helm 3 installed</li>
                  <li>• Your cluster is in "Succeeded" provisioning state</li>
                </ul>
              </div>

              <div>
                <h3 className="font-bold text-gray-800 mb-2 flex items-center gap-2">
                  <span className="w-6 h-6 bg-blue-600 text-white rounded-full flex items-center justify-center text-xs">1</span>
                  Get AKS credentials
                </h3>
                <p className="text-sm text-gray-600 mb-3">
                  Download the kubeconfig for your AKS cluster so kubectl can connect to it.
                </p>
                <CodeBlock code={getCredentialsCmd} />
              </div>
            </>
          )}

          {activePhase === 2 && (
            <>
              <div>
                <h3 className="font-bold text-gray-800 mb-2 flex items-center gap-2">
                  <span className="w-6 h-6 bg-blue-600 text-white rounded-full flex items-center justify-center text-xs">1</span>
                  Install kube-prometheus-stack
                </h3>
                <p className="text-sm text-gray-600 mb-3">
                  This single command installs Prometheus, Grafana, kube-state-metrics, and node-exporter.
                  The LoadBalancer service type assigns a public IP so XamOps can reach Prometheus.
                </p>
                <CodeBlock code={installPrometheusCmd} />
              </div>

              <div>
                <h3 className="font-bold text-gray-800 mb-2 flex items-center gap-2">
                  <span className="w-6 h-6 bg-blue-600 text-white rounded-full flex items-center justify-center text-xs">2</span>
                  Get your Prometheus URL
                </h3>
                <CodeBlock code={getPrometheusUrlCmd} />
              </div>

              <div className="bg-green-50 border border-green-200 rounded-xl p-4">
                <div className="flex items-center gap-2 mb-2">
                  <i className="fas fa-check-circle text-green-500"></i>
                  <span className="text-sm font-semibold text-green-800">Step 3: Enable Monitoring in XamOps</span>
                </div>
                <ol className="text-sm text-green-700 space-y-1 list-decimal list-inside">
                  <li>Find your cluster in the AKS Clusters table above</li>
                  <li>Click the yellow <strong>Enable Monitoring</strong> button</li>
                  <li>Enter your Prometheus URL: <code className="bg-green-100 px-1 rounded">http://{"<EXTERNAL-IP>"}:9090</code></li>
                  <li>Click OK — XamOps will connect and start showing data within seconds</li>
                </ol>
              </div>
            </>
          )}

          {activePhase === 3 && (
            <>
              <p className="text-sm text-gray-600">
                These are optional add-ons that enable additional tabs in the AKS Details view.
              </p>

              {/* OpenCost */}
              <div className="border border-gray-200 rounded-xl p-4">
                <h3 className="font-bold text-gray-800 mb-1 flex items-center gap-2">
                  <i className="fas fa-dollar-sign text-green-500"></i>
                  OpenCost — for the Cost tab
                </h3>
                <p className="text-sm text-gray-600 mb-3">
                  Real-time cost allocation per namespace and workload.
                </p>
                <CodeBlock code={opencostCmd} />
              </div>

              {/* Falco */}
              <div className="border border-gray-200 rounded-xl p-4">
                <h3 className="font-bold text-gray-800 mb-1 flex items-center gap-2">
                  <i className="fas fa-shield-alt text-red-500"></i>
                  Falco — for the Security tab
                </h3>
                <p className="text-sm text-gray-600 mb-3">
                  Runtime security monitoring for Kubernetes workloads.
                </p>
                <div className="space-y-3">
                  <div>
                    <p className="text-xs text-gray-500 mb-1 font-semibold">Step 1 — Install Falco:</p>
                    <CodeBlock code={falcoCmd} />
                  </div>
                  <div>
                    <p className="text-xs text-gray-500 mb-1 font-semibold">Step 2 — Enable Prometheus ServiceMonitor:</p>
                    <CodeBlock code={falcoServiceMonitorCmd} />
                  </div>
                </div>
              </div>

              {/* Kubescape */}
              <div className="border border-gray-200 rounded-xl p-4">
                <h3 className="font-bold text-gray-800 mb-1 flex items-center gap-2">
                  <i className="fas fa-shield-check text-purple-500"></i>
                  Kubescape — for the Security tab (Compliance)
                </h3>
                <p className="text-sm text-gray-600 mb-3">
                  CIS benchmark and compliance scanning for Kubernetes resources.
                  Replace <code className="bg-gray-100 px-1 rounded">&lt;your-cluster-name&gt;</code> with your AKS cluster name.
                </p>
                <CodeBlock code={kubescapeCmd} />
              </div>

              {/* Trivy */}
              <div className="border border-gray-200 rounded-xl p-4">
                <h3 className="font-bold text-gray-800 mb-1 flex items-center gap-2">
                  <i className="fas fa-bug text-orange-500"></i>
                  Trivy — for the Security tab (Vulnerabilities)
                </h3>
                <p className="text-sm text-gray-600 mb-3">
                  Container image vulnerability scanning for Kubernetes workloads.
                </p>
                <div className="space-y-3">
                  <div>
                    <p className="text-xs text-gray-500 mb-1 font-semibold">Step 1 — Install Trivy Operator:</p>
                    <CodeBlock code={trivyCmd} />
                  </div>
                  <div>
                    <p className="text-xs text-gray-500 mb-1 font-semibold">Step 2 — Enable Prometheus ServiceMonitor:</p>
                    <CodeBlock code={trivyServiceMonitorCmd} />
                  </div>
                </div>
              </div>
            </>
          )}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default AzureCloudK8sHelpModal;
