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

import React, { useEffect, useRef, useState } from 'react';
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
}

const AutoSpottingCustomerDashboardPage: React.FC = () => {
    const { setActions, clearActions } = useTopbar();
    const { selectedAccountDisplay } = useAccounts();
    const selectedAccount = selectedAccountDisplay.id && selectedAccountDisplay.id !== 'No account selected' ? { accountId: selectedAccountDisplay.id, accountName: selectedAccountDisplay.name } : null;

    // State
    const [savings, setSavings] = useState<CustomerSavings | null>(null);
    const [spotInstances, setSpotInstances] = useState<SpotInstance[]>([]);
    const [recentActivity, setRecentActivity] = useState<ActivityEvent[]>([]);
    const [recommendations, setRecommendations] = useState<Recommendation[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // Chart refs
    const savingsChartRef = useRef<HTMLCanvasElement>(null);
    const savingsChartInstance = useRef<Chart<'bar'> | null>(null);

    // Topbar actions
    useEffect(() => {
        setActions(
            <div className="flex items-center gap-2">
                <button
                    onClick={fetchDashboardData}
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
    }, [setActions, clearActions]);

    // Fetch dashboard data
    const fetchDashboardData = async () => {
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

            // Mock recommendations for now
            setRecommendations([
                {
                    id: 1,
                    title: 'Enable Spot on "payment-service"',
                    description: 'Enabling Spot instances on this ASG could save $145/month.',
                    potentialSavings: 145.00,
                    priority: 'high'
                },
                {
                    id: 2,
                    title: 'Diversify instance types',
                    description: 'Add m5.large to "frontend-cluster" to increase spot availability.',
                    potentialSavings: 45.50,
                    priority: 'medium'
                }
            ]);

        } catch (err) {
            console.error('Error fetching dashboard data:', err);
            setError(err instanceof Error ? err.message : 'Failed to load dashboard');
        } finally {
            setLoading(false);
        }
    };

    // Initial data fetch
    useEffect(() => {
        if (selectedAccount) {
            fetchDashboardData();
        }
    }, [selectedAccount, selectedAccount?.accountId]); // Added dependency safely

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
                    backgroundColor: ['rgba(239, 68, 68, 0.6)', 'rgba(34, 197, 94, 0.6)'],
                    borderColor: ['rgba(239, 68, 68, 1)', 'rgba(34, 197, 94, 1)'],
                    borderWidth: 2
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
                        backgroundColor: 'rgba(15, 23, 42, 0.95)',
                        titleColor: '#F8FAFC',
                        bodyColor: '#F8FAFC',
                        borderColor: 'rgba(255, 255, 255, 0.2)',
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
                            color: 'rgba(255, 255, 255, 0.1)',
                            drawBorder: false
                        },
                        ticks: {
                            color: '#94A3B8',
                            font: {
                                family: 'Fira Sans',
                                size: 12
                            }
                        }
                    },
                    y: {
                        grid: {
                            color: 'rgba(255, 255, 255, 0.1)',
                            drawBorder: false
                        },
                        ticks: {
                            color: '#94A3B8',
                            font: {
                                family: 'Fira Sans',
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

    if (loading && !savings) {
        return (
            <div className="flex items-center justify-center min-h-screen bg-slate-900">
                <div className="text-center">
                    <div className="inline-block animate-spin rounded-full h-12 w-12 border-4 border-amber-500 border-t-transparent"></div>
                    <p className="mt-4 text-slate-400 font-['Fira_Sans']">Loading AutoSpotting data...</p>
                </div>
            </div>
        );
    }

    if (error) {
        return (
            <div className="flex items-center justify-center min-h-screen bg-slate-900">
                <div className="bg-red-500/10 border border-red-500/20 rounded-lg p-6 max-w-md">
                    <h3 className="text-red-400 font-['Fira_Code'] text-lg mb-2">Error Loading Dashboard</h3>
                    <p className="text-slate-300 font-['Fira_Sans']">{error}</p>
                    <button
                        onClick={fetchDashboardData}
                        className="mt-4 px-4 py-2 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded-lg transition-colors font-['Fira_Sans']"
                    >
                        Retry
                    </button>
                </div>
            </div>
        );
    }

    if (!selectedAccount) {
        return (
            <div className="flex items-center justify-center min-h-screen bg-slate-900">
                <div className="text-center">
                    <svg className="w-16 h-16 mx-auto text-slate-600 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
                    </svg>
                    <p className="text-slate-400 font-['Fira_Sans']">Please select an AWS account to view AutoSpotting data</p>
                </div>
            </div>
        );
    }

    return (
        <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900/20 to-slate-900 p-6">
            <div className="max-w-7xl mx-auto space-y-6">
                {/* Header */}
                <div className="backdrop-blur-xl bg-white/5 border border-white/10 rounded-2xl p-6 shadow-2xl">
                    <h1 className="text-3xl font-bold text-slate-100 font-['Fira_Code'] mb-2">
                        AutoSpotting Dashboard
                    </h1>
                    <p className="text-slate-400 font-['Fira_Sans']">
                        Track your spot instance cost savings for <span className="text-amber-400 font-semibold">{selectedAccount.accountName}</span>
                    </p>
                </div>

                {/* Savings Overview Cards */}
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                    <GlassCard
                        title="Monthly Savings"
                        value={`$${savings?.actualMonthlySavings.toFixed(2) || '0.00'}`}
                        subtitle="This month"
                        icon={
                            <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                            </svg>
                        }
                        color="green"
                    />

                    <GlassCard
                        title="Total Savings"
                        value={`$${((savings?.actualMonthlySavings || 0) * 12).toFixed(2)}`} // Projected
                        subtitle="Estimated Annual"
                        icon={
                            <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                            </svg>
                        }
                        color="indigo"
                    />

                    <GlassCard
                        title="Enabled ASGs"
                        value={savings?.enabledAsgs?.toString() || '0'}
                        subtitle={`Out of ${savings?.totalAsgs || 0} ASGs`}
                        icon={
                            <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 01-2 2v4a2 2 0 012 2h14a2 2 0 012-2v-4a2 2 0 01-2-2m-2-4h.01M17 16h.01" />
                            </svg>
                        }
                        color="amber"
                    />

                    <GlassCard
                        title="Potential Savings"
                        value={`$${savings?.potentialMonthlySavings.toFixed(2) || '0.00'}`}
                        subtitle="Additional monthly"
                        icon={
                            <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                            </svg>
                        }
                        color="purple"
                    />
                </div>

                <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                    {/* Cost Comparison Chart */}
                    <div className="lg:col-span-2 backdrop-blur-xl bg-white/5 border border-white/10 rounded-2xl p-6 shadow-xl">
                        <h3 className="text-xl font-bold text-slate-100 font-['Fira_Code'] mb-6 flex items-center gap-2">
                            <span className="w-2 h-8 bg-indigo-500 rounded-full"></span>
                            Cost Comparison
                        </h3>
                        <div className="h-64 relative">
                            <canvas ref={savingsChartRef}></canvas>
                        </div>
                    </div>

                    {/* Active Instances Table */}
                    <div className="backdrop-blur-xl bg-white/5 border border-white/10 rounded-2xl p-6 shadow-xl overflow-hidden flex flex-col">
                        <h3 className="text-xl font-bold text-slate-100 font-['Fira_Code'] mb-4 flex items-center gap-2">
                            <span className="w-2 h-8 bg-amber-500 rounded-full"></span>
                            Active Spot Instances
                        </h3>
                        <div className="overflow-y-auto pr-2 flex-1">
                            {spotInstances.length > 0 ? (
                                <table className="w-full text-left">
                                    <thead>
                                        <tr className="text-slate-400 font-['Fira_Code'] text-xs border-b border-white/10">
                                            <th className="pb-2">Instance ID</th>
                                            <th className="pb-2">Type</th>
                                            <th className="pb-2 text-right">Savings</th>
                                        </tr>
                                    </thead>
                                    <tbody className="space-y-2">
                                        {spotInstances.filter(i => i.lifecycle === 'spot').map((instance) => (
                                            <tr key={instance.instanceId} className="group hover:bg-white/5 transition-colors">
                                                <td className="py-2 text-slate-300 font-['Fira_Code'] text-xs">
                                                    {instance.instanceId.substring(0, 10)}...
                                                    <div className="text-[10px] text-slate-500">{instance.availabilityZone}</div>
                                                </td>
                                                <td className="py-2">
                                                    <span className="bg-indigo-500/20 text-indigo-300 text-[10px] px-2 py-0.5 rounded font-['Fira_Code']">
                                                        {instance.instanceType}
                                                    </span>
                                                </td>
                                                <td className="py-2 text-right">
                                                    <span className="text-green-400 font-bold font-['Fira_Code'] text-xs">
                                                        ${instance.estimatedHourlySavings?.toFixed(3) || '0.000'}/hr
                                                    </span>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            ) : (
                                <div className="text-center py-8 text-slate-500 italic">
                                    No active Spot instances found.
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Recent Activity and Recommendations */}
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 pb-6">
                    {/* Recent Activity */}
                    <div className="backdrop-blur-xl bg-white/5 border border-white/10 rounded-2xl p-6 shadow-xl">
                        <h3 className="text-xl font-bold text-slate-100 font-['Fira_Code'] mb-4 flex items-center gap-2">
                            <span className="w-2 h-8 bg-purple-500 rounded-full"></span>
                            Recent Activity
                        </h3>
                        <div className="space-y-4 max-h-80 overflow-y-auto pr-2">
                            {recentActivity.length > 0 ? recentActivity.map((activity) => (
                                <div key={activity.id} className="flex items-start gap-4 p-3 rounded-lg bg-white/5 border border-white/5 hover:border-white/10 transition-colors">
                                    <div className={`p-2 rounded-lg ${activity.eventType === 'LAUNCH' || activity.eventType?.includes('launched') ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'}`}>
                                        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                            {activity.eventType === 'LAUNCH' || activity.eventType?.includes('launched') ? (
                                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                                            ) : (
                                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                                            )}
                                        </svg>
                                    </div>
                                    <div className="flex-1 min-w-0">
                                        <p className="text-slate-200 text-sm font-medium truncate">{activity.description}</p>
                                        <div className="flex items-center gap-3 mt-1">
                                            <span className="text-xs text-slate-500 font-['Fira_Code']">
                                                {new Date(activity.timestamp).toLocaleString()}
                                            </span>
                                            {activity.region && (
                                                <span className="text-[10px] bg-slate-700/50 text-slate-400 px-1.5 py-0.5 rounded border border-white/5">
                                                    {activity.region}
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                    {activity.savings && (
                                        <div className="text-right">
                                            <span className="block text-green-400 font-bold font-['Fira_Code'] text-sm">
                                                +${activity.savings.toFixed(3)}
                                            </span>
                                            <span className="text-[10px] text-slate-500">saved</span>
                                        </div>
                                    )}
                                </div>
                            )) : (
                                <div className="text-center py-8 text-slate-500 italic">
                                    No recent activity.
                                </div>
                            )}
                        </div>
                    </div>

                    {/* Recommendations */}
                    <div className="backdrop-blur-xl bg-white/5 border border-white/10 rounded-2xl p-6 shadow-xl">
                        <h3 className="text-xl font-bold text-slate-100 font-['Fira_Code'] mb-4 flex items-center gap-2">
                            <span className="w-2 h-8 bg-blue-500 rounded-full"></span>
                            Optimization Recommendations
                        </h3>
                        <div className="space-y-3 max-h-64 overflow-y-auto">
                            {recommendations.length === 0 ? (
                                <div className="text-center py-8">
                                    <svg className="w-12 h-12 mx-auto text-green-500 mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                                    </svg>
                                    <p className="text-slate-400 font-['Fira_Sans']">All optimized! No recommendations at this time.</p>
                                </div>
                            ) : (
                                recommendations.map((rec) => (
                                    <div
                                        key={rec.id}
                                        className="backdrop-blur-sm bg-white/5 border border-white/10 rounded-lg p-4 hover:bg-white/10 transition-all cursor-pointer"
                                    >
                                        <div className="flex items-start justify-between">
                                            <div className="flex-1">
                                                <div className="flex items-center gap-2 mb-1">
                                                    <span className={`px-2 py-0.5 rounded text-xs font-['Fira_Sans'] ${rec.priority === 'high' ? 'bg-red-500/20 text-red-400' :
                                                        rec.priority === 'medium' ? 'bg-yellow-500/20 text-yellow-400' :
                                                            'bg-blue-500/20 text-blue-400'
                                                        }`}>
                                                        {rec.priority.toUpperCase()}
                                                    </span>
                                                    <h3 className="text-slate-200 font-['Fira_Sans'] font-semibold">{rec.title}</h3>
                                                </div>
                                                <p className="text-sm text-slate-400 font-['Fira_Sans']">{rec.description}</p>
                                            </div>
                                            <div className="text-right ml-4">
                                                <p className="text-green-400 font-['Fira_Code'] font-semibold">+${rec.potentialSavings.toFixed(2)}</p>
                                                <p className="text-xs text-slate-500 font-['Fira_Sans']">potential</p>
                                            </div>
                                        </div>
                                    </div>
                                ))
                            )}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

// Reusable Glass Card Component
const GlassCard: React.FC<{
    title: string;
    value: string;
    subtitle: string;
    icon: React.ReactNode;
    color: 'amber' | 'blue' | 'purple' | 'green' | 'red' | 'indigo' | 'yellow';
}> = ({ title, value, subtitle, icon, color }) => {
    const colorClasses = {
        amber: 'bg-amber-500/20 text-amber-400',
        blue: 'bg-blue-500/20 text-blue-400',
        purple: 'bg-purple-500/20 text-purple-400',
        green: 'bg-green-500/20 text-green-400',
        red: 'bg-red-500/20 text-red-400',
        indigo: 'bg-indigo-500/20 text-indigo-400',
        yellow: 'bg-yellow-500/20 text-yellow-400'
    };

    return (
        <div className="backdrop-blur-xl bg-white/5 border border-white/10 rounded-2xl p-6 shadow-xl hover:bg-white/10 transition-colors group">
            <div className="flex items-start justify-between">
                <div>
                    <h3 className="text-slate-400 text-sm font-medium font-['Fira_Sans'] mb-1">{title}</h3>
                    <div className="text-2xl font-bold text-slate-100 font-['Fira_Code'] mt-1">{value}</div>
                    <div className="text-xs text-slate-500 mt-1 font-['Fira_Sans']">{subtitle}</div>
                </div>
                <div className={`p-3 rounded-xl ${colorClasses[color]} group-hover:scale-110 transition-transform duration-300`}>
                    {icon}
                </div>
            </div>
        </div>
    );
};

export default AutoSpottingCustomerDashboardPage;
