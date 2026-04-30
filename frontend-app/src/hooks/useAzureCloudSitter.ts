import { useState, useCallback, useEffect, useRef } from 'react';
import type {
  EC2Resource,
  CloudSitterPolicy,
  PolicyAssignment,
  PolicyFormData,
  ViewType,
} from '../types/cloudsitter';

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

export const useAzureCloudSitter = (subscriptionId: string | null) => {
  const [currentView, setCurrentView] = useState<ViewType>('dashboard');
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [resources, setResources] = useState<EC2Resource[]>([]);
  const [policies, setPolicies] = useState<CloudSitterPolicy[]>([]);
  const [, setAssignments] = useState<Record<string, PolicyAssignment>>({});

  const abortControllerRef = useRef<AbortController | null>(null);

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

  const [showAssignModal, setShowAssignModal] = useState(false);
  const [showManagePoliciesModal, setShowManagePoliciesModal] = useState(false);
  const [assignTarget, setAssignTarget] = useState<EC2Resource | null>(null);
  const [selectedPolicyId, setSelectedPolicyId] = useState('');
  const [isCreatingFromAssign, setIsCreatingFromAssign] = useState(false);
  const [formInstanceTarget, setFormInstanceTarget] = useState<EC2Resource | null>(null);

  const resetGrid = useCallback(() => {
    setPolicyGrid(Array(7).fill(null).map(() => Array(24).fill(true)));
  }, []);

  const loadGridFromJson = useCallback((jsonString: string) => {
    try {
      const scheduleMap = JSON.parse(jsonString);
      const newGrid = Array(7).fill(null).map(() => Array(24).fill(false));
      DAYS.forEach((day, dIndex) => {
        const activeHours = scheduleMap[day.toUpperCase()];
        if (activeHours) {
          activeHours.forEach((hour: number) => {
            if (hour >= 0 && hour < 24) newGrid[dIndex][hour] = true;
          });
        }
      });
      setPolicyGrid(newGrid);
    } catch {
      resetGrid();
    }
  }, [resetGrid]);

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

  const loadData = useCallback(async (forceRefresh = false) => {
    if (!subscriptionId) return;

    abortControllerRef.current?.abort();
    abortControllerRef.current = new AbortController();
    const { signal } = abortControllerRef.current;

    setIsLoading(true);
    try {
      const cacheKey = `azure-cloudsitter-vms-${subscriptionId}`;
      const cacheTimestampKey = `azure-cloudsitter-vms-ts-${subscriptionId}`;
      const CACHE_DURATION = 5 * 60 * 1000;

      let azureVms: any[] = [];

      if (!forceRefresh) {
        const cached = sessionStorage.getItem(cacheKey);
        const ts = sessionStorage.getItem(cacheTimestampKey);
        if (cached && ts && Date.now() - parseInt(ts) < CACHE_DURATION) {
          azureVms = JSON.parse(cached);
        }
      } else {
        sessionStorage.removeItem(cacheKey);
        sessionStorage.removeItem(cacheTimestampKey);
      }

      const fetchPromises: Promise<any>[] = [];

      if (azureVms.length === 0 || forceRefresh) {
        fetchPromises.push(
          fetch(`/api/azure/cloudsitter/vms?subscriptionId=${encodeURIComponent(subscriptionId)}`, { signal })
            .then(async (r) => {
              if (r.ok) {
                azureVms = await r.json();
                sessionStorage.setItem(cacheKey, JSON.stringify(azureVms));
                sessionStorage.setItem(cacheTimestampKey, Date.now().toString());
              }
            })
            .catch((err) => {
              if (err instanceof DOMException && err.name === 'AbortError') throw err;
            })
        );
      }

      fetchPromises.push(
        fetch('/api/azure/cloudsitter/policies', { signal }).then(r => r.json()),
        fetch(`/api/azure/cloudsitter/assignments?subscriptionId=${encodeURIComponent(subscriptionId)}`, { signal }).then(r => r.json())
      );

      const results = await Promise.all(fetchPromises);
      const polResponse = fetchPromises.length === 3 ? results[1] : results[0];
      const assignResponse = fetchPromises.length === 3 ? results[2] : results[1];

      setAssignments(assignResponse);
      setPolicies(polResponse);

      const mappedResources = azureVms.map((vm: any) => {
        const resourceKey = `${vm.name}|${vm.resourceGroup}`;
        const assignment = assignResponse[resourceKey];
        return {
          id: resourceKey,
          name: vm.name,
          region: vm.resourceGroup,
          type: vm.size || 'Standard_D2s_v3',
          state: vm.state || 'Unknown',
          policyName: assignment ? assignment.policy.name : null,
          savings: assignment ? (assignment.monthlySavings || 0) : 0,
        };
      });

      setResources(mappedResources);
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return;
      console.error('Failed to load Azure CloudSitter data:', e);
    } finally {
      setIsLoading(false);
    }
  }, [subscriptionId]);

  const filteredResources = useCallback(() => {
    if (!searchQuery) return resources;
    const q = searchQuery.toLowerCase();
    return resources.filter(
      r => (r.name && r.name.toLowerCase().includes(q)) || (r.id && r.id.toLowerCase().includes(q))
    );
  }, [resources, searchQuery])();

  const openCreatePolicy = useCallback(() => {
    setPolicyForm({ id: null, name: '', type: 'SCHEDULE', timezone: 'Asia/Kolkata', notificationsEnabled: true });
    resetGrid();
    setEditMode(false);
    setCurrentView('createPolicy');
  }, [resetGrid]);

  const closeCreatePolicy = useCallback(() => {
    setCurrentView('dashboard');
    if (isCreatingFromAssign) {
      setShowAssignModal(true);
      setIsCreatingFromAssign(false);
    }
  }, [isCreatingFromAssign]);

  const openCreatePolicyFromAssign = useCallback(() => {
    setIsCreatingFromAssign(true);
    setFormInstanceTarget(assignTarget);
    setShowAssignModal(false);
    openCreatePolicy();
  }, [openCreatePolicy, assignTarget]);

  const applyRecommendationToGrid = useCallback((grid: boolean[][]) => {
    setPolicyGrid(grid);
  }, []);

  const toggleCell = useCallback((d: number, h: number) => {
    setPolicyGrid(prev => {
      const newGrid = [...prev];
      const newState = !newGrid[d][h];
      newGrid[d] = [...newGrid[d]];
      newGrid[d][h] = newState;
      setDragState(newState);
      return newGrid;
    });
  }, []);

  const dragToggle = useCallback((d: number, h: number) => {
    if (isDragging) {
      setPolicyGrid(prev => {
        const newGrid = [...prev];
        newGrid[d] = [...newGrid[d]];
        newGrid[d][h] = dragState;
        return newGrid;
      });
    }
  }, [isDragging, dragState]);

  const savePolicy = useCallback(async () => {
    if (!policyForm.name) { alert('Please enter a policy name.'); return; }
    setIsSaving(true);
    const payload = {
      name: policyForm.name,
      type: policyForm.type,
      timeZone: policyForm.timezone,
      notificationsEnabled: policyForm.notificationsEnabled,
      scheduleConfig: convertGridToJson(),
    };
    try {
      let url = '/api/azure/cloudsitter/policies';
      let method = 'POST';
      if (editMode && policyForm.id) { url += '/' + policyForm.id; method = 'PUT'; }

      const res = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (res.ok) {
        const savedPolicy = await res.json();
        const polResponse = await fetch('/api/azure/cloudsitter/policies');
        setPolicies(await polResponse.json());
        setCurrentView('dashboard');
        setShowManagePoliciesModal(false);
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

  const editPolicy = useCallback((policy: CloudSitterPolicy) => {
    setPolicyForm({ id: policy.id, name: policy.name, type: policy.type, timezone: policy.timeZone, notificationsEnabled: policy.notificationsEnabled });
    if (policy.scheduleJson) loadGridFromJson(policy.scheduleJson);
    setEditMode(true);
    setShowManagePoliciesModal(false);
    setCurrentView('createPolicy');
  }, [loadGridFromJson]);

  const deletePolicy = useCallback(async (id: string) => {
    if (!confirm('Delete this policy? VMs using it will be unassigned.')) return;
    try {
      const res = await fetch(`/api/azure/cloudsitter/policies/${id}`, { method: 'DELETE' });
      if (res.ok) { setPolicies(prev => prev.filter(p => p.id !== id)); loadData(); }
      else alert('Failed to delete policy');
    } catch { alert('Error deleting policy'); }
  }, [loadData]);

  const openAssignModal = useCallback((resource: EC2Resource) => {
    setAssignTarget(resource);
    setSelectedPolicyId('');
    setShowAssignModal(true);
  }, []);

  const submitAssignment = useCallback(async () => {
    if (!selectedPolicyId) { alert('Please select a policy.'); return; }
    if (!assignTarget || !subscriptionId) return;
    try {
      const res = await fetch('/api/azure/cloudsitter/assign', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          policyId: selectedPolicyId,
          instanceIds: [assignTarget.id],
          accountId: subscriptionId,
          region: assignTarget.region,
        }),
      });
      if (res.ok) { setShowAssignModal(false); await loadData(); }
      else alert('Failed to assign policy.');
    } catch { alert('Error assigning policy.'); }
  }, [selectedPolicyId, assignTarget, subscriptionId, loadData]);

  useEffect(() => {
    if (subscriptionId) loadData();
    resetGrid();
  }, [subscriptionId, loadData, resetGrid]);

  return {
    currentView, isLoading, isSaving, searchQuery,
    resources: filteredResources, allResources: resources, policies, policyForm, policyGrid,
    editMode, isDragging, showAssignModal, showManagePoliciesModal,
    assignTarget, selectedPolicyId, formInstanceTarget,
    setCurrentView, setSearchQuery, setPolicyForm, setIsDragging,
    setShowAssignModal, setShowManagePoliciesModal, setSelectedPolicyId,
    loadData, openCreatePolicy, closeCreatePolicy, openCreatePolicyFromAssign,
    resetGrid, toggleCell, dragToggle, savePolicy, editPolicy, deletePolicy,
    openAssignModal, submitAssignment, applyRecommendationToGrid,
  };
};
