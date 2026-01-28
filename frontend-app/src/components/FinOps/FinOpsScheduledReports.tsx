/**
 * FinOps Scheduled Reports Component
 *
 * Displays table of scheduled email reports
 */

import React from 'react';
import type { ReportSchedule } from '../../types/finops';

interface FinOpsScheduledReportsProps {
  schedules: ReportSchedule[];
  onDeleteSchedule: (scheduleId: number) => void;
}

const FinOpsScheduledReports: React.FC<FinOpsScheduledReportsProps> = ({ schedules, onDeleteSchedule }) => {
  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md fade-in">
      <div className="flex items-center space-x-3 mb-6">
        <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-pink-600 rounded-xl flex items-center justify-center shadow-md">
          <i className="fas fa-calendar-alt text-white"></i>
        </div>
        <div>
          <h3 className="text-xl font-bold text-gray-800">Scheduled Reports</h3>
          <p className="text-sm text-gray-600">Your automated email reports</p>
        </div>
      </div>

      <div className="overflow-x-auto rounded-lg border border-gray-200">
        <table className="w-full text-sm text-left text-gray-600">
          <thead className="text-xs text-gray-700 uppercase bg-gray-50 sticky top-0">
            <tr>
              <th className="px-6 py-4">Account</th>
              <th className="px-6 py-4">Email</th>
              <th className="px-6 py-4">Frequency</th>
              <th className="px-6 py-4">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {schedules.map((schedule) => (
              <tr key={schedule.id} className="hover:bg-gray-50">
                <td className="px-6 py-4 font-semibold">{schedule.accountName}</td>
                <td className="px-6 py-4">{schedule.email}</td>
                <td className="px-6 py-4">{schedule.frequency}</td>
                <td className="px-6 py-4">
                  <button
                    onClick={() => onDeleteSchedule(schedule.id)}
                    className="text-red-500 hover:text-red-700 flex items-center"
                  >
                    <i className="fas fa-trash-alt mr-1"></i>Delete
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
