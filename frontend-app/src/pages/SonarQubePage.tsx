import React, { useState, useCallback, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useSonarQube } from '../hooks/useSonarQube';
import { QualityGateStatus, Rating, NewSonarQubeProject } from '../types/sonarqube';
import { useTopbar } from '../contexts/TopbarContext';
import { AlertDialog, DialogRoot, DialogPanel } from '../components/ui';

// ─── Helpers ──────────────────────────────────────────────────────────────────

const NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '32px',
  lineHeight: 1,
  letterSpacing: '0.02em',
};

const inputCls = 'w-full px-3 py-2.5 border border-[rgba(0,0,0,0.15)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none bg-white placeholder-[rgba(0,0,0,0.3)]';
const labelCls = 'block text-[11px] font-semibold text-[rgba(0,0,0,0.55)] mb-1.5 uppercase tracking-[0.07em]';

function getRatingStyle(rating: Rating | undefined): { bg: string; text: string; border: string } {
  switch ((rating || '').toUpperCase()) {
    case 'A': return { bg: 'bg-emerald-50', text: 'text-emerald-700', border: 'border-emerald-200' };
    case 'B': return { bg: 'bg-blue-50',    text: 'text-blue-700',    border: 'border-blue-200' };
    case 'C': return { bg: 'bg-amber-50',   text: 'text-amber-700',   border: 'border-amber-200' };
    case 'D': return { bg: 'bg-orange-50',  text: 'text-orange-700',  border: 'border-orange-200' };
    default:  return { bg: 'bg-red-50',     text: 'text-red-700',     border: 'border-red-200' };
  }
}

function getGateStyle(status: QualityGateStatus | undefined) {
  const s = (status || '').toUpperCase();
  if (s === 'OK' || s === 'PASSED')   return { bg: 'bg-emerald-50', border: 'border-emerald-200', text: 'text-emerald-700', icon: 'mdi:check-circle-outline', label: 'Passed' };
  if (s === 'ERROR' || s === 'FAILED') return { bg: 'bg-red-50',     border: 'border-red-200',     text: 'text-red-700',     icon: 'mdi:close-circle-outline', label: 'Failed' };
  if (s === 'WARN')                    return { bg: 'bg-amber-50',   border: 'border-amber-200',   text: 'text-amber-700',   icon: 'mdi:alert-circle-outline', label: 'Warning' };
  return { bg: 'bg-[rgba(0,0,0,0.03)]', border: 'border-[rgba(0,0,0,0.08)]', text: 'text-[rgba(0,0,0,0.45)]', icon: 'mdi:help-circle-outline', label: 'Unknown' };
}

function formatTechDebt(minutes: number | undefined): string {
  if (!minutes || minutes === 0) return '0m';
  const days = Math.floor(minutes / 480);
  const rem = minutes % 480;
  const hours = Math.floor(rem / 60);
  const mins = rem % 60;
  let r = '';
  if (days > 0) r += `${days}d `;
  if (hours > 0) r += `${hours}h `;
  if (mins > 0) r += `${mins}m`;
  return r.trim();
}

function fmtNum(n: number | undefined) { return (n || 0).toLocaleString(); }

// ─── Progress Bar ─────────────────────────────────────────────────────────────

const Bar: React.FC<{ pct: number; color: string }> = ({ pct, color }) => (
  <div className="w-full h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
    <div className={`h-full ${color} rounded-full transition-all`} style={{ width: `${Math.min(pct, 100)}%` }} />
  </div>
);

// ─── Rating Badge ─────────────────────────────────────────────────────────────

const RatingBadge: React.FC<{ rating: Rating | undefined }> = ({ rating }) => {
  const s = getRatingStyle(rating);
  return (
    <span className={`inline-flex items-center justify-center w-9 h-9 rounded-lg text-[15px] font-black border ${s.bg} ${s.text} ${s.border}`}>
      {(rating || '?').toUpperCase()}
    </span>
  );
};

// ─── Metric Card ──────────────────────────────────────────────────────────────

const MetricCard: React.FC<{
  icon: string; iconColor: string; iconBg: string;
  title: string; rating?: Rating;
  children: React.ReactNode;
}> = ({ icon, iconColor, iconBg, title, rating, children }) => (
  <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-5 shadow-sm">
    <div className="flex items-center justify-between mb-4">
      <div className="flex items-center gap-3">
        <div className={`w-10 h-10 ${iconBg} border rounded-lg flex items-center justify-center`}>
          <Icon icon={icon} width={18} color={iconColor} />
        </div>
        <h4 className="font-bold text-[rgba(0,0,0,0.88)] text-[14px]">{title}</h4>
      </div>
      {rating !== undefined && <RatingBadge rating={rating} />}
    </div>
    {children}
  </div>
);

