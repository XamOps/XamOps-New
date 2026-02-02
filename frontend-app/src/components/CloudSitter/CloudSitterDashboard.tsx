/**
 * CloudSitter Dashboard Component
 *
 * Main dashboard view with EC2 instances table and filters
 */

import React from 'react';
import type { EC2Resource } from '../../types/cloudsitter';
import Skeleton from '../Shared/Skeleton';

interface CloudSitterDashboardProps {
  accountId: string | null;
  isLoading: boolean;
  searchQuery: string;
  resources: EC2Resource[];
  onSearchChange: (query: string) => void;
  onRefresh: () => void;
  onCreatePolicy: () => void;
  onManagePolicies: () => void;
  onAssignPolicy: (resource: EC2Resource) => void;
}

const CloudSitterDashboard: React.FC<CloudSitterDashboardProps> = ({
  accountId,
  isLoading,
  searchQuery,
  resources,
  onSearchChange,
  onRefresh,
  onCreatePolicy,
  onManagePolicies,
  onAssignPolicy,
}) => {
  return (
    <>
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
              <i className="fab fa-aws mr-1"></i> EC2 Instances
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
                <th className="p-4 text-right">Est. Monthly Savings</th>
                <th className="p-4 text-center">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 text-sm">
              {resources.map((resource) => (
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
                    {resource.savings > 0 ? (
                      <div className="text-green-600 font-bold">${resource.savings.toFixed(2)}</div>
                    ) : (
                      <div className="text-gray-300">-</div>
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
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
};

export default CloudSitterDashboard;
