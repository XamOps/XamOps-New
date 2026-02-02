/**
 * GCP Schedule Modal Component
 *
 * Modal for scheduling automated GCP FinOps email reports
 */

import React, { useState, useEffect } from 'react';
import type { GcpNewSchedule } from '../../types/gcpFinops';

interface GcpScheduleModalProps {
  isOpen: boolean;
  isCreating: boolean;
  cloudAccountId: string | null;
  onClose: () => void;
  onSubmit: (schedule: GcpNewSchedule) => void;
}

const GcpScheduleModal: React.FC<GcpScheduleModalProps> = ({
  isOpen,
  isCreating,
  cloudAccountId,
  onClose,
  onSubmit,
}) => {
  const [email, setEmail] = useState('');
  const [frequency, setFrequency] = useState('');
  const [customFrequencyType, setCustomFrequencyType] = useState('DAILY');
  const [customHour, setCustomHour] = useState(18);
  const [customMinute, setCustomMinute] = useState(0);
  const [customDayOfWeek, setCustomDayOfWeek] = useState(5);
  const [customDayOfMonth, setCustomDayOfMonth] = useState(1);

  // Reset form when modal opens
  useEffect(() => {
    if (isOpen) {
      setEmail('');
      setFrequency('');
      setCustomFrequencyType('DAILY');
      setCustomHour(18);
      setCustomMinute(0);
      setCustomDayOfWeek(5);
      setCustomDayOfMonth(1);
    }
  }, [isOpen]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    const schedule: GcpNewSchedule = {
      email,
      frequency,
      cloudAccountId,
    };

    // Add custom fields if CUSTOM frequency is selected
    if (frequency === 'CUSTOM') {
      schedule.customHour = customHour;
      schedule.customMinute = customMinute;
      schedule.customFrequencyType = customFrequencyType;

      if (customFrequencyType === 'WEEKLY') {
        schedule.customDayOfWeek = customDayOfWeek;
      } else if (customFrequencyType === 'MONTHLY') {
        schedule.customDayOfMonth = customDayOfMonth;
      }
    }

    onSubmit(schedule);
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onClick={onClose}>
      <div
        className="bg-white rounded-2xl p-8 max-w-md w-full mx-4 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center space-x-3 mb-6">
          <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-pink-600 rounded-xl flex items-center justify-center">
            <i className="fas fa-calendar-alt text-white"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-800">Schedule GCP FinOps Report</h3>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="space-y-6">
            <div>
              <label htmlFor="scheduleEmail" className="block text-sm font-semibold text-gray-700 mb-2">
                Email Address
              </label>
              <input
                type="email"
                id="scheduleEmail"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="w-full px-4 py-3 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                placeholder="recipient@example.com"
                required
              />
            </div>
            <div>
              <label htmlFor="scheduleFrequency" className="block text-sm font-semibold text-gray-700 mb-2">
                Frequency
              </label>
              <select
                id="scheduleFrequency"
                value={frequency}
                onChange={(e) => setFrequency(e.target.value)}
                className="w-full px-4 py-3 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                required
              >
                <option value="">Select a timeline</option>
                <option value="DAILY">Daily (at 6 PM)</option>
                <option value="WEEKLY">Weekly (Friday at 6 PM)</option>
                <option value="MONTHLY">Monthly (30th at 6 PM)</option>
                <option value="CUSTOM">Custom Time</option>
              </select>
            </div>

            {/* Custom Time Fields (shown when CUSTOM is selected) */}
            {frequency === 'CUSTOM' && (
              <div className="space-y-4 p-4 bg-blue-50 rounded-lg border border-blue-200">
                <h4 className="text-sm font-semibold text-gray-800 mb-3">Custom Schedule Settings</h4>

                {/* Frequency Type */}
                <div>
                  <label className="block text-sm font-semibold text-gray-700 mb-2">Schedule Type</label>
                  <select
                    value={customFrequencyType}
                    onChange={(e) => setCustomFrequencyType(e.target.value)}
                    className="w-full px-4 py-2 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                  >
                    <option value="DAILY">Daily</option>
                    <option value="WEEKLY">Weekly</option>
                    <option value="MONTHLY">Monthly</option>
                  </select>
                </div>

                {/* Time Selection */}
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-2">Hour (0-23)</label>
                    <input
                      type="number"
                      value={customHour}
                      onChange={(e) => setCustomHour(parseInt(e.target.value))}
                      min="0"
                      max="23"
                      className="w-full px-4 py-2 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                      placeholder="18"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-2">Minute (0-59)</label>
                    <input
                      type="number"
                      value={customMinute}
                      onChange={(e) => setCustomMinute(parseInt(e.target.value))}
                      min="0"
                      max="59"
                      className="w-full px-4 py-2 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                      placeholder="0"
                    />
                  </div>
                </div>

                {/* Day of Week (for Weekly) */}
                {customFrequencyType === 'WEEKLY' && (
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-2">Day of Week</label>
                    <select
                      value={customDayOfWeek}
                      onChange={(e) => setCustomDayOfWeek(parseInt(e.target.value))}
                      className="w-full px-4 py-2 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                    >
                      <option value="1">Monday</option>
                      <option value="2">Tuesday</option>
                      <option value="3">Wednesday</option>
                      <option value="4">Thursday</option>
                      <option value="5">Friday</option>
                      <option value="6">Saturday</option>
                      <option value="7">Sunday</option>
                    </select>
                  </div>
                )}

                {/* Day of Month (for Monthly) */}
                {customFrequencyType === 'MONTHLY' && (
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-2">Day of Month (1-31)</label>
                    <input
                      type="number"
                      value={customDayOfMonth}
                      onChange={(e) => setCustomDayOfMonth(parseInt(e.target.value))}
                      min="1"
                      max="31"
                      className="w-full px-4 py-2 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                      placeholder="1"
                    />
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="flex justify-end space-x-3 mt-8">
            <button
              type="button"
              onClick={onClose}
              className="px-6 py-3 bg-white border border-gray-200 text-gray-700 font-semibold rounded-lg hover:bg-gray-50 transition-all"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isCreating}
              className="px-6 py-3 bg-blue-600 text-white font-semibold rounded-lg shadow-md hover:bg-blue-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center"
            >
              {isCreating ? (
                <>
                  <div className="loading-spinner mr-2"></div>
                  Saving...
                </>
              ) : (
                'Create Schedule'
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default GcpScheduleModal;
