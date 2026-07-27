import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { useParams, Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { useTopbar } from '../contexts/TopbarContext';
import { useSoc2ProgramData } from '../hooks/useSoc2ProgramData';
import {
  publishSoc2Policy, setSoc2PolicyArchived,
  updateSoc2Task, createSoc2Task,
  createSoc2Vendor, updateSoc2Vendor, deleteSoc2Vendor,
  createSoc2Risk, updateSoc2Risk, deleteSoc2Risk,
  deleteSoc2Control,
  inviteSoc2Member, updateSoc2Person, deleteSoc2Person,
} from '../api/soc2Api';
import type {
  Soc2ControlStatus, Soc2Control, Soc2Policy, Soc2Task, Soc2Person,
  Soc2Vendor, Soc2Risk, Soc2Integration, Soc2CloudTest,
  CreateVendorRequest, UpdateVendorRequest, CreateRiskRequest, UpdateRiskRequest,
  UpdateTaskRequest, CreateTaskRequest, UpdatePersonRequest, InviteMemberRequest,
} from '../api/soc2Api';

type SelectedRow =
  | { type: 'control'; data: Soc2Control }
  | { type: 'policy'; data: Soc2Policy }
  | { type: 'task'; data: Soc2Task }
  | { type: 'person'; data: Soc2Person }
  | { type: 'vendor'; data: Soc2Vendor }
  | { type: 'risk'; data: Soc2Risk }
  | { type: 'integration'; data: Soc2Integration }
  | { type: 'cloudtest'; data: Soc2CloudTest }
  | null;

const CONTROL_STATUS_LABEL: Record<Soc2ControlStatus, string> = {
  PASSING: 'Passing',
  IN_PROGRESS: 'In Progress',
  NOT_STARTED: 'Not Started',
};

const TASK_STATUS_OPTIONS = ['todo', 'in_progress', 'in_review', 'done', 'not_relevant', 'failed'];
const VENDOR_CATEGORY_OPTIONS = ['cloud', 'infrastructure', 'software_as_a_service', 'finance', 'marketing', 'sales', 'hr', 'other'];
const VENDOR_STATUS_OPTIONS = ['not_assessed', 'in_progress', 'assessed'];
const RISK_CATEGORY_OPTIONS = ['customer', 'fraud', 'governance', 'operations', 'other', 'people', 'regulatory', 'reporting', 'resilience', 'technology', 'vendor_management'];
const DEPARTMENT_OPTIONS = ['none', 'admin', 'gov', 'hr', 'it', 'itsm', 'qms'];
const MEMBER_ROLE_OPTIONS = ['admin', 'employee', 'auditor'];

type Tone = 'success' | 'warning' | 'danger' | 'neutral';

const TONE_CLASSES: Record<Tone, string> = {
  success: 'bg-emerald-50 text-emerald-700 border border-emerald-200',
  warning: 'bg-amber-50 text-amber-700 border border-amber-200',
  danger: 'bg-red-50 text-red-700 border border-red-200',
  neutral: 'bg-gray-50 text-gray-500 border border-gray-200',
};

const controlStatusClass = (status: Soc2ControlStatus): string => {
  if (status === 'PASSING') return TONE_CLASSES.success;
  if (status === 'IN_PROGRESS') return TONE_CLASSES.warning;
  return TONE_CLASSES.neutral;
};

const StatusPill: React.FC<{ text: string; tone?: Tone }> = ({ text, tone = 'neutral' }) => (
  <span className={`px-2 py-0.5 rounded-full text-[11px] font-medium capitalize ${TONE_CLASSES[tone]}`}>{text || '—'}</span>
);

const policyTone = (status: string | null): Tone => {
  const s = (status || '').toLowerCase();
  if (s === 'published') return 'success';
  if (s === 'needs_review' || s === 'in_review' || s === 'draft') return 'warning';
  return 'neutral';
};

const severityTone = (severity: string | null): Tone => {
  const s = (severity || '').toLowerCase();
  if (s === 'critical' || s === 'high') return 'danger';
  if (s === 'medium') return 'warning';
  if (s === 'low') return 'success';
  return 'neutral';
};

const vendorStatusTone = (status: string | null): Tone => {
  const s = (status || '').toLowerCase();
  if (s === 'assessed') return 'success';
  if (s === 'in_progress') return 'warning';
  return 'neutral';
};

const riskStatusTone = (status: string | null): Tone => {
  const s = (status || '').toLowerCase();
  if (s.includes('mitigat') || s.includes('closed') || s.includes('resolved') || s.includes('accept')) return 'success';
  if (s.includes('open') || s.includes('progress')) return 'warning';
  return 'neutral';
};

const findingStatusTone = (status: string | null): Tone => {
  const s = (status || '').toLowerCase();
  if (s.includes('pass') || s.includes('resolved') || s.includes('closed')) return 'success';
  if (s.includes('fail') || s.includes('open')) return 'danger';
  return 'neutral';
};

// SOC 2 Trust Service Criteria series: CC (Security/Common Criteria, the majority)
// stays neutral so it doesn't dominate; the optional criteria each get their own color.
const CATEGORY_CLASSES: Record<string, string> = {
  CC: 'bg-gray-50 text-gray-600 border border-gray-200',
  A: 'bg-emerald-50 text-emerald-700 border border-emerald-200',
  C: 'bg-amber-50 text-amber-700 border border-amber-200',
  P: 'bg-orange-50 text-orange-700 border border-orange-200',
  PI: 'bg-rose-50 text-rose-700 border border-rose-200',
};

const categorySeries = (category: string): string => {
  if (category.startsWith('PI')) return 'PI';
  const letters = category.match(/^[A-Za-z]+/)?.[0] ?? '';
  return letters || category;
};

const CategoryBadge: React.FC<{ category: string }> = ({ category }) => (
  <span className={`inline-block px-2 py-0.5 rounded-full text-[11px] font-medium ${CATEGORY_CLASSES[categorySeries(category)] ?? CATEGORY_CLASSES.CC}`}>
    {category}
  </span>
);

const Chip: React.FC<{ text: string }> = ({ text }) => (
  <span className="inline-block px-2 py-0.5 rounded-full text-[11px] font-medium bg-gray-100 text-gray-600 capitalize">
    {text}
  </span>
);

const OwnerBadge: React.FC<{ name: string | null }> = ({ name }) => {
  if (!name) return <span className="text-[12px] text-gray-400 italic">Unassigned</span>;
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className="w-5 h-5 rounded-full bg-gray-200 text-gray-700 text-[10px] font-semibold flex items-center justify-center shrink-0">
        {name.trim().charAt(0).toUpperCase() || '?'}
      </span>
      <span className="text-[12px] text-gray-700">{name}</span>
    </span>
  );
};

const ICON_TONE_BG: Record<Tone, string> = {
  success: 'bg-emerald-50 text-emerald-600',
  warning: 'bg-amber-50 text-amber-600',
  danger: 'bg-red-50 text-red-600',
  neutral: 'bg-gray-100 text-gray-500',
};

const StatCard: React.FC<{ label: string; value: string | number; sub?: string; icon: string; tone?: Tone; to?: string }> =
  ({ label, value, sub, icon, tone = 'neutral', to }) => {
    const inner = (
      <div className="border border-[rgba(0,0,0,0.10)] rounded-xl p-4 bg-white h-full flex items-start gap-3 transition-shadow hover:shadow-sm">
        <div className={`w-9 h-9 rounded-lg flex items-center justify-center shrink-0 ${ICON_TONE_BG[tone]}`}>
          <Icon icon={icon} width={18} />
        </div>
        <div className="min-w-0">
          <div className="text-[12px] text-gray-500">{label}</div>
          <div className="text-2xl font-semibold text-gray-900 mt-0.5">{value}</div>
          {sub && <div className="text-[11px] text-gray-400 mt-0.5">{sub}</div>}
        </div>
      </div>
    );
    return to ? <Link to={to} className="block h-full">{inner}</Link> : inner;
  };

