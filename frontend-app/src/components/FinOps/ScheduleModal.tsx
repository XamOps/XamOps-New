import React, { useState } from 'react';
import type { NewSchedule } from '../../types/finops';
import { DialogRoot, DialogPanel } from '../ui';
import { Icon } from '@iconify/react';

interface ScheduleModalProps {
  isOpen: boolean;
  isCreating: boolean;
  cloudAccountId: string | null;
  onClose: () => void;
  onSubmit: (schedule: NewSchedule) => void;
}

const ScheduleModal: React.FC<ScheduleModalProps> = ({ isOpen, isCreating, cloudAccountId, onClose, onSubmit }) => {
  const [schedule, setSchedule] = useState<NewSchedule>({ email: '', frequency: '', cloudAccountId });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit({ ...schedule, cloudAccountId });
    setSchedule({ email: '', frequency: '', cloudAccountId });
  };

  const handleClose = () => {
    setSchedule({ email: '', frequency: '', cloudAccountId });
    onClose();
  };

  const inputCls = 'w-full px-3 py-2.5 rounded-lg text-[13px] border border-[rgba(0,0,0,0.12)] focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition-colors';
  const labelCls = 'block text-[12px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-wide mb-1.5';

  return (
    <DialogRoot isOpen={isOpen} onClose={handleClose}>
      <DialogPanel
        title={
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-white border border-[rgba(0,0,0,0.12)] flex items-center justify-center flex-shrink-0">
              <Icon icon="mdi:email-outline" width={18} color="#2563EB" />
            </div>
            <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Schedule FinOps Report</span>
          </div>
        }
        onClose={handleClose}
        footer={
          <>
            <button type="button" onClick={handleClose} className="px-4 py-2 text-[13px] font-medium text-[rgba(0,0,0,0.6)] border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.04)] transition-colors">
              Cancel
            </button>
            <button type="submit" form="schedule-form" disabled={isCreating} className="px-4 py-2 text-[13px] font-bold bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center gap-2">
              {isCreating ? <><div className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />Saving...</> : 'Create Schedule'}
            </button>
          </>
        }
      >
        <form id="schedule-form" onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="scheduleEmail" className={labelCls}>Email Address</label>
            <input type="email" id="scheduleEmail" value={schedule.email} onChange={(e) => setSchedule({ ...schedule, email: e.target.value })} className={inputCls} placeholder="recipient@example.com" required />
          </div>
          <div>
            <label htmlFor="scheduleFrequency" className={labelCls}>Frequency</label>
            <select id="scheduleFrequency" value={schedule.frequency} onChange={(e) => setSchedule({ ...schedule, frequency: e.target.value })} className={inputCls} required>
              <option value="" disabled>Select a frequency</option>
              <option value="DAILY">Daily (at 6 PM)</option>
              <option value="WEEKLY">Weekly (Friday at 6 PM)</option>
              <option value="MONTHLY">Monthly (30th at 6 PM)</option>
            </select>
          </div>
        </form>
      </DialogPanel>
    </DialogRoot>
  );
};

export default ScheduleModal;
