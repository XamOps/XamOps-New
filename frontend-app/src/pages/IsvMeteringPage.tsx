/**
 * IsvMeteringPage — XamOps Metering-as-a-Service Platform
 *
 * Allows ISVs to:
 *   - Register their AWS Marketplace SaaS product for metered billing
 *   - View and manage customer registrations
 *   - Monitor metering records submitted to AWS
 *   - Copy their API key for SDK integration
 *   - Track pending/failed usage events
 */

import React, { useState, useCallback } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import {
  useIsvProducts,
  useIsvDashboard,
  useIsvCustomers,
  useIsvMeteringRecords,
  useSubscriptionEvents,
  useIsvAnalytics,
  createIsvProduct,
  regenerateApiKey,
  cancelIsvCustomer,
  triggerMeteringEngine,
} from '../hooks/useIsv';
import { IsvProduct, IsvProductRequest, SubscriptionEvent } from '../types/isv';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' });
}

function copyToClipboard(text: string) {
  navigator.clipboard.writeText(text).catch(() => {});
}

// ─── Shared UI atoms ──────────────────────────────────────────────────────────

const Spinner: React.FC<{ text?: string }> = ({ text = 'Loading…' }) => (
  <div className="flex items-center justify-center py-12 gap-2 text-gray-400">
    <i className="fas fa-spinner fa-spin" />
    <span className="text-sm">{text}</span>
  </div>
);

const ErrorBox: React.FC<{ message: string }> = ({ message }) => (
  <div className="flex items-center gap-2 text-red-500 text-sm bg-red-50 border border-red-100 rounded-xl px-4 py-3">
    <i className="fas fa-exclamation-circle" />
    <span>{message}</span>
  </div>
);

const Empty: React.FC<{ text: string }> = ({ text }) => (
  <div className="text-center py-10 text-gray-400">
    <i className="fas fa-inbox text-3xl mb-2 block" />
    <p className="text-sm">{text}</p>
  </div>
);

const StatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const colors: Record<string, string> = {
    ACTIVE:    'bg-green-100 text-green-700',
    INACTIVE:  'bg-gray-100 text-gray-500',
    CANCELLED: 'bg-amber-100 text-amber-700',
    EXPIRED:   'bg-red-100 text-red-600',
    SUCCESS:   'bg-green-100 text-green-700',
    SUBMITTED: 'bg-blue-100 text-blue-700',
    FAILED:    'bg-red-100 text-red-600',
  };
  return (
    <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${colors[status] ?? 'bg-gray-100 text-gray-600'}`}>
      {status}
    </span>
  );
};

// ─── Stat Card ────────────────────────────────────────────────────────────────

const StatCard: React.FC<{
  icon: string;
  label: string;
  value: number | string;
  color: string;
  alert?: boolean;
}> = ({ icon, label, value, color, alert }) => (
  <div className={`rounded-2xl border p-4 bg-white/80 ${alert && Number(value) > 0 ? 'border-red-200 bg-red-50/30' : 'border-gray-100'}`}>
    <div className="flex items-center gap-3">
      <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${color}`}>
        <i className={`${icon} text-white text-sm`} />
      </div>
      <div>
        <p className="text-xs text-gray-400 font-medium">{label}</p>
        <p className={`text-xl font-bold ${alert && Number(value) > 0 ? 'text-red-600' : 'text-gray-800'}`}>{value}</p>
      </div>
    </div>
  </div>
);

// ─── API Key display ──────────────────────────────────────────────────────────

const ApiKeyDisplay: React.FC<{
  productCode: string;
  apiKey: string;
  onRegenerate: () => void;
}> = ({ productCode: _productCode, apiKey, onRegenerate }) => {
  const [visible, setVisible] = useState(false);
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    copyToClipboard(apiKey);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="rounded-2xl border border-indigo-100 bg-gradient-to-r from-indigo-50 to-violet-50 p-5">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <i className="fas fa-key text-indigo-500" />
          <span className="font-semibold text-gray-700 text-sm">API Key</span>
        </div>
        <button
          onClick={onRegenerate}
          className="text-xs text-amber-600 hover:text-amber-700 font-medium flex items-center gap-1"
        >
          <i className="fas fa-sync-alt" />
          Regenerate
        </button>
      </div>
      <div className="flex items-center gap-2">
        <code className="flex-1 text-xs bg-white/80 border border-indigo-100 rounded-lg px-3 py-2 font-mono text-gray-700 truncate">
          {visible ? apiKey : '•'.repeat(Math.min(apiKey.length, 40))}
        </code>
        <button
          onClick={() => setVisible(v => !v)}
          className="p-2 rounded-lg hover:bg-white/60 text-gray-400 hover:text-gray-600 transition-colors"
          title={visible ? 'Hide' : 'Show'}
        >
          <i className={`fas fa-eye${visible ? '-slash' : ''} text-xs`} />
        </button>
        <button
          onClick={handleCopy}
          className="p-2 rounded-lg hover:bg-white/60 text-gray-400 hover:text-gray-600 transition-colors"
          title="Copy"
        >
          <i className={`fas fa-${copied ? 'check text-green-500' : 'copy'} text-xs`} />
        </button>
      </div>
      <p className="text-xs text-indigo-400 mt-2">
        Use this key in the <code className="bg-white/70 px-1 rounded">X-Api-Key</code> header when calling <code className="bg-white/70 px-1 rounded">POST /api/metering/events</code>
      </p>
    </div>
  );
};

// ─── Integration Guide ────────────────────────────────────────────────────────

