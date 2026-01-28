/**
 * EKS Integration Guide Modal
 *
 * Simplified integration guide for EKS monitoring setup
 */

import React from 'react';

interface EksIntegrationModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const EksIntegrationModal: React.FC<EksIntegrationModalProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      {/* Backdrop */}
      <div onClick={onClose} className="fixed inset-0 bg-black/50 backdrop-blur-sm" />

      {/* Modal Content */}
      <div
        className="bg-white rounded-2xl p-8 w-full max-w-4xl mx-auto max-h-[90vh] overflow-y-auto z-50 shadow-2xl relative"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between pb-4 border-b border-gray-200">
          <div className="flex items-center space-x-4">
            <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center shadow-lg">
              <i className="fas fa-book text-white text-xl"></i>
            </div>
            <div>
              <h3 className="font-bold text-xl text-gray-800">EKS Integration Guide</h3>
              <p className="text-sm text-gray-600">Complete observability setup for your cluster</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-3xl leading-none hover:bg-gray-100 w-10 h-10 rounded-full flex items-center justify-center transition-colors"
          >
            &times;
          </button>
        </div>

        {/* Content */}
        <div className="mt-6 space-y-6">
          {/* Overview */}
          <div className="bg-blue-50 border-l-4 border-blue-500 p-4 rounded-r-lg">
            <p className="text-sm text-blue-700">
              <strong>Overview:</strong> This guide helps you set up complete observability for your EKS cluster including Falco security monitoring, resource metrics collection, and Prometheus integration.
            </p>
          </div>

          {/* Prerequisites */}
          <section>
            <h3 className="text-lg font-bold text-gray-900 mb-3 flex items-center">
              <i className="fas fa-clipboard-list text-indigo-500 mr-2"></i>
              Prerequisites
            </h3>
            <ul className="list-disc list-inside space-y-2 text-gray-700 ml-4">
              <li>EKS cluster running (v1.25 or higher)</li>
              <li>kubectl configured with cluster access</li>
              <li>Helm 3 installed</li>
              <li>AWS CLI configured</li>
            </ul>
          </section>

          {/* Step 1 */}
          <section>
            <h3 className="text-lg font-bold text-gray-900 mb-3 flex items-center">
              <span className="w-8 h-8 bg-blue-500 text-white rounded-full flex items-center justify-center text-sm mr-3">
                1
              </span>
              Install Falco Security Monitoring
            </h3>
            <div className="bg-gray-900 text-gray-100 p-4 rounded-lg font-mono text-sm overflow-x-auto">
              <code>
                helm repo add falcosecurity https://falcosecurity.github.io/charts<br />
                helm repo update<br />
                helm install falco falcosecurity/falco \<br />
                &nbsp;&nbsp;--namespace falco \<br />
                &nbsp;&nbsp;--create-namespace \<br />
                &nbsp;&nbsp;--set falcosidekick.enabled=true \<br />
                &nbsp;&nbsp;--set falcosidekick.config.prometheus.enabled=true
              </code>
            </div>
          </section>

          {/* Step 2 */}
          <section>
            <h3 className="text-lg font-bold text-gray-900 mb-3 flex items-center">
              <span className="w-8 h-8 bg-green-500 text-white rounded-full flex items-center justify-center text-sm mr-3">
                2
              </span>
              Install Node Exporter
            </h3>
            <div className="bg-gray-900 text-gray-100 p-4 rounded-lg font-mono text-sm overflow-x-auto">
              <code>
                helm repo add prometheus-community https://prometheus-community.github.io/helm-charts<br />
                helm repo update<br />
                helm install node-exporter prometheus-community/prometheus-node-exporter \<br />
                &nbsp;&nbsp;--namespace default \<br />
                &nbsp;&nbsp;--create-namespace
              </code>
            </div>
          </section>

          {/* Step 3 */}
          <section>
            <h3 className="text-lg font-bold text-gray-900 mb-3 flex items-center">
              <span className="w-8 h-8 bg-purple-500 text-white rounded-full flex items-center justify-center text-sm mr-3">
                3
              </span>
              Install Kube State Metrics
            </h3>
            <div className="bg-gray-900 text-gray-100 p-4 rounded-lg font-mono text-sm overflow-x-auto">
              <code>
                helm install kube-state-metrics prometheus-community/kube-state-metrics \<br />
                &nbsp;&nbsp;--namespace kube-system
              </code>
            </div>
          </section>

          {/* Step 4 */}
          <section>
            <h3 className="text-lg font-bold text-gray-900 mb-3 flex items-center">
              <span className="w-8 h-8 bg-orange-500 text-white rounded-full flex items-center justify-center text-sm mr-3">
                4
              </span>
              Configure XamOps Integration
            </h3>
            <p className="text-gray-700 mb-3">
              Contact your XamOps administrator to configure the monitoring agent connection to this EKS cluster.
            </p>
            <div className="bg-amber-50 border border-amber-200 rounded-lg p-4">
              <p className="text-sm text-amber-800">
                <i className="fas fa-info-circle mr-2"></i>
                <strong>Note:</strong> The XamOps backend will automatically discover and start monitoring your cluster once the integration is configured.
              </p>
            </div>
          </section>

          {/* Verification */}
          <section>
            <h3 className="text-lg font-bold text-gray-900 mb-3 flex items-center">
              <i className="fas fa-check-circle text-green-500 mr-2"></i>
              Verification
            </h3>
            <p className="text-gray-700 mb-3">Verify that all components are running:</p>
            <div className="bg-gray-900 text-gray-100 p-4 rounded-lg font-mono text-sm overflow-x-auto">
              <code>
                kubectl get pods -n falco<br />
                kubectl get pods -n default | grep node-exporter<br />
                kubectl get pods -n kube-system | grep kube-state-metrics
              </code>
            </div>
            <p className="text-sm text-gray-600 mt-3">
              All pods should be in <span className="font-semibold text-green-600">Running</span> state. Once configured, metrics will appear in this dashboard within 5-10 minutes.
            </p>
          </section>

          {/* Help */}
          <div className="bg-gray-50 rounded-lg p-6 border border-gray-200">
            <h4 className="font-bold text-gray-900 mb-2">Need Help?</h4>
            <p className="text-sm text-gray-600">
              For detailed documentation and troubleshooting, contact your XamOps support team or visit the internal documentation portal.
            </p>
          </div>
        </div>

        {/* Footer */}
        <div className="mt-6 pt-4 border-t border-gray-200 flex justify-end">
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

export default EksIntegrationModal;
