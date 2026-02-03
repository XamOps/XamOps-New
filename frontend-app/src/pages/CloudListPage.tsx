/**
 * CloudList Page Component
 *
 * Converted from: cloudlist.html
 *
 * Features:
 * - Lists all cloud resources by service type
 * - Paginated loading with "Load More" button
 * - Real-time updates via WebSocket
 * - Search by resource name or ID
 * - Filter by region
 * - Export to CSV
 * - Expandable service groups and resource details
 * - Force refresh capability
 *
 * All backend connections and API endpoints preserved exactly as in cloudlist.html
 */

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import { fetchCloudlistServicesPaginated, triggerCloudlistRefresh } from '../services/cloudlistApi';
import type { ServiceGroup } from '../types/cloudlist';
import SockJS from 'sockjs-client';
import Stomp from 'stompjs';
import { useTopbar } from '../contexts/TopbarContext';
import Skeleton from '../components/Shared/Skeleton';

const CloudListPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const { selectedAccountIds } = useAccounts();

  // State
  const [allGroups, setAllGroups] = useState<ServiceGroup[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [currentPage, setCurrentPage] = useState(0);
  const [hasNextPage, setHasNextPage] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedRegion, setSelectedRegion] = useState('');
  const [statusMessage, setStatusMessage] = useState('Ready');
  const [statusColor, setStatusColor] = useState('green');
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [expandedResources, setExpandedResources] = useState<Set<string>>(new Set());
  const [initialServiceProcessed, setInitialServiceProcessed] = useState(false);

  const { setActions, clearActions } = useTopbar();

  // WebSocket
  const stompClientRef = useRef<Stomp.Client | null>(null);
  const isConnectingRef = useRef<boolean>(false);
  const isMountedRef = useRef<boolean>(true);

  // Constants
  const servicesPerPage = 5;

  // Get selected account ID from URL or context
  const selectedAccountId = useMemo(() => {
    const urlAccountIds = searchParams.get('accountIds');
    if (urlAccountIds) return urlAccountIds;

    if (selectedAccountIds && selectedAccountIds.length > 0) {
      return selectedAccountIds.join(',');
    }

    return null;
  }, [searchParams, selectedAccountIds]);

  /**
   * Disconnect WebSocket
   */
  const disconnectWebSocket = useCallback(() => {
    if (stompClientRef.current) {
      // Ensure we pass a callback as required by some versions/types of stompjs
      try {
        stompClientRef.current.disconnect(() => {
          console.log('🔌 [CloudList] WebSocket disconnected');
        });
      } catch (e) {
        console.warn('⚠️ Error during socket disconnect:', e);
      }
      stompClientRef.current = null;
    }
  }, []);

  /**
   * Load initial resources (page 0)
   */
  const loadInitialResources = useCallback(async (forceRefresh: boolean = false) => {
    if (!selectedAccountId) {
      if (isMountedRef.current) {
        setIsLoading(false);
        setStatusMessage('Please select an account');
        setStatusColor('yellow');
      }
      return;
    }

    if (forceRefresh) {
      if (isMountedRef.current) {
        setIsRefreshing(true);
        setStatusMessage('Fetching resources from AWS... This may take 2-5 minutes.');
        setStatusColor('yellow');
      }
      try {
        await triggerCloudlistRefresh(selectedAccountId);
        if (isMountedRef.current) {
          setStatusMessage('Refresh started. Please wait for WebSocket notification or refresh manually after a few minutes.');
        }
      } catch (error) {
        console.error('❌ [CloudList] Error triggering refresh:', error);
        if (isMountedRef.current) {
          setStatusMessage('Refresh started (backend will fetch in background)');
          setStatusColor('yellow');
          setIsRefreshing(false);
        }
      }
      return;
    }

    if (isMountedRef.current) {
      setIsLoading(true);
      setCurrentPage(0);
      setAllGroups([]);
      setHasNextPage(true);
      setStatusMessage('Loading resources...');
      setStatusColor('green');
    }

    try {
      console.log('🔄 [CloudList] Loading resources for account:', selectedAccountId);
      const data = await fetchCloudlistServicesPaginated(selectedAccountId, 0, servicesPerPage);

      if (!isMountedRef.current) return;

      console.log('✅ [CloudList] Data received:', data);
      setAllGroups(data.services || []);
      setHasNextPage(data.hasNext || false);
      setStatusMessage(`Updated: ${new Date().toLocaleTimeString()}`);
      setStatusColor('green');

      // Cache EC2 instances for CloudSitter
      const ec2Group = (data.services || []).find(g => g.serviceType === 'EC2 Instance');
      if (ec2Group && ec2Group.resources && ec2Group.resources.length > 0) {
        const cacheKey = `cloudlist-ec2-${selectedAccountId}`;
        const cacheTimestampKey = `cloudlist-ec2-timestamp-${selectedAccountId}`;
        sessionStorage.setItem(cacheKey, JSON.stringify(ec2Group.resources));
        sessionStorage.setItem(cacheTimestampKey, Date.now().toString());
        console.log(`✅ Cached ${ec2Group.resources.length} EC2 instances for CloudSitter`);
      }
    } catch (error: any) {
      console.error('❌ [CloudList] Error loading resources:', error);

      if (!isMountedRef.current) return;

      // Check if it's a timeout or network error (like 503 Service Unavailable)
      const errorMsg = error?.message || '';
      if (errorMsg.includes('503') || errorMsg.includes('timeout') || errorMsg.includes('INCOMPLETE_CHUNKED_ENCODING')) {
        setStatusMessage('Backend is fetching data from AWS in the background. Please wait a few minutes and refresh.');
        setStatusColor('yellow');
      } else {
        setStatusMessage('Update failed - ' + (errorMsg || 'Unknown error'));
        setStatusColor('red');
      }

      setAllGroups([]);
    } finally {
      if (isMountedRef.current) {
        setIsLoading(false);
      }
    }
  }, [selectedAccountId]);

  const connectWebSocket = useCallback(() => {
    if (stompClientRef.current?.connected || isConnectingRef.current) {
      console.log('⏭️ [CloudList] WebSocket already connected or connecting');
      return;
    }

    console.log('📡 [CloudList] Opening Web Socket...');
    isConnectingRef.current = true;

    const socket = new SockJS('/xamops-ws') as any;
    const client = Stomp.over(socket);

    // Disable logging to reduce console clutter
    client.debug = () => { };

    client.connect({}, (frame: any) => {
      isConnectingRef.current = false;
      if (!isMountedRef.current) {
        try { client.disconnect(() => { }); } catch (e) { }
        return;
      }

      console.log('✅ [CloudList] WebSocket connected:', frame);

      client.subscribe('/topic/cloudlist-refresh', (message: any) => {
        if (!isMountedRef.current) return;

        const body = JSON.parse(message.body);
        console.log('📨 [CloudList] WebSocket message received:', body);

        if (body.accountId === selectedAccountId) {
          if (body.status === 'refresh-complete') {
            setIsRefreshing(false);
            setStatusMessage(`Updated: ${new Date().toLocaleTimeString()}`);
            setStatusColor('green');
            loadInitialResources(false);
          } else if (body.status === 'refresh-failed') {
            setIsRefreshing(false);
            setStatusMessage('Refresh failed');
            setStatusColor('red');
          }
        }
      });
    }, (error: any) => {
      isConnectingRef.current = false;
      console.error('❌ [CloudList] WebSocket connection failed:', error);
      if (isMountedRef.current) {
        setStatusMessage('Real-time connection failed');
        setStatusColor('red');
      }
    });

    stompClientRef.current = client;
  }, [selectedAccountId, loadInitialResources]);


  /**
   * Load more resources (pagination)
   */
  const loadMoreResources = useCallback(async () => {
    if (!selectedAccountId || isLoadingMore || !hasNextPage) return;

    setIsLoadingMore(true);
    const nextPage = currentPage + 1;

    try {
      const data = await fetchCloudlistServicesPaginated(selectedAccountId, nextPage, servicesPerPage);
      setAllGroups(prev => [...prev, ...data.services]);
      setHasNextPage(data.hasNext);
      setCurrentPage(nextPage);

      // Update EC2 cache if new EC2 instances loaded
      const ec2Group = data.services.find(g => g.serviceType === 'EC2 Instance');
      if (ec2Group && ec2Group.resources.length > 0) {
        const cacheKey = `cloudlist-ec2-${selectedAccountId}`;
        const cacheTimestampKey = `cloudlist-ec2-timestamp-${selectedAccountId}`;

        // Get all EC2 instances from allGroups + new data
        const allEc2s = [...allGroups, ...data.services]
          .filter(g => g.serviceType === 'EC2 Instance')
          .flatMap(g => g.resources);

        sessionStorage.setItem(cacheKey, JSON.stringify(allEc2s));
        sessionStorage.setItem(cacheTimestampKey, Date.now().toString());
        console.log(`✅ Updated cache with ${allEc2s.length} EC2 instances for CloudSitter`);
      }
    } catch (error) {
      console.error('❌ [CloudList] Error loading more resources:', error);
    } finally {
      setIsLoadingMore(false);
    }
  }, [selectedAccountId, currentPage, hasNextPage, isLoadingMore]);

  /**
   * Filter resources by search term and region
   */
  const filteredGroups = useMemo(() => {
    return allGroups.map(group => {
      const resources = group.resources || [];
      const matchingResources = resources.filter(res => {
        const regionMatch = !selectedRegion || res.region === selectedRegion;
        const searchMatch = !searchTerm ||
          (res.name && res.name.toLowerCase().includes(searchTerm.toLowerCase())) ||
          (res.id && res.id.toLowerCase().includes(searchTerm.toLowerCase()));
        return regionMatch && searchMatch;
      });
      return { ...group, resources: matchingResources };
    }).filter(group => group.resources.length > 0);
  }, [allGroups, searchTerm, selectedRegion]);

  /**
   * Get all unique regions
   */
  const allRegions = useMemo(() => {
    const regions = [...new Set(allGroups.flatMap(g => (g.resources || []).map(r => r.region)))].filter(Boolean);
    regions.sort();

    // Ensure 'Global' and 'Regional' come first
    const priorityRegions = ['Global', 'Regional'].filter(r => regions.includes(r));
    const otherRegions = regions.filter(r => !priorityRegions.includes(r));

    return [...priorityRegions, ...otherRegions];
  }, [allGroups]);

  /**
   * Count total resources
   */
  const totalResourceCount = useMemo(() => {
    return filteredGroups.reduce((acc, group) => acc + group.resources.length, 0);
  }, [filteredGroups]);

  /**
   * Toggle service group expansion
   */
  const toggleGroup = useCallback((serviceType: string) => {
    setExpandedGroups(prev => {
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
   * Toggle resource details expansion
   */
  const toggleResource = useCallback((resourceId: string) => {
    setExpandedResources(prev => {
      const newSet = new Set(prev);
      if (newSet.has(resourceId)) {
        newSet.delete(resourceId);
      } else {
        newSet.add(resourceId);
      }
      return newSet;
    });
  }, []);

  /**
   * Export data to CSV
   */
  const exportData = useCallback(() => {
    const headers = ['ID', 'Name', 'Type', 'Region', 'State', 'Launch Time', 'Details'];
    const rows = filteredGroups.flatMap(group =>
      group.resources.map(res => [
        `"${res.id || ''}"`,
        `"${res.name || ''}"`,
        `"${res.type || ''}"`,
        `"${res.region || ''}"`,
        `"${res.state || ''}"`,
        `"${res.launchTime ? new Date(res.launchTime).toLocaleString() : 'N/A'}"`,
        `"${JSON.stringify(res.details || {}).replace(/"/g, '""')}"`
      ])
    );

    const csvContent = "data:text/csv;charset=utf-8,"
      + headers.join(",") + "\n"
      + rows.map(e => e.join(",")).join("\n");

    const encodedUri = encodeURI(csvContent);
    const link = document.createElement("a");
    link.setAttribute("href", encodedUri);
    link.setAttribute("download", "cloudlist_export.csv");
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }, [filteredGroups]);

  /**
   * Get service icon filename
   */
  const getServiceIcon = (serviceType: string): string => {
    const iconMap: Record<string, string> = {
      'EC2 Instance': 'ec2-instances.png',
      'EBS Volume': 'aws-ebs.png',
      'RDS Instance': 'rds-instance.png',
      'Lambda Function': 'aws-lambda.png',
      'S3 Bucket': 's3-bucket.png',
      'Load Balancer': 'load-balanceraws.png',
      'Auto Scaling Group': 'autoscaling.png',
      'VPC': 'vpc.png',
      'Security Group': 'security-group.png',
      'Route 53 Zone': 'aws-route53.png',
      'EKS Cluster': 'aws-eks.png',
      'ECS Cluster': 'aws-ecs.png',
      'Certificate Manager': 'certificate-manager.png',
      'CloudTrail': 'cloudtrail.png',
      'CloudWatch Log Group': 'cloudwatch-log-group.png',
      'ECR Repository': 'ecr-repository.png',
      'SNS Topic': 'sns-topic.png',
      'SQS Queue': 'sqs-queue.png',
      'Lightsail Instance': 'lightsail.png',
      'Amplify App': 'amplify.png',
      'AWS Step Functions': 'step-functions.png',
      'Amazon ElastiCache': 'elasticache.png',
      'AWS Config': 'Config.png',
      'AWS Security Hub': 'security-hub.png',
      'AWS Glue': 'glue.png',
      'Amazon Athena': 'athena.png',
      'Amazon Cognito': 'cognito.png',
      'Amazon CloudWatch Events': 'cloudwatch-events.png',
      'AWS WAF': 'waf.png',
      'CloudFront': 'cloudfront.png',
      'DynamoDB Table': 'dynamodb.png',
      'Bedrock': 'bedrock.png',
      'Amazon Textract': 'textract.png',
      'SageMaker': 'sagemaker.png',
      'Amazon Elastic File System': 'efs.png',
      'AWS Systems Manager': 'systems-manager.png',
      'Amazon DataZone': 'datazone.png',
      'KMS': 'kms.png',
      'AWS Cloud Map': 'cloud-map.png',
      'EFS File System': 'efs.png',
      'SSM Managed Instance': 'ssm.png',
      'NAT Gateway': 'Res_Amazon-VPC_NAT-Gateway_48_Light.png',
      'Internet Gateway': 'Res_Amazon-VPC_Internet-Gateway_48_Light.png',
      'Network Interface (ENI)': 'eni.png',
      'Elastic IP': 'elastic-ip.png',
      'EBS Snapshot': 'snapshot.png',
      'Security Hub': 'security-hub.png',
      'CloudFormation Stack': 'cloudformation.png',
      'DataZone Domain': 'datazone.png',
      'Textract Project': 'textract.png',
      'EventBridge Bus': 'eventbridge.png',
      'Kinesis Stream': 'kinesis.png',
      'CodePipeline': 'codepipeline.png',
      'CodeBuild Project': 'codebuild.png',
      'CodeCommit Repository': 'codecommit.png',
      'AWS Shield Protection': 'shield.png',
      'Organization Account': 'organizations.png',
      'Control Tower Control': 'controltower.png',
      'Elastic Beanstalk Environment': 'elasticbeanstalk.png',
      'API Gateway': 'api-gateway.png'
    };
    return iconMap[serviceType] || 'default-icon.png';
  };

  /**
   * Get state badge class
   */
  const getStateClass = (state: string): string => {
    const s = (state || '').toLowerCase();
    if (['running', 'available', 'active', 'in-use', 'issued'].includes(s)) {
      return 'bg-green-100 text-green-800 border-green-200';
    }
    if (['stopped', 'terminated', 'deleting', 'inactive', 'expired'].includes(s)) {
      return 'bg-red-100 text-red-800 border-red-200';
    }
    if (['pending', 'stopping', 'pending_validation'].includes(s)) {
      return 'bg-yellow-100 text-yellow-800 border-yellow-200';
    }
    return 'bg-gray-100 text-gray-800 border-gray-200';
  };

  // Initialize WebSocket and load data
  useEffect(() => {
    isMountedRef.current = true;
    connectWebSocket();
    loadInitialResources(false);

    return () => {
      isMountedRef.current = false;
      disconnectWebSocket();
    };
  }, [connectWebSocket, loadInitialResources, disconnectWebSocket]);

  // Handle initial service from URL
  useEffect(() => {
    if (allGroups.length > 0 && !initialServiceProcessed) {
      const serviceParam = searchParams.get('service');
      if (serviceParam) {
        console.log('🎯 [CloudList] Processing initial service filter:', serviceParam);

        // Find matching group
        const matchingGroup = allGroups.find(g =>
          g.serviceType.toLowerCase().includes(serviceParam.toLowerCase()) ||
          serviceParam.toLowerCase().includes(g.serviceType.toLowerCase())
        );

        if (matchingGroup) {
          console.log('✅ [CloudList] Found matching group for service:', matchingGroup.serviceType);
          setExpandedGroups(new Set([matchingGroup.serviceType]));

          // If it's a specific service like "EC2", we might want to scroll to it or prioritize it
          // For now, just expanding it is a good start.
        }
        setInitialServiceProcessed(true);
      }
    }
  }, [allGroups, searchParams, initialServiceProcessed]);

  // Listen for account changes
  useEffect(() => {
    const handleAccountChanged = (event: any) => {
      console.log('[CloudList] Account changed event received:', event.detail);
      loadInitialResources(false);
    };

    window.addEventListener('account-changed', handleAccountChanged);
    return () => {
      window.removeEventListener('account-changed', handleAccountChanged);
    };
  }, [loadInitialResources]);

  // Topbar Integration
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <div className="flex items-center space-x-2 bg-white/50 px-3 py-1.5 rounded-lg border border-slate-200/50 backdrop-blur-sm">
          <div className={`w-2 h-2 rounded-full ${statusColor === 'green' ? 'bg-green-500 animate-pulse' : statusColor === 'yellow' ? 'bg-yellow-500' : 'bg-red-500'}`}></div>
          <span className="text-xs font-medium text-slate-600">{statusMessage}</span>
        </div>
        <button
          onClick={() => loadInitialResources(true)}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, statusMessage, statusColor, loadInitialResources]);

  return (
    <main className="cloudlist-page flex-1 flex flex-col overflow-hidden" style={{ minHeight: '100vh', background: '#f8fafc' }}>
      {/* Header */}
      {/* Content */}

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-8">
        {/* Filters */}
        <div className="bg-white/80 backdrop-blur-sm rounded-2xl p-6 mb-8 border border-gray-200/50 shadow-lg">
          <div className="flex flex-wrap items-center justify-between gap-6">
            <div className="flex items-center space-x-4 flex-1">
              <div className="relative">
                <i className="fas fa-globe absolute left-3 top-1/2 -translate-y-1/2 text-gray-400"></i>
                <select
                  value={selectedRegion}
                  onChange={(e) => setSelectedRegion(e.target.value)}
                  className="cloudlist-select pl-10 pr-4 py-3 rounded-xl border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 min-w-[200px]"
                >
                  <option value="">All Regions</option>
                  {allRegions.map(region => (
                    <option key={region} value={region}>{region}</option>
                  ))}
                </select>
              </div>
              <div className="relative flex-1 min-w-[350px]">
                <i className="fas fa-search absolute left-4 top-1/2 -translate-y-1/2 text-gray-400"></i>
                <input
                  type="text"
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  placeholder="Search resources by name or ID..."
                  className="cloudlist-input w-full pl-12 pr-4 py-3 rounded-xl border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                />
              </div>
            </div>
            <div className="flex items-center space-x-4">
              <p className="text-sm text-gray-600 font-medium">
                Showing {totalResourceCount} resources from {allGroups.length} services
              </p>
              <button
                onClick={exportData}
                className="cloudlist-btn-primary px-6 py-3 text-sm font-semibold text-white rounded-xl shadow-md hover:shadow-lg"
              >
                <i className="fas fa-file-excel mr-2"></i>Export Data
              </button>
            </div>
          </div>
        </div>

        {/* Resource List */}
        {isLoading ? (
          <div className="space-y-4">
            {[1, 2, 3, 4].map(i => (
              <Skeleton key={i} variant="rounded" height={96} className="w-full shadow-sm" />
            ))}
          </div>
        ) : filteredGroups.length === 0 ? (
          <div className="cloudlist-empty-state text-center py-20 px-8 rounded-2xl">
            <div className="w-16 h-16 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className={`fas ${statusColor === 'yellow' ? 'fa-hourglass-half' : 'fa-search-minus'} text-white text-2xl`}></i>
            </div>
            <h3 className="text-xl font-bold text-gray-800 mb-2">
              {!selectedAccountId ? 'No Account Selected' : statusColor === 'yellow' ? 'Fetching Resources from AWS' : 'No Resources Found'}
            </h3>
            <p className="text-gray-600 mb-4">
              {!selectedAccountId
                ? 'Please use the sidebar to select a cloud account to view its resources.'
                : statusColor === 'yellow'
                  ? 'The backend is currently fetching resources from AWS. This may take 2-5 minutes for the first time. Please click the Refresh button after a few moments.'
                  : 'No cloud resources were found for the selected account. Try clicking the Refresh button to fetch resources from AWS.'}
            </p>
            {selectedAccountId && statusColor !== 'yellow' && (
              <button
                onClick={() => loadInitialResources(true)}
                disabled={isRefreshing}
                className="px-6 py-3 text-sm font-semibold text-white bg-gradient-to-r from-indigo-600 to-purple-600 rounded-lg hover:from-indigo-700 hover:to-purple-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <i className={`fas fa-sync-alt mr-2 ${isRefreshing ? 'animate-spin' : ''}`}></i>
                {isRefreshing ? 'Fetching from AWS...' : 'Fetch Resources from AWS'}
              </button>
            )}
          </div>
        ) : (
          <>
            <div className="space-y-6">
              {filteredGroups.map(group => (
                <div key={group.serviceType} className="cloudlist-service-group rounded-2xl shadow-lg overflow-hidden border border-gray-200/50">
                  {/* Service Header */}
                  <div
                    className="flex items-center justify-between p-6 cursor-pointer"
                    onClick={() => toggleGroup(group.serviceType)}
                  >
                    <div className="flex items-center space-x-4 flex-1">
                      <i className={`fas fa-chevron-right text-gray-400 w-5 text-center transition-all duration-200 ${expandedGroups.has(group.serviceType) ? 'rotate-90' : ''}`}></i>
                      <div className="cloudlist-service-icon w-12 h-12 flex items-center justify-center rounded-xl">
                        <img src={`/icons/${getServiceIcon(group.serviceType)}`} alt={group.serviceType} className="w-full h-full rounded-xl object-cover" />
                      </div>
                      <div className="flex-1">
                        <h3 className="font-bold text-gray-800 text-lg">{group.serviceType}</h3>
                        <p className="text-sm text-gray-600">AWS Service</p>
                      </div>
                      <div className="cloudlist-resource-counter text-sm font-bold px-4 py-2 rounded-full">
                        {group.resources.length}
                      </div>
                    </div>
                  </div>

                  {/* Resources */}
                  {expandedGroups.has(group.serviceType) && (
                    <div className="bg-gradient-to-r from-gray-50/30 to-gray-100/30 backdrop-blur-sm">
                      {group.resources.map(resource => {
                        const hasDetails = resource.details && Object.keys(resource.details).length > 0;
                        const isExpanded = expandedResources.has(resource.id);

                        return (
                          <div key={resource.id} className="cloudlist-resource-row border-t border-gray-200/50">
                            {/* Resource Header */}
                            <div
                              className={`grid grid-cols-12 items-center p-6 pl-16 text-sm hover:bg-white/50 transition-all duration-200 ${hasDetails ? 'cursor-pointer' : ''}`}
                              onClick={() => hasDetails && toggleResource(resource.id)}
                            >
                              <div className="col-span-6 flex items-center space-x-4">
                                {hasDetails && (
                                  <i className={`fas fa-chevron-right text-gray-400 w-5 text-center transition-all duration-200 ${isExpanded ? 'rotate-90' : ''}`}></i>
                                )}
                                {!hasDetails && <div className="w-5"></div>}
                                <div className="flex-1">
                                  <div className="flex items-center space-x-2">
                                    <p className="font-bold text-gray-800">{resource.name || 'N/A'}</p>
                                    <div className="w-1 h-1 bg-gray-400 rounded-full"></div>
                                    <p className="font-mono text-xs text-gray-500">{resource.id || 'N/A'}</p>
                                  </div>
                                </div>
                              </div>
                              <div className="col-span-2 flex items-center space-x-2">
                                <i className="fas fa-map-marker-alt text-gray-400 text-xs"></i>
                                <span className="font-semibold text-gray-600">{resource.region || 'Global'}</span>
                              </div>
                              <div className="col-span-2">
                                <span className={`cloudlist-state-badge px-3 py-1 inline-flex text-xs leading-5 font-bold rounded-full capitalize ${getStateClass(resource.state)}`}>
                                  {resource.state || 'Unknown'}
                                </span>
                              </div>
                              <div className="col-span-2 text-right pr-4">
                                {hasDetails ? (
                                  <button className="cloudlist-btn-secondary px-4 py-2 text-sm font-semibold rounded-lg hover:bg-gray-100 transition-all">
                                    <i className="fas fa-info-circle mr-2"></i>Details
                                  </button>
                                ) : (
                                  <div className="flex items-center justify-end space-x-2">
                                    <i className="fas fa-external-link-alt text-gray-400 text-xs"></i>
                                    <span className="text-xs text-gray-500">No Extra Details</span>
                                  </div>
                                )}
                              </div>
                            </div>

                            {/* Resource Details */}
                            {hasDetails && isExpanded && (
                              <div className="p-6 pl-24 text-sm text-gray-700 bg-gray-50/50 border-t border-gray-200">
                                <h4 className="font-semibold text-base mb-3">Resource Details:</h4>
                                <div className="grid grid-cols-2 gap-2">
                                  {Object.entries(resource.details).map(([key, value]) => (
                                    <div key={key} className="flex items-center">
                                      <span className="font-medium text-gray-600 mr-2">
                                        {key.replace(/([A-Z])/g, ' $1').replace(/^./, str => str.toUpperCase())}:
                                      </span>
                                      <span>{value !== null && value !== undefined ? String(value) : 'N/A'}</span>
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
              ))}
            </div>

            {/* Load More Button */}
            {hasNextPage && (
              <div className="text-center py-8">
                <button
                  onClick={loadMoreResources}
                  disabled={isLoadingMore}
                  className="cloudlist-btn-secondary px-8 py-3 text-sm font-semibold rounded-xl hover:bg-gray-100 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {isLoadingMore ? (
                    <>
                      <span>Loading...</span>
                      <div className="loading-spinner inline-block ml-2"></div>
                    </>
                  ) : (
                    <span>Load More</span>
                  )}
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </main>
  );
};
export default CloudListPage;
