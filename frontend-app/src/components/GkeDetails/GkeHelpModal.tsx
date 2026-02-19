/**
 * GKE Help Modal
 *
 * Setup guide for connecting Prometheus to a GKE cluster in XamOps.
 */

import React, { useState } from 'react';

interface GkeHelpModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const GkeHelpModal: React.FC<GkeHelpModalProps> = ({ isOpen, onClose }) => {
  const [copiedStep, setCopiedStep] = useState<string | null>(null);

  const copyToClipboard = (text: string, stepId: string) => {
    navigator.clipboard.writeText(text);
    setCopiedStep(stepId);
    setTimeout(() => setCopiedStep(null), 2000);
  };

  const CopyButton: React.FC<{ text: string; id: string }> = ({ text, id }) => (
    <button
      onClick={() => copyToClipboard(text, id)}
      className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
    >
      {copiedStep === id ? (
        <><i className="fas fa-check"></i> Copied!</>
      ) : (
        <><i className="far fa-copy"></i> Copy</>
      )}
    </button>
  );

  if (!isOpen) return null;

  const installCmd = `helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm install kube-prom prometheus-community/kube-prometheus-stack \\
  -n monitoring --create-namespace \\
  --set prometheus.service.type=LoadBalancer`;

  const getIpCmd = `kubectl get svc -n monitoring kube-prom-kube-prometheus-prometheus`;

  const opencostCmd = `helm repo add opencost https://opencost.github.io/opencost-helm-chart
helm install opencost opencost/opencost -n opencost --create-namespace \\
  --set opencost.prometheus.internal.serviceName=kube-prom-kube-prometheus-prometheus \\
  --set opencost.prometheus.internal.namespaceName=monitoring`;

  const falcoCmd = `helm repo add falcosecurity https://falcosecurity.github.io/charts
helm install falco falcosecurity/falco -n falco --create-namespace \\
  --set falcosidekick.enabled=true`;

  const falcoServiceMonitorCmd = `helm upgrade falco falcosecurity/falco -n falco \\
  --reuse-values \\
  --set falcosidekick.serviceMonitor.enabled=true`;

  const falcoKubePromCmd = `helm upgrade kube-prom prometheus-community/kube-prometheus-stack -n monitoring \\
  --reuse-values \\
  --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false`;

  const kubescapeCmd = `helm repo add kubescape https://kubescape.github.io/helm-charts/
helm repo update
helm install kubescape kubescape/kubescape-operator \\
  -n kubescape --create-namespace \\
  --set clusterName=<your-cluster-name>`;

  const trivyInstallCmd = `helm repo add aqua https://aquasecurity.github.io/helm-charts/
helm repo update
helm install trivy-operator aqua/trivy-operator \\
  -n trivy-system --create-namespace`;

  const trivyServiceMonitorCmd = `helm upgrade trivy-operator aqua/trivy-operator -n trivy-system \\
  --reuse-values \\
  --set serviceMonitor.enabled=true`;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div onClick={onClose} className="fixed inset-0 bg-black/40"></div>
      <div
        className="relative bg-white rounded-2xl p-8 w-full max-w-3xl mx-auto max-h-[90vh] overflow-y-auto shadow-2xl z-50"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between pb-4 border-b border-gray-200">
          <div className="flex items-center space-x-4">
            <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center">
              <i className="fas fa-book text-white text-xl"></i>
            </div>
            <div>
              <h3 className="font-bold text-xl text-gray-800">GKE MONITORING SETUP</h3>
              <p className="text-sm text-gray-500">Connect Prometheus to this cluster in 3 steps</p>
            </div>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-2xl leading-none">
            &times;
          </button>
        </div>

        {/* Content */}
        <div className="mt-6 space-y-8">

