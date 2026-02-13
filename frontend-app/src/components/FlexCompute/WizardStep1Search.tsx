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

        {/* Row 3: Instance Type + Platform */}
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
            <select
              value={form.platform}
              onChange={(e) => onChange({ platform: e.target.value })}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
            >
              <option value="Linux/UNIX">Linux/UNIX</option>
              <option value="Windows">Windows</option>
              <option value="RHEL">RHEL</option>
              <option value="SUSE">SUSE</option>
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Tenancy</label>
            <select
              value={form.tenancy}
              onChange={(e) => onChange({ tenancy: e.target.value })}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
            >
              <option value="Shared">Shared</option>
              <option value="Dedicated">Dedicated</option>
            </select>
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
          </div>
        </div>

        {/* Row 5: Dates + Auto-renewal */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Start Date</label>
            <input
              type="date"
              value={form.startDate}
              onChange={(e) => onChange({ startDate: e.target.value })}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              required
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">End Date</label>
            <input
              type="date"
              value={form.endDate}
              onChange={(e) => onChange({ endDate: e.target.value })}
              min={form.startDate}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              required
            />
          </div>
          <div className="flex items-end">
            <label className="flex items-center gap-3 cursor-pointer py-2.5">
              <input
                type="checkbox"
                checked={form.autoRenewal}
                onChange={(e) => onChange({ autoRenewal: e.target.checked })}
                className="w-4 h-4 text-blue-600 border-gray-300 rounded focus:ring-blue-500"
              />
              <span className="text-sm font-medium text-gray-700">Auto-renewal</span>
            </label>
          </div>
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
