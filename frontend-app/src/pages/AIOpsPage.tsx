/**
 * AIOps Page
 *
 * Converted from: aiops.html
 *
 * This page displays the AIOps "Coming Soon" feature preview with:
 * - Modern gradient design
 * - Feature preview cards for Anomaly Detection and Auto-Remediation
 * - Animated elements and professional UI
 * - Account context integration
 *
 * Changes from original:
 * - Converted from Alpine.js to React functional component
 * - Added TypeScript for type safety
 * - Integrated with React Sidebar
 * - Enhanced UI with better animations and hover effects
 * - Added additional feature cards for a more comprehensive preview
 */

import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';

interface FeatureCard {
  icon: string;
  iconBg: string;
  iconColor: string;
  borderColor: string;
  title: string;
  description: string;
}

const AIOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();

  const features: FeatureCard[] = [
    {
      icon: 'fas fa-magic',
      iconBg: 'bg-indigo-100',
      iconColor: 'text-indigo-600',
      borderColor: 'border-indigo-500',
      title: 'Anomaly Detection',
      description: 'AI-driven detection of unusual patterns in metrics, logs, and traces in real-time.',
    },
    {
      icon: 'fas fa-robot',
      iconBg: 'bg-purple-100',
      iconColor: 'text-purple-600',
      borderColor: 'border-purple-500',
      title: 'Auto-Remediation',
      description: 'Self-healing automation scripts for common operational incidents.',
    },
    {
      icon: 'fas fa-chart-line',
      iconBg: 'bg-blue-100',
      iconColor: 'text-blue-600',
      borderColor: 'border-blue-500',
      title: 'Predictive Analytics',
      description: 'Forecast resource needs and potential issues before they impact your systems.',
    },
    {
      icon: 'fas fa-bell',
      iconBg: 'bg-amber-100',
      iconColor: 'text-amber-600',
      borderColor: 'border-amber-500',
      title: 'Intelligent Alerting',
      description: 'Smart alert correlation and noise reduction to surface only critical issues.',
    },
    {
      icon: 'fas fa-network-wired',
      iconBg: 'bg-teal-100',
      iconColor: 'text-teal-600',
      borderColor: 'border-teal-500',
      title: 'Root Cause Analysis',
      description: 'Automated dependency mapping and root cause identification across services.',
    },
    {
      icon: 'fas fa-brain',
      iconBg: 'bg-pink-100',
      iconColor: 'text-pink-600',
      borderColor: 'border-pink-500',
      title: 'ML-Powered Insights',
      description: 'Continuous learning from your infrastructure to improve detection accuracy.',
    },
  ];

  const upcomingCapabilities = [
    { label: 'Log Pattern Recognition', icon: 'fas fa-file-alt' },
    { label: 'Metric Forecasting', icon: 'fas fa-chart-area' },
    { label: 'Event Correlation', icon: 'fas fa-project-diagram' },
    { label: 'Runbook Automation', icon: 'fas fa-book-open' },
    { label: 'ChatOps Integration', icon: 'fas fa-comments' },
    { label: 'Custom ML Models', icon: 'fas fa-cogs' },
  ];

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();

  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {selectedAccountIds.length > 0 && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-indigo-50 text-indigo-700 rounded-lg text-xs font-medium border border-indigo-100">
            <i className="fas fa-cloud"></i>
            <span>{selectedAccountIds.length} account{selectedAccountIds.length > 1 ? 's' : ''} selected</span>
          </div>
        )}
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, selectedAccountIds]);

  return (
    <main className="aiops-page flex-1 flex flex-col min-h-screen bg-gray-50">

      {/* Main Content */}
      <div className="flex-1 p-8">
        <div className="max-w-7xl mx-auto">
          {/* Hero Section */}
          <div className="aiops-hero relative overflow-hidden bg-white rounded-2xl shadow-xl mb-8">
            {/* Background Pattern */}
            <div className="absolute inset-0 opacity-5">
              <div
                className="absolute inset-0"
                style={{
                  backgroundImage: 'repeating-linear-gradient(45deg, transparent, transparent 10px, rgba(99, 102, 241, 0.1) 10px, rgba(99, 102, 241, 0.1) 20px)'
                }}
              />
            </div>

            {/* Gradient Orbs */}
            <div className="absolute -top-24 -right-24 w-64 h-64 bg-gradient-to-br from-indigo-400/20 to-purple-400/20 rounded-full blur-3xl"></div>
            <div className="absolute -bottom-24 -left-24 w-64 h-64 bg-gradient-to-tr from-purple-400/20 to-pink-400/20 rounded-full blur-3xl"></div>

            <div className="relative z-10 text-center py-16 px-8">
              {/* Animated Icon */}
              <div className="mb-8 flex justify-center">
                <div className="relative">
                  <div className="aiops-float-animation w-28 h-28 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-full flex items-center justify-center shadow-2xl">
                    <i className="fas fa-brain text-white text-5xl"></i>
                  </div>
                  {/* Pulse rings */}
                  <div className="absolute inset-0 w-28 h-28 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-full animate-ping opacity-20"></div>
                </div>
              </div>

              {/* Title */}
              <h2 className="text-5xl font-bold text-gray-800 mb-4">
                <span className="bg-gradient-to-r from-indigo-600 to-purple-600 bg-clip-text text-transparent">
                  Coming Soon
                </span>
              </h2>
              <p className="text-2xl text-gray-800 mb-3 font-semibold">AIOps - Intelligent Operations</p>
              <p className="text-gray-600 mb-10 max-w-3xl mx-auto text-lg leading-relaxed">
                Leverage Artificial Intelligence to automate IT operations. Predict issues, correlate
                events, and auto-remediate incidents across your cloud infrastructure with machine
                learning-powered insights.
              </p>

              {/* Launch Badge */}
              <div className="inline-flex items-center px-8 py-4 bg-gradient-to-r from-indigo-500 to-purple-500 text-white rounded-full shadow-xl transform hover:scale-105 transition-all duration-300 cursor-default">
                <i className="fas fa-rocket mr-3 text-lg"></i>
                <span className="font-bold text-lg">Target Launch: Q1 2026</span>
              </div>
            </div>
          </div>

          {/* Features Grid */}
          <div className="mb-8">
            <h3 className="text-xl font-bold text-gray-800 mb-6 flex items-center gap-3">
              <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
                <i className="fas fa-star text-white text-sm"></i>
              </div>
              Planned Features
            </h3>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {features.map((feature, index) => (
                <div
                  key={index}
                  className={`aiops-feature-card bg-white p-6 rounded-xl shadow-md border-l-4 ${feature.borderColor} hover:shadow-lg transition-all duration-300`}
                >
                  <div className="flex items-start space-x-4">
                    <div className={`flex-shrink-0 w-14 h-14 ${feature.iconBg} rounded-xl flex items-center justify-center`}>
                      <i className={`${feature.icon} ${feature.iconColor} text-2xl`}></i>
                    </div>
                    <div className="flex-1">
                      <h4 className="font-bold text-gray-900 text-lg mb-2">{feature.title}</h4>
                      <p className="text-sm text-gray-600 leading-relaxed">{feature.description}</p>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Upcoming Capabilities */}
          <div className="bg-white rounded-2xl shadow-lg p-8">
            <h3 className="text-xl font-bold text-gray-800 mb-6 flex items-center gap-3">
              <div className="w-8 h-8 bg-gradient-to-br from-purple-500 to-pink-600 rounded-lg flex items-center justify-center">
                <i className="fas fa-lightbulb text-white text-sm"></i>
              </div>
              Upcoming Capabilities
            </h3>
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
              {upcomingCapabilities.map((capability, index) => (
                <div
                  key={index}
                  className="aiops-capability-card flex flex-col items-center p-4 bg-gray-50 rounded-xl hover:bg-gradient-to-br hover:from-indigo-50 hover:to-purple-50 transition-all duration-300 cursor-default"
                >
                  <div className="w-12 h-12 bg-white rounded-full flex items-center justify-center shadow-md mb-3">
                    <i className={`${capability.icon} text-indigo-600 text-lg`}></i>
                  </div>
                  <span className="text-sm font-medium text-gray-700 text-center">{capability.label}</span>
                </div>
              ))}
            </div>

            {/* Footer Note */}
            <div className="mt-8 pt-6 border-t border-gray-100">
              <div className="flex items-center justify-center gap-3 text-gray-500">
                <i className="fas fa-info-circle text-indigo-500"></i>
                <p className="text-sm">
                  AIOps will integrate seamlessly with your existing cloud accounts and monitoring tools.
                  Stay tuned for early access announcements.
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>
  );
};

export default AIOpsPage;
