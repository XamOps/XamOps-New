/**
 * Actions History Component
 *
 * Displays AutoSpotting events history with filters, summary cards, and detailed timeline.
 * Converted from: legacy/actions-history.html
 *
 * Features:
 * - Date range filtering
 * - Event type filtering
 * - ASG name filtering
 * - Summary cards (replacements, interruptions, savings, MTBI)
 * - Events timeline table
 * - Loading skeletons
 * - Empty states
 */

import React, { useState, useEffect } from 'react';
import { useActionsHistory } from '../../hooks/useActionsHistory';
import { ActionsHistoryFilters } from '../../types/actionsHistory';

interface ActionsHistoryProps {
  accountId: string | null;
}

const ActionsHistory: React.FC<ActionsHistoryProps> = ({ accountId }) => {
  const { events, summary, loading, error, loadEvents, resetFilters } = useActionsHistory({
    accountId,
    autoLoad: false,
  });

  const [filters, setFilters] = useState<ActionsHistoryFilters>(() => ({
    startDate: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
    endDate: new Date().toISOString().split('T')[0],
    eventType: '',
    asgName: '',
  }));

  const [hasInitialLoad, setHasInitialLoad] = useState(false);

  // Auto-load events on mount
  useEffect(() => {
    if (accountId && !hasInitialLoad) {
      loadEvents(filters);
      setHasInitialLoad(true);
    }
  }, [accountId, hasInitialLoad, filters, loadEvents]);

  const handleLoadEvents = () => {
    loadEvents(filters);
  };

  const handleResetFilters = () => {
    const defaultFilters = resetFilters();
    setFilters(defaultFilters);
    loadEvents(defaultFilters);
  };

  const handleFilterChange = (key: keyof ActionsHistoryFilters, value: string) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
  };

  // Format functions
  const formatDateTime = (ts: string | undefined) => {
    if (!ts) return '-';
    return new Date(ts).toLocaleString('en-US', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const formatDateShort = (dateStr: string) => {
    if (!dateStr) return '';
    return new Date(dateStr).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
  };

  const formatEventType = (type: string) => {
    if (!type) return '';
    return type
      .split('_')
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ');
  };

  const getEventTypeClass = (type: string): string => {
    const classes: Record<string, string> = {
      instance_replacement: 'bg-green-100 text-green-800',
      spot_interruption: 'bg-orange-100 text-orange-800',
      launch_success: 'bg-emerald-100 text-emerald-800',
      launch_failure: 'bg-red-100 text-red-800',
      launch_attempt: 'bg-blue-100 text-blue-700',
      asg_run: 'bg-gray-100 text-gray-700',
      asg_enabled: 'bg-teal-100 text-teal-800',
      asg_disabled: 'bg-gray-200 text-gray-600',
    };
    return classes[type] || 'bg-gray-100 text-gray-800';
  };

  const getEventTypeIcon = (type: string): string => {
    const icons: Record<string, string> = {
      instance_replacement: 'fa-sync-alt',
      spot_interruption: 'fa-exclamation-triangle',
      launch_success: 'fa-check-circle',
      launch_failure: 'fa-times-circle',
      launch_attempt: 'fa-rocket',
      asg_run: 'fa-play-circle',
      asg_enabled: 'fa-toggle-on',
      asg_disabled: 'fa-toggle-off',
    };
    return icons[type] || 'fa-info-circle';
  };

  // No account selected
  if (!accountId) {
    return (
      <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-16 text-center">
        <i className="fas fa-inbox text-5xl mb-4 text-gray-300"></i>
        <p className="text-lg font-medium text-gray-900">No Account Selected</p>
        <p className="text-sm text-gray-500 mt-2">
          Please select an AWS account from the sidebar to view actions history.
        </p>
      </div>
    );
  }

  return (
    <div className="actions-history-container space-y-6">
      {/* Filters Section */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Filters</h3>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">From</label>
            <input
              type="date"
              value={filters.startDate}
              onChange={(e) => handleFilterChange('startDate', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent text-sm"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">To</label>
            <input
              type="date"
              value={filters.endDate}
              onChange={(e) => handleFilterChange('endDate', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent text-sm"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Type</label>
            <select
              value={filters.eventType}
              onChange={(e) => handleFilterChange('eventType', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent text-sm"
            >
              <option value="">All Events</option>
              <option value="instance_replacement">Replacements</option>
              <option value="spot_interruption">Interruptions</option>
              <option value="launch_success">Launch Success</option>
              <option value="launch_failure">Launch Failure</option>
              <option value="asg_run">ASG Run</option>
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">ASG Name</label>
            <input
              type="text"
              value={filters.asgName}
              onChange={(e) => handleFilterChange('asgName', e.target.value)}
              placeholder="Filter by ASG..."
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent text-sm"
            />
          </div>
        </div>

        <div className="flex gap-3 mt-4">
          <button
            onClick={handleLoadEvents}
            disabled={loading}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:bg-blue-400 disabled:cursor-not-allowed transition-colors font-medium text-sm flex items-center"
          >
            <i className={`fas mr-2 ${loading ? 'fa-spinner fa-spin' : 'fa-search'}`}></i>
            <span>{loading ? 'Loading...' : 'Load History'}</span>
          </button>
          <button
            onClick={handleResetFilters}
            className="px-4 py-2 bg-gray-200 text-gray-700 rounded-lg hover:bg-gray-300 transition-colors font-medium text-sm"
          >
            <i className="fas fa-undo mr-2"></i>
            Reset
          </button>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
        <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-gray-600">Total Replacements</p>
              <p className="text-2xl font-bold text-green-600 mt-1">
                {summary.total_replacements || 0}
              </p>
              <p className="text-xs text-gray-500 mt-1">on-demand → spot</p>
            </div>
            <div className="bg-green-100 rounded-full p-3">
              <i className="fas fa-sync-alt text-green-600 text-xl"></i>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-gray-600">Spot Interruptions</p>
              <p className="text-2xl font-bold text-orange-600 mt-1">
                {summary.total_interruptions || 0}
              </p>
              <p className="text-xs text-gray-500 mt-1">handled</p>
            </div>
            <div className="bg-orange-100 rounded-full p-3">
              <i className="fas fa-exclamation-triangle text-orange-600 text-xl"></i>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-gray-600">Est. Hourly Savings</p>
              <p className="text-2xl font-bold text-purple-600 mt-1">
                ${(summary.total_estimated_savings || 0).toFixed(4)}
              </p>
              <p className="text-xs text-gray-500 mt-1">from replacements</p>
            </div>
            <div className="bg-purple-100 rounded-full p-3">
              <i className="fas fa-dollar-sign text-purple-600 text-xl"></i>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-gray-600">Stability Score (MTBI)</p>
              <p className={`text-2xl font-bold mt-1 ${summary.mtbiColor || 'text-gray-800'}`}>
                {summary.mtbi || '-'}
              </p>
              <p className="text-xs text-gray-500 mt-1">Mean time b/w interruptions</p>
            </div>
            <div className="bg-indigo-100 rounded-full p-3">
              <i className="fas fa-heartbeat text-indigo-600 text-xl"></i>
            </div>
          </div>
        </div>
      </div>

      {/* Loading Indicator */}
      {loading && events.length === 0 && (
        <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-16 text-center">
          <i className="fas fa-spinner fa-spin text-5xl text-blue-600 mb-4"></i>
          <p className="text-gray-600 font-medium text-lg">Loading events history...</p>
          <p className="text-sm text-gray-500 mt-2">Fetching AutoSpotting events data</p>
        </div>
      )}

      {/* Events Timeline Table */}
      {(!loading || events.length > 0) && (
        <div className="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-200 bg-gray-50">
            <h3 className="font-semibold text-gray-800">Events Timeline</h3>
            <p className="text-xs text-gray-500 mt-1">
              Showing {events.length} event{events.length !== 1 ? 's' : ''} from{' '}
              {formatDateShort(filters.startDate)} to {formatDateShort(filters.endDate)}
            </p>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse min-w-max">
              <thead>
                <tr className="bg-gray-50 text-gray-600 text-xs uppercase tracking-wider">
                  <th className="px-4 py-3 font-semibold whitespace-nowrap">Timestamp</th>
                  <th className="px-4 py-3 font-semibold whitespace-nowrap">Type</th>
                  <th className="px-4 py-3 font-semibold whitespace-nowrap">ASG Name</th>
                  <th className="px-4 py-3 font-semibold whitespace-nowrap">Region</th>
                  <th className="px-4 py-3 font-semibold whitespace-nowrap">Old Instance</th>
                  <th className="px-4 py-3 font-semibold whitespace-nowrap">Old Type</th>
                  <th className="px-4 py-3 font-semibold whitespace-nowrap">New Instance</th>
                  <th className="px-4 py-3 font-semibold whitespace-nowrap">New Type</th>
                  <th className="px-4 py-3 font-semibold whitespace-nowrap">Impact</th>
                  <th className="px-4 py-3 font-semibold min-w-[200px]">Reason</th>
                </tr>
              </thead>

              {/* Loading Skeleton */}
              {loading && (
                <tbody className="divide-y divide-gray-100">
                  {[1, 2, 3, 4, 5].map((i) => (
                    <tr key={i} className="animate-pulse">
                      <td className="px-4 py-4">
                        <div className="h-4 bg-gray-200 rounded w-24"></div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="h-6 bg-gray-200 rounded-full w-24"></div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="h-4 bg-gray-200 rounded w-32"></div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="h-4 bg-gray-200 rounded w-16"></div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="h-4 bg-gray-200 rounded w-20 mb-1"></div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="h-4 bg-gray-200 rounded w-16"></div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="h-4 bg-gray-200 rounded w-20 mb-1"></div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="h-4 bg-gray-200 rounded w-16"></div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="h-4 bg-gray-200 rounded w-12"></div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="h-4 bg-gray-200 rounded w-full"></div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              )}

              {/* Events Data */}
              {!loading && events.length > 0 && (
                <tbody className="divide-y divide-gray-100">
                  {events.map((event, index) => (
                    <tr key={index} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-4 text-sm text-gray-900 whitespace-nowrap">
                        <div>{formatDateTime(event.timestamp)}</div>
                      </td>

                      <td className="px-4 py-4 whitespace-nowrap">
                        <span
                          className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium whitespace-nowrap ${getEventTypeClass(
                            event.event_type
                          )}`}
                        >
                          <i className={`fas mr-1.5 ${getEventTypeIcon(event.event_type)}`}></i>
                          <span>{formatEventType(event.event_type)}</span>
                        </span>
                      </td>

                      <td className="px-4 py-4 whitespace-nowrap">
                        <div
                          className="text-sm font-medium text-gray-900 truncate w-40"
                          title={event.asg_name}
                        >
                          {event.asg_name}
                        </div>
                      </td>

                      <td className="px-4 py-4 whitespace-nowrap">
                        <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-blue-100 text-blue-800">
                          <span>{event.region}</span>
                        </span>
                      </td>

                      <td className="px-4 py-4 whitespace-nowrap">
                        <div className="text-xs font-mono text-gray-600">
                          {event.old_instance_id || '-'}
                        </div>
                        {event.old_lifecycle && (
                          <div className="text-[10px] text-gray-500 mt-0.5">
                            <span
                              className={`px-1.5 py-0.5 rounded ${
                                event.old_lifecycle === 'on-demand'
                                  ? 'bg-blue-100 text-blue-700'
                                  : 'bg-yellow-100 text-yellow-700'
                              }`}
                            >
                              {event.old_lifecycle}
                            </span>
                          </div>
                        )}
                      </td>

                      <td className="px-4 py-4 whitespace-nowrap">
                        <div className="text-xs font-medium text-gray-700">
                          {event.old_instance_type || '-'}
                        </div>
                        {event.old_hourly_price !== undefined && (
                          <div className="text-[10px] text-gray-500">
                            ${(event.old_hourly_price || 0).toFixed(4)}
                          </div>
                        )}
                      </td>

                      <td className="px-4 py-4 whitespace-nowrap">
                        <div className="text-xs font-mono text-gray-600">
                          {event.new_instance_id || '-'}
                        </div>
                        {event.new_lifecycle && (
                          <div className="text-[10px] text-gray-500 mt-0.5">
                            <span
                              className={`px-1.5 py-0.5 rounded ${
                                event.new_lifecycle === 'on-demand'
                                  ? 'bg-blue-100 text-blue-700'
                                  : 'bg-yellow-100 text-yellow-700'
                              }`}
                            >
                              {event.new_lifecycle}
                            </span>
                          </div>
                        )}
                      </td>

                      <td className="px-4 py-4 whitespace-nowrap">
                        <div className="text-xs font-medium text-gray-700">
                          {event.new_instance_type || '-'}
                        </div>
                        {event.new_hourly_price !== undefined && (
                          <div className="text-[10px] text-gray-500">
                            ${(event.new_hourly_price || 0).toFixed(4)}
                          </div>
                        )}
                      </td>

                      <td className="px-4 py-4 whitespace-nowrap">
                        {event.estimated_savings && event.estimated_savings > 0 ? (
                          <div className="text-sm font-bold text-green-600">
                            ${(event.estimated_savings || 0).toFixed(4)}
                          </div>
                        ) : (
                          <span className="text-xs text-gray-400">-</span>
                        )}
                      </td>

                      <td className="px-4 py-4">
                        <div className="text-xs text-gray-600 max-w-xs break-words">
                          {event.reason || '-'}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              )}

              {/* Empty State */}
              {!loading && events.length === 0 && (
                <tbody className="divide-y divide-gray-100">
                  <tr>
                    <td colSpan={10} className="px-6 py-12 text-center text-gray-500 bg-white">
                      <div className="flex flex-col items-center justify-center">
                        <i className="fas fa-inbox text-4xl mb-3 text-gray-300"></i>
                        <p className="text-lg font-medium text-gray-900">No Events Found</p>
                        <p className="text-sm text-gray-500 mb-4">
                          Try adjusting your filters or date range.
                        </p>
                        <button
                          onClick={handleResetFilters}
                          className="px-4 py-2 bg-blue-50 text-blue-600 rounded-lg hover:bg-blue-100 transition-colors text-sm font-medium"
                        >
                          <i className="fas fa-undo mr-2"></i> Reset Filters
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              )}
            </table>
          </div>
        </div>
      )}

      {/* Error State */}
      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4">
          <div className="flex">
            <div className="flex-shrink-0">
              <i className="fas fa-exclamation-circle text-red-400"></i>
            </div>
            <div className="ml-3">
              <h3 className="text-sm font-medium text-red-800">Error Loading Events</h3>
              <div className="mt-2 text-sm text-red-700">
                <p>{error.message}</p>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ActionsHistory;
