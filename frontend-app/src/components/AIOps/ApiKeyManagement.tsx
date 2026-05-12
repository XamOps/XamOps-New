import React, { useState } from 'react';
import { DialogRoot, DialogPanel } from '../ui/Dialog';
import type { ApiKeyStats, ApiKeyRotateRequest } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';
import { InfoTooltip } from '../ui/Tooltip';

interface ApiKeyManagementProps {
  keys: ApiKeyStats[];
  loading: boolean;
  onRotateKey: (id: number, req: ApiKeyRotateRequest) => Promise<void>;
  onUpdateUsageLimit: (id: number, limit: number | null) => Promise<void>;
  onDeleteProvider?: (id: number) => Promise<void>;
  onCleanupOrphanedLogs?: () => Promise<{ deleted: number; message: string }>;
  onAssignTeam?: (id: number, team: string | null) => Promise<void>;
}

const HEALTH_BADGE: Record<string, { bg: string; text: string; icon: string }> = {
  HEALTHY: { bg: 'bg-emerald-100', text: 'text-emerald-700', icon: 'fas fa-check-circle' },
  DEGRADED: { bg: 'bg-amber-100', text: 'text-amber-700', icon: 'fas fa-exclamation-triangle' },
  ERROR: { bg: 'bg-red-100', text: 'text-red-700', icon: 'fas fa-times-circle' },
  UNKNOWN: { bg: 'bg-gray-100', text: 'text-gray-500', icon: 'fas fa-question-circle' },
};

function timeAgo(isoString: string | null): string {
  if (!isoString) return 'Never';
  const diff = Date.now() - new Date(isoString).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

// ─── Rotate Key Modal ────────────────────────────────────────
function RotateKeyModal({
  isOpen,
  onClose,
  keyInfo,
  onSave,
}: {
  isOpen: boolean;
  onClose: () => void;
  keyInfo: ApiKeyStats | null;
  onSave: (id: number, req: ApiKeyRotateRequest) => void;
}) {
  const [apiKey, setApiKey] = useState('');
  const [awsAccess, setAwsAccess] = useState('');
  const [awsSecret, setAwsSecret] = useState('');
  const [saving, setSaving] = useState(false);

  React.useEffect(() => {
    if (isOpen) {
      setApiKey('');
      setAwsAccess('');
      setAwsSecret('');
    }
  }, [isOpen]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!keyInfo) return;
    setSaving(true);
    const req: ApiKeyRotateRequest = {};
    if (apiKey) req.newApiKey = apiKey;
    if (awsAccess) req.newAwsAccessKey = awsAccess;
    if (awsSecret) req.newAwsSecretKey = awsSecret;
    await onSave(keyInfo.id, req);
    setSaving(false);
    onClose();
  };

  const isBedrock = keyInfo?.provider === 'BEDROCK';

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose}>
      <DialogPanel title={`Rotate Key — ${keyInfo?.displayName || keyInfo?.provider}`}>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="p-3 bg-amber-50 border border-amber-200 rounded-lg text-xs text-amber-700">
            <i className="fas fa-exclamation-triangle mr-1" />
            Enter the new key value. The old key will be replaced immediately.
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">
              {isBedrock ? 'API Key (optional for Bedrock)' : 'New API Key'}
            </label>
            <input
              type="password"
              value={apiKey}
              onChange={e => setApiKey(e.target.value)}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none font-mono"
              placeholder="sk-..."
              required={!isBedrock}
            />
          </div>

          {isBedrock && (
            <>
              <div>
                <label className="block text-xs font-semibold text-gray-600 mb-1">New AWS Access Key</label>
                <input
                  type="password"
                  value={awsAccess}
                  onChange={e => setAwsAccess(e.target.value)}
                  className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none font-mono"
                  placeholder="AKIA..."
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-gray-600 mb-1">New AWS Secret Key</label>
                <input
                  type="password"
                  value={awsSecret}
                  onChange={e => setAwsSecret(e.target.value)}
                  className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none font-mono"
                  placeholder="wJal..."
                />
              </div>
            </>
          )}

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving}
              className="px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-amber-500 to-orange-600 rounded-lg hover:from-amber-600 hover:to-orange-700 transition-all disabled:opacity-50"
            >
              {saving ? 'Rotating...' : 'Rotate Key'}
            </button>
          </div>
        </form>
      </DialogPanel>
    </DialogRoot>
  );
}