const readinessColor = (percent: number): string =>
  percent >= 80 ? '#059669' : percent >= 50 ? '#d97706' : '#dc2626';

const ReadinessRing: React.FC<{ percent: number }> = ({ percent }) => {
  const size = 128;
  const stroke = 10;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (Math.max(0, Math.min(100, percent)) / 100) * circumference;
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={radius} stroke="#e5e7eb" strokeWidth={stroke} fill="none" />
        <circle
          cx={size / 2} cy={size / 2} r={radius} stroke={readinessColor(percent)} strokeWidth={stroke} fill="none"
          strokeDasharray={circumference} strokeDashoffset={offset} strokeLinecap="round"
          style={{ transition: 'stroke-dashoffset 0.4s ease' }}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="text-2xl font-bold text-gray-900">{percent}%</span>
        <span className="text-[10px] text-gray-400 uppercase tracking-wide">Ready</span>
      </div>
    </div>
  );
};

const ControlsBreakdownBar: React.FC<{ passing: number; inProgress: number; notStarted: number }> = ({ passing, inProgress, notStarted }) => {
  const total = passing + inProgress + notStarted || 1;
  return (
    <div className="w-full h-2.5 rounded-full overflow-hidden flex bg-gray-100">
      {passing > 0 && <div style={{ width: `${(passing / total) * 100}%` }} className="bg-emerald-500" />}
      {inProgress > 0 && <div style={{ width: `${(inProgress / total) * 100}%` }} className="bg-amber-400" />}
      {notStarted > 0 && <div style={{ width: `${(notStarted / total) * 100}%` }} className="bg-gray-300" />}
    </div>
  );
};

const Legend: React.FC<{ color: string; label: string; value: number }> = ({ color, label, value }) => (
  <div className="flex items-center gap-1.5 text-[12px] text-gray-600">
    <span className={`w-2 h-2 rounded-full shrink-0 ${color}`} />
    {label} <span className="font-semibold text-gray-900">{value}</span>
  </div>
);

const Th: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <th className="p-3 text-left text-[11px] font-semibold uppercase tracking-wide text-gray-500">{children}</th>
);

const ActionButton: React.FC<{ onClick: (e: React.MouseEvent) => void; disabled?: boolean; children: React.ReactNode }> = ({ onClick, disabled, children }) => (
  <button
    onClick={onClick}
    disabled={disabled}
    className="px-2.5 py-1 text-[11px] font-medium rounded-md bg-[#1a1a1a] text-white hover:bg-black disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
  >
    {children}
  </button>
);

const SecondaryButton: React.FC<{ onClick: (e: React.MouseEvent) => void; disabled?: boolean; children: React.ReactNode }> = ({ onClick, disabled, children }) => (
  <button
    onClick={onClick}
    disabled={disabled}
    className="px-2.5 py-1 text-[11px] font-medium rounded-md border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
  >
    {children}
  </button>
);

const DangerButton: React.FC<{ onClick: (e: React.MouseEvent) => void; disabled?: boolean; children: React.ReactNode }> = ({ onClick, disabled, children }) => (
  <button
    onClick={onClick}
    disabled={disabled}
    className="px-2.5 py-1 text-[11px] font-medium rounded-md border border-red-200 bg-white text-red-600 hover:bg-red-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
  >
    {children}
  </button>
);

