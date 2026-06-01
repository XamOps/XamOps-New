/**
 * FinOps Scheduled Reports Component
 *
 * Displays table of scheduled email reports
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { ReportSchedule } from '../../types/finops';

interface FinOpsScheduledReportsProps {
  schedules: ReportSchedule[];
  onDeleteSchedule: (scheduleId: number) => void;
}

const FinOpsScheduledReports: React.FC<FinOpsScheduledReportsProps> = ({ schedules, onDeleteSchedule }) => {
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
      {/* Header */}
      <div className="flex items-center gap-2 pb-4 border-b border-[rgba(0,0,0,0.06)] mb-5">
        <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]">
          <Icon icon="mdi:calendar-clock" width={16} color="#7C3AED" />
        </div>
        <div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Scheduled Reports</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-purple-400 animate-pulse inline-block"></span>
            Your automated email reports
          </p>
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)]">
              <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Account</th>
              <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Email</th>
              <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Frequency</th>
              <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Actions</th>
            </tr>
          </thead>
          <tbody>
            {schedules.map((schedule) => (
              <tr key={schedule.id} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                <td className="px-4 py-3 text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{schedule.accountName}</td>
                <td className="px-4 py-3 text-[14px] text-[rgba(0,0,0,0.88)]">{schedule.email}</td>
                <td className="px-4 py-3 text-[14px] text-[rgba(0,0,0,0.88)]">{schedule.frequency}</td>
                <td className="px-4 py-3">
                  <button
                    onClick={() => onDeleteSchedule(schedule.id)}
                    className="text-red-500 hover:text-red-700 text-xs flex items-center gap-1"
                  >
                    <Icon icon="mdi:trash-can-outline" width={14} />
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default FinOpsScheduledReports;
