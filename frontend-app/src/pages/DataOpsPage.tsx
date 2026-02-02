/**
 * DataOps Page
 *
 * Converted from: dataops.html
 *
 * This page displays the DataOps "Coming Soon" feature preview with:
 * - Modern gradient design with sky-blue theme
 * - Feature preview cards for Database Reliability and Automation
 * - Animated elements and professional UI
 * - Account context integration
 *
 * Changes from original:
 * - Converted from Alpine.js to React functional component
 * - Added TypeScript for type safety
 * - Integrated with React Sidebar
 * - Enhanced UI with better animations and hover effects
 * - Added additional feature cards and upcoming capabilities section
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

const DataOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();

  const features: FeatureCard[] = [
    {
      icon: 'fas fa-search-location',
      iconBg: 'bg-sky-100',
      iconColor: 'text-sky-600',
      borderColor: 'border-sky-500',
      title: 'Deep Observability',
      description: 'Non-intrusive monitoring for thousands of instances. Pinpoint problematic queries and analyze execution patterns down to the second.',
    },
    {
      icon: 'fas fa-robot',
      iconBg: 'bg-blue-100',
      iconColor: 'text-blue-600',
      borderColor: 'border-blue-500',
      title: 'Automated Remediation',
      description: 'A rule-based engine that automatically fixes bottlenecks via intelligent caching, rate limiting, throttling, and query rewriting.',
    },
    {
      icon: 'fas fa-bolt',
      iconBg: 'bg-indigo-100',
      iconColor: 'text-indigo-600',
      borderColor: 'border-indigo-500',
      title: 'Smart Caching',
      description: 'Automatically cache frequently executed queries to drastically cut response times and improve database performance.',
    },
    {
      icon: 'fas fa-hand-holding-usd',
      iconBg: 'bg-cyan-100',
      iconColor: 'text-cyan-600',
      borderColor: 'border-cyan-500',
      title: 'Cost Optimization',
      description: 'Identify over-provisioned instances and inefficient queries to safely downgrade resources and reduce spend by up to 75%.',
    },
    {
      icon: 'fas fa-tachometer-alt',
      iconBg: 'bg-teal-100',
      iconColor: 'text-teal-600',
      borderColor: 'border-teal-500',
      title: 'Performance Analytics',
      description: 'Real-time dashboards showing query performance, connection pooling stats, and resource utilization across your database fleet.',
    },
    {
      icon: 'fas fa-shield-alt',
      iconBg: 'bg-emerald-100',
      iconColor: 'text-emerald-600',
      borderColor: 'border-emerald-500',
      title: 'Security & Compliance',
      description: 'Automated security audits, access pattern analysis, and compliance reporting for SOC2, HIPAA, and PCI-DSS standards.',
    },
  ];

  const upcomingCapabilities = [
    { label: 'Query Rewriting', icon: 'fas fa-edit' },
    { label: 'Connection Pooling', icon: 'fas fa-water' },
    { label: 'Index Optimization', icon: 'fas fa-sort-amount-up' },
    { label: 'Backup Automation', icon: 'fas fa-cloud-upload-alt' },
    { label: 'Multi-Cloud Support', icon: 'fas fa-cloud' },
    { label: 'Schema Drift Detection', icon: 'fas fa-code-branch' },
  ];

  const supportedDatabases = [
    { name: 'PostgreSQL', icon: 'fas fa-database', color: 'text-blue-600' },
    { name: 'MySQL', icon: 'fas fa-database', color: 'text-orange-500' },
    { name: 'MongoDB', icon: 'fas fa-leaf', color: 'text-green-600' },
    { name: 'Redis', icon: 'fas fa-bolt', color: 'text-red-500' },
    { name: 'Amazon RDS', icon: 'fab fa-aws', color: 'text-amber-600' },
    { name: 'Aurora', icon: 'fab fa-aws', color: 'text-purple-600' },
  ];

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();

  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {selectedAccountIds.length > 0 && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-sky-50 text-sky-700 rounded-lg text-xs font-medium border border-sky-100">
            <i className="fas fa-cloud"></i>
            <span>{selectedAccountIds.length} account{selectedAccountIds.length > 1 ? 's' : ''} selected</span>
          </div>
        )}
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, selectedAccountIds]);

  return (
    <main className="dataops-page flex-1 flex flex-col min-h-screen bg-gray-50">

      {/* Main Content */}
      <div className="flex-1 p-8">
        <div className="max-w-7xl mx-auto">
          {/* Hero Section */}
          <div className="dataops-hero relative overflow-hidden bg-white rounded-2xl shadow-xl mb-8">
            {/* Background Pattern */}
            <div className="absolute inset-0 opacity-5">
              <div
                className="absolute inset-0"
                style={{
                  backgroundImage: 'repeating-linear-gradient(45deg, transparent, transparent 10px, rgba(14, 165, 233, 0.1) 10px, rgba(14, 165, 233, 0.1) 20px)'
                }}
              />
            </div>

            {/* Gradient Orbs */}
            <div className="absolute -top-24 -right-24 w-64 h-64 bg-gradient-to-br from-sky-400/20 to-blue-400/20 rounded-full blur-3xl"></div>
            <div className="absolute -bottom-24 -left-24 w-64 h-64 bg-gradient-to-tr from-blue-400/20 to-cyan-400/20 rounded-full blur-3xl"></div>

            <div className="relative z-10 text-center py-16 px-8">
              {/* Animated Icon */}
              <div className="mb-8 flex justify-center">
                <div className="relative">
                  <div className="dataops-float-animation w-28 h-28 bg-gradient-to-br from-sky-500 to-blue-600 rounded-full flex items-center justify-center shadow-2xl">
                    <i className="fas fa-database text-white text-5xl"></i>
                  </div>
                  {/* Pulse rings */}
                  <div className="absolute inset-0 w-28 h-28 bg-gradient-to-br from-sky-500 to-blue-600 rounded-full animate-ping opacity-20"></div>
                </div>
              </div>

              {/* Title */}
              <h2 className="text-5xl font-bold text-gray-800 mb-4">
                <span className="bg-gradient-to-r from-sky-500 to-blue-600 bg-clip-text text-transparent">
                  Coming Soon
                </span>
              </h2>
              <p className="text-2xl text-gray-800 mb-3 font-semibold">DataOps - Database Reliability & Automation</p>
              <p className="text-gray-600 mb-10 max-w-3xl mx-auto text-lg leading-relaxed">
                The all-in-one database management and optimization platform. Unify observability across
                your entire fleet, detect bottlenecks in real-time, and automate complex fixes like
                query rewriting and caching without any code changes.
              </p>

              {/* Launch Badge */}
              <div className="inline-flex items-center px-8 py-4 bg-gradient-to-r from-sky-500 to-blue-500 text-white rounded-full shadow-xl transform hover:scale-105 transition-all duration-300 cursor-default">
                <i className="fas fa-rocket mr-3 text-lg"></i>
                <span className="font-bold text-lg">Target Launch: Q1 2026</span>
              </div>
            </div>
          </div>

          {/* Features Grid */}
          <div className="mb-8">
            <h3 className="text-xl font-bold text-gray-800 mb-6 flex items-center gap-3">
              <div className="w-8 h-8 bg-gradient-to-br from-sky-500 to-blue-600 rounded-lg flex items-center justify-center">
                <i className="fas fa-star text-white text-sm"></i>
              </div>
              Planned Features
            </h3>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {features.map((feature) => (
                <div
                  key={feature.title}
                  className={`dataops-feature-card bg-white p-6 rounded-xl shadow-md border-l-4 ${feature.borderColor} hover:shadow-lg transition-all duration-300`}
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

          {/* Supported Databases */}
          <div className="bg-white rounded-2xl shadow-lg p-8 mb-8">
            <h3 className="text-xl font-bold text-gray-800 mb-6 flex items-center gap-3">
              <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-cyan-600 rounded-lg flex items-center justify-center">
                <i className="fas fa-database text-white text-sm"></i>
              </div>
              Supported Databases
            </h3>
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
              {supportedDatabases.map((db) => (
                <div
                  key={db.name}
                  className="dataops-db-card flex flex-col items-center p-4 bg-gray-50 rounded-xl hover:bg-gradient-to-br hover:from-sky-50 hover:to-blue-50 transition-all duration-300 cursor-default"
                >
                  <div className="w-12 h-12 bg-white rounded-full flex items-center justify-center shadow-md mb-3">
                    <i className={`${db.icon} ${db.color} text-lg`}></i>
                  </div>
                  <span className="text-sm font-medium text-gray-700 text-center">{db.name}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Upcoming Capabilities */}
          <div className="bg-white rounded-2xl shadow-lg p-8">
            <h3 className="text-xl font-bold text-gray-800 mb-6 flex items-center gap-3">
              <div className="w-8 h-8 bg-gradient-to-br from-cyan-500 to-teal-600 rounded-lg flex items-center justify-center">
                <i className="fas fa-lightbulb text-white text-sm"></i>
              </div>
              Upcoming Capabilities
            </h3>
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
              {upcomingCapabilities.map((capability) => (
                <div
                  key={capability.label}
                  className="dataops-capability-card flex flex-col items-center p-4 bg-gray-50 rounded-xl hover:bg-gradient-to-br hover:from-sky-50 hover:to-blue-50 transition-all duration-300 cursor-default"
                >
                  <div className="w-12 h-12 bg-white rounded-full flex items-center justify-center shadow-md mb-3">
                    <i className={`${capability.icon} text-sky-600 text-lg`}></i>
                  </div>
                  <span className="text-sm font-medium text-gray-700 text-center">{capability.label}</span>
                </div>
              ))}
            </div>

            {/* Footer Note */}
            <div className="mt-8 pt-6 border-t border-gray-100">
              <div className="flex items-center justify-center gap-3 text-gray-500">
                <i className="fas fa-info-circle text-sky-500"></i>
                <p className="text-sm">
                  DataOps will integrate seamlessly with your existing cloud accounts and database infrastructure.
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

export default DataOpsPage;