const Soc2ProgramPage: React.FC = () => {
  const { tab = 'overview' } = useParams<{ tab: string }>();
  const { setTitle, clearActions } = useTopbar();
  const { overview, controls, policies, tasks, personnel, vendors, risks, cloudTests, integrations, isLoading, error, refetch } = useSoc2ProgramData();

  const [publishingId, setPublishingId] = useState<string | null>(null);
  const [updatingTaskId, setUpdatingTaskId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [vendorModalOpen, setVendorModalOpen] = useState(false);
  const [riskModalOpen, setRiskModalOpen] = useState(false);
  const [taskModalOpen, setTaskModalOpen] = useState(false);
  const [inviteModalOpen, setInviteModalOpen] = useState(false);
  const [selected, setSelected] = useState<SelectedRow>(null);

  useEffect(() => {
    setTitle('Program');
    return () => { clearActions(); setTitle(null); };
  }, [setTitle, clearActions]);

  const handlePublish = async (policyId: string) => {
    setActionError(null);
    setPublishingId(policyId);
    try {
      await publishSoc2Policy(policyId);
      await refetch();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Failed to publish policy');
    } finally {
      setPublishingId(null);
    }
  };

  const handleArchivePolicy = async (policyId: string, archived: boolean) => {
    setActionError(null);
    try {
      await setSoc2PolicyArchived(policyId, archived);
      await refetch();
      setSelected(null);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Failed to update policy');
    }
  };

  const handleTaskStatusChange = async (taskId: string, status: string) => {
    setActionError(null);
    setUpdatingTaskId(taskId);
    try {
      await updateSoc2Task(taskId, { status });
      await refetch();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Failed to update task');
    } finally {
      setUpdatingTaskId(null);
    }
  };

  const handleSaveTask = async (taskId: string, payload: UpdateTaskRequest) => {
    await updateSoc2Task(taskId, payload);
    await refetch();
    setSelected(null);
  };

  const handleDeleteControl = async (controlId: string) => {
    if (!window.confirm('Delete this control? This cannot be undone.')) return;
    setActionError(null);
    try {
      await deleteSoc2Control(controlId);
      await refetch();
      setSelected(null);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Failed to delete control');
    }
  };

  const handleCreateVendor = async (payload: CreateVendorRequest) => {
    await createSoc2Vendor(payload);
    await refetch();
    setVendorModalOpen(false);
  };

  const handleSaveVendor = async (vendorId: string, payload: UpdateVendorRequest) => {
    await updateSoc2Vendor(vendorId, payload);
    await refetch();
    setSelected(null);
  };

  const handleDeleteVendor = async (vendorId: string) => {
    if (!window.confirm('Delete this vendor? This cannot be undone.')) return;
    setActionError(null);
    try {
      await deleteSoc2Vendor(vendorId);
      await refetch();
      setSelected(null);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Failed to delete vendor');
    }
  };

  const handleCreateRisk = async (payload: CreateRiskRequest) => {
    await createSoc2Risk(payload);
    await refetch();
    setRiskModalOpen(false);
  };

  const handleSaveRisk = async (riskId: string, payload: UpdateRiskRequest) => {
    await updateSoc2Risk(riskId, payload);
    await refetch();
    setSelected(null);
  };

  const handleDeleteRisk = async (riskId: string) => {
    if (!window.confirm('Delete this risk? This cannot be undone.')) return;
    setActionError(null);
    try {
      await deleteSoc2Risk(riskId);
      await refetch();
      setSelected(null);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Failed to delete risk');
    }
  };

  const handleCreateTask = async (payload: CreateTaskRequest) => {
    await createSoc2Task(payload);
    await refetch();
    setTaskModalOpen(false);
  };

  const handleInviteMember = async (payload: InviteMemberRequest) => {
    await inviteSoc2Member(payload);
    await refetch();
    setInviteModalOpen(false);
  };

  const handleSavePerson = async (memberId: string, payload: UpdatePersonRequest) => {
    await updateSoc2Person(memberId, payload);
    await refetch();
    setSelected(null);
  };

  const handleDeletePerson = async (memberId: string) => {
    if (!window.confirm('Remove this person from the organization? This cannot be undone.')) return;
    setActionError(null);
    try {
      await deleteSoc2Person(memberId);
      await refetch();
      setSelected(null);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Failed to remove person');
    }
  };

  if (isLoading) {
    return <div className="p-6 text-gray-500 text-sm">Loading SOC 2 program…</div>;
  }
  if (error) {
    return <div className="p-6 text-sm text-red-600">Failed to load SOC 2 program data: {error.message}</div>;
  }

  return (
    <div className="p-6">
      {actionError && (
        <div className="mb-4 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">
          {actionError}
        </div>
      )}

      {tab === 'overview' && overview && (
        <div className="space-y-6">
          <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-6 flex flex-col md:flex-row items-center md:items-stretch gap-6">
            <div className="flex flex-col items-center justify-center shrink-0">
              <ReadinessRing percent={overview.readinessPercent} />
              <div className="text-center mt-2">
                <div className="text-[11px] font-semibold text-gray-700">{overview.frameworkName}</div>
                <div className="text-[11px] text-gray-400">Type I & II</div>
              </div>
            </div>
            <div className="flex-1 flex flex-col justify-center gap-3 min-w-0 w-full">
              <div className="flex items-center justify-between">
                <span className="text-[13px] font-semibold text-gray-900">Controls</span>
                <span className="text-[12px] text-gray-400">{overview.controlsTotal} total</span>
              </div>
              <ControlsBreakdownBar
                passing={overview.controlsPassing}
                inProgress={overview.controlsInProgress}
                notStarted={overview.controlsNotStarted}
              />
              <div className="flex items-center gap-5 flex-wrap">
                <Legend color="bg-emerald-500" label="Passing" value={overview.controlsPassing} />
                <Legend color="bg-amber-400" label="In Progress" value={overview.controlsInProgress} />
                <Legend color="bg-gray-300" label="Not Started" value={overview.controlsNotStarted} />
              </div>
              <Link to="/soc2-program/controls" className="text-[12px] font-medium text-gray-600 hover:text-gray-900 mt-1 inline-flex items-center gap-1 w-fit">
                View all controls <Icon icon="mdi:arrow-right" width={14} />
              </Link>
            </div>
          </div>

          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
            <StatCard
              label="Policies" icon="mdi:file-document-outline" to="/soc2-program/policies"
              tone={overview.policiesTotal > 0 && overview.policiesPublished === overview.policiesTotal ? 'success' : 'warning'}
              value={`${overview.policiesPublished}/${overview.policiesTotal}`} sub="published"
            />
            <StatCard
              label="Evidence & Tasks" icon="mdi:clipboard-check-outline" to="/soc2-program/evidence-tasks"
              tone={overview.tasksOpen === 0 ? 'success' : 'warning'}
              value={overview.tasksOpen} sub={`${overview.tasksTotal} total`}
            />
            <StatCard label="Personnel" icon="mdi:account-group-outline" to="/soc2-program/personnel" value={overview.peopleTotal} />
            <StatCard label="Vendors" icon="mdi:domain" to="/soc2-program/vendors" value={overview.vendorsTotal} />
            <StatCard
              label="Open Risks" icon="mdi:alert-outline" to="/soc2-program/risks"
              tone={overview.risksTotal === 0 ? 'success' : 'danger'} value={overview.risksTotal}
            />
            <StatCard
              label="Cloud Findings" icon="mdi:cloud-alert-outline" to="/soc2-program/cloud-tests"
              tone={overview.cloudFindingsTotal === 0 ? 'success' : 'danger'} value={overview.cloudFindingsTotal}
            />
            <StatCard
              label="Integrations" icon="mdi:power-plug-outline" to="/soc2-program/integrations"
              tone={overview.integrationsConnected > 0 ? 'success' : 'neutral'}
              value={overview.integrationsConnected} sub="connected"
            />
          </div>
        </div>
      )}

      {tab === 'controls' && (
        <TableWrap>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Control</Th><Th>Category</Th><Th>Status</Th><Th>Policy</Th><Th>Tasks</Th></tr>
          </thead>
          <tbody>
            {controls.map((c) => (
              <tr key={c.id} onClick={() => setSelected({ type: 'control', data: c })}
                  className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer">
                <td className="p-3">
                  <div className="text-[13px] text-gray-900 font-medium">{c.name}</div>
                  {c.requirementName && <div className="text-[11px] text-gray-400 truncate max-w-md">{c.requirementName}</div>}
                </td>
                <td className="p-3"><CategoryBadge category={c.category} /></td>
                <td className="p-3">
                  <span className={`px-2 py-0.5 rounded-full text-[11px] font-medium ${controlStatusClass(c.status)}`}>
                    {CONTROL_STATUS_LABEL[c.status]}
                  </span>
                </td>
                <td className="p-3 text-[12px] text-gray-500">{c.policyName || '—'}</td>
                <td className="p-3 text-[12px] text-gray-500">{c.taskCount}</td>
              </tr>
            ))}
            {controls.length === 0 && <EmptyRow cols={5} text="No controls." />}
          </tbody>
        </TableWrap>
      )}

      {tab === 'policies' && (
        <TableWrap>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Policy</Th><Th>Status</Th><Th>Description</Th><Th>Action</Th></tr>
          </thead>
          <tbody>
            {policies.map((p) => {
              const isPublished = (p.status || '').toLowerCase() === 'published';
              return (
                <tr key={p.id} onClick={() => setSelected({ type: 'policy', data: p })}
                    className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer">
                  <td className="p-3 text-[13px] text-gray-900 font-medium">{p.name}</td>
                  <td className="p-3"><StatusPill text={(p.status || '').replace(/_/g, ' ')} tone={policyTone(p.status)} /></td>
                  <td className="p-3 text-[12px] text-gray-500 max-w-lg truncate">{p.description || '—'}</td>
                  <td className="p-3">
                    {!isPublished && (
                      <ActionButton onClick={(e) => { e.stopPropagation(); handlePublish(p.id); }} disabled={publishingId === p.id}>
                        {publishingId === p.id ? 'Publishing…' : 'Publish'}
                      </ActionButton>
                    )}
                  </td>
                </tr>
              );
            })}
            {policies.length === 0 && <EmptyRow cols={4} text="No policies." />}
          </tbody>
        </TableWrap>
      )}

      {tab === 'evidence-tasks' && (
        <div className="space-y-3">
          <div className="flex justify-end">
            <button
              onClick={() => setTaskModalOpen(true)}
              className="flex items-center gap-2 px-3.5 py-2 bg-[#1a1a1a] text-white rounded-lg text-[12px] font-medium hover:bg-black transition-colors"
            >
              <Icon icon="mdi:plus" width={14} /> New Task
            </button>
          </div>
          <TableWrap>
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr><Th>Task</Th><Th>Control</Th><Th>Owner</Th><Th>Status</Th></tr>
            </thead>
            <tbody>
              {tasks.map((t) => (
                <tr key={t.id} onClick={() => setSelected({ type: 'task', data: t })}
                    className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer">
                  <td className="p-3 text-[13px] text-gray-900 font-medium">{t.title}</td>
                  <td className="p-3 text-[12px] text-gray-500">{t.controlName || '—'}</td>
                  <td className="p-3"><OwnerBadge name={t.assigneeName} /></td>
                  <td className="p-3">
                    <select
                      value={t.status || 'todo'}
                      disabled={updatingTaskId === t.id}
                      onClick={(e) => e.stopPropagation()}
                      onChange={(e) => handleTaskStatusChange(t.id, e.target.value)}
                      className="text-[11px] font-medium rounded-md border border-gray-300 bg-white px-2 py-1 text-gray-700 disabled:opacity-40 capitalize"
                    >
                      {TASK_STATUS_OPTIONS.map((s) => (
                        <option key={s} value={s} className="capitalize">{s.replace(/_/g, ' ')}</option>
                      ))}
                    </select>
                  </td>
                </tr>
              ))}
              {tasks.length === 0 && <EmptyRow cols={4} text="No tasks." />}
            </tbody>
          </TableWrap>
        </div>
      )}

      {tab === 'personnel' && (
        <div className="space-y-3">
          <div className="flex justify-end">
            <button
              onClick={() => setInviteModalOpen(true)}
              className="flex items-center gap-2 px-3.5 py-2 bg-[#1a1a1a] text-white rounded-lg text-[12px] font-medium hover:bg-black transition-colors"
            >
              <Icon icon="mdi:plus" width={14} /> Invite Member
            </button>
          </div>
          <TableWrap>
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr><Th>Name</Th><Th>Email</Th><Th>Role</Th><Th>Department</Th><Th>Status</Th></tr>
            </thead>
            <tbody>
              {personnel.map((p) => (
                <tr key={p.id} onClick={() => setSelected({ type: 'person', data: p })}
                    className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer">
                  <td className="p-3 text-[13px] text-gray-900 font-medium">{p.name}</td>
                  <td className="p-3 text-[12px] text-gray-500">{p.email}</td>
                  <td className="p-3 text-[12px] text-gray-500 capitalize">{p.role}</td>
                  <td className="p-3 text-[12px] text-gray-500 capitalize">{p.department || '—'}</td>
                  <td className="p-3"><StatusPill text={p.active ? 'Active' : 'Inactive'} tone={p.active ? 'success' : 'neutral'} /></td>
                </tr>
              ))}
              {personnel.length === 0 && <EmptyRow cols={5} text="No personnel." />}
            </tbody>
          </TableWrap>
        </div>
      )}

      {tab === 'vendors' && (
        <div className="space-y-3">
          <div className="flex justify-end">
            <button
              onClick={() => setVendorModalOpen(true)}
              className="flex items-center gap-2 px-3.5 py-2 bg-[#1a1a1a] text-white rounded-lg text-[12px] font-medium hover:bg-black transition-colors"
            >
              <Icon icon="mdi:plus" width={14} /> Add Vendor
            </button>
          </div>
          <TableWrap>
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr><Th>Vendor</Th><Th>Category</Th><Th>Status</Th><Th>Residual Risk</Th><Th>Description</Th></tr>
            </thead>
            <tbody>
              {vendors.map((v) => (
                <tr key={v.id} onClick={() => setSelected({ type: 'vendor', data: v })}
                    className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer">
                  <td className="p-3 text-[13px] text-gray-900 font-medium">{v.name}</td>
                  <td className="p-3">{v.category ? <Chip text={v.category.replace(/_/g, ' ')} /> : <span className="text-[12px] text-gray-400">—</span>}</td>
                  <td className="p-3"><StatusPill text={(v.status || '').replace(/_/g, ' ')} tone={vendorStatusTone(v.status)} /></td>
                  <td className="p-3">{v.residualSeverity ? <StatusPill text={v.residualSeverity} tone={severityTone(v.residualSeverity)} /> : <span className="text-[12px] text-gray-400">—</span>}</td>
                  <td className="p-3 text-[12px] text-gray-500 max-w-lg truncate">{v.description || '—'}</td>
                </tr>
              ))}
              {vendors.length === 0 && <EmptyRow cols={5} text="No vendors tracked yet." />}
            </tbody>
          </TableWrap>
        </div>
      )}

      {tab === 'risks' && (
        <div className="space-y-3">
          <div className="flex justify-end">
            <button
              onClick={() => setRiskModalOpen(true)}
              className="flex items-center gap-2 px-3.5 py-2 bg-[#1a1a1a] text-white rounded-lg text-[12px] font-medium hover:bg-black transition-colors"
            >
              <Icon icon="mdi:plus" width={14} /> Add Risk
            </button>
          </div>
          <TableWrap>
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr><Th>Risk</Th><Th>Category</Th><Th>Severity</Th><Th>Status</Th><Th>Owner</Th></tr>
            </thead>
            <tbody>
              {risks.map((r) => (
                <tr key={r.id} onClick={() => setSelected({ type: 'risk', data: r })}
                    className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer">
                  <td className="p-3">
                    <div className="text-[13px] text-gray-900 font-medium">{r.title || '—'}</div>
                    {r.description && <div className="text-[11px] text-gray-400 truncate max-w-md">{r.description}</div>}
                  </td>
                  <td className="p-3">{r.category ? <Chip text={r.category.replace(/_/g, ' ')} /> : <span className="text-[12px] text-gray-400">—</span>}</td>
                  <td className="p-3"><StatusPill text={r.severity || '—'} tone={severityTone(r.severity)} /></td>
                  <td className="p-3"><StatusPill text={(r.status || '').replace(/_/g, ' ')} tone={riskStatusTone(r.status)} /></td>
                  <td className="p-3 text-[12px] text-gray-500">{r.owner || '—'}</td>
                </tr>
              ))}
              {risks.length === 0 && <EmptyRow cols={5} text="No risks in the register yet." />}
            </tbody>
          </TableWrap>
        </div>
      )}

      {tab === 'integrations' && (
        <TableWrap>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Integration</Th><Th>Category</Th><Th>Capabilities</Th><Th>Status</Th></tr>
          </thead>
          <tbody>
            {integrations.map((i) => (
              <tr key={i.id} onClick={() => setSelected({ type: 'integration', data: i })}
                  className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer">
                <td className="p-3">
                  <div className="text-[13px] text-gray-900 font-medium">{i.name}</div>
                  {i.description && <div className="text-[11px] text-gray-400 truncate max-w-md">{i.description}</div>}
                </td>
                <td className="p-3 text-[12px] text-gray-500">{i.category || '—'}</td>
                <td className="p-3 text-[12px] text-gray-500">{i.capabilities || '—'}</td>
                <td className="p-3"><StatusPill text={i.connected ? 'Connected' : 'Available'} tone={i.connected ? 'success' : 'neutral'} /></td>
              </tr>
            ))}
            {integrations.length === 0 && <EmptyRow cols={4} text="No integrations available." />}
          </tbody>
        </TableWrap>
      )}

      {tab === 'cloud-tests' && (
        <TableWrap>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Check</Th><Th>Provider</Th><Th>Resource</Th><Th>Severity</Th><Th>Status</Th></tr>
          </thead>
          <tbody>
            {cloudTests.map((f) => (
              <tr key={f.id} onClick={() => setSelected({ type: 'cloudtest', data: f })}
                  className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer">
                <td className="p-3 text-[13px] text-gray-900 font-medium">{f.title || '—'}</td>
                <td className="p-3 text-[12px] text-gray-500 uppercase">{f.provider || '—'}</td>
                <td className="p-3 text-[12px] text-gray-500 truncate max-w-xs">{f.resource || '—'}</td>
                <td className="p-3"><StatusPill text={f.severity || '—'} tone={severityTone(f.severity)} /></td>
                <td className="p-3"><StatusPill text={(f.status || '').replace(/_/g, ' ')} tone={findingStatusTone(f.status)} /></td>
              </tr>
            ))}
            {cloudTests.length === 0 && <EmptyRow cols={5} text="No cloud test findings. Connect a cloud provider under Integrations to run checks." />}
          </tbody>
        </TableWrap>
      )}

      {vendorModalOpen && (
        <VendorModal onClose={() => setVendorModalOpen(false)} onSubmit={handleCreateVendor} />
      )}
      {riskModalOpen && (
        <RiskModal onClose={() => setRiskModalOpen(false)} onSubmit={handleCreateRisk} />
      )}
      {taskModalOpen && (
        <TaskModal onClose={() => setTaskModalOpen(false)} onSubmit={handleCreateTask} personnel={personnel} controls={controls} />
      )}
      {inviteModalOpen && (
        <InviteModal onClose={() => setInviteModalOpen(false)} onSubmit={handleInviteMember} />
      )}
      {selected && (
        <DetailDrawer
          selected={selected}
          onClose={() => setSelected(null)}
          onPublishPolicy={handlePublish}
          publishingId={publishingId}
          onArchivePolicy={handleArchivePolicy}
          onTaskStatusChange={handleTaskStatusChange}
          updatingTaskId={updatingTaskId}
          onSaveTask={handleSaveTask}
          onDeleteControl={handleDeleteControl}
          onSaveVendor={handleSaveVendor}
          onDeleteVendor={handleDeleteVendor}
          onSaveRisk={handleSaveRisk}
          onDeleteRisk={handleDeleteRisk}
          onSavePerson={handleSavePerson}
          onDeletePerson={handleDeletePerson}
          personnel={personnel}
        />
      )}
    </div>
  );
};

const ModalShell: React.FC<{ title: string; onClose: () => void; children: React.ReactNode }> = ({ title, onClose, children }) => createPortal((
  <div className="fixed inset-0 z-50 flex items-center justify-center p-4" style={{ backgroundColor: 'rgba(0,0,0,0.38)' }}>
    <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md">
      <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
        <h3 className="text-[15px] font-semibold text-gray-900">{title}</h3>
        <button onClick={onClose} className="text-gray-400 hover:text-gray-700">
          <Icon icon="mdi:close" width={18} />
        </button>
      </div>
      <div className="p-5">{children}</div>
    </div>
  </div>
), document.body);

const FormField: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="mb-3">
    <label className="block text-[11px] font-medium text-gray-500 mb-1">{label}</label>
    {children}
  </div>
);