          {/* Step 1 */}
          <section>
            <div className="flex items-center gap-3 mb-3">
              <div className="w-7 h-7 rounded-full bg-indigo-600 text-white text-sm font-bold flex items-center justify-center flex-shrink-0">1</div>
              <h2 className="text-base font-bold text-gray-800 uppercase tracking-wide">Install kube-prometheus-stack</h2>
            </div>
            <p className="text-sm text-gray-600 mb-3 ml-10">
              Run these commands in <strong>Google Cloud Shell</strong> (or any terminal with kubectl access to your cluster).
              This single Helm chart installs Prometheus, Alertmanager, kube-state-metrics, and node-exporter.
            </p>
            <div className="relative ml-10">
              <div className="absolute top-2 right-2 z-10">
                <CopyButton text={installCmd} id="step1" />
              </div>
              <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700 pr-20">
{installCmd}
              </pre>
            </div>
          </section>

          {/* Step 2 */}
          <section>
            <div className="flex items-center gap-3 mb-3">
              <div className="w-7 h-7 rounded-full bg-indigo-600 text-white text-sm font-bold flex items-center justify-center flex-shrink-0">2</div>
              <h2 className="text-base font-bold text-gray-800 uppercase tracking-wide">Get Your Prometheus URL</h2>
            </div>
            <p className="text-sm text-gray-600 mb-3 ml-10">
              Wait about 1–2 minutes for the LoadBalancer IP to be assigned, then run:
            </p>
            <div className="relative ml-10">
              <div className="absolute top-2 right-2 z-10">
                <CopyButton text={getIpCmd} id="step2" />
              </div>
              <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700 pr-20">
{getIpCmd}
              </pre>
            </div>
            <div className="ml-10 mt-3 bg-blue-50 border-l-4 border-blue-400 p-3 rounded-r text-sm text-blue-700">
              Look for the <strong>EXTERNAL-IP</strong> column. Your Prometheus URL will be:<br />
              <code className="font-mono bg-blue-100 px-1 rounded">http://&lt;EXTERNAL-IP&gt;:9090</code>
            </div>
          </section>

