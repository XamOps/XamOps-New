import React from 'react';
import { AzureReportSchedule } from '../../types/azureFinops';
import { useTheme } from '../../contexts/ThemeContext';
import clsx from 'clsx';

interface AzureFinOpsScheduleSectionProps {
  schedules: AzureReportSchedule[];
  onDeleteSchedule: (id: number) => void;
  onOpenScheduleModal: () => void;
}

const AzureFinOpsScheduleSection: React.FC<AzureFinOpsScheduleSectionProps> = ({
  schedules,
  onDeleteSchedule,
  onOpenScheduleModal,
}) => {
  const { theme } = useTheme();

  const cardCls = clsx('glass-card rounded-xl p-6', theme === 'dark' ? 'bg-slate-800/50' : '');
  const thCls = clsx('px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide', theme === 'dark' ? 'text-slate-400' : 'text-gray-500');
  const tdCls = clsx('px-4 py-3 text-sm', theme === 'dark' ? 'text-slate-300' : 'text-gray-700');
  const trCls = clsx('border-t', theme === 'dark' ? 'border-slate-700 hover:bg-slate-700/30' : 'border-gray-100 hover:bg-gray-50');

  return (
    <div className={cardCls}>
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 bg-gradient-to-br from-purple-500 to-pink-600 rounded-lg flex items-center justify-center shadow">
            <i className="fas fa-calendar-alt text-white text-sm" />
          </div>
          <div>
            <h3 className={clsx('text-base font-bold', theme === 'dark' ? 'text-slate-100' : 'text-gray-900')}>
              Scheduled Reports
            </h3>
            <p className={clsx('text-xs', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
              Automated FinOps email reports
            </p>
          </div>
        </div>
        <button
          onClick={onOpenScheduleModal}
          className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-purple-600 text-white hover:bg-purple-700 transition-colors"
        >
          <i className="fas fa-plus text-xs" />
          Schedule Report
        </button>
      </div>

      {schedules.length === 0 ? (
        <div className="flex flex-col items-center py-10">
          <div className="w-12 h-12 bg-purple-100 rounded-full flex items-center justify-center mb-3">
            <i className="fas fa-calendar-alt text-purple-500 text-xl" />
          </div>
          <p className={clsx('text-sm font-medium mb-1', theme === 'dark' ? 'text-slate-300' : 'text-gray-600')}>
            No scheduled reports
          </p>
          <p className={clsx('text-xs', theme === 'dark' ? 'text-slate-500' : 'text-gray-400')}>
            Set up automated email reports for this subscription.
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr>
                <th className={thCls}>Subscription</th>
                <th className={thCls}>Email</th>
                <th className={thCls}>Frequency</th>
                <th className={thCls}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {schedules.map(s => (
                <tr key={s.id} className={trCls}>
                  <td className={clsx(tdCls, 'font-medium')}>{s.subscriptionName || s.accountName || 'Azure Subscription'}</td>
                  <td className={tdCls}>{s.email}</td>
                  <td className={tdCls}>
                    <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-purple-100 text-purple-700 border border-purple-200">
                      {s.frequency}
                    </span>
                  </td>
                  <td className={tdCls}>
                    <button
                      onClick={() => onDeleteSchedule(s.id)}
                      className="text-red-500 hover:text-red-700 text-xs font-medium transition-colors"
                    >
                      <i className="fas fa-trash-alt mr-1" />
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default AzureFinOpsScheduleSection;