const inputCls = "w-full text-[13px] border border-gray-300 rounded-lg px-3 py-2 text-gray-900 focus:outline-none focus:ring-1 focus:ring-gray-400";

const VendorModal: React.FC<{ onClose: () => void; onSubmit: (payload: CreateVendorRequest) => Promise<void> }> = ({ onClose, onSubmit }) => {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState(VENDOR_CATEGORY_OPTIONS[0]);
  const [status, setStatus] = useState(VENDOR_STATUS_OPTIONS[0]);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    if (!name.trim() || !description.trim()) {
      setErr('Name and description are required.');
      return;
    }
    setSubmitting(true);
    setErr(null);
    try {
      await onSubmit({ name: name.trim(), description: description.trim(), category, status });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to create vendor');
      setSubmitting(false);
    }
  };

  return (
    <ModalShell title="Add Vendor" onClose={onClose}>
      {err && <div className="mb-3 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">{err}</div>}
      <FormField label="Name"><input className={inputCls} value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. AWS" /></FormField>
      <FormField label="Description"><textarea className={inputCls} rows={2} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="What does this vendor provide?" /></FormField>
      <FormField label="Category">
        <select className={inputCls} value={category} onChange={(e) => setCategory(e.target.value)}>
          {VENDOR_CATEGORY_OPTIONS.map((c) => <option key={c} value={c}>{c.replace(/_/g, ' ')}</option>)}
        </select>
      </FormField>
      <FormField label="Status">
        <select className={inputCls} value={status} onChange={(e) => setStatus(e.target.value)}>
          {VENDOR_STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
        </select>
      </FormField>
      <div className="flex justify-end gap-2 mt-4">
        <button onClick={onClose} className="px-3.5 py-2 text-[12px] font-medium text-gray-600 hover:text-gray-900">Cancel</button>
        <ActionButton onClick={submit} disabled={submitting}>{submitting ? 'Adding…' : 'Add Vendor'}</ActionButton>
      </div>
    </ModalShell>
  );
};

