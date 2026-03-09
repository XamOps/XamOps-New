/**
 * Schedule Modal Component
 *
 * Modal for scheduling FinOps reports
 */

import React, { useState } from 'react';
import type { NewSchedule } from '../../types/finops';
import { DialogRoot, DialogPanel } from '../ui';

interface ScheduleModalProps {
  isOpen: boolean;
  isCreating: boolean;
  cloudAccountId: string | null;
  onClose: () => void;
  onSubmit: (schedule: NewSchedule) => void;
}

const ScheduleModal: React.FC<ScheduleModalProps> = ({
  isOpen,
  isCreating,
  cloudAccountId,
  onClose,
  onSubmit,
}) => {
  const [schedule, setSchedule] = useState<NewSchedule>({
    email: '',
    frequency: '',
    cloudAccountId: cloudAccountId,
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit({ ...schedule, cloudAccountId: cloudAccountId });
    // Reset form
    setSchedule({ email: '', frequency: '', cloudAccountId: cloudAccountId });
  };

  const handleClose = () => {
    setSchedule({ email: '', frequency: '', cloudAccountId: cloudAccountId });
    onClose();
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={handleClose}>
      <DialogPanel
        title={
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-pink-600 rounded-xl flex items-center justify-center shadow-md">
              <i className="fas fa-envelope text-white"></i>
            </div>
            <span>Schedule FinOps Report</span>
          </div>
        }
        onClose={handleClose}
        footer={
          <>
            <button type="button" onClick={handleClose} className="px-6 py-3 bg-white border border-gray-200 text-gray-700 font-semibold rounded-lg hover:bg-gray-50 transition-all">
              Cancel
            </button>
            <button type="submit" form="schedule-form" disabled={isCreating} className="px-6 py-3 bg-indigo-600 text-white font-semibold rounded-lg shadow-md hover:bg-indigo-700 transition-all flex items-center disabled:opacity-50">
              {isCreating ? <><div className="border-2 border-white border-t-transparent rounded-full w-4 h-4 animate-spin mr-2"></div>Saving...</> : 'Create Schedule'}
            </button>
          </>
        }
      >
        <form id="schedule-form" onSubmit={handleSubmit}>
          <div className="space-y-6">
            <div>
              <label htmlFor="scheduleEmail" className="block text-sm font-semibold text-gray-700 mb-2">Email Address</label>
              <input type="email" id="scheduleEmail" value={schedule.email} onChange={(e) => setSchedule({ ...schedule, email: e.target.value })} className="w-full px-4 py-3 rounded-lg border border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 transition-all" placeholder="recipient@example.com" required />
            </div>
            <div>
              <label htmlFor="scheduleFrequency" className="block text-sm font-semibold text-gray-700 mb-2">Frequency</label>
              <select id="scheduleFrequency" value={schedule.frequency} onChange={(e) => setSchedule({ ...schedule, frequency: e.target.value })} className="w-full px-4 py-3 rounded-lg border border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 transition-all" required>
                <option value="" disabled>Select a timeline</option>
                <option value="DAILY">Daily (at 6 PM)</option>
                <option value="WEEKLY">Weekly (Friday at 6 PM)</option>
                <option value="MONTHLY">Monthly (30th at 6 PM)</option>
              </select>
            </div>
          </div>
        </form>
      </DialogPanel>
    </DialogRoot>
  );
};

export default ScheduleModal;
