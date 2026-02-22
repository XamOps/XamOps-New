/**
 * ASG Configuration Page
 *
 * Comprehensive configuration page for Auto Scaling Group settings
 *
 * Features:
 * - Spot/On-Demand ratio configuration
 * - Instance type selection for diversification
 * - Future: Scaling policies, notifications, etc.
 */

import React, { useState, useEffect } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import { useTopbar } from '../contexts/TopbarContext';
import apiClient from '../utils/apiClient';
import clsx from 'clsx';

const ASGConfigPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const { setTitle, setActions, clearActions } = useTopbar();

  // Get params from URL
  const asgName = searchParams.get('asgName');
  const region = searchParams.get('region');
  const accountId = searchParams.get('accountIds');

  // State
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Configuration State
  const [configMode, setConfigMode] = useState<'percentage' | 'count'>('percentage');
  const [spotPercentage, setSpotPercentage] = useState(75);
  const [onDemandCount, setOnDemandCount] = useState<number>(0);
  const [selectedTypes, setSelectedTypes] = useState<Set<string>>(new Set());
  const [customType, setCustomType] = useState('');

  // Instance type families
  const INSTANCE_TYPE_FAMILIES = {
    'T Family (Burstable)': ['t2.micro', 't2.small', 't2.medium', 't2.large', 't3.micro', 't3.small', 't3.medium', 't3.large'],
    'M Family (General Purpose)': ['m4.large', 'm4.xlarge', 'm5.large', 'm5.xlarge', 'm5.2xlarge', 'm6i.large', 'm6i.xlarge'],
    'C Family (Compute Optimized)': ['c4.large', 'c4.xlarge', 'c5.large', 'c5.xlarge', 'c5.2xlarge', 'c6i.large', 'c6i.xlarge'],
    'R Family (Memory Optimized)': ['r4.large', 'r4.xlarge', 'r5.large', 'r5.xlarge', 'r5.2xlarge', 'r6i.large', 'r6i.xlarge'],
  };

  // Load current configuration
  useEffect(() => {
    const loadConfig = async () => {
      if (!accountId || !asgName || !region) return;

      try {
        setLoading(true);
        setError(null);

        // Load instance types
        const types = await apiClient.get(
          `/api/autospotting/asgs/${accountId}/instance-types?region=${region}&asgName=${encodeURIComponent(asgName)}`
        );
        setSelectedTypes(new Set(types.instanceTypes || []));

        // Load spot configuration
        const spotConfig = await apiClient.get(
          `/api/autospotting/asgs/${accountId}/spot-config?region=${region}&asgName=${encodeURIComponent(asgName)}`
        );

        if (spotConfig.configMode === 'percentage') {
          setConfigMode('percentage');
          setSpotPercentage(spotConfig.spotPercentage || 75);
        } else if (spotConfig.configMode === 'count') {
          setConfigMode('count');
          setOnDemandCount(spotConfig.onDemandCount || 0);
        }

      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load configuration');
      } finally {
        setLoading(false);
      }
    };

    loadConfig();
  }, [accountId, asgName, region]);

  // Save configuration
  const handleSave = async () => {
    if (!accountId || !asgName || !region) return;

    try {
      setSaving(true);
      setError(null);
      setSuccessMessage(null);

      // Save instance types
      await apiClient.put(
        `/api/autospotting/asgs/${accountId}/instance-types?region=${region}&asgName=${encodeURIComponent(asgName)}`,
        { instanceTypes: Array.from(selectedTypes) }
      );

      // Save spot configuration
      if (configMode === 'percentage') {
        await apiClient.put(
          `/api/autospotting/asgs/${accountId}/spot-config?region=${region}&asgName=${encodeURIComponent(asgName)}`,
          { spotPercentage }
        );
      } else {
        await apiClient.put(
          `/api/autospotting/asgs/${accountId}/spot-config?region=${region}&asgName=${encodeURIComponent(asgName)}`,
          { onDemandCount }
        );
      }

      setSuccessMessage('Configuration saved successfully!');
      setTimeout(() => setSuccessMessage(null), 4000);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save configuration');
    } finally {
      setSaving(false);
    }
  };

  // Topbar Integration
  useEffect(() => {
    if (!asgName) return;

    setTitle(`Configure ${asgName}`);

    setActions(
      <div className="flex items-center gap-4">
        <Link
          to={`/spot-automation?accountIds=${accountId}`}
          className="w-10 h-10 bg-white border border-gray-200 rounded-xl flex items-center justify-center text-gray-500 hover:text-indigo-600 hover:border-indigo-300 transition-all shadow-sm"
          title="Back to Spot Automation"
        >
          <i className="fas fa-arrow-left"></i>
        </Link>

        <div className="hidden md:flex flex-col items-end mr-2">
          <div className="flex items-center gap-1.5 text-[10px] text-gray-400 font-medium whitespace-nowrap">
            <i className="fas fa-map-marker-alt"></i> {region}
          </div>
        </div>

        <button
          onClick={handleSave}
          disabled={saving}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas', saving ? 'fa-spinner fa-spin' : 'fa-save')}></i>
          <span>{saving ? 'Saving...' : 'Save Configuration'}</span>
        </button>
      </div>
    );

    return () => {
      clearActions();
      setTitle(null);
    };
  }, [setTitle, setActions, clearActions, asgName, accountId, region, saving]);

  // Handle invalid params
  if (!asgName || !accountId || !region) {
    return (
      <main className="flex-1 flex items-center justify-center min-h-screen bg-gray-50">
        <div className="text-center p-8">
          <div className="w-20 h-20 bg-red-100 rounded-2xl flex items-center justify-center mx-auto mb-4 border border-red-300">
            <i className="fas fa-exclamation-triangle text-red-500 text-3xl"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900 mb-2">Missing Parameters</h3>
          <p className="text-gray-600">
            Required parameters missing: {!asgName && 'asgName'} {!accountId && 'accountId'} {!region && 'region'}
          </p>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      <div className="p-8 space-y-6">
        {/* Success Message */}
        {successMessage && (
          <div className="bg-green-50 border-l-4 border-green-500 p-4 rounded-lg shadow-sm">
            <div className="flex items-center">
              <i className="fas fa-check-circle text-green-500 text-xl mr-3"></i>
              <p className="text-sm text-green-700 font-semibold">{successMessage}</p>
            </div>
          </div>
        )}

        {/* Error State */}
        {error && (
          <div className="bg-red-50 border-l-4 border-red-500 p-4 rounded-lg shadow-sm">
            <div className="flex items-center">
              <i className="fas fa-exclamation-circle text-red-500 text-xl mr-3"></i>
              <p className="text-sm text-red-700 font-semibold">{error}</p>
            </div>
          </div>
        )}

        {/* Loading State */}
        {loading ? (
          <div className="space-y-6">
            <div className="bg-white rounded-xl p-6 animate-pulse">
              <div className="h-6 bg-gray-200 rounded w-1/3 mb-4"></div>
              <div className="h-32 bg-gray-200 rounded"></div>
            </div>
            <div className="bg-white rounded-xl p-6 animate-pulse">
              <div className="h-6 bg-gray-200 rounded w-1/3 mb-4"></div>
              <div className="h-64 bg-gray-200 rounded"></div>
            </div>
          </div>
        ) : (
          <>
            {/* Spot/On-Demand Configuration Section */}
            <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
              <h2 className="text-xl font-bold text-gray-900 mb-4 flex items-center">
                <i className="fas fa-sliders-h text-green-600 mr-3 text-2xl"></i>
                Spot/On-Demand Configuration
              </h2>

              <div className="space-y-4">
                {/* % Spot Instances Option */}
                <div className="flex items-start space-x-3">
                  <input
                    type="radio"
                    id="percentage-mode"
                    name="config-mode"
                    checked={configMode === 'percentage'}
                    onChange={() => setConfigMode('percentage')}
                    className="mt-1 w-4 h-4 text-indigo-600"
                  />
                  <div className="flex-1">
                    <label htmlFor="percentage-mode" className="flex items-center space-x-2 cursor-pointer">
                      <span className="font-semibold text-gray-700">% Spot instances</span>
                      <div className="group relative">
                        <i className="fas fa-info-circle text-gray-400 hover:text-gray-600"></i>
                        <div className="absolute left-0 bottom-full mb-2 hidden group-hover:block w-64 p-2 bg-gray-800 text-white text-xs rounded-lg shadow-lg z-10">
                          Set the target percentage of Spot instances. AWS will maintain this ratio automatically.
                        </div>
                      </div>
                    </label>

                    {configMode === 'percentage' && (
                      <div className="mt-3 bg-gray-50 p-4 rounded-lg border border-gray-200">
                        <div className="flex items-center space-x-4">
                          <input
                            type="range"
                            min="0"
                            max="100"
                            value={spotPercentage}
                            onChange={(e) => setSpotPercentage(Number(e.target.value))}
                            className="flex-1 h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer accent-indigo-600"
                            style={{
                              background: `linear-gradient(to right, rgb(79 70 229) 0%, rgb(79 70 229) ${spotPercentage}%, rgb(229 231 235) ${spotPercentage}%, rgb(229 231 235) 100%)`
                            }}
                          />
                          <input
                            type="number"
                            min="0"
                            max="100"
                            value={spotPercentage}
                            onChange={(e) => setSpotPercentage(Math.min(100, Math.max(0, Number(e.target.value))))}
                            className="w-24 px-4 py-2 border border-gray-300 rounded-lg text-center font-semibold text-lg focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                          />
                        </div>
                        <div className="flex justify-between text-xs text-gray-500 mt-2">
                          <span>0 (All On-Demand)</span>
                          <span>100 (All Spot)</span>
                        </div>
                      </div>
                    )}
                  </div>
                </div>

                {/* Specific On-Demand Count Option */}
                <div className="flex items-start space-x-3">
                  <input
                    type="radio"
                    id="count-mode"
                    name="config-mode"
                    checked={configMode === 'count'}
                    onChange={() => setConfigMode('count')}
                    className="mt-1 w-4 h-4 text-indigo-600"
                  />
                  <div className="flex-1">
                    <label htmlFor="count-mode" className="flex items-center space-x-2 cursor-pointer">
                      <span className="font-semibold text-gray-700">Specific on-demand count</span>
                      <div className="group relative">
                        <i className="fas fa-info-circle text-gray-400 hover:text-gray-600"></i>
                        <div className="absolute left-0 bottom-full mb-2 hidden group-hover:block w-64 p-2 bg-gray-800 text-white text-xs rounded-lg shadow-lg z-10">
                          Set a fixed number of On-Demand instances. All other instances will be Spot.
                        </div>
                      </div>
                    </label>

                    {configMode === 'count' && (
                      <div className="mt-3 bg-gray-50 p-4 rounded-lg border border-gray-200">
                        <input
                          type="number"
                          min="0"
                          value={onDemandCount}
                          onChange={(e) => setOnDemandCount(Math.max(0, Number(e.target.value)))}
                          placeholder="Enter count..."
                          className="w-full px-4 py-3 border border-gray-300 rounded-lg text-lg focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                        />
                        <p className="text-xs text-gray-500 mt-2">
                          Number of On-Demand instances to maintain. Remaining will be Spot.
                        </p>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </div>

            {/* Instance Types Configuration Section */}
            <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
              <h2 className="text-xl font-bold text-gray-900 mb-2 flex items-center">
                <i className="fas fa-server text-indigo-600 mr-3 text-2xl"></i>
                Instance Types
              </h2>
              <p className="text-sm text-gray-600 mb-6">
                Select backup instance types for Spot diversification. If AWS runs out of your preferred type, it will automatically use these alternatives.
              </p>

              <div className="space-y-6">
                {Object.entries(INSTANCE_TYPE_FAMILIES).map(([family, types]) => (
                  <div key={family} className="border border-gray-200 rounded-lg p-4 bg-gray-50">
                    <h4 className="font-semibold text-gray-700 mb-3 flex items-center">
                      <i className="fas fa-layer-group text-indigo-500 mr-2"></i>
                      {family}
                    </h4>
                    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-2">
                      {types.map((type) => (
                        <button
                          key={type}
                          type="button"
                          onClick={() => {
                            setSelectedTypes((prev) => {
                              const next = new Set(prev);
                              if (next.has(type)) next.delete(type);
                              else next.add(type);
                              return next;
                            });
                          }}
                          className={clsx(
                            'px-3 py-2 rounded-lg text-sm font-medium transition-all border',
                            selectedTypes.has(type)
                              ? 'bg-indigo-600 text-white border-indigo-600 shadow-md'
                              : 'bg-white text-gray-700 border-gray-300 hover:border-indigo-400 hover:bg-indigo-50'
                          )}
                        >
                          {type}
                        </button>
                      ))}
                    </div>
                  </div>
                ))}

                {/* Custom Instance Type */}
                <div className="border border-gray-200 rounded-lg p-4 bg-gray-50">
                  <h4 className="font-semibold text-gray-700 mb-3 flex items-center">
                    <i className="fas fa-plus-circle text-green-500 mr-2"></i>
                    Custom Instance Type
                  </h4>
                  <div className="flex items-center space-x-2">
                    <input
                      type="text"
                      value={customType}
                      onChange={(e) => setCustomType(e.target.value)}
                      onKeyPress={(e) => {
                        if (e.key === 'Enter' && customType.trim() && /^[a-z0-9]+\.[a-z0-9]+$/.test(customType.trim())) {
                          setSelectedTypes((prev) => new Set(prev).add(customType.trim()));
                          setCustomType('');
                        }
                      }}
                      placeholder="e.g., m5n.large"
                      className="flex-1 px-4 py-2 rounded-lg border border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 transition-all"
                    />
                    <button
                      type="button"
                      onClick={() => {
                        const trimmed = customType.trim();
                        if (trimmed && /^[a-z0-9]+\.[a-z0-9]+$/.test(trimmed)) {
                          setSelectedTypes((prev) => new Set(prev).add(trimmed));
                          setCustomType('');
                        }
                      }}
                      className="px-4 py-2 bg-green-600 text-white font-semibold rounded-lg shadow-md hover:bg-green-700 transition-all"
                    >
                      <i className="fas fa-plus mr-2"></i>
                      Add
                    </button>
                  </div>
                </div>

                {/* Selected Count */}
                <div className="text-sm text-gray-600 bg-blue-50 border border-blue-200 rounded-lg p-3">
                  <i className="fas fa-info-circle text-blue-600 mr-2"></i>
                  <span className="font-semibold">{selectedTypes.size}</span> instance type{selectedTypes.size !== 1 ? 's' : ''} selected
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </main>
  );
};

export default ASGConfigPage;
