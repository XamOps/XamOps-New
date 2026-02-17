/**
 * AutoSpotting Customer Dashboard Page
 *
 * Customer-facing dashboard for viewing their own AutoSpotting savings and activity.
 * Accessible to users with ROLE_XAMOPS_USER.
 *
 * Features:
 * - Personal savings metrics (monthly, all-time)
 * - Active spot instances for their account
 * - Recent activity timeline
 * - Optimization recommendations
 * - Cost comparison charts
 */

import React, { useEffect, useRef, useState, useMemo, useCallback } from 'react';
import Chart from 'chart.js/auto';
import { useTopbar } from '../contexts/TopbarContext';
import { useAccounts } from '../contexts/AccountContext';

interface CustomerSavings {
    currentMonthlyCost: number;
    onDemandMonthlyCost: number;
    actualMonthlySavings: number;
    potentialMonthlySavings: number;
    totalAsgs: number;
    enabledAsgs: number;
}

interface SpotInstance {
    instanceId: string;
    instanceType: string;
    availabilityZone: string;
    lifecycle: string; // "spot" or "on-demand"
    state: string;
    asgName: string;
    region: string;
    estimatedHourlySavings: number;
    launchTime: string;
}

interface ActivityEvent {
    id: string;
    timestamp: string;
    description: string;
    eventType: string;
    savings?: number;
    region?: string;
    asgName?: string;
}

interface Recommendation {
    id: number;
    title: string;
    description: string;
    potentialSavings: number;
    priority: 'high' | 'medium' | 'low';
    asgName?: string;
    region?: string;
}

interface AsgGroup {
    name: string;
    region: string;
    isEnabled: boolean;
    instanceCount: number;
    minSize: number;
    maxSize: number;
    type: string;
    currentHourlyCost?: number;
    actualHourlySavings?: number;
    potentialHourlySavings?: number;
    spotInstanceCount?: number;
    ondemandInstanceCount?: number;
}

type ActiveTab = 'dashboard' | 'asgs';

