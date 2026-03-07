/**
 * GCP CloudK8s Help Modal Component
 *
 * Comprehensive setup guide for GKE observability
 */

import React, { useState } from 'react';
import { DialogRoot, DialogPanel } from '../ui';

interface GcpCloudK8sHelpModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const GcpCloudK8sHelpModal: React.FC<GcpCloudK8sHelpModalProps> = ({ isOpen, onClose }) => {
  const [copiedStep, setCopiedStep] = useState<string | null>(null);

  const copyToClipboard = (text: string, stepId: string) => {
    navigator.clipboard.writeText(text);
    setCopiedStep(stepId);
    setTimeout(() => setCopiedStep(null), 2000);
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-4xl">
      <DialogPanel
        title={
          <div className="flex items-center space-x-4">
            <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center">
              <i className="fas fa-rocket text-white text-xl"></i>
            </div>
            <div>
              <div className="font-bold text-xl text-gray-800">GKE OBSERVABILITY SETUP GUIDE</div>
              <div className="text-sm text-gray-600">
                Install monitoring stack on GKE → Forward metrics to monitoring instance
              </div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={<button onClick={onClose} className="px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors">Done</button>}
      >
        <div className="overflow-y-auto max-h-[60vh] pr-2 space-y-6">

          {/* Quick Start (Recommended) */}
          <div className="bg-indigo-50 border border-indigo-200 rounded-xl p-5">
            <div className="flex items-center gap-2 mb-3">
              <i className="fas fa-bolt text-indigo-600"></i>
              <h2 className="text-base font-extrabold text-indigo-800 uppercase tracking-wide">Quick Start (Recommended)</h2>
            </div>
            <p className="text-sm text-indigo-700 mb-3">
              Install <strong>kube-prometheus-stack</strong> — one Helm chart that bundles Prometheus, kube-state-metrics, and node-exporter.
              Run in <strong>Google Cloud Shell</strong>.
            </p>

            <div className="relative mb-3">
              <div className="absolute top-2 right-2 z-10">
                <button
                  onClick={() => copyToClipboard(`helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm install kube-prom prometheus-community/kube-prometheus-stack \\
  -n monitoring --create-namespace \\
  --set prometheus.service.type=LoadBalancer`, 'qs-install')}
                  className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                >
                  {copiedStep === 'qs-install' ? (
                    <><i className="fas fa-check"></i> Copied!</>
                  ) : (
                    <><i className="far fa-copy"></i> Copy</>
                  )}
                </button>
              </div>
              <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm install kube-prom prometheus-community/kube-prometheus-stack \\
  -n monitoring --create-namespace \\
  --set prometheus.service.type=LoadBalancer`}
              </pre>
            </div>

            <p className="text-sm text-indigo-700 mb-2">Get the Prometheus external IP (wait ~2 min after install):</p>
            <div className="relative">
              <div className="absolute top-2 right-2 z-10">
                <button
                  onClick={() => copyToClipboard(`kubectl get svc -n monitoring kube-prom-kube-prometheus-prometheus`, 'qs-ip')}
                  className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                >
                  {copiedStep === 'qs-ip' ? (
                    <><i className="fas fa-check"></i> Copied!</>
                  ) : (
                    <><i className="far fa-copy"></i> Copy</>
                  )}
                </button>
              </div>
              <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`kubectl get svc -n monitoring kube-prom-kube-prometheus-prometheus
# Look for EXTERNAL-IP → your Prometheus URL = http://<EXTERNAL-IP>:9090`}
              </pre>
            </div>
          </div>

          {/* Connect to XamOps */}
          <div className="bg-green-50 border border-green-200 rounded-xl p-5">
            <div className="flex items-center gap-2 mb-3">
              <i className="fas fa-plug text-green-600"></i>
              <h2 className="text-base font-extrabold text-green-800 uppercase tracking-wide">Connect to XamOps</h2>
            </div>
            <ol className="space-y-2 text-sm text-green-700">
              <li className="flex items-start gap-2">
                <span className="w-5 h-5 rounded-full bg-green-200 text-green-800 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">1</span>
                <span>Find your cluster in the GKE Clusters list above.</span>
              </li>
              <li className="flex items-start gap-2">
                <span className="w-5 h-5 rounded-full bg-green-200 text-green-800 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">2</span>
                <span>Click the <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-yellow-400 text-yellow-900 text-xs font-bold rounded">⚡ Enable Monitoring</span> button on the cluster row.</span>
              </li>
              <li className="flex items-start gap-2">
                <span className="w-5 h-5 rounded-full bg-green-200 text-green-800 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">3</span>
                <span>Enter your Prometheus URL: <code className="font-mono bg-green-100 px-1 rounded">http://&lt;EXTERNAL-IP&gt;:9090</code></span>
              </li>
              <li className="flex items-start gap-2">
                <span className="w-5 h-5 rounded-full bg-green-200 text-green-800 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">4</span>
                <span>Click <strong>OK</strong> — XamOps will connect and start showing live metrics in the cluster details page.</span>
              </li>
            </ol>
          </div>

          <hr className="border-gray-200" />

          {/* Overview */}
          <div className="bg-blue-50 border-l-4 border-blue-500 p-4 rounded-r">
            <p className="text-sm text-blue-700">
              <strong>Alternative (Manual Install):</strong> If you prefer to install components individually — or need Falco for security monitoring — follow the steps below.
            </p>
          </div>

          <hr className="border-gray-200" />

          {/* Phase 1: Prerequisites */}
          <section>
            <h2 className="text-lg font-extrabold text-gray-900 mb-4 uppercase tracking-wide">
              Phase 1: Prerequisites & Setup
            </h2>

            <div className="mb-6">
              <span className="location-badge loc-cloudshell">
                <i className="fab fa-google mr-1"></i> Google Cloud Shell
              </span>
              <p className="doc-p mt-2">Run these commands in Google Cloud Shell to configure your environment.</p>

              <h3 className="doc-h3">Step 1: Configure Environment Variables</h3>
              <div className="relative group my-4">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`# Set your GKE cluster details
export PROJECT_ID="your-gcp-project-id"
export CLUSTER_NAME="your-gke-cluster-name"
export CLUSTER_REGION="us-central1"
export CLUSTER_ZONE="us-central1-a"  # For zonal clusters

# Authenticate
gcloud config set project $PROJECT_ID
gcloud container clusters get-credentials $CLUSTER_NAME --region $CLUSTER_REGION`, 'step1')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step1' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`# Set your GKE cluster details
export PROJECT_ID="your-gcp-project-id"
export CLUSTER_NAME="your-gke-cluster-name"
export CLUSTER_REGION="us-central1"
export CLUSTER_ZONE="us-central1-a"  # For zonal clusters

# Authenticate
gcloud config set project $PROJECT_ID
gcloud container clusters get-credentials $CLUSTER_NAME --region $CLUSTER_REGION`}
                </pre>
              </div>
            </div>
          </section>

          <hr className="border-gray-200" />

          {/* Phase 2: Install Monitoring Components */}
          <section>
            <h2 className="text-lg font-extrabold text-gray-900 mb-4 uppercase tracking-wide">
              Phase 2: Install Monitoring Components
            </h2>

            <div className="mb-6">
              <h3 className="doc-h3">Step 2: Install Helm (if not installed)</h3>
              <div className="relative group my-4">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm version`, 'step2')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step2' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm version`}
                </pre>
              </div>

