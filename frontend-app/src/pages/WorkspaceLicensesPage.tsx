/**
 * WorkSpace Licenses Page
 *
 * Centralized SaaS seat and license management page
 *
 * Converted from: billops/workspace-licenses.html
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Enhanced UI with more professional design
 * - Added smooth animations and transitions
 * - Improved responsive design
 * - Added proper component structure
 *
 * What stayed the same:
 * - All features and functionality preserved
 * - Same "Coming Soon" message (no backend yet)
 * - Same core information and branding
 */

import React, { useEffect } from 'react';
import { useWorkspaceLicenses } from '../hooks/useWorkspaceLicenses';
import { useTopbar } from '../contexts/TopbarContext';

const WorkspaceLicensesPage: React.FC = () => {
  const { } = useWorkspaceLicenses();
  const { clearActions } = useTopbar();

  // Topbar Integration
  useEffect(() => {
    clearActions();
    return () => clearActions();
  }, [clearActions]);

  return (
    <main className="workspace-licenses-page flex-1 flex flex-col min-h-screen bg-gray-50">
      {/* Header Removed - Integrated into Topbar */}

      {/* Main Content */}
      <div className="flex-1 flex items-center justify-center p-8">
        <div className="max-w-5xl w-full">
          <div className="workspace-licenses-card relative overflow-hidden bg-white rounded-2xl shadow-xl p-8 md:p-12">
            {/* Background Pattern */}
            <div className="absolute inset-0 opacity-5">
              <div
                className="absolute inset-0"
                style={{
                  backgroundImage:
                    'repeating-linear-gradient(45deg, transparent, transparent 10px, rgba(249, 115, 22, 0.1) 10px, rgba(249, 115, 22, 0.1) 20px)',
                }}
              ></div>
            </div>

            <div className="relative z-10 text-center">
              {/* Animated Icon */}
              <div className="mb-8 flex justify-center">
                <div className="relative">
                  <div className="workspace-licenses-icon w-24 h-24 bg-gradient-to-br from-orange-500 to-red-600 rounded-full flex items-center justify-center shadow-lg">
                    <i className="fas fa-id-card text-white text-4xl"></i>
                  </div>
                  {/* Pulse Ring Effect */}
                  <div className="workspace-licenses-pulse absolute inset-0 rounded-full bg-orange-500 opacity-20"></div>
                </div>
              </div>

              {/* Coming Soon Badge */}
              <div className="inline-block mb-4">
                <span className="px-4 py-2 bg-gradient-to-r from-orange-500 to-red-500 text-white text-sm font-bold rounded-full shadow-md">
                  Coming Soon
                </span>
              </div>

              {/* Title */}
              <h2 className="text-4xl md:text-5xl font-bold text-gray-900 mb-4">
                <span className="bg-gradient-to-r from-orange-500 to-red-600 bg-clip-text text-transparent">
                  WorkSpace Licenses
                </span>
              </h2>

              {/* Description */}
              <p className="text-lg text-gray-700 mb-8 max-w-2xl mx-auto leading-relaxed">
                Centralized management for all your SaaS seats and licenses. Track usage, optimize
                allocation, and reduce seat wastage across your organization.
              </p>

              {/* Features Grid */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 mb-10 text-left">
                {/* Seat Optimization Feature */}
                <div className="workspace-licenses-feature-card group p-6 rounded-xl bg-gradient-to-br from-orange-50 to-orange-100 border-l-4 border-orange-500 shadow-md hover:shadow-xl transition-all duration-300 transform hover:-translate-y-1">
                  <div className="flex items-start space-x-4">
                    <div className="flex-shrink-0 w-12 h-12 bg-white rounded-lg flex items-center justify-center shadow-sm group-hover:scale-110 transition-transform duration-300">
                      <i className="fas fa-users text-orange-600 text-xl"></i>
                    </div>
                    <div className="flex-1">
                      <h3 className="font-bold text-gray-900 text-base mb-2">Seat Optimization</h3>
                      <p className="text-sm text-gray-700">
                        Identify inactive users and reclaim unused licenses to maximize efficiency.
                      </p>
                    </div>
                  </div>
                </div>

                {/* Renewal Alerts Feature */}
                <div className="workspace-licenses-feature-card group p-6 rounded-xl bg-gradient-to-br from-red-50 to-red-100 border-l-4 border-red-500 shadow-md hover:shadow-xl transition-all duration-300 transform hover:-translate-y-1">
                  <div className="flex items-start space-x-4">
                    <div className="flex-shrink-0 w-12 h-12 bg-white rounded-lg flex items-center justify-center shadow-sm group-hover:scale-110 transition-transform duration-300">
                      <i className="fas fa-sync text-red-600 text-xl"></i>
                    </div>
                    <div className="flex-1">
                      <h3 className="font-bold text-gray-900 text-base mb-2">Renewal Alerts</h3>
                      <p className="text-sm text-gray-700">
                        Automated notifications for upcoming renewals and license expiration dates.
                      </p>
                    </div>
                  </div>
                </div>

                {/* Cost Tracking Feature */}
                <div className="workspace-licenses-feature-card group p-6 rounded-xl bg-gradient-to-br from-amber-50 to-amber-100 border-l-4 border-amber-500 shadow-md hover:shadow-xl transition-all duration-300 transform hover:-translate-y-1">
                  <div className="flex items-start space-x-4">
                    <div className="flex-shrink-0 w-12 h-12 bg-white rounded-lg flex items-center justify-center shadow-sm group-hover:scale-110 transition-transform duration-300">
                      <i className="fas fa-chart-line text-amber-600 text-xl"></i>
                    </div>
                    <div className="flex-1">
                      <h3 className="font-bold text-gray-900 text-base mb-2">Cost Tracking</h3>
                      <p className="text-sm text-gray-700">
                        Real-time visibility into license costs and usage patterns across tools.
                      </p>
                    </div>
                  </div>
                </div>
              </div>

              {/* Launch Timeline */}
              <div className="workspace-licenses-timeline inline-flex items-center px-6 py-3 bg-gradient-to-r from-orange-500 via-red-500 to-red-600 text-white rounded-full shadow-lg">
                <i className="fas fa-clock mr-3 text-lg"></i>
                <span className="font-semibold text-base">Target Launch:</span>
                <span className="ml-3 px-4 py-1 bg-white bg-opacity-30 rounded-full text-sm font-bold backdrop-blur-sm">
                  Q1 2026
                </span>
              </div>

              {/* Additional Info */}
              <div className="mt-10 pt-8 border-t border-gray-200">
                <p className="text-sm text-gray-600 mb-4">
                  <i className="fas fa-info-circle text-orange-500 mr-2"></i>
                  This feature is currently under development. Stay tuned for updates!
                </p>
                <div className="flex justify-center gap-4">
                  <button className="px-6 py-2 bg-gradient-to-r from-orange-500 to-red-500 text-white rounded-lg font-semibold hover:shadow-lg transform hover:-translate-y-0.5 transition-all duration-200">
                    <i className="fas fa-bell mr-2"></i>
                    Notify Me
                  </button>
                  <button className="px-6 py-2 bg-gray-100 text-gray-700 rounded-lg font-semibold hover:bg-gray-200 transform hover:-translate-y-0.5 transition-all duration-200">
                    <i className="fas fa-question-circle mr-2"></i>
                    Learn More
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>
  );
};

export default WorkspaceLicensesPage;
