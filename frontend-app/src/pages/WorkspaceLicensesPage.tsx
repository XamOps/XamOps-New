import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Icon } from '@iconify/react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell,
  PieChart, Pie, Legend,
} from 'recharts';
import { useTopbar, useTopbarActions } from '../contexts/TopbarContext';
import {
  useWorkspaceStatus,
  useWorkspaceUsers,
  useWorkspaceLicenseSummary,
  useWorkspaceOptimization,
  connectWorkspace,
  syncWorkspace,
  disconnectWorkspace,
} from '../hooks/useWorkspaceLicenses';
import type { WorkspaceTab, UserStatusFilter } from '../types/workspace';
import { useTabState } from '../hooks/useTabState';

const StatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const map: Record<string, string> = {
    ACTIVE: 'bg-green-100 text-green-700',
    INACTIVE: 'bg-amber-100 text-amber-700',
    SUSPENDED: 'bg-red-100 text-red-700',
  };
  return (
    <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${map[status] ?? 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)]'}`}>
      {status}
    </span>
  );
};

const REQUIRED_SCOPE = 'https://www.googleapis.com/auth/admin.directory.user.readonly';

const SetupGuide: React.FC = () => {
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);

  const copyScope = () => {
    navigator.clipboard.writeText(REQUIRED_SCOPE).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  const steps: { icon: string; title: string; body: React.ReactNode }[] = [
    {
      icon: 'mdi:graph-outline',
      title: 'Create or select a GCP Project & enable the Admin SDK',
      body: (
        <ol className="list-decimal pl-5 space-y-1 text-sm text-[rgba(0,0,0,0.6)]">
          <li>Go to <strong>console.cloud.google.com</strong> and sign in with a Google account that belongs to your Workspace domain.</li>
          <li>Create a new project (or select an existing one).</li>
          <li>Navigate to <strong>APIs &amp; Services → Library</strong>, search for <em>"Admin SDK API"</em> and click <strong>Enable</strong>.</li>
        </ol>
      ),
    },
    {
      icon: 'mdi:robot-outline',
      title: 'Create a Service Account',
      body: (
        <ol className="list-decimal pl-5 space-y-1 text-sm text-[rgba(0,0,0,0.6)]">
          <li>In the GCP Console go to <strong>IAM &amp; Admin → Service Accounts</strong>.</li>
          <li>Click <strong>Create Service Account</strong>, give it a name (e.g. <em>xamops-workspace</em>), then click <strong>Done</strong>.</li>
          <li>No project-level IAM role is needed — leave those steps blank.</li>
        </ol>
      ),
    },
    {
      icon: 'mdi:key-outline',
      title: 'Enable Domain-Wide Delegation on the service account',
      body: (
        <ol className="list-decimal pl-5 space-y-1 text-sm text-[rgba(0,0,0,0.6)]">
          <li>Click on the service account you just created to open its detail page.</li>
          <li>Open the <strong>Advanced settings</strong> section (or scroll to "Domain-wide delegation").</li>
          <li>Check <strong>"Enable Google Workspace Domain-wide Delegation"</strong> and save.</li>
          <li><strong>Copy the numeric Client ID</strong> shown — you will need it in Step 5.</li>
        </ol>
      ),
    },
    {
      icon: 'mdi:download',
      title: 'Download the JSON key file',
      body: (
        <>
          <ol className="list-decimal pl-5 space-y-1 text-sm text-[rgba(0,0,0,0.6)] mb-3">
            <li>On the service account page, go to the <strong>Keys</strong> tab.</li>
            <li>Click <strong>Add Key → Create new key</strong>, choose <strong>JSON</strong>, then click <strong>Create</strong>.</li>
            <li>A <code className="bg-[rgba(0,0,0,0.04)] px-1 rounded text-xs">.json</code> file will download automatically — this is what you upload/paste in the form below.</li>
          </ol>
          <div className="flex items-start gap-2 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
            <Icon icon="mdi:alert-outline" width={16} className="mt-0.5 flex-shrink-0" />
            <p>
              <strong>Keep this file private.</strong> It contains a private key that grants admin-level read access to your Workspace directory.
              Never commit it to source control, share it in chat, or expose it publicly.
            </p>
          </div>
        </>
      ),
    },
    {
      icon: 'mdi:shield-outline',
      title: 'Authorise the scope in Google Workspace Admin Console',
      body: (
        <>
          <ol className="list-decimal pl-5 space-y-1 text-sm text-[rgba(0,0,0,0.6)] mb-3">
            <li>Open a new tab and go to <strong>admin.google.com</strong> — sign in as a Super Admin.</li>
            <li>Navigate to <strong>Security → Access and data control → API controls</strong>.</li>
            <li>Click <strong>Manage Domain-Wide Delegation</strong>, then <strong>Add new</strong>.</li>
            <li>Paste the <strong>Client ID</strong> you copied in Step 3.</li>
            <li>In the <em>OAuth scopes</em> field paste the scope below, then click <strong>Authorise</strong>.</li>
          </ol>
          <div className="flex items-center gap-2 bg-[rgba(0,0,0,0.04)] rounded-lg px-3 py-2">
            <code className="flex-1 text-xs text-blue-700 break-all select-all font-mono">{REQUIRED_SCOPE}</code>
            <button
              onClick={copyScope}
              className="flex-shrink-0 px-2 py-1 bg-white border border-[rgba(0,0,0,0.12)] rounded text-xs font-medium text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.025)] transition flex items-center gap-1"
            >
              <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={12} className={copied ? 'text-green-500' : ''} />
              {copied ? 'Copied!' : 'Copy'}
            </button>
          </div>
        </>
      ),
    },
    {
      icon: 'mdi:power-plug-outline',
      title: 'Fill in the form below and connect',
      body: (
        <ul className="list-disc pl-5 space-y-1 text-sm text-[rgba(0,0,0,0.6)]">
          <li><strong>Workspace Domain</strong> — your primary Google Workspace domain (e.g. <em>company.com</em>).</li>
          <li><strong>Admin Email</strong> — the Super Admin email that the service account will impersonate (e.g. <em>admin@company.com</em>).</li>
          <li><strong>Service Account Key</strong> — upload or paste the JSON file you downloaded in Step 4.</li>
        </ul>
      ),
    },
  ];

  return (
    <div className="mb-6 border border-[rgba(0,0,0,0.12)] rounded-xl overflow-hidden">
      <button
        onClick={() => setOpen((o) => !o)}
        className="w-full flex items-center justify-between px-5 py-4 bg-[rgba(0,0,0,0.02)] hover:bg-[rgba(0,0,0,0.04)] transition text-left rounded-t-xl"
      >
        <div className="flex items-center gap-3">
          <Icon icon="mdi:book-open-outline" width={16} className="text-[rgba(0,0,0,0.5)]" />
          <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">How to set up the service account — step-by-step guide</span>
        </div>
        <Icon icon={open ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={16} className="text-[rgba(0,0,0,0.4)]" />
      </button>

      {open && (
        <div className="bg-white border-t border-[rgba(0,0,0,0.06)] p-6">
          <p className="text-sm text-[rgba(0,0,0,0.5)] mb-6">
            XamOps uses a <strong>service account with Domain-Wide Delegation</strong> to read your Google Workspace
            directory without requiring per-user OAuth. Follow these steps once in your GCP Console and Google Workspace
            Admin Console.
          </p>

          <div className="space-y-5">
            {steps.map((step, idx) => (
              <div key={idx} className="flex gap-4">
                <div className="flex flex-col items-center flex-shrink-0">
                  <div className="w-7 h-7 rounded-full bg-[rgba(0,0,0,0.06)] flex items-center justify-center text-[rgba(0,0,0,0.6)] font-bold text-xs">
                    {idx + 1}
                  </div>
                  {idx < steps.length - 1 && (
                    <div className="w-0.5 flex-1 bg-[rgba(0,0,0,0.06)] mt-1" />
                  )}
                </div>

                <div className="flex-1 pb-5">
                  <div className="flex items-center gap-2 mb-2">
                    <Icon icon={step.icon} width={14} className="text-[rgba(0,0,0,0.4)]" />
                    <h4 className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">{step.title}</h4>
                  </div>
                  {step.body}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

const ConnectionPanel: React.FC<{ onConnected: () => void }> = ({ onConnected }) => {
  const [domain, setDomain] = useState('');
  const [adminEmail, setAdminEmail] = useState('');
  const [serviceAccountKey, setServiceAccountKey] = useState('');
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => setServiceAccountKey(ev.target?.result as string ?? '');
    reader.readAsText(file);
  };

  const handleConnect = async () => {
    if (!domain.trim() || !adminEmail.trim() || !serviceAccountKey.trim()) {
      setError('All fields are required.');
      return;
    }
    setConnecting(true);
    setError(null);
    try {
      await connectWorkspace({ domain: domain.trim(), adminEmail: adminEmail.trim(), serviceAccountKey });
      onConnected();
    } catch (e: any) {
      setError(e.message ?? 'Connection failed. Check your service account key and permissions.');
    } finally {
      setConnecting(false);
    }
  };

  return (
    <div className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen flex items-start justify-center">
      <div className="max-w-4xl w-full">
        <div className="text-center mb-10">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-xl bg-indigo-50 border border-indigo-100 mb-6">
            <Icon icon="mdi:google" width={32} className="text-indigo-600" />
          </div>
          <h2 className="text-[22px] font-bold text-[rgba(0,0,0,0.88)] mb-2">Connect Google Workspace</h2>
          <p className="text-[14px] text-[rgba(0,0,0,0.5)] max-w-xl mx-auto">
            Link your organisation's Google Workspace to track seat usage, identify inactive accounts,
            and optimise licence costs — all from one place.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-10">
          {[
            { icon: 'mdi:account-group-outline', title: 'Seat Visibility', desc: 'See every user, their last login, and seat allocation in real time.' },
            { icon: 'mdi:lightbulb-outline', title: 'Smart Optimisation', desc: 'Auto-detect inactive and never-logged-in users to reclaim unused seats.' },
            { icon: 'mdi:chart-bar', title: 'Cost Insights', desc: 'Understand utilisation rates and quantify licence waste instantly.' },
          ].map((b) => (
            <div key={b.title} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 flex gap-4 shadow-sm hover:shadow-md">
              <div className="w-10 h-10 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center flex-shrink-0">
                <Icon icon={b.icon} width={20} className="text-indigo-600" />
              </div>
              <div>
                <p className="font-semibold text-[rgba(0,0,0,0.88)]">{b.title}</p>
                <p className="text-sm text-[rgba(0,0,0,0.5)] mt-1">{b.desc}</p>
              </div>
            </div>
          ))}
        </div>

        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-8">
          <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-5 flex items-center gap-2">
            <Icon icon="mdi:power-plug-outline" width={16} className="text-[rgba(0,0,0,0.4)]" /> Connection Setup
          </h3>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-5 mb-5">
            <div>
              <label className="text-[12px] font-medium text-[rgba(0,0,0,0.6)] mb-1.5 block">
                Workspace Domain <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                placeholder="company.com"
                value={domain}
                onChange={(e) => setDomain(e.target.value)}
                className="w-full border border-[rgba(0,0,0,0.12)] rounded-lg px-4 py-2.5 text-[13px] text-[rgba(0,0,0,0.7)] focus:ring-2 focus:ring-indigo-300 focus:border-transparent outline-none"
              />
            </div>
            <div>
              <label className="text-[12px] font-medium text-[rgba(0,0,0,0.6)] mb-1.5 block">
                Admin Email (for impersonation) <span className="text-red-500">*</span>
              </label>
              <input
                type="email"
                placeholder="admin@company.com"
                value={adminEmail}
                onChange={(e) => setAdminEmail(e.target.value)}
                className="w-full border border-[rgba(0,0,0,0.12)] rounded-lg px-4 py-2.5 text-[13px] text-[rgba(0,0,0,0.7)] focus:ring-2 focus:ring-indigo-300 focus:border-transparent outline-none"
              />
            </div>
          </div>

          <div className="mb-6">
            <label className="text-[12px] font-medium text-[rgba(0,0,0,0.6)] mb-1.5 block">
              Service Account Key (JSON) <span className="text-red-500">*</span>
            </label>
            <div className="flex gap-3 mb-2">
              <button
                onClick={() => fileRef.current?.click()}
                className="px-4 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] font-medium text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)] transition flex items-center gap-2"
              >
                <Icon icon="mdi:upload" width={14} /> Upload JSON file
              </button>
              <input ref={fileRef} type="file" accept=".json" onChange={handleFileUpload} className="hidden" />
              {serviceAccountKey && (
                <span className="flex items-center gap-1.5 text-sm text-green-600 font-medium">
                  <Icon icon="mdi:check-circle-outline" width={14} /> File loaded
                </span>
              )}
            </div>
            <textarea
              placeholder={'Paste service account JSON here…\n{\n  "type": "service_account",\n  "project_id": "...",\n  ...\n}'}
              value={serviceAccountKey}
              onChange={(e) => setServiceAccountKey(e.target.value)}
              rows={6}
              className="w-full border border-[rgba(0,0,0,0.12)] rounded-lg px-4 py-3 text-xs font-mono focus:ring-2 focus:ring-indigo-300 focus:border-transparent outline-none resize-none"
            />
          </div>

          {error && (
            <div className="mb-4 p-4 bg-red-50 border border-red-100 rounded-xl flex gap-3">
              <Icon icon="mdi:alert-circle-outline" width={16} className="text-red-500 mt-0.5 flex-shrink-0" />
              <p className="text-sm text-red-700">{error}</p>
            </div>
          )}

          <SetupGuide />

          <button
            onClick={handleConnect}
            disabled={connecting}
            className="w-full py-2.5 bg-[rgba(0,0,0,0.88)] text-white rounded-xl text-[13px] font-semibold hover:bg-[rgba(0,0,0,0.75)] transition disabled:opacity-60 flex items-center justify-center gap-2"
          >
            {connecting
              ? <><Icon icon="mdi:loading" width={16} className="animate-spin" /> Connecting…</>
              : <><Icon icon="mdi:google" width={16} /> Connect Google Workspace</>}
          </button>
        </div>
      </div>
    </div>
  );
};

const SavingsEstimator: React.FC<{ inactiveSeats: number }> = ({ inactiveSeats }) => {
  const [costPerSeat, setCostPerSeat] = useState<string>('12');
  const monthly = parseFloat(costPerSeat || '0') * inactiveSeats;
  const annual  = monthly * 12;

  return (
    <div className="flex flex-wrap items-end gap-6">
      <div>
        <label className="text-[11px] text-[rgba(0,0,0,0.4)] mb-1 block">Monthly cost per seat ($)</label>
        <input
          type="number"
          min="0"
          step="0.01"
          value={costPerSeat}
          onChange={(e) => setCostPerSeat(e.target.value)}
          className="w-36 border border-[rgba(0,0,0,0.12)] rounded-lg px-3 py-2 text-[13px] text-[rgba(0,0,0,0.7)] focus:ring-2 focus:ring-indigo-300 focus:border-transparent outline-none"
        />
      </div>
      <div className="flex gap-6">
        <div>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-1">Reclaimable seats</p>
          <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '28px', fontWeight: 400, lineHeight: '28px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{inactiveSeats}</p>
        </div>
        <div>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-1">Monthly saving</p>
          <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '28px', fontWeight: 400, lineHeight: '28px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>${monthly.toFixed(2)}</p>
        </div>
        <div>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-1">Annual saving</p>
          <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '28px', fontWeight: 400, lineHeight: '28px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>${annual.toFixed(2)}</p>
        </div>
      </div>
    </div>
  );
};

const ConnectedDashboard: React.FC<{ onDisconnected: () => void; onRefresh: () => void }> = ({
  onDisconnected, onRefresh,
}) => {
  const [activeTab, setActiveTab] = useTabState<WorkspaceTab>('overview');
  const [statusFilter, setStatusFilter] = useState<UserStatusFilter>('ALL');
  const [inactiveDays, setInactiveDays] = useState(30);
  const [userSearch, setUserSearch] = useState('');
  const [syncing, setSyncing] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const { status, refetch: refetchStatus } = useWorkspaceStatus();
  const { users, loading: usersLoading } = useWorkspaceUsers(true, 'ALL');
  const { summary, loading: summaryLoading } = useWorkspaceLicenseSummary(true);
  const { items: optimItems, loading: optimLoading } = useWorkspaceOptimization(true, inactiveDays);
  const { setTitle, setActions, clearActions } = useTopbarActions();

  const handleSync = useCallback(async () => {
    setSyncing(true);
    setActionError(null);
    try {
      await syncWorkspace();
      await refetchStatus();
      onRefresh();
    } catch (e: any) {
      setActionError(e.message ?? 'Sync failed');
    } finally {
      setSyncing(false);
    }
  }, [refetchStatus, onRefresh]);

  const handleDisconnect = useCallback(async () => {
    if (!window.confirm('Disconnect Google Workspace? All cached user data will be removed.')) return;
    setDisconnecting(true);
    try {
      await disconnectWorkspace();
      onDisconnected();
    } catch (e: any) {
      setActionError(e.message ?? 'Disconnect failed');
      setDisconnecting(false);
    }
  }, [onDisconnected]);

  const filteredUsers = users.filter((u) => {
    const matchSearch = !userSearch ||
      u.email.toLowerCase().includes(userSearch.toLowerCase()) ||
      u.fullName.toLowerCase().includes(userSearch.toLowerCase());
    const matchStatus = statusFilter === 'ALL' || u.status === statusFilter;
    return matchSearch && matchStatus;
  });

  const tabs: { id: WorkspaceTab; label: string; icon: string }[] = [
    { id: 'overview',     label: 'Overview',      icon: 'mdi:chart-donut' },
    { id: 'users',        label: 'Users & Seats', icon: 'mdi:account-group-outline' },
    { id: 'optimization', label: 'Optimisation',  icon: 'mdi:lightbulb-outline' },
    { id: 'cost',         label: 'Cost & Savings', icon: 'mdi:currency-usd' },
  ];

  const lastSync = status?.lastSyncedAt
    ? new Date(status.lastSyncedAt).toLocaleString()
    : 'Never';

  const utilisationData = summary
    ? [
        { name: 'Active',    value: summary.activeSeats,    fill: '#22c55e' },
        { name: 'Inactive',  value: summary.inactiveSeats,  fill: '#f59e0b' },
        { name: 'Suspended', value: summary.suspendedSeats, fill: '#ef4444' },
      ]
    : [];

  const optimSeverity: Record<string, { label: string; color: string; icon: string }> = {
    INACTIVE_USER:          { label: 'Inactive User',         color: 'bg-amber-50 text-amber-700 border border-amber-200',   icon: 'mdi:clock-outline'      },
    SUSPENDED_WITH_LICENSE: { label: 'Suspended (with seat)', color: 'bg-red-50 text-red-700 border border-red-100',         icon: 'mdi:cancel'             },
    NEVER_LOGGED_IN:        { label: 'Never Logged In',       color: 'bg-purple-50 text-purple-700 border border-purple-100',icon: 'mdi:account-cancel-outline' },
  };

  useEffect(() => {
    setTitle('Workspace Licenses');
    setActions(
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 bg-indigo-600 rounded-lg flex items-center justify-center flex-shrink-0">
            <Icon icon="mdi:google" width={14} className="text-white" />
          </div>
          <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)]">{status?.domain}</span>
          <span className="px-2 py-0.5 bg-[#F0FDF4] border border-[#D1FAE1] text-[#158045] text-[10px] font-semibold rounded-full">Connected</span>
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">Last synced: {lastSync}</span>
        </div>
        <button
          onClick={handleSync}
          disabled={syncing}
          className="flex items-center gap-2 px-3 py-1.5 rounded-md text-[12px] font-medium bg-[rgba(0,0,0,0.88)] text-white hover:bg-[rgba(0,0,0,0.75)] transition-all disabled:opacity-60"
        >
          <Icon icon="mdi:refresh" width={14} className={syncing ? 'animate-spin' : ''} />
          {syncing ? 'Syncing…' : 'Sync Now'}
        </button>
        <button
          onClick={handleDisconnect}
          disabled={disconnecting}
          className="flex items-center gap-2 px-3 py-1.5 rounded-md text-[12px] font-medium border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:text-red-600 hover:border-red-200 transition-all disabled:opacity-60"
        >
          <Icon icon="mdi:link-variant-off" width={14} />
          Disconnect
        </button>
      </div>
    );
    return () => {
      clearActions();
      setTitle(null);
    };
  }, [setTitle, setActions, clearActions, status?.domain, lastSync, handleSync, handleDisconnect, syncing, disconnecting]);

  return (
    <div className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen space-y-5">

      {actionError && (
        <div className="p-3 bg-red-50 border border-red-100 rounded-xl flex gap-2 text-sm text-red-700">
          <Icon icon="mdi:alert-circle-outline" width={16} className="mt-0.5 flex-shrink-0" />{actionError}
          <button onClick={() => setActionError(null)} className="ml-auto text-red-400 hover:text-red-600">
            <Icon icon="mdi:close" width={14} />
          </button>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        {[
          { label: 'Total Users', value: status?.totalUsers ?? '—', sub: 'Workspace accounts' },
          { label: 'Active', value: status?.activeUsers ?? '—', sub: 'Logged in recently' },
          { label: 'Inactive', value: status?.inactiveUsers ?? '—', sub: `No login in >${inactiveDays} days` },
          { label: 'Suspended', value: status?.suspendedUsers ?? '—', sub: 'Access suspended' },
        ].map(({ label, value, sub }) => (
          <div key={label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
            <div className="absolute top-0 right-0 w-full h-full pointer-events-none opacity-[0.07]">
              <svg className="absolute -top-8 -right-12 w-32 h-32" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
                <path d="M140 70c0-15-12-27-27-27-3 0-6 1-9 2-6-14-20-24-36-24-17 0-31 10-36 24-3-1-6-2-9-2-15 0-27 12-27 27s12 27 27 27h117c15 0 27-12 27-27z"/>
              </svg>
              <svg className="absolute top-16 right-2 w-24 h-24" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
                <path d="M120 60c0-10-8-18-18-18-2 0-4 0-5 1-4-8-13-14-23-14s-19 6-23 14c-1-1-3-1-5-1-10 0-18 8-18 18s8 18 18 18h74c10 0 18-8 18-18z"/>
              </svg>
            </div>
            <div className="relative z-10">
              <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">{label}</p>
              <p className="leading-8 mb-2">
                <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
                  {value}
                </span>
              </p>
              <p className="text-[11px] text-[rgba(0,0,0,0.35)]">{sub}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="flex items-center gap-5 border-b border-[rgba(0,0,0,0.06)]">
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => setActiveTab(t.id)}
            className={`flex items-center gap-1.5 px-0.5 py-2 text-[12px] font-medium transition-all border-b-2 -mb-px ${
              activeTab === t.id
                ? 'text-[rgba(0,0,0,0.88)] border-[rgba(0,0,0,0.88)]'
                : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] border-transparent'
            }`}
          >
            <Icon icon={t.icon} width={13} />{t.label}
          </button>
        ))}
      </div>

      {activeTab === 'overview' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
            <div className="flex items-center gap-2 mb-4">
              <div className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse" />
              <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Seat Utilisation</p>
            </div>
            {summaryLoading
              ? (
                <div className="flex items-center justify-center py-12 gap-2 text-[rgba(0,0,0,0.4)]">
                  <Icon icon="mdi:loading" width={16} className="animate-spin" />
                  <span className="text-sm">Loading…</span>
                </div>
              )
              : summary && (
                <div className="flex flex-col items-center">
                  <ResponsiveContainer width="100%" height={220}>
                    <PieChart>
                      <Pie data={utilisationData} cx="50%" cy="50%" innerRadius={60} outerRadius={90} paddingAngle={3} dataKey="value">
                        {utilisationData.map((entry, i) => <Cell key={i} fill={entry.fill} />)}
                      </Pie>
                      <Tooltip formatter={(v) => `${v} seats`} />
                      <Legend />
                    </PieChart>
                  </ResponsiveContainer>
                  <div className="mt-4 text-center">
                    <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '36px', fontWeight: 400, lineHeight: '36px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{summary.utilizationPercent}%</span>
                    <p className="text-sm text-[rgba(0,0,0,0.4)]">Utilisation rate</p>
                  </div>
                </div>
              )
            }
          </div>

          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 flex flex-col gap-4">
            <div className="flex items-center gap-2 mb-0">
              <div className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse" />
              <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Seat Breakdown</p>
            </div>
            {summaryLoading
              ? (
                <div className="flex items-center justify-center py-12 gap-2 text-[rgba(0,0,0,0.4)]">
                  <Icon icon="mdi:loading" width={16} className="animate-spin" />
                  <span className="text-sm">Loading…</span>
                </div>
              )
              : summary && [
                  { label: 'Active seats',    value: summary.activeSeats,    color: 'bg-green-500'  },
                  { label: 'Inactive seats',  value: summary.inactiveSeats,  color: 'bg-amber-500'  },
                  { label: 'Suspended seats', value: summary.suspendedSeats, color: 'bg-red-500'    },
                  { label: 'Never logged in', value: summary.neverLoggedIn,  color: 'bg-purple-500' },
                  { label: 'Total seats',     value: summary.totalSeats,     color: 'bg-blue-500'   },
                ].map(({ label, value, color }) => (
                  <div key={label} className="flex items-center justify-between border-b border-[rgba(0,0,0,0.04)] last:border-0 py-2.5">
                    <div className="flex items-center gap-2">
                      <div className={`w-2 h-2 rounded-full ${color}`} />
                      <span className="text-[13px] text-[rgba(0,0,0,0.5)]">{label}</span>
                    </div>
                    <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{value}</span>
                  </div>
                ))
            }
          </div>

          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 lg:col-span-2">
            <div className="flex items-center gap-2 mb-4">
              <div className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse" />
              <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Top Optimisation Opportunities</p>
            </div>
            {optimLoading
              ? (
                <div className="flex items-center justify-center py-12 gap-2 text-[rgba(0,0,0,0.4)]">
                  <Icon icon="mdi:loading" width={16} className="animate-spin" />
                  <span className="text-sm">Loading…</span>
                </div>
              )
              : optimItems.length === 0
                ? <p className="text-sm text-[rgba(0,0,0,0.4)] text-center py-4">No issues detected — great job!</p>
                : (
                  <div className="space-y-2">
                    {optimItems.slice(0, 5).map((item, i) => {
                      const sev = optimSeverity[item.type] ?? { label: item.type, color: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)]', icon: 'mdi:account-outline' };
                      return (
                        <div key={i} className="flex items-center gap-3 p-3 rounded-lg bg-[rgba(0,0,0,0.02)] hover:bg-[rgba(0,0,0,0.04)]">
                          <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${sev.color} flex-shrink-0 flex items-center gap-1`}>
                            <Icon icon={sev.icon} width={11} />{sev.label}
                          </span>
                          <div className="flex-1 min-w-0">
                            <p className="text-sm font-medium text-[rgba(0,0,0,0.88)] truncate">{item.fullName || item.email}</p>
                            <p className="text-xs text-[rgba(0,0,0,0.4)] truncate">{item.email}</p>
                          </div>
                          <span className="text-sm text-[rgba(0,0,0,0.5)] flex-shrink-0">
                            {item.daysSinceLogin != null ? `${item.daysSinceLogin}d` : 'Never'}
                          </span>
                        </div>
                      );
                    })}
                    {optimItems.length > 5 && (
                      <button
                        onClick={() => setActiveTab('optimization')}
                        className="w-full text-center text-sm text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] font-medium pt-1 transition-colors"
                      >
                        View all {optimItems.length} recommendations →
                      </button>
                    )}
                  </div>
                )
            }
          </div>
        </div>
      )}

      {activeTab === 'users' && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
          <div className="flex flex-wrap items-center gap-3 mb-5">
            <div className="relative flex-1 min-w-48">
              <Icon icon="mdi:magnify" width={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.4)]" />
              <input
                type="text"
                placeholder="Search by name or email…"
                value={userSearch}
                onChange={(e) => setUserSearch(e.target.value)}
                className="w-full pl-9 pr-4 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.7)] focus:ring-2 focus:ring-indigo-300 focus:border-transparent outline-none"
              />
            </div>
            <div className="flex gap-1">
              {(['ALL', 'ACTIVE', 'INACTIVE', 'SUSPENDED'] as UserStatusFilter[]).map((s) => (
                <button
                  key={s}
                  onClick={() => setStatusFilter(s)}
                  className={`px-3 py-1 rounded-lg text-xs font-semibold transition-all ${
                    statusFilter === s ? 'bg-[rgba(0,0,0,0.88)] text-white' : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.07)]'
                  }`}
                >
                  {s}
                </button>
              ))}
            </div>
            <span className="text-sm text-[rgba(0,0,0,0.4)]">{filteredUsers.length} user{filteredUsers.length !== 1 ? 's' : ''}</span>
          </div>

          {usersLoading
            ? (
              <div className="flex items-center justify-center py-12 gap-2 text-[rgba(0,0,0,0.4)]">
                <Icon icon="mdi:loading" width={16} className="animate-spin" />
                <span className="text-sm">Loading…</span>
              </div>
            )
            : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                    <tr>
                      <th className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">User</th>
                      <th className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Org Unit</th>
                      <th className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Status</th>
                      <th className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Last Login</th>
                      <th className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Days Inactive</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredUsers.length === 0 && (
                      <tr><td colSpan={5} className="text-center py-10 text-[rgba(0,0,0,0.4)]">No users found</td></tr>
                    )}
                    {filteredUsers.map((u) => (
                      <tr key={u.email} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                        <td className="py-3 px-4">
                          <div className="flex items-center gap-2">
                            <div className="w-7 h-7 rounded-full bg-indigo-600 flex items-center justify-center text-white text-xs font-bold flex-shrink-0">
                              {(u.fullName || u.email).charAt(0).toUpperCase()}
                            </div>
                            <div>
                              <p className="font-medium text-[rgba(0,0,0,0.88)] leading-tight">{u.fullName || '—'}</p>
                              <p className="text-xs text-[rgba(0,0,0,0.4)]">{u.email}</p>
                            </div>
                            {u.isAdmin && (
                              <span className="px-1.5 py-0.5 bg-blue-100 text-blue-600 text-xs rounded">Admin</span>
                            )}
                          </div>
                        </td>
                        <td className="py-3 px-4 text-[rgba(0,0,0,0.5)] text-xs">{u.orgUnit || '/'}</td>
                        <td className="py-3 px-4"><StatusBadge status={u.status} /></td>
                        <td className="py-3 px-4 text-[rgba(0,0,0,0.5)]">
                          {u.lastLoginTime
                            ? new Date(u.lastLoginTime).toLocaleDateString()
                            : <span className="text-[rgba(0,0,0,0.2)]">Never</span>}
                        </td>
                        <td className="py-3 px-4">
                          {u.daysSinceLogin != null
                            ? <span className={u.daysSinceLogin >= 30 ? 'text-amber-600 font-semibold' : 'text-[rgba(0,0,0,0.6)]'}>{u.daysSinceLogin}d</span>
                            : <span className="text-purple-500 font-semibold">Never</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
          }
        </div>
      )}

      {activeTab === 'optimization' && (
        <div className="space-y-4">
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-4 flex flex-wrap items-center gap-4">
            <span className="text-sm font-medium text-[rgba(0,0,0,0.7)]">Inactivity threshold:</span>
            <div className="flex gap-2">
              {[7, 14, 30, 60, 90].map((d) => (
                <button
                  key={d}
                  onClick={() => setInactiveDays(d)}
                  className={`px-3 py-1 rounded-lg text-xs font-semibold transition-all ${
                    inactiveDays === d ? 'bg-[rgba(0,0,0,0.88)] text-white' : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.07)]'
                  }`}
                >
                  {d} days
                </button>
              ))}
            </div>
            {!optimLoading && (
              <span className="text-sm text-[rgba(0,0,0,0.4)] ml-auto">
                {optimItems.length} recommendation{optimItems.length !== 1 ? 's' : ''}
              </span>
            )}
          </div>

          {optimLoading
            ? (
              <div className="flex items-center justify-center py-12 gap-2 text-[rgba(0,0,0,0.4)]">
                <Icon icon="mdi:loading" width={16} className="animate-spin" />
                <span className="text-sm">Loading…</span>
              </div>
            )
            : optimItems.length === 0
              ? (
                <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-12 text-center">
                  <Icon icon="mdi:check-circle-outline" width={40} className="text-green-400 mx-auto mb-4" />
                  <p className="text-lg font-semibold text-[rgba(0,0,0,0.7)]">All seats are optimally used!</p>
                  <p className="text-sm text-[rgba(0,0,0,0.4)] mt-2">No inactive or problematic accounts detected.</p>
                </div>
              )
              : (
                <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
                  <table className="w-full text-sm">
                    <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                      <tr>
                        <th className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Type</th>
                        <th className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">User</th>
                        <th className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Org Unit</th>
                        <th className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Inactive Days</th>
                        <th className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.35)]">Recommendation</th>
                      </tr>
                    </thead>
                    <tbody>
                      {optimItems.map((item, i) => {
                        const sev = optimSeverity[item.type] ?? { label: item.type, color: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)]', icon: 'mdi:account-outline' };
                        return (
                          <tr key={i} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                            <td className="py-3 px-4">
                              <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${sev.color} flex items-center gap-1 w-fit`}>
                                <Icon icon={sev.icon} width={11} />{sev.label}
                              </span>
                            </td>
                            <td className="py-3 px-4">
                              <p className="font-medium text-[rgba(0,0,0,0.88)]">{item.fullName || '—'}</p>
                              <p className="text-xs text-[rgba(0,0,0,0.4)]">{item.email}</p>
                            </td>
                            <td className="py-3 px-4 text-[rgba(0,0,0,0.5)] text-xs">{item.orgUnit || '/'}</td>
                            <td className="py-3 px-4 font-semibold text-amber-600">
                              {item.daysSinceLogin != null ? `${item.daysSinceLogin}d` : 'Never logged in'}
                            </td>
                            <td className="py-3 px-4 text-[rgba(0,0,0,0.6)] text-xs max-w-xs">{item.recommendation}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )
          }
        </div>
      )}

      {activeTab === 'cost' && (
        <div className="space-y-5">
          {summaryLoading
            ? (
              <div className="flex items-center justify-center py-12 gap-2 text-[rgba(0,0,0,0.4)]">
                <Icon icon="mdi:loading" width={16} className="animate-spin" />
                <span className="text-sm">Loading…</span>
              </div>
            )
            : summary && (
              <>
                <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
                  <div className="flex items-center gap-2 mb-4">
                    <div className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse" />
                    <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Seat Utilisation Breakdown</p>
                  </div>
                  <ResponsiveContainer width="100%" height={220}>
                    <BarChart data={utilisationData} margin={{ left: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                      <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                      <YAxis tick={{ fontSize: 12 }} />
                      <Tooltip formatter={(v) => `${v} seats`} />
                      <Bar dataKey="value" radius={[6, 6, 0, 0]}>
                        {utilisationData.map((entry, i) => <Cell key={i} fill={entry.fill} />)}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm hover:shadow-md">
                    <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">Active seats in use</p>
                    <p className="leading-8 mb-2">
                      <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{summary.activeSeats}</span>
                    </p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.35)]">{summary.utilizationPercent}% utilisation</p>
                  </div>
                  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm hover:shadow-md">
                    <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">Inactive seats</p>
                    <p className="leading-8 mb-2">
                      <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{summary.inactiveSeats}</span>
                    </p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.35)]">No login in {inactiveDays}+ days</p>
                  </div>
                  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm hover:shadow-md">
                    <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">Never logged in</p>
                    <p className="leading-8 mb-2">
                      <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{summary.neverLoggedIn}</span>
                    </p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.35)]">Potentially wasted seats</p>
                  </div>
                </div>

                <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
                  <div className="flex items-center gap-2 mb-2">
                    <div className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse" />
                    <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Savings Estimator</p>
                  </div>
                  <p className="text-sm text-[rgba(0,0,0,0.4)] mb-5">
                    Enter your per-seat monthly cost to estimate potential savings from reclaiming unused seats.
                  </p>
                  <SavingsEstimator
                    inactiveSeats={summary.inactiveSeats + summary.suspendedSeats + summary.neverLoggedIn}
                  />
                </div>
              </>
            )
          }
        </div>
      )}
    </div>
  );
};