              <h3 className="doc-h3">Step 3: Install Node Exporter</h3>
              <p className="doc-p">Node Exporter collects hardware and OS metrics from cluster nodes.</p>
              <div className="relative group my-4">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install node-exporter prometheus-community/prometheus-node-exporter \\
  --namespace monitoring \\
  --create-namespace \\
  --wait`, 'step3')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step3' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install node-exporter prometheus-community/prometheus-node-exporter \\
  --namespace monitoring \\
  --create-namespace \\
  --wait`}
                </pre>
              </div>

              <h3 className="doc-h3">Step 4: Install Kube State Metrics</h3>
              <p className="doc-p">Kube State Metrics exposes cluster-level metrics about Kubernetes objects.</p>
              <div className="relative group my-4">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`helm upgrade --install kube-state-metrics prometheus-community/kube-state-metrics \\
  --namespace kube-system \\
  --wait`, 'step4')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step4' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`helm upgrade --install kube-state-metrics prometheus-community/kube-state-metrics \\
  --namespace kube-system \\
  --wait`}
                </pre>
              </div>

              <h3 className="doc-h3">Step 5: Install Falco Security Monitoring</h3>
              <p className="doc-p">Falco provides runtime security monitoring and threat detection.</p>
              <p className="text-xs text-gray-500 mb-2 mt-3">5a — Install Falco with Falcosidekick:</p>
              <div className="relative group my-2">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update
helm upgrade --install falco falcosecurity/falco \\
  --namespace falco \\
  --create-namespace \\
  --set falcosidekick.enabled=true \\
  --wait`, 'step5a')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step5a' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update