// ─── Assign Team Modal ───────────────────────────────────────
function AssignTeamModal({
  isOpen,
  onClose,
  keyInfo,
  onSave,
}: {
  isOpen: boolean;
  onClose: () => void;
  keyInfo: ApiKeyStats | null;
  onSave: (id: number, team: string | null) => Promise<void>;
}) {
  const [teamName, setTeamName] = useState('');
  const [saving, setSaving] = useState(false);

  React.useEffect(() => {
    if (isOpen && keyInfo) {
      setTeamName(keyInfo.assignedTeam || '');
    }
  }, [isOpen, keyInfo]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!keyInfo) return;
    setSaving(true);
    await onSave(keyInfo.id, teamName.trim() || null);
    setSaving(false);
    onClose();
  };

  const handleRemove = async () => {
    if (!keyInfo) return;
    setSaving(true);
    await onSave(keyInfo.id, null);
    setSaving(false);
    onClose();
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose}>
      <DialogPanel title={`Assign Team — ${keyInfo?.displayName || keyInfo?.provider}`}>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="p-3 bg-indigo-50 border border-indigo-200 rounded-lg text-xs text-indigo-700">
            <i className="fas fa-info-circle mr-1" />
            All requests made through this key will be automatically tagged with this team name and appear in team analytics.
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">
              Team Name <span className="text-gray-400 font-normal">(e.g. backend-team, data-science, product)</span>
            </label>
            <input
              type="text"
              value={teamName}
              onChange={e => setTeamName(e.target.value)}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
              placeholder="Enter team name..."
            />
          </div>

          <div className="flex items-center justify-between pt-2">
            <div>
              {keyInfo?.assignedTeam && (
                <button
                  type="button"
                  onClick={handleRemove}
                  disabled={saving}
                  className="px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 border border-red-200 rounded-lg hover:bg-red-100 transition-colors disabled:opacity-50"
                >
                  <i className="fas fa-times mr-1" />Remove Assignment
                </button>
              )}
            </div>
            <div className="flex gap-3">
              <button
                type="button"
                onClick={onClose}
                className="px-4 py-2 text-sm font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={saving || !teamName.trim()}
                className="px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-indigo-500 to-purple-600 rounded-lg hover:from-indigo-600 hover:to-purple-700 transition-all disabled:opacity-50"
              >
                {saving ? 'Saving...' : 'Assign Team'}
              </button>
            </div>
          </div>
        </form>
      </DialogPanel>
    </DialogRoot>
  );
}

