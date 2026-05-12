import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { useLocation } from 'react-router-dom';
import { GcpServiceGroup, GcpCloudListPageData } from '../types/gcpCloudList';
import { useTopbar } from '../contexts/TopbarContext';
import { AnimatedItem } from '../components/Shared/AnimatedList';
import clsx from 'clsx';
import '../styles/gcpCloudList.css';

const GcpCloudListPage: React.FC = () => {
  const location = useLocation();

  // Parse URL parameters
  const searchParams = new URLSearchParams(location.search);
  const urlAccountIds = searchParams.get('accountIds') || '';

  // State management
  const [allGroups, setAllGroups] = useState<GcpServiceGroup[]>([]);
  const [, setTotalResources] = useState(0);
  const [totalServices, setTotalServices] = useState(0);
  const [hasNextPage, setHasNextPage] = useState(true);
  const [currentPage, setCurrentPage] = useState(0);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [selectedAccountId, setSelectedAccountId] = useState<string | null>(null);

  // Filter states
  const [regionFilter, setRegionFilter] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  // UI states
  const [, setLastUpdated] = useState<string>('');
  const [, setUpdateStatus] = useState('Ready');

  const servicesPerPage = 10;

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();

  // Region display name mapping
  const getRegionDisplayName = (regionId: string): string => {
    const regionMap: Record<string, string> = {
      'us-east1': 'US East (N. Virginia)',
      'us-east4': 'US East (S. Carolina)',
      'us-central1': 'US Central (Iowa)',
      'us-west1': 'US West (Oregon)',
      'us-west2': 'US West (Los Angeles)',
      'us-west3': 'US West (Salt Lake City)',
      'us-west4': 'US West (Las Vegas)',
      'europe-west1': 'Europe (Belgium)',
      'europe-west2': 'Europe (London)',
      'europe-west3': 'Europe (Frankfurt)',
      'europe-west4': 'Europe (Netherlands)',
      'europe-west6': 'Europe (Zürich)',
      'europe-north1': 'Europe (Finland)',
      'europe-southwest1': 'Europe (Madrid)',
      'europe-central2': 'Europe (Warsaw)',
      'asia-east1': 'Asia Pacific (Taiwan)',
      'asia-east2': 'Asia Pacific (Hong Kong)',
      'asia-northeast1': 'Asia Pacific (Tokyo)',
      'asia-northeast2': 'Asia Pacific (Osaka)',
      'asia-northeast3': 'Asia Pacific (Seoul)',
      'asia-south1': 'Asia Pacific (Mumbai)',
      'asia-south2': 'Asia Pacific (Delhi)',
      'asia-southeast1': 'Asia Pacific (Singapore)',
      'asia-southeast2': 'Asia Pacific (Jakarta)',
      'australia-southeast1': 'Australia (Sydney)',
      'australia-southeast2': 'Australia (Melbourne)',
      'southamerica-east1': 'South America (São Paulo)',
      'southamerica-west1': 'South America (Santiago)',
      'global': 'Global',
    };
    return regionMap[regionId] || regionId.charAt(0).toUpperCase() + regionId.slice(1);
  };

  // Get service icon
  const getServiceIcon = (serviceType: string): string => {
    const iconMap: Record<string, string> = {
      'Compute Engine': 'gcp-compute-engine.png',
      'Cloud Run': 'gcp-cloud-run.png',
      'Cloud Functions': 'gcp-cloud-functions.png',
      'Kubernetes Engine': 'gcp-gke.png',
      'App Engine': 'gcp-app-engine.png',
      'Cloud Storage': 'gcp-cloud-storage.png',
      'Cloud SQL': 'gcp-cloud-sql.png',
      'VPC Network': 'gcp-vpc.png',
      'Cloud DNS': 'gcp-cloud-dns.png',
      'Load Balancer': 'gcp-load-balancer.png',
      'Firewall Rule': 'gcp-firewall.png',
      'Cloud NAT': 'gcp-nat.png',
      'Cloud IAM': 'gcp-iam.png',
      'Cloud KMS': 'gcp-kms.png',
      'Secret Manager': 'gcp-secret-manager.png',
      'Cloud Armor': 'gcp-cloud-armor.png',
      'Artifact Registry': 'gcp-artifact-registry.png',
      'Cloud Build': 'gcp-cloud-build.png',
      'BigQuery Dataset': 'gcp-bigquery.png',
      'Pub/Sub Topic': 'gcp-pubsub.png',
      'Pub/Sub Subscription': 'gcp-pubsub.png',
      'Dataflow Job': 'gcp-dataflow.png',
      'Dataproc Cluster': 'gcp-dataproc.png',
      'Dataplex': 'gcp-dataplex.png',
      'Logging Bucket': 'gcp-logging.png',
      'Monitoring Alert': 'gcp-monitoring.png',
      'Cloud Scheduler': 'gcp-cloud-scheduler.png',
      'Uptime Check': 'gcp-monitoring.png',
      'Vertex AI Endpoint': 'gcp-vertex-ai.png',
      'Vertex AI Model': 'gcp-vertex-ai.png',
      'API Gateway': 'gcp-api-gateway.png',
      'BigQuery Reservation': 'gcp-bigquery.png',
      'Cloud Dataproc': 'gcp-dataproc.png',
      'OS Config': 'gcp-compute-engine.png',
    };
    return iconMap[serviceType] || 'gcp-icon.png';
  };

  // Get status class
  const getStateClass = (state: string): string => {
    const s = (state || '').toLowerCase();
    if (['running', 'available', 'active', 'in-use', 'ready', 'ok'].includes(s)) {
      return 'gcp-status-success';
    }
    if (['stopped', 'terminated', 'deleting', 'inactive', 'error', 'disabled'].includes(s)) {
      return 'gcp-status-error';
    }
    if (['pending', 'stopping', 'provisioning', 'reconciling'].includes(s)) {
      return 'gcp-status-warning';
    }
    return 'gcp-status-default';
  };

  // Format status text
  const formatStatusText = (status: string): string => {
    if (!status) return 'Unknown';
    const lower = status.toLowerCase().replace(/_/g, ' ');
    return lower.charAt(0).toUpperCase() + lower.slice(1);
  };

  // Load resources from API
  const loadResources = useCallback(async (forceRefresh: boolean = false, isPaginatedLoad: boolean = false) => {
    const storedAccountIds = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
    const accountId = urlAccountIds || (storedAccountIds.length > 0 ? storedAccountIds[0] : null);

    setSelectedAccountId(accountId);

    if (!accountId) {
      setUpdateStatus('Please select an account.');
      return;
    }

    if (!isPaginatedLoad) {
      setIsRefreshing(true);
      setUpdateStatus('Updating...');
    }

    try {
      const apiUrl = `/api/xamops/gcp/cloudlist/services-paginated?accountId=${accountId}&page=${currentPage}&servicesPerPage=${servicesPerPage}&forceRefresh=${forceRefresh}`;
      const response = await fetch(apiUrl, { credentials: 'include' });

      if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

      const pageData: GcpCloudListPageData = await response.json();

      if (!pageData || (pageData.services.length === 0 && !isPaginatedLoad)) {
        setAllGroups([]);
        setTotalResources(0);
        return;
      }

      const newServiceGroups = pageData.services.map(s => ({
        serviceType: s.serviceType,
        resourceCount: s.resourceCount,
        resources: s.resources
      }));

      if (isPaginatedLoad) {
        setAllGroups(prev => [...prev, ...newServiceGroups]);
      } else {
        setAllGroups(newServiceGroups);
      }

      setHasNextPage(pageData.hasNext);
      setTotalServices(pageData.totalServices);
      setTotalResources(pageData.totalResources);

      const now = new Date().toLocaleTimeString();
      setLastUpdated(now);
      setUpdateStatus(`Updated: ${now}`);
    } catch (error) {
      console.error('Error fetching resources:', error);
      setUpdateStatus('Update failed');
      if (!isPaginatedLoad) {
        setAllGroups([]);
        setTotalResources(0);
      }
    } finally {
      if (!isPaginatedLoad) {
        setIsRefreshing(false);
      }
    }
  }, [currentPage, urlAccountIds, servicesPerPage]);

  // Initial load
  useEffect(() => {
    setCurrentPage(0);
    setAllGroups([]);
    setTotalResources(0);
    setTotalServices(0);
    setHasNextPage(true);
    loadResources(false, false);
  }, [selectedAccountId]);

  // Listen for account changes
  useEffect(() => {
    const handleAccountChanged = (event: CustomEvent) => {
      console.log('[GCP CloudList] Account changed event received:', event.detail);
      const detail = event.detail;

      if (detail.accountIds) {
        setSelectedAccountId(detail.accountIds[0]);
      } else if (detail.accountId) {
        setSelectedAccountId(detail.accountId);
      }
    };

    window.addEventListener('account-changed', handleAccountChanged as EventListener);

    return () => {
      window.removeEventListener('account-changed', handleAccountChanged as EventListener);
    };
  }, []);

  // Handle refresh
  const handleRefresh = () => {
    if (!isRefreshing) {
      setCurrentPage(0);
      setAllGroups([]);
      loadResources(true, false);
    }
  };

  // Handle load more
  const handleLoadMore = async () => {
    if (isLoadingMore || !hasNextPage) return;

    setIsLoadingMore(true);
    setCurrentPage(prev => prev + 1);

    await loadResources(false, true);

    setIsLoadingMore(false);
  };

  // Get available regions
  const availableRegions = useMemo(() => {
    const regions = [...new Set(allGroups.flatMap(g => g.resources.map(r => r.location)).filter(Boolean))];
    return regions.sort();
  }, [allGroups]);

  // Get filtered groups
  const filteredGroups = useMemo(() => {
    return allGroups.map(group => {
      const matchingResources = group.resources.filter(res => {
        const regionMatch = !regionFilter || (res.location && res.location === regionFilter);
        const searchMatch = !searchInput ||
          (res.name && res.name.toLowerCase().includes(searchInput.toLowerCase())) ||
          (res.id && res.id.toLowerCase().includes(searchInput.toLowerCase()));
        return regionMatch && searchMatch;
      });
      return { ...group, resources: matchingResources };
    }).filter(group => group.resources.length > 0);
  }, [allGroups, regionFilter, searchInput]);

  // Get displayed resource count
  const displayedResourceCount = useMemo(() => {
    return filteredGroups.reduce((acc, group) => acc + group.resources.length, 0);
  }, [filteredGroups]);

  // Toggle group expansion
  const toggleGroup = (serviceType: string) => {
    setExpandedGroups(prev => {
      const newSet = new Set(prev);
      if (newSet.has(serviceType)) {
        newSet.delete(serviceType);
      } else {
        newSet.add(serviceType);
      }
      return newSet;
    });
  };

  // Export data
  const exportData = () => {
    const headers = ['ID', 'Name', 'Type', 'Region', 'Status', 'Tags'];
    const rows = filteredGroups.flatMap(group =>
      group.resources.map(res => [
        `"${res.id || ''}"`,
        `"${res.name || ''}"`,
        `"${res.type || ''}"`,
        `"${res.location || ''}"`,
        `"${res.status || ''}"`,
        `"${JSON.stringify(res.tags || {}).replace(/"/g, '""')}"`
      ])
    );

    const csvContent = 'data:text/csv;charset=utf-8,' +
      headers.join(',') + '\n' +
      rows.map(e => e.join(',')).join('\n');

    const encodedUri = encodeURI(csvContent);
    const link = document.createElement('a');
    link.setAttribute('href', encodedUri);
    link.setAttribute('download', 'gcp_cloudlist_export.csv');
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  // Topbar actions setup
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={exportData}
          className="flex items-center gap-2 px-4 py-2 bg-white text-emerald-600 text-xs font-bold rounded-xl border border-emerald-100 shadow-sm hover:shadow-md transition-all"
        >
          <i className="fas fa-file-excel"></i>
          <span>Export Data</span>
        </button>

        <button
          onClick={handleRefresh}
          disabled={isRefreshing}
          className={clsx(
            'flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95',
            isRefreshing && 'cursor-not-allowed'
          )}
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing]);

  // Empty state
  if (!selectedAccountId) {
    return (
      <div className="gcp-cloudlist-container">
        <div className="gcp-cloudlist-empty-state">
          <div className="gcp-empty-icon">
            <i className="fas fa-mouse-pointer"></i>
          </div>
          <h3>No Account Selected</h3>
          <p>Please use the sidebar to select a GCP project to view its resources.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="gcp-cloudlist-container">
      {/* Filters Section */}
      <div className="gcp-cloudlist-filters">
        <div className="gcp-filter-controls">
          <div className="gcp-filter-item">
            <div className="gcp-filter-icon">
              <i className="fas fa-globe"></i>
            </div>
            <select
              value={regionFilter}
              onChange={(e) => setRegionFilter(e.target.value)}
              className="gcp-filter-select"
              disabled={isRefreshing}
            >
              <option value="">All Regions</option>
              {availableRegions.map(regionId => (
                <option key={regionId} value={regionId}>
                  {getRegionDisplayName(regionId)}
                </option>
              ))}
            </select>
          </div>

          <div className="gcp-filter-item flex-1">
            <div className="gcp-filter-icon">
              <i className="fas fa-search"></i>
            </div>
            <input
              type="text"
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              placeholder="Search resources by name or ID..."
              className="gcp-filter-input"
            />
          </div>
        </div>

        <div className="gcp-filter-actions">
          <p className="gcp-resource-count">
            Showing {displayedResourceCount} resources from {allGroups.length} services (Top {totalServices})
          </p>
        </div>
      </div>

      {/* Resource List */}
      <div className="gcp-cloudlist-content">
        {isRefreshing && allGroups.length === 0 ? (
          <div className="gcp-loading-skeleton">
            {[1, 2, 3, 4, 5].map(i => (
              <div key={i} className="gcp-service-group animate-pulse">
                <div className="gcp-service-group-header bg-gray-100">
                  <div className="gcp-service-group-header-left">
                    <div className="w-4 h-4 bg-gray-300 rounded"></div>
                    <div className="gcp-service-icon bg-gray-200 w-10 h-10 rounded-lg"></div>
                    <div className="gcp-service-info">
                      <div className="h-5 bg-gray-300 rounded w-32 mb-1"></div>
                      <div className="h-3 bg-gray-200 rounded w-20"></div>
                    </div>
                  </div>
                  <div className="gcp-resource-badge bg-gray-300 w-12 h-6 rounded-full"></div>
                </div>
              </div>
            ))}
          </div>
        ) : allGroups.length === 0 ? (
          <div className="gcp-cloudlist-empty-state">
            <div className="gcp-empty-icon">
              <i className="fas fa-search-minus"></i>
            </div>
            <h3>No Resources Found</h3>
            <p>No GCP resources were found for the selected project.</p>
          </div>
        ) : (
          <>
            <div className="gcp-service-groups">
              {filteredGroups.map((group, index) => (
                <AnimatedItem key={group.serviceType} index={index} delay={index * 0.04}>
                <div className="gcp-service-group">
                  <div
                    className="gcp-service-group-header"
                    onClick={() => toggleGroup(group.serviceType)}
                  >
                    <div className="gcp-service-group-header-left">
                      <i className={`fas fa-chevron-right gcp-chevron ${expandedGroups.has(group.serviceType) ? 'expanded' : ''}`}></i>
                      <div className="gcp-service-icon">
                        <img src={`/icons/${getServiceIcon(group.serviceType)}`} alt={group.serviceType} />
                      </div>
                      <div className="gcp-service-info">
                        <h3>{group.serviceType}</h3>
                        <p>GCP Service</p>
                      </div>
                    </div>
                    <div className="gcp-resource-badge">
                      {group.resourceCount || group.resources.length}
                    </div>
                  </div>

                  {expandedGroups.has(group.serviceType) && (
                    <div className="gcp-resources-list">
                      {group.resources.map((resource) => (
                        <div key={resource.id} className="gcp-resource-row">
                          <div className="gcp-resource-col gcp-resource-name">
                            <div className="gcp-resource-info-block">
                              <p className="gcp-resource-title">{resource.name || 'N/A'}</p>
                              <p className="gcp-resource-id">{resource.id || 'N/A'}</p>
                            </div>
                          </div>
                          <div className="gcp-resource-col gcp-resource-location">
                            <i className="fas fa-map-marker-alt"></i>
                            <span>{getRegionDisplayName(resource.location) || 'Global'}</span>
                          </div>
                          <div className="gcp-resource-col gcp-resource-status">
                            <span className={`gcp-status-badge ${getStateClass(resource.status)}`}>
                              {formatStatusText(resource.status)}
                            </span>
                          </div>
                          <div className="gcp-resource-col gcp-resource-actions">
                            <i className="fas fa-external-link-alt"></i>
                            <span>No Extra Details</span>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
                </AnimatedItem>
              ))}
            </div>

            {/* Pagination */}
            {hasNextPage && (
              <div className="gcp-pagination">
                <button
                  onClick={handleLoadMore}
                  disabled={isLoadingMore}
                  className="gcp-load-more-btn"
                >
                  {isLoadingMore ? (
                    <>
                      <div className="loading-spinner"></div>
                      Loading...
                    </>
                  ) : (
                    'Load More'
                  )}
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default GcpCloudListPage;
