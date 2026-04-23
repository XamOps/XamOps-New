import React, { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { getInvestigation, pollStatus, SreInvestigation } from '../hooks/useSreInvestigation';

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs} hr${hrs > 1 ? 's' : ''} ago`;
  return new Date(iso).toLocaleString();
}

function duration(start?: string, end?: string): string {
  if (!start || !end) return '';
  const secs = Math.floor((new Date(end).getTime() - new Date(start).getTime()) / 1000);
  if (secs < 60) return `${secs}s`;
  return `${Math.floor(secs / 60)}m ${secs % 60}s`;
}

const SreInvestigationDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [inv, setInv] = useState<SreInvestigation | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const data = await getInvestigation(Number(id));
      setInv(data);
    } catch (e) {
      setError('Failed to load investigation.');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (!inv || inv.status === 'COMPLETED' || inv.status === 'FAILED') return;

    const interval = setInterval(async () => {
      try {
        const { status } = await pollStatus(Number(id));
        if (status === 'COMPLETED' || status === 'FAILED') {
          await load();
          clearInterval(interval);
        }
      } catch {
        clearInterval(interval);
      }
    }, 5000);

    return () => clearInterval(interval);
  }, [inv, id, load]);

  if (loading) {
    return (
      <div className="flex flex-col h-screen overflow-hidden bg-gray-50 items-center justify-center">
        <div className="text-gray-400">Loading investigation...</div>
      </div>
    );
  }

  if (error || !inv) {
    return (
      <div className="flex flex-col h-screen overflow-hidden bg-gray-50 items-center justify-center">
        <div className="text-red-500">{error || 'Investigation not found.'}</div>
      </div>
    );
  }

  const isRunning = inv.status === 'PENDING' || inv.status === 'RUNNING';

  return (
    <div className="flex flex-col h-screen overflow-hidden bg-gray-50">
      <div className="flex-1 p-8 space-y-5 overflow-y-auto max-w-4xl w-full mx-auto">

        {/* Back button */}
        <button
          onClick={() => navigate('/sre/investigations')}
          className="text-gray-500 hover:text-gray-800 text-sm flex items-center gap-1 transition-colors"
        >
          ← Back to Investigations
        </button>

        {/* Header card */}
        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-5">
          <div className="flex items-start gap-3">
            <span className={`text-xs font-bold px-2.5 py-1 rounded-full ${
              inv.status === 'COMPLETED' ? 'bg-green-100 text-green-700' :
              inv.status === 'FAILED' ? 'bg-red-100 text-red-600' :
              'bg-yellow-100 text-yellow-700'
            }`}>
              {inv.status}
            </span>
            <div>
              <h1 className="text-lg font-bold text-gray-800">{inv.resourceName || inv.resourceId}</h1>
              <div className="text-gray-400 text-sm mt-0.5">
                {inv.resourceType} · {inv.resourceId}
                {inv.completedAt && inv.startedAt ? ` · Completed in ${duration(inv.startedAt, inv.completedAt)}` : ''}
                {' · '}{timeAgo(inv.createdAt)}
              </div>
            </div>
          </div>
        </div>

        {/* Running state */}
        {isRunning && (
          <div className="bg-yellow-50 border border-yellow-200 rounded-2xl p-5 flex items-center gap-3">
            <div className="w-3 h-3 bg-yellow-400 rounded-full animate-pulse shrink-0" />
            <div>
              <div className="text-yellow-700 font-semibold">Investigation in progress</div>
              <div className="text-yellow-600/80 text-sm">AI agents are gathering evidence and analyzing root cause…</div>
            </div>
          </div>
        )}

        {/* Failed state */}
        {inv.status === 'FAILED' && (
          <div className="bg-red-50 border border-red-200 rounded-2xl p-5">
            <div className="text-red-600 font-semibold">Investigation Failed</div>
            <div className="text-red-500/80 text-sm mt-1">{inv.errorMessage || 'Unknown error occurred.'}</div>
          </div>
        )}

        {/* Completed report */}
        {inv.status === 'COMPLETED' && (
          <div className="flex flex-col gap-5">
            {inv.reportSummary && (
              <div className="bg-blue-50 border border-blue-200 rounded-2xl p-5">
                <div className="text-blue-600 text-xs font-bold uppercase tracking-wider mb-3">📋 Summary</div>
                <p className="text-gray-700 text-sm leading-relaxed">{inv.reportSummary}</p>
              </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
              {inv.reportRootCause && (
                <div className="bg-red-50 border border-red-200 rounded-2xl p-5">
                  <div className="text-red-600 text-xs font-bold uppercase tracking-wider mb-3">🔍 Root Cause</div>
                  <p className="text-gray-700 text-sm leading-relaxed">{inv.reportRootCause}</p>
                </div>
              )}

              {inv.reportRecommendations && inv.reportRecommendations.length > 0 && (
                <div className="bg-green-50 border border-green-200 rounded-2xl p-5">
                  <div className="text-green-600 text-xs font-bold uppercase tracking-wider mb-3">✅ Recommendations</div>
                  <div className="flex flex-col gap-2">
                    {inv.reportRecommendations.map((rec, i) => (
                      <div key={i} className="flex gap-2 items-start">
                        <span className="bg-green-100 text-green-700 text-xs font-bold px-2 py-0.5 rounded shrink-0">{i + 1}</span>
                        <span className="text-gray-700 text-sm">{rec}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>

            {inv.reportFullMarkdown && (
              <div className="bg-white border border-gray-200 rounded-2xl p-5 shadow-sm">
                <div className="text-gray-400 text-xs font-bold uppercase tracking-wider mb-3">📄 Full Report</div>
                <pre className="text-gray-600 text-sm leading-relaxed whitespace-pre-wrap font-mono overflow-x-auto">
                  {inv.reportFullMarkdown}
                </pre>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default SreInvestigationDetailPage;
