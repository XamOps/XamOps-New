import React from 'react';
import { Icon } from '@iconify/react';
import { AzureReportSchedule } from '../../types/azureFinops';

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
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-3">
          <div
            className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]"
            style={{ backgroundColor: '#8B5CF614' }}
          >
            <Icon icon="mdi:calendar-clock" width={16} color="#8B5CF6" />
          </div>
          <div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Scheduled Reports</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">Automated FinOps email reports</p>
          </div>
        </div>
        <button
          onClick={onOpenScheduleModal}
          className="flex items-center gap-2 bg-blue-600 text-white px-4 py-2 rounded-xl text-xs font-bold hover:bg-blue-700 transition-colors"
        >
          <Icon icon="mdi:plus" width={14} />
          Schedule Report
        </button>
      </div>

      {schedules.length === 0 ? (
        <div className="flex flex-col items-center py-10 gap-2">
          <div className="w-12 h-12 bg-purple-50 rounded-full flex items-center justify-center">
            <Icon icon="mdi:calendar-clock" width={24} color="#8B5CF6" />
          </div>
          <p className="text-[14px] font-medium text-[rgba(0,0,0,0.6)]">No scheduled reports</p>
          <p className="text-[12px] text-[rgba(0,0,0,0.4)]">
            Set up automated email reports for this subscription.
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr>
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">
                  Subscription
                </th>
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">
                  Email
                </th>
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">
                  Frequency
                </th>
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">
                  Actions
                </th>
              </tr>
            </thead>
            <tbody>
              {schedules.map(s => (
                <tr key={s.id} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                  <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.88)] font-medium">
                    {s.subscriptionName || s.accountName || 'Azure Subscription'}
                  </td>
                  <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.88)]">
                    {s.email}
                  </td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-purple-50 text-purple-600 border border-purple-100">
                      {s.frequency}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <button
                      onClick={() => onDeleteSchedule(s.id)}
                      className="inline-flex items-center gap-1 text-red-500 hover:text-red-700 text-[12px] font-medium transition-colors"
                    >
                      <Icon icon="mdi:delete-outline" width={14} />
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
