import React from 'react';
import { Link } from 'react-router-dom';

const AzureCloudK8sPage: React.FC = () => {
  return (
    <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
      <div className="text-center p-12 bg-white rounded-2xl shadow-xl max-w-lg w-full border border-gray-100">
        {/* Azure AKS Icon */}
        <div className="w-24 h-24 bg-gradient-to-br from-blue-500 to-cyan-600 rounded-2xl flex items-center justify-center mx-auto mb-8 shadow-lg shadow-blue-500/30">
          <i className="fas fa-dharmachakra text-4xl text-white"></i>
        </div>

        {/* Coming Soon Badge */}
        <div className="inline-flex items-center gap-2 px-4 py-2 bg-amber-50 border border-amber-200 rounded-full mb-6">
          <i className="fas fa-clock text-amber-500"></i>
          <span className="text-sm font-semibold text-amber-700">Coming Soon</span>
        </div>

        <h1 className="text-3xl font-bold text-gray-800 mb-3">Azure Kubernetes Service</h1>
        <p className="text-gray-500 mb-8 leading-relaxed">
          AKS cluster management and monitoring is currently under development.
          We're working hard to bring you comprehensive Azure Kubernetes insights.
        </p>

        {/* Feature Preview */}
        <div className="bg-gray-50 rounded-xl p-6 mb-8 text-left">
          <h3 className="text-sm font-semibold text-gray-700 mb-4 flex items-center gap-2">
            <i className="fas fa-sparkles text-blue-500"></i>
            Upcoming Features
          </h3>
          <ul className="space-y-3">
            <li className="flex items-center gap-3 text-sm text-gray-600">
              <div className="w-6 h-6 bg-blue-100 rounded-full flex items-center justify-center">
                <i className="fas fa-check text-blue-600 text-xs"></i>
              </div>
              AKS Cluster Overview & Health
            </li>
            <li className="flex items-center gap-3 text-sm text-gray-600">
              <div className="w-6 h-6 bg-blue-100 rounded-full flex items-center justify-center">
                <i className="fas fa-check text-blue-600 text-xs"></i>
              </div>
              Node Pool Management
            </li>
            <li className="flex items-center gap-3 text-sm text-gray-600">
              <div className="w-6 h-6 bg-blue-100 rounded-full flex items-center justify-center">
                <i className="fas fa-check text-blue-600 text-xs"></i>
              </div>
              Workload & Pod Monitoring
            </li>
            <li className="flex items-center gap-3 text-sm text-gray-600">
              <div className="w-6 h-6 bg-blue-100 rounded-full flex items-center justify-center">
                <i className="fas fa-check text-blue-600 text-xs"></i>
              </div>
              Cost Analysis & Optimization
            </li>
          </ul>
        </div>

        {/* Back Button */}
        <Link
          to="/azure-dashboard"
          className="inline-flex items-center gap-2 px-6 py-3 bg-gradient-to-r from-blue-600 to-cyan-600 text-white rounded-xl font-semibold hover:shadow-lg hover:shadow-blue-500/30 transition-all"
        >
          <i className="fas fa-arrow-left"></i>
          Back to Dashboard
        </Link>
      </div>
    </div>
  );
};

export default AzureCloudK8sPage;
