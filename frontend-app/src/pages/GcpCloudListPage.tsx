import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { useLocation } from 'react-router-dom';
import { GcpServiceGroup, GcpCloudListPageData } from '../types/gcpCloudList';
import { useTopbar } from '../contexts/TopbarContext';
import { AnimatedItem } from '../components/Shared/AnimatedList';
import Skeleton from '../components/Shared/Skeleton';

const GcpCloudListPage: React.FC = () => {
  const location = useLocation();

  const searchParams = new URLSearchParams(location.search);
  const urlAccountIds = searchParams.get('accountIds') || '';

  const [allGroups, setAllGroups] = useState<GcpServiceGroup[]>([]);
  const [, setTotalResources] = useState(0);
  const [totalServices, setTotalServices] = useState(0);
  const [hasNextPage, setHasNextPage] = useState(true);
  const [currentPage, setCurrentPage] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [selectedAccountId, setSelectedAccountId] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState('Ready');
  const [statusColor, setStatusColor] = useState('green');

  const [regionFilter, setRegionFilter] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [expandedResources, setExpandedResources] = useState<Set<string>>(new Set());

  const servicesPerPage = 10;

  const { setActions, clearActions } = useTopbar();

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

  const getStateBadge = (status: string): string => {
    const s = (status || '').toLowerCase();
    if (['running', 'available', 'active', 'in-use', 'ready', 'ok'].includes(s))
      return 'bg-[#16A34A14] text-[#16A34A] border border-[#16A34A20]';
    if (['stopped', 'terminated', 'deleting', 'inactive', 'error', 'disabled'].includes(s))
      return 'bg-[#DC262614] text-[#DC2626] border border-[#DC262620]';
    if (['pending', 'stopping', 'provisioning', 'reconciling'].includes(s))
      return 'bg-[#D9770614] text-[#D97706] border border-[#D9770620]';
    return 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border border-[rgba(0,0,0,0.08)]';
  };

  const formatStatusText = (status: string): string => {
    if (!status) return 'Unknown';
    const lower = status.toLowerCase().replace(/_/g, ' ');
    return lower.charAt(0).toUpperCase() + lower.slice(1);
  };

  // `pageOverride` lets a caller name the page to fetch. Without it a paginated
  // load would use the `currentPage` captured in this closure, which is still
  // the previous page when the caller has just bumped the state — refetching
  // and re-appending the page that is already on screen.
  const loadResources = useCallback(async (forceRefresh: boolean = false, isPaginatedLoad: boolean = false, pageOverride?: number) => {
    const storedAccountIds = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
    const accountId = urlAccountIds || (storedAccountIds.length > 0 ? storedAccountIds[0] : null);

    setSelectedAccountId(accountId);

    if (!accountId) {
      setStatusMessage('Please select an account');
      setStatusColor('yellow');
      setIsLoading(false);
      return;
    }

    if (!isPaginatedLoad) {
      setIsLoading(true);
      setIsRefreshing(forceRefresh);
      setStatusMessage(forceRefresh ? 'Fetching resources from GCP...' : 'Loading resources...');
      setStatusColor('green');
    }

    try {
      const pageToLoad = pageOverride ?? currentPage;
      const apiUrl = `/api/xamops/gcp/cloudlist/services-paginated?accountId=${accountId}&page=${pageToLoad}&servicesPerPage=${servicesPerPage}&forceRefresh=${forceRefresh}`;
      const response = await fetch(apiUrl, { credentials: 'include' });

      if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

      const pageData: GcpCloudListPageData = await response.json();

      if (!pageData || (pageData.services.length === 0 && !isPaginatedLoad)) {
        setAllGroups([]);
        setTotalResources(0);
        setStatusMessage(`Updated: ${new Date().toLocaleTimeString()}`);
        setStatusColor('green');
        return;
      }

      const newServiceGroups = pageData.services.map(s => ({
        serviceType: s.serviceType,
        resourceCount: s.resourceCount,
        resources: s.resources,
      }));

      if (isPaginatedLoad) {
        setAllGroups(prev => [...prev, ...newServiceGroups]);
      } else {
        setAllGroups(newServiceGroups);
      }

      setHasNextPage(pageData.hasNext);
      setTotalServices(pageData.totalServices);
      setTotalResources(pageData.totalResources);
      setStatusMessage(`Updated: ${new Date().toLocaleTimeString()}`);
      setStatusColor('green');
    } catch (error) {
      console.error('Error fetching resources:', error);
      setStatusMessage('Update failed');
      setStatusColor('red');
      if (!isPaginatedLoad) {
        setAllGroups([]);
        setTotalResources(0);
      }
    } finally {
      if (!isPaginatedLoad) {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    }
  }, [currentPage, urlAccountIds, servicesPerPage]);

  useEffect(() => {
    setCurrentPage(0);
    setAllGroups([]);
    setTotalResources(0);
    setTotalServices(0);
    setHasNextPage(true);
    loadResources(false, false);
  }, [selectedAccountId]);

  useEffect(() => {
    const handleAccountChanged = (event: CustomEvent) => {
      const detail = event.detail;
      if (detail.accountIds) {
        setSelectedAccountId(detail.accountIds[0]);
      } else if (detail.accountId) {
        setSelectedAccountId(detail.accountId);
      }
    };

    window.addEventListener('account-changed', handleAccountChanged as EventListener);
    return () => window.removeEventListener('account-changed', handleAccountChanged as EventListener);
  }, []);

  const handleRefresh = useCallback(() => {
    if (!isRefreshing) {
      setCurrentPage(0);
      setAllGroups([]);
      loadResources(true, false);
    }
  }, [isRefreshing, loadResources]);

  const handleLoadMore = useCallback(async () => {
    if (isLoadingMore || !hasNextPage) return;
    setIsLoadingMore(true);
    const nextPage = currentPage + 1;
    setCurrentPage(nextPage);
    await loadResources(false, true, nextPage);
    setIsLoadingMore(false);
  }, [isLoadingMore, hasNextPage, currentPage, loadResources]);

  /**
   * Services arrive a page at a time, so a search would otherwise only see the
   * pages already fetched. Pull the remaining pages in while a search is active;
   * each load re-runs this effect until there is no next page left.
   */
  useEffect(() => {
    // statusColor turns red when a load fails — stop there rather than retrying
    // the same page in a tight loop; the Load More button remains available.
    if (!searchInput.trim() || !hasNextPage || isLoading || isLoadingMore || statusColor === 'red') return;
    const timer = setTimeout(() => { void handleLoadMore(); }, 300);
    return () => clearTimeout(timer);
  }, [searchInput, hasNextPage, isLoading, isLoadingMore, statusColor, handleLoadMore]);

  const availableRegions = useMemo(() => {
    const regions = [...new Set(allGroups.flatMap(g => g.resources.map(r => r.location)).filter(Boolean))];
    return regions.sort();
  }, [allGroups]);

  const filteredGroups = useMemo(() => {
    const term = searchInput.trim().toLowerCase();
    const groups = allGroups.map(group => {
      // A term matching the service name (e.g. "Compute Engine") keeps the
      // whole group; otherwise resources are matched on their own fields.
      const serviceMatch = !!term && group.serviceType.toLowerCase().includes(term);
      const matchingResources = group.resources.filter(res => {
        const regionMatch = !regionFilter || (res.location && res.location === regionFilter);
        const searchMatch = !term || serviceMatch ||
          (res.name && res.name.toLowerCase().includes(term)) ||
          (res.id && res.id.toLowerCase().includes(term)) ||
          (res.type && res.type.toLowerCase().includes(term));
        return regionMatch && searchMatch;
      });
      return { ...group, resources: matchingResources };
    }).filter(group => group.resources.length > 0);

    if (!term) return groups;

    // Lead with the services whose own name matches, ahead of groups that only
    // matched because a resource name contains the term.
    return groups.sort((a, b) => {
      const aService = a.serviceType.toLowerCase().includes(term) ? 0 : 1;
      const bService = b.serviceType.toLowerCase().includes(term) ? 0 : 1;
      return aService - bService;
    });
  }, [allGroups, regionFilter, searchInput]);

  const displayedResourceCount = useMemo(() => {
    return filteredGroups.reduce((acc, group) => acc + group.resources.length, 0);
  }, [filteredGroups]);

  // While a search is active every matching group is shown expanded.
  const isGroupExpanded = (serviceType: string) =>
    searchInput.trim() ? true : expandedGroups.has(serviceType);

  const toggleGroup = (serviceType: string) => {
    setExpandedGroups(prev => {
      const newSet = new Set(prev);
      if (newSet.has(serviceType)) newSet.delete(serviceType); else newSet.add(serviceType);
      return newSet;
    });
  };

  const toggleResource = useCallback((resourceId: string) => {
    setExpandedResources(prev => {
      const newSet = new Set(prev);
      if (newSet.has(resourceId)) newSet.delete(resourceId); else newSet.add(resourceId);
      return newSet;
    });
  }, []);

  const exportData = useCallback(() => {
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
  }, [filteredGroups]);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.03)] rounded-lg border border-[rgba(0,0,0,0.08)]">
          <div className={`w-1.5 h-1.5 rounded-full ${statusColor === 'green' ? 'bg-green-500 animate-pulse' : statusColor === 'yellow' ? 'bg-amber-400' : 'bg-red-500'}`}></div>
          <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">{statusMessage}</span>
        </div>
        <button
          onClick={handleRefresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white text-xs font-bold rounded-xl hover:bg-black transition-all disabled:opacity-50"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, statusMessage, statusColor, handleRefresh]);

  return (
    <main className="flex-1 flex flex-col overflow-hidden bg-white">
      <div className="flex-1 overflow-y-auto p-8">

        {/* Filters */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-4 mb-6">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-3 flex-1 flex-wrap">
              {/* Region select */}
              <div className="relative">
                <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-[rgba(0,0,0,0.3)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064" />
                </svg>
                <select
                  value={regionFilter}
                  onChange={(e) => setRegionFilter(e.target.value)}
                  className="pl-9 pr-8 py-2 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] min-w-[180px] appearance-none"
                >
                  <option value="">All Regions</option>
                  {availableRegions.map(regionId => (
                    <option key={regionId} value={regionId}>{getRegionDisplayName(regionId)}</option>
                  ))}
                </select>
              </div>

              {/* Search */}
              <div className="relative flex-1 min-w-[280px]">
                <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-[rgba(0,0,0,0.3)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
                <input
                  type="text"
                  value={searchInput}
                  onChange={(e) => setSearchInput(e.target.value)}
                  placeholder="Search by service, name or ID…"
                  className="w-full pl-9 pr-4 py-2 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
                />
              </div>
            </div>

            <div className="flex items-center gap-3">
              <p className="text-[12px] text-[rgba(0,0,0,0.4)] font-medium whitespace-nowrap">
                {displayedResourceCount} resources · {allGroups.length} services (Top {totalServices})
              </p>
              <button
                onClick={exportData}
                className="flex items-center gap-2 px-4 py-2 text-[12px] font-semibold text-[rgba(0,0,0,0.88)] border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.03)] transition-colors"
              >
                <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
                </svg>
                Export
              </button>
            </div>
          </div>
        </div>

        {/* Resource List */}
        {isLoading ? (
          <div className="space-y-3">
            {[1, 2, 3, 4].map(i => (
              <Skeleton key={i} variant="rounded" height={72} className="w-full" />
            ))}
          </div>
        ) : filteredGroups.length === 0 ? (
          <div className="text-center py-20 bg-white border border-[rgba(0,0,0,0.08)] rounded-xl">
            <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-4">
              <svg className="w-6 h-6 text-[rgba(0,0,0,0.25)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d={statusColor === 'yellow' ? "M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" : "M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"} />
              </svg>
            </div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">
              {!selectedAccountId ? 'No Account Selected' : statusColor === 'yellow' ? 'Fetching Resources…' : 'No Resources Found'}
            </p>
            <p className="text-[13px] text-[rgba(0,0,0,0.4)] mb-5 max-w-sm mx-auto">
              {!selectedAccountId
                ? 'Select a GCP project from the sidebar to view resources.'
                : statusColor === 'yellow'
                  ? 'The backend is fetching from GCP. This may take a few minutes.'
                  : 'No resources found for this project. Click Refresh to fetch from GCP.'}
            </p>
            {selectedAccountId && statusColor !== 'yellow' && (
              <button
                onClick={handleRefresh}
                disabled={isRefreshing}
                className="px-5 py-2 text-[13px] font-semibold text-white bg-[rgba(0,0,0,0.88)] rounded-lg hover:bg-black disabled:opacity-50 transition-colors"
              >
                <i className={`fas fa-sync-alt mr-2 ${isRefreshing ? 'animate-spin' : ''}`}></i>
                {isRefreshing ? 'Fetching…' : 'Fetch from GCP'}
              </button>
            )}
          </div>
        ) : (
          <>
            <div className="space-y-3">
              {filteredGroups.map((group, index) => (
                <AnimatedItem key={group.serviceType} index={index} delay={index * 0.04}>
                  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
                    {/* Service Header */}
                    <div
                      className="flex items-center justify-between px-5 py-4 cursor-pointer hover:bg-[rgba(0,0,0,0.02)] transition-colors"
                      onClick={() => toggleGroup(group.serviceType)}
                    >
                      <div className="flex items-center gap-4 flex-1">
                        <svg
                          className={`w-3.5 h-3.5 text-[rgba(0,0,0,0.3)] transition-transform duration-200 flex-shrink-0 ${isGroupExpanded(group.serviceType) ? 'rotate-90' : ''}`}
                          fill="none" stroke="currentColor" viewBox="0 0 24 24"
                        >
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M9 5l7 7-7 7" />
                        </svg>
                        <div className="w-10 h-10 flex items-center justify-center rounded-lg bg-[rgba(0,0,0,0.03)] flex-shrink-0">
                          <img src={`/icons/${getServiceIcon(group.serviceType)}`} alt={group.serviceType} className="w-6 h-6 object-contain" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{group.serviceType}</p>
                          <p className="text-[11px] text-[rgba(0,0,0,0.35)] uppercase tracking-wide">GCP Service</p>
                        </div>
                      </div>
                      <span className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.05)] px-3 py-1 rounded-full tabular-nums">
                        {group.resourceCount || group.resources.length}
                      </span>
                    </div>

                    {/* Resources Table */}
                    {isGroupExpanded(group.serviceType) && (
                      <div className="border-t border-[rgba(0,0,0,0.06)]">
                        {/* Table header */}
                        <div className="grid grid-cols-12 items-center px-5 py-2 bg-[rgba(0,0,0,0.02)]">
                          <div className="col-span-1"></div>
                          <div className="col-span-5 text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Name / ID</div>
                          <div className="col-span-3 text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Region</div>
                          <div className="col-span-3 text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Status</div>
                        </div>

                        {group.resources.map(resource => {
                          const hasDetails = resource.tags && Object.keys(resource.tags).length > 0;
                          const isExpanded = expandedResources.has(resource.id);

                          return (
                            <div key={resource.id} className="border-t border-[rgba(0,0,0,0.06)]">
                              {/* Resource row */}
                              <div
                                className="grid grid-cols-12 items-center px-5 py-3 hover:bg-[rgba(0,0,0,0.015)] transition-colors cursor-pointer"
                                onClick={() => toggleResource(resource.id)}
                              >
                                {/* Chevron */}
                                <div className="col-span-1 flex items-center">
                                  <svg
                                    className={`w-3 h-3 text-[rgba(0,0,0,0.3)] transition-transform duration-200 ${isExpanded ? 'rotate-90' : ''}`}
                                    fill="none" stroke="currentColor" viewBox="0 0 24 24"
                                  >
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M9 5l7 7-7 7" />
                                  </svg>
                                </div>

                                {/* Name / ID */}
                                <div className="col-span-5 min-w-0 pr-3">
                                  <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate">{resource.name || 'N/A'}</p>
                                  <p className="font-mono text-[11px] text-[rgba(0,0,0,0.35)] truncate">{resource.id || 'N/A'}</p>
                                </div>

                                {/* Region */}
                                <div className="col-span-3">
                                  <span className="text-[12px] text-[rgba(0,0,0,0.5)]">{getRegionDisplayName(resource.location) || 'Global'}</span>
                                </div>

                                {/* Status */}
                                <div className="col-span-3">
                                  <span className={`inline-flex px-2.5 py-0.5 text-[11px] font-semibold rounded-full capitalize ${getStateBadge(resource.status)}`}>
                                    {formatStatusText(resource.status)}
                                  </span>
                                </div>
                              </div>

                              {/* Expanded panel — tags */}
                              {isExpanded && hasDetails && (
                                <div className="border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)] px-8 py-4 pl-14">
                                  <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)] mb-3">Tags</p>
                                  <div className="grid grid-cols-2 gap-x-8 gap-y-1.5">
                                    {Object.entries(resource.tags || {}).map(([key, value]) => (
                                      <div key={key} className="flex items-baseline gap-2">
                                        <span className="text-[12px] text-[rgba(0,0,0,0.4)] shrink-0">{key}</span>
                                        <span className="text-[12px] font-medium text-[rgba(0,0,0,0.88)] truncate">{value !== null && value !== undefined ? String(value) : '—'}</span>
                                      </div>
                                    ))}
                                  </div>
                                </div>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                </AnimatedItem>
              ))}
            </div>

            {/* Load More */}
            {hasNextPage && (
              <div className="text-center py-8">
                <button
                  onClick={handleLoadMore}
                  disabled={isLoadingMore}
                  className="px-6 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.88)] border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.03)] transition-colors disabled:opacity-50"
                >
                  {isLoadingMore ? 'Loading…' : 'Load More'}
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </main>
  );
};

export default GcpCloudListPage;