helm upgrade --install falco falcosecurity/falco \\
  --namespace falco \\
  --create-namespace \\
  --set falcosidekick.enabled=true \\
  --wait`}
                </pre>
              </div>
              <p className="text-xs text-gray-500 mb-2 mt-4">5b — Enable Falcosidekick's Prometheus ServiceMonitor:</p>
              <div className="relative group my-2">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`helm upgrade falco falcosecurity/falco -n falco \\
  --reuse-values \\
  --set falcosidekick.serviceMonitor.enabled=true`, 'step5b')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step5b' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`helm upgrade falco falcosecurity/falco -n falco \\
  --reuse-values \\
  --set falcosidekick.serviceMonitor.enabled=true`}
                </pre>
              </div>
              <p className="text-xs text-gray-500 mb-2 mt-4">5c — Allow kube-prometheus-stack to discover all ServiceMonitors:</p>
              <div className="relative group my-2">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`helm upgrade kube-prom prometheus-community/kube-prometheus-stack -n monitoring \\
  --reuse-values \\
  --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false`, 'step5c')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step5c' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`helm upgrade kube-prom prometheus-community/kube-prometheus-stack -n monitoring \\
  --reuse-values \\
  --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false`}
                </pre>
              </div>
              <div className="mt-3 bg-amber-50 border-l-4 border-amber-400 p-3 rounded-r text-xs text-amber-700">
                <strong>Why step 5c?</strong> kube-prometheus-stack only scrapes ServiceMonitors with the label <code className="bg-amber-100 px-1 rounded">release: kube-prom</code> by default.
                Setting <code className="bg-amber-100 px-1 rounded">serviceMonitorSelectorNilUsesHelmValues=false</code> makes Prometheus discover <em>all</em> ServiceMonitors cluster-wide, including Falcosidekick's.
                Wait ~60 seconds after running this, then refresh the Security tab.
              </div>

              <h3 className="doc-h3 mt-6">Step 6: Install Kubescape (Optional — Compliance Scanner)</h3>
              <p className="doc-p">Kubescape Operator scans your cluster against CIS benchmarks and Kubernetes best practices.</p>
              <p className="text-xs text-gray-500 mb-2 mt-3">Replace <code className="bg-gray-100 px-1 rounded">my-cluster</code> with your GKE cluster name:</p>
              <div className="relative group my-2">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`helm repo add kubescape https://kubescape.github.io/helm-charts/
helm repo update
helm install kubescape kubescape/kubescape-operator \\
  -n kubescape --create-namespace \\
  --set clusterName=my-cluster`, 'step6')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step6' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`helm repo add kubescape https://kubescape.github.io/helm-charts/
