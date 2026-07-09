import React, { useCallback, useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useCICDPipelines } from '../hooks/useCICDPipelines';
import { RunHistory } from '../types/cicdPipelines';
import { useTopbar } from '../contexts/TopbarContext';
import { DialogRoot, DialogPanel } from '../components/ui';
import { useCicdMetrics } from '../hooks/useCicdMetrics';
import { CicdTimeRangeSelector } from '../components/CICDPipelines/CicdTimeRangeSelector';
import { CicdKpiCards } from '../components/CICDPipelines/CicdKpiCards';
import { CicdTrendCharts } from '../components/CICDPipelines/CicdTrendCharts';
import { CicdDoraRow } from '../components/CICDPipelines/CicdDoraRow';
import { CicdFailureAnalysis } from '../components/CICDPipelines/CicdFailureAnalysis';
import { CicdLogViewer } from '../components/CICDPipelines/CicdLogViewer';
import CicdHealthScorecard from '../components/CICDPipelines/CicdHealthScorecard';
import CicdFailureDetail from '../components/CICDPipelines/CicdFailureDetail';
import CicdDiffSummary from '../components/CICDPipelines/CicdDiffSummary';
import CicdNotificationSettings from '../components/CICDPipelines/CicdNotificationSettings';
import { useCicdFailureAnalysis } from '../hooks/useCicdFailureAnalysis';
import { useCicdNotificationConfig } from '../hooks/useCicdNotificationConfig';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function statusStyle(status: string): { dot: string; text: string; bg: string; border: string } {
  const s = (status || '').toLowerCase();
  if (s === 'success' || s === 'completed')
    return { dot: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50', border: 'border-emerald-200' };
  if (s === 'failure' || s === 'failed')
    return { dot: 'bg-red-500', text: 'text-red-700', bg: 'bg-red-50', border: 'border-red-200' };
  if (s === 'in_progress' || s === 'running' || s === 'building')
    return { dot: 'bg-blue-500', text: 'text-blue-700', bg: 'bg-blue-50', border: 'border-blue-200' };
  if (s === 'unstable')
    return { dot: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50', border: 'border-amber-200' };
  return { dot: 'bg-[rgba(0,0,0,0.2)]', text: 'text-[rgba(0,0,0,0.45)]', bg: 'bg-[rgba(0,0,0,0.03)]', border: 'border-[rgba(0,0,0,0.08)]' };
}

function stageStyle(status: string): { icon: string; iconColor: string; ring: string; bg: string } {
  switch (status) {
    case 'success':  return { icon: 'mdi:check',        iconColor: '#059669', ring: 'border-emerald-400', bg: 'bg-emerald-50' };
    case 'failure':  return { icon: 'mdi:close',        iconColor: '#DC2626', ring: 'border-red-400',     bg: 'bg-red-50' };
    case 'running':  return { icon: 'mdi:loading',      iconColor: '#2563EB', ring: 'border-blue-400',    bg: 'bg-blue-50' };
    case 'skipped':  return { icon: 'mdi:slash-forward',iconColor: 'rgba(0,0,0,0.3)', ring: 'border-[rgba(0,0,0,0.12)]', bg: 'bg-[rgba(0,0,0,0.03)]' };
    default:         return { icon: 'mdi:clock-outline', iconColor: 'rgba(0,0,0,0.3)', ring: 'border-[rgba(0,0,0,0.12)]', bg: 'bg-[rgba(0,0,0,0.03)]' };
  }
}

// ─── Page ─────────────────────────────────────────────────────────────────────

const CICDPipelinesPage: React.FC = () => {
  const {
    githubRuns, isLoadingGithub, githubAuthError,
    jenkinsJobs, isLoadingJenkins,
    activeTab, setActiveTab,
    selectedWorkflow, workflowHistory, isLoadingHistory, viewWorkflowHistory, closeWorkflow,
    selectedRun, selectedRunNumber, selectedRunUrl, pipelineStages, isStagesLoading, selectRunFromHistory,
    refreshData,

    autoOpenLog,
  } = useCICDPipelines();

  const githubMetrics = useCicdMetrics('github');
  const jenkinsMetrics = useCicdMetrics('jenkins');

  const failureAnalysisHook = useCicdFailureAnalysis();
  const notificationConfig = useCicdNotificationConfig();
  const [dialogTab, setDialogTab] = useState<'overview' | 'analysis' | 'diff'>('overview');

  const formatTime = useCallback((timeValue: string | number | undefined): string => {
    if (!timeValue) return 'N/A';
    try {
      return new Date(timeValue).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    } catch { return String(timeValue); }
  }, []);

  const getRunId = useCallback((run: RunHistory): string | number =>
    'id' in run ? run.id : (run as any).number, []);

  const getRunNumber = useCallback((run: RunHistory): string | number =>
    run.run_number ?? (run as any).number, []);

  const isRunSelected = useCallback(
    (run: RunHistory) => selectedRun ? getRunId(selectedRun) === getRunId(run) : false,
    [selectedRun, getRunId]
  );

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape' && selectedWorkflow) closeWorkflow(); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [selectedWorkflow, closeWorkflow]);

  const { setActions, clearActions } = useTopbar();
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-2 h-2 rounded-full bg-amber-400" />
        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.65)]">CI/CD Pipelines</span>
        <span className="text-[rgba(0,0,0,0.2)]">|</span>
        <button
          onClick={refreshData}
          disabled={isLoadingGithub || isLoadingJenkins}
          className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.04)] rounded-lg transition-colors disabled:opacity-50"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className={(isLoadingGithub || isLoadingJenkins) ? 'animate-spin' : ''}>
            <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
          </svg>
          Refresh
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isLoadingGithub, isLoadingJenkins, refreshData]);

  const TABS = [
    { id: 'github',  label: 'GitHub Actions', icon: 'mdi:github' },
    { id: 'jenkins', label: 'Jenkins',         icon: 'simple-icons:jenkins' },
    { id: 'health',  label: 'Pipeline Health', icon: 'mdi:chart-bar' },
  ] as const;

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">

      {/* Tab Bar */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
        <div className="flex border-b border-[rgba(0,0,0,0.08)]">
          {TABS.map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 px-5 py-3.5 text-[13px] font-semibold border-b-2 transition-colors ${
                activeTab === tab.id
                  ? 'border-indigo-600 text-indigo-600 bg-indigo-50/50'
                  : 'border-transparent text-[rgba(0,0,0,0.55)] hover:text-[rgba(0,0,0,0.75)] hover:bg-[rgba(0,0,0,0.02)]'
              }`}
            >
              <Icon icon={tab.icon} width={15} />
              {tab.label}
            </button>
          ))}
        </div>

        <AnimatePresence mode="wait">
        {/* GitHub Tab */}
        {activeTab === 'github' && (
          <motion.div key="github" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
            {githubAuthError && (
              <div className="mb-5 flex items-start gap-3 px-4 py-3 bg-amber-50 border border-amber-200 rounded-xl">
                <Icon icon="mdi:key-alert" width={20} color="#B45309" className="mt-0.5 flex-shrink-0" />
                <div className="text-[13px] leading-relaxed">
                  <p className="font-bold text-amber-800">GitHub token invalid or expired</p>
                  <p className="text-amber-700 mt-0.5">
                    GitHub rejected the stored access token, so pipeline data can't be loaded. Generate a new
                    Personal Access Token (with <span className="font-mono">repo</span> and{' '}
                    <span className="font-mono">actions:read</span> scopes) and reconnect the repository in{' '}
                    <span className="font-semibold">Account Management → Connected GitHub Repositories</span>.
                  </p>
                </div>
              </div>
            )}
            {/* GitHub Metrics */}
            <div className="mb-6">
              {githubMetrics.error && (
                <div className="mb-3 px-4 py-2 bg-red-50 border border-red-200 rounded-lg text-sm text-red-600">
                  Metrics unavailable: {githubMetrics.error}
                </div>
              )}
              <div className="flex justify-end mb-3">
                <CicdTimeRangeSelector days={githubMetrics.days} onChange={githubMetrics.setDays} />
              </div>
              <CicdKpiCards metrics={githubMetrics.metrics ?? { successRate: 0, totalRuns: 0, failureCount: 0, avgDurationSeconds: 0, prevSuccessRate: 0, prevTotalRuns: 0, prevFailureCount: 0, prevAvgDurationSeconds: 0, dora: null, dailyStats: [], topFailingStages: [], topFailingWorkflows: [] }} isLoading={githubMetrics.isLoading} />
              <CicdTrendCharts dailyStats={githubMetrics.metrics?.dailyStats ?? []} isLoading={githubMetrics.isLoading} />
              <CicdDoraRow dora={githubMetrics.metrics?.dora ?? null} isLoading={githubMetrics.isLoading} />
              <CicdFailureAnalysis topFailingStages={githubMetrics.metrics?.topFailingStages ?? []} topFailingWorkflows={githubMetrics.metrics?.topFailingWorkflows ?? []} isLoading={githubMetrics.isLoading} />
            </div>
            {isLoadingGithub && (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {[...Array(6)].map((_, i) => (
                  <div key={i} className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl h-28 animate-pulse" />
                ))}
              </div>
            )}
            {!isLoadingGithub && githubRuns.length === 0 && (
              <div className="text-center py-14">
                <div className="w-12 h-12 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.06)] rounded-xl flex items-center justify-center mx-auto mb-3">
                  <Icon icon="mdi:github" width={24} color="rgba(0,0,0,0.2)" />
                </div>
                <p className="text-[14px] font-bold text-[rgba(0,0,0,0.55)]">No GitHub Actions runs found</p>
                <p className="text-[12px] text-[rgba(0,0,0,0.35)] mt-1">Connect a repository in Account Management to get started.</p>
              </div>
            )}
            {!isLoadingGithub && githubRuns.length > 0 && (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {githubRuns.map((run, idx) => {
                  const st = statusStyle(run.displayStatus);
                  return (
                    <div key={run.id} onClick={() => viewWorkflowHistory(run)}
                      style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.04, 0.4)}s both` }}
                      className={`bg-white border ${st.border} rounded-xl p-4 cursor-pointer hover:shadow-md transition-all`}>
                      <div className="flex justify-between items-center mb-2.5">
                        <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.07)] px-2 py-0.5 rounded-full truncate max-w-[130px]">
                          {run.repository?.full_name || 'Unknown Repo'}
                        </span>
                        <a href={run.html_url} target="_blank" rel="noopener noreferrer"
                          onClick={e => e.stopPropagation()}
                          className="flex items-center gap-1 text-[11px] font-semibold text-indigo-600 hover:text-indigo-800 transition-colors">
                          View <Icon icon="mdi:open-in-new" width={11} />
                        </a>
                      </div>
                      <p className="font-bold text-[rgba(0,0,0,0.88)] text-[13px] mb-2 truncate">{run.name}</p>
                      <div className="flex items-center justify-between">
                        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 ${st.bg} border ${st.border} rounded-full text-[11px] font-semibold ${st.text}`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${st.dot}`} />
                          {run.displayStatus}
                        </span>
                        <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{formatTime(run.updated_at)}</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </motion.div>
        )}

        {/* Jenkins Tab */}
        {activeTab === 'jenkins' && (
          <motion.div key="jenkins" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
            {/* Jenkins Metrics */}
            <div className="mb-6">
              {jenkinsMetrics.error && (
                <div className="mb-3 px-4 py-2 bg-red-50 border border-red-200 rounded-lg text-sm text-red-600">
                  Metrics unavailable: {jenkinsMetrics.error}
                </div>
              )}
              <div className="flex justify-end mb-3">
                <CicdTimeRangeSelector days={jenkinsMetrics.days} onChange={jenkinsMetrics.setDays} />
              </div>
              <CicdKpiCards metrics={jenkinsMetrics.metrics ?? { successRate: 0, totalRuns: 0, failureCount: 0, avgDurationSeconds: 0, prevSuccessRate: 0, prevTotalRuns: 0, prevFailureCount: 0, prevAvgDurationSeconds: 0, dora: null, dailyStats: [], topFailingStages: [], topFailingWorkflows: [] }} isLoading={jenkinsMetrics.isLoading} />
              <CicdTrendCharts dailyStats={jenkinsMetrics.metrics?.dailyStats ?? []} isLoading={jenkinsMetrics.isLoading} />
              <CicdFailureAnalysis topFailingStages={jenkinsMetrics.metrics?.topFailingStages ?? []} topFailingWorkflows={jenkinsMetrics.metrics?.topFailingWorkflows ?? []} isLoading={jenkinsMetrics.isLoading} />
            </div>
            {isLoadingJenkins && (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {[...Array(6)].map((_, i) => (
                  <div key={i} className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl h-28 animate-pulse" />
                ))}
              </div>
            )}
            {!isLoadingJenkins && jenkinsJobs.length === 0 && (
              <div className="text-center py-14">
                <div className="w-12 h-12 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.06)] rounded-xl flex items-center justify-center mx-auto mb-3">
                  <Icon icon="simple-icons:jenkins" width={22} color="rgba(0,0,0,0.2)" />
                </div>
                <p className="text-[14px] font-bold text-[rgba(0,0,0,0.55)]">No Jenkins jobs found</p>
                <p className="text-[12px] text-[rgba(0,0,0,0.35)] mt-1">Configure your Jenkins connection in Account Management.</p>
              </div>
            )}
            {!isLoadingJenkins && jenkinsJobs.length > 0 && (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {jenkinsJobs.map((job, idx) => {
                  const st = statusStyle(job.displayStatus);
                  return (
                    <div key={job.url} onClick={() => viewWorkflowHistory(job)}
                      style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.04, 0.4)}s both` }}
                      className={`bg-white border ${st.border} rounded-xl p-4 cursor-pointer hover:shadow-md transition-all`}>
                      <div className="flex justify-between items-center mb-2.5">
                        <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.07)] px-2 py-0.5 rounded-full">
                          Jenkins
                        </span>
                        <a href={job.url} target="_blank" rel="noopener noreferrer"
                          onClick={e => e.stopPropagation()}
                          className="flex items-center gap-1 text-[11px] font-semibold text-indigo-600 hover:text-indigo-800 transition-colors">
                          Open <Icon icon="mdi:open-in-new" width={11} />
                        </a>
                      </div>
                      <p className="font-bold text-[rgba(0,0,0,0.88)] text-[13px] mb-2 truncate">{job.name}</p>
                      <div className="flex items-center justify-between">
                        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 ${st.bg} border ${st.border} rounded-full text-[11px] font-semibold ${st.text}`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${st.dot}`} />
                          {job.displayStatus}
                        </span>
                        {job.lastBuild && (
                          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">
                            #{job.lastBuild.number} · {formatTime(job.lastBuild.timestamp)}
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </motion.div>
        )}

        {/* Health Tab */}
        {activeTab === 'health' && (
          <motion.div key="health" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
            <div className="space-y-6 p-4">
              <CicdHealthScorecard
                githubRuns={githubRuns}
                jenkinsJobs={jenkinsJobs}
                githubDailyStats={githubMetrics.metrics?.dailyStats ?? []}
                jenkinsDailyStats={jenkinsMetrics.metrics?.dailyStats ?? []}
                onCardClick={(provider, _name) => setActiveTab(provider)}
              />
              <div className="border-t border-[rgba(0,0,0,0.06)] pt-4">
                <CicdNotificationSettings
                  config={notificationConfig.config}
                  isLoading={notificationConfig.isLoading}
                  isSaving={notificationConfig.isSaving}
                  saveError={notificationConfig.saveError}
                  onSave={notificationConfig.saveConfig}
                  onTestSlack={notificationConfig.testSlack}
                />
              </div>
            </div>
          </motion.div>
        )}
        </AnimatePresence>
      </div>

      {/* Workflow History Modal */}
      <DialogRoot isOpen={!!selectedWorkflow} onClose={() => { closeWorkflow(); setDialogTab('overview'); failureAnalysisHook.reset(); }} maxWidth="max-w-4xl">
        {selectedWorkflow && (
          <DialogPanel
            title={
              <div className="min-w-0 flex items-center gap-3">
                <div className="w-9 h-9 bg-indigo-50 border border-indigo-100 rounded-lg flex items-center justify-center flex-shrink-0">
                  <Icon icon={'repository' in selectedWorkflow ? 'mdi:github' : 'simple-icons:jenkins'} width={17} color="#4F46E5" />
                </div>
                <div className="min-w-0">
                  <p className="text-[15px] font-bold text-[rgba(0,0,0,0.88)] truncate">{selectedWorkflow.name}</p>
                  <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
                    {'repository' in selectedWorkflow && selectedWorkflow.repository
                      ? selectedWorkflow.repository.full_name
                      : 'Jenkins Job'}
                  </p>
                </div>
              </div>
            }
            onClose={() => { closeWorkflow(); setDialogTab('overview'); failureAnalysisHook.reset(); }}
          >
            {/* Dialog tab strip */}
            <div className="flex gap-0 border-b border-[rgba(0,0,0,0.06)] mb-4 -mx-6 px-6">
              {(['overview', 'analysis', 'diff'] as const).map(tab => (
                <button
                  key={tab}
                  onClick={() => {
                    setDialogTab(tab);
                    if (tab === 'analysis' && selectedRun && selectedWorkflow && 'repository' in selectedWorkflow && selectedWorkflow.repository) {
                      const parts = (selectedWorkflow.repository as any).full_name?.split('/') ?? [];
                      const owner = parts[0] ?? '';
                      const repo = parts[1] ?? '';
                      const jobId = 'id' in selectedRun ? (selectedRun as any).id : '';
                      if (owner && repo && jobId) {
                        failureAnalysisHook.fetchFailureAnalysis(owner, repo, jobId);
                      }
                    }
                    if (tab === 'diff' && selectedRun && selectedWorkflow && 'repository' in selectedWorkflow && selectedWorkflow.repository) {
                      const parts = (selectedWorkflow.repository as any).full_name?.split('/') ?? [];
                      const owner = parts[0] ?? '';
                      const repo = parts[1] ?? '';
                      const runId = 'id' in selectedRun ? (selectedRun as any).id : '';
                      if (owner && repo && runId) {
                        failureAnalysisHook.fetchDiffSummary(owner, repo, runId);
                      }
                    }
                  }}
                  className={`px-4 py-2 text-[12px] font-medium border-b-2 transition-colors ${
                    dialogTab === tab
                      ? 'border-[rgba(0,0,0,0.75)] text-[rgba(0,0,0,0.85)]'
                      : 'border-transparent text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.65)]'
                  }`}
                >
                  {tab === 'analysis' ? 'Failure Analysis' : tab === 'diff' ? 'Diff' : 'Overview'}
                </button>
              ))}
            </div>

            {dialogTab === 'overview' && (
              <div className="flex flex-col md:flex-row -mx-6 -mb-5 overflow-hidden" style={{ maxHeight: '60vh' }}>
                {/* History list */}
                <div className="w-full md:w-1/3 border-r border-[rgba(0,0,0,0.08)] overflow-y-auto">
                  <div className="px-4 py-3 border-b border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)]">
                    <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-[0.07em]">Recent Runs</p>
                  </div>
                  {isLoadingHistory && (
                    <div className="flex items-center justify-center py-10">
                      <Icon icon="mdi:loading" width={22} color="#6366F1" className="animate-spin" />
                    </div>
                  )}
                  {!isLoadingHistory && workflowHistory.length === 0 && (
                    <p className="text-center text-[12px] text-[rgba(0,0,0,0.35)] py-8">No run history found.</p>
                  )}
                  {!isLoadingHistory && workflowHistory.map(run => {
                    const st = statusStyle(run.displayStatus);
                    const selected = isRunSelected(run);
                    return (
                      <div key={getRunId(run)} onClick={() => selectRunFromHistory(run)}
                        className={`flex items-center gap-3 px-4 py-3.5 cursor-pointer border-b border-[rgba(0,0,0,0.05)] transition-colors ${
                          selected ? 'bg-indigo-50 border-l-2 border-l-indigo-500' : 'hover:bg-[rgba(0,0,0,0.02)]'
                        }`}>
                        <span className={`w-2 h-2 rounded-full flex-shrink-0 ${st.dot}`} />
                        <div className="flex-1 min-w-0">
                          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate">Run #{getRunNumber(run)}</p>
                          <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">
                            {formatTime('updated_at' in run ? run.updated_at : (run as any).timestamp)}
                          </p>
                        </div>
                        <Icon icon="mdi:chevron-right" width={14} color="rgba(0,0,0,0.25)" />
                      </div>
                    );
                  })}
                </div>

                {/* Pipeline stages */}
                <div className="w-full md:w-2/3 p-5 overflow-y-auto">
                  <div className="flex justify-between items-center mb-5">
                    <div>
                      <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-[0.07em] mb-0.5">Pipeline Stages</p>
                      <p className="text-[15px] font-bold text-[rgba(0,0,0,0.88)]">Run #{selectedRunNumber}</p>
                    </div>
                    {selectedRunUrl && (
                      <a href={selectedRunUrl} target="_blank" rel="noopener noreferrer"
                        className="flex items-center gap-1 text-[12px] font-semibold text-indigo-600 hover:text-indigo-800 transition-colors">
                        External Logs <Icon icon="mdi:open-in-new" width={12} />
                      </a>
                    )}
                  </div>

                  {isStagesLoading && (
                    <div className="flex flex-col items-center justify-center py-12 gap-3">
                      <Icon icon="mdi:loading" width={28} color="#6366F1" className="animate-spin" />
                      <p className="text-[12px] text-[rgba(0,0,0,0.4)]">Loading pipeline topology…</p>
                    </div>
                  )}

                  {!isStagesLoading && pipelineStages.length === 0 && (
                    <div className="text-center py-10 bg-[rgba(0,0,0,0.02)] border border-dashed border-[rgba(0,0,0,0.10)] rounded-xl">
                      <Icon icon="mdi:source-branch" width={32} color="rgba(0,0,0,0.15)" className="mx-auto mb-3" />
                      <p className="text-[13px] text-[rgba(0,0,0,0.4)]">No stage information available for this run.</p>
                    </div>
                  )}

                  {!isStagesLoading && pipelineStages.length > 0 && (
                    <div className="flex flex-wrap items-start gap-0">
                      {pipelineStages.map((stage, i) => {
                        const ss = stageStyle(stage.status);
                        return (
                          <div key={i} className="flex items-center">
                            <div className="flex flex-col items-center w-24">
                              <div className={`w-10 h-10 ${ss.bg} border-2 ${ss.ring} rounded-full flex items-center justify-center`}>
                                <Icon icon={ss.icon} width={16} color={ss.iconColor}
                                  className={stage.status === 'running' ? 'animate-spin' : ''} />
                              </div>
                              <p className="text-[11px] font-bold text-[rgba(0,0,0,0.75)] mt-2 text-center leading-tight px-1 truncate w-full" title={stage.name}>
                                {stage.name}
                              </p>
                              {stage.duration !== '-' && (
                                <p className="text-[10px] text-[rgba(0,0,0,0.35)] font-mono mt-0.5">{stage.duration}</p>
                              )}
                            </div>
                            {i < pipelineStages.length - 1 && (
                              <div className="w-6 h-0.5 bg-[rgba(0,0,0,0.10)] mb-6 flex-shrink-0" />
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}

                  {/* Log Viewer */}
                  {selectedRun && (
                    <>
                      {selectedWorkflow && 'repository' in selectedWorkflow && selectedWorkflow.repository ? (
                        (() => {
                          const [owner, repo] = (selectedWorkflow.repository as any).full_name.split('/');
                          const jobId = 'id' in selectedRun ? (selectedRun as any).id : undefined;
                          return (
                            <CicdLogViewer
                              provider="github"
                              owner={owner}
                              repo={repo}
                              jobId={jobId}
                              autoOpen={autoOpenLog || (selectedRun.displayStatus || '').toLowerCase().includes('fail')}
                            />
                          );
                        })()
                      ) : (
                        <CicdLogViewer
                          provider="jenkins"
                          jobName={selectedWorkflow?.name}
                          buildNumber={selectedRunNumber ? Number(selectedRunNumber) : undefined}
                          autoOpen={autoOpenLog || (selectedRun.displayStatus || '').toLowerCase().includes('fail')}
                        />
                      )}
                    </>
                  )}
                </div>
              </div>
            )}

            {dialogTab === 'analysis' && (
              <CicdFailureDetail
                failureCategory={(selectedWorkflow as any)?.failureCategory}
                failedStage={(selectedWorkflow as any)?.failedStage}
                analysis={failureAnalysisHook.failureAnalysis}
                isLoading={failureAnalysisHook.isLoadingAnalysis}
                error={failureAnalysisHook.analysisError}
                onRetry={() => {
                  if (selectedRun && selectedWorkflow && 'repository' in selectedWorkflow && selectedWorkflow.repository) {
                    const parts = (selectedWorkflow.repository as any).full_name?.split('/') ?? [];
                    const owner = parts[0] ?? '';
                    const repo = parts[1] ?? '';
                    const jobId = 'id' in selectedRun ? (selectedRun as any).id : '';
                    if (owner && repo && jobId) failureAnalysisHook.fetchFailureAnalysis(owner, repo, jobId);
                  }
                }}
                onViewFullLog={() => setDialogTab('overview')}
              />
            )}

            {dialogTab === 'diff' && (
              <CicdDiffSummary
                diff={failureAnalysisHook.diffSummary}
                isLoading={failureAnalysisHook.isLoadingDiff}
                error={failureAnalysisHook.diffError}
                onRetry={() => {
                  if (selectedRun && selectedWorkflow && 'repository' in selectedWorkflow && selectedWorkflow.repository) {
                    const parts = (selectedWorkflow.repository as any).full_name?.split('/') ?? [];
                    const owner = parts[0] ?? '';
                    const repo = parts[1] ?? '';
                    const runId = 'id' in selectedRun ? (selectedRun as any).id : '';
                    if (owner && repo && runId) failureAnalysisHook.fetchDiffSummary(owner, repo, runId);
                  }
                }}
              />
            )}
          </DialogPanel>
        )}
      </DialogRoot>
    </main>
  );
};

export default CICDPipelinesPage;
