/**
 * CloudSitter Dashboard Component
 *
 * Main dashboard view with EC2 instances table and filters
 */

import React from 'react';
import type { EC2Resource } from '../../types/cloudsitter';
import Skeleton from '../Shared/Skeleton';

// Hourly on-demand prices (us-east-1 baseline)
const HOURLY_PRICES: Record<string, number> = {
  't2.nano': 0.0058, 't2.micro': 0.0116, 't2.small': 0.023, 't2.medium': 0.0464,
  't2.large': 0.0928, 't2.xlarge': 0.1856, 't2.2xlarge': 0.3712,
  't3.nano': 0.0052, 't3.micro': 0.0104, 't3.small': 0.0208, 't3.medium': 0.0416,
  't3.large': 0.0832, 't3.xlarge': 0.1664, 't3.2xlarge': 0.3328,
  'm5.large': 0.096, 'm5.xlarge': 0.192, 'm5.2xlarge': 0.384, 'm5.4xlarge': 0.768,
  'm5.8xlarge': 1.536, 'm5.12xlarge': 2.304, 'm5.24xlarge': 4.608,
  'c5.large': 0.085, 'c5.xlarge': 0.17, 'c5.2xlarge': 0.34, 'c5.4xlarge': 0.68,
  'r5.large': 0.126, 'r5.xlarge': 0.252, 'r5.2xlarge': 0.504, 'r5.4xlarge': 1.008,
};

// Office-hours schedule: Mon-Fri 9-18 = 45 hrs/wk running → 123 hrs/wk stopped
const STOPPED_HOURS_PER_MONTH = Math.round(123 * 4.33); // ~532 hrs

function getHourlyPrice(instanceType: string): number {
  return HOURLY_PRICES[instanceType.toLowerCase()] ?? 0.05;
}

function getMonthlyRunCost(resource: EC2Resource): number {
  return Math.round(getHourlyPrice(resource.type) * 730 * 100) / 100;
}

function getPotentialSavings(resource: EC2Resource): number {
  if (resource.savings > 0) return resource.savings;
  return Math.round(getHourlyPrice(resource.type) * STOPPED_HOURS_PER_MONTH * 100) / 100;
}

interface KpiData {
  totalInstances: number;
  managedCount: number;
  totalMonthlyCost: number;
  potentialSavings: number;
}

function isActive(resource: EC2Resource): boolean {
  return resource.state !== 'terminated' && resource.state !== 'stopped';
}

function computeKpis(allResources: EC2Resource[]): KpiData {
  const activeResources = allResources.filter(isActive);
  const managedCount = activeResources.filter((r) => r.policyName).length;
  const totalMonthlyCost = activeResources.reduce((sum, r) => sum + getMonthlyRunCost(r), 0);
  const potentialSavings = activeResources.reduce((sum, r) => sum + getPotentialSavings(r), 0);
  return {
    totalInstances: allResources.length,
    managedCount,
    totalMonthlyCost: Math.round(totalMonthlyCost * 100) / 100,
    potentialSavings: Math.round(potentialSavings * 100) / 100,
  };
}

interface CloudSitterDashboardProps {
  accountId: string | null;
  isLoading: boolean;
  searchQuery: string;
  resources: EC2Resource[];
  allResources: EC2Resource[];
  onSearchChange: (query: string) => void;
  onRefresh: () => void;
  onCreatePolicy: () => void;
  onManagePolicies: () => void;
  onAssignPolicy: (resource: EC2Resource) => void;
  provider?: 'AWS' | 'GCP' | 'AZURE';
}

const RESOURCE_LABEL: Record<string, { icon: string; label: string }> = {
  AWS:   { icon: 'fab fa-aws',     label: 'EC2 Instances' },
  GCP:   { icon: 'fab fa-google',  label: 'VM Instances' },
  AZURE: { icon: 'fab fa-microsoft', label: 'Virtual Machines' },
};

