import React, { useState } from 'react';
import { DialogRoot, DialogPanel } from '../ui/Dialog';
import type { RoutingRule, RoutingRuleCreateRequest, RoutingStats, RoutingHistoryEntry } from '../../types/aiops';

interface CostOptimizerTabProps {
  rules: RoutingRule[];
  stats: RoutingStats | null;
  history: RoutingHistoryEntry[];
  rulesLoading: boolean;
  statsLoading: boolean;
  historyLoading: boolean;
  saving: boolean;
  onCreateRule: (req: RoutingRuleCreateRequest) => Promise<void>;
  onUpdateRule: (id: number, req: RoutingRuleCreateRequest) => Promise<void>;
  onToggleRule: (id: number, enabled: boolean) => Promise<void>;
  onDeleteRule: (id: number) => Promise<void>;
}

function formatCurrency(value: number): string {
  if (value >= 1000) return '$' + (value / 1000).toFixed(1) + 'k';
  if (value < 0.01 && value > 0) return '$' + value.toFixed(6);
  return '$' + value.toFixed(2);
}

// ─── Routing Rule Modal ─────────────────────────────────────
function RoutingRuleModal({
  isOpen,
  onClose,
  onSave,
  editRule,
  saving,
}: {
  isOpen: boolean;
  onClose: () => void;
  onSave: (req: RoutingRuleCreateRequest, id?: number) => void;
  editRule: RoutingRule | null;
  saving: boolean;
}) {
  const [form, setForm] = useState<RoutingRuleCreateRequest>({
    ruleName: editRule?.ruleName || '',
    sourceModel: editRule?.sourceModel || '',
    targetModel: editRule?.targetModel || '',
    conditionType: editRule?.conditionType || 'AGENT_NAME',
    conditionValue: editRule?.conditionValue || '*',
    priority: editRule?.priority ?? 0,
  });

  React.useEffect(() => {
    if (isOpen) {
      setForm({
        ruleName: editRule?.ruleName || '',
        sourceModel: editRule?.sourceModel || '',
        targetModel: editRule?.targetModel || '',
        conditionType: editRule?.conditionType || 'AGENT_NAME',
        conditionValue: editRule?.conditionValue || '*',
        priority: editRule?.priority ?? 0,
      });
    }
  }, [isOpen, editRule]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSave(form, editRule?.id);
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose}>
      <DialogPanel title={editRule ? 'Edit Routing Rule' : 'Create Routing Rule'}>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">Rule Name</label>
            <input
              type="text"
              required
              value={form.ruleName}
              onChange={e => setForm({ ...form, ruleName: e.target.value })}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
              placeholder="e.g. Downgrade GPT-4 for summaries"
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-semibold text-gray-600 mb-1">Source Model</label>
              <input
                type="text"
                required
                value={form.sourceModel}
                onChange={e => setForm({ ...form, sourceModel: e.target.value })}
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
                placeholder="e.g. gpt-4o"
              />
            </div>
            <div>
              <label className="block text-xs font-semibold text-gray-600 mb-1">Target Model</label>
              <input
                type="text"
                required
                value={form.targetModel}
                onChange={e => setForm({ ...form, targetModel: e.target.value })}
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
                placeholder="e.g. gpt-4o-mini"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-semibold text-gray-600 mb-1">Condition Type</label>
              <select
                value={form.conditionType}
                onChange={e => setForm({ ...form, conditionType: e.target.value })}
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none bg-white"
              >
                <option value="AGENT_NAME">Agent Name</option>
                <option value="MAX_TOKENS">Max Tokens</option>
                <option value="REQUEST_PATH">Request Path</option>
              </select>
            </div>
            <div>
              <label className="block text-xs font-semibold text-gray-600 mb-1">Condition Value</label>
              <input
                type="text"
                required
                value={form.conditionValue}
                onChange={e => setForm({ ...form, conditionValue: e.target.value })}
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
                placeholder="* for all"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">Priority (lower = higher priority)</label>
            <input
              type="number"
              value={form.priority}
              onChange={e => setForm({ ...form, priority: parseInt(e.target.value) || 0 })}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
            />
          </div>

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
              className="px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-indigo-500 to-purple-600 rounded-lg hover:from-indigo-600 hover:to-purple-700 transition-all disabled:opacity-50"
            >
              {saving ? 'Saving...' : editRule ? 'Update Rule' : 'Create Rule'}
            </button>
          </div>
        </form>
      </DialogPanel>
    </DialogRoot>
  );
}

