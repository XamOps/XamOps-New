// frontend-app/src/pages/SreInvestigationsPage.tsx
import React, { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  listInvestigations, triggerInvestigation,
  SreInvestigation, InvestigationStatus, ResourceType
} from '../hooks/useSreInvestigation';
import { useTabState } from '../hooks/useTabState';
import SreStatusBadge from '../components/SRE/SreStatusBadge';
import { timeAgo, resourceTypeLabel, duration } from '../components/SRE/sreUtils';

type FilterTab = 'ALL' | 'ACTIVE' | 'COMPLETED' | 'FAILED';

const SreInvestigationsPage: React.FC = () => {
  const navigate = useNavigate();
  const [investigations, setInvestigations] = useState<SreInvestigation[]>([]);
  const [activeTab, setActiveTab] = useTabState<FilterTab>('ALL');
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [triggering, setTriggering] = useState(false);
  const [showTriggerModal, setShowTriggerModal] = useState(false);

  const fetchData = useCallback(async () => {
    try {
      const data = await listInvestigations();
      setInvestigations(data);
    } catch {
      // silently fail on background polls
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 10000);
    return () => clearInterval(interval);
  }, [fetchData]);

  const filtered = investigations.filter(inv => {
    const matchesTab =
      activeTab === 'ALL' ? true :
      activeTab === 'ACTIVE' ? (inv.status === 'PENDING' || inv.status === 'RUNNING') :
      activeTab === 'COMPLETED' ? inv.status === 'COMPLETED' :
      inv.status === 'FAILED';
    const q = search.toLowerCase();
    const matchesSearch = !q || (inv.resourceName || inv.resourceId).toLowerCase().includes(q)
      || (inv.reportRootCause || '').toLowerCase().includes(q);
    return matchesTab && matchesSearch;
  });

  const counts = {
    ALL: investigations.length,
    ACTIVE: investigations.filter(i => i.status === 'PENDING' || i.status === 'RUNNING').length,
    COMPLETED: investigations.filter(i => i.status === 'COMPLETED').length,
    FAILED: investigations.filter(i => i.status === 'FAILED').length,
  };

  return (
    <div className="flex flex-col h-full overflow-y-auto bg-gray-50">
      <div className="max-w-6xl w-full mx-auto px-6 py-8 space-y-5">

        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Investigations</h1>
            <p className="text-sm text-gray-500 mt-0.5">{investigations.length} total · auto-refreshing</p>
          </div>
          <button
            onClick={() => setShowTriggerModal(true)}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
            </svg>
            New Investigation
          </button>
        </div>

        {/* Filters row */}
        <div className="flex items-center justify-between gap-4">
          <div className="flex gap-1.5">
            {(['ALL', 'ACTIVE', 'COMPLETED', 'FAILED'] as FilterTab[]).map(tab => (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className={`px-3.5 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                  activeTab === tab
                    ? 'bg-indigo-600 text-white shadow-sm'
                    : 'text-gray-500 hover:text-gray-800 bg-white border border-gray-200 hover:border-gray-300'
                }`}
              >
                {tab === 'ALL' ? `All (${counts.ALL})` :
                 tab === 'ACTIVE' ? `Active (${counts.ACTIVE})` :
                 tab === 'COMPLETED' ? `Completed (${counts.COMPLETED})` :
                 `Failed (${counts.FAILED})`}
              </button>
            ))}
          </div>
          <div className="relative">
            <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              placeholder="Search by service or root cause…"
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-9 pr-4 py-1.5 text-sm border border-gray-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 w-72 placeholder-gray-400"
            />
          </div>
        </div>

        {/* Table */}
        <div className="bg-white rounded-2xl overflow-hidden border border-gray-200 shadow-sm">
          <div className="grid grid-cols-12 px-5 py-3 text-xs text-gray-400 uppercase tracking-wider border-b border-gray-100 font-medium">
            <span className="col-span-3">Service</span>
            <span className="col-span-2">Type</span>
            <span className="col-span-2">Status</span>
            <span className="col-span-3">Root Cause</span>
            <span className="col-span-1">Duration</span>
            <span className="col-span-1">Age</span>
          </div>

          {loading ? (
            <SkeletonRows count={5} />
          ) : filtered.length === 0 ? (
            <EmptyState search={search} onClear={() => setSearch('')} />
          ) : (
            <ul className="divide-y divide-gray-100">
              {filtered.map(inv => (
                <li
                  key={inv.id}
                  onClick={() => navigate(`/sre/investigations/${inv.id}`)}
                  className="grid grid-cols-12 px-5 py-4 items-center cursor-pointer hover:bg-gray-50 transition-colors group"
                >
                  {/* Service */}
                  <div className="col-span-3 flex items-center gap-2.5">
                    <div className={`w-8 h-8 rounded-lg flex items-center justify-center text-xs font-bold shrink-0 ${
                      inv.status === 'COMPLETED' ? 'bg-emerald-100 text-emerald-700' :
                      inv.status === 'FAILED' ? 'bg-red-100 text-red-700' :
                      'bg-amber-100 text-amber-700'
                    }`}>
                      {(inv.resourceName || inv.resourceId).charAt(0).toUpperCase()}
                    </div>
                    <div className="min-w-0">
                      <p className="text-sm font-semibold text-gray-900 truncate">{inv.resourceName || inv.resourceId}</p>
                      <div className="flex items-center gap-1 mt-0.5">
                        <span className={`text-xs px-1.5 py-0.5 rounded font-medium ${
                          inv.triggerType === 'AUTO'
                            ? 'bg-purple-100 text-purple-700'
                            : 'bg-gray-100 text-gray-500'
                        }`}>
                          {inv.triggerType === 'AUTO' ? 'Auto' : 'Manual'}
                        </span>
                      </div>
                    </div>
                  </div>

                  {/* Type */}
                  <span className="col-span-2 text-sm text-gray-500">{resourceTypeLabel[inv.resourceType]}</span>

                  {/* Status */}
                  <div className="col-span-2">
                    <SreStatusBadge status={inv.status} />
                  </div>

                  {/* Root cause preview */}
                  <div className="col-span-3 pr-4">
                    {inv.reportRootCause ? (
                      <p className="text-sm text-gray-600 truncate">{inv.reportRootCause}</p>
                    ) : inv.status === 'COMPLETED' ? (
                      <span className="text-sm text-gray-300">No root cause recorded</span>
                    ) : (
                      <span className="text-sm text-gray-300 italic">Investigating…</span>
                    )}
                  </div>

                  {/* Duration */}
                  <span className="col-span-1 text-sm text-gray-400">
                    {duration(inv.startedAt, inv.completedAt) || '—'}
                  </span>

                  {/* Age */}
                  <div className="col-span-1 flex items-center justify-between">
                    <span className="text-sm text-gray-400">{timeAgo(inv.createdAt)}</span>
                    <svg className="w-4 h-4 text-gray-300 group-hover:text-gray-500 transition-colors" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                    </svg>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {/* Manual trigger modal */}
      {showTriggerModal && (
        <TriggerModal
          onClose={() => setShowTriggerModal(false)}
          onTrigger={async (req) => {
            setTriggering(true);
            try {
              const inv = await triggerInvestigation(req);
              setShowTriggerModal(false);
              navigate(`/sre/investigations/${inv.id}`);
            } finally {
              setTriggering(false);
            }
          }}
          triggering={triggering}
        />
      )}
    </div>
  );
};

const SkeletonRows: React.FC<{ count: number }> = ({ count }) => (
  <>
    {Array.from({ length: count }).map((_, i) => (
      <div key={i} className="grid grid-cols-12 px-5 py-4 items-center border-b border-gray-100">
        <div className="col-span-3 flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-gray-100 animate-pulse" />
          <div className="space-y-1.5">
            <div className="h-3 w-28 bg-gray-100 rounded animate-pulse" />
            <div className="h-2.5 w-14 bg-gray-100 rounded animate-pulse" />
          </div>
        </div>
        <div className="col-span-2"><div className="h-3 w-16 bg-gray-100 rounded animate-pulse" /></div>
        <div className="col-span-2"><div className="h-5 w-20 bg-gray-100 rounded-full animate-pulse" /></div>
        <div className="col-span-3"><div className="h-3 w-40 bg-gray-100 rounded animate-pulse" /></div>
        <div className="col-span-1"><div className="h-3 w-10 bg-gray-100 rounded animate-pulse" /></div>
        <div className="col-span-1"><div className="h-3 w-10 bg-gray-100 rounded animate-pulse" /></div>
      </div>
    ))}
  </>
);

const EmptyState: React.FC<{ search: string; onClear: () => void }> = ({ search, onClear }) => (
  <div className="py-16 text-center">
    <p className="text-gray-400 text-sm">
      {search ? (
        <>No results for "<span className="font-medium text-gray-600">{search}</span>". <button onClick={onClear} className="text-indigo-600 hover:underline">Clear search</button></>
      ) : 'No investigations found.'}
    </p>
  </div>
);

interface TriggerModalProps {
  onClose: () => void;
  onTrigger: (req: { resourceType: ResourceType; resourceId: string; resourceName?: string }) => Promise<void>;
  triggering: boolean;
}

const TriggerModal: React.FC<TriggerModalProps> = ({ onClose, onTrigger, triggering }) => {
  const [resourceType, setResourceType] = useState<ResourceType>('K8S_POD');
  const [resourceId, setResourceId] = useState('');
  const [resourceName, setResourceName] = useState('');

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md p-6 space-y-5" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-gray-900">New Investigation</h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-700">
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="space-y-4">
          <div>
            <label className="text-xs font-medium text-gray-600 uppercase tracking-wide block mb-1.5">Resource Type</label>
            <select
              value={resourceType}
              onChange={e => setResourceType(e.target.value as ResourceType)}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="K8S_POD">K8s Pod</option>
              <option value="EC2">EC2 Instance</option>
              <option value="RDS">RDS</option>
              <option value="AZURE_VM">Azure VM</option>
              <option value="GCP_VM">GCP VM</option>
              <option value="GENERIC">Generic Service</option>
            </select>
          </div>

          <div>
            <label className="text-xs font-medium text-gray-600 uppercase tracking-wide block mb-1.5">Resource ID</label>
            <input
              type="text"
              placeholder="e.g. user-service, i-0abc123"
              value={resourceId}
              onChange={e => setResourceId(e.target.value)}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 placeholder-gray-300"
            />
          </div>

          <div>
            <label className="text-xs font-medium text-gray-600 uppercase tracking-wide block mb-1.5">Display Name <span className="text-gray-400 normal-case">(optional)</span></label>
            <input
              type="text"
              placeholder="e.g. User Service"
              value={resourceName}
              onChange={e => setResourceName(e.target.value)}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 placeholder-gray-300"
            />
          </div>
        </div>

        <div className="flex gap-3 pt-1">
          <button
            onClick={onClose}
            className="flex-1 px-4 py-2.5 border border-gray-200 text-gray-600 text-sm font-medium rounded-lg hover:bg-gray-50 transition-colors"
          >
            Cancel
          </button>
          <button
            disabled={!resourceId.trim() || triggering}
            onClick={() => onTrigger({ resourceType, resourceId: resourceId.trim(), resourceName: resourceName.trim() || undefined })}
            className="flex-1 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {triggering ? 'Starting…' : 'Start Investigation'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default SreInvestigationsPage;