helm repo update
helm install kubescape kubescape/kubescape-operator \\
  -n kubescape --create-namespace \\
  --set clusterName=my-cluster`}
                </pre>
              </div>

              <h3 className="doc-h3 mt-6">Step 7: Install Trivy Operator (Optional — Image Vulnerability Scanner)</h3>
              <p className="doc-p">Trivy Operator scans all running container images for CVEs and reports results as Prometheus metrics.</p>
              <p className="text-xs text-gray-500 mb-2 mt-3">7a — Install Trivy Operator:</p>
              <div className="relative group my-2">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`helm repo add aqua https://aquasecurity.github.io/helm-charts/
helm repo update
helm install trivy-operator aqua/trivy-operator \\
  -n trivy-system --create-namespace`, 'step7a')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step7a' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`helm repo add aqua https://aquasecurity.github.io/helm-charts/
helm repo update
helm install trivy-operator aqua/trivy-operator \\
  -n trivy-system --create-namespace`}
                </pre>
              </div>
              <p className="text-xs text-gray-500 mb-2 mt-4">7b — Enable ServiceMonitor (kube-prom's discovery is already configured from Step 5c):</p>
              <div className="relative group my-2">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`helm upgrade trivy-operator aqua/trivy-operator -n trivy-system \\
  --reuse-values \\
  --set serviceMonitor.enabled=true`, 'step7b')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step7b' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`helm upgrade trivy-operator aqua/trivy-operator -n trivy-system \\
  --reuse-values \\
  --set serviceMonitor.enabled=true`}
                </pre>
              </div>
              <p className="text-xs text-gray-400 mt-1">Wait ~2 minutes for the initial image scan, then refresh the Security tab.</p>
            </div>
          </section>

          <hr className="border-gray-200" />

          {/* Phase 3: Verification */}
          <section>
            <h2 className="text-lg font-extrabold text-gray-900 mb-4 uppercase tracking-wide">
              Phase 3: Verify Installation
            </h2>

            <div className="mb-6">
              <h3 className="doc-h3">Step 8: Check Running Pods</h3>
              <div className="relative group my-4">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`kubectl get pods -n monitoring
kubectl get pods -n kube-system | grep kube-state-metrics
kubectl get pods -n falco
kubectl get pods -n kubescape
kubectl get pods -n trivy-system`, 'step8')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step8' ? (
                      <><i className="fas fa-check"></i> Copied!</>
                    ) : (
                      <><i className="far fa-copy"></i> Copy</>
                    )}
                  </button>
                </div>
                <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm font-mono leading-relaxed border border-gray-700">
{`kubectl get pods -n monitoring
kubectl get pods -n kube-system | grep kube-state-metrics
kubectl get pods -n falco
kubectl get pods -n kubescape
kubectl get pods -n trivy-system`}
                </pre>
              </div>
            </div>
          </section>

          <hr className="border-gray-200" />

          {/* Success Banner */}
          <div className="bg-green-50 border border-green-200 rounded-lg p-4">
            <div className="flex items-start space-x-3">
              <i className="fas fa-check-circle text-green-600 text-xl mt-0.5"></i>
              <div>
                <h4 className="font-bold text-green-800 mb-2">Setup Complete!</h4>
                <p className="text-sm text-green-700 leading-relaxed">
                  Your GKE cluster is now equipped with comprehensive monitoring. All metrics are being collected and can be
                  visualized through the XamOps dashboard. Navigate to the cluster details to view real-time metrics.
                </p>
              </div>
            </div>
          </div>

          {/* Essential Metrics */}
          <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
            <h4 className="font-bold text-gray-800 mb-3 flex items-center">
              <i className="fas fa-chart-bar text-indigo-500 mr-2"></i>
              Key Metrics to Monitor
            </h4>
            <ul className="list-disc pl-5 space-y-2 text-gray-700 text-sm">
              <li><strong>Node Metrics:</strong> CPU, Memory, Disk utilization per node</li>
              <li><strong>Pod Metrics:</strong> Pod status, restarts, resource requests/limits</li>
              <li><strong>Cluster Health:</strong> Node readiness, pod distribution, namespace quotas</li>
              <li><strong>Security Events:</strong> Falco runtime threat detection and alerts</li>
            </ul>
          </div>
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default GcpCloudK8sHelpModal;
