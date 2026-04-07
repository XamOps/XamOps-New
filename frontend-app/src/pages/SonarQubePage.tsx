/**
 * SonarQube Code Quality Dashboard Page
 *
 * Displays code quality metrics from SonarQube including quality gates,
 * bugs, vulnerabilities, code smells, test coverage, and more.
 *
 * Converted from: sonarqube.html
 *
 * Features:
 * - View quality metrics from multiple SonarQube projects
 * - Select different projects from dropdown
 * - Manage projects (add/delete)
 * - Real-time backend integration
 * - Quality gate status badges
 * - Rating badges (A-E) for different metrics
 */

import React, { useState, useCallback, useEffect } from 'react';
import { useSonarQube } from '../hooks/useSonarQube';
import { QualityGateStatus, Rating, NewSonarQubeProject } from '../types/sonarqube';
import { useTopbar } from '../contexts/TopbarContext';
import Skeleton from '../components/Shared/Skeleton';
import clsx from 'clsx';
import { AlertDialog, DialogRoot, DialogPanel } from '../components/ui';

const SonarQubePage: React.FC = () => {
  const {
    projects,
    selectedProjectId,
    metrics,
    isLoading,
    isRefreshing,
    error,
    modalError,
    setSelectedProjectId,
    refreshMetrics,
    addProject,
    deleteProject,
  } = useSonarQube();

  const [isManageModalOpen, setIsManageModalOpen] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [pendingDeleteProjectId, setPendingDeleteProjectId] = useState<number | null>(null);
  const [newProject, setNewProject] = useState<NewSonarQubeProject>({
    projectName: '',
    serverUrl: '',
    projectKey: '',
    token: '',
  });

  /**
   * Get selected project URL
   */
  const getSelectedProjectUrl = useCallback(() => {
    if (!selectedProjectId) return '#';
    const project = projects.find(p => p.id.toString() === selectedProjectId);
    if (!project) return '#';
    return `${project.serverUrl}/dashboard?id=${project.projectKey}`;
  }, [selectedProjectId, projects]);

  /**
   * Get quality gate CSS class
   */
  const getQualityGateClass = useCallback((status: QualityGateStatus | undefined) => {
    if (!status) return 'quality-gate-none';
    const upperStatus = status.toUpperCase();
    if (upperStatus === 'OK' || upperStatus === 'PASSED') return 'quality-gate-ok';
    if (upperStatus === 'ERROR' || upperStatus === 'FAILED') return 'quality-gate-error';
    if (upperStatus === 'WARN') return 'quality-gate-warn';
    return 'quality-gate-none';
  }, []);

  /**
   * Get quality gate icon
   */
  const getQualityGateIcon = useCallback((status: QualityGateStatus | undefined) => {
    if (!status) return '?';
    const upperStatus = status.toUpperCase();
    if (upperStatus === 'OK' || upperStatus === 'PASSED') return '✓';
    if (upperStatus === 'ERROR' || upperStatus === 'FAILED') return '✕';
    return '?';
  }, []);

  /**
   * Get quality gate text
   */
  const getQualityGateText = useCallback((status: QualityGateStatus | undefined) => {
    if (!status) return 'Unknown';
    const upperStatus = status.toUpperCase();
    if (upperStatus === 'OK' || upperStatus === 'PASSED') return 'Passed';
    if (upperStatus === 'ERROR' || upperStatus === 'FAILED') return 'Failed';
    if (upperStatus === 'WARN') return 'Warning';
    return status;
  }, []);

  /**
   * Get rating CSS class
   */
  const getRatingClass = useCallback((rating: Rating | undefined) => {
    if (!rating) return 'rating-e';
    switch (rating.toUpperCase()) {
      case 'A': return 'rating-a';
      case 'B': return 'rating-b';
      case 'C': return 'rating-c';
      case 'D': return 'rating-d';
      case 'E': return 'rating-e';
      default: return 'rating-e';
    }
  }, []);

  /**
   * Get semantic color class for issue count numbers
   */
  const getCountClass = useCallback((count: number | undefined, type: 'bug' | 'vuln' | 'hotspot' | 'smell') => {
    const n = count || 0;
    if (n === 0) return 'metric-count-good';
    if (type === 'bug' || type === 'vuln') return 'metric-count-danger';
    if (type === 'hotspot') return 'metric-count-warn';
    return 'metric-count-neutral';
  }, []);

  /**
   * Get progress bar fill class based on metric type and value
   */
  const getCoverageProgressClass = useCallback((coverage: number | undefined) => {
    const c = coverage || 0;
    if (c >= 80) return 'progress-fill-good';
    if (c >= 50) return 'progress-fill-warn';
    return 'progress-fill-danger';
  }, []);

  /**
   * Compute overall health label from all ratings
   */
  const getOverallHealth = useCallback(() => {
    if (!metrics) return null;
    const ratings = [metrics.reliabilityRating, metrics.securityRating, metrics.maintainabilityRating, metrics.securityReviewRating];
    const scores: Record<string, number> = { A: 5, B: 4, C: 3, D: 2, E: 1 };
    const valid = ratings.filter(Boolean) as string[];
    if (valid.length === 0) return null;
    const avg = valid.reduce((sum, r) => sum + (scores[r.toUpperCase()] || 1), 0) / valid.length;
    if (avg >= 4.5) return { label: 'Excellent', color: 'health-excellent' };
    if (avg >= 3.5) return { label: 'Good', color: 'health-good' };
    if (avg >= 2.5) return { label: 'Fair', color: 'health-fair' };
    return { label: 'Poor', color: 'health-poor' };
  }, [metrics]);

  /**
   * Format technical debt (minutes to days/hours/mins)
   */
  const formatTechDebt = useCallback((minutes: number | undefined) => {
    if (!minutes || minutes === 0) return '0m';
    const days = Math.floor(minutes / 480);
    const remainingMinutes = minutes % 480;
    const hours = Math.floor(remainingMinutes / 60);
    const mins = remainingMinutes % 60;

    let result = '';
    if (days > 0) result += `${days}d `;
    if (hours > 0) result += `${hours}h `;
    if (mins > 0) result += `${mins}m`;
    return result.trim();
  }, []);

  /**
   * Format number with commas
   */
  const formatNumber = useCallback((num: number | undefined) => {
    return (num || 0).toLocaleString();
  }, []);

  /**
   * Handle add project form submission
   */
  const handleAddProject = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setIsSaving(true);

    try {
      await addProject(newProject);
      // Reset form and close modal
      setNewProject({
        projectName: '',
        serverUrl: '',
        projectKey: '',
        token: '',
      });
      setIsManageModalOpen(false);
    } catch (err) {
      // Error is already set in the hook
    } finally {
      setIsSaving(false);
    }
  }, [newProject, addProject]);

  /**
   * Handle delete project
   */
  const handleDeleteProject = useCallback((projectId: number) => {
    setPendingDeleteProjectId(projectId);
  }, []);

  const confirmDeleteProject = useCallback(async () => {
    if (pendingDeleteProjectId === null) return;
    const id = pendingDeleteProjectId;
    setPendingDeleteProjectId(null);
    try {
      await deleteProject(id);
    } catch (err) {
      // Error is already set in the hook
    }
  }, [pendingDeleteProjectId, deleteProject]);

  /**
   * Close modal on escape key
   */
  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isManageModalOpen) {
        setIsManageModalOpen(false);
      }
    };

    window.addEventListener('keydown', handleEscape);
    return () => window.removeEventListener('keydown', handleEscape);
  }, [isManageModalOpen]);

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={refreshMetrics}
          disabled={isRefreshing || !selectedProjectId}
          className="flex items-center gap-2 px-4 py-2 bg-white text-indigo-600 text-xs font-bold rounded-xl border border-indigo-100 shadow-sm hover:shadow-md transition-all disabled:opacity-50"
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>Refresh</span>
        </button>
        <button
          onClick={() => setIsManageModalOpen(true)}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all active:scale-95"
        >
          <i className="fas fa-cog"></i>
          <span>Manage Projects</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, selectedProjectId, refreshMetrics]);

  return (
    <div className="sonarqube-container min-h-screen bg-gray-50">
      <AlertDialog
        isOpen={pendingDeleteProjectId !== null}
        title="Remove project configuration?"
        description="This will remove the SonarQube project from XamOps."
        confirmLabel="Remove"
        variant="danger"
        onConfirm={confirmDeleteProject}
        onCancel={() => setPendingDeleteProjectId(null)}
      />
      {/* Header Bar */}
      {/* Main Content */}

      {/* Main Content */}
      <main className="p-8 space-y-8">
        {/* Project Selector */}
        <div className="glass-card p-6 stagger-enter">
          <label htmlFor="projectSelect" className="block text-sm font-bold text-gray-900 mb-3 tracking-wide">
            <i className="fas fa-folder-open text-indigo-600 mr-2"></i>SELECT PROJECT
          </label>
          <select
            id="projectSelect"
            value={selectedProjectId}
            onChange={(e) => setSelectedProjectId(e.target.value)}
            className="w-full px-4 py-3 bg-white border border-gray-300 rounded-xl text-gray-900 font-medium focus:outline-none focus:border-indigo-500 transition-all cursor-pointer"
          >
            <option value="">-- Select a Project --</option>
            {projects.map((project) => (
              <option key={project.id} value={project.id}>
                {project.projectName}
              </option>
            ))}
          </select>
        </div>

        {/* Loading State */}
        {isLoading && (
          <div className="space-y-8 stagger-enter">
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
              <div className="glass-card p-6 h-48 flex flex-col justify-center items-center">
                <Skeleton variant="circular" width={64} height={64} className="mb-4" />
                <Skeleton variant="text" width={120} height={24} />
              </div>
              <div className="lg:col-span-2 glass-card p-6 h-48 flex flex-col justify-center items-center">
                <Skeleton variant="text" width={160} height={60} className="mb-2" />
                <Skeleton variant="text" width={100} height={20} />
              </div>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {[1, 2, 3, 4, 5, 6].map(i => (
                <div key={i} className="glass-card p-6 h-64 flex flex-col justify-between">
                  <div className="flex justify-between">
                    <Skeleton variant="rounded" width={48} height={48} />
                    <Skeleton variant="rounded" width={32} height={32} />
                  </div>
                  <div className="space-y-4">
                    <Skeleton variant="text" width="60%" height={24} />
                    <Skeleton variant="rounded" width="100%" height={8} />
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Error Message */}
        {error && (
          <div className="glass-card border-red-500/50 bg-gradient-to-r from-red-50/80 to-red-100/50 p-5 flex items-start gap-3">
            <i className="fas fa-exclamation-circle text-red-600 flex-shrink-0 mt-1 text-xl"></i>
            <div>
              <h4 className="font-bold text-red-900">Error</h4>
              <p className="text-sm text-red-800 mt-1">{error}</p>
            </div>
          </div>
        )}

        {/* No Projects State */}
        {!isLoading && projects.length === 0 && (
          <div className="glass-card p-16">
            <div className="w-20 h-20 bg-gradient-to-br from-indigo-100 to-purple-100 rounded-full flex items-center justify-center mb-6 mx-auto">
              <i className="fas fa-inbox text-indigo-600 text-4xl"></i>
            </div>
            <h3 className="text-3xl font-bold text-gray-900 text-center mt-4">No Projects Found</h3>
            <p className="text-gray-600 mt-3 text-center max-w-sm mx-auto">
              Add your first SonarQube project to start analyzing code quality
            </p>
            <div className="flex justify-center mt-8">
              <button
                onClick={() => setIsManageModalOpen(true)}
                className="px-8 py-3 btn-primary rounded-lg font-semibold flex items-center gap-2"
              >
                <i className="fas fa-plus"></i>Add Project
              </button>
            </div>
          </div>
        )}

        {/* No Project Selected State */}
        {!isLoading && projects.length > 0 && !selectedProjectId && (
          <div className="glass-card p-16">
            <div className="w-20 h-20 bg-gradient-to-br from-blue-100 to-cyan-100 rounded-full flex items-center justify-center mb-6 mx-auto">
              <i className="fas fa-hand-pointer text-blue-600 text-4xl"></i>
            </div>
            <h3 className="text-3xl font-bold text-gray-900 text-center mt-4">Select a Project</h3>
            <p className="text-gray-600 mt-3 text-center">Choose a project above to view detailed quality metrics</p>
          </div>
        )}

        {/* Metrics Display */}
        {!isLoading && selectedProjectId && metrics && (
          <div className="space-y-8 stagger-enter">

            {/* Summary Strip */}
            {(() => {
              const health = getOverallHealth();
              return (
                <div className="summary-strip glass-card p-4">
                  <div className="flex flex-wrap items-center gap-3">
                    {health && (
                      <span className={`summary-pill summary-pill-health ${health.color}`}>
                        <i className="fas fa-heartbeat"></i>
                        {health.label}
                      </span>
                    )}
                    <span className={`summary-pill ${(metrics.bugs || 0) === 0 ? 'summary-pill-ok' : 'summary-pill-danger'}`}>
                      <i className="fas fa-bug"></i>
                      {metrics.bugs || 0} Bug{(metrics.bugs || 0) !== 1 ? 's' : ''}
                    </span>
                    <span className={`summary-pill ${(metrics.vulnerabilities || 0) === 0 ? 'summary-pill-ok' : 'summary-pill-danger'}`}>
                      <i className="fas fa-shield-virus"></i>
                      {metrics.vulnerabilities || 0} Vuln{(metrics.vulnerabilities || 0) !== 1 ? 's' : ''}
                    </span>
                    <span className={`summary-pill ${(metrics.coverage || 0) >= 80 ? 'summary-pill-ok' : (metrics.coverage || 0) >= 50 ? 'summary-pill-warn' : 'summary-pill-danger'}`}>
                      <i className="fas fa-crosshairs"></i>
                      {metrics.coverage || 0}% Coverage
                    </span>
                    <span className="summary-pill summary-pill-neutral">
                      <i className="fas fa-clock"></i>
                      {formatTechDebt(metrics.techDebt)} Debt
                    </span>
                    <span className={`summary-pill ${(metrics.codeSmells || 0) === 0 ? 'summary-pill-ok' : 'summary-pill-neutral'}`}>
                      <i className="fas fa-wind"></i>
                      {formatNumber(metrics.codeSmells)} Smell{(metrics.codeSmells || 0) !== 1 ? 's' : ''}
                    </span>
                  </div>
                </div>
              );
            })()}

            {/* Quality Gate and Lines of Code */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
              <div className={clsx('glass-card metric-card p-6 top-glow', getQualityGateClass(metrics.qualityGateStatus))}>
                <div className="flex justify-between items-start mb-6">
                  <div className="flex items-center gap-3">
                    <i className="fas fa-shield-alt text-2xl text-indigo-600"></i>
                    <h3 className="text-lg font-bold text-gray-900">Quality Gate</h3>
                  </div>
                  <a
                    href={getSelectedProjectUrl()}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-xs font-semibold hover:underline inline-flex items-center gap-1 transition-colors text-indigo-600"
                  >
                    View <i className="fas fa-external-link-alt"></i>
                  </a>
                </div>
                <div className="flex items-baseline gap-4">
                  <span className="text-6xl font-black">{getQualityGateIcon(metrics.qualityGateStatus)}</span>
                  <div>
                    <p className="text-3xl font-bold number-change text-gray-900">
                      {getQualityGateText(metrics.qualityGateStatus)}
                    </p>
                    <p className="text-xs font-semibold text-gray-600 mt-1 uppercase tracking-wide">Status</p>
                  </div>
                </div>
              </div>

              <div className="lg:col-span-2 glass-card metric-card p-6 top-glow">
                <div className="flex items-center gap-3 mb-6">
                  <i className="fas fa-code text-2xl text-indigo-600 icon-pulse"></i>
                  <h3 className="text-lg font-bold text-gray-900">Lines of Code</h3>
                </div>
                <div className="flex items-baseline gap-4">
                  <p className="text-6xl font-black glow-text number-change">{formatNumber(metrics.linesOfCode)}</p>
                  <span className="text-gray-600 font-semibold tracking-wide">total LOC</span>
                </div>
                <div className="mt-6 pt-6 border-t border-gray-200">
                  <p className="text-xs text-gray-600 flex items-center gap-2">
                    <i className="fas fa-info-circle"></i>
                    <span>Comprehensive codebase analysis</span>
                  </p>
                </div>
              </div>
            </div>

            {/* Detailed Metrics Grid */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {/* Reliability */}
              <div className="glass-card metric-card p-6 top-glow hover-lift">
                <div className="flex items-center justify-between mb-6">
                  <div className="flex items-center gap-3">
                    <div className="w-12 h-12 bg-gradient-to-br from-orange-100 to-red-100 rounded-xl flex items-center justify-center">
                      <i className="fas fa-bug text-orange-600 text-lg"></i>
                    </div>
                    <h4 className="font-bold text-gray-900">Reliability</h4>
                  </div>
                  <div className={clsx('rating-badge', getRatingClass(metrics.reliabilityRating))}>
                    {metrics.reliabilityRating || '?'}
                  </div>
                </div>
                <div className="space-y-4">
                  <div>
                    <div className="flex justify-between items-center mb-2">
                      <span className="text-xs font-bold text-gray-600 uppercase tracking-wide">Bugs</span>
                      <span className={`text-2xl font-black number-change ${getCountClass(metrics.bugs, 'bug')}`}>{metrics.bugs || 0}</span>
                    </div>
                    <div className="progress-bar">
                      <div className={`progress-fill ${(metrics.bugs || 0) === 0 ? 'progress-fill-good' : 'progress-fill-danger'}`} style={{ width: `${Math.min((metrics.bugs || 0) * 3, 100)}%` }}></div>
                    </div>
                  </div>
                </div>
              </div>

              {/* Security */}
              <div className="glass-card metric-card p-6 top-glow hover-lift">
                <div className="flex items-center justify-between mb-6">
                  <div className="flex items-center gap-3">
                    <div className="w-12 h-12 bg-gradient-to-br from-red-100 to-rose-100 rounded-xl flex items-center justify-center">
                      <i className="fas fa-lock text-red-600 text-lg"></i>
                    </div>
                    <h4 className="font-bold text-gray-900">Security</h4>
                  </div>
                  <div className={clsx('rating-badge', getRatingClass(metrics.securityRating))}>
                    {metrics.securityRating || '?'}
                  </div>
                </div>
                <div className="space-y-4">
                  <div>
                    <div className="flex justify-between items-center mb-2">
                      <span className="text-xs font-bold text-gray-600 uppercase tracking-wide">Vulnerabilities</span>
                      <span className={`text-2xl font-black number-change ${getCountClass(metrics.vulnerabilities, 'vuln')}`}>{metrics.vulnerabilities || 0}</span>
                    </div>
                    <div className="progress-bar">
                      <div className={`progress-fill ${(metrics.vulnerabilities || 0) === 0 ? 'progress-fill-good' : 'progress-fill-danger'}`} style={{ width: `${Math.min((metrics.vulnerabilities || 0) * 4, 100)}%` }}></div>
                    </div>
                  </div>
                </div>
              </div>

              {/* Security Hotspots */}
              <div className="glass-card metric-card p-6 top-glow hover-lift">
                <div className="flex items-center justify-between mb-6">
                  <div className="flex items-center gap-3">
                    <div className="w-12 h-12 bg-gradient-to-br from-yellow-100 to-amber-100 rounded-xl flex items-center justify-center">
                      <i className="fas fa-exclamation-triangle text-yellow-600 text-lg"></i>
                    </div>
                    <h4 className="font-bold text-gray-900">Hotspots</h4>
                  </div>
                  <div className={clsx('rating-badge', getRatingClass(metrics.securityReviewRating))}>
                    {metrics.securityReviewRating || '?'}
                  </div>
                </div>
                <div className="space-y-4">
                  <div>
                    <div className="flex justify-between items-center mb-2">
                      <span className="text-xs font-bold text-gray-600 uppercase tracking-wide">To Review</span>
                      <span className={`text-2xl font-black number-change ${getCountClass(metrics.securityHotspots, 'hotspot')}`}>{metrics.securityHotspots || 0}</span>
                    </div>
                    <div className="progress-bar">
                      <div className={`progress-fill ${(metrics.securityHotspots || 0) === 0 ? 'progress-fill-good' : 'progress-fill-warn'}`} style={{ width: `${Math.min((metrics.securityHotspots || 0) * 2, 100)}%` }}></div>
                    </div>
                  </div>
                </div>
              </div>

              {/* Maintainability */}
              <div className="glass-card metric-card p-6 top-glow hover-lift">
                <div className="flex items-center justify-between mb-6">
                  <div className="flex items-center gap-3">
                    <div className="w-12 h-12 bg-gradient-to-br from-indigo-100 to-purple-100 rounded-xl flex items-center justify-center">
                      <i className="fas fa-wrench text-indigo-600 text-lg"></i>
                    </div>
                    <h4 className="font-bold text-gray-900">Maintainability</h4>
                  </div>
                  <div className={clsx('rating-badge', getRatingClass(metrics.maintainabilityRating))}>
                    {metrics.maintainabilityRating || '?'}
                  </div>
                </div>
                <div className="space-y-4">
                  <div>
                    <div className="flex justify-between items-center mb-2">
                      <span className="text-xs font-bold text-gray-600 uppercase tracking-wide">Code Smells</span>
                      <span className={`text-2xl font-black number-change ${getCountClass(metrics.codeSmells, 'smell')}`}>{metrics.codeSmells || 0}</span>
                    </div>
                    <div className="progress-bar">
                      <div className={`progress-fill ${(metrics.codeSmells || 0) === 0 ? 'progress-fill-good' : ''}`} style={{ width: `${Math.min((metrics.codeSmells || 0) * 2, 100)}%` }}></div>
                    </div>
                  </div>
                  <div className="pt-4 border-t border-gray-200">
                    <p className="text-xs font-bold text-gray-600 uppercase tracking-wide mb-2">Technical Debt</p>
                    <p className="text-2xl font-black text-gray-900 number-change">{formatTechDebt(metrics.techDebt)}</p>
                  </div>
                </div>
              </div>

              {/* Test Coverage */}
              <div className="glass-card metric-card p-6 top-glow hover-lift">
                <div className="flex items-center gap-3 mb-4">
                  <div className="w-12 h-12 bg-gradient-to-br from-blue-100 to-cyan-100 rounded-xl flex items-center justify-center">
                    <i className="fas fa-crosshairs text-blue-600 text-lg"></i>
                  </div>
                  <h4 className="font-bold text-gray-900">Test Coverage</h4>
                </div>
                <div className="flex items-center gap-6">
                  {/* Coverage Ring */}
                  <div className="coverage-ring-wrapper flex-shrink-0">
                    <svg viewBox="0 0 36 36" className="coverage-ring">
                      <path
                        className="coverage-ring-bg"
                        d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
                      />
                      <path
                        className={`coverage-ring-fill ${getCoverageProgressClass(metrics.coverage)}`}
                        strokeDasharray={`${metrics.coverage || 0}, 100`}
                        d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
                      />
                      <g transform="rotate(90, 18, 18)">
                        <text x="18" y="15.5" className="coverage-ring-pct">{metrics.coverage || 0}</text>
                        <text x="18" y="22" className="coverage-ring-label">%</text>
                      </g>
                    </svg>
                  </div>
                  <div className="space-y-3 flex-1">
                    <div>
                      <p className="text-xs font-bold text-gray-600 uppercase tracking-wide mb-1">Lines to Cover</p>
                      <p className="text-xl font-black text-gray-900 number-change">{formatNumber(metrics.linesToCover)}</p>
                    </div>
                    <div className="progress-bar">
                      <div className={`progress-fill ${getCoverageProgressClass(metrics.coverage)}`} style={{ width: `${metrics.coverage || 0}%` }}></div>
                    </div>
                  </div>
                </div>
              </div>

              {/* Duplications */}
              <div className="glass-card metric-card p-6 top-glow hover-lift">
                <div className="flex items-center gap-3 mb-6">
                  <div className="w-12 h-12 bg-gradient-to-br from-pink-100 to-rose-100 rounded-xl flex items-center justify-center">
                    <i className="fas fa-copy text-pink-600 text-lg"></i>
                  </div>
                  <h4 className="font-bold text-gray-900">Duplications</h4>
                </div>
                <div className="space-y-4">
                  <div>
                    <div className="flex justify-between items-center mb-2">
                      <span className="text-xs font-bold text-gray-600 uppercase tracking-wide">Density</span>
                      <span className={`text-2xl font-black number-change ${(metrics.duplicationDensity || 0) === 0 ? 'metric-count-good' : (metrics.duplicationDensity || 0) <= 5 ? 'metric-count-warn' : 'metric-count-danger'}`}>{metrics.duplicationDensity || 0}%</span>
                    </div>
                    <div className="progress-bar">
                      <div className={`progress-fill ${(metrics.duplicationDensity || 0) === 0 ? 'progress-fill-good' : (metrics.duplicationDensity || 0) <= 5 ? 'progress-fill-warn' : 'progress-fill-danger'}`} style={{ width: `${metrics.duplicationDensity || 0}%` }}></div>
                    </div>
                  </div>
                  <div className="pt-4 border-t border-gray-200">
                    <p className="text-xs font-bold text-gray-600 uppercase tracking-wide mb-2">Duplicated Lines</p>
                    <p className="text-xl font-black text-gray-900 number-change">{formatNumber(metrics.duplicatedLines)}</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Manage Projects Modal */}
      <DialogRoot isOpen={isManageModalOpen} onClose={() => setIsManageModalOpen(false)} maxWidth="max-w-2xl">
        <DialogPanel
          title={
            <div className="flex items-center gap-3">
              <i className="fas fa-cog text-indigo-600 text-2xl icon-pulse"></i>
              <span className="text-2xl font-bold text-gray-900">Manage Projects</span>
            </div>
          }
          onClose={() => setIsManageModalOpen(false)}
          footer={
            <>
              <button
                type="button"
                onClick={() => setIsManageModalOpen(false)}
                className="px-6 py-3 text-sm font-bold text-gray-700 btn-secondary rounded-lg transition-all"
              >
                Cancel
              </button>
              <button
                type="submit"
                form="add-project-form"
                disabled={isSaving}
                className="px-6 py-3 text-sm font-bold text-white btn-primary rounded-lg disabled:opacity-50 transition-all flex items-center gap-2"
              >
                {isSaving ? (
                  <><i className="fas fa-spinner fa-spin"></i> Saving...</>
                ) : (
                  <><i className="fas fa-plus"></i> Add Project</>
                )}
              </button>
            </>
          }
        >
          <div className="overflow-y-auto max-h-[60vh] space-y-6">
            {/* Configured Projects List */}
            <div className="border-b border-gray-200 pb-6">
              <h3 className="text-xl font-bold text-gray-900 mb-5 flex items-center gap-2">
                <i className="fas fa-list text-indigo-600"></i>
                Configured Projects
              </h3>
              <div className="max-h-48 overflow-y-auto space-y-2">
                {projects.length === 0 ? (
                  <div className="text-center py-12">
                    <i className="fas fa-inbox text-gray-400 text-5xl mb-3"></i>
                    <p className="text-gray-600 font-medium">No projects yet</p>
                  </div>
                ) : (
                  projects.map((project) => (
                    <div
                      key={project.id}
                      className="flex justify-between items-center p-4 bg-gray-50 rounded-lg hover:bg-gray-100 transition-all hover-lift"
                    >
                      <div className="flex-1 min-w-0">
                        <p className="font-bold text-gray-900 truncate">{project.projectName}</p>
                        <p className="text-xs text-gray-600 font-mono mt-1">{project.projectKey}</p>
                      </div>
                      <button
                        onClick={() => handleDeleteProject(project.id)}
                        className="ml-4 px-4 py-2 text-xs font-bold text-red-600 bg-red-50 rounded-lg hover:bg-red-100 transition-all flex items-center gap-2 border border-red-200"
                      >
                        <i className="fas fa-trash"></i>
                        Delete
                      </button>
                    </div>
                  ))
                )}
              </div>
            </div>

            {/* Add New Project Form */}
            <form id="add-project-form" onSubmit={handleAddProject} className="space-y-6">
              <h3 className="text-xl font-bold text-gray-900 flex items-center gap-2">
                <i className="fas fa-plus-circle text-green-600"></i>
                Add New Project
              </h3>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                <div>
                  <label htmlFor="projectName" className="block text-sm font-bold text-gray-900 mb-2 uppercase tracking-wide">
                    Project Name <span className="text-red-600">*</span>
                  </label>
                  <input
                    type="text"
                    id="projectName"
                    value={newProject.projectName}
                    onChange={(e) => setNewProject({ ...newProject, projectName: e.target.value })}
                    placeholder="e.g., XamOps Backend"
                    required
                    className="w-full px-4 py-3 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-500 focus:outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100 transition-all"
                  />
                </div>
                <div>
                  <label htmlFor="serverUrl" className="block text-sm font-bold text-gray-900 mb-2 uppercase tracking-wide">
                    Server URL <span className="text-red-600">*</span>
                  </label>
                  <input
                    type="url"
                    id="serverUrl"
                    value={newProject.serverUrl}
                    onChange={(e) => setNewProject({ ...newProject, serverUrl: e.target.value })}
                    placeholder="http://sonarqube.example.com"
                    required
                    className="w-full px-4 py-3 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-500 focus:outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100 transition-all"
                  />
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                <div>
                  <label htmlFor="projectKey" className="block text-sm font-bold text-gray-900 mb-2 uppercase tracking-wide">
                    Project Key <span className="text-red-600">*</span>
                  </label>
                  <input
                    type="text"
                    id="projectKey"
                    value={newProject.projectKey}
                    onChange={(e) => setNewProject({ ...newProject, projectKey: e.target.value })}
                    placeholder="sonarqube-xamops"
                    required
                    className="w-full px-4 py-3 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-500 font-mono text-sm focus:outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100 transition-all"
                  />
                </div>
                <div>
                  <label htmlFor="token" className="block text-sm font-bold text-gray-900 mb-2 uppercase tracking-wide">
                    API Token <span className="text-red-600">*</span>
                  </label>
                  <input
                    type="password"
                    id="token"
                    value={newProject.token}
                    onChange={(e) => setNewProject({ ...newProject, token: e.target.value })}
                    placeholder="sqp_..."
                    required
                    className="w-full px-4 py-3 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-500 font-mono text-sm focus:outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100 transition-all"
                  />
                </div>
              </div>

              {modalError && (
                <div className="p-4 bg-red-50 border border-red-200 rounded-lg flex items-start gap-3">
                  <i className="fas fa-exclamation-circle text-red-600 flex-shrink-0 mt-0.5"></i>
                  <p className="text-sm text-red-700">{modalError}</p>
                </div>
              )}
            </form>
          </div>
        </DialogPanel>
      </DialogRoot>
    </div>
  );
};

export default SonarQubePage;