const RiskModal: React.FC<{ onClose: () => void; onSubmit: (payload: CreateRiskRequest) => Promise<void> }> = ({ onClose, onSubmit }) => {
  const [title, setTitleVal] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState(RISK_CATEGORY_OPTIONS[0]);
  const [department, setDepartment] = useState(DEPARTMENT_OPTIONS[0]);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    if (!title.trim() || !description.trim()) {
      setErr('Title and description are required.');
      return;
    }
    setSubmitting(true);
    setErr(null);
    try {
      await onSubmit({ title: title.trim(), description: description.trim(), category, department });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to create risk');
      setSubmitting(false);
    }
  };

  return (
    <ModalShell title="Add Risk" onClose={onClose}>
      {err && <div className="mb-3 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">{err}</div>}
      <FormField label="Title"><input className={inputCls} value={title} onChange={(e) => setTitleVal(e.target.value)} placeholder="e.g. Offboarding gap" /></FormField>
      <FormField label="Description"><textarea className={inputCls} rows={2} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Describe the risk" /></FormField>
      <FormField label="Category">
        <select className={inputCls} value={category} onChange={(e) => setCategory(e.target.value)}>
          {RISK_CATEGORY_OPTIONS.map((c) => <option key={c} value={c}>{c.replace(/_/g, ' ')}</option>)}
        </select>
      </FormField>
      <FormField label="Department">
        <select className={inputCls} value={department} onChange={(e) => setDepartment(e.target.value)}>
          {DEPARTMENT_OPTIONS.map((d) => <option key={d} value={d}>{d}</option>)}
        </select>
      </FormField>
      <div className="flex justify-end gap-2 mt-4">
        <button onClick={onClose} className="px-3.5 py-2 text-[12px] font-medium text-gray-600 hover:text-gray-900">Cancel</button>
        <ActionButton onClick={submit} disabled={submitting}>{submitting ? 'Adding…' : 'Add Risk'}</ActionButton>
      </div>
    </ModalShell>
  );
};

const TaskModal: React.FC<{ onClose: () => void; onSubmit: (payload: CreateTaskRequest) => Promise<void>; personnel: Soc2Person[]; controls: Soc2Control[] }> = ({ onClose, onSubmit, personnel, controls }) => {
  const [title, setTitleVal] = useState('');
  const [description, setDescription] = useState('');
  const [assigneeId, setAssigneeId] = useState('');
  const [controlId, setControlId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    if (!title.trim() || !description.trim()) {
      setErr('Title and description are required.');
      return;
    }
    setSubmitting(true);
    setErr(null);
    try {
      await onSubmit({
        title: title.trim(),
        description: description.trim(),
        assigneeId: assigneeId || undefined,
        controlIds: controlId ? [controlId] : undefined,
      });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to create task');
      setSubmitting(false);
    }
  };

  return (
    <ModalShell title="New Task" onClose={onClose}>
      {err && <div className="mb-3 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">{err}</div>}
      <FormField label="Title"><input className={inputCls} value={title} onChange={(e) => setTitleVal(e.target.value)} placeholder="e.g. Review access logs" /></FormField>
      <FormField label="Description"><textarea className={inputCls} rows={2} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Describe the task" /></FormField>
      <FormField label="Assignee (optional)">
        <select className={inputCls} value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)}>
          <option value="">Unassigned</option>
          {personnel.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      </FormField>
      <FormField label="Link to control (optional)">
        <select className={inputCls} value={controlId} onChange={(e) => setControlId(e.target.value)}>
          <option value="">None</option>
          {controls.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
      </FormField>
      <div className="flex justify-end gap-2 mt-4">
        <button onClick={onClose} className="px-3.5 py-2 text-[12px] font-medium text-gray-600 hover:text-gray-900">Cancel</button>
        <ActionButton onClick={submit} disabled={submitting}>{submitting ? 'Creating…' : 'Create Task'}</ActionButton>
      </div>
    </ModalShell>
  );
};

