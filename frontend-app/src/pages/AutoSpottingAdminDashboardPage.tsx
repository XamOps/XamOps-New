/**
 * AutoSpotting Admin Dashboard Page
 * 
 * Centralized monitoring dashboard for AutoSpotting cost savings across all customer accounts.
 * 
 * Features:
 * - Total savings metrics (all-time, monthly, daily)
 * - Active spot instances count and conversion rate
 * - Historical savings trend chart
 * - Recent activity table with real-time updates
 * - Account breakdown visualization
 */

import React, { useEffect, useRef, useState, useCallback } from 'react';
import Chart from 'chart.js/auto';
import { useTopbar } from '../contexts/TopbarContext';
import { useAutoSpottingCache } from '../contexts/AutoSpottingCacheContext';
import { DashboardSkeleton } from '../components/Shared/Skeleton';
import {
    SavingsMetrics,
    SpotActivity,
    HistoricalSavings,
    AccountSavings
} from '../types/autospotting';

const AutoSpottingAdminDashboardPage: React.FC = () => {
    const { setActions, clearActions } = useTopbar();
    const { getAdminData, setAdminData } = useAutoSpottingCache();

    // State
    const [metrics, setMetrics] = useState<SavingsMetrics | null>(null);
    const [recentActivity, setRecentActivity] = useState<SpotActivity[]>([]);
    const [historicalData, setHistoricalData] = useState<HistoricalSavings[]>([]);
    const [accountBreakdown, setAccountBreakdown] = useState<AccountSavings[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [dateRange, setDateRange] = useState('7d'); // 7d, 30d, 90d
    const [isInitialLoad, setIsInitialLoad] = useState(true);

    // Chart refs
    const savingsChartRef = useRef<HTMLCanvasElement>(null);
    const accountChartRef = useRef<HTMLCanvasElement>(null);
    const savingsChartInstance = useRef<Chart<'line'> | null>(null);
    const accountChartInstance = useRef<Chart<'doughnut'> | null>(null);

    // Fetch dashboard data
    const fetchDashboardData = useCallback(async (isRefresh = false) => {
        try {
            if (!isRefresh && !metrics) {
                setLoading(true);
            }
            setError(null);

            // Check cache first
            if (!isRefresh && isInitialLoad) {
                const cachedData = getAdminData(dateRange);
                if (cachedData) {
                    console.log('[AutoSpotting Admin] Using cached data');
                    setMetrics(cachedData.metrics);
                    setRecentActivity(cachedData.recentActivity);
                    setHistoricalData(cachedData.historicalData);
                    setAccountBreakdown(cachedData.accountBreakdown);
                    setLoading(false);
                    setIsInitialLoad(false);
                    // Refresh in background
                }
            }

            // Fetch all data in parallel
            const [metricsRes, activityRes, historicalRes, breakdownRes] = await Promise.all([
                fetch('/api/admin/autospotting/total-savings'),
                fetch('/api/admin/autospotting/recent-activity?limit=50'),
                fetch(`/api/admin/autospotting/historical-savings?days=${dateRange === '7d' ? 7 : dateRange === '30d' ? 30 : 90}`),
                fetch('/api/admin/autospotting/account-breakdown')
            ]);

            if (!metricsRes.ok || !activityRes.ok || !historicalRes.ok || !breakdownRes.ok) {
                throw new Error('Failed to fetch dashboard data');
            }

            const [metricsData, activityData, historicalData, breakdownData] = await Promise.all([
                metricsRes.json(),
                activityRes.json(),
                historicalRes.json(),
                breakdownRes.json()
            ]);

            setMetrics(metricsData);
            setRecentActivity(activityData);
            setHistoricalData(historicalData);
            setAccountBreakdown(breakdownData);

            // Update cache
            setAdminData(dateRange, {
                metrics: metricsData,
                recentActivity: activityData,
                historicalData,
                accountBreakdown: breakdownData
            });
        } catch (err) {
            console.error('Error fetching dashboard data:', err);
            setError(err instanceof Error ? err.message : 'Failed to load dashboard');
        } finally {
            setLoading(false);
            setIsInitialLoad(false);
        }
    }, [dateRange, metrics, isInitialLoad, getAdminData, setAdminData]);

    // Topbar actions
    useEffect(() => {
        setActions(
            <div className="flex items-center gap-2">
                <button
                    onClick={() => fetchDashboardData(true)}
                    className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold shadow-lg shadow-indigo-500/20 hover:bg-indigo-700 transition-all"
                >
                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                    </svg>
                    Refresh
                </button>
            </div>
        );

        return () => clearActions();
    }, [setActions, clearActions, fetchDashboardData]);

    // Initial data fetch
    useEffect(() => {
        fetchDashboardData();
    }, [dateRange]);

    // Savings trend chart
    useEffect(() => {
        if (!savingsChartRef.current || !historicalData.length) return;

        // Destroy existing chart
        if (savingsChartInstance.current) {
            savingsChartInstance.current.destroy();
        }

        const ctx = savingsChartRef.current.getContext('2d');
        if (!ctx) return;

        savingsChartInstance.current = new Chart(ctx, {
            type: 'line',
            data: {
                labels: historicalData.map(d => new Date(d.date).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })),
                datasets: [{
                    label: 'Daily Savings',
                    data: historicalData.map(d => d.savings),
                    borderColor: '#F59E0B',
                    backgroundColor: 'rgba(245, 158, 11, 0.1)',
                    fill: true,
                    tension: 0.4,
                    pointRadius: 4,
                    pointHoverRadius: 6,
                    pointBackgroundColor: '#F59E0B',
                    pointBorderColor: '#0F172A',
                    pointBorderWidth: 2
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        backgroundColor: '#1E293B',
                        titleColor: '#F8FAFC',
                        bodyColor: '#F8FAFC',
                        borderColor: '#334155',
                        borderWidth: 1,
                        padding: 12,
                        displayColors: false,
                        callbacks: {
                            label: (context) => `Savings: $${context.parsed.y.toFixed(2)}`
                        }
                    }
                },
                scales: {
                    x: {
                        grid: {
                            color: '#1E293B',
                            drawBorder: false
                        },
                        ticks: {
                            color: '#94A3B8',
                            font: {
                                family: 'Fira Sans',
                                size: 11
                            }
                        }
                    },
                    y: {
                        grid: {
                            color: '#1E293B',
                            drawBorder: false
                        },
                        ticks: {
                            color: '#94A3B8',
                            font: {
                                family: 'Fira Sans',
                                size: 11
                            },
                            callback: (value) => `$${value}`
                        }
                    }
                }
            }
        });

        return () => {
            if (savingsChartInstance.current) {
                savingsChartInstance.current.destroy();
            }
        };
    }, [historicalData]);

    // Account breakdown chart
    useEffect(() => {
        if (!accountChartRef.current || !accountBreakdown.length) return;

        // Destroy existing chart
        if (accountChartInstance.current) {
            accountChartInstance.current.destroy();
        }

        const ctx = accountChartRef.current.getContext('2d');
        if (!ctx) return;

        const colors = ['#F59E0B', '#FBBF24', '#8B5CF6', '#EC4899', '#10B981', '#3B82F6'];

        accountChartInstance.current = new Chart(ctx, {
            type: 'doughnut',
            data: {
                labels: accountBreakdown.map(a => a.accountName),
                datasets: [{
                    data: accountBreakdown.map(a => a.totalSavings),
                    backgroundColor: colors.slice(0, accountBreakdown.length),
                    borderColor: '#0F172A',
                    borderWidth: 2
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        position: 'right',
                        labels: {
                            color: '#F8FAFC',
                            font: {
                                family: 'Fira Sans',
                                size: 12
                            },
                            padding: 15,
                            usePointStyle: true,
                            pointStyle: 'circle'
                        }
                    },
                    tooltip: {
                        backgroundColor: '#1E293B',
                        titleColor: '#F8FAFC',
                        bodyColor: '#F8FAFC',
                        borderColor: '#334155',
                        borderWidth: 1,
                        padding: 12,
                        callbacks: {
                            label: (context) => {
                                const account = accountBreakdown[context.dataIndex];
                                return [
                                    `Savings: $${account.totalSavings.toFixed(2)}`,
                                    `Instances: ${account.instanceCount}`
                                ];
                            }
                        }
                    }
                }
            }
        });

        return () => {
            if (accountChartInstance.current) {
                accountChartInstance.current.destroy();
            }
        };
    }, [accountBreakdown]);

    if (loading && !metrics) {
        return <DashboardSkeleton />;
    }

    if (error) {
        return (
            <div className="flex items-center justify-center min-h-screen bg-slate-900">
                <div className="bg-red-500/10 border border-red-500/20 rounded-lg p-6 max-w-md">
                    <h3 className="text-red-400 font-['Fira_Code'] text-lg mb-2">Error Loading Dashboard</h3>
                    <p className="text-slate-300 font-['Fira_Sans']">{error}</p>
                    <button
                        onClick={() => fetchDashboardData(true)}
                        className="mt-4 px-4 py-2 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded-lg transition-colors font-['Fira_Sans']"
                    >
                        Retry
                    </button>
                </div>
            </div>
        );
    }

    return (
        <div className="min-h-screen bg-slate-900 p-6">
            <div className="max-w-7xl mx-auto space-y-6">
                {/* Header */}
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-3xl font-bold text-slate-100 font-['Fira_Code']">
                            AutoSpotting Dashboard
                        </h1>
                        <p className="text-slate-400 mt-1 font-['Fira_Sans']">
                            Monitor spot instance cost savings across all customer accounts
                        </p>
                    </div>

                    {/* Date Range Selector */}
                    <div className="flex gap-2">
                        {['7d', '30d', '90d'].map((range) => (
                            <button
                                key={range}
                                onClick={() => setDateRange(range)}
                                className={`px-4 py-2 rounded-lg font-['Fira_Sans'] transition-all ${dateRange === range
                                    ? 'bg-amber-500 text-slate-900'
                                    : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                                    }`}
                            >
                                {range === '7d' ? 'Last 7 Days' : range === '30d' ? 'Last 30 Days' : 'Last 90 Days'}
                            </button>
                        ))}
                    </div>
                </div>

                {/* KPI Cards */}
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                    <KPICard
                        title="Total Savings"
                        value={`$${metrics?.totalSavings.toFixed(2) || '0.00'}`}
                        subtitle="All-time cost reduction"
                        icon={
                            <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                            </svg>
                        }
                        color="amber"
                    />

                    <KPICard
                        title="Monthly Savings"
                        value={`$${metrics?.monthlySavings.toFixed(2) || '0.00'}`}
                        subtitle="Current month"
                        icon={
                            <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                            </svg>
                        }
                        color="yellow"
                    />

                    <KPICard
                        title="Active Spot Instances"
                        value={metrics?.activeSpotInstances.toString() || '0'}
                        subtitle="Currently running"
                        icon={
                            <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01" />
                            </svg>
                        }
                        color="purple"
                    />

                    <KPICard
                        title="Conversion Rate"
                        value={`${metrics?.conversionRate.toFixed(1) || '0.0'}%`}
                        subtitle="On-demand → Spot"
                        icon={
                            <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                            </svg>
                        }
                        color="green"
                    />
                </div>

                {/* Charts Row */}
                <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                    {/* Savings Trend Chart */}
                    <div className="lg:col-span-2 bg-slate-800/50 backdrop-blur-sm border border-slate-700/50 rounded-xl p-6">
                        <h2 className="text-xl font-semibold text-slate-100 mb-4 font-['Fira_Code']">
                            Savings Trend
                        </h2>
                        <div className="h-80">
                            <canvas ref={savingsChartRef}></canvas>
                        </div>
                    </div>

                    {/* Account Breakdown Chart */}
                    <div className="bg-slate-800/50 backdrop-blur-sm border border-slate-700/50 rounded-xl p-6">
                        <h2 className="text-xl font-semibold text-slate-100 mb-4 font-['Fira_Code']">
                            Savings by Account
                        </h2>
                        <div className="h-80">
                            <canvas ref={accountChartRef}></canvas>
                        </div>
                    </div>
                </div>

                {/* Recent Activity Table */}
                <div className="bg-slate-800/50 backdrop-blur-sm border border-slate-700/50 rounded-xl p-6">
                    <h2 className="text-xl font-semibold text-slate-100 mb-4 font-['Fira_Code']">
                        Recent Activity
                    </h2>
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="border-b border-slate-700">
                                    <th className="text-left py-3 px-4 text-slate-400 font-['Fira_Sans'] font-medium text-sm">Timestamp</th>
                                    <th className="text-left py-3 px-4 text-slate-400 font-['Fira_Sans'] font-medium text-sm">Account</th>
                                    <th className="text-left py-3 px-4 text-slate-400 font-['Fira_Sans'] font-medium text-sm">Instance</th>
                                    <th className="text-left py-3 px-4 text-slate-400 font-['Fira_Sans'] font-medium text-sm">Type</th>
                                    <th className="text-left py-3 px-4 text-slate-400 font-['Fira_Sans'] font-medium text-sm">Event</th>
                                    <th className="text-left py-3 px-4 text-slate-400 font-['Fira_Sans'] font-medium text-sm">Hourly Savings</th>
                                    <th className="text-left py-3 px-4 text-slate-400 font-['Fira_Sans'] font-medium text-sm">Region</th>
                                </tr>
                            </thead>
                            <tbody>
                                {recentActivity.length === 0 ? (
                                    <tr>
                                        <td colSpan={7} className="text-center py-8 text-slate-500 font-['Fira_Sans']">
                                            No recent activity
                                        </td>
                                    </tr>
                                ) : (
                                    recentActivity.map((activity) => (
                                        <tr key={activity.id} className="border-b border-slate-700/50 hover:bg-slate-700/30 transition-colors">
                                            <td className="py-3 px-4 text-slate-300 font-['Fira_Code'] text-sm">
                                                {new Date(activity.timestamp).toLocaleString()}
                                            </td>
                                            <td className="py-3 px-4 text-slate-300 font-['Fira_Sans']">
                                                {activity.accountName}
                                            </td>
                                            <td className="py-3 px-4 text-slate-300 font-['Fira_Code'] text-sm">
                                                {activity.instanceId}
                                            </td>
                                            <td className="py-3 px-4 text-slate-300 font-['Fira_Sans']">
                                                {activity.instanceType} → {activity.spotInstanceType}
                                            </td>
                                            <td className="py-3 px-4">
                                                <span className={`px-2 py-1 rounded text-xs font-['Fira_Sans'] ${activity.eventType === 'LAUNCH'
                                                    ? 'bg-green-500/20 text-green-400'
                                                    : 'bg-red-500/20 text-red-400'
                                                    }`}>
                                                    {activity.eventType}
                                                </span>
                                            </td>
                                            <td className="py-3 px-4 text-amber-400 font-['Fira_Code']">
                                                ${activity.hourlySavings.toFixed(4)}/hr
                                            </td>
                                            <td className="py-3 px-4 text-slate-400 font-['Fira_Sans'] text-sm">
                                                {activity.region}
                                            </td>
                                        </tr>
                                    ))
                                )}
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
        </div>
    );
};