const WorkspaceLicensesPage: React.FC = () => {
  const { clearActions } = useTopbar();
  const { status, loading, error, refetch } = useWorkspaceStatus();
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    clearActions();
    return () => clearActions();
  }, [clearActions]);

  const handleConnected = useCallback(() => {
    setRefreshKey((k) => k + 1);
    refetch();
  }, [refetch]);

  const handleDisconnected = useCallback(() => {
    setRefreshKey((k) => k + 1);
    refetch();
  }, [refetch]);

  if (loading) {
    return (
      <main className="flex-1 flex items-center justify-center bg-white min-h-screen">
        <div className="flex flex-col items-center gap-3">
          <Icon icon="mdi:loading" width={32} className="animate-spin text-[rgba(0,0,0,0.3)]" />
          <p className="text-[rgba(0,0,0,0.4)] text-sm">Loading Workspace…</p>
        </div>
      </main>
    );
  }

  if (error) {
    return (
      <main className="flex-1 flex items-center justify-center bg-white min-h-screen">
        <div className="text-center p-8">
          <div className="w-14 h-14 bg-red-50 border border-red-100 rounded-xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:alert-outline" width={28} className="text-red-500" />
          </div>
          <p className="text-[rgba(0,0,0,0.7)] font-medium mb-4">{error}</p>
          <button onClick={refetch} className="px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white rounded-xl text-[12px] font-medium hover:bg-[rgba(0,0,0,0.75)]">Retry</button>
        </div>
      </main>
    );
  }

  return (
    <main key={refreshKey} className="flex-1 flex flex-col">
      {status?.connected
        ? <ConnectedDashboard onDisconnected={handleDisconnected} onRefresh={refetch} />
        : <ConnectionPanel onConnected={handleConnected} />
      }
    </main>
  );
};

export default WorkspaceLicensesPage;
