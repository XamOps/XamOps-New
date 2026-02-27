import React from 'react';

// Interfaces based on GcpDashboard transformations
interface GcpRecommendationItem {
    resourceId: string;
    resourceName?: string;
    resourceType?: string;
    currentSize?: string;
    recommendedSize?: string;
    potentialSavings: number;
    reason?: string;
}

interface GcpOptimizationSummary {
    totalRecommendations: number;
    totalPotentialSavings: number;
    highConfidenceRecommendations: number;
}

interface GcpOptimizationData {
    summary: GcpOptimizationSummary;
    anomalies: any[];
    gceRecommendations: GcpRecommendationItem[];
    cloudSqlRecommendations: GcpRecommendationItem[];
    gkeRecommendations: GcpRecommendationItem[];
}

interface GcpOptimizationSectionProps {
    optimization: GcpOptimizationData;
    isLoading: boolean;
}

const GcpOptimizationSection: React.FC<GcpOptimizationSectionProps> = ({ optimization, isLoading }) => {
    const formatCurrency = (num: number): string => {
        return '₹' + (num == null ? '0.00' : num.toLocaleString('en-IN', {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2
        }));
    };

    const renderEmptyCard = (title: string, _icon: string) => (
        <div className="rounded-xl p-4 border border-gray-100 bg-gray-50/50 backdrop-blur-sm shadow-inner">
            <div className="flex items-center justify-between mb-4">
                <h4 className="font-black text-[10px] text-gray-400 uppercase tracking-widest flex items-center">
                    <div className="w-6 h-6 bg-gray-200 rounded-lg flex items-center justify-center mr-2 shadow-sm">
                        <svg className="w-3.5 h-3.5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01" />
                        </svg>
                    </div>
                    <span className="truncate">{title}</span>
                </h4>
                <span className="bg-gray-100 text-gray-400 text-[10px] font-black px-1.5 py-0.5 rounded-md flex-shrink-0 border border-gray-200">0</span>
            </div>
            <div className="text-center py-6">
                <div className="w-12 h-12 bg-white rounded-full flex items-center justify-center mx-auto mb-3 shadow-sm border border-gray-100">
                    <svg className="w-6 h-6 text-green-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                    </svg>
                </div>
                <p className="text-[11px] text-gray-500 font-bold uppercase tracking-wider">Optimized</p>
                <p className="text-[10px] text-gray-400 mt-1">No pending actions</p>
            </div>
        </div>
    );

    const renderRecsCard = (
        title: string,
        recs: GcpRecommendationItem[] | undefined,
        color: 'blue' | 'green' | 'yellow' | 'red', // Tailwind colors
        gradientClass: string,
        linkPath: string
    ) => {
        if (!recs || recs.length === 0) {
            return renderEmptyCard(title, 'fa-server');
        }

        // Dynamic color classes
        const borderLColor = `border-l-${color}-500`;
        const shadowColor = `shadow-${color}-500/20`;
        const bgColor = `bg-${color}-50`;
        const textColor = `text-${color}-600`;
        const borderColor = `border-${color}-100`;

        return (
            <div className={`optimization-card rounded-xl p-4 border border-gray-100 bg-white shadow-sm hover:shadow-md transition-all border-l-4 ${borderLColor} group`}>
                <div className="flex items-center justify-between mb-4">
                    <h4 className="font-black text-[10px] text-gray-500 uppercase tracking-widest flex items-center">
                        <div className={`w-6 h-6 bg-gradient-to-br ${gradientClass} rounded-lg flex items-center justify-center mr-2 shadow-lg ${shadowColor} group-hover:scale-110 transition-transform`}>
                            <svg className="w-3.5 h-3.5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                            </svg>
                        </div>
                        <span className="truncate">{title}</span>
                    </h4>
                    <span className={`${bgColor} ${textColor} text-[10px] font-black px-1.5 py-0.5 rounded-md flex-shrink-0 border ${borderColor}`}>
                        {recs.length}
                    </span>
                </div>
                <div className="overflow-x-auto">
                    <table className="w-full text-xs table-fixed">
                        <thead className="bg-gray-50">
                            <tr>
                                <th className="px-2 py-1.5 text-left font-bold text-gray-700 text-[10px] uppercase tracking-wider w-[140px]">Resource</th>
                                <th className="px-2 py-1.5 text-left font-bold text-gray-700 text-[10px] uppercase tracking-wider w-[100px]">Change</th>
                                <th className="px-2 py-1.5 text-right font-bold text-gray-700 text-[10px] uppercase tracking-wider">Savings</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-100">
                            {recs.slice(0, 2).map((rec, idx) => {
                                // Clean up resource name
                                let name = rec.resourceName || rec.resourceId || 'N/A';
                                if (name.toLowerCase() === 'unknown') name = 'N/A';

                                // Clean up sizes
                                let currentSize = rec.currentSize || '';
                                let recommendedSize = rec.recommendedSize || '';
                                if (currentSize.toLowerCase() === 'unknown') currentSize = '';
                                if (recommendedSize.toLowerCase() === 'unknown') recommendedSize = '';

                                const change = currentSize && recommendedSize
                                    ? `${currentSize} → ${recommendedSize}`
                                    : 'See details';

                                return (
                                    <tr key={idx} className="hover:bg-gray-50 transition-colors">
                                        <td className="px-2 py-1.5 font-mono text-[10px] text-gray-600 max-w-[140px]" title={name}>
                                            <div className="truncate">{name}</div>
                                        </td>
                                        <td className="px-2 py-1.5" title={change}>
                                            <div className="text-gray-600 text-[10px] max-w-[100px] truncate">{change}</div>
                                        </td>
                                        <td className={`px-2 py-1.5 text-right font-semibold ${textColor} text-[10px] whitespace-nowrap`}>
                                            {formatCurrency(rec.potentialSavings)}
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
                {recs.length > 2 && (
                    <div className="mt-3 text-center border-t border-gray-50 pt-3">
                        <a
                            href={linkPath}
                            className={`inline-flex items-center text-[10px] uppercase font-black tracking-widest ${textColor} hover:brightness-90 transition-colors`}
                        >
                            +{recs.length - 2} more insights
                            <svg className="w-3 h-3 ml-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M17 8l4 4m0 0l-4 4m4-4H3" />
                            </svg>
                        </a>
                    </div>
                )}
            </div>
        );
    };

    const totalSavings = optimization?.summary?.totalPotentialSavings || 0;
    const totalRecs = optimization?.summary?.totalRecommendations || 0;

    return (
        <div className="glass-card p-6 rounded-2xl border border-gray-200 shadow-xl shadow-indigo-500/5 relative overflow-hidden">
            <div className="flex items-center justify-between mb-8">
                <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-amber-50 rounded-xl flex items-center justify-center border border-amber-100 shadow-sm">
                        <svg className="w-6 h-6 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
                        </svg>
                    </div>
                    <div>
                        <h3 className="text-xl font-black text-gray-900 tracking-tight uppercase px-1">Optimization Hub</h3>
                        <p className="text-[10px] text-gray-400 font-bold uppercase tracking-widest flex items-center gap-1.5 px-1 mt-0.5">
                            <span className="w-1.5 h-1.5 bg-amber-400 rounded-full animate-pulse"></span>
                            Strategic Insights
                        </p>
                    </div>
                </div>
                {isLoading && <div className="loading-spinner w-5 h-5 border-2 border-blue-500/20 border-t-blue-500"></div>}
            </div>

            {isLoading && (
                <div className="absolute inset-x-0 bottom-0 top-16 z-10 p-6 bg-white/80 backdrop-blur-sm rounded-b-xl space-y-6">
                    <div className="grid grid-cols-2 gap-6">
                        <div className="h-32 skeleton rounded-xl"></div>
                        <div className="h-32 skeleton rounded-xl"></div>
                    </div>
                    <div className="h-24 skeleton rounded-xl"></div>
                </div>
            )}

            {!isLoading && (
                <div className="fade-in">
                    {/* Summary Cards */}
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-2 mb-2">
                        {/* Savings */}
                        <div className="bg-gradient-to-br from-green-50 to-green-100 border border-green-200 p-2.5 rounded-lg">
                            <div className="flex items-center space-x-2">
                                <div className="w-7 h-7 bg-gradient-to-br from-green-500 to-green-600 rounded-lg flex items-center justify-center flex-shrink-0 shadow-lg shadow-green-500/20">
                                    <svg className="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                                    </svg>
                                </div>
                                <div>
                                    <p className="text-xs text-green-700 font-medium">Potential Savings</p>
                                    <p className="text-base font-bold text-green-800">
                                        {formatCurrency(totalSavings)}&nbsp;<span className="text-xs font-normal text-green-600">/mo</span>
                                    </p>
                                </div>
                            </div>
                        </div>
                        {/* Recommendations Count */}
                        <div className="bg-gradient-to-br from-blue-50 to-blue-100 border border-blue-200 p-2.5 rounded-lg">
                            <div className="flex items-center space-x-2">
                                <div className="w-7 h-7 bg-gradient-to-br from-blue-500 to-blue-600 rounded-lg flex items-center justify-center flex-shrink-0 shadow-lg shadow-blue-500/20">
                                    <svg className="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                                    </svg>
                                </div>
                                <div>
                                    <p className="text-xs text-blue-700 font-medium">Recommendations</p>
                                    <p className="text-base font-bold text-blue-800">
                                        {totalRecs}
                                        <span className="text-xs font-normal text-blue-600"> items</span>
                                    </p>
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Rec Cards Grid */}
                    <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-2">
                        {renderRecsCard(
                            'GCE Right-Sizing',
                            optimization.gceRecommendations,
                            'blue',
                            'from-blue-500 to-blue-600',
                            '/gcp-rightsizing'
                        )}
                        {renderRecsCard(
                            'Cloud SQL Opt.',
                            optimization.cloudSqlRecommendations,
                            'blue',
                            'from-blue-500 to-blue-600',
                            '/gcp-rightsizing'
                        )}
                        {renderRecsCard(
                            'GKE Clusters',
                            optimization.gkeRecommendations,
                            'green',
                            'from-green-500 to-green-600',
                            '/gcp-cloudk8s'
                        )}
                        {renderEmptyCard('Anomalies', 'fa-exclamation-triangle')}
                    </div>
                </div>
            )}
        </div>
    );
};

export default GcpOptimizationSection;