const CloudSitterDashboard: React.FC<CloudSitterDashboardProps> = ({
  accountId,
  isLoading,
  searchQuery,
  resources,
  allResources,
  onSearchChange,
  onRefresh,
  onCreatePolicy,
  onManagePolicies,
  onAssignPolicy,
  provider = 'AWS',
}) => {
  const kpi = computeKpis(allResources);
  const coveragePct = kpi.totalInstances > 0
    ? Math.round((kpi.managedCount / kpi.totalInstances) * 100)
    : 0;

  return (
    <>
      {/* KPI Cards */}
      {(allResources.length > 0 || isLoading) && (
        <div className="grid grid-cols-4 gap-4 mb-6">
          {/* Total Instances */}
          <div className="bg-white rounded-xl border border-gray-200 shadow-sm p-5 flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-indigo-50 flex items-center justify-center flex-shrink-0">
              <i className="fas fa-server text-indigo-600 text-lg"></i>
            </div>
            <div className="min-w-0">
              <div className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-0.5">
                Total Instances
              </div>
              {isLoading ? (
                <Skeleton variant="text" width={48} height={28} />
              ) : (
                <div className="text-2xl font-bold text-gray-900">{kpi.totalInstances}</div>
              )}
              <div className="text-xs text-gray-400 mt-0.5">EC2 resources</div>
            </div>
          </div>

          {/* Managed */}
          <div className="bg-white rounded-xl border border-gray-200 shadow-sm p-5 flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-green-50 flex items-center justify-center flex-shrink-0">
              <i className="fas fa-shield-alt text-green-600 text-lg"></i>
            </div>
            <div className="min-w-0">
              <div className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-0.5">
                Managed
              </div>
              {isLoading ? (
                <Skeleton variant="text" width={48} height={28} />
              ) : (
                <div className="text-2xl font-bold text-gray-900">
                  {kpi.managedCount}
                  <span className="text-sm font-medium text-gray-400 ml-1.5">/ {kpi.totalInstances}</span>
                </div>
              )}
              <div className="text-xs text-gray-400 mt-0.5">{coveragePct}% coverage</div>
            </div>
          </div>

          {/* Total Monthly Cost */}
          <div className="bg-white rounded-xl border border-gray-200 shadow-sm p-5 flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-blue-50 flex items-center justify-center flex-shrink-0">
              <i className="fas fa-dollar-sign text-blue-600 text-lg"></i>
            </div>
            <div className="min-w-0">
              <div className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-0.5">
                Total Monthly Cost
              </div>
              {isLoading ? (
                <Skeleton variant="text" width={80} height={28} />
              ) : (
                <div className="text-2xl font-bold text-gray-900">
                  ${kpi.totalMonthlyCost.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 0 })}
                </div>
              )}
              <div className="text-xs text-gray-400 mt-0.5">24/7 run cost est.</div>
            </div>
          </div>

          {/* Potential Savings */}
          <div className="bg-white rounded-xl border border-emerald-100 shadow-sm p-5 flex items-center gap-4 relative overflow-hidden">
            <div className="absolute inset-0 bg-gradient-to-br from-emerald-50/60 to-transparent pointer-events-none" />
            <div className="w-12 h-12 rounded-xl bg-emerald-50 flex items-center justify-center flex-shrink-0 relative">
              <i className="fas fa-piggy-bank text-emerald-600 text-lg"></i>
            </div>
            <div className="min-w-0 relative">
              <div className="text-xs font-semibold text-emerald-700 uppercase tracking-wide mb-0.5">
                Potential Savings
              </div>
              {isLoading ? (
                <Skeleton variant="text" width={80} height={28} />
              ) : (
                <div className="text-2xl font-bold text-emerald-700">
                  ${kpi.potentialSavings.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 0 })}
                  <span className="text-sm font-medium text-emerald-500 ml-1">/mo</span>
                </div>
              )}
              <div className="text-xs text-emerald-600/70 mt-0.5 flex items-center gap-1">
                <i className="fas fa-brain text-[10px]"></i>
                Based on CPU &amp; memory patterns
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Filter Bar */}
      <div className="flex justify-between items-center mb-6 bg-white p-4 rounded-xl border border-gray-200 shadow-sm">
        <div className="flex gap-4 items-center">
          <span className="text-sm font-bold text-gray-700 uppercase tracking-wide">
            Filter Resources:
          </span>
          <label className="flex items-center gap-2 px-3 py-1.5 border rounded-lg cursor-pointer bg-indigo-50 border-indigo-100 text-indigo-700 transition-colors">
            <input
              type="checkbox"
              checked
              disabled
              className="rounded text-indigo-600 focus:ring-indigo-500"
            />
            <span className="text-sm font-medium">
              <i className={`${RESOURCE_LABEL[provider].icon} mr-1`}></i> {RESOURCE_LABEL[provider].label}
            </span>
          </label>
        </div>

        <div className="flex gap-3">
          <button
            onClick={onRefresh}
            disabled={isLoading}
            className="text-gray-600 hover:text-indigo-600 hover:bg-gray-50 px-4 py-2 rounded-lg text-sm font-medium transition-all border border-gray-300 flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <i className={`fas fa-sync-alt ${isLoading ? 'animate-spin' : ''}`}></i> Refresh
          </button>

          <button
            onClick={onManagePolicies}
            className="text-gray-600 hover:text-indigo-600 hover:bg-gray-50 px-4 py-2 rounded-lg text-sm font-medium transition-all border border-gray-300 flex items-center gap-2"
          >
            <i className="fas fa-cog"></i> Manage Policies
          </button>

          <div className="relative">
            <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400"></i>
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => onSearchChange(e.target.value)}
              placeholder="Search name or ID..."
              className="pl-10 pr-4 py-2 border border-gray-300 rounded-lg text-sm w-64 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
            />
          </div>
          <button
            onClick={onCreatePolicy}
            className="bg-indigo-600 hover:bg-indigo-700 text-white px-4 py-2 rounded-lg text-sm font-medium transition-all shadow-md hover:shadow-lg flex items-center gap-2"
          >
            <i className="fas fa-plus"></i> Create Policy
          </button>
        </div>
      </div>

      {/* Loading State */}
      {isLoading && (
        <div className="space-y-4">
          {[1, 2, 3, 4, 5].map(i => (
            <div key={i} className="bg-white rounded-xl p-6 border border-gray-200 shadow-sm flex items-center gap-4">
              <div className="flex-1">
                <Skeleton variant="text" width="30%" height={24} className="mb-2" />
                <Skeleton variant="text" width="50%" height={16} />
              </div>
              <Skeleton variant="text" width={100} height={32} className="rounded-md" />
            </div>
          ))}
        </div>
      )}

      {/* Empty State */}
      {!isLoading && (!accountId || resources.length === 0) && (
        <div className="flex flex-col items-center justify-center py-20 bg-white rounded-xl border border-gray-200 shadow-sm">
          <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mb-4 text-gray-400 text-2xl">
            <i className="fas fa-server"></i>
          </div>
          <h3 className="text-lg font-bold text-gray-800 mb-1">No Resources Found</h3>
          <p className="text-gray-500 text-sm max-w-md text-center">
            {accountId
              ? 'No EC2 instances matched your search criteria.'
              : 'Please select an AWS account from the sidebar to view resources.'}
          </p>
        </div>
      )}

      {/* Resources Table */}
      {!isLoading && resources.length > 0 && (
        <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
          <table className="w-full text-left border-collapse">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase font-semibold border-b">
              <tr>
                <th className="p-4">Name / Instance ID</th>
                <th className="p-4">Region</th>
                <th className="p-4">Type</th>
                <th className="p-4">State</th>
                <th className="p-4">CloudSitter Policy</th>
                <th className="p-4 text-right">Est. Monthly Cost</th>
                <th className="p-4 text-right">Potential Savings</th>
                <th className="p-4 text-center">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 text-sm">
              {resources.map((resource) => {
                const isTerminated = resource.state === 'terminated' || resource.state === 'stopped';
                const monthlyCost = getMonthlyRunCost(resource);
                const potSavings = getPotentialSavings(resource);
                const hasPolicy = !!resource.policyName;
                return (
                  <tr key={resource.id} className="hover:bg-gray-50 transition-colors group">
                    <td className="p-4">
                      <div className="font-bold text-gray-900">{resource.name || '-'}</div>
                      <div className="text-gray-500 text-xs font-mono mt-0.5 flex items-center gap-1">
                        <span>{resource.id}</span>
                        <i
                          className="far fa-copy cursor-pointer hover:text-indigo-600"
                          title="Copy ID"
                          onClick={(e) => {
                            e.stopPropagation();
                            navigator.clipboard.writeText(resource.id);
                          }}
                        ></i>
                      </div>
                    </td>
                    <td className="p-4">
                      <div className="flex items-center gap-2 text-gray-600">
                        <img
                          src="/icons/aws-icon.png"
                          className="w-4 h-4 object-contain"
                          onError={(e) => {
                            e.currentTarget.style.display = 'none';
                          }}
                          alt="AWS"
                        />
                        <span>{resource.region}</span>
                      </div>
                    </td>
                    <td className="p-4">
                      <span className="bg-gray-100 text-gray-700 px-2 py-1 rounded text-xs font-mono border border-gray-200">
                        {resource.type}
                      </span>
                    </td>
                    <td className="p-4">
                      <div className="flex items-center gap-2">
                        <div
                          className={`w-2.5 h-2.5 rounded-full ${resource.state === 'running' ? 'bg-green-500' : 'bg-gray-400'
                            }`}
                        ></div>
                        <span className="text-gray-700 font-medium capitalize">{resource.state}</span>
                      </div>
                    </td>
                    <td className="p-4">
                      {resource.policyName ? (
                        <div className="flex items-center gap-2">
                          <span className="bg-green-50 text-green-700 border border-green-200 px-2.5 py-1 rounded-md text-xs font-semibold flex items-center gap-1.5">
                            <i className="fas fa-clock"></i> <span>{resource.policyName}</span>
                          </span>
                        </div>
                      ) : (
                        <div className="text-gray-400 text-xs italic pl-2">No Policy</div>
                      )}
                    </td>
                    <td className="p-4 text-right">
                      {isTerminated ? (
                        <div className="text-gray-300">—</div>
                      ) : (
                        <>
                          <div className="text-gray-600 font-medium">${monthlyCost.toFixed(2)}</div>
                          <div className="text-gray-400 text-xs">24/7 est.</div>
                        </>
                      )}
                    </td>
                    <td className="p-4 text-right">
                      {isTerminated ? (
                        <div className="text-gray-300">—</div>
                      ) : (
                        <>
                          <div className={`font-bold ${hasPolicy ? 'text-emerald-600' : 'text-amber-500'}`}>
                            ${potSavings.toFixed(2)}
                          </div>
                          <div className={`text-xs mt-0.5 ${hasPolicy ? 'text-emerald-500' : 'text-amber-400'}`}>
                            {hasPolicy ? (
                              <span className="flex items-center justify-end gap-1">
                                <i className="fas fa-check-circle text-[10px]"></i> Active
                              </span>
                            ) : (
                              <span className="flex items-center justify-end gap-1">
                                <i className="fas fa-brain text-[10px]"></i> Recommended
                              </span>
                            )}
                          </div>
                        </>
                      )}
                    </td>
                    <td className="p-4 text-center">
                      <button
                        onClick={() => onAssignPolicy(resource)}
                        className="text-indigo-600 hover:text-indigo-800 hover:bg-indigo-50 px-3 py-1.5 rounded-md text-sm font-medium transition-colors border border-transparent hover:border-indigo-200"
                      >
                        <i className="fas fa-calendar-check mr-1"></i> Assign
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
};

export default CloudSitterDashboard;
