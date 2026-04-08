import React, { useEffect, useRef, useState, useCallback } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell,
  PieChart, Pie, Legend,
} from 'recharts';
import { useTopbar } from '../contexts/TopbarContext';
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

// ─── Small reusable pieces ────────────────────────────────────────────────────

const Spinner: React.FC<{ size?: string }> = ({ size = 'h-8 w-8' }) => (
  <div className={`animate-spin rounded-full border-4 border-orange-200 border-t-orange-500 ${size}`} />
);

const SummaryCard: React.FC<{
  icon: string; label: string; value: number | string; color: string; sub?: string;
}> = ({ icon, label, value, color, sub }) => (
  <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-5 flex items-center gap-4">
    <div className={`w-12 h-12 rounded-xl flex items-center justify-center ${color}`}>
      <i className={`${icon} text-white text-lg`} />
    </div>
    <div>
      <p className="text-2xl font-bold text-gray-900">{value}</p>
      <p className="text-sm text-gray-500">{label}</p>
      {sub && <p className="text-xs text-gray-400 mt-0.5">{sub}</p>}
    </div>
  </div>
);

const StatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const map: Record<string, string> = {
    ACTIVE: 'bg-green-100 text-green-700',
    INACTIVE: 'bg-amber-100 text-amber-700',
    SUSPENDED: 'bg-red-100 text-red-700',
  };
  return (
    <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${map[status] ?? 'bg-gray-100 text-gray-600'}`}>
      {status}
    </span>
  );
};

// ─── Setup Guide ─────────────────────────────────────────────────────────────

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
      icon: 'fas fa-project-diagram',
      title: 'Create or select a GCP Project & enable the Admin SDK',
      body: (
        <ol className="list-decimal pl-5 space-y-1 text-sm text-gray-600">
          <li>Go to <strong>console.cloud.google.com</strong> and sign in with a Google account that belongs to your Workspace domain.</li>
          <li>Create a new project (or select an existing one).</li>
          <li>Navigate to <strong>APIs &amp; Services → Library</strong>, search for <em>"Admin SDK API"</em> and click <strong>Enable</strong>.</li>
        </ol>
      ),
    },
    {
      icon: 'fas fa-robot',
      title: 'Create a Service Account',
      body: (
        <ol className="list-decimal pl-5 space-y-1 text-sm text-gray-600">
          <li>In the GCP Console go to <strong>IAM &amp; Admin → Service Accounts</strong>.</li>
          <li>Click <strong>Create Service Account</strong>, give it a name (e.g. <em>xamops-workspace</em>), then click <strong>Done</strong>.</li>
          <li>No project-level IAM role is needed — leave those steps blank.</li>
        </ol>
      ),
    },
    {
      icon: 'fas fa-key',
      title: 'Enable Domain-Wide Delegation on the service account',
      body: (
        <ol className="list-decimal pl-5 space-y-1 text-sm text-gray-600">
          <li>Click on the service account you just created to open its detail page.</li>
          <li>Open the <strong>Advanced settings</strong> section (or scroll to "Domain-wide delegation").</li>
          <li>Check <strong>"Enable Google Workspace Domain-wide Delegation"</strong> and save.</li>
          <li><strong>Copy the numeric Client ID</strong> shown — you will need it in Step 5.</li>
        </ol>
      ),
    },
    {
      icon: 'fas fa-download',
      title: 'Download the JSON key file',
      body: (
        <>
          <ol className="list-decimal pl-5 space-y-1 text-sm text-gray-600 mb-3">
            <li>On the service account page, go to the <strong>Keys</strong> tab.</li>
            <li>Click <strong>Add Key → Create new key</strong>, choose <strong>JSON</strong>, then click <strong>Create</strong>.</li>
            <li>A <code className="bg-gray-100 px-1 rounded text-xs">.json</code> file will download automatically — this is what you upload/paste in the form below.</li>
          </ol>
          <div className="flex items-start gap-2 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
            <i className="fas fa-exclamation-triangle mt-0.5 flex-shrink-0" />
            <p>
              <strong>Keep this file private.</strong> It contains a private key that grants admin-level read access to your Workspace directory.
              Never commit it to source control, share it in chat, or expose it publicly.
            </p>
          </div>
        </>
      ),
    },
    {
      icon: 'fas fa-shield-alt',
      title: 'Authorise the scope in Google Workspace Admin Console',
      body: (
        <>
          <ol className="list-decimal pl-5 space-y-1 text-sm text-gray-600 mb-3">
            <li>Open a new tab and go to <strong>admin.google.com</strong> — sign in as a Super Admin.</li>
            <li>Navigate to <strong>Security → Access and data control → API controls</strong>.</li>
            <li>Click <strong>Manage Domain-Wide Delegation</strong>, then <strong>Add new</strong>.</li>
            <li>Paste the <strong>Client ID</strong> you copied in Step 3.</li>
            <li>In the <em>OAuth scopes</em> field paste the scope below, then click <strong>Authorise</strong>.</li>
          </ol>
          <div className="flex items-center gap-2 bg-gray-100 rounded-lg px-3 py-2">
            <code className="flex-1 text-xs text-blue-700 break-all select-all font-mono">{REQUIRED_SCOPE}</code>
            <button
              onClick={copyScope}
              className="flex-shrink-0 px-2 py-1 bg-white border border-gray-200 rounded text-xs font-medium text-gray-600 hover:bg-gray-50 transition flex items-center gap-1"
            >
              <i className={copied ? 'fas fa-check text-green-500' : 'fas fa-copy'} />
              {copied ? 'Copied!' : 'Copy'}
            </button>
          </div>
        </>
      ),
    },
    {
      icon: 'fas fa-plug',
      title: 'Fill in the form below and connect',
      body: (
        <ul className="list-disc pl-5 space-y-1 text-sm text-gray-600">
          <li><strong>Workspace Domain</strong> — your primary Google Workspace domain (e.g. <em>company.com</em>).</li>
          <li><strong>Admin Email</strong> — the Super Admin email that the service account will impersonate (e.g. <em>admin@company.com</em>).</li>
          <li><strong>Service Account Key</strong> — upload or paste the JSON file you downloaded in Step 4.</li>
        </ul>
      ),
    },
  ];

  return (
    <div className="mb-6 border border-blue-100 rounded-xl overflow-hidden">
      <button
        onClick={() => setOpen((o) => !o)}
        className="w-full flex items-center justify-between px-5 py-4 bg-blue-50 hover:bg-blue-100 transition text-left"
      >
        <div className="flex items-center gap-3">
          <i className="fas fa-book-open text-blue-600" />
          <span className="text-sm font-semibold text-blue-800">How to set up the service account — step-by-step guide</span>
        </div>
        <i className={`fas fa-chevron-${open ? 'up' : 'down'} text-blue-500 text-sm`} />
      </button>

      {open && (
        <div className="bg-white border-t border-blue-100 p-6">
          <p className="text-sm text-gray-500 mb-6">
            XamOps uses a <strong>service account with Domain-Wide Delegation</strong> to read your Google Workspace
            directory without requiring per-user OAuth. Follow these steps once in your GCP Console and Google Workspace
            Admin Console.
          </p>

          <div className="space-y-5">
            {steps.map((step, idx) => (
              <div key={idx} className="flex gap-4">
                {/* Step number + connector */}
                <div className="flex flex-col items-center flex-shrink-0">
                  <div className="w-8 h-8 rounded-full bg-orange-100 flex items-center justify-center text-orange-600 font-bold text-sm">
                    {idx + 1}
                  </div>
                  {idx < steps.length - 1 && (
                    <div className="w-0.5 flex-1 bg-orange-100 mt-1" />
                  )}
                </div>

                {/* Content */}
                <div className="flex-1 pb-5">
                  <div className="flex items-center gap-2 mb-2">
                    <i className={`${step.icon} text-orange-500 text-sm`} />
                    <h4 className="text-sm font-semibold text-gray-800">{step.title}</h4>
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

// ─── Connection Setup Panel ───────────────────────────────────────────────────

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
    <div className="flex-1 flex items-center justify-center p-8">
      <div className="max-w-4xl w-full">
        {/* Hero */}
        <div className="text-center mb-10">
          <div className="inline-flex items-center justify-center w-20 h-20 rounded-2xl bg-gradient-to-br from-blue-500 to-blue-700 shadow-lg mb-6">
            <i className="fab fa-google text-white text-3xl" />
          </div>
          <h2 className="text-3xl font-bold text-gray-900 mb-3">Connect Google Workspace</h2>
          <p className="text-gray-500 text-lg max-w-xl mx-auto">
            Link your organisation's Google Workspace to track seat usage, identify inactive accounts,
            and optimise licence costs — all from one place.
          </p>
        </div>

        {/* Benefit cards */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-10">
          {[
            { icon: 'fas fa-users', color: 'bg-blue-500', title: 'Seat Visibility', desc: 'See every user, their last login, and seat allocation in real time.' },
            { icon: 'fas fa-lightbulb', color: 'bg-orange-500', title: 'Smart Optimisation', desc: 'Auto-detect inactive and never-logged-in users to reclaim unused seats.' },
            { icon: 'fas fa-chart-bar', color: 'bg-green-500', title: 'Cost Insights', desc: 'Understand utilisation rates and quantify licence waste instantly.' },
          ].map((b) => (
            <div key={b.title} className="bg-white rounded-xl border border-gray-100 shadow-sm p-5 flex gap-4">
              <div className={`${b.color} w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0`}>
                <i className={`${b.icon} text-white`} />
              </div>
              <div>
                <p className="font-semibold text-gray-800">{b.title}</p>
                <p className="text-sm text-gray-500 mt-1">{b.desc}</p>
              </div>
            </div>
          ))}
        </div>

        {/* Connection form */}
        <div className="bg-white rounded-2xl shadow-lg border border-gray-100 p-8">
          <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
            <i className="fas fa-plug text-orange-500" /> Connection Setup
          </h3>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-5 mb-5">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Workspace Domain <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                placeholder="company.com"
                value={domain}
                onChange={(e) => setDomain(e.target.value)}
                className="w-full border border-gray-200 rounded-lg px-4 py-2.5 text-sm focus:ring-2 focus:ring-orange-400 focus:border-transparent outline-none"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Admin Email (for impersonation) <span className="text-red-500">*</span>
              </label>
              <input
                type="email"
                placeholder="admin@company.com"
                value={adminEmail}
                onChange={(e) => setAdminEmail(e.target.value)}
                className="w-full border border-gray-200 rounded-lg px-4 py-2.5 text-sm focus:ring-2 focus:ring-orange-400 focus:border-transparent outline-none"
              />
            </div>
          </div>

          <div className="mb-6">
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Service Account Key (JSON) <span className="text-red-500">*</span>
            </label>
            <div className="flex gap-3 mb-2">
              <button
                onClick={() => fileRef.current?.click()}
                className="px-4 py-2 border border-gray-200 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 transition flex items-center gap-2"
              >
                <i className="fas fa-upload text-gray-500" /> Upload JSON file
              </button>
              <input ref={fileRef} type="file" accept=".json" onChange={handleFileUpload} className="hidden" />
              {serviceAccountKey && (
                <span className="flex items-center gap-1.5 text-sm text-green-600 font-medium">
                  <i className="fas fa-check-circle" /> File loaded
                </span>
              )}
            </div>
            <textarea
              placeholder={'Paste service account JSON here…\n{\n  "type": "service_account",\n  "project_id": "...",\n  ...\n}'}
              value={serviceAccountKey}
              onChange={(e) => setServiceAccountKey(e.target.value)}
              rows={6}
              className="w-full border border-gray-200 rounded-lg px-4 py-3 text-xs font-mono focus:ring-2 focus:ring-orange-400 focus:border-transparent outline-none resize-none"
            />
          </div>

          {error && (
            <div className="mb-4 p-4 bg-red-50 border border-red-200 rounded-lg flex gap-3">
              <i className="fas fa-exclamation-circle text-red-500 mt-0.5 flex-shrink-0" />
              <p className="text-sm text-red-700">{error}</p>
            </div>
          )}

          {/* Setup guide */}
          <SetupGuide />

          <button
            onClick={handleConnect}
            disabled={connecting}
            className="w-full py-3 bg-gradient-to-r from-orange-500 to-red-500 text-white rounded-xl font-semibold text-sm hover:shadow-lg transition disabled:opacity-60 flex items-center justify-center gap-2"
          >
            {connecting
              ? <><Spinner size="h-4 w-4" /> Connecting…</>
              : <><i className="fab fa-google" /> Connect Google Workspace</>}
          </button>
        </div>
      </div>
    </div>
  );
};

// ─── Savings Estimator ────────────────────────────────────────────────────────

const SavingsEstimator: React.FC<{ inactiveSeats: number }> = ({ inactiveSeats }) => {
  const [costPerSeat, setCostPerSeat] = useState<string>('12');
  const monthly = parseFloat(costPerSeat || '0') * inactiveSeats;
  const annual  = monthly * 12;

  return (
    <div className="flex flex-wrap items-end gap-6">
      <div>
        <label className="block text-xs font-medium text-gray-600 mb-1">Monthly cost per seat ($)</label>
        <input
          type="number"
          min="0"
          step="0.01"
          value={costPerSeat}
          onChange={(e) => setCostPerSeat(e.target.value)}
          className="w-36 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-orange-400 focus:border-transparent outline-none"
        />
      </div>
      <div className="flex gap-6">
        <div>
          <p className="text-xs text-gray-500">Reclaimable seats</p>
          <p className="text-2xl font-bold text-gray-900">{inactiveSeats}</p>
        </div>
        <div>
          <p className="text-xs text-gray-500">Monthly saving</p>
          <p className="text-2xl font-bold text-green-600">${monthly.toFixed(2)}</p>
        </div>
        <div>
          <p className="text-xs text-gray-500">Annual saving</p>
          <p className="text-2xl font-bold text-green-700">${annual.toFixed(2)}</p>
        </div>
      </div>
    </div>
  );
};

// ─── Connected Dashboard ──────────────────────────────────────────────────────

const ConnectedDashboard: React.FC<{ onDisconnected: () => void; onRefresh: () => void }> = ({
  onDisconnected, onRefresh,
}) => {
  const [activeTab, setActiveTab] = useState<WorkspaceTab>('overview');
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

  const handleSync = async () => {
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
  };

  const handleDisconnect = async () => {
    if (!window.confirm('Disconnect Google Workspace? All cached user data will be removed.')) return;
    setDisconnecting(true);
    try {
      await disconnectWorkspace();
      onDisconnected();
    } catch (e: any) {
      setActionError(e.message ?? 'Disconnect failed');
      setDisconnecting(false);
    }
  };

  // Filtered users for the table
  const filteredUsers = users.filter((u) => {
    const matchSearch = !userSearch ||
      u.email.toLowerCase().includes(userSearch.toLowerCase()) ||
      u.fullName.toLowerCase().includes(userSearch.toLowerCase());
    const matchStatus = statusFilter === 'ALL' || u.status === statusFilter;
    return matchSearch && matchStatus;
  });

  const tabs: { id: WorkspaceTab; label: string; icon: string }[] = [
    { id: 'overview',     label: 'Overview',      icon: 'fas fa-chart-pie' },
    { id: 'users',        label: 'Users & Seats', icon: 'fas fa-users' },
    { id: 'optimization', label: 'Optimisation',  icon: 'fas fa-lightbulb' },
    { id: 'cost',         label: 'Cost & Savings', icon: 'fas fa-dollar-sign' },
  ];

  const lastSync = status?.lastSyncedAt
    ? new Date(status.lastSyncedAt).toLocaleString()
    : 'Never';

  // Chart data for donut / bar
  const utilisationData = summary
    ? [
        { name: 'Active',    value: summary.activeSeats,    fill: '#22c55e' },
        { name: 'Inactive',  value: summary.inactiveSeats,  fill: '#f59e0b' },
        { name: 'Suspended', value: summary.suspendedSeats, fill: '#ef4444' },
      ]
    : [];

  const optimSeverity: Record<string, { label: string; color: string; icon: string }> = {
    INACTIVE_USER:          { label: 'Inactive User',         color: 'bg-amber-100 text-amber-700',  icon: 'fas fa-clock'      },
    SUSPENDED_WITH_LICENSE: { label: 'Suspended (with seat)', color: 'bg-red-100 text-red-700',      icon: 'fas fa-ban'        },
    NEVER_LOGGED_IN:        { label: 'Never Logged In',       color: 'bg-purple-100 text-purple-700',icon: 'fas fa-user-slash' },
  };

  return (
    <div className="flex-1 flex flex-col p-6 max-w-7xl mx-auto w-full">

      {/* ── Header bar ── */}
      <div className="flex flex-wrap items-center justify-between gap-4 mb-6">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-blue-700 rounded-xl flex items-center justify-center">
            <i className="fab fa-google text-white" />
          </div>
          <div>
            <h2 className="font-bold text-gray-900 text-lg">{status?.domain}</h2>
            <p className="text-xs text-gray-400">Last synced: {lastSync}</p>
          </div>
          <span className="px-2 py-1 bg-green-100 text-green-700 text-xs font-semibold rounded-full">
            Connected
          </span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={handleSync}
            disabled={syncing}
            className="flex items-center gap-2 px-4 py-2 bg-orange-500 text-white rounded-lg text-sm font-medium hover:bg-orange-600 transition disabled:opacity-60"
          >
            <i className={`fas fa-sync ${syncing ? 'animate-spin' : ''}`} />
            {syncing ? 'Syncing…' : 'Sync Now'}
          </button>
          <button
            onClick={handleDisconnect}
            disabled={disconnecting}
            className="flex items-center gap-2 px-4 py-2 bg-gray-100 text-gray-700 rounded-lg text-sm font-medium hover:bg-red-50 hover:text-red-600 transition disabled:opacity-60"
          >
            <i className="fas fa-unlink" />
            Disconnect
          </button>
        </div>
      </div>

      {actionError && (
        <div className="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg flex gap-2 text-sm text-red-700">
          <i className="fas fa-exclamation-circle mt-0.5" />{actionError}
          <button onClick={() => setActionError(null)} className="ml-auto text-red-400 hover:text-red-600">
            <i className="fas fa-times" />
          </button>
        </div>
      )}

      {/* ── Summary cards ── */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <SummaryCard icon="fas fa-users"      label="Total Users"  value={status?.totalUsers    ?? '—'} color="bg-blue-500" />
        <SummaryCard icon="fas fa-user-check" label="Active"       value={status?.activeUsers   ?? '—'} color="bg-green-500" />
        <SummaryCard icon="fas fa-user-clock" label="Inactive"     value={status?.inactiveUsers ?? '—'} color="bg-amber-500" sub={`>${inactiveDays} days`} />
        <SummaryCard icon="fas fa-user-times" label="Suspended"    value={status?.suspendedUsers ?? '—'} color="bg-red-500" />
      </div>

      {/* ── Tabs ── */}
      <div className="flex gap-1 bg-gray-100 rounded-xl p-1 mb-6 overflow-x-auto">
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => setActiveTab(t.id)}
            className={`flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium transition whitespace-nowrap ${
              activeTab === t.id
                ? 'bg-white text-orange-600 shadow-sm'
                : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            <i className={t.icon} /> {t.label}
          </button>
        ))}
      </div>

      {/* ── OVERVIEW TAB ── */}
      {activeTab === 'overview' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Donut */}
          <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-6">
            <h3 className="font-semibold text-gray-800 mb-4 flex items-center gap-2">
              <i className="fas fa-chart-pie text-orange-500" /> Seat Utilisation
            </h3>
            {summaryLoading
              ? <div className="flex justify-center py-8"><Spinner /></div>
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
                    <span className="text-4xl font-bold text-gray-900">{summary.utilizationPercent}%</span>
                    <p className="text-sm text-gray-500">Utilisation rate</p>
                  </div>
                </div>
              )
            }
          </div>

          {/* Stats list */}
          <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-6 flex flex-col gap-4">
            <h3 className="font-semibold text-gray-800 flex items-center gap-2">
              <i className="fas fa-info-circle text-orange-500" /> Seat Breakdown
            </h3>
            {summaryLoading
              ? <div className="flex justify-center py-8"><Spinner /></div>
              : summary && [
                  { label: 'Active seats',    value: summary.activeSeats,    color: 'bg-green-500'  },
                  { label: 'Inactive seats',  value: summary.inactiveSeats,  color: 'bg-amber-500'  },
                  { label: 'Suspended seats', value: summary.suspendedSeats, color: 'bg-red-500'    },
                  { label: 'Never logged in', value: summary.neverLoggedIn,  color: 'bg-purple-500' },
                  { label: 'Total seats',     value: summary.totalSeats,     color: 'bg-blue-500'   },
                ].map(({ label, value, color }) => (
                  <div key={label} className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <div className={`w-2 h-2 rounded-full ${color}`} />
                      <span className="text-sm text-gray-600">{label}</span>
                    </div>
                    <span className="font-bold text-gray-900">{value}</span>
                  </div>
                ))
            }
          </div>

          {/* Top optimisation preview */}
          <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-6 lg:col-span-2">
            <h3 className="font-semibold text-gray-800 mb-4 flex items-center gap-2">
              <i className="fas fa-exclamation-triangle text-amber-500" /> Top Optimisation Opportunities
            </h3>
            {optimLoading
              ? <div className="flex justify-center py-4"><Spinner /></div>
              : optimItems.length === 0
                ? <p className="text-sm text-gray-400 text-center py-4">No issues detected — great job!</p>
                : (
                  <div className="space-y-2">
                    {optimItems.slice(0, 5).map((item, i) => {
                      const sev = optimSeverity[item.type] ?? { label: item.type, color: 'bg-gray-100 text-gray-600', icon: 'fas fa-user' };
                      return (
                        <div key={i} className="flex items-center gap-3 p-3 rounded-lg bg-gray-50">
                          <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${sev.color} flex-shrink-0`}>
                            <i className={`${sev.icon} mr-1`} />{sev.label}
                          </span>
                          <div className="flex-1 min-w-0">
                            <p className="text-sm font-medium text-gray-800 truncate">{item.fullName || item.email}</p>
                            <p className="text-xs text-gray-400 truncate">{item.email}</p>
                          </div>
                          <span className="text-sm text-gray-500 flex-shrink-0">
                            {item.daysSinceLogin != null ? `${item.daysSinceLogin}d` : 'Never'}
                          </span>
                        </div>
                      );
                    })}
                    {optimItems.length > 5 && (
                      <button
                        onClick={() => setActiveTab('optimization')}
                        className="w-full text-center text-sm text-orange-500 hover:text-orange-600 font-medium pt-1"
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

      {/* ── USERS & SEATS TAB ── */}
      {activeTab === 'users' && (
        <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-6">
          <div className="flex flex-wrap items-center gap-3 mb-5">
            <div className="relative flex-1 min-w-48">
              <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-sm" />
              <input
                type="text"
                placeholder="Search by name or email…"
                value={userSearch}
                onChange={(e) => setUserSearch(e.target.value)}
                className="w-full pl-9 pr-4 py-2 border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-orange-400 focus:border-transparent outline-none"
              />
            </div>
            <div className="flex gap-1 bg-gray-100 rounded-lg p-1">
              {(['ALL', 'ACTIVE', 'INACTIVE', 'SUSPENDED'] as UserStatusFilter[]).map((s) => (
                <button
                  key={s}
                  onClick={() => setStatusFilter(s)}
                  className={`px-3 py-1.5 rounded-md text-xs font-semibold transition ${
                    statusFilter === s ? 'bg-white text-orange-600 shadow-sm' : 'text-gray-500 hover:text-gray-700'
                  }`}
                >
                  {s}
                </button>
              ))}
            </div>
            <span className="text-sm text-gray-400">{filteredUsers.length} user{filteredUsers.length !== 1 ? 's' : ''}</span>
          </div>

          {usersLoading
            ? <div className="flex justify-center py-12"><Spinner /></div>
            : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-100">
                      <th className="text-left py-3 px-2 text-gray-500 font-medium">User</th>
                      <th className="text-left py-3 px-2 text-gray-500 font-medium">Org Unit</th>
                      <th className="text-left py-3 px-2 text-gray-500 font-medium">Status</th>
                      <th className="text-left py-3 px-2 text-gray-500 font-medium">Last Login</th>
                      <th className="text-left py-3 px-2 text-gray-500 font-medium">Days Inactive</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredUsers.length === 0 && (
                      <tr><td colSpan={5} className="text-center py-10 text-gray-400">No users found</td></tr>
                    )}
                    {filteredUsers.map((u) => (
                      <tr key={u.email} className="border-b border-gray-50 hover:bg-gray-50 transition">
                        <td className="py-3 px-2">
                          <div className="flex items-center gap-2">
                            <div className="w-7 h-7 rounded-full bg-gradient-to-br from-blue-400 to-blue-600 flex items-center justify-center text-white text-xs font-bold flex-shrink-0">
                              {(u.fullName || u.email).charAt(0).toUpperCase()}
                            </div>
                            <div>
                              <p className="font-medium text-gray-800 leading-tight">{u.fullName || '—'}</p>
                              <p className="text-xs text-gray-400">{u.email}</p>
                            </div>
                            {u.isAdmin && (
                              <span className="px-1.5 py-0.5 bg-blue-100 text-blue-600 text-xs rounded">Admin</span>
                            )}
                          </div>
                        </td>
                        <td className="py-3 px-2 text-gray-500 text-xs">{u.orgUnit || '/'}</td>
                        <td className="py-3 px-2"><StatusBadge status={u.status} /></td>
                        <td className="py-3 px-2 text-gray-500">
                          {u.lastLoginTime
                            ? new Date(u.lastLoginTime).toLocaleDateString()
                            : <span className="text-gray-300">Never</span>}
                        </td>
                        <td className="py-3 px-2">
                          {u.daysSinceLogin != null
                            ? <span className={u.daysSinceLogin >= 30 ? 'text-amber-600 font-semibold' : 'text-gray-600'}>{u.daysSinceLogin}d</span>
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

      {/* ── OPTIMISATION TAB ── */}
      {activeTab === 'optimization' && (
        <div className="space-y-4">
          <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-4 flex flex-wrap items-center gap-4">
            <span className="text-sm font-medium text-gray-700">Inactivity threshold:</span>
            <div className="flex gap-2">
              {[7, 14, 30, 60, 90].map((d) => (
                <button
                  key={d}
                  onClick={() => setInactiveDays(d)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition ${
                    inactiveDays === d ? 'bg-orange-500 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                  }`}
                >
                  {d} days
                </button>
              ))}
            </div>
            {!optimLoading && (
              <span className="text-sm text-gray-400 ml-auto">
                {optimItems.length} recommendation{optimItems.length !== 1 ? 's' : ''}
              </span>
            )}
          </div>

          {optimLoading
            ? <div className="flex justify-center py-12"><Spinner /></div>
            : optimItems.length === 0
              ? (
                <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-12 text-center">
                  <i className="fas fa-check-circle text-green-400 text-4xl mb-4" />
                  <p className="text-lg font-semibold text-gray-700">All seats are optimally used!</p>
                  <p className="text-sm text-gray-400 mt-2">No inactive or problematic accounts detected.</p>
                </div>
              )
              : (
                <div className="bg-white rounded-xl border border-gray-100 shadow-sm overflow-hidden">
                  <table className="w-full text-sm">
                    <thead className="bg-gray-50">
                      <tr>
                        <th className="text-left py-3 px-4 text-gray-500 font-medium">Type</th>
                        <th className="text-left py-3 px-4 text-gray-500 font-medium">User</th>
                        <th className="text-left py-3 px-4 text-gray-500 font-medium">Org Unit</th>
                        <th className="text-left py-3 px-4 text-gray-500 font-medium">Inactive Days</th>
                        <th className="text-left py-3 px-4 text-gray-500 font-medium">Recommendation</th>
                      </tr>
                    </thead>
                    <tbody>
                      {optimItems.map((item, i) => {
                        const sev = optimSeverity[item.type] ?? { label: item.type, color: 'bg-gray-100 text-gray-600', icon: 'fas fa-user' };
                        return (
                          <tr key={i} className="border-t border-gray-50 hover:bg-gray-50">
                            <td className="py-3 px-4">
                              <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${sev.color}`}>
                                <i className={`${sev.icon} mr-1`} />{sev.label}
                              </span>
                            </td>
                            <td className="py-3 px-4">
                              <p className="font-medium text-gray-800">{item.fullName || '—'}</p>
                              <p className="text-xs text-gray-400">{item.email}</p>
                            </td>
                            <td className="py-3 px-4 text-gray-500 text-xs">{item.orgUnit || '/'}</td>
                            <td className="py-3 px-4 font-semibold text-amber-600">
                              {item.daysSinceLogin != null ? `${item.daysSinceLogin}d` : 'Never logged in'}
                            </td>
                            <td className="py-3 px-4 text-gray-600 text-xs max-w-xs">{item.recommendation}</td>
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

      {/* ── COST & SAVINGS TAB ── */}
      {activeTab === 'cost' && (
        <div className="space-y-6">
          {summaryLoading
            ? <div className="flex justify-center py-12"><Spinner /></div>
            : summary && (
              <>
                <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-6">
                  <h3 className="font-semibold text-gray-800 mb-4 flex items-center gap-2">
                    <i className="fas fa-chart-bar text-orange-500" /> Seat Utilisation Breakdown
                  </h3>
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
                  <div className="bg-gradient-to-br from-green-50 to-green-100 rounded-xl border border-green-200 p-5">
                    <i className="fas fa-check-circle text-green-500 text-xl mb-3" />
                    <p className="text-2xl font-bold text-green-800">{summary.activeSeats}</p>
                    <p className="text-sm text-green-700 font-medium">Active seats in use</p>
                    <p className="text-xs text-green-600 mt-1">{summary.utilizationPercent}% utilisation</p>
                  </div>
                  <div className="bg-gradient-to-br from-amber-50 to-amber-100 rounded-xl border border-amber-200 p-5">
                    <i className="fas fa-exclamation-circle text-amber-500 text-xl mb-3" />
                    <p className="text-2xl font-bold text-amber-800">{summary.inactiveSeats}</p>
                    <p className="text-sm text-amber-700 font-medium">Inactive seats</p>
                    <p className="text-xs text-amber-600 mt-1">No login in {inactiveDays}+ days</p>
                  </div>
                  <div className="bg-gradient-to-br from-purple-50 to-purple-100 rounded-xl border border-purple-200 p-5">
                    <i className="fas fa-user-slash text-purple-500 text-xl mb-3" />
                    <p className="text-2xl font-bold text-purple-800">{summary.neverLoggedIn}</p>
                    <p className="text-sm text-purple-700 font-medium">Never logged in</p>
                    <p className="text-xs text-purple-600 mt-1">Potentially wasted seats</p>
                  </div>
                </div>

                <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-6">
                  <h3 className="font-semibold text-gray-800 mb-2 flex items-center gap-2">
                    <i className="fas fa-calculator text-orange-500" /> Savings Estimator
                  </h3>
                  <p className="text-sm text-gray-500 mb-5">
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

// ─── Main Page ────────────────────────────────────────────────────────────────

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
      <main className="flex-1 flex items-center justify-center min-h-screen bg-gray-50">
        <div className="flex flex-col items-center gap-4">
          <Spinner size="h-12 w-12" />
          <p className="text-gray-400 text-sm">Loading Workspace…</p>
        </div>
      </main>
    );
  }

  if (error) {
    return (
      <main className="flex-1 flex items-center justify-center min-h-screen bg-gray-50">
        <div className="text-center p-8">
          <i className="fas fa-exclamation-triangle text-red-400 text-4xl mb-4" />
          <p className="text-gray-700 font-medium">{error}</p>
          <button
            onClick={refetch}
            className="mt-4 px-4 py-2 bg-orange-500 text-white rounded-lg text-sm font-medium"
          >
            Retry
          </button>
        </div>
      </main>
    );
  }

  return (
    <main key={refreshKey} className="flex-1 flex flex-col min-h-screen bg-gray-50">
      {status?.connected
        ? <ConnectedDashboard onDisconnected={handleDisconnected} onRefresh={refetch} />
        : <ConnectionPanel onConnected={handleConnected} />
      }
    </main>
  );
};

export default WorkspaceLicensesPage;
