/**
 * CICD Pipelines Page
 *
 * Display and manage CICD pipelines from GitHub Actions and Jenkins
 *
 * Converted from: cicd_pipelines.html
 *
 * Features:
 * - View GitHub Actions workflow runs
 * - View Jenkins job builds
 * - Modal with workflow/job history
 * - Visual pipeline stage representation
 * - Real-time backend integration
 */

import React, { useCallback } from 'react';
import { useCICDPipelines } from '../hooks/useCICDPipelines';
import { RunHistory } from '../types/cicdPipelines';
import clsx from 'clsx';
import { useTopbar } from '../contexts/TopbarContext';
import Skeleton from '../components/Shared/Skeleton';
import { DialogRoot, DialogPanel } from '../components/ui';

const CICDPipelinesPage: React.FC = () => {
  const {
    // GitHub
    githubRuns,
    isLoadingGithub,

    // Jenkins
    jenkinsJobs,
    isLoadingJenkins,

    // Tab
    activeTab,
    setActiveTab,

    // Workflow
    selectedWorkflow,
    workflowHistory,
    isLoadingHistory,
    viewWorkflowHistory,
    closeWorkflow,

    // Run details
    selectedRun,
    selectedRunNumber,
    selectedRunUrl,
    pipelineStages,
    isStagesLoading,
    selectRunFromHistory,

    // Utilities
    refreshData,
  } = useCICDPipelines();

  /**
   * Format timestamp to readable format
   */
  const formatTime = useCallback((timeValue: string | number | undefined): string => {
    if (!timeValue) return 'N/A';
    try {
      const date = typeof timeValue === 'number' ? new Date(timeValue) : new Date(timeValue);
      return date.toLocaleString(undefined, {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch (e) {
      return String(timeValue);
    }
  }, []);

  /**
   * Get status color class
   */
  const getRunStatusColor = useCallback((status: string): string => {
    const s = (status || '').toLowerCase();
    if (s === 'success' || s === 'completed') return 'text-green-600';
    if (s === 'failure' || s === 'failed') return 'text-red-600';
    if (s === 'in_progress' || s === 'running' || s === 'building') return 'text-blue-600';
    if (s === 'cancelled' || s === 'aborted') return 'text-gray-500';
    if (s === 'unstable') return 'text-yellow-600';
    return 'text-gray-400';
  }, []);

  /**
   * Get border color class for cards
   */
  const getRunBorderColor = useCallback((status: string): string => {
    const s = (status || '').toLowerCase();
    if (s === 'success' || s === 'completed') return 'border-green-500';
    if (s === 'failure' || s === 'failed') return 'border-red-600';
    if (s === 'in_progress' || s === 'running' || s === 'building') return 'border-blue-500';
    if (s === 'cancelled' || s === 'aborted') return 'border-gray-500';
    if (s === 'unstable') return 'border-yellow-500';
    return 'border-gray-400';
  }, []);

  /**
   * Get status icon color
   */
  const getRunStatusIconColor = useCallback((status: string): string => {
    const s = (status || '').toLowerCase();
    if (s === 'success' || s === 'completed') return 'text-green-600';
    if (s === 'failure' || s === 'failed') return 'text-red-600';
    if (s === 'in_progress' || s === 'running' || s === 'building') return 'text-blue-600';
    if (s === 'cancelled' || s === 'aborted') return 'text-gray-500';
    if (s === 'unstable') return 'text-yellow-500';
    return 'text-gray-400';
  }, []);

  /**
   * Get run ID for comparison
   */
  const getRunId = useCallback((run: RunHistory): string | number => {
    if ('id' in run) return run.id;
    return (run as any).number;
  }, []);

  /**
   * Get run number for display
   */
  const getRunNumber = useCallback((run: RunHistory): string | number => {
    return run.run_number ?? (run as any).number;
  }, []);

  /**
   * Check if run is selected
   */
  const isRunSelected = useCallback(
    (run: RunHistory): boolean => {
      return selectedRun ? getRunId(selectedRun) === getRunId(run) : false;
    },
    [selectedRun, getRunId]
  );

  /**
   * Get stage class based on status
   */
  const getStageClass = useCallback((status: string): string => {
    const statusMap: { [key: string]: string } = {
      success: 'stage-success',
      failure: 'stage-failure',
      running: 'stage-running',
      skipped: 'stage-skipped',
      pending: 'stage-pending',
    };
    return statusMap[status] || '';
  }, []);

  /**
   * Close modal on escape key
   */
  React.useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && selectedWorkflow) {
        closeWorkflow();
      }
    };

    window.addEventListener('keydown', handleEscape);
    return () => window.removeEventListener('keydown', handleEscape);
  }, [selectedWorkflow, closeWorkflow]);

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();
  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={refreshData}
          disabled={isLoadingGithub || isLoadingJenkins}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i
            className={clsx(
              'fas fa-sync-alt',
              (isLoadingGithub || isLoadingJenkins) && 'animate-spin'
            )}
          ></i>
          <span>Refresh All</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isLoadingGithub, isLoadingJenkins, refreshData]);

  return (
    <div className="cicd-pipelines-container min-h-screen bg-gray-50">
      {/* Header */}
      {/* Main Content */}

      {/* Main Content */}
      <div className="px-8 pb-8">
        {/* Tabs */}
        <div className="mb-6 border-b border-gray-200">
          <nav className="-mb-px flex space-x-6" aria-label="Tabs">
            <button
              onClick={() => setActiveTab('github')}
              className={clsx('tab-button', activeTab === 'github' && 'active')}
            >
              <i className="fab fa-github mr-2"></i> GitHub Actions
            </button>
            <button
              onClick={() => setActiveTab('jenkins')}
              className={clsx('tab-button', activeTab === 'jenkins' && 'active')}
            >
              <i className="fab fa-jenkins mr-2"></i> Jenkins
            </button>
          </nav>
        </div>

        {/* GitHub Tab Content */}
        {activeTab === 'github' && (
          <div>
            {/* Loading State */}
            {isLoadingGithub && (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {[1, 2, 3, 4, 5, 6].map(i => (
                  <div key={i} className="bg-white p-4 rounded-lg shadow border-l-4 border-gray-100 h-32 flex flex-col justify-between">
                    <div className="flex justify-between">
                      <Skeleton variant="rounded" width={100} height={20} />
                      <Skeleton variant="text" width={60} height={16} />
                    </div>
                    <div>
                      <Skeleton variant="text" width="70%" height={24} className="mb-2" />
                      <Skeleton variant="text" width="40%" height={16} />
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* Empty State */}
            {!isLoadingGithub && githubRuns.length === 0 && (
              <div className="text-center py-10 bg-white rounded-lg shadow">
                <i className="fas fa-info-circle text-2xl text-gray-400 mb-2"></i>
                <p className="text-gray-500">No GitHub Actions runs found.</p>
                <p className="text-sm text-gray-400 mt-2">
                  Have you connected a repository in the Account Management?
                </p>
              </div>
            )}

            {/* GitHub Runs Grid */}
            {!isLoadingGithub && githubRuns.length > 0 && (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {githubRuns.map((run) => (
                  <div
                    key={run.id}
                    onClick={() => viewWorkflowHistory(run)}
                    className={clsx(
                      'bg-white p-4 rounded-lg shadow border-l-4 cursor-pointer transition-all hover:shadow-lg hover:scale-105',
                      getRunBorderColor(run.displayStatus)
                    )}
                  >
                    <div className="flex justify-between items-center mb-2">
                      <span className="text-xs font-semibold bg-gray-100 text-gray-700 px-2 py-0.5 rounded">
                        {run.repository ? run.repository.full_name : 'Unknown Repo'}
                      </span>
                      <a
                        href={run.html_url}
                        target="_blank"
                        rel="noopener noreferrer"
                        onClick={(e) => e.stopPropagation()}
                        className="text-xs text-indigo-500 hover:text-indigo-700 font-medium"
                      >
                        View Run <i className="fas fa-external-link-alt ml-1"></i>
                      </a>
                    </div>
                    <p className="font-semibold text-gray-900">{run.name}</p>
                    <p className="text-sm mt-2">
                      Status:{' '}
                      <span className={clsx('font-bold', getRunStatusColor(run.displayStatus))}>
                        {run.displayStatus}
                      </span>
                    </p>
                    <p className="text-xs text-gray-500 mt-1">
                      Last updated: {formatTime(run.updated_at)}
                    </p>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Jenkins Tab Content */}
        {activeTab === 'jenkins' && (
          <div>
            {/* Loading State */}
            {isLoadingJenkins && (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {[1, 2, 3, 4, 5, 6].map(i => (
                  <div key={i} className="bg-white p-4 rounded-lg shadow border-l-4 border-gray-100 h-32 flex flex-col justify-between">
                    <div className="flex justify-between">
                      <Skeleton variant="rounded" width={80} height={20} />
                      <Skeleton variant="text" width={60} height={16} />
                    </div>
                    <div>
                      <Skeleton variant="text" width="70%" height={24} className="mb-2" />
                      <Skeleton variant="text" width="40%" height={16} />
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* Empty State */}
            {!isLoadingJenkins && jenkinsJobs.length === 0 && (
              <div className="text-center py-10 bg-white rounded-lg shadow">
                <i className="fab fa-jenkins text-4xl text-gray-400 mb-2"></i>
                <p className="text-gray-500">No Jenkins jobs found.</p>
                <p className="text-sm text-gray-400 mt-2">
                  Configure your Jenkins connection in Account Management.
                </p>
              </div>
            )}

            {/* Jenkins Jobs Grid */}
            {!isLoadingJenkins && jenkinsJobs.length > 0 && (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {jenkinsJobs.map((job) => (
                  <div
                    key={job.url}
                    onClick={() => viewWorkflowHistory(job)}
                    className={clsx(
                      'bg-white p-4 rounded-lg shadow border-l-4 cursor-pointer transition-all hover:shadow-lg',
                      getRunBorderColor(job.displayStatus)
                    )}
                  >
                    <div className="flex justify-between items-center mb-2">
                      <span className="text-xs font-semibold bg-gray-100 text-gray-700 px-2 py-0.5 rounded">
                        Jenkins
                      </span>
                      <a
                        href={job.url}
                        target="_blank"
                        rel="noopener noreferrer"
                        onClick={(e) => e.stopPropagation()}
                        className="text-xs text-indigo-500 hover:text-indigo-700 font-medium"
                      >
                        Open in Jenkins <i className="fas fa-external-link-alt ml-1"></i>
                      </a>
                    </div>
                    <p className="font-semibold text-gray-900">{job.name}</p>
                    <div className="mt-2 text-sm">
                      <p>
                        Status:{' '}
                        <span className={clsx('font-bold', getRunStatusColor(job.displayStatus))}>
                          {job.displayStatus}
                        </span>
                      </p>
                      {job.lastBuild && (
                        <p className="text-xs text-gray-500 mt-1">
                          Build #{job.lastBuild.number} • {formatTime(job.lastBuild.timestamp)}
                        </p>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Workflow History Modal */}
      <DialogRoot isOpen={!!selectedWorkflow} onClose={closeWorkflow} maxWidth="max-w-4xl">
        {selectedWorkflow && (
          <DialogPanel
            title={
              <div className="min-w-0">
                <div className="text-lg font-bold text-gray-900">{selectedWorkflow.name}</div>
                <p className="text-sm text-gray-500">
                  {'repository' in selectedWorkflow && selectedWorkflow.repository
                    ? selectedWorkflow.repository.full_name
                    : 'Jenkins Job'}
                </p>
              </div>
            }
            onClose={closeWorkflow}
          >
            <div className="flex flex-col md:flex-row -mx-6 -mb-5 overflow-hidden" style={{ maxHeight: '60vh' }}>
              {/* History List */}
              <div className="w-full md:w-1/3 border-r bg-gray-50 overflow-y-auto max-h-[60vh] md:max-h-[calc(90vh-80px)]">
                <div className="p-4 border-b bg-gray-100">
                  <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                    Recent Runs
                  </h3>
                </div>

                {isLoadingHistory && (
                  <div className="text-center py-8">
                    <i className="fas fa-spinner fa-spin text-xl text-indigo-600"></i>
                  </div>
                )}

                {!isLoadingHistory && workflowHistory.length === 0 && (
                  <p className="text-center text-gray-500 py-6 text-sm">No run history found.</p>
                )}

                {!isLoadingHistory &&
                  workflowHistory.map((run) => (
                    <div
                      key={getRunId(run)}
                      onClick={() => selectRunFromHistory(run)}
                      className={clsx('history-item', isRunSelected(run) && 'active-run')}
                    >
                      <div className="flex items-center w-full">
                        <div className="mr-3">
                          <i
                            className={clsx(
                              'fas fa-circle text-xs',
                              getRunStatusIconColor(run.displayStatus)
                            )}
                          ></i>
                        </div>
                        <div className="flex-1 min-w-0">
                          <p className="text-sm font-medium text-gray-900 truncate">
                            Run #{getRunNumber(run)}
                          </p>
                          <p className="text-xs text-gray-500 truncate">
                            {formatTime('updated_at' in run ? run.updated_at : (run as any).timestamp)}
                          </p>
                        </div>
                        <div className="ml-2">
                          <i className="fas fa-chevron-right text-gray-300 text-xs"></i>
                        </div>
                      </div>
                    </div>
                  ))}
              </div>

              {/* Pipeline Details */}
              <div className="w-full md:w-2/3 p-6 overflow-y-auto max-h-[60vh] md:max-h-[calc(90vh-80px)]">
                <div className="mb-6 flex justify-between items-end">
                  <div>
                    <h3 className="text-sm font-bold text-gray-500 uppercase tracking-wider mb-1">
                      Pipeline Stages
                    </h3>
                    <p className="text-lg font-semibold text-gray-800">
                      Run #{selectedRunNumber} Details
                    </p>
                  </div>
                  {selectedRunUrl && (
                    <a
                      href={selectedRunUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-xs text-indigo-600 hover:text-indigo-800 font-medium"
                    >
                      Open External Logs <i className="fas fa-external-link-alt ml-1"></i>
                    </a>
                  )}
                </div>

                {isStagesLoading && (
                  <div className="flex justify-center py-12">
                    <div className="flex flex-col items-center">
                      <i className="fas fa-spinner fa-spin text-3xl text-indigo-500 mb-3"></i>
                      <span className="text-sm text-gray-500">Loading pipeline topology...</span>
                    </div>
                  </div>
                )}

                {!isStagesLoading && pipelineStages.length === 0 && (
                  <div className="text-center py-10 bg-gray-50 rounded-xl border border-dashed border-gray-300">
                    <i className="fas fa-project-diagram text-gray-300 text-4xl mb-3"></i>
                    <p className="text-gray-500">No stage information available for this run.</p>
                  </div>
                )}

                {!isStagesLoading && pipelineStages.length > 0 && (
                  <div className="pipeline-container">
                    {pipelineStages.map((stage, index) => (
                      <div
                        key={index}
                        className={clsx(
                          'pipeline-stage',
                          stage.status === 'success' && 'completed',
                          getStageClass(stage.status)
                        )}
                      >
                        <div className="stage-node shadow-sm">
                          {stage.status === 'success' && <i className="fas fa-check"></i>}
                          {stage.status === 'failure' && <i className="fas fa-times"></i>}
                          {stage.status === 'running' && <i className="fas fa-sync-alt fa-spin"></i>}
                          {stage.status === 'skipped' && <i className="fas fa-slash"></i>}
                          {stage.status === 'pending' && <i className="fas fa-clock"></i>}
                        </div>

                        <div className="mt-3 text-center px-2 w-full">
                          <p
                            className="text-xs font-bold text-gray-800 whitespace-nowrap overflow-hidden text-ellipsis max-w-[120px]"
                            title={stage.name}
                          >
                            {stage.name}
                          </p>
                          {stage.duration !== '-' && (
                            <p className="text-[10px] text-gray-500 font-mono mt-0.5">
                              {stage.duration}
                            </p>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </DialogPanel>
        )}
      </DialogRoot>
    </div>
  );
};

export default CICDPipelinesPage;