const InviteModal: React.FC<{ onClose: () => void; onSubmit: (payload: InviteMemberRequest) => Promise<void> }> = ({ onClose, onSubmit }) => {
  const [email, setEmail] = useState('');
  const [role, setRole] = useState(MEMBER_ROLE_OPTIONS[1]);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    if (!email.trim()) {
      setErr('Email is required.');
      return;
    }
    setSubmitting(true);
    setErr(null);
    try {
      await onSubmit({ email: email.trim(), role });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to send invite');
      setSubmitting(false);
    }
  };

  return (
    <ModalShell title="Invite Member" onClose={onClose}>
      {err && <div className="mb-3 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">{err}</div>}
      <FormField label="Email"><input type="email" className={inputCls} value={email} onChange={(e) => setEmail(e.target.value)} placeholder="teammate@company.com" /></FormField>
      <FormField label="Role">
        <select className={inputCls} value={role} onChange={(e) => setRole(e.target.value)}>
          {MEMBER_ROLE_OPTIONS.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
      </FormField>
      <div className="flex justify-end gap-2 mt-4">
        <button onClick={onClose} className="px-3.5 py-2 text-[12px] font-medium text-gray-600 hover:text-gray-900">Cancel</button>
        <ActionButton onClick={submit} disabled={submitting}>{submitting ? 'Sending…' : 'Send Invite'}</ActionButton>
      </div>
    </ModalShell>
  );
};

const DrawerShell: React.FC<{ title: string; subtitle?: string; onClose: () => void; children: React.ReactNode }> = ({ title, subtitle, onClose, children }) => createPortal((
  <div className="fixed inset-0 z-50">
    <div className="fixed inset-0 bg-black/30" onClick={onClose} />
    <div className="fixed right-0 top-0 h-full w-full max-w-lg bg-white shadow-2xl flex flex-col">
      <div className="flex items-start justify-between gap-4 px-6 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
        <div>
          <h3 className="text-[16px] font-semibold text-gray-900">{title}</h3>
          {subtitle && <p className="text-[12px] text-gray-500 mt-0.5">{subtitle}</p>}
        </div>
        <button onClick={onClose} className="text-gray-400 hover:text-gray-700 shrink-0">
          <Icon icon="mdi:close" width={20} />
        </button>
      </div>
      <div className="flex-1 overflow-y-auto px-6 py-5">{children}</div>
    </div>
  </div>
), document.body);

const DrawerSection: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="mb-5">
    <div className="text-[11px] font-semibold uppercase tracking-wide text-gray-400 mb-2">{label}</div>
    {children}
  </div>
);

interface DetailDrawerProps {
  selected: Exclude<SelectedRow, null>;
  onClose: () => void;
  onPublishPolicy: (policyId: string) => void;
  publishingId: string | null;
  onArchivePolicy: (policyId: string, archived: boolean) => Promise<void>;
  onTaskStatusChange: (taskId: string, status: string) => void;
  updatingTaskId: string | null;
  onSaveTask: (taskId: string, payload: UpdateTaskRequest) => Promise<void>;
  onDeleteControl: (controlId: string) => Promise<void>;
  onSaveVendor: (vendorId: string, payload: UpdateVendorRequest) => Promise<void>;
  onDeleteVendor: (vendorId: string) => Promise<void>;
  onSaveRisk: (riskId: string, payload: UpdateRiskRequest) => Promise<void>;
  onDeleteRisk: (riskId: string) => Promise<void>;
  onSavePerson: (memberId: string, payload: UpdatePersonRequest) => Promise<void>;
  onDeletePerson: (memberId: string) => Promise<void>;
  personnel: Soc2Person[];
}