// KPI Card Component
interface KPICardProps {
    title: string;
    value: string;
    subtitle: string;
    icon: React.ReactNode;
    color: 'amber' | 'yellow' | 'purple' | 'green';
}

const KPICard: React.FC<KPICardProps> = ({ title, value, subtitle, icon, color }) => {
    const colorClasses = {
        amber: 'text-amber-500',
        yellow: 'text-yellow-500',
        purple: 'text-purple-500',
        green: 'text-green-500'
    };

    return (
        <div className="bg-slate-800/50 backdrop-blur-sm border border-slate-700/50 rounded-xl p-6 hover:border-slate-600/50 transition-all cursor-pointer group">
            <div className="flex items-start justify-between">
                <div className="flex-1">
                    <p className="text-slate-400 text-sm font-['Fira_Sans'] mb-1">{title}</p>
                    <p className={`text-3xl font-bold font-['Fira_Code'] ${colorClasses[color]} group-hover:scale-105 transition-transform`}>
                        {value}
                    </p>
                    <p className="text-slate-500 text-xs font-['Fira_Sans'] mt-1">{subtitle}</p>
                </div>
                <div className={`${colorClasses[color]} opacity-60 group-hover:opacity-100 transition-opacity`}>
                    {icon}
                </div>
            </div>
        </div>
    );
};

export default AutoSpottingAdminDashboardPage;
