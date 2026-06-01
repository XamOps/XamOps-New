import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { FlexComputeCapacity } from '../../types/flexCompute';
import { DialogRoot, DialogPanel } from '../ui';
import { TIME_ZONES } from '../../types/flexCompute';

interface ExtendUsageModalProps {
  capacity: FlexComputeCapacity;
  onExtend: (cidrId: string, datetime: string, timezone: string) => Promise<boolean>;
  onClose: () => void;
}

const roundToQuarterHour = (datetimeStr: string): string => {
  if (!datetimeStr) return datetimeStr;
  const [datePart, timePart] = datetimeStr.split('T');
  const [h, m] = (timePart || '00:00').split(':').map(Number);
  const totalMins = h * 60 + m;
  const rounded = Math.round(totalMins / 15) * 15;
  const rh = Math.floor(rounded / 60) % 24;
  const rm = rounded % 60;
  const date = new Date(`${datePart}T00:00Z`);
  if (Math.floor(rounded / 60) >= 24) date.setUTCDate(date.getUTCDate() + 1);
  const newDate = date.toISOString().slice(0, 10);
  return `${newDate}T${String(rh).padStart(2, '0')}:${String(rm).padStart(2, '0')}`;
};

const inputCls = 'w-full px-3 py-2.5 border border-[rgba(0,0,0,0.15)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none bg-white';
const labelCls = 'block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] mb-1.5';

const ExtendUsageModal: React.FC<ExtendUsageModalProps> = ({ capacity, onExtend, onClose }) => {
  const [newEndDatetime, setNewEndDatetime] = useState('');
  const [timezone, setTimezone]             = useState('America/Los_Angeles');
  const [isSubmitting, setIsSubmitting]     = useState(false);
  const [error, setError]                   = useState<string | null>(null);

  const handleSubmit = async () => {
    if (!newEndDatetime) { setError('Please select a new end date and time.'); return; }
    const rounded = roundToQuarterHour(newEndDatetime);
    setIsSubmitting(true);
    setError(null);
    try {
      const success = await onExtend(capacity.id, rounded, timezone);
      if (success) { onClose(); }
      else { setError('Failed to extend capacity. Please try again.'); }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Extend failed');
    } finally {
      setIsSubmitting(false);
    }
  };

  const minDatetime = roundToQuarterHour(
    new Date(Date.now() + 60 * 60 * 1000).toISOString().slice(0, 16)
  );

  return (
    <DialogRoot isOpen={true} onClose={onClose} maxWidth="max-w-md">
      <DialogPanel
        title={
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 bg-indigo-50 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:clock-plus-outline" width={18} color="#6366F1" />
            </div>
            <div>
              <div className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Extend Usage</div>
              <div className="text-[12px] text-[rgba(0,0,0,0.45)]">{capacity.name}</div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={
          <>
            <button
              onClick={onClose}
              disabled={isSubmitting}
              className="flex items-center gap-2 px-5 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              onClick={handleSubmit}
              disabled={isSubmitting || !newEndDatetime}
              className="flex items-center gap-2 px-5 py-2.5 text-[13px] font-semibold text-white bg-indigo-600 rounded-xl hover:bg-indigo-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {isSubmitting
                ? <><Icon icon="mdi:loading" width={14} className="animate-spin" />Extending...</>
                : <><Icon icon="mdi:clock-plus-outline" width={14} />Extend Usage</>
              }
            </button>
          </>
        }
      >
        <div className="space-y-5">
          <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl p-4">
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-1">Current End Date</p>
            <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{capacity.endDate || '—'}</p>
          </div>

          <div>
            <label className={labelCls}>
              <Icon icon="mdi:earth" width={12} className="inline mr-1.5 text-[rgba(0,0,0,0.35)]" />
              Time Zone
            </label>
            <select value={timezone} onChange={(e) => setTimezone(e.target.value)} className={inputCls}>
              {TIME_ZONES.map((tz) => (
                <option key={tz.value} value={tz.value}>{tz.label}</option>
              ))}
            </select>
          </div>

          <div>
            <label className={labelCls}>
              <Icon icon="mdi:calendar-clock-outline" width={12} className="inline mr-1.5 text-[rgba(0,0,0,0.35)]" />
              New End Date & Time
            </label>
            <input
              type="datetime-local"
              value={newEndDatetime}
              min={minDatetime}
              onChange={(e) => setNewEndDatetime(e.target.value)}
              className={inputCls}
            />
            <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1 flex items-center gap-1">
              <Icon icon="mdi:information-outline" width={12} />
              Time will be rounded to the nearest 15-minute increment (00, 15, 30, 45).
            </p>
          </div>

          {error && (
            <div className="bg-red-50 border border-red-200 rounded-xl px-4 py-3 text-[13px] text-red-700 flex items-start gap-2">
              <Icon icon="mdi:alert-circle-outline" width={15} className="mt-0.5 flex-shrink-0" />
              <span>{error}</span>
            </div>
          )}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default ExtendUsageModal;
