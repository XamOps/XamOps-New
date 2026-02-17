/**
 * Actions History Component
 *
 * Displays AutoSpotting events history with filters, summary cards, and detailed timeline.
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
    startDate: new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
    endDate: new Date().toISOString().split('T')[0],
    eventType: '',
    asgName: '',
  }));

  useEffect(() => {
    if (accountId) {
      loadEvents(filters);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountId]);

  const handleLoadEvents = () => loadEvents(filters);

  const handleResetFilters = () => {
    const defaultFilters = resetFilters();
    setFilters(defaultFilters);
    loadEvents(defaultFilters);
  };

  const handleFilterChange = (key: keyof ActionsHistoryFilters, value: string) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
  };

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
      instance_replacement: 'bg-emerald-100 text-emerald-800',
      spot_interruption: 'bg-orange-100 text-orange-800',
      launch_success: 'bg-green-100 text-green-800',
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

  const getResultBadge = (result: string | undefined) => {
    if (!result) return <span className="text-xs text-gray-400">-</span>;
    const styles: Record<string, { bg: string; icon: string }> = {
      Success:     { bg: 'bg-emerald-100 text-emerald-800', icon: 'fa-check-circle' },
      Interrupted: { bg: 'bg-orange-100 text-orange-800',  icon: 'fa-exclamation-triangle' },
      Enabled:     { bg: 'bg-teal-100 text-teal-800',      icon: 'fa-toggle-on' },
      Disabled:    { bg: 'bg-gray-200 text-gray-600',      icon: 'fa-toggle-off' },
      Failed:      { bg: 'bg-red-100 text-red-800',        icon: 'fa-times-circle' },
    };
    const s = styles[result] ?? { bg: 'bg-gray-100 text-gray-700', icon: 'fa-info-circle' };
    return (
      <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold ${s.bg}`}>
        <i className={`fas ${s.icon} mr-1.5`}></i>
        {result}
      </span>
    );
  };

  if (!accountId) {
    return (
      <div className="bg-white rounded-xl shadow-lg border border-gray-100 p-16 text-center">
        <div className="p-5 bg-gradient-to-br from-gray-50 to-gray-100 rounded-full w-20 h-20 flex items-center justify-center mx-auto mb-4">
          <i className="fas fa-history text-4xl text-gray-400"></i>
        </div>
        <p className="text-lg font-bold text-gray-800">No Account Selected</p>
        <p className="text-sm text-gray-500 mt-2">Select an AWS account from the sidebar to view actions history.</p>
      </div>
    );
  }

  return (
    <div className="actions-history-container space-y-6">

      {/* Compact Filter Bar */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 px-4 py-3 flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-2 mr-2">
          <i className="fas fa-history text-indigo-600 text-sm"></i>
          <span className="font-bold text-gray-800 text-sm">Actions History</span>
        </div>
        <div className="flex flex-wrap items-center gap-2 flex-1">
          <label className="text-xs font-semibold text-gray-500">From</label>
          <input
            type="date"
            value={filters.startDate}
            onChange={(e) => handleFilterChange('startDate', e.target.value)}
            className="px-2.5 py-1.5 border border-gray-200 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent text-xs text-gray-700"
          />
          <label className="text-xs font-semibold text-gray-500">To</label>
          <input
            type="date"
            value={filters.endDate}
            onChange={(e) => handleFilterChange('endDate', e.target.value)}
            className="px-2.5 py-1.5 border border-gray-200 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent text-xs text-gray-700"
          />
          <select
            value={filters.eventType}
            onChange={(e) => handleFilterChange('eventType', e.target.value)}
            className="px-2.5 py-1.5 border border-gray-200 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent text-xs text-gray-700"
          >
            <option value="">All Events</option>
            <option value="instance_replacement">Replacements</option>
            <option value="spot_interruption">Interruptions</option>
            <option value="asg_enabled">ASG Enabled</option>
            <option value="asg_disabled">ASG Disabled</option>
            <option value="launch_failure">Launch Failure</option>
          </select>
          <input
            type="text"
            value={filters.asgName}
            onChange={(e) => handleFilterChange('asgName', e.target.value)}
            placeholder="Filter by ASG..."
            className="px-2.5 py-1.5 border border-gray-200 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent text-xs text-gray-700 min-w-[140px]"
          />
        </div>
        <div className="flex gap-2">
          <button
            onClick={handleLoadEvents}
            disabled={loading}
            className="px-3 py-1.5 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-semibold text-xs flex items-center gap-1.5 shadow-sm"
          >
            <i className={`fas ${loading ? 'fa-spinner fa-spin' : 'fa-search'}`}></i>
            {loading ? 'Loading...' : 'Load'}
          </button>
          <button
            onClick={handleResetFilters}
            className="px-3 py-1.5 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors font-semibold text-xs flex items-center gap-1.5"
          >
            <i className="fas fa-undo"></i>Reset
          </button>
        </div>
      </div>

      {/* Summary Cards — sticky so they remain visible while scrolling the table */}
      <div className="sticky top-0 z-20 bg-gray-50 pb-3">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <div className="bg-white rounded-xl shadow-md border border-gray-100 px-5 py-4 hover:shadow-lg transition-shadow duration-300">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Replacements</p>
                <h3 className="text-2xl font-bold text-emerald-600 mt-1">{summary.total_replacements || 0}</h3>
                <p className="text-xs text-gray-400 mt-0.5">on-demand → spot</p>
              </div>
              <div className="p-2.5 bg-gradient-to-br from-emerald-50 to-emerald-100 rounded-lg">
                <i className="fas fa-sync-alt text-emerald-600 text-lg"></i>
              </div>
            </div>
          </div>

          <div className="bg-white rounded-xl shadow-md border border-gray-100 px-5 py-4 hover:shadow-lg transition-shadow duration-300">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Interruptions</p>
                <h3 className="text-2xl font-bold text-orange-500 mt-1">{summary.total_interruptions || 0}</h3>
                <p className="text-xs text-gray-400 mt-0.5">handled by ASG</p>
              </div>
              <div className="p-2.5 bg-gradient-to-br from-orange-50 to-orange-100 rounded-lg">
                <i className="fas fa-exclamation-triangle text-orange-500 text-lg"></i>
              </div>
            </div>
          </div>

          <div className="bg-white rounded-xl shadow-md border border-gray-100 px-5 py-4 hover:shadow-lg transition-shadow duration-300">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Est. Hourly Savings</p>
                <h3 className="text-2xl font-bold text-purple-600 mt-1">
                  ${(summary.total_estimated_savings || 0).toFixed(4)}
                </h3>
                <p className="text-xs text-gray-400 mt-0.5">from replacements</p>
              </div>
              <div className="p-2.5 bg-gradient-to-br from-purple-50 to-purple-100 rounded-lg">
                <i className="fas fa-dollar-sign text-purple-600 text-lg"></i>
              </div>
            </div>
          </div>

          <div className="bg-white rounded-xl shadow-md border border-gray-100 px-5 py-4 hover:shadow-lg transition-shadow duration-300">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Stability (MTBI)</p>
                <h3 className={`text-2xl font-bold mt-1 ${summary.mtbiColor || 'text-indigo-600'}`}>
                  {summary.mtbi || '-'}
                </h3>
                <p className="text-xs text-gray-400 mt-0.5">mean time b/w interruptions</p>
              </div>
              <div className="p-2.5 bg-gradient-to-br from-indigo-50 to-indigo-100 rounded-lg">
                <i className="fas fa-heartbeat text-indigo-600 text-lg"></i>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Events Table */}
      <div className="bg-white rounded-xl shadow-lg border border-gray-100 overflow-hidden">
        <div className="px-6 py-5 border-b border-gray-200 bg-gradient-to-r from-gray-50 to-white flex items-center justify-between">
          <div>
            <h3 className="font-bold text-gray-800 text-lg">Events Timeline</h3>
            <p className="text-xs text-gray-500 mt-0.5">
              {events.length > 0
                ? `${events.length} event${events.length !== 1 ? 's' : ''} from ${formatDateShort(filters.startDate)} to ${formatDateShort(filters.endDate)}`
                : `${formatDateShort(filters.startDate)} — ${formatDateShort(filters.endDate)}`}
            </p>
          </div>
          {events.length > 0 && (
            <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-semibold bg-indigo-100 text-indigo-700">
              <i className="fas fa-list mr-1.5"></i>{events.length} record{events.length !== 1 ? 's' : ''}
            </span>
          )}
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse min-w-max">
            <thead>
              <tr className="bg-gradient-to-r from-gray-100 to-gray-50 text-gray-700 text-xs uppercase tracking-wider">
                <th className="px-6 py-4 font-bold whitespace-nowrap">Timestamp</th>
                <th className="px-6 py-4 font-bold whitespace-nowrap">Type</th>
                <th className="px-6 py-4 font-bold whitespace-nowrap">Result</th>
                <th className="px-6 py-4 font-bold whitespace-nowrap">ASG Name</th>
                <th className="px-6 py-4 font-bold whitespace-nowrap">Region</th>
                <th className="px-6 py-4 font-bold whitespace-nowrap">Old Instance</th>
                <th className="px-6 py-4 font-bold whitespace-nowrap">Old Type</th>
                <th className="px-6 py-4 font-bold whitespace-nowrap">New Instance</th>
                <th className="px-6 py-4 font-bold whitespace-nowrap">New Type</th>
                <th className="px-6 py-4 font-bold whitespace-nowrap">Impact</th>
                <th className="px-6 py-4 font-bold min-w-[220px]">Reason / Details</th>
              </tr>
            </thead>

            {/* Loading Skeleton */}
            {loading && (
              <tbody className="divide-y divide-gray-100">
                {[1, 2, 3, 4, 5].map((i) => (
                  <tr key={i} className="animate-pulse">
                    {[28, 24, 20, 36, 16, 24, 16, 24, 16, 14, 48].map((w, j) => (
                      <td key={j} className="px-6 py-4">
                        <div className={`h-4 bg-gray-200 rounded w-${w}`}></div>
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            )}

            {/* Events Data */}
            {!loading && events.length > 0 && (
              <tbody className="divide-y divide-gray-100">
                {events.map((event, index) => (
                  <tr key={index} className="hover:bg-blue-50 transition-colors">
                    <td className="px-6 py-4 text-sm text-gray-900 whitespace-nowrap font-medium">
                      {formatDateTime(event.timestamp)}
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap">
                      <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold ${getEventTypeClass(event.event_type)}`}>
                        <i className={`fas mr-1.5 ${getEventTypeIcon(event.event_type)}`}></i>
                        {formatEventType(event.event_type)}
                      </span>
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap">
                      {getResultBadge(event.result)}
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="flex items-center">
                        <i className="fas fa-layer-group text-gray-400 mr-2 text-sm"></i>
                        <span className="text-sm font-semibold text-gray-900 truncate max-w-[140px]" title={event.asg_name}>
                          {event.asg_name || '-'}
                        </span>
                      </div>
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap">
                      <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-blue-100 text-blue-800">
                        <i className="fas fa-map-marker-alt mr-1.5"></i>
                        {event.region}
                      </span>
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="text-xs font-mono text-gray-600">{event.old_instance_id || '-'}</div>
                      {event.old_lifecycle && (
                        <div className="mt-0.5">
                          <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${event.old_lifecycle === 'spot' ? 'bg-yellow-100 text-yellow-700' : 'bg-blue-100 text-blue-700'}`}>
                            {event.old_lifecycle}
                          </span>
                        </div>
                      )}
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap">
                      <span className="text-xs font-semibold text-gray-700">{event.old_instance_type || '-'}</span>
                      {event.old_hourly_price !== undefined && (
                        <div className="text-[10px] text-gray-500">${(event.old_hourly_price || 0).toFixed(4)}</div>
                      )}
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="text-xs font-mono text-gray-600">{event.new_instance_id || '-'}</div>
                      {event.new_lifecycle && (
                        <div className="mt-0.5">
                          <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${event.new_lifecycle === 'spot' ? 'bg-yellow-100 text-yellow-700' : 'bg-blue-100 text-blue-700'}`}>
                            {event.new_lifecycle}
                          </span>
                        </div>
                      )}
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap">
                      <span className="text-xs font-semibold text-gray-700">{event.new_instance_type || '-'}</span>
                      {event.new_hourly_price !== undefined && (
                        <div className="text-[10px] text-gray-500">${(event.new_hourly_price || 0).toFixed(4)}</div>
                      )}
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap">
                      {event.estimated_savings && event.estimated_savings > 0 ? (
                        <span className="text-sm font-bold text-emerald-600">
                          ${(event.estimated_savings).toFixed(4)}
                        </span>
                      ) : (
                        <span className="text-xs text-gray-400">-</span>
                      )}
                    </td>

                    <td className="px-6 py-4">
                      <div className="text-xs text-gray-600 max-w-xs break-words leading-relaxed">
                        {event.reason || '-'}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            )}

            {/* Empty State — no data yet */}
            {!loading && events.length === 0 && (
              <tbody>
                <tr>
                  <td colSpan={11} className="px-6 py-16 bg-white">
                    <div className="flex flex-col items-center justify-center text-center">
                      <div className="p-5 bg-gradient-to-br from-indigo-50 to-blue-100 rounded-full w-20 h-20 flex items-center justify-center mb-5">
                        <i className="fas fa-history text-3xl text-indigo-500"></i>
                      </div>
                      <p className="text-xl font-bold text-gray-800 mb-2">No Events Yet</p>
                      <p className="text-sm text-gray-500 max-w-md mb-6">
                        Events will appear here once AutoSpotting starts acting on your ASGs. Try these to generate your first event:
                      </p>
                      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 max-w-2xl w-full text-left mb-6">
                        <div className="bg-teal-50 border border-teal-200 rounded-xl p-4">
                          <div className="flex items-center mb-2">
                            <i className="fas fa-toggle-on text-teal-600 text-lg mr-2"></i>
                            <span className="text-sm font-bold text-teal-800">Enable an ASG</span>
                          </div>
                          <p className="text-xs text-teal-700">Go to Dashboard and enable AutoSpotting on any ASG. An "ASG Enabled" event will be logged immediately.</p>
                        </div>
                        <div className="bg-emerald-50 border border-emerald-200 rounded-xl p-4">
                          <div className="flex items-center mb-2">
                            <i className="fas fa-sync-alt text-emerald-600 text-lg mr-2"></i>
                            <span className="text-sm font-bold text-emerald-800">Wait for Swap</span>
                          </div>
                          <p className="text-xs text-emerald-700">After enabling, ASG will gradually replace On-Demand instances with Spot. Each swap is logged as an "Instance Replacement".</p>
                        </div>
                        <div className="bg-orange-50 border border-orange-200 rounded-xl p-4">
                          <div className="flex items-center mb-2">
                            <i className="fas fa-exclamation-triangle text-orange-500 text-lg mr-2"></i>
                            <span className="text-sm font-bold text-orange-800">Interruptions</span>
                          </div>
                          <p className="text-xs text-orange-700">If AWS reclaims a Spot instance, a "Spot Interruption" event is logged. ASG auto-replaces it within seconds.</p>
                        </div>
                      </div>
                      <div className="flex gap-3">
                        <button
                          onClick={handleResetFilters}
                          className="px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg text-sm font-semibold transition-colors shadow-sm hover:shadow-md"
                        >
                          <i className="fas fa-undo mr-2"></i>Reset Filters
                        </button>
                        <button
                          onClick={handleLoadEvents}
                          className="px-5 py-2.5 bg-gray-100 hover:bg-gray-200 text-gray-700 rounded-lg text-sm font-semibold transition-colors"
                        >
                          <i className="fas fa-refresh mr-2"></i>Refresh
                        </button>
                      </div>
                    </div>
                  </td>
                </tr>
              </tbody>
            )}
          </table>
        </div>
      </div>

      {/* Error State */}
      {error && (
        <div className="bg-red-50 border border-red-200 rounded-xl p-5 shadow-sm">
          <div className="flex">
            <div className="flex-shrink-0">
              <i className="fas fa-exclamation-circle text-red-500 text-lg"></i>
            </div>
            <div className="ml-3">
              <h3 className="text-sm font-bold text-red-800">Error Loading Events</h3>
              <p className="mt-1 text-sm text-red-700">{error.message}</p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ActionsHistory;
