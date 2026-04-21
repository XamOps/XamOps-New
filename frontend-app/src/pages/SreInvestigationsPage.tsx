import React, { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { listInvestigations, SreInvestigation, InvestigationStatus } from '../hooks/useSreInvestigation';

type FilterTab = 'ALL' | 'RUNNING' | 'COMPLETED' | 'FAILED';

const statusColor: Record<InvestigationStatus, string> = {
  PENDING: 'text-yellow-400',
  RUNNING: 'text-yellow-400',
  COMPLETED: 'text-green-400',
  FAILED: 'text-red-400',
};

const statusDot: Record<InvestigationStatus, string> = {
  PENDING: 'bg-yellow-400',
  RUNNING: 'bg-yellow-400',
  COMPLETED: 'bg-green-400',
  FAILED: 'bg-red-400',
};

const resourceTypeLabel: Record<string, string> = {
  K8S_POD: 'K8s Pod',
  EC2: 'EC2 Instance',
  RDS: 'RDS',
  AZURE_VM: 'Azure VM',
  GCP_VM: 'GCP VM',
};

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs} hr${hrs > 1 ? 's' : ''} ago`;
  return new Date(iso).toLocaleDateString();
}

const SreInvestigationsPage: React.FC = () => {
  const navigate = useNavigate();
  const [investigations, setInvestigations] = useState<SreInvestigation[]>([]);
  const [activeTab, setActiveTab] = useState<FilterTab>('ALL');
  const [loading, setLoading] = useState(true);

  const fetchData = useCallback(async () => {
    try {
      const data = await listInvestigations();
      setInvestigations(data);
    } catch (e) {
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
    if (activeTab === 'ALL') return true;
    if (activeTab === 'RUNNING') return inv.status === 'PENDING' || inv.status === 'RUNNING';
    return inv.status === activeTab;
  });

  const counts = {
    ALL: investigations.length,
    RUNNING: investigations.filter(i => i.status === 'PENDING' || i.status === 'RUNNING').length,
    COMPLETED: investigations.filter(i => i.status === 'COMPLETED').length,
    FAILED: investigations.filter(i => i.status === 'FAILED').length,
  };

  return (
    <div className="min-h-screen bg-gray-900 text-white p-6">
      <div className="max-w-6xl mx-auto">
        {/* Header */}
        <div className="flex justify-between items-center mb-6">
          <div>
            <h1 className="text-2xl font-bold text-white">SRE Investigations</h1>
            <p className="text-gray-400 text-sm mt-1">AI-powered root cause analysis for your infrastructure</p>
          </div>
        </div>

        {/* Filter tabs */}
        <div className="flex gap-2 mb-4">
          {(['ALL', 'RUNNING', 'COMPLETED', 'FAILED'] as FilterTab[]).map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={`px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
                activeTab === tab
                  ? 'bg-blue-700 text-blue-100'
                  : 'text-gray-400 hover:text-white'
              }`}
            >
              {tab === 'ALL' ? `All (${counts.ALL})` :
               tab === 'RUNNING' ? `Running (${counts.RUNNING})` :
               tab === 'COMPLETED' ? `Completed (${counts.COMPLETED})` :
               `Failed (${counts.FAILED})`}
            </button>
          ))}
        </div>

        {/* Table */}
        <div className="bg-gray-800 rounded-xl overflow-hidden border border-gray-700">
          {/* Table header */}
          <div className="grid grid-cols-5 px-6 py-3 text-xs text-gray-500 uppercase tracking-wider border-b border-gray-700">
            <span>Resource</span>
            <span>Type</span>
            <span>Trigger</span>
            <span>Status</span>
            <span>Time</span>
          </div>

          {loading ? (
            <div className="px-6 py-8 text-center text-gray-400">Loading investigations...</div>
          ) : filtered.length === 0 ? (
            <div className="px-6 py-8 text-center text-gray-400">No investigations found.</div>
          ) : (
            filtered.map(inv => (
              <div
                key={inv.id}
                onClick={() => inv.status === 'COMPLETED' && navigate(`/sre/investigations/${inv.id}`)}
                className={`grid grid-cols-5 px-6 py-4 border-b border-gray-700 items-center ${
                  inv.status === 'COMPLETED' ? 'cursor-pointer hover:bg-gray-700' : ''
                } ${inv.status === 'RUNNING' || inv.status === 'PENDING' ? 'bg-gray-800/50' : ''}`}
              >
                <div>
                  <div className="text-sm font-semibold text-white">{inv.resourceName || inv.resourceId}</div>
                  <div className="text-xs text-gray-500">{inv.resourceId}</div>
                </div>
                <span className="text-sm text-blue-400">{resourceTypeLabel[inv.resourceType] || inv.resourceType}</span>
                <span className="text-sm text-gray-400">
                  {inv.triggerType === 'AUTO' ? '🔔 Auto' : '👆 Manual'}
                </span>
                <span className={`flex items-center gap-1.5 text-sm ${statusColor[inv.status]}`}>
                  <span className={`w-2 h-2 rounded-full inline-block ${statusDot[inv.status]} ${
                    (inv.status === 'RUNNING' || inv.status === 'PENDING') ? 'animate-pulse' : ''
                  }`} />
                  {inv.status === 'PENDING' ? 'Running' : inv.status.charAt(0) + inv.status.slice(1).toLowerCase()}
                </span>
                <span className="text-sm text-gray-400">{timeAgo(inv.createdAt)}</span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};

export default SreInvestigationsPage;
