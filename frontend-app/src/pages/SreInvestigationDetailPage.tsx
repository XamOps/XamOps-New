// frontend-app/src/pages/SreInvestigationDetailPage.tsx
import React, { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  getInvestigation, pollStatus, submitVerdict,
  SreInvestigation, Verdict
} from '../hooks/useSreInvestigation';
import SreStatusBadge from '../components/SRE/SreStatusBadge';
import { timeAgo, duration, resourceTypeLabel } from '../components/SRE/sreUtils';

const SreInvestigationDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [inv, setInv] = useState<SreInvestigation | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [submittingVerdict, setSubmittingVerdict] = useState(false);
  const [showFullReport, setShowFullReport] = useState(false);

  const load = useCallback(async () => {
    try {
      const data = await getInvestigation(Number(id));
      setInv(data);
    } catch {
      setError('Investigation not found or access denied.');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  // Poll while running
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
    }, 4000);
    return () => clearInterval(interval);
  }, [inv, id, load]);

  const handleVerdict = async (verdict: Verdict) => {
    if (!inv) return;
    setSubmittingVerdict(true);
    try {
      const updated = await submitVerdict(inv.id, verdict);
      setInv(updated);
    } finally {
      setSubmittingVerdict(false);
    }
  };

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-gray-400">Loading investigation…</p>
        </div>
      </div>
    );
  }

  if (error || !inv) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center">
          <p className="text-red-500 font-medium">{error || 'Investigation not found.'}</p>
          <button onClick={() => navigate('/sre/investigations')} className="mt-3 text-sm text-indigo-600 hover:underline">
            ← Back to investigations
          </button>
        </div>
      </div>
    );
  }

  const isRunning = inv.status === 'PENDING' || inv.status === 'RUNNING';

  return (
    <div className="flex flex-col h-full overflow-y-auto bg-gray-50">
      <div className="max-w-4xl w-full mx-auto px-6 py-8 space-y-5">

        {/* Breadcrumb */}
        <button
          onClick={() => navigate('/sre/investigations')}
          className="flex items-center gap-1.5 text-sm text-gray-400 hover:text-gray-700 transition-colors"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          Investigations
        </button>

        {/* Hero card */}
        <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-6">
          <div className="flex items-start justify-between gap-4">
            <div className="flex items-start gap-4">
              <div className={`w-12 h-12 rounded-xl flex items-center justify-center text-lg font-bold shrink-0 ${
                inv.status === 'COMPLETED' ? 'bg-emerald-100 text-emerald-700' :
                inv.status === 'FAILED' ? 'bg-red-100 text-red-700' :
                'bg-amber-100 text-amber-700'
              }`}>
                {(inv.resourceName || inv.resourceId).charAt(0).toUpperCase()}
              </div>
              <div>
                <h1 className="text-xl font-bold text-gray-900">{inv.resourceName || inv.resourceId}</h1>
                <div className="flex items-center flex-wrap gap-2 mt-1.5">
                  <SreStatusBadge status={inv.status} />
                  <span className="text-xs text-gray-400">{resourceTypeLabel[inv.resourceType]}</span>
                  <span className="text-xs text-gray-300">·</span>
                  <span className={`text-xs px-1.5 py-0.5 rounded font-medium ${
                    inv.triggerType === 'AUTO' ? 'bg-purple-100 text-purple-700' : 'bg-gray-100 text-gray-500'
                  }`}>
                    {inv.triggerType === 'AUTO' ? 'Auto-detected' : 'Manual'}
                  </span>
                  <span className="text-xs text-gray-300">·</span>
                  <span className="text-xs text-gray-400">{timeAgo(inv.createdAt)}</span>
                  {inv.startedAt && inv.completedAt && (
                    <>
                      <span className="text-xs text-gray-300">·</span>
                      <span className="text-xs text-gray-400">Completed in {duration(inv.startedAt, inv.completedAt)}</span>
                    </>
                  )}
                </div>
              </div>
            </div>

            {/* Verdict badge if already submitted */}
            {inv.verdict && (
              <span className={`shrink-0 px-3 py-1.5 rounded-lg text-sm font-semibold ${
                inv.verdict === 'RESOLVED' ? 'bg-emerald-100 text-emerald-700' :
                inv.verdict === 'FALSE_POSITIVE' ? 'bg-gray-100 text-gray-600' :
                'bg-orange-100 text-orange-700'
              }`}>
                {inv.verdict === 'RESOLVED' ? '✓ Resolved' :
                 inv.verdict === 'FALSE_POSITIVE' ? 'False positive' : 'Wrong root cause'}
              </span>
            )}
          </div>
        </div>

        {/* Running state */}
        {isRunning && (
          <div className="bg-amber-50 border border-amber-200 rounded-2xl p-5">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-3 h-3 bg-amber-400 rounded-full animate-pulse shrink-0" />
              <p className="text-amber-700 font-semibold text-sm">AI investigation in progress</p>
            </div>
            <div className="space-y-2">
              {['Connecting to observability stack', 'Gathering logs and metrics', 'Analyzing root cause', 'Generating recommendations'].map((step, i) => (
                <div key={i} className="flex items-center gap-2">
                  <div className={`w-1.5 h-1.5 rounded-full ${i === 0 ? 'bg-amber-500 animate-pulse' : 'bg-amber-200'}`} />
                  <span className={`text-xs ${i === 0 ? 'text-amber-700' : 'text-amber-400'}`}>{step}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Failed state */}
        {inv.status === 'FAILED' && (
          <div className="bg-red-50 border border-red-200 rounded-2xl p-5">
            <p className="text-red-600 font-semibold text-sm mb-1">Investigation failed</p>
            <p className="text-red-500 text-sm">{inv.errorMessage || 'An unexpected error occurred during investigation.'}</p>
          </div>
        )}

        {/* Completed results */}
        {inv.status === 'COMPLETED' && (
          <div className="space-y-4">

            {/* Summary */}
            {inv.reportSummary && (
              <div className="bg-white border border-gray-200 rounded-2xl p-5 shadow-sm">
                <div className="flex items-center gap-2 mb-3">
                  <div className="w-5 h-5 rounded bg-blue-100 flex items-center justify-center">
                    <svg className="w-3 h-3 text-blue-600" fill="currentColor" viewBox="0 0 20 20">
                      <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
                    </svg>
                  </div>
                  <h3 className="text-sm font-semibold text-gray-800">Summary</h3>
                </div>
                <p className="text-sm text-gray-600 leading-relaxed">{inv.reportSummary}</p>
              </div>
            )}

            {/* Root cause + Recommendations side by side */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {inv.reportRootCause && (
                <div className="bg-white border border-red-100 rounded-2xl p-5 shadow-sm">
                  <div className="flex items-center gap-2 mb-3">
                    <div className="w-5 h-5 rounded bg-red-100 flex items-center justify-center">
                      <svg className="w-3 h-3 text-red-600" fill="currentColor" viewBox="0 0 20 20">
                        <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
                      </svg>
                    </div>
                    <h3 className="text-sm font-semibold text-gray-800">Root Cause</h3>
                  </div>
                  <p className="text-sm text-gray-600 leading-relaxed">{inv.reportRootCause}</p>
                </div>
              )}

              {inv.reportRecommendations && inv.reportRecommendations.length > 0 && (
                <div className="bg-white border border-emerald-100 rounded-2xl p-5 shadow-sm">
                  <div className="flex items-center gap-2 mb-3">
                    <div className="w-5 h-5 rounded bg-emerald-100 flex items-center justify-center">
                      <svg className="w-3 h-3 text-emerald-600" fill="currentColor" viewBox="0 0 20 20">
                        <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                      </svg>
                    </div>
                    <h3 className="text-sm font-semibold text-gray-800">Recommended Actions</h3>
                  </div>
                  <ol className="space-y-2">
                    {inv.reportRecommendations.map((rec, i) => (
                      <li key={i} className="flex gap-2.5 items-start">
                        <span className="w-5 h-5 rounded-full bg-emerald-100 text-emerald-700 text-xs font-bold flex items-center justify-center shrink-0 mt-0.5">{i + 1}</span>
                        <span className="text-sm text-gray-600 leading-snug">{rec}</span>
                      </li>
                    ))}
                  </ol>
                </div>
              )}
            </div>

            {/* Full report toggle */}
            {inv.reportFullMarkdown && (
              <div className="bg-white border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
                <button
                  onClick={() => setShowFullReport(v => !v)}
                  className="w-full flex items-center justify-between px-5 py-4 hover:bg-gray-50 transition-colors"
                >
                  <div className="flex items-center gap-2">
                    <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                    </svg>
                    <span className="text-sm font-medium text-gray-700">Full Investigation Report</span>
                  </div>
                  <svg className={`w-4 h-4 text-gray-400 transition-transform ${showFullReport ? 'rotate-180' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                  </svg>
                </button>
                {showFullReport && (
                  <div className="px-5 pb-5 border-t border-gray-100">
                    <pre className="text-xs text-gray-600 leading-relaxed whitespace-pre-wrap font-mono overflow-x-auto pt-4 max-h-96 overflow-y-auto">
                      {inv.reportFullMarkdown}
                    </pre>
                  </div>
                )}
              </div>
            )}

            {/* Verdict section */}
            {!inv.verdict && (
              <div className="bg-white border border-gray-200 rounded-2xl p-5 shadow-sm">
                <p className="text-sm font-semibold text-gray-700 mb-3">Was this investigation accurate?</p>
                <div className="flex gap-2.5">
                  <VerdictButton
                    label="✓ Resolved"
                    description="Root cause was correct"
                    color="emerald"
                    disabled={submittingVerdict}
                    onClick={() => handleVerdict('RESOLVED')}
                  />
                  <VerdictButton
                    label="False Positive"
                    description="Not a real incident"
                    color="gray"
                    disabled={submittingVerdict}
                    onClick={() => handleVerdict('FALSE_POSITIVE')}
                  />
                  <VerdictButton
                    label="Wrong Root Cause"
                    description="Something else caused it"
                    color="orange"
                    disabled={submittingVerdict}
                    onClick={() => handleVerdict('WRONG_ROOT_CAUSE')}
                  />
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

interface VerdictButtonProps {
  label: string;
  description: string;
  color: 'emerald' | 'gray' | 'orange';
  disabled: boolean;
  onClick: () => void;
}

const colorMap = {
  emerald: 'border-emerald-200 hover:border-emerald-400 hover:bg-emerald-50 text-emerald-700',
  gray:    'border-gray-200   hover:border-gray-400   hover:bg-gray-50   text-gray-600',
  orange:  'border-orange-200 hover:border-orange-400 hover:bg-orange-50 text-orange-700',
};

const VerdictButton: React.FC<VerdictButtonProps> = ({ label, description, color, disabled, onClick }) => (
  <button
    onClick={onClick}
    disabled={disabled}
    className={`flex-1 border-2 rounded-xl px-4 py-3 text-left transition-all disabled:opacity-50 disabled:cursor-not-allowed ${colorMap[color]}`}
  >
    <p className="text-sm font-semibold">{label}</p>
    <p className="text-xs text-gray-400 mt-0.5">{description}</p>
  </button>
);

export default SreInvestigationDetailPage;
