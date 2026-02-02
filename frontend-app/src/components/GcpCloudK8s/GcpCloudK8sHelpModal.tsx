/**
 * GCP CloudK8s Help Modal Component
 *
 * Comprehensive setup guide for GKE observability
 */

import React, { useState } from 'react';

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

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div onClick={onClose} className="modal-overlay fixed inset-0"></div>
      <div
        className="modal-content rounded-2xl p-8 w-full max-w-4xl mx-auto max-h-[90vh] overflow-y-auto fade-in z-50 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between pb-4 border-b">
          <div className="flex items-center space-x-4">
            <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center">
              <i className="fas fa-rocket text-white text-xl"></i>
            </div>
            <div>
              <h3 className="font-bold text-xl text-gray-800">GKE OBSERVABILITY SETUP GUIDE</h3>
              <p className="text-sm text-gray-600">
                Install monitoring stack on GKE → Forward metrics to monitoring instance
              </p>
            </div>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-2xl">
            &times;
          </button>
        </div>

        {/* Content */}
        <div className="mt-6 pr-2 space-y-6">
          {/* Overview */}
          <div className="bg-blue-50 border-l-4 border-blue-500 p-4 rounded-r">
            <p className="text-sm text-blue-700">
              <strong>Overview:</strong> This guide helps you set up comprehensive monitoring for GKE clusters including
              node metrics, pod metrics, and security monitoring with Falco. Metrics are forwarded to a central monitoring instance.
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
              <div className="relative group my-4">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update
helm upgrade --install falco falcosecurity/falco \\
  --namespace falco \\
  --create-namespace \\
  --set falcosidekick.enabled=true \\
  --set falcosidekick.config.prometheus.enabled=true \\
  --wait`, 'step5')}
                    className="px-2 py-1 bg-gray-700 hover:bg-gray-600 text-white text-xs rounded shadow transition-colors border border-gray-600"
                  >
                    {copiedStep === 'step5' ? (
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
  --set falcosidekick.config.prometheus.enabled=true \\
  --wait`}
                </pre>
              </div>
            </div>
          </section>

          <hr className="border-gray-200" />

          {/* Phase 3: Verification */}
          <section>
            <h2 className="text-lg font-extrabold text-gray-900 mb-4 uppercase tracking-wide">
              Phase 3: Verify Installation
            </h2>

            <div className="mb-6">
              <h3 className="doc-h3">Step 6: Check Running Pods</h3>
              <div className="relative group my-4">
                <div className="absolute top-2 right-2 z-10">
                  <button
                    onClick={() => copyToClipboard(`kubectl get pods -n monitoring
kubectl get pods -n kube-system | grep kube-state-metrics
kubectl get pods -n falco`, 'step6')}
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
{`kubectl get pods -n monitoring
kubectl get pods -n kube-system | grep kube-state-metrics
kubectl get pods -n falco`}
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
      </div>
    </div>
  );
};

export default GcpCloudK8sHelpModal;