const IntegrationGuide: React.FC<{ productCode: string; apiKey: string }> = ({ productCode: _productCode, apiKey }) => {
  const [copied, setCopied] = useState(false);
  const snippet = `// Send a usage event from your product backend
curl -X POST https://app.xamops.io/api/metering/events \\
  -H "X-Api-Key: ${apiKey}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "customerId": "<aws-customer-identifier>",
    "dimension": "api_calls",
    "quantity": 1500
  }'`;

  return (
    <div className="rounded-2xl border border-gray-100 bg-white/80 p-5">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <i className="fas fa-code text-blue-500" />
          <span className="font-semibold text-gray-700 text-sm">Integration Guide</span>
        </div>
        <button
          onClick={() => { copyToClipboard(snippet); setCopied(true); setTimeout(() => setCopied(false), 2000); }}
          className="text-xs text-blue-600 hover:text-blue-700 font-medium flex items-center gap-1"
        >
          <i className={`fas fa-${copied ? 'check' : 'copy'}`} />
          {copied ? 'Copied!' : 'Copy'}
        </button>
      </div>
      <pre className="text-xs bg-gray-900 text-green-400 rounded-xl p-4 overflow-x-auto leading-relaxed">
        {snippet}
      </pre>
      <div className="mt-4 grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs text-gray-600">
        <div className="flex items-start gap-2 p-3 bg-blue-50 rounded-xl">
          <i className="fas fa-circle-1 text-blue-500 mt-0.5" />
          <span>Configure your product&apos;s fulfillment URL on AWS Marketplace to point to <strong>https://app.xamops.io/marketplace/register</strong></span>
        </div>
        <div className="flex items-start gap-2 p-3 bg-violet-50 rounded-xl">
          <i className="fas fa-circle-2 text-violet-500 mt-0.5" />
          <span>When a buyer subscribes, AWS redirects them with a token. POST to <strong>/api/metering/register</strong> with the token to activate the customer.</span>
        </div>
        <div className="flex items-start gap-2 p-3 bg-indigo-50 rounded-xl">
          <i className="fas fa-circle-3 text-indigo-500 mt-0.5" />
          <span>Send usage events from your product backend using your API key. XamOps batches and submits them to AWS every hour.</span>
        </div>
      </div>
    </div>
  );
};

// ─── Dimensions list ──────────────────────────────────────────────────────────

const DimensionsList: React.FC<{ dimensions: IsvProduct['dimensions'] }> = ({ dimensions }) => (
  <div className="rounded-2xl border border-gray-100 bg-white/80 overflow-hidden">
    <div className="px-5 py-3 bg-gray-50 border-b border-gray-100">
      <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
        Registered Dimensions ({dimensions.length})
      </p>
    </div>
    {dimensions.length === 0 ? (
      <Empty text="No dimensions registered. Add dimensions to start metering." />
    ) : (
      <div className="divide-y divide-gray-50">
        {dimensions.map(d => (
          <div key={d.id} className="flex items-center justify-between px-5 py-3">
            <div>
              <code className="text-sm font-semibold text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded-lg">
                {d.dimensionKey}
              </code>
              {d.description && (
                <p className="text-xs text-gray-400 mt-0.5">{d.description}</p>
              )}
            </div>
            {d.unit && (
              <span className="text-xs text-gray-400 bg-gray-100 px-2 py-1 rounded-lg">{d.unit}</span>
            )}
          </div>
        ))}
      </div>
    )}
  </div>
);

// ─── Customers tab ────────────────────────────────────────────────────────────

