/**
 * EKS Security Tab
 */

import React from 'react';

const EksSecurityTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard, sortedAlertsByPriority } = eksData;

  // Compute Falco totals
  const totalEvents = dashboard?.securityMetrics?.totalEvents || 0;
  const criticalEvents = dashboard?.securityMetrics?.eventsByPriority?.['CRITICAL'] ||
    dashboard?.securityMetrics?.eventsByPriority?.['4'] || 0;
  const warningEvents = dashboard?.securityMetrics?.eventsByPriority?.['WARNING'] ||
    dashboard?.securityMetrics?.eventsByPriority?.['3'] || 0;

  return (
    <div className="space-y-8">

      {/* 1. FALCO RUNTIME SECURITY SECTION */}
      <section className="bg-white rounded-3xl shadow-lg border border-gray-100 p-8 relative overflow-hidden">
        {/* Background Decoration */}
        <div className="absolute top-0 right-0 w-64 h-64 bg-red-50 rounded-full blur-3xl opacity-50 -translate-y-1/2 translate-x-1/3 pointer-events-none"></div>

        <div className="relative z-10">
          <div className="flex items-center justify-between mb-8">
            <div className="flex items-center gap-4">
              <div className="w-16 h-16 bg-red-50 rounded-2xl flex items-center justify-center shadow-sm">
                <i className="fas fa-shield-virus text-red-600 text-3xl"></i>
              </div>
              <div>
                <h3 className="text-3xl font-black tracking-tight text-gray-900">Runtime Security</h3>
                <p className="text-gray-600 text-sm mt-1">Real-time threat detection and behavioral monitoring via Falco</p>
              </div>
            </div>

            {/* Status Integration Badge */}
            <div className={`px-4 py-2 rounded-full border flex items-center gap-2 ${totalEvents > 0 ? 'bg-red-50 border-red-200 text-red-700' : 'bg-green-50 border-green-200 text-green-700'
              }`}>
              <div className={`w-2.5 h-2.5 rounded-full animate-pulse ${totalEvents > 0 ? 'bg-red-600' : 'bg-green-600'
                }`}></div>
              <span className="font-bold text-sm">
                {totalEvents > 0 ? 'Threats Detected' : 'System Secure'}
              </span>
            </div>
          </div>

          {/* Threat Summary Cards */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
            <div className="bg-gradient-to-br from-red-500 to-red-600 rounded-2xl p-6 text-white shadow-lg shadow-red-200">
              <div className="text-red-100 text-sm font-semibold uppercase tracking-wider mb-2">Critical Threats</div>
              <div className="text-5xl font-black">{criticalEvents}</div>
              <div className="mt-4 text-sm text-white/80 bg-white/20 inline-block px-3 py-1 rounded-lg">
                <i className="fas fa-exclamation-triangle mr-2"></i> Immediate Action Required
              </div>
            </div>

            <div className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm hover:shadow-md transition-shadow">
              <div className="flex items-start justify-between mb-4">
                <div>
                  <div className="text-gray-500 text-sm font-semibold uppercase tracking-wider">Warnings</div>
                  <div className="text-4xl font-bold text-gray-900 mt-1">{warningEvents}</div>
                </div>
                <div className="w-10 h-10 bg-amber-100 rounded-lg flex items-center justify-center text-amber-600">
                  <i className="fas fa-bell"></i>
                </div>
              </div>
              <div className="w-full bg-gray-100 rounded-full h-1.5 mt-2">
                <div className="bg-amber-500 h-1.5 rounded-full" style={{ width: `${Math.min(100, warningEvents * 5)}%` }}></div>
              </div>
            </div>

            <div className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm hover:shadow-md transition-shadow">
              <div className="flex items-start justify-between mb-4">
                <div>
                  <div className="text-gray-500 text-sm font-semibold uppercase tracking-wider">Total Events</div>
                  <div className="text-4xl font-bold text-gray-900 mt-1">{totalEvents}</div>
                </div>
                <div className="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center text-blue-600">
                  <i className="fas fa-list"></i>
                </div>
              </div>
              <div className="text-sm text-gray-500 mt-2">
                across <span className="font-bold text-gray-900">{dashboard?.securityMetrics?.podCount || 'all'}</span> pods
              </div>
            </div>
          </div>

          {/* Alert Feed */}
          <div>
            <h4 className="font-bold text-gray-900 text-lg mb-4 flex items-center gap-2">
              <i className="fas fa-stream text-gray-400"></i>
              Latest Security Alerts
            </h4>
            <div className="space-y-3">
              {sortedAlertsByPriority?.length === 0 ? (
                <div className="text-center py-12 bg-gray-50 rounded-xl border-2 border-dashed border-gray-200">
                  <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
                    <i className="fas fa-check text-green-600 text-2xl"></i>
                  </div>
                  <h3 className="text-lg font-bold text-gray-900">No Security Alerts</h3>
                  <p className="text-gray-500">Your cluster is running securely. No Falco events detected.</p>
                </div>
              ) : (
                sortedAlertsByPriority?.slice(0, 10).map((alert: any, index: number) => (
                  <div key={index} className="group bg-white border border-gray-200 hover:border-red-300 rounded-xl p-4 transition-all hover:shadow-md flex items-center justify-between">
                    <div className="flex items-start gap-4">
                      <div className={`mt-1 w-2 h-12 rounded-full ${alert.priority === 'CRITICAL' || alert.priority === '4' ? 'bg-red-500' :
                          alert.priority === 'WARNING' || alert.priority === '3' ? 'bg-amber-500' : 'bg-blue-500'
                        }`}></div>
                      <div>
                        <h5 className="font-bold text-gray-900 text-base">{alert.rule}</h5>
                        <div className="flex items-center gap-3 mt-1 text-sm text-gray-500">
                          {alert.namespace && (
                            <span className="bg-gray-100 px-2 py-0.5 rounded text-xs font-mono">
                              ns: {alert.namespace}
                            </span>
                          )}
                          <span className="flex items-center gap-1">
                            <i className="far fa-clock text-xs"></i> Just now
                          </span>
                        </div>
                      </div>
                    </div>
                    <div className="text-center px-4">
                      <div className="text-2xl font-black text-gray-900">{alert.count}</div>
                      <div className="text-xs text-gray-500 font-semibold uppercase">Events</div>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </section>
    </div>
  );
};

export default EksSecurityTab;
