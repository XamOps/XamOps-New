/**
 * CloudSitter Custom Hook
 *
 * Manages CloudSitter state and API interactions
 */

import { useState, useCallback, useEffect, useRef } from 'react';
import type {
  EC2Resource,
  CloudSitterPolicy,
  PolicyAssignment,
  PolicyFormData,
  ViewType,
  AuditLogEntry,
} from '../types/cloudsitter';

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const DAY_KEYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

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

function computeRecommendedSavings(
  instanceType: string,
  recommendedSchedule: Record<string, number[]>,
): { savings: number; offHoursPerWeek: number } {
  const runningHoursPerWeek = DAY_KEYS.reduce(
    (sum, k) => sum + (recommendedSchedule[k]?.length ?? 0), 0
  );
  const offHoursPerWeek = 7 * 24 - runningHoursPerWeek;
  const offHoursPerMonth = offHoursPerWeek * 4.33;
  const hourlyPrice = HOURLY_PRICES[instanceType.toLowerCase()] ?? 0.05;
  const savings = Math.round(hourlyPrice * offHoursPerMonth * 100) / 100;
  return { savings, offHoursPerWeek };
}

export const useCloudSitter = (accountId: string | null) => {
  // State
  const [currentView, setCurrentView] = useState<ViewType>('dashboard');
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [resources, setResources] = useState<EC2Resource[]>([]);
  const [policies, setPolicies] = useState<CloudSitterPolicy[]>([]);
  const [, setAssignments] = useState<Record<string, PolicyAssignment>>({});

  const abortControllerRef = useRef<AbortController | null>(null);

  // Policy Form State
  const [policyForm, setPolicyForm] = useState<PolicyFormData>({
    id: null,
    name: '',
    type: 'SCHEDULE',
    timezone: 'Asia/Kolkata',
    notificationsEnabled: true,
  });
  const [policyGrid, setPolicyGrid] = useState<boolean[][]>([]);
  const [editMode, setEditMode] = useState(false);
  const [isDragging, setIsDragging] = useState(false);
  const [dragState, setDragState] = useState(true);

  // Audit Log
  const [auditLogs, setAuditLogs] = useState<AuditLogEntry[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [auditTotalPages, setAuditTotalPages] = useState(0);
  const [auditPage, setAuditPage] = useState(0);

  // Modals
  const [showAssignModal, setShowAssignModal] = useState(false);
  const [showManagePoliciesModal, setShowManagePoliciesModal] = useState(false);
  const [assignTarget, setAssignTarget] = useState<EC2Resource | null>(null);
  const [selectedPolicyId, setSelectedPolicyId] = useState('');
  const [isCreatingFromAssign, setIsCreatingFromAssign] = useState(false);
  const [formInstanceTarget, setFormInstanceTarget] = useState<EC2Resource | null>(null);

  /**
   * Reset grid to all running (true)
   */
  const resetGrid = useCallback(() => {
    setPolicyGrid(Array(7).fill(null).map(() => Array(24).fill(true)));
  }, []);

  /**
   * Load grid from JSON
   */
  const loadGridFromJson = useCallback((jsonString: string) => {
    try {
      const scheduleMap = JSON.parse(jsonString);
      const newGrid = Array(7).fill(null).map(() => Array(24).fill(false));

      DAYS.forEach((day, dIndex) => {
        const upperDay = day.toUpperCase();
        if (scheduleMap[upperDay]) {
          const activeHours = scheduleMap[upperDay];
          activeHours.forEach((hour: number) => {
            if (hour >= 0 && hour < 24) newGrid[dIndex][hour] = true;
          });
        }
      });

      setPolicyGrid(newGrid);
    } catch (e) {
      console.error('Error parsing schedule JSON', e);
      resetGrid();
    }
  }, [resetGrid]);

  /**
   * Convert grid to JSON
   */
  const convertGridToJson = useCallback(() => {
    const scheduleMap: Record<string, number[]> = {};
    DAYS.forEach((day, index) => {
      const activeHours: number[] = [];
      policyGrid[index]?.forEach((isActive, hour) => {
        if (isActive) activeHours.push(hour);
      });
      scheduleMap[day.toUpperCase()] = activeHours;
    });
    return JSON.stringify(scheduleMap);
  }, [policyGrid]);

  /**
   * Load data from API
   */
  const loadData = useCallback(async (forceRefresh = false) => {
    if (!accountId) return;

    // Cancel any in-flight request before starting a new one
    abortControllerRef.current?.abort();
    abortControllerRef.current = new AbortController();
    const { signal } = abortControllerRef.current;

    setIsLoading(true);
    console.log('Loading CloudSitter data for account:', accountId);

    try {
      // Cache key for EC2 instances
      const cacheKey = `cloudlist-ec2-${accountId}`;
      const cacheTimestampKey = `cloudlist-ec2-timestamp-${accountId}`;
      const CACHE_DURATION = 5 * 60 * 1000; // 5 minutes

      let ec2Instances: any[] = [];

      // Check cache first (unless force refresh)
      if (!forceRefresh) {
        const cachedData = sessionStorage.getItem(cacheKey);
        const cachedTimestamp = sessionStorage.getItem(cacheTimestampKey);

        if (cachedData && cachedTimestamp) {
          const age = Date.now() - parseInt(cachedTimestamp);
          if (age < CACHE_DURATION) {
            console.log('✅ Using cached EC2 data (age: ' + Math.round(age / 1000) + 's)');
            ec2Instances = JSON.parse(cachedData);
          }
        }
      } else {
        // Clear cache on force refresh
        sessionStorage.removeItem(`cloudlist-ec2-${accountId}`);
        sessionStorage.removeItem(`cloudlist-ec2-timestamp-${accountId}`);
      }

      // Fetch fresh data if cache miss or force refresh
      const fetchPromises: Promise<any>[] = [];

      if (ec2Instances.length === 0 || forceRefresh) {
        console.log('🔄 Fetching fresh EC2 data from CloudList API');
        fetchPromises.push(
          fetch(`/api/xamops/cloudlist/all?accountId=${accountId}&size=100`, { signal })
            .then(async (resResponse) => {
              if (resResponse.ok) {
                const resData = await resResponse.json();
                const allResources = resData.content || resData;
                ec2Instances = allResources.filter((r: any) => r.type === 'EC2 Instance');
                console.log(`✅ Found ${ec2Instances.length} EC2 instances`);

                // Cache the EC2 instances
                sessionStorage.setItem(cacheKey, JSON.stringify(ec2Instances));
                sessionStorage.setItem(cacheTimestampKey, Date.now().toString());
              } else {
                console.error('❌ CloudList API error:', resResponse.status, resResponse.statusText);
              }
            })
            .catch((err) => {
              if (err instanceof DOMException && err.name === 'AbortError') throw err;
              console.error('❌ CloudList API fetch failed:', err);
            })
        );
      }

      fetchPromises.push(
        fetch('/api/xamops/cloudsitter/policies', { signal }).then(r => r.json()),
        fetch(`/api/xamops/cloudsitter/assignments?accountId=${accountId}`, { signal }).then(r => r.json())
      );

      const results = await Promise.all(fetchPromises);
      const polResponse = fetchPromises.length === 3 ? results[1] : results[0];
      const assignResponse = fetchPromises.length === 3 ? results[2] : results[1];

      setAssignments(assignResponse);
      setPolicies(polResponse);

      const mappedResources = ec2Instances.map((r: any) => {
        const assignment = assignResponse[r.id];
        return {
          id: r.id,
          name: r.name,
          region: r.region,
          type: r.details?.['Type'] || 't3.micro',
          state: r.state || 'unknown',
          policyName: assignment ? assignment.policy.name : null,
          savings: assignment ? (assignment.monthlySavings || 0) : 0,
        };
      });

      setResources(mappedResources);
      console.log(`✅ Mapped ${mappedResources.length} resources for CloudSitter display`);

      // Fetch CloudWatch-based recommendations for unmanaged running instances in parallel.
      // recommendedSavings starts as undefined (loading) and is updated when fetches complete.
      const unmanagedRunning = mappedResources.filter(
        (r) => !r.policyName && r.state === 'running'
      );

      if (unmanagedRunning.length > 0) {
        Promise.allSettled(
          unmanagedRunning.map((r) => {
            const cacheKey = `cs-rec-${accountId}-${r.id}`;
            const cached = sessionStorage.getItem(cacheKey);
            if (cached) {
              return Promise.resolve({ instanceId: r.id, instanceType: r.type, rec: JSON.parse(cached) });
            }
            return fetch(
              `/api/xamops/cloudsitter/recommendation?accountId=${accountId}&instanceId=${r.id}&region=${r.region}&lookbackDays=30`,
              { signal }
            )
              .then((res) => (res.ok ? res.json() : null))
              .then((rec) => {
                if (rec) sessionStorage.setItem(cacheKey, JSON.stringify(rec));
                return { instanceId: r.id, instanceType: r.type, rec };
              });
          })
        ).then((results) => {
          const savingsMap: Record<string, { savings: number; offHoursPerWeek: number }> = {};
          results.forEach((result) => {
            if (result.status === 'fulfilled' && result.value?.rec) {
              const { instanceId, instanceType, rec } = result.value;
              savingsMap[instanceId] = computeRecommendedSavings(instanceType, rec.recommendedSchedule);
            }
          });

          setResources((prev) =>
            prev.map((r) => {
              if (r.policyName || r.state !== 'running') return r;
              const computed = savingsMap[r.id];
              return {
                ...r,
                recommendedSavings: computed !== undefined ? computed.savings : null,
                recommendedOffHoursPerWeek: computed !== undefined ? computed.offHoursPerWeek : null,
              };
            })
          );
        });
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return;
      console.error('Failed to load CloudSitter data:', e);
    } finally {
      setIsLoading(false);
    }
  }, [accountId]);

  /**
   * Filtered resources based on search query
   */
  const filteredResources = useCallback(() => {
    if (!searchQuery) return resources;
    const q = searchQuery.toLowerCase();
    return resources.filter(
      (r) =>
        (r.name && r.name.toLowerCase().includes(q)) ||
        (r.id && r.id.toLowerCase().includes(q))
    );
  }, [resources, searchQuery])();

  /**
   * Open create policy view
   */
  const openCreatePolicy = useCallback(() => {
    setPolicyForm({
      id: null,
      name: '',
      type: 'SCHEDULE',
      timezone: 'Asia/Kolkata',
      notificationsEnabled: true,
    });
    resetGrid();
    setEditMode(false);
    setCurrentView('createPolicy');
  }, [resetGrid]);

  /**
   * Close create policy view
   */
  const closeCreatePolicy = useCallback(() => {
    setCurrentView('dashboard');
    if (isCreatingFromAssign) {
      setShowAssignModal(true);
      setIsCreatingFromAssign(false);
    }
  }, [isCreatingFromAssign]);

  /**
   * Open create policy from assign modal — preserves assign target as form context
   */
  const openCreatePolicyFromAssign = useCallback(() => {
    setIsCreatingFromAssign(true);
    setFormInstanceTarget(assignTarget);
    setShowAssignModal(false);
    openCreatePolicy();
  }, [openCreatePolicy, assignTarget]);

  /**
   * Apply a recommendation grid (boolean[][]) to the policy grid
   */
  const applyRecommendationToGrid = useCallback((grid: boolean[][]) => {
    setPolicyGrid(grid);
  }, []);

  /**
   * Toggle cell in grid
   */
  const toggleCell = useCallback((d: number, h: number) => {
    setPolicyGrid((prev) => {
      const newGrid = [...prev];
      const newState = !newGrid[d][h];
      newGrid[d] = [...newGrid[d]];
      newGrid[d][h] = newState;
      setDragState(newState);
      return newGrid;
    });
  }, []);

  /**
   * Drag toggle cell
   */
  const dragToggle = useCallback(
    (d: number, h: number) => {
      if (isDragging) {
        setPolicyGrid((prev) => {
          const newGrid = [...prev];
          newGrid[d] = [...newGrid[d]];
          newGrid[d][h] = dragState;
          return newGrid;
        });
      }
    },
    [isDragging, dragState]
  );

  /**
   * Save policy
   */
  const savePolicy = useCallback(async () => {
    if (!policyForm.name) {
      alert('Please enter a policy name.');
      return;
    }

    setIsSaving(true);
    const payload = {
      name: policyForm.name,
      type: policyForm.type,
      timeZone: policyForm.timezone,
      notificationsEnabled: policyForm.notificationsEnabled,
      scheduleConfig: convertGridToJson(),
    };

    try {
      let url = '/api/xamops/cloudsitter/policies';
      let method = 'POST';

      if (editMode && policyForm.id) {
        url += '/' + policyForm.id;
        method = 'PUT';
      }

      const res = await fetch(url, {
        method: method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (res.ok) {
        const savedPolicy = await res.json();

        // Refresh policy list
        const polResponse = await fetch('/api/xamops/cloudsitter/policies');
        const updatedPolicies = await polResponse.json();
        setPolicies(updatedPolicies);

        setCurrentView('dashboard');
        setShowManagePoliciesModal(false);

        // If we came from Assign Modal, auto-select this new policy
        if (isCreatingFromAssign) {
          setSelectedPolicyId(savedPolicy.id);
          setShowAssignModal(true);
          setIsCreatingFromAssign(false);
        }
      } else {
        alert('Failed to save policy.');
      }
    } catch (e) {
      console.error('Save failed', e);
      alert('Error saving policy.');
    } finally {
      setIsSaving(false);
    }
  }, [policyForm, editMode, convertGridToJson, isCreatingFromAssign]);

  /**
   * Edit policy
   */
  const editPolicy = useCallback(
    (policy: CloudSitterPolicy) => {
      setPolicyForm({
        id: policy.id,
        name: policy.name,
        type: policy.type,
        timezone: policy.timeZone,
        notificationsEnabled: policy.notificationsEnabled,
      });
      if (policy.scheduleJson) {
        loadGridFromJson(policy.scheduleJson);
      }
      setEditMode(true);
      setShowManagePoliciesModal(false);
      setCurrentView('createPolicy');
    },
    [loadGridFromJson]
  );

  /**
   * Delete policy
   */
  const deletePolicy = useCallback(async (id: string) => {
    if (
      !confirm(
        'Are you sure you want to delete this policy? Any instances using it will be unassigned.'
      )
    )
      return;

    try {
      const res = await fetch(`/api/xamops/cloudsitter/policies/${id}`, {
        method: 'DELETE',
      });
      if (res.ok) {
        setPolicies((prev) => prev.filter((p) => p.id !== id));
        loadData();
      } else {
        alert('Failed to delete policy');
      }
    } catch (e) {
      console.error(e);
      alert('Error deleting policy');
    }
  }, [loadData]);

  /**
   * Open assign modal
   */
  const openAssignModal = useCallback((resource: EC2Resource) => {
    setAssignTarget(resource);
    setSelectedPolicyId('');
    setShowAssignModal(true);
  }, []);

  /**
   * Submit assignment
   */
  const submitAssignment = useCallback(async () => {
    if (!selectedPolicyId) {
      alert('Please select a policy.');
      return;
    }

    if (!assignTarget || !accountId) return;

    try {
      const payload = {
        policyId: selectedPolicyId,
        instanceIds: [assignTarget.id],
        accountId: accountId,
        region: assignTarget.region,
      };

      const res = await fetch('/api/xamops/cloudsitter/assign', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (res.ok) {
        setShowAssignModal(false);
        await loadData();
      } else {
        alert('Failed to assign policy.');
      }
    } catch (e) {
      console.error('Assignment failed', e);
      alert('Error assigning policy.');
    }
  }, [selectedPolicyId, assignTarget, accountId, loadData]);

  const loadAuditLog = useCallback(async (page = 0) => {
    if (!accountId) return;
    setAuditLoading(true);
    try {
      const res = await fetch(`/api/xamops/cloudsitter/audit-log?accountId=${accountId}&page=${page}&size=50`);
      if (res.ok) {
        const data = await res.json();
        setAuditLogs(data.content ?? []);
        setAuditTotalPages(data.totalPages ?? 0);
        setAuditPage(page);
      }
    } catch (e) {
      console.error('Failed to load audit log:', e);
    } finally {
      setAuditLoading(false);
    }
  }, [accountId]);

  // Initialize on mount and account change
  useEffect(() => {
    if (accountId) {
      loadData();
    }
    resetGrid();
  }, [accountId, loadData, resetGrid]);

  return {
    // State
    currentView,
    isLoading,
    isSaving,
    searchQuery,
    resources: filteredResources,
    allResources: resources,
    policies,
    policyForm,
    policyGrid,
    editMode,
    isDragging,
    showAssignModal,
    showManagePoliciesModal,
    assignTarget,
    selectedPolicyId,

    // Actions
    setCurrentView,
    setSearchQuery,
    setPolicyForm,
    setIsDragging,
    setShowAssignModal,
    setShowManagePoliciesModal,
    setSelectedPolicyId,
    loadData,
    openCreatePolicy,
    closeCreatePolicy,
    openCreatePolicyFromAssign,
    resetGrid,
    toggleCell,
    dragToggle,
    savePolicy,
    editPolicy,
    deletePolicy,
    openAssignModal,
    submitAssignment,
    formInstanceTarget,
    applyRecommendationToGrid,
    auditLogs,
    auditLoading,
    auditTotalPages,
    auditPage,
    loadAuditLog,
  };
};