// ─── Page ─────────────────────────────────────────────────────────────────────

const SonarQubePage: React.FC = () => {
  const {
    projects, selectedProjectId, metrics,
    isLoading, isRefreshing, error, modalError,
    setSelectedProjectId, refreshMetrics, addProject, deleteProject,
  } = useSonarQube();

  const [isManageModalOpen, setIsManageModalOpen]     = useState(false);
  const [isSaving, setIsSaving]                       = useState(false);
  const [pendingDeleteId, setPendingDeleteId]         = useState<number | null>(null);
  const [newProject, setNewProject]                   = useState<NewSonarQubeProject>({ projectName: '', serverUrl: '', projectKey: '', token: '' });

  const getProjectUrl = useCallback(() => {
    if (!selectedProjectId) return '#';
    const p = projects.find(p => p.id.toString() === selectedProjectId);
    return p ? `${p.serverUrl}/dashboard?id=${p.projectKey}` : '#';
  }, [selectedProjectId, projects]);

  const getOverallHealth = useCallback(() => {
    if (!metrics) return null;
    const ratings = [metrics.reliabilityRating, metrics.securityRating, metrics.maintainabilityRating, metrics.securityReviewRating];
    const scores: Record<string, number> = { A: 5, B: 4, C: 3, D: 2, E: 1 };
    const valid = ratings.filter(Boolean) as string[];
    if (valid.length === 0) return null;
    const avg = valid.reduce((s, r) => s + (scores[r.toUpperCase()] || 1), 0) / valid.length;
    if (avg >= 4.5) return { label: 'Excellent', color: 'text-emerald-600', bg: 'bg-emerald-50 border-emerald-100' };
    if (avg >= 3.5) return { label: 'Good',      color: 'text-blue-600',    bg: 'bg-blue-50 border-blue-100' };
    if (avg >= 2.5) return { label: 'Fair',       color: 'text-amber-600',   bg: 'bg-amber-50 border-amber-100' };
    return              { label: 'Poor',           color: 'text-red-600',     bg: 'bg-red-50 border-red-100' };
  }, [metrics]);

  const handleAddProject = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setIsSaving(true);
    try {
      await addProject(newProject);
      setNewProject({ projectName: '', serverUrl: '', projectKey: '', token: '' });
      setIsManageModalOpen(false);
    } catch {}
    finally { setIsSaving(false); }
  }, [newProject, addProject]);

  const confirmDelete = useCallback(async () => {
    if (pendingDeleteId === null) return;
    const id = pendingDeleteId;
    setPendingDeleteId(null);
    try { await deleteProject(id); } catch {}
  }, [pendingDeleteId, deleteProject]);

  // Topbar
  const { setActions, clearActions } = useTopbar();
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-2 h-2 rounded-full bg-amber-400" />
        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.65)]">SonarQube</span>
        <span className="text-[rgba(0,0,0,0.2)]">|</span>
        <button
          onClick={refreshMetrics}
          disabled={isRefreshing || !selectedProjectId}
          className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.04)] rounded-lg transition-colors disabled:opacity-50"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className={isRefreshing ? 'animate-spin' : ''}>
            <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
          </svg>
          Refresh
        </button>
        <span className="text-[rgba(0,0,0,0.2)]">|</span>
        <button
          onClick={() => setIsManageModalOpen(true)}
          className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.04)] rounded-lg transition-colors"
        >
          <Icon icon="mdi:cog-outline" width={13} />
          Manage Projects
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, selectedProjectId, refreshMetrics]);

  const gateStyle = getGateStyle(metrics?.qualityGateStatus);
  const health    = getOverallHealth();
  const coverage  = metrics?.coverage || 0;
  const coverageColor = coverage >= 80 ? 'bg-emerald-500' : coverage >= 50 ? 'bg-amber-500' : 'bg-red-500';

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">

      <AlertDialog
        isOpen={pendingDeleteId !== null}
        title="Remove project configuration?"
        description="This will remove the SonarQube project from XamOps."
        confirmLabel="Remove"
        variant="danger"
        onConfirm={confirmDelete}
        onCancel={() => setPendingDeleteId(null)}
      />

      {/* Project Selector */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-5 shadow-sm">
        <label htmlFor="projectSelect" className={`${labelCls} flex items-center gap-1.5 mb-2`}>
          <Icon icon="mdi:folder-open-outline" width={12} color="#6366F1" />
          Select Project
        </label>
        <select
          id="projectSelect"
          value={selectedProjectId}
          onChange={e => setSelectedProjectId(e.target.value)}
          className="w-full px-3 py-2.5 bg-white border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] font-medium focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all cursor-pointer"
        >
          <option value="">— Select a Project —</option>
          {projects.map(p => (
            <option key={p.id} value={p.id}>{p.projectName}</option>
          ))}
        </select>
      </div>

      {/* Error */}
      {error && (
        <div className="bg-white border border-red-200 rounded-xl p-4 shadow-sm flex items-start gap-3">
          <Icon icon="mdi:alert-circle-outline" width={16} color="#DC2626" className="mt-0.5 flex-shrink-0" />
          <div>
            <h4 className="font-bold text-red-800 text-[13px]">Error</h4>
            <p className="text-[12px] text-red-700 mt-0.5">{error}</p>
          </div>
        </div>
      )}

      {/* Loading */}
      {isLoading && (
        <div className="space-y-5">
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl h-36 animate-pulse" />
            <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.08)] rounded-xl h-36 animate-pulse" />
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {[...Array(6)].map((_, i) => (
              <div key={i} className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl h-48 animate-pulse" />
            ))}
          </div>
        </div>
      )}

      {/* No projects */}
      {!isLoading && projects.length === 0 && (
        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl py-16 shadow-sm text-center">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.06)] rounded-xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:inbox-outline" width={26} color="rgba(0,0,0,0.2)" />
          </div>
          <h3 className="text-[17px] font-bold text-[rgba(0,0,0,0.88)]">No Projects Found</h3>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mt-1 max-w-xs mx-auto">Add your first SonarQube project to start analyzing code quality</p>
          <button onClick={() => setIsManageModalOpen(true)}
            className="mt-6 inline-flex items-center gap-2 px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-xl transition-colors">
            <Icon icon="mdi:plus" width={14} />
            Add Project
          </button>
        </div>
      )}

      {/* No project selected */}
      {!isLoading && projects.length > 0 && !selectedProjectId && (
        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl py-16 shadow-sm text-center">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.06)] rounded-xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:cursor-pointer" width={26} color="rgba(0,0,0,0.2)" />
          </div>
          <h3 className="text-[17px] font-bold text-[rgba(0,0,0,0.88)]">Select a Project</h3>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mt-1">Choose a project above to view detailed quality metrics</p>
        </div>
      )}

      {/* Metrics */}
      {!isLoading && selectedProjectId && metrics && (
        <div className="space-y-5">

          {/* Summary strip */}
          <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl px-5 py-3 shadow-sm">
            <div className="flex flex-wrap items-center gap-2">
              {health && (
                <span className={`inline-flex items-center gap-1.5 px-3 py-1.5 ${health.bg} border rounded-full text-[12px] font-bold ${health.color}`}>
                  <Icon icon="mdi:heart-pulse" width={13} />
                  {health.label}
                </span>
              )}
              {[
                { icon: 'mdi:bug-outline',         val: `${metrics.bugs || 0} Bug${(metrics.bugs || 0) !== 1 ? 's' : ''}`,        ok: (metrics.bugs || 0) === 0 },
                { icon: 'mdi:shield-alert-outline', val: `${metrics.vulnerabilities || 0} Vuln${(metrics.vulnerabilities || 0) !== 1 ? 's' : ''}`, ok: (metrics.vulnerabilities || 0) === 0 },
                { icon: 'mdi:crosshairs',           val: `${coverage}% Coverage`, ok: coverage >= 80 },
                { icon: 'mdi:clock-outline',        val: `${formatTechDebt(metrics.techDebt)} Debt`, ok: null },
                { icon: 'mdi:smoke-detector-variant-outline', val: `${fmtNum(metrics.codeSmells)} Smell${(metrics.codeSmells || 0) !== 1 ? 's' : ''}`, ok: (metrics.codeSmells || 0) === 0 },
              ].map(item => (
                <span key={item.val}
                  className={`inline-flex items-center gap-1.5 px-3 py-1.5 border rounded-full text-[12px] font-semibold ${
                    item.ok === null ? 'bg-[rgba(0,0,0,0.03)] border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.55)]'
                    : item.ok ? 'bg-emerald-50 border-emerald-100 text-emerald-700'
                    : 'bg-red-50 border-red-100 text-red-700'
                  }`}>
                  <Icon icon={item.icon} width={12} />
                  {item.val}
                </span>
              ))}
            </div>
          </div>

          {/* Quality Gate + LOC */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div className={`bg-white border ${gateStyle.border} rounded-xl p-5 shadow-sm`}>
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-2">
                  <Icon icon="mdi:shield-check-outline" width={16} color="#6366F1" />
                  <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Quality Gate</h3>
                </div>
                <a href={getProjectUrl()} target="_blank" rel="noopener noreferrer"
                  className="flex items-center gap-1 text-[11px] font-semibold text-indigo-600 hover:text-indigo-800 transition-colors">
                  View <Icon icon="mdi:open-in-new" width={11} />
                </a>
              </div>
              <div className={`flex items-center gap-3 p-3 ${gateStyle.bg} border ${gateStyle.border} rounded-xl`}>
                <Icon icon={gateStyle.icon} width={28} color={gateStyle.text.replace('text-', '').replace('-700', '')} />
                <div>
                  <p style={{ ...NUM_STYLE, fontSize: '26px' }} className={gateStyle.text}>{gateStyle.label}</p>
                  <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider mt-0.5">Status</p>
                </div>
              </div>
            </div>

            <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-5 shadow-sm">
              <div className="flex items-center gap-2 mb-4">
                <Icon icon="mdi:code-tags" width={16} color="#6366F1" />
                <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Lines of Code</h3>
              </div>
              <div className="flex items-baseline gap-3">
                <p style={NUM_STYLE} className="text-[rgba(0,0,0,0.88)] text-[40px]">{fmtNum(metrics.linesOfCode)}</p>
                <span className="text-[rgba(0,0,0,0.4)] font-semibold text-[13px] tracking-wide">total LOC</span>
              </div>
              <p className="text-[12px] text-[rgba(0,0,0,0.4)] mt-3 flex items-center gap-1.5">
                <Icon icon="mdi:information-outline" width={12} />
                Comprehensive codebase analysis
              </p>
            </div>
          </div>

          {/* Detailed Metrics Grid */}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">

            {/* Reliability */}
            <MetricCard icon="mdi:bug-outline" iconColor="#EA580C" iconBg="bg-orange-50 border-orange-100" title="Reliability" rating={metrics.reliabilityRating}>
              <div>
                <div className="flex justify-between items-center mb-2">
                  <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">Bugs</span>
                  <span style={NUM_STYLE} className={(metrics.bugs || 0) === 0 ? 'text-emerald-600' : 'text-red-600'}>
                    {metrics.bugs || 0}
                  </span>
                </div>
                <Bar pct={(metrics.bugs || 0) * 3} color={(metrics.bugs || 0) === 0 ? 'bg-emerald-500' : 'bg-red-500'} />
              </div>
            </MetricCard>

            {/* Security */}
            <MetricCard icon="mdi:lock-outline" iconColor="#DC2626" iconBg="bg-red-50 border-red-100" title="Security" rating={metrics.securityRating}>
              <div>
                <div className="flex justify-between items-center mb-2">
                  <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">Vulnerabilities</span>
                  <span style={NUM_STYLE} className={(metrics.vulnerabilities || 0) === 0 ? 'text-emerald-600' : 'text-red-600'}>
                    {metrics.vulnerabilities || 0}
                  </span>
                </div>
                <Bar pct={(metrics.vulnerabilities || 0) * 4} color={(metrics.vulnerabilities || 0) === 0 ? 'bg-emerald-500' : 'bg-red-500'} />
              </div>
            </MetricCard>

            {/* Hotspots */}
            <MetricCard icon="mdi:alert-outline" iconColor="#D97706" iconBg="bg-amber-50 border-amber-100" title="Hotspots" rating={metrics.securityReviewRating}>
              <div>
                <div className="flex justify-between items-center mb-2">
                  <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">To Review</span>
                  <span style={NUM_STYLE} className={(metrics.securityHotspots || 0) === 0 ? 'text-emerald-600' : 'text-amber-600'}>
                    {metrics.securityHotspots || 0}
                  </span>
                </div>
                <Bar pct={(metrics.securityHotspots || 0) * 2} color={(metrics.securityHotspots || 0) === 0 ? 'bg-emerald-500' : 'bg-amber-500'} />
              </div>
            </MetricCard>

            {/* Maintainability */}
            <MetricCard icon="mdi:wrench-outline" iconColor="#6366F1" iconBg="bg-indigo-50 border-indigo-100" title="Maintainability" rating={metrics.maintainabilityRating}>
              <div className="space-y-3">
                <div>
                  <div className="flex justify-between items-center mb-2">
                    <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">Code Smells</span>
                    <span style={NUM_STYLE} className={(metrics.codeSmells || 0) === 0 ? 'text-emerald-600' : 'text-[rgba(0,0,0,0.65)]'}>
                      {fmtNum(metrics.codeSmells)}
                    </span>
                  </div>
                  <Bar pct={(metrics.codeSmells || 0) * 0.5} color={(metrics.codeSmells || 0) === 0 ? 'bg-emerald-500' : 'bg-indigo-500'} />
                </div>
                <div className="pt-3 border-t border-[rgba(0,0,0,0.06)]">
                  <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider mb-1">Technical Debt</p>
                  <p style={NUM_STYLE} className="text-[rgba(0,0,0,0.75)]">{formatTechDebt(metrics.techDebt)}</p>
                </div>
              </div>
            </MetricCard>

            {/* Test Coverage */}
            <MetricCard icon="mdi:crosshairs" iconColor="#3B82F6" iconBg="bg-blue-50 border-blue-100" title="Test Coverage">
              <div className="flex items-center gap-5">
                {/* SVG ring */}
                <div className="relative flex-shrink-0 w-20 h-20">
                  <svg viewBox="0 0 36 36" className="w-full h-full -rotate-90">
                    <path d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
                      fill="none" stroke="rgba(0,0,0,0.06)" strokeWidth="3" />
                    <path d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
                      fill="none"
                      stroke={coverage >= 80 ? '#10B981' : coverage >= 50 ? '#F59E0B' : '#EF4444'}
                      strokeWidth="3"
                      strokeDasharray={`${coverage}, 100`}
                      strokeLinecap="round"
                    />
                  </svg>
                  <div className="absolute inset-0 flex flex-col items-center justify-center">
                    <span className="text-[16px] font-black text-[rgba(0,0,0,0.88)]">{coverage}</span>
                    <span className="text-[9px] font-bold text-[rgba(0,0,0,0.4)]">%</span>
                  </div>
                </div>
                <div className="flex-1 space-y-2">
                  <div>
                    <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider mb-1">Lines to Cover</p>
                    <p style={NUM_STYLE} className="text-[rgba(0,0,0,0.75)]">{fmtNum(metrics.linesToCover)}</p>
                  </div>
                  <Bar pct={coverage} color={coverageColor} />
                </div>
              </div>
            </MetricCard>

            {/* Duplications */}
            <MetricCard icon="mdi:content-copy" iconColor="#EC4899" iconBg="bg-pink-50 border-pink-100" title="Duplications">
              <div className="space-y-3">
                <div>
                  {(() => {
                    const dup = metrics.duplicationDensity || 0;
                    const dupColor = dup === 0 ? 'text-emerald-600' : dup <= 5 ? 'text-amber-600' : 'text-red-600';
                    const barColor = dup === 0 ? 'bg-emerald-500' : dup <= 5 ? 'bg-amber-500' : 'bg-red-500';
                    return (
                      <>
                        <div className="flex justify-between items-center mb-2">
                          <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">Density</span>
                          <span style={NUM_STYLE} className={dupColor}>{dup}%</span>
                        </div>
                        <Bar pct={dup} color={barColor} />
                      </>
                    );
                  })()}
                </div>
                <div className="pt-3 border-t border-[rgba(0,0,0,0.06)]">
                  <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider mb-1">Duplicated Lines</p>
                  <p style={NUM_STYLE} className="text-[rgba(0,0,0,0.75)]">{fmtNum(metrics.duplicatedLines)}</p>
                </div>
              </div>
            </MetricCard>

          </div>
        </div>
      )}

      {/* Manage Projects Modal */}
      <DialogRoot isOpen={isManageModalOpen} onClose={() => setIsManageModalOpen(false)} maxWidth="max-w-2xl">
        <DialogPanel
          title={
            <div className="flex items-center gap-2">
              <Icon icon="mdi:cog-outline" width={16} color="#6366F1" />
              <span className="text-[15px] font-bold text-[rgba(0,0,0,0.88)]">Manage Projects</span>
            </div>
          }
          onClose={() => setIsManageModalOpen(false)}
          footer={
            <>
              <button type="button" onClick={() => setIsManageModalOpen(false)}
                className="px-5 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                Cancel
              </button>
              <button type="submit" form="add-project-form" disabled={isSaving}
                className="flex items-center gap-2 px-5 py-2.5 text-[13px] font-bold text-white bg-indigo-600 hover:bg-indigo-700 rounded-xl disabled:opacity-50 transition-colors">
                {isSaving
                  ? <><Icon icon="mdi:loading" width={14} className="animate-spin" /> Saving…</>
                  : <><Icon icon="mdi:plus" width={14} /> Add Project</>}
              </button>
            </>
          }
        >
          <div className="overflow-y-auto max-h-[60vh] space-y-5">
            {/* Configured Projects */}
            <div className="border-b border-[rgba(0,0,0,0.08)] pb-5">
              <h3 className="text-[13px] font-bold text-[rgba(0,0,0,0.75)] mb-3 flex items-center gap-2">
                <Icon icon="mdi:format-list-bulleted" width={14} color="#6366F1" />
                Configured Projects
              </h3>
              <div className="max-h-48 overflow-y-auto space-y-2">
                {projects.length === 0 ? (
                  <div className="text-center py-8">
                    <Icon icon="mdi:inbox-outline" width={32} color="rgba(0,0,0,0.2)" className="mx-auto mb-2" />
                    <p className="text-[13px] text-[rgba(0,0,0,0.45)] font-medium">No projects yet</p>
                  </div>
                ) : (
                  projects.map(project => (
                    <div key={project.id}
                      className="flex justify-between items-center p-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl hover:bg-[rgba(0,0,0,0.03)] transition-all">
                      <div className="flex-1 min-w-0">
                        <p className="font-bold text-[rgba(0,0,0,0.88)] text-[13px] truncate">{project.projectName}</p>
                        <p className="text-[11px] text-[rgba(0,0,0,0.4)] font-mono mt-0.5">{project.projectKey}</p>
                      </div>
                      <button onClick={() => setPendingDeleteId(project.id)}
                        className="ml-3 flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold text-red-600 bg-red-50 border border-red-100 rounded-lg hover:bg-red-100 transition-all">
                        <Icon icon="mdi:trash-can-outline" width={12} />
                        Delete
                      </button>
                    </div>
                  ))
                )}
              </div>
            </div>

            {/* Add Form */}
            <form id="add-project-form" onSubmit={handleAddProject} className="space-y-4">
              <h3 className="text-[13px] font-bold text-[rgba(0,0,0,0.75)] flex items-center gap-2">
                <Icon icon="mdi:plus-circle-outline" width={14} color="#16A34A" />
                Add New Project
              </h3>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label htmlFor="projectName" className={labelCls}>Project Name <span className="text-red-500">*</span></label>
                  <input type="text" id="projectName" value={newProject.projectName} required placeholder="e.g., XamOps Backend"
                    onChange={e => setNewProject({ ...newProject, projectName: e.target.value })}
                    className={inputCls} />
                </div>
                <div>
                  <label htmlFor="serverUrl" className={labelCls}>Server URL <span className="text-red-500">*</span></label>
                  <input type="url" id="serverUrl" value={newProject.serverUrl} required placeholder="http://sonarqube.example.com"
                    onChange={e => setNewProject({ ...newProject, serverUrl: e.target.value })}
                    className={inputCls} />
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label htmlFor="projectKey" className={labelCls}>Project Key <span className="text-red-500">*</span></label>
                  <input type="text" id="projectKey" value={newProject.projectKey} required placeholder="sonarqube-xamops"
                    onChange={e => setNewProject({ ...newProject, projectKey: e.target.value })}
                    className={`${inputCls} font-mono`} />
                </div>
                <div>
                  <label htmlFor="token" className={labelCls}>API Token <span className="text-red-500">*</span></label>
                  <input type="password" id="token" value={newProject.token} required placeholder="sqp_..."
                    onChange={e => setNewProject({ ...newProject, token: e.target.value })}
                    className={`${inputCls} font-mono`} />
                </div>
              </div>

              {modalError && (
                <div className="p-3 bg-red-50 border border-red-200 rounded-xl flex items-start gap-2">
                  <Icon icon="mdi:alert-circle-outline" width={14} color="#DC2626" className="mt-0.5 flex-shrink-0" />
                  <p className="text-[12px] text-red-700">{modalError}</p>
                </div>
              )}
            </form>
          </div>
        </DialogPanel>
      </DialogRoot>

    </main>
  );
};

export default SonarQubePage;