          {/* Step 3 */}
          <section>
            <div className="flex items-center gap-3 mb-3">
              <div className="w-7 h-7 rounded-full bg-indigo-600 text-white text-sm font-bold flex items-center justify-center flex-shrink-0">3</div>
              <h2 className="text-base font-bold text-gray-800 uppercase tracking-wide">Connect to XamOps</h2>
            </div>
            <div className="ml-10 space-y-2 text-sm text-gray-700">
              <div className="flex items-start gap-2">
                <span className="w-5 h-5 rounded-full bg-gray-200 text-gray-600 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">a</span>
                <span>Navigate back to the <strong>GKE Clusters</strong> list (← button above).</span>
              </div>
              <div className="flex items-start gap-2">
                <span className="w-5 h-5 rounded-full bg-gray-200 text-gray-600 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">b</span>
                <span>Find this cluster and click the <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-yellow-400 text-yellow-900 text-xs font-bold rounded">⚡ Enable Monitoring</span> button.</span>
              </div>
              <div className="flex items-start gap-2">
                <span className="w-5 h-5 rounded-full bg-gray-200 text-gray-600 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">c</span>
                <span>Paste your Prometheus URL (e.g. <code className="font-mono bg-gray-100 px-1 rounded">http://34.x.x.x:9090</code>) and click OK.</span>
              </div>
              <div className="flex items-start gap-2">
                <span className="w-5 h-5 rounded-full bg-gray-200 text-gray-600 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">d</span>
                <span>Return to Details — all tabs will now show live metrics.</span>
              </div>
            </div>
          </section>

          <hr className="border-gray-200" />

          {/* Optional Addons */}
          <section>
            <h2 className="text-sm font-bold text-gray-500 uppercase tracking-widest mb-4">Optional Add-ons</h2>

            {/* OpenCost */}
            <div className="mb-5">
              <div className="flex items-center gap-2 mb-2">
                <i className="fas fa-dollar-sign text-green-500"></i>
                <span className="font-semibold text-gray-800 text-sm">OpenCost — enables the Cost tab</span>
              </div>
              <div className="relative">
                <div className="absolute top-2 right-2 z-10">
                  <CopyButton text={opencostCmd} id="opencost" />
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700 pr-20">
{opencostCmd}
                </pre>
              </div>
            </div>

            {/* Falco */}
            <div>
              <div className="flex items-center gap-2 mb-2">
                <i className="fas fa-shield-alt text-red-500"></i>
                <span className="font-semibold text-gray-800 text-sm">Falco — enables the Security tab</span>
              </div>
              <p className="text-xs text-gray-500 mb-2">Step 1 of 3 — Install Falco with Falcosidekick:</p>
              <div className="relative mb-3">
                <div className="absolute top-2 right-2 z-10">
                  <CopyButton text={falcoCmd} id="falco" />
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700 pr-20">
{falcoCmd}
                </pre>
              </div>
              <p className="text-xs text-gray-500 mb-2">Step 2 of 3 — Enable Falcosidekick's Prometheus ServiceMonitor:</p>
              <div className="relative mb-3">
                <div className="absolute top-2 right-2 z-10">
                  <CopyButton text={falcoServiceMonitorCmd} id="falco-sm" />
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700 pr-20">
{falcoServiceMonitorCmd}
                </pre>
              </div>
              <p className="text-xs text-gray-500 mb-2">Step 3 of 3 — Allow kube-prometheus-stack to discover all ServiceMonitors:</p>
              <div className="relative mb-2">
                <div className="absolute top-2 right-2 z-10">
                  <CopyButton text={falcoKubePromCmd} id="falco-kp" />
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700 pr-20">
{falcoKubePromCmd}
                </pre>
              </div>
              <p className="text-xs text-gray-400 mt-1">Wait ~60 seconds, then click Refresh on the Security tab — events will appear.</p>
            </div>

            {/* Kubescape */}
            <div className="mt-5">
              <div className="flex items-center gap-2 mb-2">
                <i className="fas fa-clipboard-check text-indigo-500"></i>
                <span className="font-semibold text-gray-800 text-sm">Kubescape — enables compliance scanning</span>
              </div>
              <p className="text-xs text-gray-500 mb-2">Replace <code className="bg-gray-100 px-1 rounded">&lt;your-cluster-name&gt;</code> with your GKE cluster name:</p>
              <div className="relative">
                <div className="absolute top-2 right-2 z-10">
                  <CopyButton text={kubescapeCmd} id="kubescape" />
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700 pr-20">
{kubescapeCmd}
                </pre>
              </div>
            </div>

            {/* Trivy */}
            <div className="mt-5">
              <div className="flex items-center gap-2 mb-2">
                <i className="fas fa-bug text-orange-500"></i>
                <span className="font-semibold text-gray-800 text-sm">Trivy Operator — enables image vulnerability scanning</span>
              </div>
              <p className="text-xs text-gray-500 mb-2">Step 1 of 2 — Install Trivy Operator:</p>
              <div className="relative mb-3">
                <div className="absolute top-2 right-2 z-10">
                  <CopyButton text={trivyInstallCmd} id="trivy-install" />
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700 pr-20">
{trivyInstallCmd}
                </pre>
              </div>
              <p className="text-xs text-gray-500 mb-2">Step 2 of 2 — Enable ServiceMonitor (uses the kube-prom discovery already set up for Falco):</p>
              <div className="relative">
                <div className="absolute top-2 right-2 z-10">
                  <CopyButton text={trivyServiceMonitorCmd} id="trivy-sm" />
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700 pr-20">
{trivyServiceMonitorCmd}
                </pre>
              </div>
              <p className="text-xs text-gray-400 mt-1">Wait ~2 minutes for the initial scan, then click Refresh on the Security tab.</p>
            </div>
          </section>

          {/* Success note */}
          <div className="bg-green-50 border border-green-200 rounded-xl p-4 flex items-start gap-3">
            <i className="fas fa-check-circle text-green-500 text-lg mt-0.5"></i>
            <div className="text-sm text-green-700">
              <strong className="text-green-800">All set!</strong> Once connected, XamOps will display
              real-time node metrics, pod health, workload status, and resource utilization across all 8 tabs.
            </div>
          </div>

        </div>
      </div>
    </div>
  );
};

export default GkeHelpModal;
