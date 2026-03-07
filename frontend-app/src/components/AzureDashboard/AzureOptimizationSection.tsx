import React from 'react';
import { Link } from 'react-router-dom';
import { AzureOptimizationData } from '../../types/azureDashboard';

interface AzureOptimizationSectionProps {
  optimization: AzureOptimizationData;
  selectedAccountIds: string[];
  isLoading: boolean;
}

const AzureOptimizationSection: React.FC<AzureOptimizationSectionProps> = ({ optimization, selectedAccountIds, isLoading }) => {
  const formatCurrency = (num: number): string => {
    return '$' + (num != null ? num.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }) : '0.00');
  };

  const renderEmptyCard = (title: string) => (
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
    recs: Array<{ resourceId: string; currentType: string; recommendedType: string; estimatedMonthlySavings: number }> | undefined,
    color: 'blue' | 'green' | 'yellow' | 'amber' | 'indigo',
    gradientClass: string,
    linkPath: string
  ) => {
    if (!recs || recs.length === 0) {
      return renderEmptyCard(title);
    }

    const colorMap = {
      blue:   { border: 'border-l-blue-500',   bg: 'bg-blue-50',   text: 'text-blue-600',   bdColor: 'border-blue-100' },
      green:  { border: 'border-l-green-500',  bg: 'bg-green-50',  text: 'text-green-600',  bdColor: 'border-green-100' },
      yellow: { border: 'border-l-yellow-500', bg: 'bg-yellow-50', text: 'text-yellow-600', bdColor: 'border-yellow-100' },
      amber:  { border: 'border-l-amber-500',  bg: 'bg-amber-50',  text: 'text-amber-600',  bdColor: 'border-amber-100' },
      indigo: { border: 'border-l-indigo-500', bg: 'bg-indigo-50', text: 'text-indigo-600', bdColor: 'border-indigo-100' },
    };
    const c = colorMap[color];

    return (
      <div className={`optimization-card rounded-xl p-4 border border-gray-100 bg-white shadow-sm hover:shadow-md transition-all border-l-4 ${c.border} group`}>
        <div className="flex items-center justify-between mb-4">
          <h4 className="font-black text-[10px] text-gray-500 uppercase tracking-widest flex items-center">
            <div className={`w-6 h-6 bg-gradient-to-br ${gradientClass} rounded-lg flex items-center justify-center mr-2 shadow-lg group-hover:scale-110 transition-transform`}>
              <svg className="w-3.5 h-3.5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
            </div>
            <span className="truncate">{title}</span>
          </h4>
          <span className={`${c.bg} ${c.text} text-[10px] font-black px-1.5 py-0.5 rounded-md flex-shrink-0 border ${c.bdColor}`}>
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
                const name = rec.resourceId || 'N/A';
                const change = rec.currentType && rec.recommendedType
                  ? `${rec.currentType} → ${rec.recommendedType}`
                  : 'See details';
                return (
                  <tr key={idx} className="hover:bg-gray-50 transition-colors">
                    <td className="px-2 py-1.5 font-mono text-[10px] text-gray-600 max-w-[140px]" title={name}>
                      <div className="truncate">{name}</div>
                    </td>
                    <td className="px-2 py-1.5" title={change}>
                      <div className="text-gray-600 text-[10px] max-w-[100px] truncate">{change}</div>
                    </td>
                    <td className={`px-2 py-1.5 text-right font-semibold ${c.text} text-[10px] whitespace-nowrap`}>
                      {formatCurrency(rec.estimatedMonthlySavings)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {recs.length > 2 && (
          <div className="mt-3 text-center border-t border-gray-50 pt-3">
            <Link
              to={linkPath}
              className={`inline-flex items-center text-[10px] uppercase font-black tracking-widest ${c.text} hover:brightness-90 transition-colors`}
            >
              +{recs.length - 2} more insights
              <svg className="w-3 h-3 ml-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M17 8l4 4m0 0l-4 4m4-4H3" />
              </svg>
            </Link>
          </div>
        )}
      </div>
    );
  };

  const totalSavings = optimization?.summary?.totalPotentialSavings || 0;
  const totalRecs =
    (optimization?.vmRecommendations?.length || 0) +
    (optimization?.diskRecommendations?.length || 0) +
    (optimization?.functionRecommendations?.length || 0);

  if (isLoading) {
    return (
      <div className="glass-card p-6 rounded-2xl border border-gray-200 shadow-xl" style={{ minHeight: 320 }}>
        <div className="flex items-center gap-3 mb-8">
          <div className="w-10 h-10 bg-amber-50 rounded-xl border border-amber-100 skeleton"></div>
          <div>
            <div className="h-5 w-40 skeleton rounded mb-1"></div>
            <div className="h-3 w-24 skeleton rounded"></div>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-4 mb-4">
          <div className="h-14 skeleton rounded-lg"></div>
          <div className="h-14 skeleton rounded-lg"></div>
        </div>
        <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
          {[1, 2, 3, 4].map(i => <div key={i} className="h-28 skeleton rounded-xl"></div>)}
        </div>
      </div>
    );
  }

  return (
    <div className="glass-card p-6 rounded-2xl border border-gray-200 shadow-xl shadow-blue-500/5 relative overflow-hidden">
      {/* Header */}
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
              Azure Efficiency & Savings
            </p>
          </div>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-2 mb-4">
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
                {totalRecs}<span className="text-xs font-normal text-blue-600"> items</span>
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* Recommendation Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-2">
        {renderRecsCard(
          'VM Right-Sizing',
          optimization.vmRecommendations,
          'blue',
          'from-blue-500 to-blue-600',
          `/rightsizing?accountIds=${selectedAccountIds.join(',')}`
        )}
        {renderRecsCard(
          'Disk Optimization',
          optimization.diskRecommendations,
          'indigo',
          'from-indigo-500 to-indigo-600',
          `/rightsizing?accountIds=${selectedAccountIds.join(',')}`
        )}
        {renderRecsCard(
          'Function Apps',
          optimization.functionRecommendations,
          'amber',
          'from-amber-500 to-amber-600',
          `/rightsizing?accountIds=${selectedAccountIds.join(',')}`
        )}
        {renderEmptyCard('Cost Anomalies')}
      </div>
    </div>
  );
};

export default AzureOptimizationSection;
