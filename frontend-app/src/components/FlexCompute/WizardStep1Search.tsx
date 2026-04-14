/**
 * Wizard Step 1 - Search Capacity Form
 *
 * Form for searching available capacity: name, account, region, instance type, etc.
 */

import React, { useEffect, useMemo, useState } from 'react';
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

const WizardStep1Search: React.FC<WizardStep1SearchProps> = ({
  form,
  onChange,
  onSearch,
  isSearching,
  accounts,
}) => {
  const [startDateError, setStartDateError] = useState('');
  const [endDateError, setEndDateError] = useState('');

  // Round wall-clock datetime string to nearest 15-minute increment (pure string math, no timezone conversion)
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

  // Tick every minute so minStartTime stays fresh even if the form is left open.
  const [, setTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setTick((t) => t + 1), 60000);
    return () => clearInterval(id);
  }, []);

  // Minimum start time = 30 min from now, rounded up to next 15-min slot,
  // expressed as wall-clock time in the USER-SELECTED timezone (not browser local time).
  // Uses Intl API with the IANA timezone name so DST is handled automatically.
  const minStartTime = useMemo(() => {
    const future = new Date();
    future.setMinutes(future.getMinutes() + 30);

    // 'sv-SE' locale gives ISO-like "YYYY-MM-DD HH:mm:ss" in the requested timezone
    const tzString = future.toLocaleString('sv-SE', { timeZone: form.timeZone });
    // tzString example: "2026-02-16 14:37:00"
    const [datePart, timePart] = tzString.split(' ');
    const [h, m] = timePart.split(':').map(Number);

    // Round UP to next 15-min slot
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
    // Clear end date if it's no longer strictly after the new start date
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

  // Get available regions for selected CSP account
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

  // Re-validate start date when timezone changes (min shifts, existing value may become invalid)
  const handleTimezoneChange = (tz: string) => {
    onChange({ timeZone: tz });
    // minStartTime will recompute on next render; clear error optimistically,
    // then the comparison in handleStartDateChange re-checks on next user interaction.
    // Also proactively clear if current start date would become past in new timezone.
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

  // Handle region/zone selection
  const handleRegionZoneChange = (value: string) => {
    const [regionCode, zoneId] = value.split('/');
    onChange({ region: regionCode, zoneId: zoneId });
  };

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
      {/* Header */}
      <div className="bg-gradient-to-r from-blue-600 to-indigo-600 px-6 py-4 text-white">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-white/20 rounded-lg flex items-center justify-center text-sm font-bold">1</div>
          <div>
            <h3 className="font-semibold">Search Capacity</h3>
            <p className="text-xs text-blue-100">Define your capacity requirements</p>
          </div>
        </div>
      </div>

      {/* Form */}
      <form onSubmit={handleSubmit} className="p-6 space-y-6">
        {/* Row 1: Name + Account */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Capacity Name</label>
            <input
              type="text"
              value={form.capacityName}
              onChange={(e) => onChange({ capacityName: e.target.value })}
              placeholder="e.g., Production Webservers"
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              required
            />
          </div>
          <div>
            <div className="flex items-center gap-1.5 mb-1.5">
              <label className="block text-sm font-medium text-gray-700">
                CSP Account <span className="text-red-500">*</span>
              </label>
              <div className="relative group">
                <button
                  type="button"
                  className="w-4 h-4 rounded-full bg-gray-200 text-gray-500 text-[10px] font-bold flex items-center justify-center hover:bg-blue-100 hover:text-blue-600 transition-colors"
                  tabIndex={-1}
                >
                  i
                </button>
                <div className="absolute left-1/2 -translate-x-1/2 bottom-full mb-2 w-64 bg-gray-900 text-white text-xs rounded-lg px-3 py-2 shadow-lg opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity z-50">
                  Don't see your account? It needs to be linked to CloudiDR manually.
                  Please <span className="underline font-medium">raise a support ticket</span> and our team will configure it for you.
                  <div className="absolute left-1/2 -translate-x-1/2 top-full w-2 h-2 bg-gray-900 rotate-45 -mt-1"></div>
                </div>
              </div>
            </div>
            <select
              value={form.cspAccount}
              onChange={(e) => onChange({ cspAccount: e.target.value })}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              required
            >
              <option value="">Select account...</option>
              {accounts.map((acc) => (
                <option key={acc.id} value={acc.id}>{acc.alias} ({acc.cspAccountId})</option>
              ))}
            </select>
          </div>
        </div>

        {/* Row 2: Region/Zone Combined (CloudiDR style) */}
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1.5">
            Regions Linked to Your CSP Account <span className="text-red-500">*</span>
          </label>
          <select
            value={form.region && form.zoneId ? `${form.region}/${form.zoneId}` : ''}
            onChange={(e) => handleRegionZoneChange(e.target.value)}
            className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
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
              <option key={region.value} value={region.value}>
                {region.label}
              </option>
            ))}
          </select>
        </div>

        {/* Row 3: Instance Type + Platform (fixed) + Tenancy (fixed) */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Instance Type <span className="text-red-500">*</span>
            </label>
            <select
              value={form.instanceType}
              onChange={(e) => onChange({ instanceType: e.target.value })}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              required
            >
              {INSTANCE_TYPES.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Platform</label>
            <input
              type="text"
              value="Linux"
              disabled
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm bg-gray-50 text-gray-500 cursor-not-allowed"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Tenancy</label>
            <input
              type="text"
              value="Shared"
              disabled
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm bg-gray-50 text-gray-500 cursor-not-allowed"
            />
          </div>
        </div>

        {/* Row 4: Quantity + Timezone */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Quantity <span className="text-red-500">*</span>
            </label>
            <input
              type="number"
              value={form.quantity}
              onChange={(e) => onChange({ quantity: Math.max(1, parseInt(e.target.value) || 1) })}
              min={1}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              required
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Time Zone</label>
            <select
              value={form.timeZone}
              onChange={(e) => handleTimezoneChange(e.target.value)}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
            >
              {TIME_ZONES.map((tz) => (
                <option key={tz.value} value={tz.value}>{tz.label}</option>
              ))}
            </select>
            <p className="text-xs text-blue-600 mt-1">
              <i className="fas fa-clock mr-1"></i>
              All times will be scheduled in this timezone
            </p>
          </div>
        </div>

        {/* Row 5: Start Date/Time + End Date/Time */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Start Date & Time
              <span className="text-xs font-normal text-blue-600 ml-2">
                ({TIME_ZONES.find(tz => tz.value === form.timeZone)?.label || 'Selected timezone'})
              </span>
            </label>
            <input
              type="datetime-local"
              value={form.startDate}
              onChange={(e) => handleStartDateChange(e.target.value)}
              min={minStartTime}
              className={`w-full px-4 py-2.5 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none ${
                startDateError ? 'border-red-400 bg-red-50' : 'border-gray-300'
              }`}
              required
            />
            {startDateError ? (
              <p className="text-xs text-red-600 mt-1 flex items-center gap-1">
                <i className="fas fa-exclamation-circle"></i>
                {startDateError}
              </p>
            ) : (
              <p className="text-xs text-gray-500 mt-1">
                <i className="fas fa-info-circle mr-1"></i>
                Earliest start time is 30 minutes from now. Times rounded to 15-min intervals.
              </p>
            )}
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              End Date & Time
              <span className="text-xs font-normal text-blue-600 ml-2">
                ({TIME_ZONES.find(tz => tz.value === form.timeZone)?.label || 'Selected timezone'})
              </span>
            </label>
            <input
              type="datetime-local"
              value={form.endDate}
              onChange={(e) => handleEndDateChange(e.target.value)}
              min={form.startDate ? (() => {
                // min must be at least 15 min after start to enforce strictly-greater
                const [datePart, timePart] = form.startDate.split('T');
                const [h, m] = (timePart || '00:00').split(':').map(Number);
                const nextSlot = m + 15;
                if (nextSlot >= 60) return `${datePart}T${String(h + 1).padStart(2, '0')}:${String(nextSlot - 60).padStart(2, '0')}`;
                return `${datePart}T${String(h).padStart(2, '0')}:${String(nextSlot).padStart(2, '0')}`;
              })() : minStartTime}
              disabled={!form.startDate}
              className={`w-full px-4 py-2.5 border rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none ${
                endDateError ? 'border-red-400 bg-red-50' : 'border-gray-300'
              } disabled:bg-gray-50 disabled:text-gray-400 disabled:cursor-not-allowed`}
              required
            />
            {endDateError ? (
              <p className="text-xs text-red-600 mt-1 flex items-center gap-1">
                <i className="fas fa-exclamation-circle"></i>
                {endDateError}
              </p>
            ) : !form.startDate ? (
              <p className="text-xs text-gray-400 mt-1">
                <i className="fas fa-lock mr-1"></i>
                Select a start date/time first.
              </p>
            ) : (
              <p className="text-xs text-gray-500 mt-1">
                <i className="fas fa-info-circle mr-1"></i>
                Must be after start time. Times rounded to 15-min intervals.
              </p>
            )}
          </div>
        </div>

        {/* Row 6: Auto-renewal Settings */}
        <div className="border border-gray-200 rounded-lg p-5 space-y-4">
          <h4 className="text-base font-semibold text-gray-900">Enable Auto-renewal</h4>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Auto-renewal enabled
              <span className="ml-2 text-xs text-gray-400">
                <i className="fas fa-info-circle"></i>
              </span>
            </label>
            <select
              value={form.autoRenewal ? 'yes' : 'no'}
              onChange={(e) => onChange({ autoRenewal: e.target.value === 'yes' })}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
            >
              <option value="yes">Yes</option>
              <option value="no">No</option>
            </select>
          </div>

          {form.autoRenewal && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-3">
                Frequency of renewal
                <span className="ml-2 text-xs text-gray-400">
                  <i className="fas fa-info-circle"></i>
                </span>
              </label>
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
                      `flex items-center gap-2 cursor-pointer outline-none ${isFocusVisible ? 'ring-2 ring-blue-400 rounded' : ''}`
                    }
                  >
                    {({ isSelected }) => (
                      <>
                        <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center shrink-0 ${isSelected ? 'border-blue-600 bg-blue-600' : 'border-gray-300'}`}>
                          {isSelected && <div className="w-1.5 h-1.5 rounded-full bg-white" />}
                        </div>
                        <span className="text-sm text-gray-700">
                          {freq === '24hrs' ? '24 hrs' : freq === '1week' ? '1 Week' : '1 Month'}
                        </span>
                      </>
                    )}
                  </Radio>
                ))}
              </RadioGroup>
              <p className="text-xs text-gray-500 mt-2">
                Specifies the time period that will be added to the current end date each time the auto-renewal is triggered.
              </p>
            </div>
          )}
        </div>

        {/* Submit */}
        <div className="pt-2">
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
            className="w-full py-3 px-6 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-semibold rounded-xl hover:from-blue-700 hover:to-indigo-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed shadow-lg shadow-blue-500/25"
          >
            {isSearching ? (
              <><i className="fas fa-spinner fa-spin mr-2"></i>Searching...</>
            ) : (
              <><i className="fas fa-search mr-2"></i>Search Capacity</>
            )}
          </button>
        </div>
      </form>
    </div>
  );
};

export default WizardStep1Search;
