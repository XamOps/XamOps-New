/**
 * Azure CloudList Page
 *
 * Displays Azure cloud resources grouped by service type with expandable sections.
 */

import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { useAzureCloudListData } from '../hooks/useAzureCloudListData';
import { getAzureServiceIcon, getStateClass } from '../api/azureCloudListApi';
import { AzureServiceGroup, AzureResource } from '../types/azureCloudList';
import { useTopbar } from '../contexts/TopbarContext';
import '../styles/azureCloudList.css';

// Color classes per detail key
const DETAIL_CHIP_CLASSES: Record<string, string> = {
  size: 'bg-blue-50 text-blue-700 border-blue-100',
  sku: 'bg-blue-50 text-blue-700 border-blue-100',
  skuTier: 'bg-blue-50 text-blue-700 border-blue-100',
  skuName: 'bg-blue-50 text-blue-700 border-blue-100',
  diskType: 'bg-blue-50 text-blue-700 border-blue-100',
  snapshotType: 'bg-blue-50 text-blue-700 border-blue-100',
  edition: 'bg-blue-50 text-blue-700 border-blue-100',
  routingMethod: 'bg-blue-50 text-blue-700 border-blue-100',
  gatewayType: 'bg-blue-50 text-blue-700 border-blue-100',
  instanceCount: 'bg-purple-50 text-purple-700 border-purple-100',
  nodeCount: 'bg-purple-50 text-purple-700 border-purple-100',
  containerCount: 'bg-purple-50 text-purple-700 border-purple-100',
  subnetCount: 'bg-purple-50 text-purple-700 border-purple-100',
  frontendCount: 'bg-purple-50 text-purple-700 border-purple-100',
  replicaCount: 'bg-purple-50 text-purple-700 border-purple-100',
  partitionCount: 'bg-purple-50 text-purple-700 border-purple-100',
  throughputUnits: 'bg-purple-50 text-purple-700 border-purple-100',
  ruleCount: 'bg-purple-50 text-purple-700 border-purple-100',
  capacity: 'bg-purple-50 text-purple-700 border-purple-100',
  osType: 'bg-teal-50 text-teal-700 border-teal-100',
  kind: 'bg-teal-50 text-teal-700 border-teal-100',
  apiType: 'bg-teal-50 text-teal-700 border-teal-100',
  k8sVersion: 'bg-green-50 text-green-700 border-green-100',
  version: 'bg-green-50 text-green-700 border-green-100',
  consistency: 'bg-green-50 text-green-700 border-green-100',
  allocationMethod: 'bg-green-50 text-green-700 border-green-100',
  sizeGb: 'bg-amber-50 text-amber-700 border-amber-100',
  bandwidthMbps: 'bg-amber-50 text-amber-700 border-amber-100',
  provider: 'bg-gray-50 text-gray-600 border-gray-200',
  serverName: 'bg-gray-50 text-gray-600 border-gray-200',
  appServicePlan: 'bg-gray-50 text-gray-600 border-gray-200',
  resourceGroup: 'bg-gray-50 text-gray-600 border-gray-200',
  faultDomains: 'bg-gray-50 text-gray-600 border-gray-200',
  updateDomains: 'bg-gray-50 text-gray-600 border-gray-200',
  loginServer: 'bg-gray-50 text-gray-600 border-gray-200',
  hostName: 'bg-gray-50 text-gray-600 border-gray-200',
  ipAddress: 'bg-gray-50 text-gray-600 border-gray-200',
  privateIp: 'bg-gray-50 text-gray-600 border-gray-200',
  addressSpace: 'bg-gray-50 text-gray-600 border-gray-200',
};

// Human-readable labels for detail keys
const DETAIL_LABELS: Record<string, string> = {
  size: 'Size',
  sku: 'SKU',
  skuTier: 'Tier',
  skuName: 'SKU',
  diskType: 'Type',
  snapshotType: 'Type',
  edition: 'Edition',
  routingMethod: 'Routing',
  gatewayType: 'Type',
  instanceCount: 'Instances',
  nodeCount: 'Nodes',
  containerCount: 'Containers',
  subnetCount: 'Subnets',
  frontendCount: 'Frontends',
  replicaCount: 'Replicas',
  partitionCount: 'Partitions',
  throughputUnits: 'TU',
  ruleCount: 'Rules',
  capacity: 'Capacity',
  osType: 'OS',
  kind: 'Kind',
  apiType: 'API',
  k8sVersion: 'k8s',
  version: 'Version',
  consistency: 'Consistency',
  allocationMethod: 'Allocation',
  sizeGb: 'Size',
  bandwidthMbps: 'Bandwidth',
  provider: 'Provider',
  serverName: 'Server',
  appServicePlan: 'Plan',
  resourceGroup: 'RG',
  faultDomains: 'FD',
  updateDomains: 'UD',
  loginServer: 'Login Server',
  hostName: 'Host',
  ipAddress: 'IP',
  privateIp: 'Private IP',
  addressSpace: 'CIDR',
};

