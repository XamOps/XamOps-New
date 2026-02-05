import React from 'react';
import { Link } from 'react-router-dom';
import { formatTimeAgo } from '../../api/gcpDashboardApi';

interface GcpDashboardHeaderProps {
    accountName: string;
    projectId: string;
    lastUpdated: string | null;
    onRefresh: () => void;
    isRefreshing: boolean;
    multiAccountMode: boolean;
}

const GcpDashboardHeader: React.FC<GcpDashboardHeaderProps> = ({
    accountName,
    projectId,
    lastUpdated,
    onRefresh,
    isRefreshing,
    multiAccountMode,
}) => {
    return (
        <header className="h-20 flex items-center justify-between glass-card px-6 -mx-6 -mt-6 mb-6 border-b sticky top-0 z-10 fade-in relative">
            <div className="flex items-center space-x-4">
                {/* Header Icon & Title */}
                <div className="flex items-center space-x-2">
                    <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-blue-600 rounded-lg flex items-center justify-center shadow-lg shadow-blue-500/20">
                        <img src="/icons/gcp-icon.png" alt="GCP" className="w-6 h-6 object-contain filter brightness-0 invert" />
                    </div>
                    <div>
                        <h1 className="text-xl font-black text-gray-900 tracking-tight">
                            {accountName}
                            {multiAccountMode && <span className="ml-2 text-xs bg-blue-100 text-blue-700 px-2 py-0.5 rounded-full font-bold uppercase tracking-wider">Multi-Account</span>}
                        </h1>
                        <div className="flex items-center space-x-2 mt-0.5">
                            <div className={`w-1.5 h-1.5 ${lastUpdated ? 'bg-green-500 shadow-[0_0_8px_rgba(34,197,94,0.6)]' : 'bg-gray-400'} rounded-full animate-pulse`}></div>
                            <p className="text-[10px] uppercase tracking-wider font-bold text-gray-400">
                                {lastUpdated ? `Live • Updated ${formatTimeAgo(lastUpdated)}` : 'Connecting...'}
                                {projectId && <span className="normal-case ml-2 text-gray-400">• ID: {projectId}</span>}
                            </p>
                        </div>
                    </div>
                </div>
            </div>

            <div className="flex items-center space-x-4">
                {/* CloudList Button */}
                <Link
                    to="/gcp-cloudlist"
                    className="flex items-center justify-center w-10 h-10 text-gray-600 hover:text-blue-600 bg-white hover:bg-blue-50 rounded-lg transition-all border border-gray-200 hover:border-blue-200 shadow-sm hover:shadow-md group cursor-pointer"
                    title="Cloud List"
                >
                    <svg className="w-6 h-6 group-hover:scale-110 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 012 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
                    </svg>
                </Link>

                {/* Pricing Calculator Button */}
                <Link
                    to="/calculator"
                    className="flex items-center justify-center w-10 h-10 text-gray-600 hover:text-blue-600 bg-white hover:bg-blue-50 rounded-lg transition-all border border-gray-200 hover:border-blue-200 shadow-sm hover:shadow-md group cursor-pointer"
                    title="Pricing Calculator"
                >
                    <svg className="w-6 h-6 group-hover:scale-110 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 7h6m0 10v-3m-3 3h.01M9 17h.01M9 14h.01M12 14h.01M15 11h.01M12 11h.01M9 11h.01M7 21h10a2 2 0 002-2V5a2 2 0 00-2-2H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
                    </svg>
                </Link>

                {/* CloudShell Button */}
                <a
                    href="/cloudshell"
                    className="flex items-center text-sm font-semibold text-gray-600 hover:text-blue-600 px-4 py-2 rounded-lg hover:bg-blue-50 transition-all border border-transparent hover:border-blue-100 group cursor-pointer"
                    title="Launch CloudShell"
                >
                    <svg className="w-6 h-6 mr-2 text-gray-400 group-hover:text-blue-600 transition-colors" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
                    </svg>
                    <span>CloudShell</span>
                </a>

                {/* Refresh Button */}
                <button
                    onClick={onRefresh}
                    disabled={isRefreshing}
                    className="flex items-center text-sm text-blue-600 hover:text-blue-800 px-4 py-2 rounded-lg hover:bg-blue-50 transition-all disabled:opacity-50 disabled:cursor-not-allowed border border-transparent hover:border-blue-100"
                >
                    <svg className={`w-4 h-4 mr-2 ${isRefreshing ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                    </svg>
                    <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
                </button>
            </div>
        </header>
    );
};

export default GcpDashboardHeader;