const AutoSpottingCustomerDashboardPage: React.FC = () => {
    const { setActions, clearActions } = useTopbar();
    const { selectedAccountDisplay } = useAccounts();
    const selectedAccount = useMemo(() => {
        return selectedAccountDisplay.id && selectedAccountDisplay.id !== 'No account selected'
            ? { accountId: selectedAccountDisplay.id, accountName: selectedAccountDisplay.name }
            : null;
    }, [selectedAccountDisplay]);

    // Tab state
    const [activeTab, setActiveTab] = useState<ActiveTab>('dashboard');

    // State
    const [savings, setSavings] = useState<CustomerSavings | null>(null);
    const [spotInstances, setSpotInstances] = useState<SpotInstance[]>([]);
    const [recentActivity, setRecentActivity] = useState<ActivityEvent[]>([]);
    const [recommendations, setRecommendations] = useState<Recommendation[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // ASGs tab state
    const [asgs, setAsgs] = useState<AsgGroup[]>([]);
    const [asgsLoading, setAsgsLoading] = useState(false);
    const [asgsError, setAsgsError] = useState<string | null>(null);
    const [togglingAsg, setTogglingAsg] = useState<string | null>(null);
    const [asgFilter, setAsgFilter] = useState('');

    // Chart refs
    const savingsChartRef = useRef<HTMLCanvasElement>(null);
    const savingsChartInstance = useRef<Chart<'bar'> | null>(null);

    // Fetch dashboard data
    const fetchDashboardData = useCallback(async () => {
        if (!selectedAccount) return;

        try {
            setLoading(true);
            setError(null);

            const accountId = selectedAccount.accountId;

            // Fetch all data in parallel
            const [savingsRes, instancesRes, activityRes] = await Promise.all([
                fetch(`/api/autospotting/savings/${accountId}`),
                fetch(`/api/autospotting/instances/${accountId}`),
                fetch(`/api/autospotting/events/${accountId}?limit=20`)
            ]);

            if (!savingsRes.ok || !instancesRes.ok || !activityRes.ok) {
                console.error("Fetch failed:", {
                    savings: savingsRes.status,
                    instances: instancesRes.status,
                    activity: activityRes.status
                });
                throw new Error('Failed to fetch AutoSpotting data');
            }

            const [savingsData, instancesData, activityData] = await Promise.all([
                savingsRes.json(),
                instancesRes.json(),
                activityRes.json()
            ]);

            // Map savings data
            setSavings(savingsData);

            // Map instances data
            setSpotInstances(instancesData);

            // Map activity data
            if (activityData && activityData.events) {
                const mappedActivity: ActivityEvent[] = activityData.events.map((event: any, index: number) => ({
                    id: `${event.time}-${index}`,
                    timestamp: event.timestamp || event.time, // Handle potential API diffs
                    description: event.description || event.eventType || event.type,
                    eventType: event.eventType || event.type,
                    savings: event.savings || event.estimatedSavings,
                    region: event.region,
                    asgName: event.asgName
                }));
                setRecentActivity(mappedActivity);
            }

            // Fetch Real Recommendations
            try {
                const recRes = await fetch(`/api/autospotting/recommendations/${accountId}`);
                if (recRes.ok) {
                    const recData = await recRes.json();
                    setRecommendations(recData);
                } else {
                    console.warn(`Failed to fetch recommendations: ${recRes.status}`);
                    setRecommendations([]);
                }
            } catch (e) {
                console.error("Error fetching recommendations:", e);
                setRecommendations([]);
            }

        } catch (err) {
            console.error('Error fetching dashboard data:', err);
            setError(err instanceof Error ? err.message : 'Failed to load dashboard');
        } finally {
            setLoading(false);
        }
    }, [selectedAccount]);

    // Fetch all ASGs in the account (all regions)
    const fetchAsgs = useCallback(async () => {
        if (!selectedAccount) return;
        setAsgsLoading(true);
        setAsgsError(null);
        try {
            const res = await fetch(`/api/autospotting/asgs/all-regions/${selectedAccount.accountId}`);
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const data: AsgGroup[] = await res.json();
            setAsgs(data);
        } catch (err) {
            setAsgsError(err instanceof Error ? err.message : 'Failed to load ASGs');
        } finally {
            setAsgsLoading(false);
        }
    }, [selectedAccount]);

    // Toggle AutoSpotting on/off for an ASG
    const toggleAsg = useCallback(async (asg: AsgGroup) => {
        if (!selectedAccount) return;
        const key = `${asg.region}:${asg.name}`;
        setTogglingAsg(key);
        try {
            const action = asg.isEnabled ? 'disable' : 'enable';
            const res = await fetch(
                `/api/autospotting/asgs/${selectedAccount.accountId}/${action}?region=${encodeURIComponent(asg.region)}&asgName=${encodeURIComponent(asg.name)}`,
                { method: 'POST' }
            );
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                throw new Error(`HTTP ${res.status}: ${body?.error ?? JSON.stringify(body)}`);
            }
            // Optimistically update the local list
            setAsgs(prev => prev.map(a =>
                a.name === asg.name && a.region === asg.region
                    ? { ...a, isEnabled: !a.isEnabled }
                    : a
            ));
        } catch (err) {
            console.error('Failed to toggle ASG:', err);
        } finally {
            setTogglingAsg(null);
        }
    }, [selectedAccount]);

    // Topbar actions
    useEffect(() => {
        const onRefresh = activeTab === 'asgs' ? fetchAsgs : fetchDashboardData;
        setActions(
            <div className="flex items-center gap-2">
                <button
                    onClick={onRefresh}
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
    }, [setActions, clearActions, activeTab, fetchDashboardData, fetchAsgs]);

    // Load ASGs when switching to that tab
    useEffect(() => {
        if (activeTab === 'asgs' && selectedAccount && asgs.length === 0 && !asgsLoading) {
            fetchAsgs();
        }
    }, [activeTab, selectedAccount]);

    // Initial data fetch
    useEffect(() => {
        if (selectedAccount) {
            fetchDashboardData();
        }
    }, [selectedAccount, fetchDashboardData]);

    // Savings comparison chart
    useEffect(() => {
        if (!savingsChartRef.current || !savings) return;

        // Destroy existing chart
        if (savingsChartInstance.current) {
            savingsChartInstance.current.destroy();
        }

        const ctx = savingsChartRef.current.getContext('2d');
        if (!ctx) return;

        const onDemandCost = savings.onDemandMonthlyCost > 0
            ? savings.onDemandMonthlyCost
            : (savings.actualMonthlySavings + savings.currentMonthlyCost);

        const spotCost = savings.currentMonthlyCost;

        savingsChartInstance.current = new Chart(ctx, {
            type: 'bar',
            data: {
                labels: ['On-Demand', 'With Spot Instances'],
                datasets: [{
                    label: 'Monthly Cost',
                    data: [onDemandCost, spotCost],
                    backgroundColor: [
                        'rgba(239, 68, 68, 0.15)',
                        'rgba(34, 197, 94, 0.15)'
                    ],
                    borderColor: [
                        'rgba(239, 68, 68, 0.8)',
                        'rgba(34, 197, 94, 0.8)'
                    ],
                    borderWidth: 2,
                    borderRadius: 6,
                    hoverBackgroundColor: [
                        'rgba(239, 68, 68, 0.25)',
                        'rgba(34, 197, 94, 0.25)'
                    ]
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
                        backgroundColor: 'rgba(255, 255, 255, 0.95)',
                        titleColor: '#1e293b',
                        bodyColor: '#475569',
                        borderColor: 'rgba(99, 102, 241, 0.2)',
                        borderWidth: 1,
                        padding: 12,
                        displayColors: false,
                        callbacks: {
                            label: (context) => `Cost: $${context.parsed.y.toFixed(2)}/month`
                        }
                    }
                },
                scales: {
                    x: {
                        grid: {
                            color: 'rgba(0, 0, 0, 0.06)',
                        },
                        ticks: {
                            color: '#64748b',
                            font: {
                                size: 12
                            }
                        }
                    },
                    y: {
                        grid: {
                            color: 'rgba(0, 0, 0, 0.06)',
                        },
                        ticks: {
                            color: '#64748b',
                            font: {
                                size: 12
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
    }, [savings]);

    // Filtered ASGs for the search box — must be before any early returns (Rules of Hooks)
    const filteredAsgs = useMemo(() => {
        if (!asgFilter.trim()) return asgs;
        const term = asgFilter.toLowerCase();
        return asgs.filter(a =>
            a.name.toLowerCase().includes(term) || a.region.toLowerCase().includes(term)
        );
    }, [asgs, asgFilter]);

    if (loading && !savings) {
        return (
            <div className="flex items-center justify-center min-h-[60vh]">
                <div className="text-center">
                    <div className="inline-block animate-spin rounded-full h-12 w-12 border-4 border-indigo-500 border-t-transparent"></div>
                    <p className="mt-4 text-gray-500">Loading AutoSpotting data...</p>
                </div>
            </div>
        );
    }

    if (error) {
        return (
            <div className="flex items-center justify-center min-h-[60vh]">
                <div className="billops-glass-card rounded-2xl p-6 max-w-md">
                    <h3 className="text-red-500 text-lg font-semibold mb-2">Error Loading Dashboard</h3>
                    <p className="text-gray-600 dark:text-gray-300">{error}</p>
                    <button
                        onClick={fetchDashboardData}
                        className="mt-4 px-4 py-2 bg-red-50 hover:bg-red-100 text-red-600 rounded-lg transition-colors font-medium dark:bg-red-500/20 dark:hover:bg-red-500/30 dark:text-red-400"
                    >
                        Retry
                    </button>
                </div>
            </div>
        );
    }

    if (!selectedAccount) {
        return (
            <div className="flex items-center justify-center min-h-[60vh]">
                <div className="text-center">
                    <svg className="w-16 h-16 mx-auto text-gray-300 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
                    </svg>
                    <p className="text-gray-500">Please select an AWS account to view AutoSpotting data</p>
                </div>
            </div>
        );
    }

    return (
        <div className="p-6">
            <div className="max-w-7xl mx-auto space-y-6">
                {/* Header + Tabs */}
                <div className="billops-glass-card rounded-2xl p-6">
                    <h1 className="text-2xl font-bold text-gray-900 dark:text-white mb-1">
                        AutoSpotting Dashboard
                    </h1>
                    <p className="text-gray-500 dark:text-gray-400 mb-5">
                        Track your spot instance cost savings for <span className="text-indigo-600 dark:text-indigo-400 font-semibold">{selectedAccount.accountName}</span>
                    </p>
                    {/* Tab Navigation */}
                    <div className="flex gap-1 bg-gray-100 dark:bg-white/5 rounded-xl p-1 w-fit">
                        <button
                            onClick={() => setActiveTab('dashboard')}
                            className={`px-4 py-2 rounded-lg text-sm font-semibold transition-all ${
                                activeTab === 'dashboard'
                                    ? 'bg-white dark:bg-indigo-600 text-indigo-600 dark:text-white shadow-sm'
                                    : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'
                            }`}
                        >
                            Dashboard
                        </button>
                        <button
                            onClick={() => setActiveTab('asgs')}
                            className={`px-4 py-2 rounded-lg text-sm font-semibold transition-all ${
                                activeTab === 'asgs'
                                    ? 'bg-white dark:bg-indigo-600 text-indigo-600 dark:text-white shadow-sm'
                                    : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'
                            }`}
                        >
                            Auto Scaling Groups
                            {asgs.length > 0 && (
                                <span className="ml-2 bg-indigo-100 dark:bg-indigo-500/30 text-indigo-600 dark:text-indigo-300 text-xs px-2 py-0.5 rounded-full">
                                    {asgs.length}
                                </span>
                            )}
                        </button>
                    </div>
                </div>

                {/* ASGs Tab */}
                {activeTab === 'asgs' && (
                    <div className="space-y-4 pb-6">
                        {/* Toolbar */}
                        <div className="flex items-center justify-between gap-4">
                            <div className="relative flex-1 max-w-xs">
                                <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                                </svg>
                                <input
                                    type="text"
                                    placeholder="Search ASGs or regions..."
                                    value={asgFilter}
                                    onChange={e => setAsgFilter(e.target.value)}
                                    className="w-full pl-9 pr-4 py-2 text-sm bg-white dark:bg-white/5 border border-gray-200 dark:border-white/10 rounded-xl text-gray-700 dark:text-gray-200 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                                />
                            </div>
                            <div className="flex items-center gap-3 text-sm text-gray-500 dark:text-gray-400">
                                <span>
                                    <span className="font-semibold text-emerald-600 dark:text-emerald-400">{asgs.filter(a => a.isEnabled).length}</span> enabled
                                </span>
                                <span>/</span>
                                <span>
                                    <span className="font-semibold text-gray-700 dark:text-gray-200">{asgs.length}</span> total
                                </span>
                            </div>
                        </div>

                        {/* Table */}
                        {asgsLoading ? (
                            <div className="billops-glass-card rounded-2xl p-12 flex items-center justify-center">
                                <div className="text-center">
                                    <div className="inline-block animate-spin rounded-full h-10 w-10 border-4 border-indigo-500 border-t-transparent mb-3"></div>
                                    <p className="text-gray-400 text-sm">Loading Auto Scaling Groups...</p>
                                </div>
                            </div>
                        ) : asgsError ? (
                            <div className="billops-glass-card rounded-2xl p-8 text-center">
                                <p className="text-red-500 font-medium mb-3">{asgsError}</p>
                                <button onClick={fetchAsgs} className="px-4 py-2 bg-red-50 dark:bg-red-500/20 text-red-600 dark:text-red-400 rounded-lg text-sm font-medium hover:bg-red-100 dark:hover:bg-red-500/30 transition-colors">
                                    Retry
                                </button>
                            </div>
                        ) : filteredAsgs.length === 0 ? (
                            <div className="billops-glass-card rounded-2xl p-12 text-center">
                                <svg className="w-14 h-14 mx-auto text-gray-300 dark:text-gray-600 mb-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 01-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01" />
                                </svg>
                                <p className="text-gray-400 text-sm">{asgFilter ? 'No ASGs match your search.' : 'No Auto Scaling Groups found in this account.'}</p>
                            </div>
                        ) : (
                            <div className="billops-glass-card rounded-2xl overflow-hidden">
                                <table className="w-full text-sm">
                                    <thead>
                                        <tr className="border-b border-gray-100 dark:border-white/10 bg-gray-50/50 dark:bg-white/3">
                                            <th className="text-left px-5 py-3 text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">ASG Name</th>
                                            <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">Region</th>
                                            <th className="text-center px-4 py-3 text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">Instances</th>
                                            <th className="text-center px-4 py-3 text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">Spot / OD</th>
                                            <th className="text-right px-4 py-3 text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">Hourly Cost</th>
                                            <th className="text-right px-4 py-3 text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">Savings</th>
                                            <th className="text-center px-5 py-3 text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">Spot Conversion</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {filteredAsgs.map((asg, idx) => {
                                            const key = `${asg.region}:${asg.name}`;
                                            const isToggling = togglingAsg === key;
                                            return (
                                                <tr
                                                    key={key}
                                                    className={`border-b border-gray-50 dark:border-white/5 hover:bg-gray-50 dark:hover:bg-white/3 transition-colors ${idx === filteredAsgs.length - 1 ? 'border-b-0' : ''}`}
                                                >
                                                    <td className="px-5 py-3.5">
                                                        <div className="font-medium text-gray-800 dark:text-gray-100 truncate max-w-[220px]" title={asg.name}>
                                                            {asg.name}
                                                        </div>
                                                        <div className="text-xs text-gray-400 mt-0.5">{asg.type || 'ASG'}</div>
                                                    </td>
                                                    <td className="px-4 py-3.5">
                                                        <span className="inline-flex items-center gap-1.5 bg-gray-100 dark:bg-white/8 text-gray-600 dark:text-gray-300 px-2.5 py-1 rounded-lg text-xs font-medium">
                                                            <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z" />
                                                            </svg>
                                                            {asg.region}
                                                        </span>
                                                    </td>
                                                    <td className="px-4 py-3.5 text-center">
                                                        <div className="text-gray-700 dark:text-gray-200 font-semibold">{asg.instanceCount}</div>
                                                        <div className="text-xs text-gray-400">{asg.minSize}–{asg.maxSize}</div>
                                                    </td>
                                                    <td className="px-4 py-3.5 text-center">
                                                        {asg.spotInstanceCount != null ? (
                                                            <div className="flex items-center justify-center gap-1.5">
                                                                <span className="text-emerald-600 dark:text-emerald-400 font-semibold text-xs bg-emerald-50 dark:bg-emerald-500/15 px-2 py-0.5 rounded">
                                                                    {asg.spotInstanceCount} spot
                                                                </span>
                                                                <span className="text-gray-400 text-xs bg-gray-100 dark:bg-white/8 px-2 py-0.5 rounded">
                                                                    {asg.ondemandInstanceCount} OD
                                                                </span>
                                                            </div>
                                                        ) : (
                                                            <span className="text-gray-400 text-xs">—</span>
                                                        )}
                                                    </td>
                                                    <td className="px-4 py-3.5 text-right">
                                                        {asg.currentHourlyCost != null ? (
                                                            <span className="text-gray-700 dark:text-gray-200 font-medium">
                                                                ${asg.currentHourlyCost.toFixed(3)}/hr
                                                            </span>
                                                        ) : (
                                                            <span className="text-gray-400 text-xs">—</span>
                                                        )}
                                                    </td>
                                                    <td className="px-4 py-3.5 text-right">
                                                        {asg.actualHourlySavings != null && asg.actualHourlySavings > 0 ? (
                                                            <span className="text-emerald-600 dark:text-emerald-400 font-semibold">
                                                                +${asg.actualHourlySavings.toFixed(3)}/hr
                                                            </span>
                                                        ) : asg.potentialHourlySavings != null && asg.potentialHourlySavings > 0 ? (
                                                            <span className="text-amber-500 dark:text-amber-400 text-xs" title="Potential savings if enabled">
                                                                ~${asg.potentialHourlySavings.toFixed(3)}/hr potential
                                                            </span>
                                                        ) : (
                                                            <span className="text-gray-400 text-xs">—</span>
                                                        )}
                                                    </td>
                                                    <td className="px-5 py-3.5 text-center">
                                                        <div className="flex flex-col items-center gap-1.5">
                                                            <button
                                                                onClick={() => toggleAsg(asg)}
                                                                disabled={isToggling}
                                                                title={asg.isEnabled ? 'Disable AutoSpotting' : 'Enable AutoSpotting (convert to Spot)'}
                                                                className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 focus:outline-none ${
                                                                    isToggling ? 'opacity-50 cursor-wait' :
                                                                    asg.isEnabled
                                                                        ? 'bg-emerald-500 hover:bg-emerald-600'
                                                                        : 'bg-gray-300 dark:bg-gray-600 hover:bg-gray-400 dark:hover:bg-gray-500'
                                                                }`}
                                                                aria-pressed={asg.isEnabled}
                                                            >
                                                                <span
                                                                    className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                                                                        asg.isEnabled ? 'translate-x-5' : 'translate-x-0'
                                                                    }`}
                                                                />
                                                                {isToggling && (
                                                                    <span className="absolute inset-0 flex items-center justify-center">
                                                                        <span className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
                                                                    </span>
                                                                )}
                                                            </button>
                                                            <span className={`text-[10px] font-semibold ${asg.isEnabled ? 'text-emerald-600 dark:text-emerald-400' : 'text-gray-400'}`}>
                                                                {asg.isEnabled ? 'Spot ON' : 'Spot OFF'}
                                                            </span>
                                                        </div>
                                                    </td>
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </div>
                )}

                {/* Dashboard Tab */}
                {activeTab === 'dashboard' && (<>

                {/* Savings Overview Cards */}
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                    <MetricCard
                        title="Monthly Savings"
                        value={`$${savings?.actualMonthlySavings.toFixed(2) || '0.00'}`}
                        subtitle="This month"
                        icon={
                            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                            </svg>
                        }
                        color="green"
                    />

                    <MetricCard
                        title="Total Savings"
                        value={`$${((savings?.actualMonthlySavings || 0) * 12).toFixed(2)}`}
                        subtitle="Estimated Annual"
                        icon={
                            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                            </svg>
                        }
                        color="indigo"
                    />

                    <MetricCard
                        title="Enabled ASGs"
                        value={savings?.enabledAsgs?.toString() || '0'}
                        subtitle={`Out of ${savings?.totalAsgs || 0} ASGs`}
                        icon={
                            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 01-2 2v4a2 2 0 012 2h14a2 2 0 012-2v-4a2 2 0 01-2-2m-2-4h.01M17 16h.01" />
                            </svg>
                        }
                        color="amber"
                    />

                    <MetricCard
                        title="Potential Savings"
                        value={`$${savings?.potentialMonthlySavings.toFixed(2) || '0.00'}`}
                        subtitle="Additional monthly"
                        icon={
                            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                            </svg>
                        }
                        color="purple"
                    />
                </div>

                <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                    {/* Cost Comparison Chart */}
                    <div className="lg:col-span-2 billops-glass-card rounded-2xl p-6">
                        <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-6 flex items-center gap-2">
                            <span className="w-1 h-6 bg-indigo-500 rounded-full"></span>
                            Cost Comparison
                        </h3>
                        <div className="h-64 relative">
                            <canvas ref={savingsChartRef}></canvas>
                        </div>
                    </div>

                    {/* Active Instances Table */}
                    <div className="billops-glass-card rounded-2xl p-6 overflow-hidden flex flex-col">
                        <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4 flex items-center gap-2">
                            <span className="w-1 h-6 bg-amber-500 rounded-full"></span>
                            Active Spot Instances
                        </h3>
                        <div className="overflow-y-auto pr-2 flex-1">
                            {spotInstances.length > 0 ? (
                                <table className="w-full text-left">
                                    <thead>
                                        <tr className="text-gray-500 dark:text-gray-400 text-xs border-b border-gray-100 dark:border-gray-700">
                                            <th className="pb-2 font-medium">Instance ID</th>
                                            <th className="pb-2 font-medium">Type</th>
                                            <th className="pb-2 text-right font-medium">Savings</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {spotInstances.filter(i => i.lifecycle === 'spot').map((instance) => (
                                            <tr key={instance.instanceId} className="border-b border-gray-50 dark:border-gray-700/50 hover:bg-gray-50 dark:hover:bg-white/5 transition-colors">
                                                <td className="py-2 text-gray-700 dark:text-gray-300 text-xs">
                                                    {instance.instanceId.substring(0, 10)}...
                                                    <div className="text-[10px] text-gray-400">{instance.availabilityZone}</div>
                                                </td>
                                                <td className="py-2">
                                                    <span className="bg-indigo-50 text-indigo-600 dark:bg-indigo-500/20 dark:text-indigo-300 text-[10px] px-2 py-0.5 rounded font-medium">
                                                        {instance.instanceType}
                                                    </span>
                                                </td>
                                                <td className="py-2 text-right">
                                                    <span className="text-emerald-600 dark:text-green-400 font-semibold text-xs">
                                                        ${instance.estimatedHourlySavings?.toFixed(3) || '0.000'}/hr
                                                    </span>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            ) : (
                                <div className="text-center py-8 text-gray-400 italic text-sm">
                                    No active Spot instances found.
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Recent Activity and Recommendations */}
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 pb-6">
                    {/* Recent Activity */}
                    <div className="billops-glass-card rounded-2xl p-6">
                        <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4 flex items-center gap-2">
                            <span className="w-1 h-6 bg-purple-500 rounded-full"></span>
                            Recent Activity
                        </h3>
                        <div className="space-y-3 max-h-80 overflow-y-auto pr-2">
                            {recentActivity.length > 0 ? recentActivity.map((activity) => (
                                <div key={activity.id} className="flex items-start gap-3 p-3 rounded-xl bg-gray-50 dark:bg-white/5 border border-gray-100 dark:border-white/5 hover:border-gray-200 dark:hover:border-white/10 transition-colors">
                                    <div className={`p-2 rounded-lg flex-shrink-0 ${activity.eventType === 'LAUNCH' || activity.eventType?.includes('launched') ? 'bg-emerald-50 text-emerald-600 dark:bg-green-500/20 dark:text-green-400' : 'bg-red-50 text-red-500 dark:bg-red-500/20 dark:text-red-400'}`}>
                                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                            {activity.eventType === 'LAUNCH' || activity.eventType?.includes('launched') ? (
                                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                                            ) : (
                                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                                            )}
                                        </svg>
                                    </div>
                                    <div className="flex-1 min-w-0">
                                        <p className="text-gray-800 dark:text-gray-200 text-sm font-medium truncate">{activity.description}</p>
                                        <div className="flex items-center gap-2 mt-1">
                                            <span className="text-xs text-gray-400">
                                                {new Date(activity.timestamp).toLocaleString()}
                                            </span>
                                            {activity.region && (
                                                <span className="text-[10px] bg-gray-100 dark:bg-gray-700/50 text-gray-500 dark:text-gray-400 px-1.5 py-0.5 rounded">
                                                    {activity.region}
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                    {activity.savings && (
                                        <div className="text-right flex-shrink-0">
                                            <span className="block text-emerald-600 dark:text-green-400 font-bold text-sm">
                                                +${activity.savings.toFixed(3)}
                                            </span>
                                            <span className="text-[10px] text-gray-400">saved</span>
                                        </div>
                                    )}
                                </div>
                            )) : (
                                <div className="text-center py-8 text-gray-400 italic text-sm">
                                    No recent activity.
                                </div>
                            )}
                        </div>
                    </div>

                    {/* Recommendations */}
                    <div className="billops-glass-card rounded-2xl p-6">
                        <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4 flex items-center gap-2">
                            <span className="w-1 h-6 bg-blue-500 rounded-full"></span>
                            Optimization Recommendations
                        </h3>
                        <div className="space-y-3 max-h-64 overflow-y-auto">
                            {recommendations.length === 0 ? (
                                <div className="text-center py-8">
                                    <svg className="w-12 h-12 mx-auto text-emerald-400 mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                                    </svg>
                                    <p className="text-gray-500 text-sm">All optimized! No recommendations at this time.</p>
                                </div>
                            ) : (
                                recommendations.map((rec) => (
                                    <div
                                        key={rec.id}
                                        className="bg-gray-50 dark:bg-white/5 border border-gray-100 dark:border-white/10 rounded-xl p-4 hover:bg-gray-100 dark:hover:bg-white/10 transition-all cursor-pointer"
                                    >
                                        <div className="flex items-start justify-between gap-4">
                                            <div className="flex-1">
                                                <div className="flex items-center gap-2 mb-1">
                                                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${rec.priority === 'high' ? 'bg-red-50 text-red-600 dark:bg-red-500/20 dark:text-red-400' :
                                                        rec.priority === 'medium' ? 'bg-amber-50 text-amber-600 dark:bg-yellow-500/20 dark:text-yellow-400' :
                                                            'bg-blue-50 text-blue-600 dark:bg-blue-500/20 dark:text-blue-400'
                                                        }`}>
                                                        {rec.priority.toUpperCase()}
                                                    </span>
                                                    <h4 className="text-gray-800 dark:text-gray-200 font-semibold text-sm">{rec.title}</h4>
                                                </div>
                                                <p className="text-sm text-gray-500 dark:text-gray-400">{rec.description}</p>
                                                {(rec.asgName || rec.region) && (
                                                    <div className="mt-2 flex items-center gap-2 text-xs text-gray-400">
                                                        {rec.asgName && <span>ASG: {rec.asgName}</span>}
                                                        {rec.region && <span className="bg-gray-100 dark:bg-white/5 px-1.5 py-0.5 rounded">{rec.region}</span>}
                                                    </div>
                                                )}
                                            </div>
                                            <div className="text-right flex-shrink-0">
                                                <p className="text-emerald-600 dark:text-green-400 font-semibold text-sm">+${rec.potentialSavings.toFixed(2)}</p>
                                                <p className="text-xs text-gray-400">potential</p>
                                            </div>
                                        </div>
                                    </div>
                                ))
                            )}
                        </div>
                    </div>
                </div>
                </>)}
            </div>
        </div>
    );
};

// Metric Card Component - matches BillOps dashboard card style
const MetricCard: React.FC<{
    title: string;
    value: string;
    subtitle: string;
    icon: React.ReactNode;
    color: 'amber' | 'blue' | 'purple' | 'green' | 'red' | 'indigo' | 'yellow';
}> = ({ title, value, subtitle, icon, color }) => {
    const colorClasses = {
        amber: 'bg-amber-50 text-amber-600 dark:bg-amber-500/20 dark:text-amber-400',
        blue: 'bg-blue-50 text-blue-600 dark:bg-blue-500/20 dark:text-blue-400',
        purple: 'bg-purple-50 text-purple-600 dark:bg-purple-500/20 dark:text-purple-400',
        green: 'bg-emerald-50 text-emerald-600 dark:bg-green-500/20 dark:text-green-400',
        red: 'bg-red-50 text-red-600 dark:bg-red-500/20 dark:text-red-400',
        indigo: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-500/20 dark:text-indigo-400',
        yellow: 'bg-yellow-50 text-yellow-600 dark:bg-yellow-500/20 dark:text-yellow-400'
    };

    return (
        <div className="billops-glass-card rounded-2xl p-6 hover:-translate-y-1 transition-transform duration-200">
            <div className="flex items-start justify-between">
                <div>
                    <p className="text-gray-500 dark:text-gray-400 text-sm font-medium mb-1">{title}</p>
                    <div className="text-2xl font-bold text-gray-900 dark:text-white mt-1">{value}</div>
                    <div className="text-xs text-gray-400 mt-1">{subtitle}</div>
                </div>
                <div className={`p-3 rounded-xl ${colorClasses[color]}`}>
                    {icon}
                </div>
            </div>
        </div>
    );
};

export default AutoSpottingCustomerDashboardPage;