// ─── Main Component ──────────────────────────────────────────
const ApiKeyManagement: React.FC<ApiKeyManagementProps> = ({
  keys, loading, onRotateKey, onUpdateUsageLimit, onDeleteProvider, onCleanupOrphanedLogs, onAssignTeam,
}) => {
  const [rotatingKey, setRotatingKey] = useState<ApiKeyStats | null>(null);
  const [assigningTeamKey, setAssigningTeamKey] = useState<ApiKeyStats | null>(null);
  const [editingLimit, setEditingLimit] = useState<number | null>(null);
  const [limitValue, setLimitValue] = useState('');
  const [deletingKey, setDeletingKey] = useState<ApiKeyStats | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [cleanupMsg, setCleanupMsg] = useState<string | null>(null);
  const [cleaning, setCleaning] = useState(false);

  if (loading) {
    return (
      <div className="space-y-4">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="animate-pulse bg-gray-200/60 rounded-2xl h-32" />
        ))}
      </div>
    );
  }

  if (keys.length === 0) {
    return (
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm p-12 text-center">
        <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <i className="fas fa-key text-gray-400 text-2xl" />
        </div>
        <p className="text-gray-500 font-medium">No API keys configured</p>
        <p className="text-xs text-gray-400 mt-1">Add a provider to manage API keys</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Cleanup banner */}
      {onCleanupOrphanedLogs && (
        <div className="flex items-center justify-between bg-amber-50 border border-amber-200 rounded-xl px-5 py-3">
          <div className="flex items-center gap-2 text-sm text-amber-800">
            <i className="fas fa-broom" />
            <span>If you see data from a deleted provider, run a cleanup to remove orphaned log entries.</span>
          </div>
          <div className="flex items-center gap-3">
            {cleanupMsg && (
              <span className="text-xs font-medium text-emerald-700">
                <i className="fas fa-check-circle mr-1" />{cleanupMsg}
              </span>
            )}
            <button
              disabled={cleaning}
              onClick={async () => {
                setCleaning(true);
                setCleanupMsg(null);
                const res = await onCleanupOrphanedLogs();
                setCleanupMsg(res.message);
                setCleaning(false);
                setTimeout(() => setCleanupMsg(null), 5000);
              }}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-amber-700 bg-amber-100 border border-amber-300 rounded-lg hover:bg-amber-200 transition-colors disabled:opacity-50"
            >
              <i className={`fas fa-broom ${cleaning ? 'fa-spin' : ''}`} />
              {cleaning ? 'Cleaning...' : 'Clean Up Orphaned Logs'}
            </button>
          </div>
        </div>
      )}

      {/* Summary KPIs */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider inline-flex items-center gap-1">Total Keys <InfoTooltip placement="bottom">Number of API keys configured across all providers.</InfoTooltip></span>
            <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-key text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{keys.length}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider inline-flex items-center gap-1">Active <InfoTooltip placement="bottom">Keys with ACTIVE status that can receive requests.</InfoTooltip></span>
            <div className="w-8 h-8 bg-gradient-to-br from-emerald-500 to-green-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-check-circle text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{keys.filter(k => k.status === 'ACTIVE').length}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider inline-flex items-center gap-1">Healthy <InfoTooltip placement="bottom">Keys that responded without errors recently.</InfoTooltip></span>
            <div className="w-8 h-8 bg-gradient-to-br from-green-500 to-emerald-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-heartbeat text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{keys.filter(k => k.healthStatus === 'HEALTHY').length}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider inline-flex items-center gap-1">Team Assigned <InfoTooltip placement="bottom">Keys that have a team assigned for automatic usage attribution.</InfoTooltip></span>
            <div className="w-8 h-8 bg-gradient-to-br from-violet-500 to-indigo-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-users text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{keys.filter(k => k.assignedTeam).length}</p>
        </div>
      </div>

      {/* Key Cards */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {keys.map(key => {
          const health = HEALTH_BADGE[key.healthStatus] || HEALTH_BADGE.UNKNOWN;
          const usagePct = key.usageLimit && key.usageLimit > 0
            ? Math.min(100, (key.requestCount / key.usageLimit) * 100)
            : null;

          return (
            <div key={key.id} className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
              {/* Header */}
              <div className="px-5 py-4 border-b border-gray-100 flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <span
                    className="w-3 h-3 rounded-full"
                    style={{ backgroundColor: PROVIDER_COLORS[key.provider] || '#94a3b8' }}
                  />
                  <div>
                    <h4 className="text-sm font-bold text-gray-900">{key.displayName || key.provider}</h4>
                    <p className="text-xs text-gray-400">{key.provider}</p>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  {/* Team badge */}
                  {key.assignedTeam ? (
                    <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-semibold bg-indigo-100 text-indigo-700 border border-indigo-200">
                      <i className="fas fa-users text-[10px]" />
                      {key.assignedTeam}
                    </span>
                  ) : (
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium bg-gray-100 text-gray-400 border border-gray-200">
                      <i className="fas fa-user-slash text-[9px]" />
                      No Team
                    </span>
                  )}
                  <span className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-bold ${health.bg} ${health.text}`}>
                    <i className={`${health.icon} text-[10px]`} />
                    {key.healthStatus}
                  </span>
                </div>
              </div>

              {/* Body */}
              <div className="px-5 py-4 space-y-3">
                {/* Masked Key */}
                <div className="flex items-center justify-between">
                  <span className="text-xs text-gray-500">API Key</span>
                  <code className="text-xs font-mono text-gray-700 bg-gray-100 px-2 py-0.5 rounded">{key.maskedKey}</code>
                </div>

                {/* Stats */}
                <div className="grid grid-cols-3 gap-3">
                  <div className="text-center">
                    <p className="text-lg font-bold text-gray-900">{key.requestCount.toLocaleString()}</p>
                    <p className="text-xs text-gray-400 inline-flex items-center gap-1">Requests <InfoTooltip placement="bottom">Total API calls made with this key.</InfoTooltip></p>
                  </div>
                  <div className="text-center">
                    <p className="text-lg font-bold text-gray-900">{key.errorCount.toLocaleString()}</p>
                    <p className="text-xs text-gray-400 inline-flex items-center gap-1">Errors <InfoTooltip placement="bottom">Failed requests (4xx/5xx responses).</InfoTooltip></p>
                  </div>
                  <div className="text-center">
                    <p className="text-lg font-bold text-gray-900">
                      {key.requestCount > 0 ? ((key.errorCount / key.requestCount) * 100).toFixed(1) : '0.0'}%
                    </p>
                    <p className="text-xs text-gray-400 inline-flex items-center gap-1">Error Rate <InfoTooltip placement="bottom">Percentage of requests that returned errors.</InfoTooltip></p>
                  </div>
                </div>

                {/* Usage Limit Progress */}
                {usagePct !== null && (
                  <div>
                    <div className="flex items-center justify-between text-xs mb-1">
                      <span className="text-gray-500">Usage Limit</span>
                      <span className="text-gray-700 font-medium">
                        {key.requestCount.toLocaleString()} / {key.usageLimit!.toLocaleString()}
                      </span>
                    </div>
                    <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                      <div
                        className={`h-full rounded-full transition-all ${
                          usagePct > 90 ? 'bg-red-500' : usagePct > 70 ? 'bg-amber-500' : 'bg-emerald-500'
                        }`}
                        style={{ width: `${usagePct}%` }}
                      />
                    </div>
                  </div>
                )}

                {/* Last Used / Last Error */}
                <div className="flex items-center justify-between text-xs">
                  <span className="text-gray-400">
                    <i className="fas fa-clock mr-1" />Last used: {timeAgo(key.lastUsedAt)}
                  </span>
                  {key.lastErrorAt && (
                    <span className="text-red-400" title={key.lastErrorMessage || ''}>
                      <i className="fas fa-exclamation-triangle mr-1" />Error: {timeAgo(key.lastErrorAt)}
                    </span>
                  )}
                </div>

                {key.lastErrorMessage && (
                  <div className="p-2 bg-red-50 border border-red-100 rounded-lg">
                    <p className="text-xs text-red-600 truncate" title={key.lastErrorMessage}>
                      <i className="fas fa-bug mr-1" />{key.lastErrorMessage}
                    </p>
                  </div>
                )}
              </div>

              {/* Actions */}
              <div className="px-5 py-3 border-t border-gray-100 bg-gray-50/50 flex items-center gap-2 flex-wrap">
                <button
                  onClick={() => setRotatingKey(key)}
                  className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-amber-700 bg-amber-100 rounded-lg hover:bg-amber-200 transition-colors"
                >
                  <i className="fas fa-sync-alt" />
                  Rotate Key
                </button>
                {onAssignTeam && (
                  <button
                    onClick={() => setAssigningTeamKey(key)}
                    className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg transition-colors ${
                      key.assignedTeam
                        ? 'text-indigo-700 bg-indigo-100 hover:bg-indigo-200'
                        : 'text-violet-700 bg-violet-100 hover:bg-violet-200'
                    }`}
                  >
                    <i className="fas fa-users" />
                    {key.assignedTeam ? 'Change Team' : 'Assign Team'}
                  </button>
                )}
                {onDeleteProvider && (
                  <button
                    onClick={() => setDeletingKey(key)}
                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-700 bg-red-100 rounded-lg hover:bg-red-200 transition-colors"
                  >
                    <i className="fas fa-trash-alt" />
                    Remove
                  </button>
                )}
                {editingLimit === key.id ? (
                  <div className="flex items-center gap-1 ml-auto">
                    <input
                      type="number"
                      value={limitValue}
                      onChange={e => setLimitValue(e.target.value)}
                      className="w-24 border border-gray-200 rounded px-2 py-1 text-xs outline-none focus:ring-1 focus:ring-indigo-500"
                      placeholder="e.g. 10000"
                    />
                    <button
                      onClick={() => {
                        onUpdateUsageLimit(key.id, limitValue ? parseInt(limitValue) : null);
                        setEditingLimit(null);
                      }}
                      className="text-emerald-600 hover:text-emerald-800 text-xs font-medium"
                    >
                      Save
                    </button>
                    <button
                      onClick={() => setEditingLimit(null)}
                      className="text-gray-400 hover:text-gray-600 text-xs"
                    >
                      Cancel
                    </button>
                  </div>
                ) : (
                  <button
                    onClick={() => {
                      setEditingLimit(key.id);
                      setLimitValue(key.usageLimit?.toString() || '');
                    }}
                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-indigo-700 bg-indigo-100 rounded-lg hover:bg-indigo-200 transition-colors ml-auto"
                  >
                    <i className="fas fa-tachometer-alt" />
                    {key.usageLimit ? 'Edit Limit' : 'Set Limit'}
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Rotate Key Modal */}
      <RotateKeyModal
        isOpen={!!rotatingKey}
        onClose={() => setRotatingKey(null)}
        keyInfo={rotatingKey}
        onSave={onRotateKey}
      />

      {/* Assign Team Modal */}
      {onAssignTeam && (
        <AssignTeamModal
          isOpen={!!assigningTeamKey}
          onClose={() => setAssigningTeamKey(null)}
          keyInfo={assigningTeamKey}
          onSave={onAssignTeam}
        />
      )}

      {/* Delete Confirmation Modal */}
      <DialogRoot isOpen={!!deletingKey} onClose={() => setDeletingKey(null)}>
        <DialogPanel title="Remove Provider">
          <div className="space-y-4">
            <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-xs text-red-700">
              <i className="fas fa-exclamation-triangle mr-1" />
              This will permanently remove <strong>{deletingKey?.displayName || deletingKey?.provider}</strong> along with its API key,
              tracking token, and all associated usage logs.
            </div>
            <p className="text-sm text-gray-600">
              Are you sure you want to remove this provider? This action cannot be undone.
            </p>
            <div className="flex justify-end gap-3 pt-2">
              <button
                type="button"
                onClick={() => setDeletingKey(null)}
                className="px-4 py-2 text-sm font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={deleting}
                onClick={async () => {
                  if (!deletingKey || !onDeleteProvider) return;
                  setDeleting(true);
                  await onDeleteProvider(deletingKey.id);
                  setDeleting(false);
                  setDeletingKey(null);
                }}
                className="px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-red-500 to-red-600 rounded-lg hover:from-red-600 hover:to-red-700 transition-all disabled:opacity-50"
              >
                {deleting ? 'Removing...' : 'Remove Provider'}
              </button>
            </div>
          </div>
        </DialogPanel>
      </DialogRoot>
    </div>
  );
};

export default ApiKeyManagement;
