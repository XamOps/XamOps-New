/**
 * CloudK8s Integrations Component
 *
 * Shows available integrations with coming soon cards
 */

import React from 'react';

const CloudK8sIntegrations: React.FC = () => {
  return (
    <div className="fade-in">
      <h3 className="text-lg font-bold text-gray-800 mb-4 flex items-center">
        <i className="fas fa-plug text-indigo-500 mr-3"></i>
        Available Integrations
      </h3>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {/* ArgoCD Integration - Coming Soon */}
        <div className="glass-card p-6 rounded-xl relative group border border-dashed border-gray-300 hover:border-indigo-300 transition-all hover:shadow-md">
          <div className="absolute top-0 right-0 bg-gradient-to-r from-gray-700 to-gray-900 text-white text-[10px] font-bold px-3 py-1 rounded-bl-lg rounded-tr-xl shadow-sm z-10">
            COMING SOON
          </div>

          <div className="flex items-center justify-between mb-4 opacity-75 group-hover:opacity-100 transition-opacity">
            <div className="w-12 h-12 bg-orange-50 rounded-lg flex items-center justify-center border border-orange-100">
              <i className="fas fa-code-branch text-orange-500 text-xl"></i>
            </div>
          </div>

          <h4 className="font-bold text-gray-800 text-lg mb-2">ArgoCD GitOps</h4>
          <p className="text-sm text-gray-500 mb-6 leading-relaxed">
            Automated continuous delivery. Sync your cluster state directly from your Git repositories.
          </p>

          <button
            disabled
            className="w-full py-2.5 rounded-lg bg-gray-100 text-gray-400 text-sm font-semibold cursor-not-allowed border border-gray-200 flex items-center justify-center"
          >
            <i className="fas fa-clock mr-2"></i>
            Integration Pending
          </button>
        </div>

        {/* Prometheus Integration - Coming Soon */}
        <div className="glass-card p-6 rounded-xl relative group border border-dashed border-gray-300 hover:border-green-300 transition-all hover:shadow-md">
          <div className="absolute top-0 right-0 bg-gradient-to-r from-gray-700 to-gray-900 text-white text-[10px] font-bold px-3 py-1 rounded-bl-lg rounded-tr-xl shadow-sm z-10">
            COMING SOON
          </div>

          <div className="flex items-center justify-between mb-4 opacity-75 group-hover:opacity-100 transition-opacity">
            <div className="w-12 h-12 bg-green-50 rounded-lg flex items-center justify-center border border-green-100">
              <i className="fas fa-chart-area text-green-500 text-xl"></i>
            </div>
          </div>

          <h4 className="font-bold text-gray-800 text-lg mb-2">Prometheus Metrics</h4>
          <p className="text-sm text-gray-500 mb-6 leading-relaxed">
            Advanced metrics collection and alerting. Monitor cluster performance with custom queries.
          </p>

          <button
            disabled
            className="w-full py-2.5 rounded-lg bg-gray-100 text-gray-400 text-sm font-semibold cursor-not-allowed border border-gray-200 flex items-center justify-center"
          >
            <i className="fas fa-clock mr-2"></i>
            Integration Pending
          </button>
        </div>

        {/* Istio Integration - Coming Soon */}
        <div className="glass-card p-6 rounded-xl relative group border border-dashed border-gray-300 hover:border-purple-300 transition-all hover:shadow-md">
          <div className="absolute top-0 right-0 bg-gradient-to-r from-gray-700 to-gray-900 text-white text-[10px] font-bold px-3 py-1 rounded-bl-lg rounded-tr-xl shadow-sm z-10">
            COMING SOON
          </div>

          <div className="flex items-center justify-between mb-4 opacity-75 group-hover:opacity-100 transition-opacity">
            <div className="w-12 h-12 bg-purple-50 rounded-lg flex items-center justify-center border border-purple-100">
              <i className="fas fa-network-wired text-purple-500 text-xl"></i>
            </div>
          </div>

          <h4 className="font-bold text-gray-800 text-lg mb-2">Service Mesh</h4>
          <p className="text-sm text-gray-500 mb-6 leading-relaxed">
            Istio service mesh integration. Advanced traffic management, security, and observability.
          </p>

          <button
            disabled
            className="w-full py-2.5 rounded-lg bg-gray-100 text-gray-400 text-sm font-semibold cursor-not-allowed border border-gray-200 flex items-center justify-center"
          >
            <i className="fas fa-clock mr-2"></i>
            Integration Pending
          </button>
        </div>
      </div>
    </div>
  );
};

export default CloudK8sIntegrations;