const CustomersTab: React.FC<{ productCode: string }> = ({ productCode }) => {
  const { data: customers, loading, error, refetch } = useIsvCustomers(productCode);
  const [cancelling, setCancelling] = useState<string | null>(null);

  const handleCancel = async (customerId: string) => {
    if (!confirm('Cancel this customer? They will no longer be metered.')) return;
    setCancelling(customerId);
    try {
      await cancelIsvCustomer(productCode, customerId);
      await refetch();
    } catch (e: any) {
      alert('Failed to cancel: ' + (e.message ?? 'Unknown error'));
    } finally {
      setCancelling(null);
    }
  };

  if (loading) return <Spinner text="Loading customers…" />;
  if (error) return <ErrorBox message={error} />;
  if (customers.length === 0) return <Empty text="No customers registered yet." />;

  return (
    <div className="overflow-x-auto rounded-2xl border border-gray-100">
      <table className="w-full text-sm">
        <thead className="bg-gray-50">
          <tr>
            {['Customer ID', 'AWS Account', 'Status', 'Registered', 'Last Entitlement Check', ''].map(h => (
              <th key={h} className="py-3 px-4 text-xs font-semibold text-gray-500 uppercase tracking-wide text-left">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-50">
          {customers.map(c => (
            <tr key={c.id} className="hover:bg-gray-50/50 transition-colors">
              <td className="py-3 px-4">
                <code className="text-xs text-gray-700 bg-gray-100 px-2 py-0.5 rounded">
                  {c.customerIdentifier}
                </code>
              </td>
              <td className="py-3 px-4 text-gray-600 text-xs">{c.customerAwsAccountId ?? '—'}</td>
              <td className="py-3 px-4"><StatusBadge status={c.status} /></td>
              <td className="py-3 px-4 text-xs text-gray-500">{formatDate(c.registeredAt)}</td>
              <td className="py-3 px-4 text-xs text-gray-500">{formatDate(c.lastEntitlementCheck)}</td>
              <td className="py-3 px-4">
                {c.status === 'ACTIVE' && (
                  <button
                    onClick={() => handleCancel(c.customerIdentifier)}
                    disabled={cancelling === c.customerIdentifier}
                    className="text-xs text-red-500 hover:text-red-700 font-medium disabled:opacity-50"
                  >
                    {cancelling === c.customerIdentifier ? 'Cancelling…' : 'Cancel'}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

// ─── Metering Records tab ─────────────────────────────────────────────────────

const MeteringRecordsTab: React.FC<{ productCode: string }> = ({ productCode }) => {
  const { data: records, loading, error } = useIsvMeteringRecords(productCode, 100);

  if (loading) return <Spinner text="Loading metering records…" />;
  if (error) return <ErrorBox message={error} />;
  if (records.length === 0) return <Empty text="No metering records yet. Records appear after the first hourly submission." />;

  return (
    <div className="overflow-x-auto rounded-2xl border border-gray-100">
      <table className="w-full text-sm">
        <thead className="bg-gray-50">
          <tr>
            {['Customer', 'Dimension', 'Qty', 'Usage Hour', 'AWS Record ID', 'Status', 'Submitted'].map(h => (
              <th key={h} className="py-3 px-4 text-xs font-semibold text-gray-500 uppercase tracking-wide text-left">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-50">
          {records.map(r => (
            <tr key={r.id} className={`hover:bg-gray-50/50 transition-colors ${r.status === 'FAILED' ? 'bg-red-50/20' : ''}`}>
              <td className="py-3 px-4">
                <code className="text-xs text-gray-600 bg-gray-100 px-2 py-0.5 rounded">
                  {r.customerIdentifier.substring(0, 12)}…
                </code>
              </td>
              <td className="py-3 px-4">
                <code className="text-xs text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded">{r.dimension}</code>
              </td>
              <td className="py-3 px-4 font-semibold text-gray-700">{r.quantity.toLocaleString()}</td>
              <td className="py-3 px-4 text-xs text-gray-500">{formatDate(r.usageTimestamp)}</td>
              <td className="py-3 px-4">
                {r.awsMeteringRecordId ? (
                  <code className="text-xs text-gray-500 bg-gray-50 px-2 py-0.5 rounded">
                    {r.awsMeteringRecordId.substring(0, 16)}…
                  </code>
                ) : <span className="text-gray-300">—</span>}
              </td>
              <td className="py-3 px-4">
                <StatusBadge status={r.status} />
                {r.errorMessage && (
                  <p className="text-xs text-red-400 mt-0.5 max-w-xs truncate" title={r.errorMessage}>
                    {r.errorMessage}
                  </p>
                )}
              </td>
              <td className="py-3 px-4 text-xs text-gray-500">{formatDate(r.submittedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

// ─── Subscription Events tab ──────────────────────────────────────────────────

const eventTypeConfig: Record<string, { color: string; icon: string; label: string }> = {
  'subscribe-success':      { color: 'bg-green-100 text-green-700',  icon: 'fa-check-circle',    label: 'Subscribe Success' },
  'subscribe-fail':         { color: 'bg-red-100 text-red-600',      icon: 'fa-times-circle',     label: 'Subscribe Failed' },
  'unsubscribe-pending':    { color: 'bg-amber-100 text-amber-700',  icon: 'fa-clock',            label: 'Unsubscribe Pending' },
  'unsubscribe-success':    { color: 'bg-slate-100 text-slate-600',  icon: 'fa-minus-circle',     label: 'Unsubscribed' },
  'entitlement-updated':    { color: 'bg-blue-100 text-blue-700',    icon: 'fa-bolt',             label: 'Entitlement Updated' },
  'subscription-confirmation': { color: 'bg-violet-100 text-violet-700', icon: 'fa-link',         label: 'SNS Confirmed' },
};

const EventTypeBadge: React.FC<{ eventType: string }> = ({ eventType }) => {
  const cfg = eventTypeConfig[eventType] ?? { color: 'bg-gray-100 text-gray-600', icon: 'fa-bell', label: eventType };
  return (
    <span className={`inline-flex items-center gap-1.5 text-xs font-semibold px-2.5 py-1 rounded-full ${cfg.color}`}>
      <i className={`fas ${cfg.icon} text-[10px]`} />
      {cfg.label}
    </span>
  );
};

const snsStatusColors: Record<string, string> = {
  PROCESSED: 'bg-green-100 text-green-700',
  FAILED:    'bg-red-100 text-red-600',
  IGNORED:   'bg-gray-100 text-gray-500',
};

const SubscriptionEventsTab: React.FC<{ productCode: string }> = ({ productCode }) => {
  const { data: events, loading, error, refetch } = useSubscriptionEvents(productCode, 100);

  if (loading) return <Spinner text="Loading SNS events…" />;
  if (error) return <ErrorBox message={error} />;
  if (events.length === 0) return (
    <div className="text-center py-10 text-gray-400 space-y-3">
      <i className="fas fa-satellite-dish text-3xl block text-gray-200" />
      <p className="text-sm font-medium text-gray-500">No SNS events received yet</p>
      <p className="text-xs text-gray-400 max-w-md mx-auto">
        Configure your AWS Marketplace SNS topic to POST to{' '}
        <code className="bg-gray-100 px-1.5 py-0.5 rounded font-mono">
          https://app.xamops.io/api/metering/sns
        </code>
        {' '}to receive subscription lifecycle events here.
      </p>
    </div>
  );

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-xs text-gray-400">{events.length} event{events.length !== 1 ? 's' : ''} — newest first</p>
        <button onClick={refetch} className="text-xs text-indigo-500 hover:text-indigo-700 flex items-center gap-1 font-medium">
          <i className="fas fa-sync-alt" /> Refresh
        </button>
      </div>
      <div className="overflow-x-auto rounded-2xl border border-gray-100">
        <table className="w-full text-sm">
          <thead className="bg-gray-50">
            <tr>
              {['Event', 'Customer', 'Status', 'Received', 'Processed'].map(h => (
                <th key={h} className="py-3 px-4 text-xs font-semibold text-gray-500 uppercase tracking-wide text-left">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {events.map((ev: SubscriptionEvent) => (
              <tr key={ev.id} className={`hover:bg-gray-50/50 transition-colors ${ev.status === 'FAILED' ? 'bg-red-50/20' : ''}`}>
                <td className="py-3 px-4">
                  <EventTypeBadge eventType={ev.eventType} />
                </td>
                <td className="py-3 px-4">
                  {ev.customerIdentifier ? (
                    <code className="text-xs text-gray-600 bg-gray-100 px-2 py-0.5 rounded">
                      {ev.customerIdentifier.length > 16
                        ? ev.customerIdentifier.substring(0, 16) + '…'
                        : ev.customerIdentifier}
                    </code>
                  ) : <span className="text-gray-300 text-xs">—</span>}
                </td>
                <td className="py-3 px-4">
                  <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${snsStatusColors[ev.status] ?? 'bg-gray-100 text-gray-600'}`}>
                    {ev.status}
                  </span>
                  {ev.errorMessage && (
                    <p className="text-xs text-red-400 mt-0.5 max-w-xs truncate" title={ev.errorMessage}>
                      {ev.errorMessage}
                    </p>
                  )}
                </td>
                <td className="py-3 px-4 text-xs text-gray-500">{formatDate(ev.receivedAt)}</td>
                <td className="py-3 px-4 text-xs text-gray-500">{formatDate(ev.processedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

// ─── Analytics Tab ────────────────────────────────────────────────────────────

const CHART_COLORS = ['#6366f1', '#8b5cf6', '#ec4899', '#f59e0b', '#10b981', '#3b82f6'];

const AnalyticsTab: React.FC<{ productCode: string }> = ({ productCode }) => {
  const { data, loading, error } = useIsvAnalytics(productCode);

  if (loading) return <Spinner text="Loading analytics…" />;
  if (error) return <ErrorBox message={error} />;
  if (!data) return null;

  // Customer growth chart data
  const growthData = data.customerGrowth.map(p => ({
    month: p.yearMonth,
    'New Customers': p.newCustomers,
  }));

  // Dimension usage — pivot rows into { month, dim1, dim2, ... }
  const dimensions = [...new Set(data.usageByDimension.map(p => p.dimension))];
  const usageByMonth: Record<string, Record<string, number>> = {};
  data.usageByDimension.forEach(p => {
    if (!usageByMonth[p.yearMonth]) usageByMonth[p.yearMonth] = { month: p.yearMonth as any };
    usageByMonth[p.yearMonth][p.dimension] = p.totalQuantity;
  });
  const usageData = Object.values(usageByMonth).sort((a, b) =>
    String(a.month).localeCompare(String(b.month))
  );

  // Submission health chart data
  const healthData = data.submissionHealth.map(p => ({
    month: p.yearMonth,
    Success: p.success,
    Failed: p.failed,
  }));

  const noData = (arr: any[]) => arr.length === 0;

  return (
    <div className="space-y-8">

      {/* ── Customer Growth ── */}
      <div className="rounded-2xl border border-gray-100 bg-white/80 p-5">
        <div className="flex items-center gap-2 mb-4">
          <i className="fas fa-user-plus text-indigo-500" />
          <span className="font-semibold text-gray-700 text-sm">Customer Growth</span>
          <span className="text-xs text-gray-400 ml-1">new registrations per month</span>
        </div>
        {noData(growthData) ? (
          <Empty text="No customer registration data yet." />
        ) : (
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={growthData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
              <XAxis dataKey="month" tick={{ fontSize: 11, fill: '#9ca3af' }} />
              <YAxis tick={{ fontSize: 11, fill: '#9ca3af' }} allowDecimals={false} />
              <Tooltip
                contentStyle={{ borderRadius: 12, border: '1px solid #e5e7eb', fontSize: 12 }}
                cursor={{ fill: '#f5f3ff' }}
              />
              <Bar dataKey="New Customers" fill="#6366f1" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>

      {/* ── Usage by Dimension ── */}
      <div className="rounded-2xl border border-gray-100 bg-white/80 p-5">
        <div className="flex items-center gap-2 mb-4">
          <i className="fas fa-layer-group text-violet-500" />
          <span className="font-semibold text-gray-700 text-sm">Usage by Dimension</span>
          <span className="text-xs text-gray-400 ml-1">total units submitted per month</span>
        </div>
        {noData(usageData) ? (
          <Empty text="No usage data yet. Records appear after the first metering cycle." />
        ) : (
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={usageData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
              <XAxis dataKey="month" tick={{ fontSize: 11, fill: '#9ca3af' }} />
              <YAxis tick={{ fontSize: 11, fill: '#9ca3af' }} />
              <Tooltip
                contentStyle={{ borderRadius: 12, border: '1px solid #e5e7eb', fontSize: 12 }}
                cursor={{ fill: '#f5f3ff' }}
              />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              {dimensions.map((dim, i) => (
                <Bar
                  key={dim}
                  dataKey={dim}
                  stackId="usage"
                  fill={CHART_COLORS[i % CHART_COLORS.length]}
                  radius={i === dimensions.length - 1 ? [4, 4, 0, 0] : [0, 0, 0, 0]}
                />
              ))}
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>

      {/* ── Submission Health ── */}
      <div className="rounded-2xl border border-gray-100 bg-white/80 p-5">
        <div className="flex items-center gap-2 mb-4">
          <i className="fas fa-heartbeat text-emerald-500" />
          <span className="font-semibold text-gray-700 text-sm">Submission Health</span>
          <span className="text-xs text-gray-400 ml-1">AWS metering record success vs failed per month</span>
        </div>
        {noData(healthData) ? (
          <Empty text="No metering records yet." />
        ) : (
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={healthData} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
              <XAxis dataKey="month" tick={{ fontSize: 11, fill: '#9ca3af' }} />
              <YAxis tick={{ fontSize: 11, fill: '#9ca3af' }} allowDecimals={false} />
              <Tooltip
                contentStyle={{ borderRadius: 12, border: '1px solid #e5e7eb', fontSize: 12 }}
                cursor={{ fill: '#f0fdf4' }}
              />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              <Bar dataKey="Success" fill="#10b981" radius={[4, 4, 0, 0]} stackId="health" />
              <Bar dataKey="Failed" fill="#f43f5e" radius={[0, 0, 0, 0]} stackId="health" />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
};

// ─── Product detail panel ─────────────────────────────────────────────────────

type Tab = 'overview' | 'customers' | 'records' | 'events' | 'analytics';

const ProductDetailPanel: React.FC<{
  productCode: string;
  onRefreshList: () => void;
}> = ({ productCode, onRefreshList }) => {
  const [activeTab, setActiveTab] = useState<Tab>('overview');
  const { data: dashboard, loading, error, refetch } = useIsvDashboard(productCode);
  const [, setRegenerating] = useState(false);
  const [triggeringEngine, setTriggeringEngine] = useState(false);

  const handleRegenerate = useCallback(async () => {
    if (!confirm('Regenerate the API key? The old key will stop working immediately.')) return;
    setRegenerating(true);
    try {
      await regenerateApiKey(productCode);
      await refetch();
      onRefreshList();
    } catch (e: any) {
      alert('Failed: ' + (e.message ?? 'Unknown error'));
    } finally {
      setRegenerating(false);
    }
  }, [productCode, refetch, onRefreshList]);

  const handleTrigger = useCallback(async () => {
    setTriggeringEngine(true);
    try {
      await triggerMeteringEngine();
      await refetch();
    } catch (e: any) {
      alert('Failed to trigger: ' + (e.message ?? 'Unknown error'));
    } finally {
      setTriggeringEngine(false);
    }
  }, [refetch]);

  if (loading) return <Spinner text="Loading product dashboard…" />;
  if (error) return <ErrorBox message={error} />;
  if (!dashboard) return null;

  const { product, eventStats, recentMeteringRecords } = dashboard;
  const tabs: { id: Tab; label: string; icon: string }[] = [
    { id: 'overview',   label: 'Overview',                                icon: 'fa-tachometer-alt' },
    { id: 'customers',  label: `Customers (${product.activeCustomers})`,  icon: 'fa-users' },
    { id: 'records',    label: 'Metering Records',                        icon: 'fa-receipt' },
    { id: 'events',     label: 'SNS Events',                              icon: 'fa-satellite-dish' },
    { id: 'analytics',  label: 'Analytics',                               icon: 'fa-chart-bar' },
  ];

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-bold text-gray-800">{product.productName}</h2>
          <p className="text-xs text-gray-400 mt-0.5">
            <code className="bg-gray-100 px-1.5 py-0.5 rounded">{product.productCode}</code>
            {' '}· {product.ownerEmail}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <StatusBadge status={product.status} />
          <button
            onClick={handleTrigger}
            disabled={triggeringEngine}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-white bg-gradient-to-r from-violet-600 to-indigo-600 rounded-xl shadow-sm hover:shadow-md disabled:opacity-60 transition-all"
          >
            <i className={`fas fa-bolt ${triggeringEngine ? 'fa-spin' : ''}`} />
            {triggeringEngine ? 'Running…' : 'Trigger Now'}
          </button>
        </div>
      </div>

      {/* Tab bar */}
      <div className="flex items-center bg-gray-100 rounded-xl p-1 w-fit gap-1">
        {tabs.map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`flex items-center gap-1.5 px-4 py-1.5 rounded-lg text-xs font-semibold transition-all ${
              activeTab === tab.id
                ? 'bg-white text-indigo-700 shadow-sm'
                : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            <i className={`fas ${tab.icon}`} />
            {tab.label}
          </button>
        ))}
      </div>

      {/* ── Overview Tab ── */}
      {activeTab === 'overview' && (
        <div className="space-y-5">
          {/* Stats */}
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
            <StatCard icon="fa-users" label="Active Customers" value={product.activeCustomers} color="bg-gradient-to-br from-blue-500 to-indigo-600" />
            <StatCard icon="fa-layer-group" label="Total Customers" value={product.totalCustomers} color="bg-gradient-to-br from-slate-500 to-gray-600" />
            <StatCard icon="fa-clock" label="Pending Events" value={eventStats.pendingEvents} color="bg-gradient-to-br from-amber-500 to-orange-600" />
            <StatCard icon="fa-exclamation-triangle" label="Failed Events" value={eventStats.failedEvents} color="bg-gradient-to-br from-red-500 to-rose-600" alert />
            <StatCard icon="fa-receipt" label="Total Records" value={product.totalMeteringRecords} color="bg-gradient-to-br from-violet-500 to-purple-600" />
            <StatCard icon="fa-times-circle" label="Failed Records" value={product.failedMeteringRecords} color="bg-gradient-to-br from-red-600 to-pink-600" alert />
          </div>

          {/* API Key */}
          <ApiKeyDisplay
            productCode={product.productCode}
            apiKey={product.apiKey}
            onRegenerate={handleRegenerate}
          />

          {/* Integration Guide */}
          <IntegrationGuide productCode={product.productCode} apiKey={product.apiKey} />

          {/* Dimensions */}
          <DimensionsList dimensions={product.dimensions} />

          {/* Recent metering records preview */}
          {recentMeteringRecords.length > 0 && (
            <div>
              <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">
                Recent Metering Records
              </p>
              <div className="overflow-x-auto rounded-2xl border border-gray-100">
                <table className="w-full text-sm">
                  <thead className="bg-gray-50">
                    <tr>
                      {['Customer', 'Dimension', 'Qty', 'Status', 'Submitted'].map(h => (
                        <th key={h} className="py-2.5 px-4 text-xs font-semibold text-gray-500 uppercase tracking-wide text-left">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-50">
                    {recentMeteringRecords.map((r: any) => (
                      <tr key={r.id} className="hover:bg-gray-50/50">
                        <td className="py-2.5 px-4">
                          <code className="text-xs text-gray-600 bg-gray-100 px-1.5 py-0.5 rounded">
                            {r.customerIdentifier.substring(0, 12)}…
                          </code>
                        </td>
                        <td className="py-2.5 px-4">
                          <code className="text-xs text-indigo-700 bg-indigo-50 px-1.5 py-0.5 rounded">{r.dimension}</code>
                        </td>
                        <td className="py-2.5 px-4 font-semibold text-gray-700 text-xs">{r.quantity?.toLocaleString()}</td>
                        <td className="py-2.5 px-4"><StatusBadge status={r.status} /></td>
                        <td className="py-2.5 px-4 text-xs text-gray-500">{formatDate(r.submittedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ── Customers Tab ── */}
      {activeTab === 'customers' && <CustomersTab productCode={productCode} />}

      {/* ── Metering Records Tab ── */}
      {activeTab === 'records' && <MeteringRecordsTab productCode={productCode} />}

      {/* ── SNS Events Tab ── */}
      {activeTab === 'events' && <SubscriptionEventsTab productCode={productCode} />}

      {/* ── Analytics Tab ── */}
      {activeTab === 'analytics' && <AnalyticsTab productCode={productCode} />}
    </div>
  );
};

// ─── IAM Role Deploy Helper ───────────────────────────────────────────────────

const CFN_TEMPLATE_URL =
  'https://app.xamops.io/cloudformation/xamops-isv-metering-role.yml';

const XAMOPS_ACCOUNT_ID = ''; // Set this to the XamOps AWS account ID in production

function buildCfnQuickCreateUrl(productCode: string, externalId: string): string {
  const base = 'https://console.aws.amazon.com/cloudformation/home#/stacks/create/review';
  const params = new URLSearchParams({ templateURL: CFN_TEMPLATE_URL, stackName: 'xamops-isv-metering-role' });
  if (XAMOPS_ACCOUNT_ID) params.set('param_XamOpsAccountId', XAMOPS_ACCOUNT_ID);
  if (externalId)        params.set('param_ExternalId', externalId);
  if (productCode)       params.set('param_ProductCode', productCode);
  return `${base}?${params.toString()}`;
}

const IamRoleDeployHelper: React.FC<{ productCode: string; externalId: string }> = ({
  productCode, externalId,
}) => {
  const [open, setOpen] = useState(false);
  const cfnUrl = buildCfnQuickCreateUrl(productCode, externalId);

  return (
    <div className="rounded-xl border border-amber-100 bg-amber-50/60 overflow-hidden">
      {/* Header row */}
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-amber-50 transition-colors"
      >
        <div className="flex items-center gap-2">
          <i className="fas fa-shield-alt text-amber-500 text-sm" />
          <span className="text-xs font-semibold text-amber-800">
            Don't have the IAM role yet? Deploy it with one click
          </span>
        </div>
        <i className={`fas fa-chevron-${open ? 'up' : 'down'} text-amber-400 text-xs`} />
      </button>

      {open && (
        <div className="px-4 pb-4 space-y-4 border-t border-amber-100">

          {/* Explanation */}
          <p className="text-xs text-amber-700 pt-3">
            XamOps needs an IAM role in <strong>your</strong> AWS account so it can call
            {' '}<code className="bg-amber-100 px-1 rounded">BatchMeterUsage</code> and{' '}
            <code className="bg-amber-100 px-1 rounded">ResolveCustomer</code> on your behalf.
            Deploy the pre-built CloudFormation stack below — it takes about 30 seconds.
          </p>

          {/* Steps */}
          <ol className="space-y-2">
            {[
              <>Click <strong>Deploy to AWS</strong> — it opens CloudFormation in your AWS account with the template pre-loaded.</>,
              <>In the CloudFormation console, enter your <strong>XamOps Account ID</strong> and <strong>External ID</strong> (use the same External ID you enter below).</>,
              <>Click <strong>Create stack</strong>. Once complete, open the <strong>Outputs</strong> tab and copy the <strong>RoleArn</strong> value.</>,
              <>Paste the RoleArn into the "IAM Role ARN" field below.</>,
            ].map((step, i) => (
              <li key={i} className="flex items-start gap-2.5 text-xs text-amber-800">
                <span className="w-5 h-5 flex-shrink-0 rounded-full bg-amber-200 text-amber-800 flex items-center justify-center font-bold text-[10px] mt-0.5">
                  {i + 1}
                </span>
                <span>{step}</span>
              </li>
            ))}
          </ol>

          {/* Permissions granted */}
          <div className="rounded-lg bg-white/70 border border-amber-100 px-3 py-2.5">
            <p className="text-[11px] font-semibold text-gray-500 uppercase tracking-wide mb-1.5">
              Permissions granted to XamOps
            </p>
            <div className="flex flex-wrap gap-2">
              {[
                'aws-marketplace:BatchMeterUsage',
                'aws-marketplace:ResolveCustomer',
                'aws-marketplace:GetEntitlements',
              ].map(p => (
                <code key={p} className="text-[11px] bg-amber-50 border border-amber-200 text-amber-800 px-2 py-0.5 rounded-lg">
                  {p}
                </code>
              ))}
            </div>
            <p className="text-[11px] text-gray-400 mt-2">
              No other permissions are granted. The role can only be assumed by XamOps using your External ID.
            </p>
          </div>

          {/* Deploy button */}
          <a
            href={cfnUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold bg-gradient-to-r from-amber-500 to-orange-500 text-white shadow-sm hover:shadow-md hover:-translate-y-0.5 transition-all"
          >
            <i className="fab fa-aws" />
            Deploy to AWS
            <i className="fas fa-external-link-alt text-xs opacity-70" />
          </a>
        </div>
      )}
    </div>
  );
};

// ─── Register Product Modal ───────────────────────────────────────────────────

const RegisterProductModal: React.FC<{
  onClose: () => void;
  onSuccess: () => void;
}> = ({ onClose, onSuccess }) => {
  const [form, setForm] = useState<Partial<IsvProductRequest>>({
    dimensions: [{ dimensionKey: '', description: '', unit: '' }],
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const updateField = (key: keyof IsvProductRequest, value: any) =>
    setForm(f => ({ ...f, [key]: value }));

  const addDimension = () =>
    setForm(f => ({
      ...f,
      dimensions: [...(f.dimensions ?? []), { dimensionKey: '', description: '', unit: '' }],
    }));

  const removeDimension = (idx: number) =>
    setForm(f => ({ ...f, dimensions: (f.dimensions ?? []).filter((_, i) => i !== idx) }));

  const updateDimension = (idx: number, field: string, value: string) =>
    setForm(f => ({
      ...f,
      dimensions: (f.dimensions ?? []).map((d, i) => i === idx ? { ...d, [field]: value } : d),
    }));

  const handleSubmit = async () => {
    if (!form.productCode || !form.productName || !form.ownerEmail || !form.roleArn) {
      setError('productCode, productName, ownerEmail, and roleArn are required.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await createIsvProduct(form as IsvProductRequest);
      onSuccess();
      onClose();
    } catch (e: any) {
      setError(e.message ?? 'Registration failed');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto m-4">
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b border-gray-100">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-600 flex items-center justify-center">
              <i className="fas fa-plus text-white text-sm" />
            </div>
            <div>
              <h3 className="font-bold text-gray-800">Register ISV Product</h3>
              <p className="text-xs text-gray-400">Connect your AWS Marketplace SaaS listing</p>
            </div>
          </div>
          <button onClick={onClose} className="w-7 h-7 flex items-center justify-center rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-all">
            <i className="fas fa-times text-xs" />
          </button>
        </div>

        {/* Form */}
        <div className="p-6 space-y-5">
          {error && <ErrorBox message={error} />}

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {[
              { label: 'Product Code *', key: 'productCode', placeholder: 'prod-xxxx or UUID', hint: 'Exact code from your AWS Marketplace listing' },
              { label: 'Product Name *', key: 'productName', placeholder: 'My SaaS Product' },
              { label: 'Owner Email *', key: 'ownerEmail', placeholder: 'dev@mycompany.com' },
            ].map(f => (
              <div key={f.key}>
                <label className="block text-xs font-semibold text-gray-600 mb-1">{f.label}</label>
                <input
                  type="text"
                  value={(form as any)[f.key] ?? ''}
                  onChange={e => updateField(f.key as any, e.target.value)}
                  placeholder={f.placeholder}
                  className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-300 transition-all"
                />
                {f.hint && <p className="text-xs text-gray-400 mt-1">{f.hint}</p>}
              </div>
            ))}

            <div>
              <label className="block text-xs font-semibold text-gray-600 mb-1">Description</label>
              <input
                type="text"
                value={form.description ?? ''}
                onChange={e => updateField('description', e.target.value)}
                placeholder="Optional product description"
                className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-300"
              />
            </div>
          </div>

          {/* ── IAM Role deploy helper ── */}
          <IamRoleDeployHelper
            productCode={form.productCode ?? ''}
            externalId={form.externalId ?? ''}
          />

          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">IAM Role ARN *</label>
            <input
              type="text"
              value={form.roleArn ?? ''}
              onChange={e => updateField('roleArn', e.target.value)}
              placeholder="arn:aws:iam::123456789012:role/XamOpsMeteringRole"
              className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm text-gray-700 font-mono focus:outline-none focus:ring-2 focus:ring-indigo-300"
            />
            <p className="text-xs text-gray-400 mt-1">
              After deploying the stack above, copy the <strong>RoleArn</strong> output and paste it here.
            </p>
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">External ID (optional)</label>
            <input
              type="text"
              value={form.externalId ?? ''}
              onChange={e => updateField('externalId', e.target.value)}
              placeholder="STS ExternalId for role assumption security"
              className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-300"
            />
          </div>

          {/* Dimensions */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-xs font-semibold text-gray-600">Metering Dimensions</label>
              <button
                onClick={addDimension}
                className="text-xs text-indigo-600 hover:text-indigo-700 font-medium flex items-center gap-1"
              >
                <i className="fas fa-plus" />
                Add Dimension
              </button>
            </div>
            <div className="space-y-3">
              {(form.dimensions ?? []).map((dim, idx) => (
                <div key={idx} className="flex items-start gap-2 p-3 bg-gray-50 rounded-xl">
                  <div className="grid grid-cols-3 gap-2 flex-1">
                    <input
                      type="text"
                      value={dim.dimensionKey}
                      onChange={e => updateDimension(idx, 'dimensionKey', e.target.value)}
                      placeholder="Key (e.g. api_calls)"
                      className="border border-gray-200 rounded-lg px-2.5 py-1.5 text-xs text-gray-700 focus:outline-none focus:ring-1 focus:ring-indigo-300"
                    />
                    <input
                      type="text"
                      value={dim.description ?? ''}
                      onChange={e => updateDimension(idx, 'description', e.target.value)}
                      placeholder="Description"
                      className="border border-gray-200 rounded-lg px-2.5 py-1.5 text-xs text-gray-700 focus:outline-none focus:ring-1 focus:ring-indigo-300"
                    />
                    <input
                      type="text"
                      value={dim.unit ?? ''}
                      onChange={e => updateDimension(idx, 'unit', e.target.value)}
                      placeholder="Unit (e.g. calls)"
                      className="border border-gray-200 rounded-lg px-2.5 py-1.5 text-xs text-gray-700 focus:outline-none focus:ring-1 focus:ring-indigo-300"
                    />
                  </div>
                  <button
                    onClick={() => removeDimension(idx)}
                    className="w-7 h-7 flex items-center justify-center text-gray-300 hover:text-red-400 transition-colors mt-0.5"
                  >
                    <i className="fas fa-times text-xs" />
                  </button>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-gray-100 bg-gray-50 rounded-b-2xl">
          <button onClick={onClose} className="px-4 py-2 text-sm font-medium text-gray-600 hover:text-gray-800 transition-colors">
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="flex items-center gap-2 px-5 py-2 rounded-xl text-sm font-semibold bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-sm hover:shadow-md disabled:opacity-60 transition-all"
          >
            {submitting ? <><i className="fas fa-spinner fa-spin" />Registering…</> : <><i className="fas fa-check" />Register Product</>}
          </button>
        </div>
      </div>
    </div>
  );
};

// ─── Product Card (sidebar) ───────────────────────────────────────────────────

const ProductCard: React.FC<{
  product: IsvProduct;
  selected: boolean;
  onClick: () => void;
}> = ({ product, selected, onClick }) => (
  <div
    onClick={onClick}
    className={`cursor-pointer rounded-2xl border p-4 transition-all ${
      selected
        ? 'border-indigo-400 bg-indigo-50/60 shadow-md ring-2 ring-indigo-200'
        : 'border-gray-100 bg-white/70 hover:border-indigo-200 hover:shadow-sm'
    }`}
  >
    <div className="flex items-start justify-between mb-3">
      <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-600 flex items-center justify-center shadow-sm">
        <i className="fas fa-store text-white text-sm" />
      </div>
      <StatusBadge status={product.status} />
    </div>
    <h3 className="font-bold text-gray-800 text-sm truncate mb-0.5">{product.productName}</h3>
    <p className="text-xs text-gray-400 truncate mb-3">{product.ownerEmail}</p>
    <div className="flex items-center justify-between text-xs text-gray-500">
      <span><i className="fas fa-users mr-1 text-blue-400" />{product.activeCustomers} active</span>
      {product.failedMeteringRecords > 0 && (
        <span className="text-red-500"><i className="fas fa-exclamation-circle mr-1" />{product.failedMeteringRecords} failed</span>
      )}
      {product.pendingEvents > 0 && (
        <span className="text-amber-500"><i className="fas fa-clock mr-1" />{product.pendingEvents} pending</span>
      )}
    </div>
  </div>
);

// ─── Main Page ────────────────────────────────────────────────────────────────

const IsvMeteringPage: React.FC = () => {
  const { data: products, loading, error, refetch } = useIsvProducts();
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);

  const handleProductRegistered = useCallback(async () => {
    await refetch();
  }, [refetch]);

  return (
    <>
      {showModal && (
        <RegisterProductModal
          onClose={() => setShowModal(false)}
          onSuccess={handleProductRegistered}
        />
      )}

      {/* ── Page Header ── */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-800">ISV Metering Platform</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Register your AWS Marketplace SaaS product and let XamOps handle metered billing
          </p>
        </div>
        <button
          onClick={() => setShowModal(true)}
          className="flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-sm hover:shadow-md hover:-translate-y-0.5 transition-all"
        >
          <i className="fas fa-plus" />
          Register Product
        </button>
      </div>

      {loading && <Spinner text="Loading products…" />}
      {!loading && error && <ErrorBox message={error} />}

      {!loading && !error && products.length === 0 && (
        <div className="flex flex-col items-center justify-center py-24 gap-4 text-gray-400">
          <div className="w-16 h-16 rounded-2xl bg-indigo-50 flex items-center justify-center">
            <i className="fas fa-store text-3xl text-indigo-300" />
          </div>
          <p className="text-base font-medium text-gray-500">No products registered yet</p>
          <p className="text-sm text-gray-400 max-w-md text-center">
            Register your AWS Marketplace SaaS product to start metering customer usage and reporting to AWS automatically.
          </p>
          <button
            onClick={() => setShowModal(true)}
            className="px-5 py-2 rounded-xl text-sm font-semibold bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-sm hover:shadow-md transition-all"
          >
            Register First Product
          </button>
        </div>
      )}

      {!loading && !error && products.length > 0 && (
        <div className="flex gap-6">
          {/* ── Product list sidebar ── */}
          <div className="w-72 flex-shrink-0 space-y-3">
            <p className="text-xs font-semibold text-gray-400 uppercase tracking-wide px-1">
              {products.length} product{products.length !== 1 ? 's' : ''}
            </p>
            {products.map(p => (
              <ProductCard
                key={p.productCode}
                product={p}
                selected={selectedCode === p.productCode}
                onClick={() => setSelectedCode(prev => prev === p.productCode ? null : p.productCode)}
              />
            ))}
          </div>

          {/* ── Detail panel ── */}
          <div className="flex-1 min-w-0">
            {selectedCode ? (
              <ProductDetailPanel
                productCode={selectedCode}
                onRefreshList={refetch}
              />
            ) : (
              <div className="flex flex-col items-center justify-center h-64 text-gray-400 border-2 border-dashed border-gray-200 rounded-2xl">
                <i className="fas fa-hand-pointer text-3xl mb-3 text-gray-300" />
                <p className="text-sm font-medium">Select a product to view details</p>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  );
};

export default IsvMeteringPage;