// ─── Main Component ─────────────────────────────────────────
const CostOptimizerTab: React.FC<CostOptimizerTabProps> = ({
  rules, stats, history,
  rulesLoading, statsLoading, historyLoading, saving,
  onCreateRule, onUpdateRule, onToggleRule, onDeleteRule,
}) => {
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<RoutingRule | null>(null);

  const handleSave = async (req: RoutingRuleCreateRequest, id?: number) => {
    if (id) {
      await onUpdateRule(id, req);
    } else {
      await onCreateRule(req);
    }
    setIsModalOpen(false);
    setEditingRule(null);
  };

  const handleEdit = (rule: RoutingRule) => {
    setEditingRule(rule);
    setIsModalOpen(true);
  };

  // Loading skeleton
  if (rulesLoading && statsLoading) {
    return (
      <div className="space-y-4">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="animate-pulse bg-gray-200/60 rounded-2xl h-24" />
          ))}
        </div>
        <div className="animate-pulse bg-gray-200/60 rounded-2xl h-64" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Stats KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Active Rules</span>
            <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-route text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{rules.filter(r => r.enabled).length}</p>
          <p className="text-xs text-gray-400 mt-1">{rules.length} total rules</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Routed Requests</span>
            <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-cyan-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-exchange-alt text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{stats?.totalRoutedRequests?.toLocaleString() || 0}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Actual Spend</span>
            <div className="w-8 h-8 bg-gradient-to-br from-emerald-500 to-green-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-dollar-sign text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{formatCurrency(stats?.actualSpendUsd || 0)}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Est. Savings</span>
            <div className="w-8 h-8 bg-gradient-to-br from-amber-500 to-orange-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-piggy-bank text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{formatCurrency(stats?.estimatedSavingsUsd || 0)}</p>
        </div>
      </div>

      {/* Routing Rules Table */}
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-list text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Routing Rules</h3>
            <span className="text-xs text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">
              {rules.length} rules
            </span>
          </div>
          <button
            onClick={() => { setEditingRule(null); setIsModalOpen(true); }}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-indigo-500 to-purple-600 rounded-lg hover:from-indigo-600 hover:to-purple-700 transition-all shadow-sm"
          >
            <i className="fas fa-plus" />
            Add Rule
          </button>
        </div>

        {rules.length === 0 ? (
          <div className="p-12 text-center">
            <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-route text-gray-400 text-2xl" />
            </div>
            <p className="text-gray-500 font-medium">No routing rules configured</p>
            <p className="text-xs text-gray-400 mt-1">Create rules to reroute requests from expensive models to cheaper alternatives</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 text-left">
                  <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Status</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Rule Name</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Source → Target</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Condition</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Priority</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {rules.map(rule => (
                  <tr key={rule.id} className="hover:bg-gray-50/50 transition-colors">
                    <td className="px-6 py-3">
                      <button
                        onClick={() => onToggleRule(rule.id, !rule.enabled)}
                        className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
                          rule.enabled ? 'bg-emerald-500' : 'bg-gray-300'
                        }`}
                      >
                        <span
                          className={`inline-block h-3.5 w-3.5 rounded-full bg-white transition-transform ${
                            rule.enabled ? 'translate-x-4.5' : 'translate-x-0.5'
                          }`}
                          style={{ transform: rule.enabled ? 'translateX(18px)' : 'translateX(2px)' }}
                        />
                      </button>
                    </td>
                    <td className="px-4 py-3 font-medium text-gray-900">{rule.ruleName}</td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-mono bg-red-50 text-red-700 px-2 py-0.5 rounded">{rule.sourceModel}</span>
                        <i className="fas fa-arrow-right text-gray-400 text-[10px]" />
                        <span className="text-xs font-mono bg-emerald-50 text-emerald-700 px-2 py-0.5 rounded">{rule.targetModel}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs font-semibold text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded-full uppercase">
                        {rule.conditionType}
                      </span>
                      <span className="text-xs text-gray-500 ml-2">{rule.conditionValue}</span>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-700">{rule.priority}</td>
                    <td className="px-4 py-3 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <button
                          onClick={() => handleEdit(rule)}
                          className="text-indigo-500 hover:text-indigo-700 transition-colors"
                          title="Edit"
                        >
                          <i className="fas fa-edit text-xs" />
                        </button>
                        <button
                          onClick={() => {
                            if (window.confirm(`Delete rule "${rule.ruleName}"?`)) {
                              onDeleteRule(rule.id);
                            }
                          }}
                          className="text-red-400 hover:text-red-600 transition-colors"
                          title="Delete"
                        >
                          <i className="fas fa-trash text-xs" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Savings by Route */}
      {stats && stats.savingsByRule.length > 0 && (
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-emerald-500 to-green-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-chart-pie text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Routing Breakdown</h3>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 text-left">
                  <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Source → Target</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Routed Count</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Actual Cost</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {stats.savingsByRule.map((entry, idx) => (
                  <tr key={idx} className="hover:bg-gray-50/50 transition-colors">
                    <td className="px-6 py-3">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-mono bg-red-50 text-red-700 px-2 py-0.5 rounded">{entry.sourceModel}</span>
                        <i className="fas fa-arrow-right text-gray-400 text-[10px]" />
                        <span className="text-xs font-mono bg-emerald-50 text-emerald-700 px-2 py-0.5 rounded">{entry.targetModel}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-700">{entry.routedCount.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right font-medium text-gray-900">{formatCurrency(entry.actualCost)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Recent Routing History */}
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
          <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-lg flex items-center justify-center">
            <i className="fas fa-history text-white text-xs" />
          </div>
          <h3 className="text-sm font-bold text-gray-800">Recent Routed Requests</h3>
        </div>

        {historyLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="animate-pulse bg-gray-200/60 rounded-xl h-10" />
            ))}
          </div>
        ) : history.length === 0 ? (
          <div className="p-8 text-center">
            <p className="text-gray-400 text-sm">No routed requests yet</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 text-left">
                  <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Timestamp</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Original → Routed</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Agent</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Tokens</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Cost</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {history.map(entry => (
                  <tr key={entry.id} className="hover:bg-gray-50/50 transition-colors">
                    <td className="px-6 py-3 text-gray-600 text-xs">
                      {new Date(entry.timestamp).toLocaleString()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-mono bg-red-50 text-red-700 px-2 py-0.5 rounded">{entry.routedFrom}</span>
                        <i className="fas fa-arrow-right text-gray-400 text-[10px]" />
                        <span className="text-xs font-mono bg-emerald-50 text-emerald-700 px-2 py-0.5 rounded">{entry.routedTo}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-gray-700">{entry.agentName || '—'}</td>
                    <td className="px-4 py-3 text-right text-gray-700">
                      {(entry.inputTokens + entry.outputTokens).toLocaleString()}
                    </td>
                    <td className="px-4 py-3 text-right font-medium text-gray-900">{formatCurrency(entry.cost)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Routing Rule Modal */}
      <RoutingRuleModal
        isOpen={isModalOpen}
        onClose={() => { setIsModalOpen(false); setEditingRule(null); }}
        onSave={handleSave}
        editRule={editingRule}
        saving={saving}
      />
    </div>
  );
};

export default CostOptimizerTab;
