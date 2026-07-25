import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { useTopbar } from '../contexts/TopbarContext';
import { useSoc2ProgramData } from '../hooks/useSoc2ProgramData';
import {
  publishSoc2Policy, updateSoc2TaskStatus, createSoc2Vendor, createSoc2Risk,
  CreateVendorRequest, CreateRiskRequest,
} from '../api/soc2Api';
import type {
  Soc2ControlStatus, Soc2Control, Soc2Policy, Soc2Task, Soc2Person,
  Soc2Vendor, Soc2Risk, Soc2Integration, Soc2CloudTest,
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

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'controls', label: 'Controls' },
  { id: 'policies', label: 'Policies' },
  { id: 'evidence-tasks', label: 'Evidence & Tasks' },
  { id: 'personnel', label: 'Personnel' },
  { id: 'vendors', label: 'Vendors' },
  { id: 'risks', label: 'Risks' },
  { id: 'integrations', label: 'Integrations' },
  { id: 'cloud-tests', label: 'Cloud Tests' },
] as const;

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

// Monochrome status pill — filled-dark = done, mid-grey = in progress, light outline = not started.
const controlStatusClass = (status: Soc2ControlStatus): string => {
  if (status === 'PASSING') return 'bg-[#1a1a1a] text-white';
  if (status === 'IN_PROGRESS') return 'bg-gray-200 text-gray-800';
  return 'bg-white text-gray-500 border border-gray-300';
};

const StatusPill: React.FC<{ text: string; tone?: 'dark' | 'mid' | 'light' }> = ({ text, tone = 'light' }) => {
  const cls = tone === 'dark' ? 'bg-[#1a1a1a] text-white'
    : tone === 'mid' ? 'bg-gray-200 text-gray-800'
    : 'bg-white text-gray-500 border border-gray-300';
  return <span className={`px-2 py-0.5 rounded-full text-[11px] font-medium capitalize ${cls}`}>{text || '—'}</span>;
};

const policyTone = (status: string | null): 'dark' | 'mid' | 'light' => {
  const s = (status || '').toLowerCase();
  if (s === 'published') return 'dark';
  if (s === 'needs_review' || s === 'in_review' || s === 'draft') return 'mid';
  return 'light';
};

