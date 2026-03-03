import React, { useState } from 'react';
import { AzureFinOpsReportData } from '../../types/azureFinops';
import { useTheme } from '../../contexts/ThemeContext';
import clsx from 'clsx';

interface AzureFinOpsInsightsSectionProps {
  reportData: AzureFinOpsReportData | null;
  isLoading: boolean;
}

type Tab = 'rightsizing' | 'waste';

const impactBadge = (impact: string) => {
  switch (impact?.toLowerCase()) {
    case 'high':   return 'bg-red-100 text-red-700 border border-red-200';
    case 'medium': return 'bg-amber-100 text-amber-700 border border-amber-200';
    case 'low':    return 'bg-blue-100 text-blue-700 border border-blue-200';
    default:       return 'bg-gray-100 text-gray-600 border border-gray-200';
  }
};

const formatCurrency = (v: number) =>
  '₹' + v.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const AzureFinOpsInsightsSection: React.FC<AzureFinOpsInsightsSectionProps> = ({ reportData, isLoading }) => {
  const { theme } = useTheme();
  const [activeTab, setActiveTab] = useState<Tab>('rightsizing');

  const rightsizingItems = reportData?.rightsizingItems ?? [];
  const wasteItems = reportData?.wasteItems ?? [];

  const cardCls = clsx('glass-card rounded-xl p-6', theme === 'dark' ? 'bg-slate-800/50' : '');
  const thCls = clsx('px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide', theme === 'dark' ? 'text-slate-400' : 'text-gray-500');
  const tdCls = clsx('px-4 py-3 text-sm', theme === 'dark' ? 'text-slate-300' : 'text-gray-700');
  const trCls = clsx('border-t transition-colors', theme === 'dark' ? 'border-slate-700 hover:bg-slate-700/30' : 'border-gray-100 hover:bg-gray-50');

  const tabs: { key: Tab; label: string; count: number; icon: string }[] = [
    { key: 'rightsizing', label: 'Rightsizing Opportunities', count: rightsizingItems.length, icon: 'fa-compress-arrows-alt' },
    { key: 'waste',       label: 'Waste Resources',           count: wasteItems.length,       icon: 'fa-trash-alt' },
  ];

  if (isLoading) {
    return (
      <div className={clsx(cardCls, 'animate-pulse')}>
        <div className="h-5 bg-gray-200 rounded w-1/3 mb-4" />
        <div className="space-y-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-10 bg-gray-200 rounded" />
          ))}
        </div>
      </div>
    );
  }

  const totalItems = rightsizingItems.length + wasteItems.length;
  if (totalItems === 0) {
    return (
      <div className={cardCls}>
        <div className="flex items-center gap-3 mb-5">
          <div className="w-9 h-9 bg-gradient-to-br from-emerald-500 to-teal-600 rounded-lg flex items-center justify-center shadow">
            <i className="fas fa-lightbulb text-white text-sm" />
          </div>
          <div>
            <h3 className={clsx('text-base font-bold', theme === 'dark' ? 'text-slate-100' : 'text-gray-900')}>
              Insights &amp; Opportunities
            </h3>
            <p className={clsx('text-xs', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
              Rightsizing and waste optimization
            </p>
          </div>
        </div>
        <div className="flex flex-col items-center py-12">
          <div className="w-14 h-14 bg-emerald-100 rounded-full flex items-center justify-center mb-3">
            <i className="fas fa-check-circle text-emerald-500 text-2xl" />
          </div>
          <h4 className={clsx('text-base font-semibold mb-1', theme === 'dark' ? 'text-slate-200' : 'text-gray-700')}>
            No Optimization Opportunities Found
          </h4>
          <p className={clsx('text-sm', theme === 'dark' ? 'text-slate-500' : 'text-gray-400')}>
            Your Azure resources are well-optimized.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className={cardCls}>
      <div className="flex items-center gap-3 mb-5">
        <div className="w-9 h-9 bg-gradient-to-br from-amber-500 to-orange-600 rounded-lg flex items-center justify-center shadow">
          <i className="fas fa-lightbulb text-white text-sm" />
        </div>
        <div>
          <h3 className={clsx('text-base font-bold', theme === 'dark' ? 'text-slate-100' : 'text-gray-900')}>
            Insights &amp; Opportunities
          </h3>
          <p className={clsx('text-xs', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
            Rightsizing and waste optimization
          </p>
        </div>
      </div>

      {/* Tab switcher */}
      <div className={clsx('flex gap-1 p-1 rounded-lg mb-5 w-fit', theme === 'dark' ? 'bg-slate-700' : 'bg-gray-100')}>
        {tabs.map(tab => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={clsx(
              'flex items-center gap-2 px-4 py-2 rounded-md text-sm font-medium transition-all',
              activeTab === tab.key
                ? 'bg-white text-gray-900 shadow-sm'
                : theme === 'dark'
                ? 'text-slate-400 hover:text-slate-200'
                : 'text-gray-500 hover:text-gray-700'
            )}
          >
            <i className={clsx('fas text-xs', tab.icon)} />
            {tab.label}
            <span className={clsx(
              'text-xs px-1.5 py-0.5 rounded-full font-semibold',
              activeTab === tab.key ? 'bg-blue-100 text-blue-700' : 'bg-gray-200 text-gray-600'
            )}>
              {tab.count}
            </span>
          </button>
        ))}
      </div>

      {/* Rightsizing tab */}
      {activeTab === 'rightsizing' && (
        rightsizingItems.length === 0 ? (
          <div className="flex flex-col items-center py-10">
            <i className="fas fa-check-circle text-emerald-400 text-3xl mb-2" />
            <p className={clsx('text-sm', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
              No rightsizing opportunities found
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr>
                  <th className={thCls}>Resource</th>
                  <th className={thCls}>Type</th>
                  <th className={thCls}>Current → Recommended</th>
                  <th className={thCls}>Impact</th>
                  <th className={clsx(thCls, 'text-right')}>Monthly Savings</th>
                </tr>
              </thead>
              <tbody>
                {rightsizingItems.map((item, idx) => (
                  <tr key={idx} className={trCls}>
                    <td className={clsx(tdCls, 'font-medium max-w-[180px] truncate')}>
                      {item.resourceName || '—'}
                    </td>
                    <td className={clsx(tdCls, 'text-gray-500')}>
                      {item.resourceType || '—'}
                    </td>
                    <td className={tdCls}>
                      <span className="font-mono text-xs">{item.currentSku || '—'}</span>
                      <span className="mx-1 text-gray-400">→</span>
                      <span className="font-mono text-xs text-emerald-600 font-semibold">{item.targetSku || '—'}</span>
                    </td>
                    <td className={tdCls}>
                      <span className={clsx('px-2 py-0.5 rounded-full text-xs font-medium', impactBadge(item.impact))}>
                        {item.impact || '—'}
                      </span>
                    </td>
                    <td className={clsx(tdCls, 'text-right font-semibold text-emerald-600')}>
                      {formatCurrency(item.monthlySavings)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {/* Waste tab */}
      {activeTab === 'waste' && (
        wasteItems.length === 0 ? (
          <div className="flex flex-col items-center py-10">
            <i className="fas fa-check-circle text-emerald-400 text-3xl mb-2" />
            <p className={clsx('text-sm', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
              No waste resources detected
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr>
                  <th className={thCls}>Resource</th>
                  <th className={thCls}>Type</th>
                  <th className={thCls}>Region</th>
                  <th className={thCls}>Reason</th>
                  <th className={clsx(thCls, 'text-right')}>Monthly Savings</th>
                </tr>
              </thead>
              <tbody>
                {wasteItems.map((item, idx) => (
                  <tr key={idx} className={trCls}>
                    <td className={clsx(tdCls, 'font-medium max-w-[160px] truncate')}>
                      {item.resourceName || item.resourceId?.split('/').pop() || '—'}
                    </td>
                    <td className={tdCls}>
                      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-700 border border-amber-200">
                        {item.resourceType || '—'}
                      </span>
                    </td>
                    <td className={clsx(tdCls, 'text-gray-500')}>{item.region || '—'}</td>
                    <td className={clsx(tdCls, 'text-gray-500 max-w-[200px] truncate')}>{item.reason || '—'}</td>
                    <td className={clsx(tdCls, 'text-right font-semibold text-red-600')}>
                      {formatCurrency(item.monthlySavings)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
    </div>
  );
};

export default AzureFinOpsInsightsSection;
