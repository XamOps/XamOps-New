import React, { useState } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpSpotAutomationData, GcpMIG } from '../hooks/useGcpSpotAutomationData';
import { DashboardSkeleton } from '../components/Shared/Skeleton';
import SpotAutomationKPIs from '../components/SpotAutomation/SpotAutomationKPIs';
import SpotAutomationChart from '../components/SpotAutomation/SpotAutomationChart';
import ActionsHistory from '../components/ActionsHistory/ActionsHistory';

const GcpSpotAutomationPage: React.FC = () => {
    const { selectedAccountDisplay, isLoading: accountsLoading } = useAccounts();
    const selectedAccountId = selectedAccountDisplay.id && selectedAccountDisplay.id !== 'No account selected' ? selectedAccountDisplay.id : null;
    const isGcp = selectedAccountDisplay.provider === 'GCP';

    const {
        migs,
        costs,
        savings,
        enabledLocationsCount,
        totalInstances,
        historyData,
        historySummary,
        isLoading,
        isLoadingHistory,
        error,
        refresh,
        fetchHistoryData,
        toggleMIG,
    } = useGcpSpotAutomationData({ selectedAccountId });

    const [activeTab, setActiveTab] = useState<'dashboard' | 'analytics' | 'history'>('dashboard');

    if (accountsLoading) {
        return <DashboardSkeleton />;
    }

    if (!selectedAccountId) {
        return (
            <div className="p-8 flex flex-col items-center justify-center min-h-[60vh]">
                <div className="bg-white border border-slate-200 shadow-sm rounded-3xl p-10 max-w-md text-center">
                    <div className="w-16 h-16 bg-slate-50 rounded-2xl flex items-center justify-center text-slate-400 mx-auto mb-6">
                        <i className="fas fa-project-diagram text-2xl"></i>
                    </div>
                    <h2 className="text-2xl font-bold text-slate-900 mb-2">No Account Selected</h2>
                    <p className="text-slate-500 text-sm mb-0">
                        Please select a GCP account from the account selector to view Spot Automation details.
                    </p>
                </div>
            </div>
        );
    }

    if (!isGcp) {
        return (
            <div className="p-8 flex flex-col items-center justify-center min-h-[60vh]">
                <div className="bg-white border border-amber-100 shadow-sm rounded-3xl p-10 max-w-md text-center">
                    <div className="w-16 h-16 bg-amber-50 rounded-2xl flex items-center justify-center text-amber-500 mx-auto mb-6">
                        <i className="fas fa-exclamation-triangle text-2xl"></i>
                    </div>
                    <h2 className="text-2xl font-bold text-slate-900 mb-2">GCP Account Required</h2>
                    <p className="text-slate-600 text-sm mb-0">
                        This page is specifically for Google Cloud Platform. Please select a GCP account from the account selector to continue.
                    </p>
                </div>
            </div>
        );
    }

    const handleToggle = async (mig: GcpMIG) => {
        try {
            await toggleMIG(mig);
        } catch (err) {
            console.error('Failed to toggle MIG:', err);
        }
    };

    const handleTimeRangeChange = (range: 'last24h' | 'last7d' | 'last30d') => {
        fetchHistoryData(range);
    };

    const enabledMIGsCount = migs.filter(m => m.isEnabled).length;

    return (
        <div className="flex-1 flex flex-col min-h-screen bg-slate-50 relative">
            {/* Coming Soon Overlay */}
            <div className="absolute inset-0 bg-white/80 backdrop-blur-sm z-40 flex items-center justify-center">
                <div className="text-center">
                    <div className="inline-flex items-center justify-center w-24 h-24 rounded-full bg-indigo-100 mb-6">
                        <i className="fas fa-rocket text-4xl text-indigo-500"></i>
                    </div>
                    <h2 className="text-3xl font-bold text-gray-800 mb-3">Coming Soon</h2>
                    <p className="text-gray-500 text-lg max-w-md mx-auto">
                        GCP AutoSpotting is currently being updated. We'll be back with enhanced features and real-time data shortly.
                    </p>
                </div>
            </div>

            {/* Tab Navigation */}
            <div className="bg-white/80 border-b border-slate-200 px-8 backdrop-blur-md sticky top-0 z-40">
                <nav className="flex space-x-8" aria-label="Tabs">
                    <button
                        onClick={() => setActiveTab('dashboard')}
                        className={`py-4 px-1 border-b-2 font-medium text-sm transition-all flex items-center gap-2 ${activeTab === 'dashboard'
                            ? 'border-indigo-600 text-indigo-600'
                            : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
                            }`}
                    >
                        <i className="fas fa-tachometer-alt"></i>
                        Dashboard
                    </button>
                    <button
                        onClick={() => setActiveTab('analytics')}
                        className={`py-4 px-1 border-b-2 font-medium text-sm transition-all flex items-center gap-2 ${activeTab === 'analytics'
                            ? 'border-indigo-600 text-indigo-600'
                            : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
                            }`}
                    >
                        <i className="fas fa-chart-line"></i>
                        Launch Analytics
                    </button>
                    <button
                        onClick={() => setActiveTab('history')}
                        className={`py-4 px-1 border-b-2 font-medium text-sm transition-all flex items-center gap-2 ${activeTab === 'history'
                            ? 'border-indigo-600 text-indigo-600'
                            : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
                            }`}
                    >
                        <i className="fas fa-history"></i>
                        Actions History
                    </button>
                </nav>
            </div>

            {/* Main Content */}
            <div className="flex-1 p-8 space-y-8">
                {activeTab === 'dashboard' && (
                    <>
                        {/* KPI Grid */}
                        <SpotAutomationKPIs
                            costs={costs}
                            savings={savings}
                            enabledASGsCount={enabledMIGsCount}
                            totalASGsCount={migs.length}
                            enabledRegionsCount={enabledLocationsCount}
                        />

                        {/* Summary Info */}
                        <div className="flex items-center justify-between text-[11px] font-bold uppercase tracking-wider text-slate-500 mb-2 px-2">
                            <span>Total Instances: <span className="text-slate-900">{totalInstances}</span></span>
                            {error && <span className="text-rose-600 font-bold flex items-center gap-1"><i className="fas fa-exclamation-circle"></i> {error.message}</span>}
                        </div>

                        {/* Chart Section */}
                        <SpotAutomationChart
                            historyData={historyData}
                            historySummary={historySummary}
                            timeRange="last7d"
                            isLoading={isLoadingHistory}
                            onTimeRangeChange={handleTimeRangeChange}
                            onRefresh={() => fetchHistoryData()}
                        />

                        {/* MIGs Table */}
                        <div className="bg-white border border-slate-200 rounded-2xl shadow-sm overflow-hidden">
                            <div className="p-6 border-b border-slate-100 flex items-center justify-between">
                                <div>
                                    <h3 className="text-xl font-bold text-slate-900">Managed Instance Groups</h3>
                                    <p className="text-slate-500 text-sm mt-1">Configure AutoSpotting for your GCP MIGs</p>
                                </div>
                                <button
                                    onClick={refresh}
                                    className="p-2 bg-slate-50 hover:bg-slate-100 text-slate-600 rounded-lg transition-colors border border-slate-200"
                                >
                                    <i className={`fas fa-sync-alt ${isLoading ? 'fa-spin' : ''}`}></i>
                                </button>
                            </div>

                            <div className="overflow-x-auto">
                                <table className="w-full text-left">
                                    <thead>
                                        <tr className="bg-slate-50/50 text-slate-500 text-xs uppercase tracking-wider">
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">Instance Group</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">Region</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">Status</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100 text-center">Instances</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100 text-right">Potential Savings</th>
                                            <th className="px-6 py-4 text-center border-b border-slate-100">Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100">
                                        {migs.length > 0 ? (
                                            migs.map((mig) => (
                                                <tr key={`${mig.region}-${mig.name}`} className="hover:bg-slate-50 transition-colors group">
                                                    <td className="px-6 py-4">
                                                        <div className="flex items-center gap-3">
                                                            <div className="w-9 h-9 rounded-xl bg-indigo-50 flex items-center justify-center text-indigo-600 border border-indigo-100 shadow-sm">
                                                                <i className="fas fa-layer-group"></i>
                                                            </div>
                                                            <div>
                                                                <div className="text-sm font-bold text-slate-800 group-hover:text-indigo-600 transition-colors">
                                                                    {mig.name}
                                                                </div>
                                                                <div className="text-[10px] text-slate-400 font-mono mt-0.5">GOOGLE CLOUD</div>
                                                            </div>
                                                        </div>
                                                    </td>
                                                    <td className="px-6 py-4">
                                                        <span className="text-xs font-semibold text-slate-600 bg-white px-2.5 py-1 rounded-full border border-slate-200">
                                                            {mig.region}
                                                        </span>
                                                    </td>
                                                    <td className="px-6 py-4">
                                                        {mig.isEnabled ? (
                                                            <span className="flex items-center gap-1.5 text-xs font-bold text-emerald-600">
                                                                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
                                                                Active
                                                            </span>
                                                        ) : (
                                                            <span className="flex items-center gap-1.5 text-xs font-bold text-slate-400">
                                                                <span className="w-2 h-2 rounded-full bg-slate-300"></span>
                                                                Disabled
                                                            </span>
                                                        )}
                                                    </td>
                                                    <td className="px-6 py-4 text-center">
                                                        <div className="inline-flex flex-col items-center">
                                                            <span className="text-sm font-bold text-slate-900">{mig.instanceCount}</span>
                                                            <div className="flex gap-1 mt-1">
                                                                <div className="w-1.5 h-1.5 rounded-full bg-emerald-500" title="Spot"></div>
                                                                <div className="w-1.5 h-1.5 rounded-full bg-slate-200" title="On-Demand"></div>
                                                            </div>
                                                        </div>
                                                    </td>
                                                    <td className="px-6 py-4 text-right">
                                                        <div className="text-sm font-bold text-emerald-600">
                                                            +₹{mig.potentialSavings.toFixed(2)}
                                                            <span className="text-[10px] text-slate-400 ml-1 font-medium">/hr</span>
                                                        </div>
                                                    </td>
                                                    <td className="px-6 py-4">
                                                        <div className="flex justify-center">
                                                            <button
                                                                onClick={() => handleToggle(mig)}
                                                                disabled={mig.updating}
                                                                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 ${mig.isEnabled ? 'bg-indigo-600' : 'bg-slate-200'
                                                                    } ${mig.updating ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
                                                            >
                                                                <span className="sr-only">Toggle AutoSpotting</span>
                                                                <span
                                                                    className={`${mig.isEnabled ? 'translate-x-6' : 'translate-x-1'
                                                                        } inline-block h-4 w-4 transform rounded-full bg-white transition-transform ease-in-out duration-200 flex items-center justify-center`}
                                                                >
                                                                    {mig.updating && (
                                                                        <i className="fas fa-circle-notch fa-spin text-[8px] text-indigo-600"></i>
                                                                    )}
                                                                </span>
                                                            </button>
                                                        </div>
                                                    </td>
                                                </tr>
                                            ))
                                        ) : (
                                            <tr>
                                                <td colSpan={6} className="px-6 py-12 text-center text-slate-500 italic">
                                                    {isLoading ? 'Scanning for Instance Groups...' : 'No Instance Groups found.'}
                                                </td>
                                            </tr>
                                        )}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    </>
                )}

                {activeTab === 'analytics' && (
                    <div className="p-12 text-center bg-white border border-slate-200 rounded-3xl shadow-sm max-w-2xl mx-auto mt-8">
                        <div className="w-20 h-20 bg-indigo-50 rounded-2xl flex items-center justify-center text-indigo-500 mx-auto mb-6 shadow-inner">
                            <i className="fas fa-chart-pie text-3xl"></i>
                        </div>
                        <h2 className="text-2xl font-bold text-slate-900 mb-3">GCP Launch Analytics</h2>
                        <p className="text-slate-500 text-base leading-relaxed mb-8">
                            Deep insights into Spot market stability, request success rates, and interruption patterns for GCP. This feature is currently in preview.
                        </p>
                        <button className="px-6 py-2.5 bg-indigo-600 text-white rounded-xl font-bold shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all">
                            Notify Me on Release
                        </button>
                    </div>
                )}

                {activeTab === 'history' && (
                    <ActionsHistory accountId={selectedAccountId} provider="GCP" />
                )}
            </div>
        </div>
    );
};

export default GcpSpotAutomationPage;
