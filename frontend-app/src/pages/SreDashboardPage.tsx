// frontend-app/src/pages/SreDashboardPage.tsx
import React, { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  listInvestigations, getSreStats, listDetectionEvents,
  SreInvestigation, SreStats, DetectionEvent
} from '../hooks/useSreInvestigation';
import SreStatusBadge from '../components/SRE/SreStatusBadge';
import { timeAgo, formatMttr, resourceTypeLabel, duration } from '../components/SRE/sreUtils';

const SreDashboardPage: React.FC = () => {
  const navigate = useNavigate();
  const [investigations, setInvestigations] = useState<SreInvestigation[]>([]);
  const [stats, setStats] = useState<SreStats | null>(null);
  const [detectionEvents, setDetectionEvents] = useState<DetectionEvent[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchAll = useCallback(async () => {
    try {
      const [invs, s, events] = await Promise.all([listInvestigations(), getSreStats(), listDetectionEvents()]);
      setInvestigations(invs);
      setStats(s);
      setDetectionEvents(events);
    } catch {
      // silently fail on background polls
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAll();
    const interval = setInterval(fetchAll, 15000);
    return () => clearInterval(interval);
  }, [fetchAll]);

  const active = investigations.filter(i => i.status === 'PENDING' || i.status === 'RUNNING');
  const recent = investigations.slice(0, 10);

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-gray-400">Loading SRE dashboard…</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full overflow-y-auto bg-gray-50">
      <div className="max-w-6xl w-full mx-auto px-6 py-8 space-y-6">

        {/* Stats row */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard
            label="Active Incidents"
            value={stats?.activeInvestigations ?? 0}
            highlight={stats ? stats.activeInvestigations > 0 : false}
            highlightColor="text-red-600"
          />
          <StatCard
            label="Resolved Today"
            value={stats?.completedToday ?? 0}
            highlight={false}
          />
          <StatCard
            label="Avg MTTR"
            value={formatMttr(stats?.avgMttrSeconds ?? null)}
            highlight={false}
            isText
          />
          <StatCard
            label="Total Investigations"
            value={stats?.totalInvestigations ?? 0}
            highlight={false}
          />
        </div>

        {/* Auto-Detected Anomalies feed */}
        {detectionEvents.length > 0 && (
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
            <div className="flex items-center justify-between px-5 py-3.5 border-b border-gray-100">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-violet-500" />
                <h2 className="text-sm font-semibold text-gray-700">Auto-Detected Anomalies</h2>
              </div>
              <span className="text-xs text-gray-400">last 10</span>
            </div>
            <ul className="divide-y divide-gray-100">
              {detectionEvents.map(evt => (
                <li
                  key={evt.id}
                  onClick={() => evt.investigationId && navigate(`/sre/investigations/${evt.investigationId}`)}
                  className={`flex items-center justify-between px-5 py-3 ${evt.investigationId ? 'cursor-pointer hover:bg-gray-50' : ''} transition-colors`}
                >
                  <div className="flex items-center gap-3">
                    <div className="w-7 h-7 rounded-lg bg-violet-100 flex items-center justify-center">
                      <svg className="w-3.5 h-3.5 text-violet-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                      </svg>
                    </div>
                    <div>
                      <p className="text-sm font-medium text-gray-800">{evt.entity}</p>
                      <p className="text-xs text-gray-400">{evt.signal} · z-score {evt.score.toFixed(1)}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-3 shrink-0">
                    {evt.metricValue != null && evt.metricBaseline != null && (
                      <span className="text-xs text-red-600 font-medium">
                        {evt.metricValue.toFixed(1)} <span className="text-gray-400">vs</span> {evt.metricBaseline.toFixed(1)}
                      </span>
                    )}
                    <span className="text-xs text-gray-400">{timeAgo(evt.detectedAt)}</span>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* Active incidents — only shown when there are active ones */}
        {active.length > 0 && (
          <div className="bg-white border border-red-100 rounded-2xl shadow-sm overflow-hidden">
            <div className="flex items-center gap-2 px-5 py-3.5 border-b border-red-100 bg-red-50">
              <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
              <h2 className="text-sm font-semibold text-red-700">
                {active.length} Active Investigation{active.length > 1 ? 's' : ''}
              </h2>
            </div>
            <ul className="divide-y divide-gray-100">
              {active.map(inv => (
                <li
                  key={inv.id}
                  onClick={() => navigate(`/sre/investigations/${inv.id}`)}
                  className="flex items-center justify-between px-5 py-4 hover:bg-gray-50 cursor-pointer transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-8 h-8 rounded-lg bg-amber-100 flex items-center justify-center text-amber-600 text-xs font-bold">
                      {(inv.resourceName || inv.resourceId).charAt(0).toUpperCase()}
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-gray-900">{inv.resourceName || inv.resourceId}</p>
                      <p className="text-xs text-gray-400">{resourceTypeLabel[inv.resourceType]} · {timeAgo(inv.createdAt)}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <SreStatusBadge status={inv.status} />
                    <svg className="w-4 h-4 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                    </svg>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* No active incidents hero */}
        {active.length === 0 && investigations.length > 0 && (
          <div className="bg-white border border-emerald-100 rounded-2xl p-6 flex items-center gap-4">
            <div className="w-10 h-10 rounded-full bg-emerald-100 flex items-center justify-center">
              <svg className="w-5 h-5 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <div>
              <p className="text-sm font-semibold text-emerald-700">All systems operational</p>
              <p className="text-xs text-gray-400">No active investigations right now</p>
            </div>
          </div>
        )}

        {/* Empty state */}
        {investigations.length === 0 && (
          <div className="bg-white border border-gray-200 rounded-2xl p-12 text-center">
            <div className="w-14 h-14 rounded-2xl bg-indigo-50 flex items-center justify-center mx-auto mb-4">
              <svg className="w-7 h-7 text-indigo-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                  d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
              </svg>
            </div>
            <h3 className="text-base font-semibold text-gray-800 mb-1">No investigations yet</h3>
            <p className="text-sm text-gray-500 mb-5">Connect your observability stack to start automatic detection</p>
            <button
              onClick={() => navigate('/sre/setup')}
              className="px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors"
            >
              Set up SRE
            </button>
          </div>
        )}

        {/* Recent activity */}
        {recent.length > 0 && (
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
            <div className="flex items-center justify-between px-5 py-3.5 border-b border-gray-100">
              <h2 className="text-sm font-semibold text-gray-700">Recent Activity</h2>
              <button
                onClick={() => navigate('/sre/investigations')}
                className="text-xs text-indigo-600 hover:text-indigo-800 transition-colors"
              >
                View all →
              </button>
            </div>
            <ul className="divide-y divide-gray-100">
              {recent.map(inv => (
                <li
                  key={inv.id}
                  onClick={() => navigate(`/sre/investigations/${inv.id}`)}
                  className="flex items-center justify-between px-5 py-3.5 hover:bg-gray-50 cursor-pointer transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <SreStatusBadge status={inv.status} />
                    <div>
                      <p className="text-sm font-medium text-gray-800">{inv.resourceName || inv.resourceId}</p>
                      {inv.reportRootCause && (
                        <p className="text-xs text-gray-400 truncate max-w-xs">{inv.reportRootCause}</p>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center gap-3 shrink-0">
                    {inv.status === 'COMPLETED' && inv.startedAt && inv.completedAt && (
                      <span className="text-xs text-gray-400">{duration(inv.startedAt, inv.completedAt)}</span>
                    )}
                    <span className="text-xs text-gray-400">{timeAgo(inv.createdAt)}</span>
                    {inv.verdict && (
                      <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                        inv.verdict === 'RESOLVED' ? 'bg-emerald-100 text-emerald-700' :
                        inv.verdict === 'FALSE_POSITIVE' ? 'bg-gray-100 text-gray-500' :
                        'bg-orange-100 text-orange-700'
                      }`}>
                        {inv.verdict === 'RESOLVED' ? 'Resolved' :
                         inv.verdict === 'FALSE_POSITIVE' ? 'False positive' : 'Wrong RCA'}
                      </span>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
};

interface StatCardProps {
  label: string;
  value: number | string;
  highlight: boolean;
  highlightColor?: string;
  isText?: boolean;
}

const StatCard: React.FC<StatCardProps> = ({ label, value, highlight, highlightColor = 'text-red-600', isText }) => (
  <div className="bg-white border border-gray-200 rounded-2xl p-5 shadow-sm">
    <p className="text-xs text-gray-500 font-medium uppercase tracking-wide">{label}</p>
    <p className={`text-3xl font-bold mt-1 ${highlight ? highlightColor : 'text-gray-900'} ${isText ? 'text-2xl' : ''}`}>
      {value}
    </p>
  </div>
);

export default SreDashboardPage;
