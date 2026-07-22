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
import { AnimatedItem } from '../components/Shared/AnimatedList';
import LabelBadge from '../components/Labels/LabelBadge';
import LabelAssignPopover from '../components/Labels/LabelAssignPopover';
import { fetchLabels, fetchResourceLabelMap, assignLabel, removeLabel, fetchConnectedResources } from '../services/resourceLabelsApi';
import type { ConnectedResource } from '../services/resourceLabelsApi';
import type { ResourceLabel, ResourceLabelMap } from '../types/resourceLabels';

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

  // ── Labels state ──────────────────────────────────────────────────────────
  const [allLabels, setAllLabels] = useState<ResourceLabel[]>([]);
  const [labelMap, setLabelMap] = useState<ResourceLabelMap>({});   // resourceId → labels
  const [selectedResources, setSelectedResources] = useState<Set<string>>(new Set());
  const [bulkLabelId, setBulkLabelId] = useState<string>('');
  const [filterLabelId, setFilterLabelId] = useState<string>('');
  const [openPopoverResourceId, setOpenPopoverResourceId] = useState<string | null>(null);
  const [connectedCache, setConnectedCache] = useState<Record<string, ConnectedResource[] | 'loading' | 'error'>>({});
  const [openConnectedPopoverId, setOpenConnectedPopoverId] = useState<string | null>(null);
  const [applyingAllFor, setApplyingAllFor] = useState<string | null>(null);
  // Label loading flag to avoid re-fetching on every render
  const labelMapLoadedForRef = useRef<string | null>(null);

  const { setActions, clearActions } = useTopbar();

  // WebSocket
  const stompClientRef = useRef<Stomp.Client | null>(null);
  const isConnectingRef = useRef<boolean>(false);
  const isMountedRef = useRef<boolean>(true);
  const refreshTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

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

      // Add safety timeout (5 minutes)
      if (refreshTimeoutRef.current) clearTimeout(refreshTimeoutRef.current);
      refreshTimeoutRef.current = setTimeout(() => {
        if (isMountedRef.current && isRefreshing) {
          console.log('⏰ [CloudList] Refresh safety timeout reached');
          setIsRefreshing(false);
          setStatusMessage('Refresh timed out. Please check again in a few minutes.');
          setStatusColor('yellow');
        }
      }, 300000);

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
          if (refreshTimeoutRef.current) {
            clearTimeout(refreshTimeoutRef.current);
            refreshTimeoutRef.current = null;
          }
        }
      }
      return;
    }

    // Cancel any in-flight request before starting a new one
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    abortControllerRef.current = new AbortController();
    const signal = abortControllerRef.current.signal;

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
      const data = await fetchCloudlistServicesPaginated(selectedAccountId, 0, servicesPerPage, signal);

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
      // Silently ignore aborted requests (expected during cleanup/remount)
      if (error?.name === 'AbortError') return;

      console.error('❌ [CloudList] Error loading resources:', error);

      if (!isMountedRef.current) return;

      // Check if it's a network or transient error
      const errorMsg = error?.message || '';
      if (errorMsg.includes('503') || errorMsg.includes('timeout') ||
          errorMsg.includes('INCOMPLETE_CHUNKED_ENCODING') || errorMsg.includes('Failed to fetch')) {
        setStatusMessage('Network error. Retrying...');
        setStatusColor('yellow');
        // Auto-retry once after 2s for transient network errors
        setTimeout(() => {
          if (isMountedRef.current) {
            loadInitialResources(false);
          }
        }, 2000);
        return;
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

        // Handle both single and multi-account scenarios
        const isTargetAccount = selectedAccountId === body.accountId ||
          (selectedAccountId && selectedAccountId.split(',').includes(body.accountId));

        if (isTargetAccount) {
          // Clear safety timeout
          if (refreshTimeoutRef.current) {
            clearTimeout(refreshTimeoutRef.current);
            refreshTimeoutRef.current = null;
          }

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

  // ── Load all labels for the client once ──────────────────────────────────
  useEffect(() => {
    fetchLabels()
      .then(data => setAllLabels(Array.isArray(data) ? data : []))
      .catch(err => console.error('[Labels] Failed to load labels:', err));
  }, []);

  // ── Fetch label map whenever the resource list changes ────────────────────
  useEffect(() => {
    if (!selectedAccountId || allGroups.length === 0) return;

    const accountKey = selectedAccountId.split(',')[0];
    if (labelMapLoadedForRef.current === accountKey + allGroups.length) return;
    labelMapLoadedForRef.current = accountKey + allGroups.length;

    const resourceIds = allGroups.flatMap(g => g.resources.map(r => r.id));
    if (resourceIds.length === 0) return;

    fetchResourceLabelMap({ accountId: accountKey, resourceIds })
      .then(setLabelMap)
      .catch(err => console.error('[Labels] Failed to load label map:', err));
  }, [selectedAccountId, allGroups]);

  // ── Bulk assign selected resources to a label ─────────────────────────────
  const handleBulkAssign = useCallback(async () => {
    if (!bulkLabelId || selectedResources.size === 0 || !selectedAccountId) return;
    const accountKey = selectedAccountId.split(',')[0];
    const label = allLabels.find(l => l.id === Number(bulkLabelId));
    if (!label) return;

    const resources = allGroups
      .flatMap(g => g.resources)
      .filter(r => selectedResources.has(r.id))
      .map(r => ({ resourceId: r.id, resourceType: r.type, resourceName: r.name ?? undefined, region: r.region }));

    try {
      await assignLabel({ accountId: accountKey, labelId: label.id, resources });
      // Update local label map optimistically
      setLabelMap(prev => {
        const next = { ...prev };
        resources.forEach(r => {
          const existing = next[r.resourceId] ?? [];
          if (!existing.find(l => l.id === label.id)) {
            next[r.resourceId] = [...existing, label];
          }
        });
        return next;
      });
      setSelectedResources(new Set());
      setBulkLabelId('');
    } catch (err) {
      console.error('[Labels] Bulk assign failed:', err);
    }
  }, [bulkLabelId, selectedResources, selectedAccountId, allLabels, allGroups]);

  const toggleResourceSelection = useCallback((resourceId: string) => {
    setSelectedResources(prev => {
      const next = new Set(prev);
      if (next.has(resourceId)) next.delete(resourceId); else next.add(resourceId);
      return next;
    });
  }, []);

  const handleLabelMapChange = useCallback((resourceId: string, updatedLabels: ResourceLabel[]) => {
    setLabelMap(prev => ({ ...prev, [resourceId]: updatedLabels }));
  }, []);

  /** Remove a label via the badge × button — calls API then updates local state */
  const handleRemoveBadgeLabel = useCallback(async (
    resource: { id: string; type: string; name?: string | null; region?: string },
    label: ResourceLabel
  ) => {
    if (!selectedAccountId) return;
    const accountKey = selectedAccountId.split(',')[0];
    // Optimistic UI update
    setLabelMap(prev => ({
      ...prev,
      [resource.id]: (prev[resource.id] ?? []).filter(l => l.id !== label.id),
    }));
    try {
      await removeLabel({
        accountId: accountKey,
        labelId: label.id,
        resources: [{ resourceId: resource.id, resourceType: resource.type, resourceName: resource.name ?? undefined, region: resource.region }],
      });
    } catch (err) {
      // Roll back on failure
      console.error('[Labels] Remove failed, rolling back:', err);
      setLabelMap(prev => ({
        ...prev,
        [resource.id]: [...(prev[resource.id] ?? []), label],
      }));
    }
  }, [selectedAccountId]);

  /**
   * Filter resources by search term, region, and label
   */
  const filteredGroups = useMemo(() => {
    return allGroups.map(group => {
      const resources = group.resources || [];
      const matchingResources = resources.filter(res => {
        const regionMatch = !selectedRegion || res.region === selectedRegion;
        const searchMatch = !searchTerm ||
          (res.name && res.name.toLowerCase().includes(searchTerm.toLowerCase())) ||
          (res.id && res.id.toLowerCase().includes(searchTerm.toLowerCase()));
        const labelMatch = !filterLabelId ||
          (labelMap[res.id] ?? []).some(l => l.id === Number(filterLabelId));
        return regionMatch && searchMatch && labelMatch;
      });
      return { ...group, resources: matchingResources };
    }).filter(group => group.resources.length > 0);
  }, [allGroups, searchTerm, selectedRegion, filterLabelId, labelMap]);

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
   * Expand a resource row and fetch connected resources on first open
   */
  const handleRowExpand = useCallback((resource: import('../types/cloudlist').CloudResource, accountId: string) => {
    const wasExpanded = expandedResources.has(resource.id);
    toggleResource(resource.id);
    if (!wasExpanded) {
      setConnectedCache(prev => {
        if (prev[resource.id]) return prev;          // already cached or loading
        return { ...prev, [resource.id]: 'loading' };
      });
      fetchConnectedResources(accountId, resource.id, resource.type, resource.region)
        .then(list => setConnectedCache(prev => ({ ...prev, [resource.id]: list })))
        .catch(() => setConnectedCache(prev => ({ ...prev, [resource.id]: 'error' })));
    }
  }, [expandedResources, toggleResource]);

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
      'ElastiCache Cluster': 'elasticache-cluster.png',
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
      'API Gateway': 'api-gateway.png',
      // Tier 1 additions — only map types whose icon file exists in /public/icons;
      // the rest intentionally fall back to default-icon.png below.
      'WAF': 'waf.png',
      'RDS Cluster (Aurora)': 'rds-instance.png'
    };
    return iconMap[serviceType] || 'default-icon.png';
  };


  // Initialize WebSocket and load data
  useEffect(() => {
    isMountedRef.current = true;
    connectWebSocket();
    loadInitialResources(false);

    return () => {
      isMountedRef.current = false;
      // Abort any in-flight fetch requests before disconnecting WebSocket
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
        abortControllerRef.current = null;
      }
      disconnectWebSocket();
      if (refreshTimeoutRef.current) {
        clearTimeout(refreshTimeoutRef.current);
      }
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
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.03)] rounded-lg border border-[rgba(0,0,0,0.08)]">
          <div className={`w-1.5 h-1.5 rounded-full ${statusColor === 'green' ? 'bg-green-500 animate-pulse' : statusColor === 'yellow' ? 'bg-amber-400' : 'bg-red-500'}`}></div>
          <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">{statusMessage}</span>
        </div>
        <button
          onClick={() => loadInitialResources(true)}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white text-xs font-bold rounded-xl hover:bg-black transition-all disabled:opacity-50"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, statusMessage, statusColor, loadInitialResources]);

  const getStateBadge = (state: string) => {
    const s = (state || '').toLowerCase();
    if (['running', 'available', 'active', 'in-use', 'issued'].includes(s))
      return 'bg-[#16A34A14] text-[#16A34A] border border-[#16A34A20]';
    if (['stopped', 'terminated', 'deleting', 'inactive', 'expired'].includes(s))
      return 'bg-[#DC262614] text-[#DC2626] border border-[#DC262620]';
    if (['pending', 'stopping', 'pending_validation'].includes(s))
      return 'bg-[#D9770614] text-[#D97706] border border-[#D9770620]';
    return 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border border-[rgba(0,0,0,0.08)]';
  };

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
                  value={selectedRegion}
                  onChange={(e) => setSelectedRegion(e.target.value)}
                  className="pl-9 pr-8 py-2 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] min-w-[180px] appearance-none"
                >
                  <option value="">All Regions</option>
                  {allRegions.map(region => (
                    <option key={region} value={region}>{region}</option>
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
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  placeholder="Search by name or ID…"
                  className="w-full pl-9 pr-4 py-2 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
                />
              </div>

              {/* Label filter */}
              <div className="relative">
                <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-[rgba(0,0,0,0.3)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z" />
                </svg>
                <select
                  value={filterLabelId}
                  onChange={(e) => setFilterLabelId(e.target.value)}
                  className="pl-9 pr-8 py-2 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] min-w-[160px] appearance-none"
                >
                  <option value="">All Labels</option>
                  {allLabels.map(l => (
                    <option key={l.id} value={l.id}>{l.name}</option>
                  ))}
                </select>
              </div>
            </div>

            <div className="flex items-center gap-3">
              <p className="text-[12px] text-[rgba(0,0,0,0.4)] font-medium whitespace-nowrap">
                {totalResourceCount} resources · {allGroups.length} services
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

        {/* Bulk assign bar */}
        {selectedResources.size > 0 && (
          <div className="flex items-center gap-3 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-xl px-5 py-3 mb-4">
            <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{selectedResources.size} selected</span>
            <select
              value={bulkLabelId}
              onChange={e => setBulkLabelId(e.target.value)}
              className="border border-[rgba(0,0,0,0.12)] rounded-lg px-3 py-1.5 text-[13px] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
            >
              <option value="">Assign label…</option>
              {allLabels.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
            <button
              onClick={handleBulkAssign}
              disabled={!bulkLabelId}
              className="px-4 py-1.5 bg-[rgba(0,0,0,0.88)] text-white text-[12px] font-bold rounded-lg hover:bg-black disabled:opacity-40 transition-colors"
            >
              Apply
            </button>
            <button
              onClick={() => setSelectedResources(new Set())}
              className="ml-auto text-[12px] text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
            >
              Clear
            </button>
          </div>
        )}

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
                ? 'Select a cloud account from the sidebar to view resources.'
                : statusColor === 'yellow'
                  ? 'The backend is fetching from AWS. This may take 2–5 minutes. Click Refresh when ready.'
                  : 'No resources found for this account. Click Refresh to fetch from AWS.'}
            </p>
            {selectedAccountId && statusColor !== 'yellow' && (
              <button
                onClick={() => loadInitialResources(true)}
                disabled={isRefreshing}
                className="px-5 py-2 text-[13px] font-semibold text-white bg-[rgba(0,0,0,0.88)] rounded-lg hover:bg-black disabled:opacity-50 transition-colors"
              >
                <i className={`fas fa-sync-alt mr-2 ${isRefreshing ? 'animate-spin' : ''}`}></i>
                {isRefreshing ? 'Fetching…' : 'Fetch from AWS'}
              </button>
            )}
          </div>
        ) : (
          <>
            <div className="space-y-3">
              {filteredGroups.map((group, index) => (
                <AnimatedItem key={group.serviceType} index={index} delay={index * 0.04}>
                  <div
                    className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm"
                    style={group.resources.some(r => openPopoverResourceId === r.id)
                      ? { overflow: 'visible', zIndex: 200, position: 'relative' }
                      : { overflow: 'hidden' }}
                  >
                    {/* Service Header */}
                    <div
                      className="flex items-center justify-between px-5 py-4 cursor-pointer hover:bg-[rgba(0,0,0,0.02)] transition-colors"
                      onClick={() => toggleGroup(group.serviceType)}
                    >
                      <div className="flex items-center gap-4 flex-1">
                        <svg
                          className={`w-3.5 h-3.5 text-[rgba(0,0,0,0.3)] transition-transform duration-200 flex-shrink-0 ${expandedGroups.has(group.serviceType) ? 'rotate-90' : ''}`}
                          fill="none" stroke="currentColor" viewBox="0 0 24 24"
                        >
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M9 5l7 7-7 7" />
                        </svg>
                        <div className="w-10 h-10 flex items-center justify-center rounded-lg bg-[rgba(0,0,0,0.03)] flex-shrink-0">
                          <img src={`/icons/${getServiceIcon(group.serviceType)}`} alt={group.serviceType} className="w-6 h-6 object-contain" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{group.serviceType}</p>
                          <p className="text-[11px] text-[rgba(0,0,0,0.35)] uppercase tracking-wide">AWS Service</p>
                        </div>
                      </div>
                      <span className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.05)] px-3 py-1 rounded-full tabular-nums">
                        {group.resources.length}
                      </span>
                    </div>

                    {/* Resources Table */}
                    {expandedGroups.has(group.serviceType) && (
                      <div className="border-t border-[rgba(0,0,0,0.06)]">
                        {/* Table header */}
                        <div className="grid grid-cols-12 items-center px-5 py-2 bg-[rgba(0,0,0,0.02)]">
                          <div className="col-span-1"></div>
                          <div className="col-span-4 text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Name / ID</div>
                          <div className="col-span-2 text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Region</div>
                          <div className="col-span-2 text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">State</div>
                          <div className="col-span-2 text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Labels</div>
                          <div className="col-span-1"></div>
                        </div>

                        {group.resources.map(resource => {
                          const hasDetails = resource.details && Object.keys(resource.details).length > 0;
                          const isExpanded = expandedResources.has(resource.id);
                          const resourceLabels = labelMap[resource.id] ?? [];
                          const isSelected = selectedResources.has(resource.id);

                          return (
                            <div
                              key={resource.id}
                              className="border-t border-[rgba(0,0,0,0.06)]"
                              style={openPopoverResourceId === resource.id ? { overflow: 'visible', zIndex: 100, position: 'relative' } : undefined}
                            >
                              {/* Resource row */}
                              <div
                                className="grid grid-cols-12 items-center px-5 py-3 hover:bg-[rgba(0,0,0,0.015)] transition-colors cursor-pointer"
                                onClick={() => selectedAccountId && handleRowExpand(resource, selectedAccountId.split(',')[0])}
                              >
                                {/* Checkbox */}
                                <div className="col-span-1 flex items-center gap-2" onClick={e => e.stopPropagation()}>
                                  <input
                                    type="checkbox"
                                    checked={isSelected}
                                    onChange={() => toggleResourceSelection(resource.id)}
                                    className="w-3.5 h-3.5 rounded border-[rgba(0,0,0,0.2)] cursor-pointer accent-black"
                                  />
                                  <svg
                                    className={`w-3 h-3 text-[rgba(0,0,0,0.3)] transition-transform duration-200 ${isExpanded ? 'rotate-90' : ''}`}
                                    fill="none" stroke="currentColor" viewBox="0 0 24 24"
                                  >
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M9 5l7 7-7 7" />
                                  </svg>
                                </div>

                                {/* Name / ID */}
                                <div className="col-span-4 min-w-0 pr-3">
                                  <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate">{resource.name || 'N/A'}</p>
                                  <p className="font-mono text-[11px] text-[rgba(0,0,0,0.35)] truncate">{resource.id || 'N/A'}</p>
                                </div>

                                {/* Region */}
                                <div className="col-span-2">
                                  <span className="text-[12px] text-[rgba(0,0,0,0.5)]">{resource.region || 'Global'}</span>
                                </div>

                                {/* State */}
                                <div className="col-span-2">
                                  <span className={`inline-flex px-2.5 py-0.5 text-[11px] font-semibold rounded-full capitalize ${getStateBadge(resource.state)}`}>
                                    {resource.state || 'Unknown'}
                                  </span>
                                </div>

                                {/* Labels */}
                                <div
                                  className="col-span-2 flex items-center gap-1 flex-wrap overflow-visible"
                                  onClick={e => e.stopPropagation()}
                                >
                                  {resourceLabels.map(lbl => (
                                    <LabelBadge key={lbl.id} label={lbl} onRemove={() => handleRemoveBadgeLabel(resource, lbl)} />
                                  ))}
                                  <div className="relative">
                                    <button
                                      type="button"
                                      onClick={() => setOpenPopoverResourceId(openPopoverResourceId === resource.id ? null : resource.id)}
                                      className="w-5 h-5 flex items-center justify-center rounded-full border border-[rgba(0,0,0,0.15)] text-[rgba(0,0,0,0.35)] hover:border-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] text-xs transition-colors"
                                      title="Assign label"
                                    >+</button>
                                    {openPopoverResourceId === resource.id && selectedAccountId && (
                                      <LabelAssignPopover
                                        accountId={selectedAccountId.split(',')[0]}
                                        resource={{ resourceId: resource.id, resourceType: resource.type, resourceName: resource.name ?? undefined, region: resource.region }}
                                        assignedLabels={resourceLabels}
                                        allLabels={allLabels}
                                        onChanged={handleLabelMapChange}
                                        onClose={() => setOpenPopoverResourceId(null)}
                                        onLabelCreated={label => setAllLabels(prev => [...prev, label])}
                                      />
                                    )}
                                  </div>
                                </div>

                                {/* Info indicator */}
                                <div className="col-span-1 flex justify-end">
                                  {hasDetails && (
                                    <svg className="w-3.5 h-3.5 text-[rgba(0,0,0,0.2)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                                    </svg>
                                  )}
                                </div>
                              </div>

                              {/* Expanded panel */}
                              {isExpanded && (
                                <div className="border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                                  {/* Resource Details */}
                                  {hasDetails && (
                                    <div className="px-8 py-4 pl-14">
                                      <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)] mb-3">Resource Details</p>
                                      <div className="grid grid-cols-2 gap-x-8 gap-y-1.5">
                                        {Object.entries(resource.details).map(([key, value]) => (
                                          <div key={key} className="flex items-baseline gap-2">
                                            <span className="text-[12px] text-[rgba(0,0,0,0.4)] shrink-0">
                                              {key.replace(/([A-Z])/g, ' $1').replace(/^./, s => s.toUpperCase())}
                                            </span>
                                            <span className="text-[12px] font-medium text-[rgba(0,0,0,0.88)] truncate">
                                              {value !== null && value !== undefined ? String(value) : '—'}
                                            </span>
                                          </div>
                                        ))}
                                      </div>
                                    </div>
                                  )}

                                  {/* Connected Resources */}
                                  <div className={`px-8 pb-4 pl-14 ${hasDetails ? 'border-t border-[rgba(0,0,0,0.06)] pt-4' : 'pt-4'}`}>
                                    <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)] mb-3">Connected Resources</p>

                                    {connectedCache[resource.id] === 'loading' && (
                                      <div className="flex items-center gap-2 py-3 text-[13px] text-[rgba(0,0,0,0.4)]">
                                        <svg className="w-3.5 h-3.5 animate-spin" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                                        </svg>
                                        Discovering connected resources…
                                      </div>
                                    )}

                                    {connectedCache[resource.id] === 'error' && (
                                      <p className="text-[13px] text-[#DC2626] py-2">Failed to load connected resources.</p>
                                    )}

                                    {Array.isArray(connectedCache[resource.id]) && (connectedCache[resource.id] as ConnectedResource[]).length === 0 && (
                                      <p className="text-[13px] text-[rgba(0,0,0,0.35)] italic py-2">No connected resources found.</p>
                                    )}

                                    {Array.isArray(connectedCache[resource.id]) && (connectedCache[resource.id] as ConnectedResource[]).length > 0 && (() => {
                                      const connected = connectedCache[resource.id] as ConnectedResource[];
                                      const sourceLabels = labelMap[resource.id] ?? [];
                                      const untaggedCount = connected.filter(r => (labelMap[r.id] ?? r.labels ?? []).length === 0).length;

                                      return (
                                        <div className="space-y-1.5">
                                          {/* Summary */}
                                          <div className="flex items-center justify-between text-[12px] text-[rgba(0,0,0,0.4)] pb-2 border-b border-[rgba(0,0,0,0.06)]">
                                            <span>
                                              <span className="font-semibold text-[rgba(0,0,0,0.88)]">{connected.length}</span> resource{connected.length !== 1 ? 's' : ''}
                                              {untaggedCount > 0 && <span className="ml-2 text-amber-600 font-medium">· {untaggedCount} untagged</span>}
                                            </span>
                                            {sourceLabels.length > 0 && (
                                              <button
                                                type="button"
                                                disabled={applyingAllFor === resource.id || untaggedCount === 0}
                                                onClick={async () => {
                                                  if (!selectedAccountId) return;
                                                  setApplyingAllFor(resource.id);
                                                  try {
                                                    for (const cr of connected) {
                                                      const crLabels = labelMap[cr.id] ?? cr.labels ?? [];
                                                      const currentIds = new Set(crLabels.map((l: ResourceLabel) => l.id));
                                                      for (const lbl of sourceLabels.filter((l: ResourceLabel) => !currentIds.has(l.id))) {
                                                        await assignLabel({ accountId: selectedAccountId.split(',')[0], labelId: lbl.id, resources: [{ resourceId: cr.id, resourceType: cr.type, resourceName: cr.name ?? undefined, region: cr.region }] });
                                                        handleLabelMapChange(cr.id, [...crLabels, lbl]);
                                                      }
                                                    }
                                                  } finally { setApplyingAllFor(null); }
                                                }}
                                                className="flex items-center gap-1.5 px-3 py-1 text-[11px] font-bold rounded-lg bg-[rgba(0,0,0,0.88)] text-white hover:bg-black disabled:opacity-40 transition-colors"
                                              >
                                                {applyingAllFor === resource.id
                                                  ? <><svg className="w-3 h-3 animate-spin" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg> Applying…</>
                                                  : 'Apply labels to untagged'}
                                              </button>
                                            )}
                                          </div>

                                          {/* Connected rows */}
                                          {connected.map(cr => {
                                            const crLabels: ResourceLabel[] = labelMap[cr.id] ?? cr.labels ?? [];
                                            const popKey = `${resource.id}::${cr.id}`;
                                            return (
                                              <div key={cr.id} className="flex items-start gap-3 p-3 rounded-lg border border-[rgba(0,0,0,0.06)] hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                                                <div className="w-7 h-7 flex-shrink-0 flex items-center justify-center rounded-md bg-[rgba(0,0,0,0.04)]">
                                                  <svg className="w-3.5 h-3.5 text-[rgba(0,0,0,0.4)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2" />
                                                  </svg>
                                                </div>
                                                <div className="flex-1 min-w-0">
                                                  <div className="flex items-center gap-2 flex-wrap">
                                                    <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate max-w-[200px]">{cr.name ?? cr.id}</span>
                                                    <span className="font-mono text-[11px] text-[rgba(0,0,0,0.35)] truncate max-w-[160px]">{cr.id}</span>
                                                    <span className="text-[11px] bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] px-1.5 py-0.5 rounded-full">{cr.type}</span>
                                                    <span className="text-[11px] text-[rgba(0,0,0,0.3)]">{cr.region}</span>
                                                  </div>
                                                  {cr.details && Object.keys(cr.details).length > 0 && (
                                                    <div className="flex items-center gap-3 mt-1 flex-wrap">
                                                      {Object.entries(cr.details).map(([k, v]) => v ? (
                                                        <span key={k} className="text-[11px] text-[rgba(0,0,0,0.4)]">
                                                          <span className="text-[rgba(0,0,0,0.25)]">{k}:</span> {v}
                                                        </span>
                                                      ) : null)}
                                                    </div>
                                                  )}
                                                  <div className="flex items-center gap-1 mt-1.5 flex-wrap" onClick={e => e.stopPropagation()}>
                                                    {crLabels.length === 0
                                                      ? <span className="text-[11px] text-[rgba(0,0,0,0.3)] italic">No labels</span>
                                                      : crLabels.map((lbl: ResourceLabel) => (
                                                          <LabelBadge key={lbl.id} label={lbl} onRemove={() => handleLabelMapChange(cr.id, crLabels.filter((l: ResourceLabel) => l.id !== lbl.id))} />
                                                        ))
                                                    }
                                                    <div className="relative">
                                                      <button
                                                        type="button"
                                                        onClick={() => setOpenConnectedPopoverId(openConnectedPopoverId === popKey ? null : popKey)}
                                                        className="w-5 h-5 flex items-center justify-center rounded-full border border-[rgba(0,0,0,0.15)] text-[rgba(0,0,0,0.35)] hover:border-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] text-xs transition-colors"
                                                        title="Assign label"
                                                      >+</button>
                                                      {openConnectedPopoverId === popKey && selectedAccountId && (
                                                        <LabelAssignPopover
                                                          accountId={selectedAccountId.split(',')[0]}
                                                          resource={{ resourceId: cr.id, resourceType: cr.type, resourceName: cr.name ?? undefined, region: cr.region }}
                                                          assignedLabels={crLabels}
                                                          allLabels={allLabels}
                                                          onChanged={handleLabelMapChange}
                                                          onClose={() => setOpenConnectedPopoverId(null)}
                                                          onLabelCreated={lbl => setAllLabels(prev => [...prev, lbl])}
                                                        />
                                                      )}
                                                    </div>
                                                  </div>
                                                </div>
                                              </div>
                                            );
                                          })}
                                        </div>
                                      );
                                    })()}
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
                  onClick={loadMoreResources}
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
export default CloudListPage;
