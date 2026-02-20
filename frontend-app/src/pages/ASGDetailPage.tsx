/**
 * ASG Detail Page
 *
 * Shows detailed information about instances running in an Auto Scaling Group
 *
 * Features:
 * - Instance list with VPC, Security Groups, AMI details
 * - Summary cards for instance counts and savings
 * - Back navigation to Spot Automation page
 * - Loading states and error handling
 */

import React, { useState, useEffect } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import { useTopbar } from '../contexts/TopbarContext';
import apiClient from '../utils/apiClient';
import type { InstanceDetail } from '../hooks/useSpotAutomationData';
import clsx from 'clsx';

const ASGDetailPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const { setTitle, setActions, clearActions } = useTopbar();

  // Get params from URL
  const asgName = searchParams.get('asgName');
  const region = searchParams.get('region');
  const accountId = searchParams.get('accountIds');

  // State
  const [instances, setInstances] = useState<InstanceDetail[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);

  // Fetch instances
  const fetchInstances = async (showRefreshing = false) => {
    if (!accountId || !asgName || !region) return;

    try {
      if (showRefreshing) setIsRefreshing(true);
      else setLoading(true);
      setError(null);

      const data = await apiClient.get(
        `/api/autospotting/instances/${accountId}?asgName=${encodeURIComponent(asgName)}&region=${region}`
      );
      setInstances(data || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch instances');
    } finally {
      setLoading(false);
      setIsRefreshing(false);
    }
  };

  useEffect(() => {
    fetchInstances();
  }, [accountId, asgName, region]);

  // Topbar Integration
  useEffect(() => {
    if (!asgName) return;

    setTitle(asgName);

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
            <i className="fas fa-map-marker-alt"></i> {region} | <i className="fas fa-server"></i> {instances.length} instances
          </div>
        </div>

        <button
          onClick={() => fetchInstances(true)}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );

    return () => {
      clearActions();
      setTitle(null);
    };
  }, [setTitle, setActions, clearActions, asgName, accountId, region, instances.length, isRefreshing]);

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

  // Calculate summary metrics
  const spotCount = instances.filter(i => i.lifecycle === 'spot').length;
  const onDemandCount = instances.filter(i => i.lifecycle === 'on-demand').length;
  const totalSavings = instances.reduce((sum, i) => sum + (i.estimatedHourlySavings || 0), 0);

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      <div className="p-8 space-y-6">
        {/* Loading State */}
        {loading && instances.length === 0 && (
          <div className="space-y-6">
            <div className="grid grid-cols-4 gap-6">
              {[1, 2, 3, 4].map(i => (
                <div key={i} className="bg-white rounded-xl p-6 animate-pulse">
                  <div className="h-4 bg-gray-200 rounded w-1/2 mb-4"></div>
                  <div className="h-8 bg-gray-200 rounded w-3/4"></div>
                </div>
              ))}
            </div>
            <div className="bg-white rounded-xl p-6 animate-pulse">
              <div className="h-96"></div>
            </div>
          </div>
        )}

        {/* Error State */}
        {error && !loading && (
          <div className="bg-red-50 border-l-4 border-red-500 p-6 rounded-lg shadow-sm">
            <div className="flex items-center">
              <i className="fas fa-exclamation-circle text-red-500 text-2xl mr-4"></i>
              <div>
                <h3 className="text-lg font-bold text-red-800">Error Loading Instances</h3>
                <p className="text-sm text-red-600 mt-1">{error}</p>
              </div>
            </div>
          </div>
        )}

        {/* Dashboard Content */}
        {(!loading || instances.length > 0) && !error && (
          <>
            {/* Summary Cards */}
            <div className="grid grid-cols-4 gap-6">
              <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Total Instances</span>
                  <i className="fas fa-server text-gray-400 text-lg"></i>
                </div>
                <div className="text-3xl font-bold text-gray-900">{instances.length}</div>
              </div>

              <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Spot Instances</span>
                  <i className="fas fa-bolt text-green-400 text-lg"></i>
                </div>
                <div className="text-3xl font-bold text-green-600">{spotCount}</div>
              </div>

              <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">On-Demand</span>
                  <i className="fas fa-server text-orange-400 text-lg"></i>
                </div>
                <div className="text-3xl font-bold text-orange-600">{onDemandCount}</div>
              </div>

              <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Est. Hourly Savings</span>
                  <i className="fas fa-piggy-bank text-indigo-400 text-lg"></i>
                </div>
                <div className="text-3xl font-bold text-indigo-600">${totalSavings.toFixed(4)}</div>
              </div>
            </div>

            {/* Instance Table */}
            <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
              <div className="px-6 py-4 border-b border-gray-200">
                <h2 className="text-lg font-bold text-gray-900">Instances</h2>
              </div>

              {instances.length === 0 ? (
                <div className="px-6 py-16 text-center">
                  <div className="text-gray-400 mb-4">
                    <i className="fas fa-inbox text-5xl"></i>
                  </div>
                  <p className="text-gray-600 font-semibold text-lg">No Instances Found</p>
                  <p className="text-gray-500 text-sm mt-2">This ASG currently has no running instances.</p>
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead className="bg-gray-50 border-b border-gray-200">
                      <tr>
                        <th className="px-6 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">Instance ID</th>
                        <th className="px-6 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">Type</th>
                        <th className="px-6 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">State</th>
                        <th className="px-6 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">Lifecycle</th>
                        <th className="px-6 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">VPC</th>
                        <th className="px-6 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">Security Groups</th>
                        <th className="px-6 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">AMI</th>
                        <th className="px-6 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">Private IP</th>
                        <th className="px-6 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">Savings/hr</th>
                      </tr>
                    </thead>
                    <tbody className="bg-white divide-y divide-gray-200">
                      {instances.map((instance) => (
                        <tr key={instance.instanceId} className="hover:bg-blue-50 transition-colors">
                          <td className="px-6 py-4 whitespace-nowrap">
                            <div className="flex items-center">
                              <i className="fas fa-server text-gray-400 mr-3"></i>
                              <span className="text-sm font-mono text-gray-900">{instance.instanceId}</span>
                            </div>
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap">
                            <span className="text-sm font-medium text-gray-700">{instance.instanceType}</span>
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap">
                            <span className={clsx(
                              'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold',
                              instance.state === 'running' && 'bg-green-100 text-green-800',
                              instance.state === 'stopped' && 'bg-gray-100 text-gray-800',
                              instance.state === 'terminated' && 'bg-red-100 text-red-800'
                            )}>
                              {instance.state}
                            </span>
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap">
                            <span className={clsx(
                              'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold',
                              instance.lifecycle === 'spot' && 'bg-green-100 text-green-800',
                              instance.lifecycle === 'on-demand' && 'bg-orange-100 text-orange-800'
                            )}>
                              <i className={clsx(
                                'fas mr-1',
                                instance.lifecycle === 'spot' && 'fa-bolt',
                                instance.lifecycle === 'on-demand' && 'fa-server'
                              )}></i>
                              {instance.lifecycle === 'spot' ? 'Spot' : 'On-Demand'}
                            </span>
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap">
                            <span className="text-sm font-mono text-gray-600">{instance.vpcId || 'N/A'}</span>
                          </td>
                          <td className="px-6 py-4">
                            <div className="flex flex-wrap gap-1">
                              {instance.securityGroups && instance.securityGroups.length > 0 ? (
                                instance.securityGroups.map((sg) => (
                                  <span key={sg.groupId} className="inline-flex items-center px-2 py-1 rounded-md text-xs bg-blue-50 text-blue-700 font-mono" title={sg.groupName}>
                                    {sg.groupId}
                                  </span>
                                ))
                              ) : (
                                <span className="text-sm text-gray-400">None</span>
                              )}
                            </div>
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap">
                            <span className="text-sm font-mono text-gray-600">{instance.imageId || 'N/A'}</span>
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap">
                            <span className="text-sm font-mono text-gray-600">{instance.privateIpAddress || 'N/A'}</span>
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap">
                            {instance.estimatedHourlySavings > 0 ? (
                              <span className="text-sm font-semibold text-green-600">
                                ${instance.estimatedHourlySavings.toFixed(4)}
                              </span>
                            ) : (
                              <span className="text-sm text-gray-400">$0.00</span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </main>
  );
};

export default ASGDetailPage;
