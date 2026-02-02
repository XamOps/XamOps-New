/**
 * GCP CloudK8s Integrations Component
 *
 * Displays available integrations for GKE clusters
 */

import React from 'react';

const GcpCloudK8sIntegrations: React.FC = () => {
  return (
    <div className="fade-in">
      <h3 className="text-lg font-bold text-gray-800 mb-4 flex items-center">
        <i className="fas fa-plug text-indigo-500 mr-2"></i> Available Integrations
      </h3>
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {/* Config Sync (GitOps) */}
        <div className="glass-card p-6 rounded-xl relative group border border-dashed border-gray-300 hover:border-indigo-300 transition-colors">
          <div className="absolute top-0 right-0 bg-gradient-to-r from-gray-700 to-gray-900 text-white text-[10px] font-bold px-3 py-1 rounded-bl-lg shadow-sm z-10">
            COMING SOON
          </div>

          <div className="flex items-center justify-between mb-4 opacity-75 group-hover:opacity-100 transition-opacity">
            <div className="w-12 h-12 bg-blue-50 rounded-lg flex items-center justify-center border border-blue-100">
              <i className="fab fa-git-alt text-blue-500 text-xl"></i>
            </div>
          </div>

          <h4 className="font-bold text-gray-800 text-lg mb-2">Config Sync</h4>
          <p className="text-sm text-gray-500 mb-6 leading-relaxed">
            GitOps for GKE. Automatically sync your cluster configurations from Git repositories.
          </p>

          <button
            disabled
            className="w-full py-2.5 rounded-lg bg-gray-100 text-gray-400 text-sm font-semibold cursor-not-allowed border border-gray-200 flex items-center justify-center"
          >
            <i className="fas fa-clock mr-2"></i> Integration Pending
          </button>
        </div>

        {/* Cloud Operations (Monitoring & Logging) */}
        <div className="glass-card p-6 rounded-xl relative group border border-dashed border-gray-300 hover:border-indigo-300 transition-colors">
          <div className="absolute top-0 right-0 bg-gradient-to-r from-gray-700 to-gray-900 text-white text-[10px] font-bold px-3 py-1 rounded-bl-lg shadow-sm z-10">
            COMING SOON
          </div>

          <div className="flex items-center justify-between mb-4 opacity-75 group-hover:opacity-100 transition-opacity">
            <div className="w-12 h-12 bg-green-50 rounded-lg flex items-center justify-center border border-green-100">
              <i className="fas fa-chart-line text-green-500 text-xl"></i>
            </div>
          </div>

          <h4 className="font-bold text-gray-800 text-lg mb-2">Cloud Operations</h4>
          <p className="text-sm text-gray-500 mb-6 leading-relaxed">
            Integrated monitoring, logging, and tracing powered by Google Cloud Operations Suite.
          </p>

          <button
            disabled
            className="w-full py-2.5 rounded-lg bg-gray-100 text-gray-400 text-sm font-semibold cursor-not-allowed border border-gray-200 flex items-center justify-center"
          >
            <i className="fas fa-clock mr-2"></i> Integration Pending
          </button>
        </div>

        {/* GKE Autopilot */}
        <div className="glass-card p-6 rounded-xl relative group border border-dashed border-gray-300 hover:border-indigo-300 transition-colors">
          <div className="absolute top-0 right-0 bg-gradient-to-r from-gray-700 to-gray-900 text-white text-[10px] font-bold px-3 py-1 rounded-bl-lg shadow-sm z-10">
            COMING SOON
          </div>

          <div className="flex items-center justify-between mb-4 opacity-75 group-hover:opacity-100 transition-opacity">
            <div className="w-12 h-12 bg-purple-50 rounded-lg flex items-center justify-center border border-purple-100">
              <i className="fas fa-robot text-purple-500 text-xl"></i>
            </div>
          </div>

          <h4 className="font-bold text-gray-800 text-lg mb-2">GKE Autopilot</h4>
          <p className="text-sm text-gray-500 mb-6 leading-relaxed">
            Hands-off cluster management with optimized configuration and automatic scaling.
          </p>

          <button
            disabled
            className="w-full py-2.5 rounded-lg bg-gray-100 text-gray-400 text-sm font-semibold cursor-not-allowed border border-gray-200 flex items-center justify-center"
          >
            <i className="fas fa-clock mr-2"></i> Integration Pending
          </button>
        </div>
      </div>

      {/* Info Banner */}
      <div className="mt-6 glass-card p-4 rounded-xl border-l-4 border-blue-500">
        <div className="flex items-start space-x-3">
          <div className="flex-shrink-0 mt-0.5">
            <i className="fas fa-info-circle text-blue-500 text-lg"></i>
          </div>
          <div className="flex-1">
            <h5 className="font-bold text-gray-800 text-sm mb-1">More Integrations Coming Soon</h5>
            <p className="text-xs text-gray-600 leading-relaxed">
              We're working on integrating more GCP services including Cloud Build, Artifact Registry,
              Binary Authorization, and Policy Controller. Stay tuned for updates!
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default GcpCloudK8sIntegrations;
