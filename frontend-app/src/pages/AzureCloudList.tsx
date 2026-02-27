/**
 * Azure CloudList Page
 *
 * Converted from: frontend-app/legacy/azure_cloudlist.html
 *
 * Displays Azure cloud resources grouped by service type with expandable sections.
 * All backend connections and API endpoints remain unchanged.
 *
 * Features:
 * - Expandable service groups
 * - Resource filtering by service type, region, and state
 * - Real-time refresh functionality
 * - Provider-aware routing (Azure accounts only)
 * - Maintains all original functionality with improved UI
 */

import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { useAzureCloudListData } from '../hooks/useAzureCloudListData';
import { getAzureServiceIcon, getStateClass } from '../api/azureCloudListApi';
import { AzureServiceGroup, AzureResource } from '../types/azureCloudList';
import '../styles/azureCloudList.css';

const AzureCloudList: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  // Get account IDs from URL or session storage
  const [selectedAccountIds, setSelectedAccountIds] = useState<string[]>([]);

  // Expanded groups state
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  // Filters
  const [serviceFilter, setServiceFilter] = useState<string>('');
  const [searchTerm, setSearchTerm] = useState<string>('');

  // Fetch data using custom hook
  const { data, loading, error, refresh, lastUpdated } = useAzureCloudListData({
    accountIds: selectedAccountIds,
    autoRefresh: false,
  });

  /**
   * Initialize account IDs from URL or session storage
   */
  useEffect(() => {
    const urlAccountId = searchParams.get('accountId');
    const urlAccountIds = searchParams.get('accountIds');
    const urlService = searchParams.get('service');
    const urlFilter = searchParams.get('filter');

    let accountIds: string[] = [];

    if (urlAccountIds) {
      accountIds = urlAccountIds.split(',');
    } else if (urlAccountId) {
      accountIds = [urlAccountId];
    } else {
      // Load from session storage
      const storedIds = sessionStorage.getItem('selectedAccountIds');
      if (storedIds) {
        try {
          accountIds = JSON.parse(storedIds);
        } catch (err) {
          console.error('Failed to parse selectedAccountIds from session storage:', err);
        }
      }
    }

    if (accountIds.length > 0) {
      setSelectedAccountIds(accountIds);
    }

    // Set service filter from URL
    if (urlService || urlFilter) {
      const filterValue = urlService || urlFilter || '';
      setServiceFilter(filterValue);
      // Auto-expand the filtered service
      if (filterValue) {
        setExpandedGroups(new Set([filterValue]));
      }
    }
  }, [searchParams]);

  /**
   * Toggle group expansion
   */
  const toggleGroup = useCallback((serviceType: string) => {
    setExpandedGroups((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(serviceType)) {
        newSet.delete(serviceType);
      } else {
        newSet.add(serviceType);
      }
      return newSet;
    });
  }, []);

  /**
   * Handle refresh button click
   */
  const handleRefresh = useCallback(() => {
    refresh(true);
  }, [refresh]);

  /**
   * Filter data based on service filter and search term
   */
  const filteredData = useMemo(() => {
    let filtered = data;

    // Filter by service type
    if (serviceFilter) {
      filtered = filtered.filter((group) =>
        group.serviceType.toLowerCase().includes(serviceFilter.toLowerCase())
      );
    }

    // Filter by search term (searches in resource names and IDs)
    if (searchTerm) {
      filtered = filtered
        .map((group) => ({
          ...group,
          resources: group.resources.filter(
            (resource) =>
              resource.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
              resource.id.toLowerCase().includes(searchTerm.toLowerCase()) ||
              resource.region.toLowerCase().includes(searchTerm.toLowerCase()) ||
              resource.state.toLowerCase().includes(searchTerm.toLowerCase())
          ),
        }))
        .filter((group) => group.resources.length > 0);
    }

    return filtered;
  }, [data, serviceFilter, searchTerm]);

  /**
   * Calculate total resources
   */
  const totalResources = useMemo(() => {
    return filteredData.reduce((sum, group) => sum + group.resources.length, 0);
  }, [filteredData]);

  /**
   * Render a single resource row
   */
  const renderResourceRow = useCallback((resource: AzureResource) => {
    return (
      <div
        key={resource.id}
        className="resource-row grid grid-cols-12 items-center p-4 pl-20 text-sm border-b border-gray-200/60 last:border-b-0"
      >
        <div className="col-span-4 font-semibold text-gray-700 flex items-center">
          <i className="fas fa-circle text-blue-400 text-xs mr-3"></i>
          {resource.name || 'N/A'}
        </div>
        <div
          className="col-span-4 text-gray-600 text-xs truncate font-mono"
          title={resource.id || 'N/A'}
        >
          {resource.id || 'N/A'}
        </div>
        <div className="col-span-2 text-gray-600 flex items-center">
          <i className="fas fa-map-marker-alt text-gray-400 text-xs mr-2"></i>
          {resource.region || 'Global'}
        </div>
        <div className="col-span-2 flex justify-end">
          <span
            className={`state-badge px-3 py-1.5 text-xs font-semibold rounded-full ${getStateClass(
              resource.state
            )}`}
          >
            {resource.state || 'Unknown'}
          </span>
        </div>
      </div>
    );
  }, []);

  /**
   * Render a service group
   */
  const renderServiceGroup = useCallback(
    (group: AzureServiceGroup) => {
      const isExpanded = expandedGroups.has(group.serviceType);
      const iconPath = getAzureServiceIcon(group.serviceType);

      return (
        <div key={group.serviceType} className="service-group rounded-xl shadow-md border">
          {/* Service Header */}
          <div
            className="service-header p-5 flex items-center justify-between cursor-pointer"
            onClick={() => toggleGroup(group.serviceType)}
          >
            <div className="flex items-center space-x-4">
              <i
                className={`fas fa-chevron-right chevron-icon text-gray-400 text-sm transition-transform ${
                  isExpanded ? 'rotate-90' : ''
                }`}
              ></i>
              <div className="service-icon bg-gradient-to-br from-blue-500 to-cyan-400 shadow-md">
                <img
                  src={`/icons/${iconPath}`}
                  alt={group.serviceType}
                  className="w-full h-full rounded-xl object-cover"
                  onError={(e) => {
                    (e.target as HTMLImageElement).src = '/icons/default-icon.png';
                  }}
                />
              </div>
              <div>
                <h3 className="font-bold text-gray-800 text-lg">{group.serviceType}</h3>
                <p className="text-sm text-gray-500">
                  {group.resources.length} resource{group.resources.length !== 1 ? 's' : ''}
                </p>
              </div>
            </div>
            <div className="resource-count-badge">{group.resources.length}</div>
          </div>

          {/* Resources Container */}
          <div
            className={`resources-container transition-all duration-400 overflow-hidden ${
              isExpanded ? 'expanded' : ''
            }`}
            style={{
              maxHeight: isExpanded ? '2000px' : '0',
            }}
          >
            <div className="bg-gray-50/50 border-t">
              {group.resources.map((resource) => renderResourceRow(resource))}
            </div>
          </div>
        </div>
      );
    },
    [expandedGroups, toggleGroup, renderResourceRow]
  );

  // Handle empty account selection
  if (selectedAccountIds.length === 0) {
    return (
      <main className="flex-1 flex items-center justify-center p-8 bg-white/95">
        <div className="text-center py-20">
          <div className="w-20 h-20 bg-gradient-to-br from-blue-500 to-cyan-400 rounded-xl flex items-center justify-center mx-auto mb-6">
            <i className="fab fa-windows text-white text-3xl"></i>
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">No Azure Account Selected</h2>
          <p className="text-gray-600 mb-8">
            Please select an Azure subscription from the account selector to view your resources.
          </p>
          <button
            onClick={() => navigate('/azure-dashboard')}
            className="px-6 py-3 bg-gradient-to-r from-blue-500 to-cyan-400 text-white rounded-lg hover:shadow-lg transition-all"
          >
            Go to Azure Dashboard
          </button>
        </div>
      </main>
    );
  }

  // Handle error state
  if (error && !data) {
    return (
      <main className="flex-1 flex items-center justify-center p-8 bg-white/95">
        <div className="text-center py-20">
          <i className="fas fa-exclamation-triangle text-red-600 text-5xl mb-4"></i>
          <p className="text-red-600 text-lg font-semibold mb-2">Error loading resources</p>
          <p className="text-gray-600 mb-6">{error.message}</p>
          <button
            onClick={handleRefresh}
            className="bg-blue-600 hover:bg-blue-700 text-white px-6 py-3 rounded-lg transition-colors"
          >
            <i className="fas fa-redo mr-2"></i>
            Try Again
          </button>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 flex flex-col h-full overflow-hidden bg-gradient-to-br from-slate-50 via-blue-50/30 to-slate-50">
      {/* Action Toolbar */}
      <div className="flex-shrink-0 bg-white/60 backdrop-blur-md border-b border-gray-200/50 px-8 py-3">
        <div className="flex items-center justify-between">
          <div className="text-sm text-gray-600">
            {loading ? (
              <span>Loading...</span>
            ) : (
              <>
                {totalResources} resource{totalResources !== 1 ? 's' : ''} across{' '}
                {filteredData.length} service{filteredData.length !== 1 ? 's' : ''}
              </>
            )}
          </div>
          <div className="flex items-center space-x-3">
            {/* Search Input */}
            <div className="relative">
              <input
                type="text"
                placeholder="Search resources..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="pl-9 pr-4 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent w-56"
              />
              <i className="fas fa-search absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400 text-xs"></i>
            </div>

            {/* Last Updated */}
            {lastUpdated && (
              <div className="text-xs text-gray-500 flex items-center">
                <i className="fas fa-clock mr-1"></i>
                {new Date(lastUpdated).toLocaleTimeString()}
              </div>
            )}

            {/* Refresh Button */}
            <button
              onClick={handleRefresh}
              disabled={loading}
              className="group flex items-center space-x-2 px-4 py-2 text-sm font-semibold text-blue-600 hover:text-white bg-white hover:bg-gradient-to-r hover:from-blue-500 hover:to-cyan-400 rounded-lg border border-blue-200 hover:border-transparent transition-all duration-200 hover:shadow-md disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <i
                className={`fas fa-sync-alt transition-transform text-sm ${
                  loading ? 'animate-spin' : 'group-hover:rotate-180'
                }`}
              ></i>
              <span>{loading ? 'Refreshing...' : 'Refresh'}</span>
            </button>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-8">
        <div id="resource-list-container" className="space-y-4">
          {/* Loading State */}
          {loading && filteredData.length === 0 && (
            <div className="text-center py-20">
              <i className="fas fa-spinner fa-spin text-4xl text-blue-600 mb-4"></i>
              <p className="text-gray-600 text-lg">Loading Azure resources...</p>
            </div>
          )}

          {/* Empty State */}
          {!loading && filteredData.length === 0 && (
            <div className="text-center py-20">
              <i className="fas fa-inbox text-gray-400 text-5xl mb-4"></i>
              <p className="text-gray-600 text-lg">
                {searchTerm || serviceFilter
                  ? 'No resources match your search criteria.'
                  : 'No resources found for the selected account.'}
              </p>
              {(searchTerm || serviceFilter) && (
                <button
                  onClick={() => {
                    setSearchTerm('');
                    setServiceFilter('');
                  }}
                  className="mt-4 text-blue-600 hover:text-blue-700 font-semibold"
                >
                  Clear Filters
                </button>
              )}
            </div>
          )}

          {/* Service Groups */}
          {filteredData.map((group) => renderServiceGroup(group))}
        </div>
      </div>
    </main>
  );
};

export default AzureCloudList;
