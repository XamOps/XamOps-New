import React, { useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { SearchCapacityForm } from '../../types/flexCompute';
import { INSTANCE_TYPES, TIME_ZONES } from '../../types/flexCompute';
import { RadioGroup, Radio } from 'react-aria-components';

interface LinkedAccount {
  id: string;
  cspAccountId: string;
  alias: string;
  accountRegions?: Array<{
    id: number;
    operatingCspRegionAz: {
      regionCode: string;
      regionType: string;
      availabilityZoneId: string;
    };
  }>;
}

interface WizardStep1SearchProps {
  form: SearchCapacityForm;
  onChange: (updates: Partial<SearchCapacityForm>) => void;
  onSearch: () => void;
  isSearching: boolean;
  accounts: LinkedAccount[];
}

const inputCls = 'w-full px-3 py-2.5 border border-[rgba(0,0,0,0.15)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none bg-white disabled:bg-[rgba(0,0,0,0.03)] disabled:text-[rgba(0,0,0,0.35)] disabled:cursor-not-allowed';
const labelCls = 'block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] mb-1.5';

const WizardStep1Search: React.FC<WizardStep1SearchProps> = ({
  form,
  onChange,
  onSearch,
  isSearching,
  accounts,
}) => {
  const [startDateError, setStartDateError] = useState('');
  const [endDateError, setEndDateError] = useState('');

  const roundToQuarterHour = (datetimeStr: string): string => {
    if (!datetimeStr) return datetimeStr;
    const [datePart, timePart] = datetimeStr.split('T');
    const [hoursStr, minutesStr] = (timePart || '00:00').split(':');
    let hours = parseInt(hoursStr, 10);
    const minutes = parseInt(minutesStr, 10);
    const roundedMinutes = Math.round(minutes / 15) * 15;
    if (roundedMinutes === 60) {
      hours = (hours + 1) % 24;
      return `${datePart}T${String(hours).padStart(2, '0')}:00`;
    }
    return `${datePart}T${String(hours).padStart(2, '0')}:${String(roundedMinutes).padStart(2, '0')}`;
  };

  const [, setTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setTick((t) => t + 1), 60000);
    return () => clearInterval(id);
  }, []);

  const minStartTime = useMemo(() => {
    const future = new Date();
    future.setMinutes(future.getMinutes() + 30);
    const tzString = future.toLocaleString('sv-SE', { timeZone: form.timeZone });
    const [datePart, timePart] = tzString.split(' ');
    const [h, m] = timePart.split(':').map(Number);
    const roundedUp = Math.ceil(m / 15) * 15;
    const finalH = roundedUp >= 60 ? (h + 1) % 24 : h;
    const finalM = roundedUp >= 60 ? 0 : roundedUp;
    return `${datePart}T${String(finalH).padStart(2, '0')}:${String(finalM).padStart(2, '0')}`;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form.timeZone, Math.floor(Date.now() / 60000)]);

  const handleStartDateChange = (rawValue: string) => {
    setStartDateError('');
    setEndDateError('');
    const rounded = roundToQuarterHour(rawValue);
    if (rounded < minStartTime) {
      setStartDateError('Start time must be at least 30 minutes from now.');
      return;
    }
    if (form.endDate && form.endDate <= rounded) {
      onChange({ startDate: rounded, endDate: '' });
      setEndDateError('End date/time must be after the start date/time.');
    } else {
      onChange({ startDate: rounded });
    }
  };

  const handleEndDateChange = (rawValue: string) => {
    setEndDateError('');
    const rounded = roundToQuarterHour(rawValue);
    if (form.startDate && rounded <= form.startDate) {
      setEndDateError('End date/time must be strictly after the start date/time.');
      return;
    }
    if (form.startDate) {
      const startMs = new Date(form.startDate).getTime();
      const endMs = new Date(rounded).getTime();
      if (endMs - startMs < 60 * 60 * 1000) {
        setEndDateError('Minimum capacity duration is 1 hour.');
        return;
      }
    }
    onChange({ endDate: rounded });
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (form.startDate && form.startDate < minStartTime) {
      setStartDateError('Start time is now in the past. Please select a new start time.');
      return;
    }
    if (form.startDate && form.endDate) {
      const startMs = new Date(form.startDate).getTime();
      const endMs = new Date(form.endDate).getTime();
      if (endMs - startMs < 60 * 60 * 1000) {
        setEndDateError('Minimum capacity duration is 1 hour.');
        return;
      }
    }
    if (startDateError || endDateError) return;
    onSearch();
  };

  const availableRegions = useMemo(() => {
    if (!form.cspAccount) return [];
    const selectedAccount = accounts.find(
      (acc) => acc.cspAccountId === form.cspAccount || acc.id === form.cspAccount
    );
    if (!selectedAccount?.accountRegions) return [];
    return selectedAccount.accountRegions.map((region) => ({
      regionCode: region.operatingCspRegionAz.regionCode,
      zoneId: region.operatingCspRegionAz.availabilityZoneId,
      label: `${region.operatingCspRegionAz.regionCode}/${region.operatingCspRegionAz.availabilityZoneId}`,
      value: `${region.operatingCspRegionAz.regionCode}/${region.operatingCspRegionAz.availabilityZoneId}`,
    }));
  }, [form.cspAccount, accounts]);

  const handleTimezoneChange = (tz: string) => {
    onChange({ timeZone: tz });
    if (form.startDate) {
      const future = new Date();
      future.setMinutes(future.getMinutes() + 30);
      const tzString = future.toLocaleString('sv-SE', { timeZone: tz });
      const [datePart, timePart] = tzString.split(' ');
      const [h, m] = timePart.split(':').map(Number);
      const roundedUp = Math.ceil(m / 15) * 15;
      const finalH = roundedUp >= 60 ? (h + 1) % 24 : h;
      const finalM = roundedUp >= 60 ? 0 : roundedUp;
      const newMin = `${datePart}T${String(finalH).padStart(2, '0')}:${String(finalM).padStart(2, '0')}`;
      if (form.startDate < newMin) {
        onChange({ timeZone: tz, startDate: '', endDate: '' });
        setStartDateError('Start time is now in the past for the selected timezone. Please re-select.');
        setEndDateError('');
      } else {
        setStartDateError('');
      }
    }
  };

  const handleRegionZoneChange = (value: string) => {
    const [regionCode, zoneId] = value.split('/');
    onChange({ region: regionCode, zoneId: zoneId });
  };

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.01)]">
        <div className="flex items-center gap-3">
          <div className="w-7 h-7 bg-indigo-600 rounded-lg flex items-center justify-center text-[12px] font-bold text-white">1</div>
          <div>
            <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Search Capacity</h3>
            <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Define your capacity requirements</p>
          </div>
        </div>
      </div>

      {/* Form */}
      <form onSubmit={handleSubmit} className="p-6 space-y-5">
        {/* Row 1: Name + Account */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <div>
            <label className={labelCls}>Capacity Name</label>
            <input
              type="text"
              value={form.capacityName}
              onChange={(e) => onChange({ capacityName: e.target.value })}
              placeholder="e.g., Production Webservers"
              className={inputCls}
              required
            />
          </div>
          <div>
            <div className="flex items-center gap-1.5 mb-1.5">
              <label className="block text-[12px] font-semibold text-[rgba(0,0,0,0.65)]">
                CSP Account <span className="text-red-500">*</span>
              </label>
              <div className="relative group">
                <button
                  type="button"
                  className="w-4 h-4 rounded-full bg-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.45)] text-[10px] font-bold flex items-center justify-center hover:bg-indigo-100 hover:text-indigo-600 transition-colors"
                  tabIndex={-1}
                >
                  i
                </button>
                <div className="absolute left-1/2 -translate-x-1/2 bottom-full mb-2 w-64 bg-[rgba(0,0,0,0.88)] text-white text-[11px] rounded-lg px-3 py-2 shadow-lg opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity z-50 leading-relaxed">
                  Don't see your account? It needs to be linked to CloudiDR manually. Please <span className="underline font-medium">raise a support ticket</span> and our team will configure it for you.
                  <div className="absolute left-1/2 -translate-x-1/2 top-full w-2 h-2 bg-[rgba(0,0,0,0.88)] rotate-45 -mt-1" />
                </div>
              </div>
            </div>
            <select
              value={form.cspAccount}
              onChange={(e) => onChange({ cspAccount: e.target.value })}
              className={inputCls}
              required
            >
              <option value="">Select account...</option>
              {accounts.map((acc) => (
                <option key={acc.id} value={acc.id}>{acc.alias} ({acc.cspAccountId})</option>
              ))}
            </select>
          </div>
        </div>

        {/* Row 2: Region/Zone */}
        <div>
          <label className={labelCls}>
            Regions Linked to Your CSP Account <span className="text-red-500">*</span>
          </label>
          <select
            value={form.region && form.zoneId ? `${form.region}/${form.zoneId}` : ''}
            onChange={(e) => handleRegionZoneChange(e.target.value)}
            className={inputCls}
            required
            disabled={!form.cspAccount || availableRegions.length === 0}
          >
            <option value="">
              {!form.cspAccount
                ? 'Select a CSP account first...'
                : availableRegions.length === 0
                ? 'No regions available for this account'
                : 'Select a region/zone...'}
            </option>
            {availableRegions.map((region) => (
              <option key={region.value} value={region.value}>{region.label}</option>
            ))}
          </select>
        </div>

        {/* Row 3: Instance Type + Platform + Tenancy */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
          <div>
            <label className={labelCls}>Instance Type <span className="text-red-500">*</span></label>
            <select
              value={form.instanceType}
              onChange={(e) => onChange({ instanceType: e.target.value })}
              className={inputCls}
              required
            >
              {INSTANCE_TYPES.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </div>
          <div>
            <label className={labelCls}>Platform</label>
            <input type="text" value="Linux" disabled className={inputCls} />
          </div>
          <div>
            <label className={labelCls}>Tenancy</label>
            <input type="text" value="Shared" disabled className={inputCls} />
          </div>
        </div>

        {/* Row 4: Quantity + Timezone */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <div>
            <label className={labelCls}>Quantity <span className="text-red-500">*</span></label>
            <input
              type="number"
              value={form.quantity}
              onChange={(e) => onChange({ quantity: Math.max(1, parseInt(e.target.value) || 1) })}
              min={1}
              className={inputCls}
              required
            />
          </div>
          <div>
            <label className={labelCls}>Time Zone</label>
            <select
              value={form.timeZone}
              onChange={(e) => handleTimezoneChange(e.target.value)}
              className={inputCls}
            >
              {TIME_ZONES.map((tz) => (
                <option key={tz.value} value={tz.value}>{tz.label}</option>
              ))}
            </select>
            <p className="text-[11px] text-indigo-600 mt-1 flex items-center gap-1">
              <Icon icon="mdi:clock-outline" width={12} />
              All times will be scheduled in this timezone
            </p>
          </div>
        </div>

        {/* Row 5: Start + End Date */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <div>
            <label className={labelCls}>
              Start Date & Time
              <span className="text-[11px] font-normal text-indigo-600 ml-2">
                ({TIME_ZONES.find(tz => tz.value === form.timeZone)?.label || 'Selected timezone'})
              </span>
            </label>
            <input
              type="datetime-local"
              value={form.startDate}
              onChange={(e) => handleStartDateChange(e.target.value)}
              min={minStartTime}
              className={`${inputCls} ${startDateError ? '!border-red-400 !bg-red-50' : ''}`}
              required
            />
            {startDateError ? (
              <p className="text-[11px] text-red-600 mt-1 flex items-center gap-1">
                <Icon icon="mdi:alert-circle-outline" width={12} />
                {startDateError}
              </p>
            ) : (
              <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1 flex items-center gap-1">
                <Icon icon="mdi:information-outline" width={12} />
                Earliest start time is 30 minutes from now. Times rounded to 15-min intervals.
              </p>
            )}
          </div>
          <div>
            <label className={labelCls}>
              End Date & Time
              <span className="text-[11px] font-normal text-indigo-600 ml-2">
                ({TIME_ZONES.find(tz => tz.value === form.timeZone)?.label || 'Selected timezone'})
              </span>
            </label>
            <input
              type="datetime-local"
              value={form.endDate}
              onChange={(e) => handleEndDateChange(e.target.value)}
              min={form.startDate ? (() => {
                const [datePart, timePart] = form.startDate.split('T');
                const [h, m] = (timePart || '00:00').split(':').map(Number);
                const nextSlot = m + 15;
                if (nextSlot >= 60) return `${datePart}T${String(h + 1).padStart(2, '0')}:${String(nextSlot - 60).padStart(2, '0')}`;
                return `${datePart}T${String(h).padStart(2, '0')}:${String(nextSlot).padStart(2, '0')}`;
              })() : minStartTime}
              disabled={!form.startDate}
              className={`${inputCls} ${endDateError ? '!border-red-400 !bg-red-50' : ''}`}
              required
            />
            {endDateError ? (
              <p className="text-[11px] text-red-600 mt-1 flex items-center gap-1">
                <Icon icon="mdi:alert-circle-outline" width={12} />
                {endDateError}
              </p>
            ) : !form.startDate ? (
              <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1 flex items-center gap-1">
                <Icon icon="mdi:lock-outline" width={12} />
                Select a start date/time first.
              </p>
            ) : (
              <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1 flex items-center gap-1">
                <Icon icon="mdi:information-outline" width={12} />
                Must be after start time. Times rounded to 15-min intervals.
              </p>
            )}
          </div>
        </div>

        {/* Auto-renewal */}
        <div className="border border-[rgba(0,0,0,0.08)] rounded-xl p-5 space-y-4 bg-[rgba(0,0,0,0.01)]">
          <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Enable Auto-renewal</h4>

          <div>
            <label className={labelCls}>Auto-renewal enabled</label>
            <select
              value={form.autoRenewal ? 'yes' : 'no'}
              onChange={(e) => onChange({ autoRenewal: e.target.value === 'yes' })}
              className={inputCls}
            >
              <option value="yes">Yes</option>
              <option value="no">No</option>
            </select>
          </div>

          {form.autoRenewal && (
            <div>
              <label className={labelCls}>Frequency of renewal</label>
              <RadioGroup
                value={form.renewalFrequency ?? '24hrs'}
                onChange={(v) => onChange({ renewalFrequency: v as '24hrs' | '1week' | '1month' })}
                aria-label="Renewal frequency"
                orientation="horizontal"
                className="flex gap-6"
              >
                {(['24hrs', '1week', '1month'] as const).map((freq) => (
                  <Radio
                    key={freq}
                    value={freq}
                    className={({ isFocusVisible }) =>
                      `flex items-center gap-2 cursor-pointer outline-none ${isFocusVisible ? 'ring-2 ring-indigo-400 rounded' : ''}`
                    }
                  >
                    {({ isSelected }) => (
                      <>
                        <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center shrink-0 ${isSelected ? 'border-indigo-600 bg-indigo-600' : 'border-[rgba(0,0,0,0.2)]'}`}>
                          {isSelected && <div className="w-1.5 h-1.5 rounded-full bg-white" />}
                        </div>
                        <span className="text-[13px] text-[rgba(0,0,0,0.65)]">
                          {freq === '24hrs' ? '24 hrs' : freq === '1week' ? '1 Week' : '1 Month'}
                        </span>
                      </>
                    )}
                  </Radio>
                ))}
              </RadioGroup>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-2">
                Specifies the time period added to the current end date each time auto-renewal is triggered.
              </p>
            </div>
          )}
        </div>

        {/* Submit */}
        <div className="pt-1">
          <button
            type="submit"
            disabled={
              isSearching ||
              !form.capacityName ||
              !form.cspAccount ||
              !form.region ||
              !form.zoneId ||
              !form.instanceType ||
              !form.quantity ||
              form.quantity < 1 ||
              !form.startDate ||
              !form.endDate ||
              !!startDateError ||
              !!endDateError
            }
            className="w-full flex items-center justify-center gap-2 py-3 px-6 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-xl transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isSearching ? (
              <><Icon icon="mdi:loading" width={16} className="animate-spin" />Searching...</>
            ) : (
              <><Icon icon="mdi:magnify" width={16} />Search Capacity</>
            )}
          </button>
        </div>
      </form>
    </div>
  );
};

export default WizardStep1Search;
