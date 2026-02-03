/**
 * GCP Alerts Page
 *
 * Converted from: gcp_alerts.html
 *
 * Monitors GCP alerts across three categories:
 * - QUOTA alerts: VPC network limits and other resource quotas
 * - ANOMALY alerts: Cost anomalies detected in billing
 * - SECURITY alerts: Critical and high-severity security findings
 *
 * API Endpoint:
 * - GET /api/xamops/gcp/cloudguard/alerts?accountId={projectId}&forceRefresh={bool}
 */

import React, { useEffect } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useGcpAlertsData } from '../hooks/useGcpAlertsData';
import clsx from 'clsx';

const GcpAlertsPage: React.FC = () => {
    const { selectedAccountIds } = useAccounts();
    const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

    const {
        filteredAlerts,
        isLoading,
        isRefreshing,
        selectedService,
        services,
        alertCounts,
        loadAlerts,
        setSelectedService,
        getUsagePercentage,
        getUsageColor,
        getUsageBadgeClass,
        getSecurityBadgeClass,
        getAlertClass,
        getGcpServiceIcon,
        getServiceIconClass,
        getGcpConsoleLink,
        getGcpActionText,
    } = useGcpAlertsData({ selectedAccountId });

    // Topbar Integration
    const { setActions, clearActions } = useTopbar();

    useEffect(() => {
        setActions(
            <div className="flex items-center gap-3">
                <div className="flex items-center space-x-2 mr-2">
                    <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse"></div>
                    <span className="text-xs font-medium text-gray-500">Active monitoring</span>
                </div>

                <button
                    onClick={() => loadAlerts(true)}
                    disabled={isRefreshing}
                    className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
                >
                    <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
                    <span>{isRefreshing ? 'Scanning...' : 'Refresh Alerts'}</span>
                </button>
            </div>
        );
        return () => clearActions();
    }, [setActions, clearActions, isRefreshing, loadAlerts]);

    return (
        <div className="flex flex-col h-screen overflow-hidden bg-gray-50">
            {/* Header Removed - Integrated into Topbar */}

            {/* Main Content */}
            <div className="flex-1 p-8 space-y-8 overflow-y-auto">
                {/* No Account Selected */}
                {!selectedAccountId && !isLoading && (
                    <div className="empty-state text-center py-20 px-8 rounded-2xl">
                        <div className="w-20 h-20 bg-gradient-to-br from-green-500 to-emerald-600 rounded-full flex items-center justify-center mx-auto mb-6">
                            <i className="fas fa-bell text-white text-3xl"></i>
                        </div>
                        <h3 className="text-2xl font-bold text-gray-800 mb-2">No GCP Account Selected</h3>
                        <p className="text-gray-600 mb-6">
                            Please select a GCP account from the sidebar to view alerts.
                        </p>
                    </div>
                )}

                {/* KPI Cards */}
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                    {isLoading ? (
                        <>
                            <div className="skeleton h-36"></div>
                            <div className="skeleton h-36"></div>
                            <div className="skeleton h-36"></div>
                            <div className="skeleton h-36"></div>
                        </>
                    ) : (
                        <>
                            {/* Total Alerts */}
                            <div className="quota-card rounded-2xl p-6 fade-in">
                                <div className="flex justify-between items-start mb-4">
                                    <div className="flex-1">
                                        <h3 className="text-gray-600 text-sm font-medium mb-2">Total Alerts</h3>
                                        <p className="text-4xl font-bold gradient-text mb-1">{alertCounts.total}</p>
                                        <p className="text-xs text-gray-500">monitored services</p>
                                    </div>
                                    <div className="w-14 h-14 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center">
                                        <i className="fas fa-list text-white text-xl"></i>
                                    </div>
                                </div>
                            </div>

                            {/* Critical Usage */}
                            <div className="quota-card rounded-2xl p-6 fade-in">
                                <div className="flex justify-between items-start mb-4">
                                    <div className="flex-1">
                                        <h3 className="text-gray-600 text-sm font-medium mb-2">Critical Usage</h3>
                                        <p className="text-4xl font-bold text-red-600 mb-1">{alertCounts.critical}</p>
                                        <p className="text-xs text-gray-500">issues requiring attention</p>
                                    </div>
                                    <div className="w-14 h-14 bg-gradient-to-br from-red-500 to-red-600 rounded-xl flex items-center justify-center">
                                        <i className="fas fa-exclamation-triangle text-white text-xl"></i>
                                    </div>
                                </div>
                            </div>

                            {/* Warning */}
                            <div className="quota-card rounded-2xl p-6 fade-in">
                                <div className="flex justify-between items-start mb-4">
                                    <div className="flex-1">
                                        <h3 className="text-gray-600 text-sm font-medium mb-2">Warning</h3>
                                        <p className="text-4xl font-bold text-yellow-500 mb-1">{alertCounts.warning}</p>
                                        <p className="text-xs text-gray-500">potential issues</p>
                                    </div>
                                    <div className="w-14 h-14 bg-gradient-to-br from-yellow-500 to-yellow-600 rounded-xl flex items-center justify-center">
                                        <i className="fas fa-exclamation-circle text-white text-xl"></i>
                                    </div>
                                </div>
                            </div>

                            {/* Healthy */}
                            <div className="quota-card rounded-2xl p-6 fade-in">
                                <div className="flex justify-between items-start mb-4">
                                    <div className="flex-1">
                                        <h3 className="text-gray-600 text-sm font-medium mb-2">Healthy</h3>
                                        <p className="text-4xl font-bold text-green-600 mb-1">{alertCounts.healthy}</p>
                                        <p className="text-xs text-gray-500">monitored items OK</p>
                                    </div>
                                    <div className="w-14 h-14 bg-gradient-to-br from-green-500 to-green-600 rounded-xl flex items-center justify-center">
                                        <i className="fas fa-check-circle text-white text-xl"></i>
                                    </div>
                                </div>
                            </div>
                        </>
                    )}
                </div>

                {/* Alerts Table Card */}
                {selectedAccountId && (
                    <div className="glass-card rounded-2xl p-8 fade-in">
                        {/* Card Header */}
                        <div className="flex items-center justify-between mb-8">
                            <div className="flex items-center space-x-4">
                                <div className="w-10 h-10 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-xl flex items-center justify-center">
                                    <i className="fas fa-chart-bar text-white text-lg"></i>
                                </div>
                                <div>
                                    <h2 className="text-xl font-bold text-gray-800">GCP Alerts Overview</h2>
                                    <p className="text-sm text-gray-600">Monitor quotas, cost anomalies, and security findings</p>
                                </div>
                            </div>
                        </div>

                        {/* Filter Section */}
                        <div className="filter-section rounded-xl p-4 mb-8">
                            <div className="flex items-center justify-between">
                                <div className="flex items-center space-x-4">
                                    <div className="flex items-center space-x-2">
                                        <i className="fas fa-filter text-indigo-600"></i>
                                        <label htmlFor="service-filter" className="text-sm font-semibold text-gray-700">
                                            Filter by Service/Category:
                                        </label>
                                    </div>
                                    <select
                                        id="service-filter"
                                        value={selectedService}
                                        onChange={(e) => setSelectedService(e.target.value)}
                                        className="form-select px-4 py-2 rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                                    >
                                        <option value="All">All Services/Categories</option>
                                        {services.map((service) => (
                                            <option key={service} value={service}>
                                                {service}
                                            </option>
                                        ))}
                                    </select>
                                </div>
                                <div className="text-sm text-gray-600">
                                    <span>{filteredAlerts.length}</span> alert{filteredAlerts.length !== 1 ? 's' : ''} shown
                                </div>
                            </div>
                        </div>

                        {/* Loading State */}
                        {isLoading && (
                            <div className="table-enhanced">
                                <table className="w-full text-sm">
                                    <thead className="text-xs text-gray-700 uppercase bg-gradient-to-r from-gray-50 to-gray-100">
                                        <tr>
                                            <th className="px-6 py-4">
                                                <div className="h-4 skeleton rounded w-32"></div>
                                            </th>
                                            <th className="px-6 py-4">
                                                <div className="h-4 skeleton rounded w-24"></div>
                                            </th>
                                            <th className="px-6 py-4">
                                                <div className="h-4 skeleton rounded w-48"></div>
                                            </th>
                                            <th className="px-6 py-4">
                                                <div className="h-4 skeleton rounded w-full"></div>
                                            </th>
                                            <th className="px-6 py-4">
                                                <div className="h-4 skeleton rounded w-24"></div>
                                            </th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {[...Array(8)].map((_, i) => (
                                            <tr key={i} className="border-b border-gray-200">
                                                <td className="px-6 py-4">
                                                    <div className="h-4 skeleton rounded w-24"></div>
                                                </td>
                                                <td className="px-6 py-4">
                                                    <div className="h-4 skeleton rounded w-16"></div>
                                                </td>
                                                <td className="px-6 py-4">
                                                    <div className="h-4 skeleton rounded w-full"></div>
                                                </td>
                                                <td className="px-6 py-4">
                                                    <div className="h-3 skeleton rounded-full w-full"></div>
                                                </td>
                                                <td className="px-6 py-4">
                                                    <div className="h-8 skeleton rounded-md w-32"></div>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}

                        {/* Empty State */}
                        {!isLoading && filteredAlerts.length === 0 && (
                            <div className="empty-state text-center py-20 px-8 rounded-2xl">
                                <div className="w-20 h-20 bg-gradient-to-br from-green-500 to-emerald-600 rounded-full flex items-center justify-center mx-auto mb-6">
                                    <i className="fas fa-check-circle text-white text-3xl"></i>
                                </div>
                                <h3 className="text-2xl font-bold text-gray-800 mb-2">All Clear!</h3>
                                <p className="text-gray-600 mb-6">No critical alerts found for the selected filter.</p>
                                <button
                                    onClick={() => loadAlerts(true)}
                                    className="btn-primary px-6 py-3 text-sm font-semibold rounded-lg"
                                >
                                    <i className="fas fa-sync-alt mr-2"></i>Refresh Check
                                </button>
                            </div>
                        )}

                        {/* Alerts Table */}
                        {!isLoading && filteredAlerts.length > 0 && (
                            <div className="table-enhanced">
                                <table className="w-full text-sm">
                                    <thead className="text-xs text-gray-700 uppercase bg-gradient-to-r from-gray-50 to-gray-100">
                                        <tr>
                                            <th className="px-6 py-4 text-left font-bold">Service / Category</th>
                                            <th className="px-6 py-4 text-left font-bold">Alert Type</th>
                                            <th className="px-6 py-4 text-left font-bold">Alert / Finding</th>
                                            <th className="px-6 py-4 text-left font-bold">Details / Usage</th>
                                            <th className="px-6 py-4 text-center font-bold">Action</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-gray-200">
                                        {filteredAlerts.map((alert) => (
                                            <tr key={alert.id} className={`hover:bg-gray-50 transition-all ${getAlertClass(alert)}`}>
                                                {/* Service/Category Column */}
                                                <td className="px-6 py-4 quota-cell-indicator">
                                                    <div className="flex items-center space-x-3">
                                                        <div className={`service-icon w-8 h-8 rounded-lg flex items-center justify-center ${getServiceIconClass(alert.service)}`}>
                                                            <i className={`fas ${getGcpServiceIcon(alert.service)}`}></i>
                                                        </div>
                                                        <span className="font-semibold text-gray-800">{alert.service}</span>
                                                    </div>
                                                </td>

                                                {/* Alert Type Column */}
                                                <td className="px-6 py-4">
                                                    <span className={`alert-type-badge alert-type-${alert.type.toLowerCase()}`}>
                                                        {alert.type}
                                                    </span>
                                                </td>

                                                {/* Alert/Finding Column */}
                                                <td className="px-6 py-4">
                                                    <div>
                                                        <div className="font-semibold text-gray-800 mb-1">{alert.name}</div>
                                                        <div className="text-xs text-gray-600">{alert.description}</div>
                                                    </div>
                                                </td>

                                                {/* Details/Usage Column - Conditional Rendering */}
                                                <td className="px-6 py-4">
                                                    {alert.type === 'QUOTA' && (
                                                        <div className="space-y-2">
                                                            <div className="progress-bar">
                                                                <div
                                                                    className={`progress-bar-fill ${getUsageColor(alert)}`}
                                                                    style={{ width: `${Math.min(getUsagePercentage(alert), 100)}%` }}
                                                                ></div>
                                                            </div>
                                                            <div className="flex items-center justify-between">
                                                                <span className={`usage-badge ${getUsageBadgeClass(alert)}`}>
                                                                    {Math.round(getUsagePercentage(alert))}%
                                                                </span>
                                                                <span className="text-xs text-gray-600">
                                                                    {alert.usage} / {alert.limit} networks
                                                                </span>
                                                            </div>
                                                        </div>
                                                    )}

                                                    {alert.type === 'ANOMALY' && (
                                                        <div>
                                                            <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-bold bg-red-100 text-red-800 border border-red-200">
                                                                ${alert.usage ? alert.usage.toLocaleString() : 'N/A'}
                                                            </span>
                                                        </div>
                                                    )}

                                                    {alert.type === 'SECURITY' && (
                                                        <div className="space-y-1">
                                                            <span className={`severity-badge ${getSecurityBadgeClass(alert.status)}`}>
                                                                {alert.status}
                                                            </span>
                                                            <div className="text-xs text-gray-600">
                                                                <i className="fas fa-map-marker-alt mr-1"></i>
                                                                {alert.region}
                                                            </div>
                                                        </div>
                                                    )}
                                                </td>

                                                {/* Action Column */}
                                                <td className="px-6 py-4 text-center">
                                                    <a
                                                        href={getGcpConsoleLink(alert)}
                                                        target="_blank"
                                                        rel="noopener noreferrer"
                                                        className="inline-flex items-center px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold rounded-lg shadow-md transition-all duration-200 hover:shadow-lg"
                                                    >
                                                        <i className="fas fa-external-link-alt mr-2"></i>
                                                        {getGcpActionText(alert)}
                                                    </a>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}

                        {/* No Account Selected State */}
                        {!selectedAccountId && (
                            <div className="empty-state text-center py-20 px-8 rounded-2xl">
                                <div className="w-20 h-20 bg-gradient-to-br from-blue-500 to-cyan-600 rounded-full flex items-center justify-center mx-auto mb-6">
                                    <i className="fas fa-cloud text-white text-3xl"></i>
                                </div>
                                <h3 className="text-2xl font-bold text-gray-800 mb-2">No Account Selected</h3>
                                <p className="text-gray-600 mb-6">Please select a GCP account from the dropdown above to view alerts.</p>
                            </div>
                        )}
                    </div>
                )}
            </div>
        </div>
    );
};

export default GcpAlertsPage;