const DetailDrawer: React.FC<DetailDrawerProps> = ({
  selected, onClose, onPublishPolicy, publishingId, onArchivePolicy,
  onTaskStatusChange, updatingTaskId, onSaveTask, onDeleteControl,
  onSaveVendor, onDeleteVendor, onSaveRisk, onDeleteRisk,
  onSavePerson, onDeletePerson, personnel,
}) => {
  const { type, data } = selected;
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  if (type === 'control') {
    const c = data;
    return (
      <DrawerShell title={c.name} onClose={onClose}>
        <DrawerSection label="Status">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span className={`px-2 py-0.5 rounded-full text-[11px] font-medium ${controlStatusClass(c.status)}`}>
                {CONTROL_STATUS_LABEL[c.status]}
              </span>
              <CategoryBadge category={c.category} />
            </div>
            <DangerButton onClick={async () => { setBusy(true); await onDeleteControl(c.id); setBusy(false); }} disabled={busy}>
              {busy ? 'Deleting…' : 'Delete Control'}
            </DangerButton>
          </div>
        </DrawerSection>
        {c.description && <DrawerSection label="Description"><p className="text-[13px] text-gray-700 whitespace-pre-line">{c.description}</p></DrawerSection>}
        <DrawerSection label={`Requirements (${c.requirements.length})`}>
          {c.requirements.length === 0 && <p className="text-[12px] text-gray-400">None mapped.</p>}
          {c.requirements.map((r, i) => (
            <div key={i} className="text-[12px] text-gray-700 mb-2">
              {r.identifier && <span className="font-medium text-gray-900">{r.identifier}</span>}{r.identifier && ' — '}{r.name}
            </div>
          ))}
        </DrawerSection>
        <DrawerSection label={`Linked Policies (${c.policies.length})`}>
          {c.policies.length === 0 && <p className="text-[12px] text-gray-400">No policy linked.</p>}
          {c.policies.map((p) => (
            <div key={p.id} className="flex items-center justify-between py-1.5">
              <span className="text-[13px] text-gray-900">{p.name}</span>
              <StatusPill text={(p.status || '').replace(/_/g, ' ')} tone={policyTone(p.status)} />
            </div>
          ))}
        </DrawerSection>
        <DrawerSection label={`Tasks (${c.tasks.length})`}>
          {c.tasks.length === 0 && <p className="text-[12px] text-gray-400">No tasks.</p>}
          {c.tasks.map((t) => (
            <div key={t.id} className="flex items-center justify-between py-1.5 gap-2">
              <span className="text-[13px] text-gray-900 flex-1">{t.title}</span>
              <select
                value={t.status || 'todo'}
                disabled={updatingTaskId === t.id}
                onChange={(e) => onTaskStatusChange(t.id, e.target.value)}
                className="text-[11px] font-medium rounded-md border border-gray-300 bg-white px-2 py-1 text-gray-700 disabled:opacity-40 capitalize"
              >
                {TASK_STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
              </select>
            </div>
          ))}
        </DrawerSection>
      </DrawerShell>
    );
  }

  if (type === 'policy') {
    const p = data;
    const isPublished = (p.status || '').toLowerCase() === 'published';
    const isArchived = (p.status || '').toLowerCase() === 'archived';
    return (
      <DrawerShell title={p.name} onClose={onClose}>
        <DrawerSection label="Status">
          <div className="flex items-center gap-2 flex-wrap">
            <StatusPill text={(p.status || '').replace(/_/g, ' ')} tone={policyTone(p.status)} />
            {!isPublished && (
              <ActionButton onClick={() => onPublishPolicy(p.id)} disabled={publishingId === p.id}>
                {publishingId === p.id ? 'Publishing…' : 'Publish'}
              </ActionButton>
            )}
            <SecondaryButton onClick={async () => { setBusy(true); await onArchivePolicy(p.id, !isArchived); setBusy(false); }} disabled={busy}>
              {busy ? 'Saving…' : isArchived ? 'Unarchive' : 'Archive'}
            </SecondaryButton>
          </div>
        </DrawerSection>
        {p.description && <DrawerSection label="Description"><p className="text-[13px] text-gray-700">{p.description}</p></DrawerSection>}
        <DrawerSection label="Document">
          {p.content
            ? <p className="text-[13px] text-gray-700 whitespace-pre-line leading-relaxed">{p.content}</p>
            : <p className="text-[12px] text-gray-400">No document content.</p>}
        </DrawerSection>
      </DrawerShell>
    );
  }

  if (type === 'task') {
    const t = data;
    if (editing) {
      return (
        <DrawerShell title="Edit Task" onClose={onClose}>
          <TaskEditForm
            task={t}
            personnel={personnel}
            onCancel={() => setEditing(false)}
            onSave={async (payload) => { await onSaveTask(t.id, payload); }}
          />
        </DrawerShell>
      );
    }
    return (
      <DrawerShell title={t.title} subtitle={t.controlName || undefined} onClose={onClose}>
        <DrawerSection label="Owner"><OwnerBadge name={t.assigneeName} /></DrawerSection>
        <DrawerSection label="Status">
          <div className="flex items-center justify-between">
            <select
              value={t.status || 'todo'}
              disabled={updatingTaskId === t.id}
              onChange={(e) => onTaskStatusChange(t.id, e.target.value)}
              className="text-[12px] font-medium rounded-md border border-gray-300 bg-white px-3 py-1.5 text-gray-700 disabled:opacity-40 capitalize"
            >
              {TASK_STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
            </select>
            <SecondaryButton onClick={() => setEditing(true)}>Edit</SecondaryButton>
          </div>
        </DrawerSection>
      </DrawerShell>
    );
  }

  if (type === 'person') {
    const p = data;
    if (editing) {
      return (
        <DrawerShell title="Edit Person" onClose={onClose}>
          <PersonEditForm
            person={p}
            onCancel={() => setEditing(false)}
            onSave={async (payload) => { await onSavePerson(p.id, payload); }}
          />
        </DrawerShell>
      );
    }
    return (
      <DrawerShell title={p.name} subtitle={p.email} onClose={onClose}>
        {err && <div className="mb-3 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">{err}</div>}
        <DrawerSection label="Role"><p className="text-[13px] text-gray-700 capitalize">{p.role}</p></DrawerSection>
        <DrawerSection label="Department"><p className="text-[13px] text-gray-700 capitalize">{p.department || '—'}</p></DrawerSection>
        {p.jobTitle && <DrawerSection label="Job Title"><p className="text-[13px] text-gray-700">{p.jobTitle}</p></DrawerSection>}
        <DrawerSection label="Status">
          <div className="flex items-center justify-between">
            <StatusPill text={p.active ? 'Active' : 'Inactive'} tone={p.active ? 'success' : 'neutral'} />
            <div className="flex gap-2">
              <SecondaryButton onClick={() => setEditing(true)}>Edit</SecondaryButton>
              <SecondaryButton
                onClick={async () => { setBusy(true); setErr(null); try { await onSavePerson(p.id, { isActive: !p.active }); } catch (e) { setErr(e instanceof Error ? e.message : 'Failed to update status'); } finally { setBusy(false); } }}
                disabled={busy}
              >
                {p.active ? 'Deactivate' : 'Reactivate'}
              </SecondaryButton>
              <DangerButton onClick={async () => { setBusy(true); await onDeletePerson(p.id); setBusy(false); }} disabled={busy}>
                Remove
              </DangerButton>
            </div>
          </div>
        </DrawerSection>
      </DrawerShell>
    );
  }

  if (type === 'vendor') {
    const v = data;
    if (editing) {
      return (
        <DrawerShell title="Edit Vendor" onClose={onClose}>
          <VendorEditForm
            vendor={v}
            onCancel={() => setEditing(false)}
            onSave={async (payload) => { await onSaveVendor(v.id, payload); }}
          />
        </DrawerShell>
      );
    }
    return (
      <DrawerShell title={v.name} subtitle={v.category || undefined} onClose={onClose}>
        <DrawerSection label="Status">
          <div className="flex items-center justify-between">
            <StatusPill text={(v.status || '').replace(/_/g, ' ')} tone={vendorStatusTone(v.status)} />
            <div className="flex gap-2">
              <SecondaryButton onClick={() => setEditing(true)}>Edit</SecondaryButton>
              <DangerButton onClick={async () => { setBusy(true); await onDeleteVendor(v.id); setBusy(false); }} disabled={busy}>
                {busy ? 'Deleting…' : 'Delete'}
              </DangerButton>
            </div>
          </div>
        </DrawerSection>
        {v.description && <DrawerSection label="Description"><p className="text-[13px] text-gray-700 whitespace-pre-line">{v.description}</p></DrawerSection>}
        {(v.inherentSeverity || v.residualSeverity) && (
          <DrawerSection label="Risk Assessment">
            <div className="flex items-center gap-6">
              <div>
                <div className="text-[10px] text-gray-400 uppercase tracking-wide mb-1">Inherent</div>
                <StatusPill text={v.inherentSeverity || '—'} tone={severityTone(v.inherentSeverity)} />
              </div>
              <Icon icon="mdi:arrow-right" width={14} className="text-gray-300 mt-3" />
              <div>
                <div className="text-[10px] text-gray-400 uppercase tracking-wide mb-1">Residual</div>
                <StatusPill text={v.residualSeverity || '—'} tone={severityTone(v.residualSeverity)} />
              </div>
            </div>
          </DrawerSection>
        )}
        {(v.subProcessor || v.website) && (
          <DrawerSection label="Details">
            <div className="flex items-center gap-2 flex-wrap">
              {v.subProcessor && <Chip text="Subprocessor" />}
              {v.website && (
                <a href={v.website} target="_blank" rel="noopener noreferrer"
                   className="text-[12px] text-gray-600 hover:text-gray-900 inline-flex items-center gap-1">
                  {v.website.replace(/^https?:\/\//, '')} <Icon icon="mdi:open-in-new" width={12} />
                </a>
              )}
            </div>
          </DrawerSection>
        )}
      </DrawerShell>
    );
  }

  if (type === 'risk') {
    const r = data;
    if (editing) {
      return (
        <DrawerShell title="Edit Risk" onClose={onClose}>
          <RiskEditForm
            risk={r}
            onCancel={() => setEditing(false)}
            onSave={async (payload) => { await onSaveRisk(r.id, payload); }}
          />
        </DrawerShell>
      );
    }
    return (
      <DrawerShell title={r.title || 'Risk'} subtitle={r.category || undefined} onClose={onClose}>
        <DrawerSection label="Severity">
          <StatusPill text={r.severity || '—'} tone={severityTone(r.severity)} />
        </DrawerSection>
        <DrawerSection label="Status">
          <div className="flex items-center justify-between">
            <StatusPill text={(r.status || '').replace(/_/g, ' ')} tone={riskStatusTone(r.status)} />
            <div className="flex gap-2">
              <SecondaryButton onClick={() => setEditing(true)}>Edit</SecondaryButton>
              <DangerButton onClick={async () => { setBusy(true); await onDeleteRisk(r.id); setBusy(false); }} disabled={busy}>
                {busy ? 'Deleting…' : 'Delete'}
              </DangerButton>
            </div>
          </div>
        </DrawerSection>
        <DrawerSection label="Owner"><p className="text-[13px] text-gray-700">{r.owner || '—'}</p></DrawerSection>
        {r.description && <DrawerSection label="Description"><p className="text-[13px] text-gray-700 whitespace-pre-line">{r.description}</p></DrawerSection>}
      </DrawerShell>
    );
  }

  if (type === 'integration') {
    const i = data;
    return (
      <DrawerShell title={i.name} subtitle={i.category || undefined} onClose={onClose}>
        <DrawerSection label="Status"><StatusPill text={i.connected ? 'Connected' : 'Available'} tone={i.connected ? 'success' : 'neutral'} /></DrawerSection>
        {i.capabilities && <DrawerSection label="Capabilities"><p className="text-[13px] text-gray-700">{i.capabilities}</p></DrawerSection>}
        {i.description && <DrawerSection label="Description"><p className="text-[13px] text-gray-700 whitespace-pre-line">{i.description}</p></DrawerSection>}
      </DrawerShell>
    );
  }

  // cloudtest
  const f = data;
  return (
    <DrawerShell title={f.title || 'Finding'} subtitle={f.provider ? f.provider.toUpperCase() : undefined} onClose={onClose}>
      <DrawerSection label="Severity">
        <StatusPill text={f.severity || '—'} tone={severityTone(f.severity)} />
      </DrawerSection>
      <DrawerSection label="Status"><StatusPill text={(f.status || '').replace(/_/g, ' ')} tone={findingStatusTone(f.status)} /></DrawerSection>
      <DrawerSection label="Resource"><p className="text-[13px] text-gray-700 break-all">{f.resource || '—'}</p></DrawerSection>
    </DrawerShell>
  );
};

const VendorEditForm: React.FC<{ vendor: Soc2Vendor; onCancel: () => void; onSave: (payload: UpdateVendorRequest) => Promise<void> }> = ({ vendor, onCancel, onSave }) => {
  const [name, setName] = useState(vendor.name);
  const [description, setDescription] = useState(vendor.description || '');
  const [category, setCategory] = useState(vendor.category || VENDOR_CATEGORY_OPTIONS[0]);
  const [status, setStatus] = useState(vendor.status || VENDOR_STATUS_OPTIONS[0]);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    setSubmitting(true);
    setErr(null);
    try {
      await onSave({ name: name.trim(), description: description.trim(), category, status });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to save vendor');
      setSubmitting(false);
    }
  };

  return (
    <div>
      {err && <div className="mb-3 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">{err}</div>}
      <FormField label="Name"><input className={inputCls} value={name} onChange={(e) => setName(e.target.value)} /></FormField>
      <FormField label="Description"><textarea className={inputCls} rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></FormField>
      <FormField label="Category">
        <select className={inputCls} value={category} onChange={(e) => setCategory(e.target.value)}>
          {VENDOR_CATEGORY_OPTIONS.map((c) => <option key={c} value={c}>{c.replace(/_/g, ' ')}</option>)}
        </select>
      </FormField>
      <FormField label="Status">
        <select className={inputCls} value={status} onChange={(e) => setStatus(e.target.value)}>
          {VENDOR_STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
        </select>
      </FormField>
      <div className="flex justify-end gap-2 mt-4">
        <button onClick={onCancel} className="px-3.5 py-2 text-[12px] font-medium text-gray-600 hover:text-gray-900">Cancel</button>
        <ActionButton onClick={submit} disabled={submitting}>{submitting ? 'Saving…' : 'Save'}</ActionButton>
      </div>
    </div>
  );
};

const RiskEditForm: React.FC<{ risk: Soc2Risk; onCancel: () => void; onSave: (payload: UpdateRiskRequest) => Promise<void> }> = ({ risk, onCancel, onSave }) => {
  const [title, setTitleVal] = useState(risk.title || '');
  const [description, setDescription] = useState(risk.description || '');
  const [category, setCategory] = useState(risk.category || RISK_CATEGORY_OPTIONS[0]);
  const [department, setDepartment] = useState(DEPARTMENT_OPTIONS[0]);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    setSubmitting(true);
    setErr(null);
    try {
      await onSave({ title: title.trim(), description: description.trim(), category, department });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to save risk');
      setSubmitting(false);
    }
  };

  return (
    <div>
      {err && <div className="mb-3 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">{err}</div>}
      <FormField label="Title"><input className={inputCls} value={title} onChange={(e) => setTitleVal(e.target.value)} /></FormField>
      <FormField label="Description"><textarea className={inputCls} rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></FormField>
      <FormField label="Category">
        <select className={inputCls} value={category} onChange={(e) => setCategory(e.target.value)}>
          {RISK_CATEGORY_OPTIONS.map((c) => <option key={c} value={c}>{c.replace(/_/g, ' ')}</option>)}
        </select>
      </FormField>
      <FormField label="Department">
        <select className={inputCls} value={department} onChange={(e) => setDepartment(e.target.value)}>
          {DEPARTMENT_OPTIONS.map((d) => <option key={d} value={d}>{d}</option>)}
        </select>
      </FormField>
      <div className="flex justify-end gap-2 mt-4">
        <button onClick={onCancel} className="px-3.5 py-2 text-[12px] font-medium text-gray-600 hover:text-gray-900">Cancel</button>
        <ActionButton onClick={submit} disabled={submitting}>{submitting ? 'Saving…' : 'Save'}</ActionButton>
      </div>
    </div>
  );
};

const TaskEditForm: React.FC<{ task: Soc2Task; personnel: Soc2Person[]; onCancel: () => void; onSave: (payload: UpdateTaskRequest) => Promise<void> }> = ({ task, personnel, onCancel, onSave }) => {
  const [title, setTitleVal] = useState(task.title);
  const [description, setDescription] = useState('');
  const [assigneeId, setAssigneeId] = useState(task.assigneeId || '');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    setSubmitting(true);
    setErr(null);
    try {
      await onSave({ title: title.trim(), description: description.trim() || undefined, assigneeId: assigneeId || undefined });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to save task');
      setSubmitting(false);
    }
  };

  return (
    <div>
      {err && <div className="mb-3 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">{err}</div>}
      <FormField label="Title"><input className={inputCls} value={title} onChange={(e) => setTitleVal(e.target.value)} /></FormField>
      <FormField label="Description"><textarea className={inputCls} rows={3} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="(optional)" /></FormField>
      <FormField label="Assignee">
        <select className={inputCls} value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)}>
          <option value="">Unassigned</option>
          {personnel.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      </FormField>
      <div className="flex justify-end gap-2 mt-4">
        <button onClick={onCancel} className="px-3.5 py-2 text-[12px] font-medium text-gray-600 hover:text-gray-900">Cancel</button>
        <ActionButton onClick={submit} disabled={submitting}>{submitting ? 'Saving…' : 'Save'}</ActionButton>
      </div>
    </div>
  );
};

const PersonEditForm: React.FC<{ person: Soc2Person; onCancel: () => void; onSave: (payload: UpdatePersonRequest) => Promise<void> }> = ({ person, onCancel, onSave }) => {
  const [role, setRole] = useState(person.role || MEMBER_ROLE_OPTIONS[1]);
  const [department, setDepartment] = useState(person.department || DEPARTMENT_OPTIONS[0]);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    setSubmitting(true);
    setErr(null);
    try {
      await onSave({ role, department });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to save person');
      setSubmitting(false);
    }
  };

  return (
    <div>
      {err && <div className="mb-3 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">{err}</div>}
      <FormField label="Role">
        <select className={inputCls} value={role} onChange={(e) => setRole(e.target.value)}>
          {MEMBER_ROLE_OPTIONS.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
      </FormField>
      <FormField label="Department">
        <select className={inputCls} value={department} onChange={(e) => setDepartment(e.target.value)}>
          {DEPARTMENT_OPTIONS.map((d) => <option key={d} value={d}>{d}</option>)}
        </select>
      </FormField>
      <div className="flex justify-end gap-2 mt-4">
        <button onClick={onCancel} className="px-3.5 py-2 text-[12px] font-medium text-gray-600 hover:text-gray-900">Cancel</button>
        <ActionButton onClick={submit} disabled={submitting}>{submitting ? 'Saving…' : 'Save'}</ActionButton>
      </div>
    </div>
  );
};

const TableWrap: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="border border-[rgba(0,0,0,0.10)] rounded-xl overflow-hidden bg-white">
    <div className="overflow-x-auto">
      <table className="w-full text-sm">{children}</table>
    </div>
  </div>
);

const EmptyRow: React.FC<{ cols: number; text: string }> = ({ cols, text }) => (
  <tr><td colSpan={cols} className="p-6 text-center text-[12px] text-gray-400">{text}</td></tr>
);

export default Soc2ProgramPage;
