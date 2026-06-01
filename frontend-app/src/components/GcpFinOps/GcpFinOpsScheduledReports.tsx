import React from 'react';
import type { GcpReportSchedule } from '../../types/gcpFinops';
import { Icon } from '@iconify/react';

interface GcpFinOpsScheduledReportsProps {
  schedules: GcpReportSchedule[];
  onDeleteSchedule: (scheduleId: number) => void;
}

const GcpFinOpsScheduledReports: React.FC<GcpFinOpsScheduledReportsProps> = ({ schedules, onDeleteSchedule }) => {
  if (!schedules || schedules.length === 0) return null;
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
      <div className="flex items-center justify-between mb-4">
        <div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Scheduled Reports</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-blue-400 inline-block"></span>
            Automated email reports
          </p>
        </div>
        <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 rounded-md">{schedules.length}</span>
      </div>
      <table className="w-full">
        <thead>
          <tr className="border-b border-[rgba(0,0,0,0.06)]">
            <th className="pb-2 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Account</th>
            <th className="pb-2 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Email</th>
            <th className="pb-2 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Frequency</th>
            <th className="pb-2 text-right text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Actions</th>
          </tr>
        </thead>
        <tbody>
          {schedules.map((s) => (
            <tr key={s.id} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
              <td className="py-3 text-[13px] text-[rgba(0,0,0,0.88)] font-medium">{s.accountName}</td>
              <td className="py-3 text-[13px] text-[rgba(0,0,0,0.6)]">{s.email}</td>
              <td className="py-3 text-[13px] text-[rgba(0,0,0,0.6)]">{s.frequency}</td>
              <td className="py-3 text-right">
                <button onClick={() => onDeleteSchedule(s.id)} className="inline-flex items-center gap-1 text-[12px] text-red-500 hover:text-red-700 transition-colors">
                  <Icon icon="mdi:trash-can-outline" width={14} />
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default GcpFinOpsScheduledReports;
