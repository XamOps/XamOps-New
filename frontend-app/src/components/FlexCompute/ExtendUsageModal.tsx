/**
 * Extend Usage Modal
 *
 * Allows users to extend the end date/time of an active capacity reservation
 */

import React, { useState } from 'react';
import type { FlexComputeCapacity } from '../../types/flexCompute';
import { DialogRoot, DialogPanel } from '../ui';
import { TIME_ZONES } from '../../types/flexCompute';

interface ExtendUsageModalProps {
  capacity: FlexComputeCapacity;
  onExtend: (cidrId: string, endDate: string, endTime: string, utcOffset: string) => Promise<boolean>;
  onClose: () => void;
}

// Round minutes up to the next 15-min increment
const roundToQuarterHour = (datetimeStr: string): string => {
  if (!datetimeStr) return datetimeStr;
  const date = new Date(datetimeStr);
  const minutes = date.getMinutes();
  const roundedMinutes = Math.round(minutes / 15) * 15;
  if (roundedMinutes === 60) {
    date.setHours(date.getHours() + 1);
    date.setMinutes(0);
  } else {
    date.setMinutes(roundedMinutes);
  }
  return date.toISOString().slice(0, 16);
};

// Convert IANA timezone to UTC offset string e.g. "America/Los_Angeles" -> "-8"
const tzOffsetMap: Record<string, string> = {
  'America/Los_Angeles': '-8',
  'America/Denver': '-7',
  'America/Chicago': '-6',
  'America/New_York': '-5',
  'Europe/London': '0',
  'Europe/Berlin': '1',
  'Asia/Kolkata': '5.5',
  'Asia/Tokyo': '9',
  'Australia/Sydney': '11',
};

const ExtendUsageModal: React.FC<ExtendUsageModalProps> = ({ capacity, onExtend, onClose }) => {
  const [newEndDatetime, setNewEndDatetime] = useState('');
  const [timezone, setTimezone] = useState('America/Los_Angeles');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async () => {
    if (!newEndDatetime) {
      setError('Please select a new end date and time.');
      return;
    }

    const rounded = roundToQuarterHour(newEndDatetime);
    const date = new Date(rounded);

    // Format date as MM/dd/yyyy
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const year = date.getFullYear();
    const endDate = `${month}/${day}/${year}`;

    // Format time as HH:mm
    const hours = String(date.getHours()).padStart(2, '0');
    const mins = String(date.getMinutes()).padStart(2, '0');
    const endTime = `${hours}:${mins}`;

    const utcOffset = tzOffsetMap[timezone] || '0';

    setIsSubmitting(true);
    setError(null);

    try {
      const success = await onExtend(capacity.id, endDate, endTime, utcOffset);
      if (success) {
        onClose();
      } else {
        setError('Failed to extend capacity. Please try again.');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Extend failed');
    } finally {
      setIsSubmitting(false);
    }
  };

  // Minimum datetime = now + 1 hour, rounded to next 15-min mark
  const minDatetime = roundToQuarterHour(
    new Date(Date.now() + 60 * 60 * 1000).toISOString().slice(0, 16)
  );

  return (
    <DialogRoot isOpen={true} onClose={onClose} maxWidth="max-w-md">
      <DialogPanel
        title={
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 bg-purple-100 rounded-lg flex items-center justify-center">
              <i className="fas fa-clock text-purple-600"></i>
            </div>
            <div>
              <div className="text-base font-bold text-gray-900">Extend Usage</div>
              <div className="text-xs text-gray-500">{capacity.name}</div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={
          <>
            <button onClick={onClose} disabled={isSubmitting} className="px-5 py-2.5 text-sm font-medium text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors disabled:opacity-50">
              Cancel
            </button>
            <button onClick={handleSubmit} disabled={isSubmitting || !newEndDatetime} className="px-5 py-2.5 text-sm font-semibold text-white bg-purple-600 rounded-lg hover:bg-purple-700 transition-colors shadow-lg shadow-purple-500/25 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2">
              {isSubmitting ? <><i className="fas fa-spinner fa-spin"></i>Extending...</> : <><i className="fas fa-clock"></i>Extend Usage</>}
            </button>
          </>
        }
      >
        <div className="space-y-5">
          <div className="bg-gray-50 rounded-xl p-4">
            <p className="text-xs text-gray-500 mb-1">Current End Date</p>
            <p className="text-sm font-semibold text-gray-800">{capacity.endDate || '—'}</p>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              <i className="fas fa-globe mr-1.5 text-gray-400"></i>
              Time Zone
            </label>
            <select value={timezone} onChange={(e) => setTimezone(e.target.value)} className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-purple-500 outline-none bg-white">
              {TIME_ZONES.map((tz) => (
                <option key={tz.value} value={tz.value}>{tz.label}</option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              <i className="fas fa-calendar-alt mr-1.5 text-gray-400"></i>
              New End Date & Time
            </label>
            <input type="datetime-local" value={newEndDatetime} min={minDatetime} onChange={(e) => setNewEndDatetime(e.target.value)} className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-purple-500 outline-none" />
            <p className="text-xs text-gray-400 mt-1">
              <i className="fas fa-info-circle mr-1"></i>
              Time will be rounded to the nearest 15-minute increment (00, 15, 30, 45).
            </p>
          </div>

          {error && (
            <div className="bg-red-50 border border-red-200 rounded-lg px-4 py-3 text-sm text-red-700 flex items-start gap-2">
              <i className="fas fa-exclamation-circle mt-0.5 flex-shrink-0"></i>
              <span>{error}</span>
            </div>
          )}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default ExtendUsageModal;
