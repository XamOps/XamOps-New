import React, { useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { useTopbar } from '../contexts/TopbarContext';
import { useSoc2ProgramData } from '../hooks/useSoc2ProgramData';
import type { Soc2ControlStatus } from '../api/soc2Api';

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

const Soc2ProgramPage: React.FC = () => {
  const { tab = 'overview' } = useParams<{ tab: string }>();
  const navigate = useNavigate();
  const { setTitle, setActions, clearActions } = useTopbar();
  const { overview, controls, policies, tasks, personnel, vendors, risks, cloudTests, integrations, isLoading, error } = useSoc2ProgramData();

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
              <tr key={c.id} className="border-t border-gray-100 hover:bg-gray-50/60">
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
            <tr><Th>Policy</Th><Th>Status</Th><Th>Description</Th></tr>
          </thead>
          <tbody>
            {policies.map((p) => (
              <tr key={p.id} className="border-t border-gray-100 hover:bg-gray-50/60">
                <td className="p-3 text-[13px] text-gray-900 font-medium">{p.name}</td>
                <td className="p-3"><StatusPill text={(p.status || '').replace(/_/g, ' ')} tone={policyTone(p.status)} /></td>
                <td className="p-3 text-[12px] text-gray-500 max-w-lg truncate">{p.description || '—'}</td>
              </tr>
            ))}
            {policies.length === 0 && <EmptyRow cols={3} text="No policies." />}
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
              <tr key={t.id} className="border-t border-gray-100 hover:bg-gray-50/60">
                <td className="p-3 text-[13px] text-gray-900 font-medium">{t.title}</td>
                <td className="p-3 text-[12px] text-gray-500">{t.controlName || '—'}</td>
                <td className="p-3"><StatusPill text={(t.status || '').replace(/_/g, ' ')} tone={(t.status || '').toLowerCase() === 'done' ? 'dark' : (t.status || '').toLowerCase() === 'in_progress' ? 'mid' : 'light'} /></td>
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
              <tr key={p.id} className="border-t border-gray-100 hover:bg-gray-50/60">
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
        <TableWrap>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Vendor</Th><Th>Category</Th><Th>Status</Th><Th>Description</Th></tr>
          </thead>
          <tbody>
            {vendors.map((v) => (
              <tr key={v.id} className="border-t border-gray-100 hover:bg-gray-50/60">
                <td className="p-3 text-[13px] text-gray-900 font-medium">{v.name}</td>
                <td className="p-3 text-[12px] text-gray-500">{v.category || '—'}</td>
                <td className="p-3"><StatusPill text={(v.status || '').replace(/_/g, ' ')} tone="mid" /></td>
                <td className="p-3 text-[12px] text-gray-500 max-w-lg truncate">{v.description || '—'}</td>
              </tr>
            ))}
            {vendors.length === 0 && <EmptyRow cols={4} text="No vendors tracked yet." />}
          </tbody>
        </TableWrap>
      )}

      {tab === 'risks' && (
        <TableWrap>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Risk</Th><Th>Category</Th><Th>Severity</Th><Th>Status</Th><Th>Owner</Th></tr>
          </thead>
          <tbody>
            {risks.map((r) => (
              <tr key={r.id} className="border-t border-gray-100 hover:bg-gray-50/60">
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
      )}

      {tab === 'integrations' && (
        <TableWrap>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Integration</Th><Th>Category</Th><Th>Capabilities</Th><Th>Status</Th></tr>
          </thead>
          <tbody>
            {integrations.map((i) => (
              <tr key={i.id} className="border-t border-gray-100 hover:bg-gray-50/60">
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
              <tr key={f.id} className="border-t border-gray-100 hover:bg-gray-50/60">
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
