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
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useGcpAlertsData } from '../hooks/useGcpAlertsData';
import SreInvestigateButton from '../components/SreInvestigateButton';

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
        getServiceIconClass,
        getGcpConsoleLink,
        getGcpActionText,
    } = useGcpAlertsData({ selectedAccountId });

    const { setActions, clearActions } = useTopbar();

    useEffect(() => {
        setActions(
            <div className="flex items-center gap-3">
                <div className="flex items-center gap-1.5">
                    <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse" />
                    <span className="text-[12px] font-medium text-[rgba(0,0,0,0.5)]">Active monitoring</span>
                </div>
                <button
                    onClick={() => loadAlerts(true)}
                    disabled={isRefreshing}
                    className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
                >
                    <Icon icon="mdi:refresh" width={14} className={isRefreshing ? 'animate-spin' : ''} />
                    <span>{isRefreshing ? 'Scanning...' : 'Refresh Alerts'}</span>
                </button>
            </div>
        );
        return () => clearActions();
    }, [setActions, clearActions, isRefreshing, loadAlerts]);

    return (
        <div className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen">
            <div className="space-y-5">

                {/* No Account Selected */}
                {!selectedAccountId && !isLoading && (
                    <div className="flex-1 flex items-center justify-center py-24">
                        <div className="text-center max-w-md">
                            <div className="w-16 h-16 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center mx-auto mb-5">
                                <Icon icon="mdi:bell-outline" width={32} className="text-indigo-500" />
                            </div>
                            <h3 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">No GCP Account Selected</h3>
                            <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Please select a GCP account from the sidebar to view alerts.</p>
                        </div>
                    </div>
                )}

                {/* KPI Cards */}
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                    {isLoading ? (
                        <>
                            <div className="bg-[rgba(0,0,0,0.04)] rounded-xl h-24 animate-pulse" />
                            <div className="bg-[rgba(0,0,0,0.04)] rounded-xl h-24 animate-pulse" />
                            <div className="bg-[rgba(0,0,0,0.04)] rounded-xl h-24 animate-pulse" />
                            <div className="bg-[rgba(0,0,0,0.04)] rounded-xl h-24 animate-pulse" />
                        </>
                    ) : (
                        <>
                            {/* Total Alerts */}
                            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
                                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">TOTAL ALERTS</p>
                                <p
                                    className="tabular-nums text-[rgba(0,0,0,0.88)]"
                                    style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px' }}
                                >
                                    {alertCounts.total}
                                </p>
                                <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">monitored services</p>
                            </div>

                            {/* Critical Usage */}
                            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
                                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">CRITICAL USAGE</p>
                                <p
                                    className="tabular-nums text-red-600"
                                    style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px' }}
                                >
                                    {alertCounts.critical}
                                </p>
                                <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">above 90%</p>
                            </div>

                            {/* Warning */}
                            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
                                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">WARNING</p>
                                <p
                                    className="tabular-nums text-amber-500"
                                    style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px' }}
                                >
                                    {alertCounts.warning}
                                </p>
                                <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">75–90%</p>
                            </div>

                            {/* Healthy */}
                            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
                                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">HEALTHY</p>
                                <p
                                    className="tabular-nums text-emerald-600"
                                    style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px' }}
                                >
                                    {alertCounts.healthy}
                                </p>
                                <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">below 75%</p>
                            </div>
                        </>
                    )}
                </div>

                {/* Main Table Card */}
                {selectedAccountId && (
                    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">

                        {/* Card Header */}
                        <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between flex-wrap gap-3">
                            <div className="flex items-center gap-3">
                                <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
                                    <Icon icon="mdi:chart-bar" width={16} className="text-indigo-600" />
                                </div>
                                <div>
                                    <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">GCP Alerts Overview</h2>
                                    <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Monitor quotas, cost anomalies, and security findings</p>
                                </div>
                            </div>
                        </div>

                        {/* Filter Bar */}
                        <div className="px-5 py-3 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3 flex-wrap">
                            <div className="flex items-center gap-2">
                                <Icon icon="mdi:filter-outline" width={14} className="text-[rgba(0,0,0,0.4)]" />
                                <label className="text-[13px] font-medium text-[rgba(0,0,0,0.6)]">Filter by Service/Category:</label>
                                <select
                                    value={selectedService}
                                    onChange={(e) => setSelectedService(e.target.value)}
                                    className="px-3 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] font-medium text-[rgba(0,0,0,0.7)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                                >
                                    <option value="All">All Services/Categories</option>
                                    {services.map((service) => (
                                        <option key={service} value={service}>{service}</option>
                                    ))}
                                </select>
                            </div>
                            <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)] px-1.5 py-0.5 rounded-md">
                                {filteredAlerts.length} alert{filteredAlerts.length !== 1 ? 's' : ''}
                            </span>
                        </div>

                        {/* Loading Skeleton */}
                        {isLoading && (
                            <div className="p-5 space-y-3">
                                {[...Array(6)].map((_, i) => (
                                    <div key={i} className="h-10 bg-[rgba(0,0,0,0.04)] rounded animate-pulse" />
                                ))}
                            </div>
                        )}

                        {/* Empty State */}
                        {!isLoading && filteredAlerts.length === 0 && (
                            <div className="py-16 text-center">
                                <div className="w-14 h-14 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center mx-auto mb-3">
                                    <Icon icon="mdi:check-circle-outline" width={28} className="text-emerald-500" />
                                </div>
                                <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">All Clear!</h3>
                                <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-4">No critical alerts found for the selected filter.</p>
                                <button
                                    onClick={() => loadAlerts(true)}
                                    className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-[13px] font-semibold rounded-lg hover:bg-indigo-700 transition-all mx-auto"
                                >
                                    <Icon icon="mdi:refresh" width={14} />
                                    Refresh Check
                                </button>
                            </div>
                        )}

                        {/* Alerts Table */}
                        {!isLoading && filteredAlerts.length > 0 && (
                            <div className="overflow-x-auto">
                                <table className="w-full text-sm">
                                    <thead>
                                        <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                                            <th className="px-5 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Service / Category</th>
                                            <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Alert Type</th>
                                            <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Alert / Finding</th>
                                            <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Details / Usage</th>
                                            <th className="px-4 py-3 text-center text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Action</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {filteredAlerts.map((alert) => (
                                            <tr key={alert.id} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors">

                                                {/* Service / Category */}
                                                <td className="px-5 py-3">
                                                    <div className="flex items-center gap-2.5">
                                                        <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${getServiceIconClass(alert.service)}`}>
                                                            <Icon icon="mdi:cog-outline" width={14} />
                                                        </div>
                                                        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{alert.service}</span>
                                                    </div>
                                                </td>

                                                {/* Alert Type */}
                                                <td className="px-4 py-3">
                                                    <span className={`inline-flex items-center px-2 py-0.5 rounded text-[11px] font-semibold border ${alert.type === 'QUOTA'
                                                            ? 'bg-blue-50 text-blue-700 border-blue-200'
                                                            : alert.type === 'ANOMALY'
                                                                ? 'bg-amber-50 text-amber-700 border-amber-200'
                                                                : 'bg-red-50 text-red-700 border-red-200'
                                                        }`}>
                                                        {alert.type}
                                                    </span>
                                                </td>

                                                {/* Alert / Finding */}
                                                <td className="px-4 py-3">
                                                    <div>
                                                        <div className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] mb-0.5">{alert.name}</div>
                                                        <div className="text-[12px] text-[rgba(0,0,0,0.5)]">{alert.description}</div>
                                                    </div>
                                                </td>

                                                {/* Details / Usage */}
                                                <td className="px-4 py-3">
                                                    {alert.type === 'QUOTA' && (
                                                        <div className="space-y-2">
                                                            {alert.region && (
                                                                <div className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium bg-blue-50 text-blue-700 border border-blue-200">
                                                                    <i className="fas fa-map-marker-alt"></i>
                                                                    {alert.region}
                                                                </div>
                                                            )}
                                                            <div className="progress-bar">
                                                                <div
                                                                    className={`h-full rounded-full ${getUsageColor(alert)}`}
                                                                    style={{ width: `${Math.min(getUsagePercentage(alert), 100)}%` }}
                                                                />
                                                            </div>
                                                            <div className="flex items-center justify-between">
                                                                <span className={`text-[11px] font-bold px-1.5 py-0.5 rounded ${getUsageBadgeClass(alert)}`}>
                                                                    {Math.round(getUsagePercentage(alert))}%
                                                                </span>
                                                                <span className="text-[12px] text-[rgba(0,0,0,0.5)]">
                                                                    {alert.usage} / {alert.limit}
                                                                </span>
                                                            </div>
                                                        </div>
                                                    )}

                                                    {alert.type === 'ANOMALY' && (
                                                        <span className="inline-flex items-center px-2.5 py-1 rounded text-[12px] font-bold bg-red-50 text-red-700 border border-red-200">
                                                            ${alert.usage ? alert.usage.toLocaleString() : 'N/A'}
                                                        </span>
                                                    )}

                                                    {alert.type === 'SECURITY' && (
                                                        <div className="space-y-1">
                                                            <span className={`inline-block px-2 py-0.5 rounded text-[11px] font-bold ${getSecurityBadgeClass(alert.status)}`}>
                                                                {alert.status}
                                                            </span>
                                                            <div className="flex items-center gap-1 text-[12px] text-[rgba(0,0,0,0.5)]">
                                                                <Icon icon="mdi:map-marker-outline" width={12} />
                                                                {alert.region}
                                                            </div>
                                                        </div>
                                                    )}
                                                </td>

                                                {/* Action */}
                                                <td className="px-4 py-3 text-center">
                                                    <div className="flex flex-col gap-2 items-center">
                                                        <a
                                                            href={getGcpConsoleLink(alert)}
                                                            target="_blank"
                                                            rel="noopener noreferrer"
                                                            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[12px] font-semibold rounded-lg transition-all"
                                                        >
                                                            <Icon icon="mdi:open-in-new" width={12} />
                                                            {getGcpActionText(alert)}
                                                        </a>
                                                        <SreInvestigateButton
                                                            resourceType="GCP_VM"
                                                            resourceId={alert.id}
                                                            resourceName={alert.name}
                                                            cloudAccountId={selectedAccountId ? Number(selectedAccountId) : undefined}
                                                        />
                                                    </div>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </div>
                )}

            </div>
        </div>
    );
};

export default GcpAlertsPage;