const AzureCloudList: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { setActions, clearActions } = useTopbar();

  const [selectedAccountIds, setSelectedAccountIds] = useState<string[]>([]);
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [searchTerm, setSearchTerm] = useState<string>('');

  const { data, loading, error, refresh, lastUpdated } = useAzureCloudListData({
    accountIds: selectedAccountIds,
    autoRefresh: false,
  });

  // Auto-expand all groups when data first loads
  useEffect(() => {
    if (data.length > 0) {
      setExpandedGroups((prev) => {
        const allTypes = new Set(data.map((g) => g.serviceType));
        // Merge with any already-expanded groups (e.g. from URL param)
        prev.forEach((t) => allTypes.add(t));
        return allTypes;
      });
    }
  }, [data]);

  // Initialize account IDs from URL or session storage
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

    // Auto-expand the service clicked from dashboard (but show all resources, no filter)
    const expandValue = urlService || urlFilter || '';
    if (expandValue) {
      setExpandedGroups(new Set([expandValue]));
    }
  }, [searchParams]);

  const handleRefresh = useCallback(() => {
    refresh(true);
  }, [refresh]);

  // Inject topbar actions: Search + Refresh
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <div className="relative">
          <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs pointer-events-none"></i>
          <input
            type="text"
            placeholder="Search resources..."
            defaultValue={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="pl-8 pr-3 py-1.5 text-xs border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 w-48"
          />
        </div>
        {lastUpdated && (
          <span className="text-xs text-gray-400 hidden sm:block">
            <i className="fas fa-clock mr-1"></i>
            {new Date(lastUpdated).toLocaleTimeString()}
          </span>
        )}
        <button
          onClick={handleRefresh}
          disabled={loading}
          className="flex items-center gap-2 px-3 py-1.5 bg-blue-600 text-white text-xs font-bold rounded-lg hover:bg-blue-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={`fas fa-sync-alt text-xs ${loading ? 'animate-spin' : ''}`}></i>
          <span>{loading ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, loading, lastUpdated, handleRefresh, searchTerm]);

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

  const filteredData = useMemo(() => {
    let filtered = data;

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
  }, [data, searchTerm]);

  const totalResources = useMemo(
    () => filteredData.reduce((sum, group) => sum + group.resources.length, 0),
    [filteredData]
  );

  const renderResourceRow = useCallback((resource: AzureResource) => (
    <div
      key={resource.id}
      className="resource-row grid grid-cols-12 items-center p-4 pl-20 text-sm border-b border-gray-200/60 last:border-b-0"
    >
      <div className="col-span-4 font-semibold text-gray-700 flex flex-col justify-center">
        <div className="flex items-center">
          <i className="fas fa-circle text-blue-400 text-xs mr-3 flex-shrink-0"></i>
          {resource.name || 'N/A'}
        </div>
        {resource.details && Object.keys(resource.details).length > 0 && (
          <div className="flex flex-wrap gap-1 mt-1.5 ml-6">
            {Object.entries(resource.details)
              .filter(([, v]) => v && v !== 'Unknown' && v !== '0')
              .slice(0, 4)
              .map(([key, value]) => {
                const chipClass = DETAIL_CHIP_CLASSES[key] ??
                  (key === 'priority' && value.toLowerCase() === 'spot'
                    ? 'bg-orange-50 text-orange-700 border-orange-100'
                    : 'bg-gray-50 text-gray-500 border-gray-200');
                const label = DETAIL_LABELS[key] ? `${DETAIL_LABELS[key]}: ${value}` : value;
                return (
                  <span key={key} className={`px-2 py-0.5 text-xs rounded border font-normal ${chipClass}`}>
                    {label}
                  </span>
                );
              })}
          </div>
        )}
      </div>
      <div className="col-span-4 text-gray-600 text-xs truncate font-mono" title={resource.id || 'N/A'}>
        {resource.id || 'N/A'}
      </div>
      <div className="col-span-2 text-gray-600 flex items-center">
        <i className="fas fa-map-marker-alt text-gray-400 text-xs mr-2"></i>
        {resource.region || 'Global'}
      </div>
      <div className="col-span-2 flex justify-end">
        <span className={`state-badge px-3 py-1.5 text-xs font-semibold rounded-full ${getStateClass(resource.state)}`}>
          {resource.state || 'Unknown'}
        </span>
      </div>
    </div>
  ), []);

  const renderServiceGroup = useCallback((group: AzureServiceGroup) => {
    const isExpanded = expandedGroups.has(group.serviceType);
    const iconPath = getAzureServiceIcon(group.serviceType);

    return (
      <div key={group.serviceType} className="service-group rounded-xl shadow-md border">
        <div
          className="service-header p-5 flex items-center justify-between cursor-pointer"
          onClick={() => toggleGroup(group.serviceType)}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => e.key === 'Enter' && toggleGroup(group.serviceType)}
          aria-expanded={isExpanded}
        >
          <div className="flex items-center space-x-4">
            <i className={`fas fa-chevron-right chevron-icon text-gray-400 text-sm transition-transform ${isExpanded ? 'rotate-90' : ''}`}></i>
            <div className="service-icon bg-gradient-to-br from-blue-500 to-cyan-400 shadow-md">
              <img
                src={`/icons/${iconPath}`}
                alt={group.serviceType}
                className="w-full h-full rounded-xl object-cover"
                onError={(e) => { (e.target as HTMLImageElement).src = '/icons/azure-icon.png'; }}
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

        <div
          className={`resources-container transition-all duration-400 overflow-hidden ${isExpanded ? 'expanded' : ''}`}
          style={{ maxHeight: isExpanded ? '2000px' : '0' }}
        >
          <div className="bg-gray-50/50 border-t">
            {group.resources.map((resource) => renderResourceRow(resource))}
          </div>
        </div>
      </div>
    );
  }, [expandedGroups, toggleGroup, renderResourceRow]);

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
  if (error && data.length === 0) {
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
            <i className="fas fa-redo mr-2"></i>Try Again
          </button>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 flex flex-col h-full overflow-hidden bg-gradient-to-br from-slate-50 via-blue-50/30 to-slate-50">
      <div className="flex-1 overflow-y-auto p-8">
        {/* Resource count summary */}
        {!loading && data.length > 0 && (
          <p className="text-xs text-gray-500 font-medium mb-4">
            {totalResources} resource{totalResources !== 1 ? 's' : ''} across{' '}
            {filteredData.length} service{filteredData.length !== 1 ? 's' : ''}
          </p>
        )}

        <div id="resource-list-container" className="space-y-4">
          {/* Skeleton Loading State */}
          {loading && filteredData.length === 0 && (
            <>
              {[...Array(6)].map((_, i) => (
                <div key={i} className="rounded-xl shadow-md border bg-white overflow-hidden animate-pulse">
                  <div className="p-5 flex items-center justify-between">
                    <div className="flex items-center space-x-4">
                      {/* chevron placeholder */}
                      <div className="w-3 h-3 bg-gray-200 rounded" />
                      {/* icon placeholder */}
                      <div className="w-10 h-10 bg-gray-200 rounded-xl flex-shrink-0" />
                      <div className="space-y-2">
                        <div className={`h-4 bg-gray-200 rounded ${i % 3 === 0 ? 'w-36' : i % 3 === 1 ? 'w-28' : 'w-44'}`} />
                        <div className="h-3 bg-gray-100 rounded w-20" />
                      </div>
                    </div>
                    {/* badge placeholder */}
                    <div className="w-8 h-8 bg-gray-200 rounded-full" />
                  </div>
                </div>
              ))}
            </>
          )}

          {/* Empty State */}
          {!loading && filteredData.length === 0 && (
            <div className="text-center py-20">
              <i className="fas fa-inbox text-gray-400 text-5xl mb-4"></i>
              <p className="text-gray-600 text-lg">
                {searchTerm
                  ? 'No resources match your search criteria.'
                  : 'No resources found for the selected account.'}
              </p>
              {searchTerm && (
                <button
                  onClick={() => setSearchTerm('')}
                  className="mt-4 text-blue-600 hover:text-blue-700 font-semibold"
                >
                  Clear Search
                </button>
              )}
            </div>
          )}

          {filteredData.map((group) => renderServiceGroup(group))}
        </div>
      </div>
    </main>
  );
};

export default AzureCloudList;
