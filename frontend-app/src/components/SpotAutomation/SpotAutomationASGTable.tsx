import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import type { ASG, SpotCosts } from '../../hooks/useSpotAutomationData';
import InstanceTypeConfigModal from './InstanceTypeConfigModal';

interface SpotAutomationASGTableProps {
  asgs: ASG[];
  costs: SpotCosts;
  totalInstances: number;
  isLoading: boolean;
  selectedAccountId: string | null;
  onToggleASG: (asg: ASG) => Promise<void>;
  onSyncASG: (asg: ASG) => Promise<{ backfilled: number; message: string }>;
  onGetInstanceTypeConfig: (region: string, asgName: string) => Promise<string[]>;
  onSaveInstanceTypeConfig: (region: string, asgName: string, instanceTypes: string[]) => Promise<void>;
  onRefresh: () => void;
}

const SpotAutomationASGTable: React.FC<SpotAutomationASGTableProps> = ({
  asgs,
  costs,
  totalInstances,
  isLoading,
  selectedAccountId,
  onToggleASG,
  onSyncASG,
  onGetInstanceTypeConfig,
  onSaveInstanceTypeConfig,
  onRefresh,
}) => {
  const [togglingASGs, setTogglingASGs] = useState<Set<string>>(new Set());
  const [syncingASGs, setSyncingASGs] = useState<Set<string>>(new Set());
  const [syncToast, setSyncToast] = useState<{ asgKey: string; message: string } | null>(null);

  // Instance type config modal state
  const [isConfigModalOpen, setIsConfigModalOpen] = useState(false);
  const [configModalASG, setConfigModalASG] = useState<ASG | null>(null);
  const [currentInstanceTypes, setCurrentInstanceTypes] = useState<string[]>([]);
  const [isLoadingConfig, setIsLoadingConfig] = useState(false);
  const [isSavingConfig, setIsSavingConfig] = useState(false);

  const formatCurrency = (value: number) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  };

  const formatHourlyCost = (value: number) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 4,
      maximumFractionDigits: 4,
    }).format(value);
  };

  const handleToggle = async (asg: ASG) => {
    const asgKey = `${asg.name}-${asg.region}`;
    if (togglingASGs.has(asgKey)) return;

    setTogglingASGs((prev) => new Set(prev).add(asgKey));
    try {
      await onToggleASG(asg);
    } finally {
      setTogglingASGs((prev) => {
        const next = new Set(prev);
        next.delete(asgKey);
        return next;
      });
    }
  };

  const handleSync = async (asg: ASG) => {
    const asgKey = `${asg.name}-${asg.region}`;
    if (syncingASGs.has(asgKey)) return;
    setSyncingASGs((prev) => new Set(prev).add(asgKey));
    try {
      const result = await onSyncASG(asg);
      setSyncToast({ asgKey, message: result.message });
      setTimeout(() => setSyncToast(null), 4000);
    } catch (err) {
      setSyncToast({ asgKey, message: 'Sync failed. Check backend logs.' });
      setTimeout(() => setSyncToast(null), 4000);
    } finally {
      setSyncingASGs((prev) => {
        const next = new Set(prev);
        next.delete(asgKey);
        return next;
      });
    }
  };

  const handleOpenConfigModal = async (asg: ASG) => {
    setConfigModalASG(asg);
    setIsLoadingConfig(true);
    setIsConfigModalOpen(true);
    try {
      const types = await onGetInstanceTypeConfig(asg.region, asg.name);
      setCurrentInstanceTypes(types);
    } catch (err) {
      console.error('Error loading instance type config:', err);
      setCurrentInstanceTypes([]);
    } finally {
      setIsLoadingConfig(false);
    }
  };

  const handleCloseConfigModal = () => {
    setIsConfigModalOpen(false);
    setConfigModalASG(null);
    setCurrentInstanceTypes([]);
  };

  const handleSaveConfig = async (instanceTypes: string[]) => {
    if (!configModalASG) return;
    setIsSavingConfig(true);
    try {
      await onSaveInstanceTypeConfig(configModalASG.region, configModalASG.name, instanceTypes);
      handleCloseConfigModal();
    } catch (err) {
      console.error('Error saving instance type config:', err);
    } finally {
      setIsSavingConfig(false);
    }
  };

  const enabledCount = asgs.filter((a) => a.isEnabled).length;
  const disabledCount = asgs.filter((a) => !a.isEnabled).length;

  return (
    <div className="bg-white rounded-xl shadow-lg border border-gray-200 overflow-hidden">
      <div className="px-6 py-5 border-b border-gray-200 flex justify-between items-center bg-gradient-to-r from-gray-50 to-white">
        <div>
          <h3 className="font-bold text-gray-800 text-lg">Auto Scaling Groups</h3>
          <p className="text-xs text-gray-600 mt-1">
            Real-time cost data from <span className="font-semibold">{new Set(asgs.map((a) => a.region)).size}</span>{' '}
            region{new Set(asgs.map((a) => a.region)).size > 1 ? 's' : ''}
          </p>
        </div>
        <button
          onClick={onRefresh}
          disabled={isLoading}
          className="text-gray-600 hover:text-indigo-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center space-x-2 px-4 py-2 rounded-lg hover:bg-indigo-50"
        >
          <i className={`fas fa-chart-line ${isLoading ? 'fa-spin' : ''}`}></i>
          <span className="text-sm font-semibold">{isLoading ? 'Loading...' : 'Refresh Data'}</span>
        </button>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-gradient-to-r from-gray-100 to-gray-50 text-gray-700 text-xs uppercase tracking-wider">
              <th className="px-6 py-4 font-bold">ASG Name</th>
              <th className="px-6 py-4 font-bold">Region</th>
              <th className="px-6 py-4 font-bold">Instance Type</th>
              <th className="px-6 py-4 font-bold">Instances</th>
              <th className="px-6 py-4 font-bold">Spot/OD</th>
              <th className="px-6 py-4 font-bold">Hourly Cost</th>
              <th className="px-6 py-4 font-bold">Monthly Cost</th>
              <th className="px-6 py-4 font-bold">
                <div className="flex items-center">
                  <span>Actual Savings/mo</span>
                  <div className="group relative ml-2">
                    <i className="fas fa-info-circle text-gray-400 hover:text-gray-600 cursor-help"></i>
                    <div className="hidden group-hover:block absolute top-full left-1/2 -translate-x-1/2 mt-2 w-64 bg-gray-900 text-white text-xs rounded-lg p-3 shadow-xl z-[100]">
                      Monthly savings you're already getting from Spot instances currently running in this ASG.
                      <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-[-1px] w-3 h-3 bg-gray-900 transform rotate-45"></div>
                    </div>
                  </div>
                </div>
              </th>
              <th className="px-6 py-4 font-bold">
                <div className="flex items-center">
                  <span>Potential Savings/mo</span>
                  <div className="group relative ml-2">
                    <i className="fas fa-info-circle text-gray-400 hover:text-gray-600 cursor-help"></i>
                    <div className="hidden group-hover:block absolute top-full left-1/2 -translate-x-1/2 mt-2 w-64 bg-gray-900 text-white text-xs rounded-lg p-3 shadow-xl z-[100]">
                      Estimated monthly savings if all on-demand instances in this ASG were converted to Spot instances.
                      <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-[-1px] w-3 h-3 bg-gray-900 transform rotate-45"></div>
                    </div>
                  </div>
                </div>
              </th>
              <th className="px-6 py-4 font-bold text-center">AutoSpotting</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {isLoading && (
              <tr>
                <td colSpan={10} className="px-6 py-16 text-center">
                  <div className="flex flex-col items-center justify-center space-y-4">
                    <i className="fas fa-spinner fa-spin text-5xl text-blue-500"></i>
                    <p className="text-gray-700 font-semibold text-lg">Fetching real-time cost data...</p>
                  </div>
                </td>
              </tr>
            )}

            {!isLoading && asgs.length === 0 && (
              <tr>
                <td colSpan={10} className="px-6 py-16 text-center">
                  <div className="text-yellow-500 mb-4">
                    <i className="fas fa-exclamation-triangle text-5xl"></i>
                  </div>
                  <p className="text-gray-700 font-bold text-xl mb-3">No Auto Scaling Groups Found</p>
                  <button
                    onClick={onRefresh}
                    className="bg-indigo-600 hover:bg-indigo-700 text-white px-6 py-3 rounded-lg text-sm font-semibold transition-colors shadow-lg hover:shadow-xl"
                  >
                    <i className="fas fa-sync-alt mr-2"></i> Retry
                  </button>
                </td>
              </tr>
            )}

            {!isLoading &&
              asgs.map((asg) => {
                const asgKey = `${asg.name}-${asg.region}`;
                const isToggling = togglingASGs.has(asgKey);

                return (
                  <tr key={asgKey} className="hover:bg-blue-50 transition-colors">
                    <td className="px-6 py-4">
                      <Link
                        to={`/asg-detail?asgName=${encodeURIComponent(asg.name)}&region=${asg.region}&accountIds=${selectedAccountId}`}
                        className="flex items-center hover:text-blue-600 transition-colors group"
                      >
                        <i className="fas fa-layer-group text-gray-400 mr-3 text-lg group-hover:text-blue-500"></i>
                        <div className="font-semibold text-gray-900 group-hover:underline">{asg.name}</div>
                        <i className="fas fa-external-link-alt ml-2 text-xs text-gray-300 opacity-0 group-hover:opacity-100 transition-opacity"></i>
                      </Link>
                    </td>
                    <td className="px-6 py-4">
                      <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-semibold bg-blue-100 text-blue-800">
                        <i className="fas fa-map-marker-alt mr-1.5"></i>
                        {asg.region}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <span className="text-xs text-gray-700 font-medium">{asg.instanceTypes || 'N/A'}</span>
                    </td>
                    <td className="px-6 py-4">
                      <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-semibold bg-gray-100 text-gray-800">
                        <i className="fas fa-server mr-1.5 text-gray-600"></i>
                        {asg.instanceCount}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <div className="text-xs font-semibold">
                        <span className="text-green-600">{asg.spotCount}</span>
                        <span className="text-gray-400">/</span>
                        <span className="text-orange-600">{asg.onDemandCount}</span>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <span className="text-sm font-bold text-gray-900">{formatHourlyCost(asg.currentCost || 0)}</span>
                    </td>
                    <td className="px-6 py-4">
                      <span className="text-sm font-bold text-gray-900">
                        {formatCurrency((asg.currentCost || 0) * 730)}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      {asg.savings && asg.savings > 0 ? (
                        <span className="text-sm font-bold text-emerald-600">{formatCurrency(asg.savings * 730)}</span>
                      ) : (
                        <span className="text-xs text-gray-400">-</span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      {asg.potentialSavings && asg.potentialSavings > 0 ? (
                        <span className="text-sm font-bold text-blue-600">
                          {formatCurrency(asg.potentialSavings * 730)}
                        </span>
                      ) : (
                        <span className="text-xs text-gray-400">-</span>
                      )}
                    </td>
                    <td className="px-6 py-4 text-center">
                      <div className="flex items-center justify-center space-x-3">
                        <div className="relative inline-block w-12 align-middle select-none transition duration-200 ease-in">
                          <input
                            type="checkbox"
                            id={`toggle-${asgKey}`}
                            className="toggle-checkbox absolute block w-7 h-7 rounded-full bg-white border-4 appearance-none cursor-pointer transition-all duration-200 shadow-md"
                            style={{
                              right: asg.isEnabled ? '0' : 'auto',
                              left: asg.isEnabled ? 'auto' : '0',
                              borderColor: asg.isEnabled ? '#10b981' : '#d1d5db',
                              opacity: isToggling ? 0.5 : 1,
                            }}
                            checked={asg.isEnabled}
                            disabled={isToggling}
                            onChange={() => handleToggle(asg)}
                          />
                          <label
                            htmlFor={`toggle-${asgKey}`}
                            className="toggle-label block overflow-hidden h-7 rounded-full cursor-pointer transition-colors duration-200"
                            style={{ backgroundColor: asg.isEnabled ? '#10b981' : '#d1d5db' }}
                          ></label>
                        </div>
                        <div className="flex items-center space-x-2">
                          <span
                            className="text-xs font-bold"
                            style={{ color: asg.isEnabled ? '#10b981' : '#6b7280' }}
                          >
                            {asg.isEnabled ? 'Enabled' : 'Disabled'}
                          </span>
                          {isToggling && <i className="fas fa-spinner fa-spin text-gray-400 text-xs"></i>}
                        </div>
                      </div>
                      {asg.isEnabled && (
                        <div className="flex items-center space-x-2 mt-2">
                          <div className="relative">
                            <button
                              onClick={() => handleSync(asg)}
                              disabled={syncingASGs.has(asgKey)}
                              title="Sync Spot events to Actions History"
                              className="text-xs text-indigo-500 hover:text-indigo-700 disabled:opacity-40 disabled:cursor-not-allowed flex items-center gap-1 px-2 py-1 rounded hover:bg-indigo-50 transition-colors"
                            >
                              <i className={`fas ${syncingASGs.has(asgKey) ? 'fa-spinner fa-spin' : 'fa-sync-alt'}`}></i>
                              <span>{syncingASGs.has(asgKey) ? 'Syncing...' : 'Sync'}</span>
                            </button>
                            {syncToast?.asgKey === asgKey && (
                              <div className="absolute right-0 top-8 z-30 bg-gray-800 text-white text-xs rounded-lg px-3 py-2 shadow-lg whitespace-nowrap">
                                {syncToast.message}
                              </div>
                            )}
                          </div>
                          <button
                            onClick={() => handleOpenConfigModal(asg)}
                            title="Configure instance types for Spot diversification"
                            className="text-xs text-purple-500 hover:text-purple-700 flex items-center gap-1 px-2 py-1 rounded hover:bg-purple-50 transition-colors"
                          >
                            <i className="fas fa-cog"></i>
                            <span>Config</span>
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
          </tbody>
        </table>
      </div>

      {asgs.length > 0 && (
        <div className="px-6 py-4 bg-gradient-to-r from-gray-50 to-white border-t border-gray-200">
          <div className="flex items-center justify-between text-sm">
            <div className="text-gray-700">
              <span className="font-bold">{asgs.length}</span> ASG{asgs.length !== 1 ? 's' : ''} •{' '}
              <span className="font-bold">{totalInstances}</span> instance{totalInstances !== 1 ? 's' : ''} •{' '}
              <span className="text-emerald-600 font-bold">
                {formatHourlyCost(costs.hourlyActualSavings)}/hr saved
              </span>
            </div>
            <div className="flex items-center space-x-6">
              <div className="flex items-center space-x-2">
                <div className="w-4 h-4 bg-emerald-500 rounded-full shadow"></div>
                <span className="text-gray-700">
                  <span className="font-bold">{enabledCount}</span> Enabled
                </span>
              </div>
              <div className="flex items-center space-x-2">
                <div className="w-4 h-4 bg-gray-300 rounded-full shadow"></div>
                <span className="text-gray-700">
                  <span className="font-bold">{disabledCount}</span> Disabled
                </span>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Instance Type Configuration Modal */}
      {configModalASG && (
        <InstanceTypeConfigModal
          isOpen={isConfigModalOpen}
          isSaving={isSavingConfig}
          asgName={configModalASG.name}
          region={configModalASG.region}
          currentTypes={isLoadingConfig ? [] : currentInstanceTypes}
          onClose={handleCloseConfigModal}
          onSave={handleSaveConfig}
        />
      )}
    </div>
  );
};

export default SpotAutomationASGTable;
