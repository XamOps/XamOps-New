/**
 * Wizard Step 1 - Search Capacity Form
 *
 * Form for searching available capacity: name, account, region, instance type, etc.
 */

import React, { useMemo } from 'react';
import type { SearchCapacityForm } from '../../types/flexCompute';
import { INSTANCE_TYPES, TIME_ZONES } from '../../types/flexCompute';

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
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSearch();
  };

  // Round datetime to nearest 15-minute increment
  const roundToQuarterHour = (datetimeStr: string): string => {
    if (!datetimeStr) return datetimeStr;

    const date = new Date(datetimeStr);
    const minutes = date.getMinutes();

    // Round to nearest 15-minute increment
    const roundedMinutes = Math.round(minutes / 15) * 15;

    if (roundedMinutes === 60) {
      date.setHours(date.getHours() + 1);
      date.setMinutes(0);
    } else {
      date.setMinutes(roundedMinutes);
    }

    // Format back to datetime-local format (YYYY-MM-DDTHH:mm)
    return date.toISOString().slice(0, 16);
  };

  // Calculate minimum start time (30 minutes from now)
  const minStartTime = useMemo(() => {
    const now = new Date();
    now.setMinutes(now.getMinutes() + 30);
    // Format: YYYY-MM-DDTHH:mm (datetime-local format)
    return now.toISOString().slice(0, 16);
  }, []);

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
            <label className="block text-sm font-medium text-gray-700 mb-1.5">CSP Account</label>
            <select
              value={form.cspAccount}
              onChange={(e) => onChange({ cspAccount: e.target.value })}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              required
            >
              <option value="">Select account...</option>
              {accounts.map((acc) => (
                <option key={acc.id} value={acc.id}>{acc.name} ({acc.id})</option>
              ))}
            </select>
          </div>
        </div>

        {/* Row 2: Region/Zone Combined (CloudiDR style) */}
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1.5">
            Regions Linked to Your CSP Account
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
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Instance Type</label>
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
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Quantity</label>
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
              onChange={(e) => onChange({ timeZone: e.target.value })}
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
              onChange={(e) => onChange({ startDate: roundToQuarterHour(e.target.value) })}
              min={minStartTime}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              required
            />
            <p className="text-xs text-gray-500 mt-1">
              <i className="fas fa-info-circle mr-1"></i>
              Earliest start time is 30 minutes from now. Times rounded to 15-min intervals (00, 15, 30, 45).
            </p>
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
              onChange={(e) => onChange({ endDate: roundToQuarterHour(e.target.value) })}
              min={form.startDate || minStartTime}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              required
            />
            <p className="text-xs text-gray-500 mt-1">
              <i className="fas fa-info-circle mr-1"></i>
              Times rounded to 15-min intervals (00, 15, 30, 45).
            </p>
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
              <div className="flex gap-6">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name="renewalFrequency"
                    value="24hrs"
                    checked={form.renewalFrequency === '24hrs'}
                    onChange={(e) => onChange({ renewalFrequency: e.target.value as '24hrs' | '1week' | '1month' })}
                    className="w-4 h-4 text-blue-600 border-gray-300 focus:ring-blue-500"
                  />
                  <span className="text-sm text-gray-700">24 hrs</span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name="renewalFrequency"
                    value="1week"
                    checked={form.renewalFrequency === '1week'}
                    onChange={(e) => onChange({ renewalFrequency: e.target.value as '24hrs' | '1week' | '1month' })}
                    className="w-4 h-4 text-blue-600 border-gray-300 focus:ring-blue-500"
                  />
                  <span className="text-sm text-gray-700">1 Week</span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name="renewalFrequency"
                    value="1month"
                    checked={form.renewalFrequency === '1month'}
                    onChange={(e) => onChange({ renewalFrequency: e.target.value as '24hrs' | '1week' | '1month' })}
                    className="w-4 h-4 text-blue-600 border-gray-300 focus:ring-blue-500"
                  />
                  <span className="text-sm text-gray-700">1 Month</span>
                </label>
              </div>
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
            disabled={isSearching || !form.capacityName || !form.cspAccount || !form.startDate || !form.endDate}
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