const StatCard: React.FC<{ label: string; value: string | number; sub?: string }> = ({ label, value, sub }) => (
  <div className="border border-[rgba(0,0,0,0.10)] rounded-xl p-4 bg-white">
    <div className="text-[12px] text-gray-500">{label}</div>
    <div className="text-2xl font-semibold text-gray-900 mt-1">{value}</div>
    {sub && <div className="text-[11px] text-gray-400 mt-0.5">{sub}</div>}
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

const Soc2ProgramPage: React.FC = () => {
  const { tab = 'overview' } = useParams<{ tab: string }>();
  const navigate = useNavigate();
  const { setTitle, setActions, clearActions } = useTopbar();
  const { overview, controls, policies, tasks, personnel, vendors, risks, cloudTests, integrations, isLoading, error, refetch } = useSoc2ProgramData();

  const [publishingId, setPublishingId] = useState<string | null>(null);
  const [updatingTaskId, setUpdatingTaskId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [vendorModalOpen, setVendorModalOpen] = useState(false);
  const [riskModalOpen, setRiskModalOpen] = useState(false);
  const [selected, setSelected] = useState<SelectedRow>(null);

  const trustCenterUrl = overview?.trustCenterUrl;

  useEffect(() => {
    setTitle('SOC 2 Program');
    setActions(
      trustCenterUrl ? (
        <a href={trustCenterUrl} target="_blank" rel="noopener noreferrer"
           className="flex items-center gap-2 px-3.5 py-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] font-medium text-gray-800 hover:shadow-sm transition-all">
          <Icon icon="mdi:shield-check-outline" width={14} /> View Trust Center
          <Icon icon="mdi:open-in-new" width={12} />
        </a>
      ) : null
    );
    return () => { clearActions(); setTitle(null); };
  }, [setTitle, setActions, clearActions, trustCenterUrl]);

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

  const handleTaskStatusChange = async (taskId: string, status: string) => {
    setActionError(null);
    setUpdatingTaskId(taskId);
    try {
      await updateSoc2TaskStatus(taskId, status);
      await refetch();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Failed to update task');
    } finally {
      setUpdatingTaskId(null);
    }
  };

  const handleCreateVendor = async (payload: CreateVendorRequest) => {
    await createSoc2Vendor(payload);
    await refetch();
    setVendorModalOpen(false);
  };

  const handleCreateRisk = async (payload: CreateRiskRequest) => {
    await createSoc2Risk(payload);
    await refetch();
    setRiskModalOpen(false);
  };

  if (isLoading) {
    return <div className="p-6 text-gray-500 text-sm">Loading SOC 2 program…</div>;
  }
  if (error) {
    return <div className="p-6 text-sm text-red-600">Failed to load SOC 2 program data: {error.message}</div>;
  }

  return (
    <div className="p-6">
      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-200 mb-6 overflow-x-auto">
        {TABS.map((t) => (
          <button
            key={t.id}
            onClick={() => navigate(`/soc2-program/${t.id}`)}
            className={`px-4 py-2.5 text-[13px] font-medium border-b-2 whitespace-nowrap transition-colors ${
              tab === t.id ? 'border-[#1a1a1a] text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-800'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {actionError && (
        <div className="mb-4 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-[12px] text-red-700">
          {actionError}
        </div>
      )}

      {tab === 'overview' && overview && (
        <div className="space-y-6">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <StatCard label="Readiness" value={`${overview.readinessPercent}%`} sub={overview.frameworkName} />
            <StatCard label="Controls passing" value={`${overview.controlsPassing}/${overview.controlsTotal}`} sub={`${overview.controlsInProgress} in progress`} />
            <StatCard label="Policies published" value={`${overview.policiesPublished}/${overview.policiesTotal}`} />
            <StatCard label="Open tasks" value={overview.tasksOpen} sub={`${overview.tasksTotal} total`} />
            <StatCard label="Not started" value={overview.controlsNotStarted} sub="controls" />
            <StatCard label="Personnel" value={overview.peopleTotal} />
            <StatCard label="Vendors" value={overview.vendorsTotal} />
            <StatCard label="Open risks" value={overview.risksTotal} />
            <StatCard label="Cloud findings" value={overview.cloudFindingsTotal} />
            <StatCard label="Integrations" value={overview.integrationsConnected} sub="connected" />
            <StatCard label="Framework" value={overview.frameworkName} sub="Type I & II" />
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
                <td className="p-3 text-[12px] text-gray-500">{c.category}</td>
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
        <TableWrap>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Task</Th><Th>Control</Th><Th>Status</Th></tr>
          </thead>
          <tbody>
            {tasks.map((t) => (
              <tr key={t.id} onClick={() => setSelected({ type: 'task', data: t })}
                  className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer">
                <td className="p-3 text-[13px] text-gray-900 font-medium">{t.title}</td>
                <td className="p-3 text-[12px] text-gray-500">{t.controlName || '—'}</td>
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
            {tasks.length === 0 && <EmptyRow cols={3} text="No tasks." />}
          </tbody>
        </TableWrap>
      )}

      {tab === 'personnel' && (
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
                <td className="p-3"><StatusPill text={p.active ? 'Active' : 'Inactive'} tone={p.active ? 'dark' : 'light'} /></td>
              </tr>
            ))}
            {personnel.length === 0 && <EmptyRow cols={5} text="No personnel." />}
          </tbody>
        </TableWrap>
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
              <tr><Th>Vendor</Th><Th>Category</Th><Th>Status</Th><Th>Description</Th></tr>
            </thead>
            <tbody>
              {vendors.map((v) => (
                <tr key={v.id} onClick={() => setSelected({ type: 'vendor', data: v })}
                    className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer">
                  <td className="p-3 text-[13px] text-gray-900 font-medium">{v.name}</td>
                  <td className="p-3 text-[12px] text-gray-500">{v.category || '—'}</td>
                  <td className="p-3"><StatusPill text={(v.status || '').replace(/_/g, ' ')} tone="mid" /></td>
                  <td className="p-3 text-[12px] text-gray-500 max-w-lg truncate">{v.description || '—'}</td>
                </tr>
              ))}
              {vendors.length === 0 && <EmptyRow cols={4} text="No vendors tracked yet." />}
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
                  <td className="p-3 text-[12px] text-gray-500">{r.category || '—'}</td>
                  <td className="p-3"><StatusPill text={r.severity || '—'} tone={(r.severity || '').toLowerCase() === 'high' || (r.severity || '').toLowerCase() === 'critical' ? 'dark' : 'mid'} /></td>
                  <td className="p-3"><StatusPill text={(r.status || '').replace(/_/g, ' ')} tone="mid" /></td>
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
                <td className="p-3"><StatusPill text={i.connected ? 'Connected' : 'Available'} tone={i.connected ? 'dark' : 'light'} /></td>
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
                <td className="p-3"><StatusPill text={f.severity || '—'} tone={(f.severity || '').toLowerCase() === 'high' || (f.severity || '').toLowerCase() === 'critical' ? 'dark' : 'mid'} /></td>
                <td className="p-3"><StatusPill text={(f.status || '').replace(/_/g, ' ')} tone="mid" /></td>
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
      {selected && (
        <DetailDrawer
          selected={selected}
          onClose={() => setSelected(null)}
          onPublishPolicy={handlePublish}
          onTaskStatusChange={handleTaskStatusChange}
          publishingId={publishingId}
          updatingTaskId={updatingTaskId}
        />
      )}
    </div>
  );
};

const ModalShell: React.FC<{ title: string; onClose: () => void; children: React.ReactNode }> = ({ title, onClose, children }) => (
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
);

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

const DrawerShell: React.FC<{ title: string; subtitle?: string; onClose: () => void; children: React.ReactNode }> = ({ title, subtitle, onClose, children }) => (
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
);

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
  onTaskStatusChange: (taskId: string, status: string) => void;
  publishingId: string | null;
  updatingTaskId: string | null;
}

const DetailDrawer: React.FC<DetailDrawerProps> = ({ selected, onClose, onPublishPolicy, onTaskStatusChange, publishingId, updatingTaskId }) => {
  const { type, data } = selected;

  if (type === 'control') {
    const c = data;
    return (
      <DrawerShell title={c.name} subtitle={c.category} onClose={onClose}>
        <DrawerSection label="Status">
          <span className={`px-2 py-0.5 rounded-full text-[11px] font-medium ${controlStatusClass(c.status)}`}>
            {CONTROL_STATUS_LABEL[c.status]}
          </span>
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
    return (
      <DrawerShell title={p.name} onClose={onClose}>
        <DrawerSection label="Status">
          <div className="flex items-center gap-3">
            <StatusPill text={(p.status || '').replace(/_/g, ' ')} tone={policyTone(p.status)} />
            {!isPublished && (
              <ActionButton onClick={() => onPublishPolicy(p.id)} disabled={publishingId === p.id}>
                {publishingId === p.id ? 'Publishing…' : 'Publish'}
              </ActionButton>
            )}
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
    return (
      <DrawerShell title={t.title} subtitle={t.controlName || undefined} onClose={onClose}>
        <DrawerSection label="Status">
          <select
            value={t.status || 'todo'}
            disabled={updatingTaskId === t.id}
            onChange={(e) => onTaskStatusChange(t.id, e.target.value)}
            className="text-[12px] font-medium rounded-md border border-gray-300 bg-white px-3 py-1.5 text-gray-700 disabled:opacity-40 capitalize"
          >
            {TASK_STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
          </select>
        </DrawerSection>
      </DrawerShell>
    );
  }

  if (type === 'person') {
    const p = data;
    return (
      <DrawerShell title={p.name} subtitle={p.email} onClose={onClose}>
        <DrawerSection label="Role"><p className="text-[13px] text-gray-700 capitalize">{p.role}</p></DrawerSection>
        <DrawerSection label="Department"><p className="text-[13px] text-gray-700 capitalize">{p.department || '—'}</p></DrawerSection>
        {p.jobTitle && <DrawerSection label="Job Title"><p className="text-[13px] text-gray-700">{p.jobTitle}</p></DrawerSection>}
        <DrawerSection label="Status"><StatusPill text={p.active ? 'Active' : 'Inactive'} tone={p.active ? 'dark' : 'light'} /></DrawerSection>
      </DrawerShell>
    );
  }

  if (type === 'vendor') {
    const v = data;
    return (
      <DrawerShell title={v.name} subtitle={v.category || undefined} onClose={onClose}>
        <DrawerSection label="Status"><StatusPill text={(v.status || '').replace(/_/g, ' ')} tone="mid" /></DrawerSection>
        {v.description && <DrawerSection label="Description"><p className="text-[13px] text-gray-700 whitespace-pre-line">{v.description}</p></DrawerSection>}
      </DrawerShell>
    );
  }

  if (type === 'risk') {
    const r = data;
    return (
      <DrawerShell title={r.title || 'Risk'} subtitle={r.category || undefined} onClose={onClose}>
        <DrawerSection label="Severity">
          <StatusPill text={r.severity || '—'} tone={(r.severity || '').toLowerCase() === 'high' || (r.severity || '').toLowerCase() === 'critical' ? 'dark' : 'mid'} />
        </DrawerSection>
        <DrawerSection label="Status"><StatusPill text={(r.status || '').replace(/_/g, ' ')} tone="mid" /></DrawerSection>
        <DrawerSection label="Owner"><p className="text-[13px] text-gray-700">{r.owner || '—'}</p></DrawerSection>
        {r.description && <DrawerSection label="Description"><p className="text-[13px] text-gray-700 whitespace-pre-line">{r.description}</p></DrawerSection>}
      </DrawerShell>
    );
  }

  if (type === 'integration') {
    const i = data;
    return (
      <DrawerShell title={i.name} subtitle={i.category || undefined} onClose={onClose}>
        <DrawerSection label="Status"><StatusPill text={i.connected ? 'Connected' : 'Available'} tone={i.connected ? 'dark' : 'light'} /></DrawerSection>
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
        <StatusPill text={f.severity || '—'} tone={(f.severity || '').toLowerCase() === 'high' || (f.severity || '').toLowerCase() === 'critical' ? 'dark' : 'mid'} />
      </DrawerSection>
      <DrawerSection label="Status"><StatusPill text={(f.status || '').replace(/_/g, ' ')} tone="mid" /></DrawerSection>
      <DrawerSection label="Resource"><p className="text-[13px] text-gray-700 break-all">{f.resource || '—'}</p></DrawerSection>
    </DrawerShell>
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
