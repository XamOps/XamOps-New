import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import { DialogRoot, DialogPanel } from '../ui/Dialog';
import type { RoutingRule, RoutingRuleCreateRequest, RoutingStats, RoutingHistoryEntry } from '../../types/aiops';
import { InfoTooltip } from '../ui/Tooltip';

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
            <label className="block text-xs font-semibold text-[rgba(0,0,0,0.6)] mb-1">Rule Name</label>
            <input
              type="text"
              required
              value={form.ruleName}
              onChange={e => setForm({ ...form, ruleName: e.target.value })}
              className="w-full bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
              placeholder="e.g. Downgrade GPT-4 for summaries"
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-semibold text-[rgba(0,0,0,0.6)] mb-1">Source Model</label>
              <input
                type="text"
                required
                value={form.sourceModel}
                onChange={e => setForm({ ...form, sourceModel: e.target.value })}
                className="w-full bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
                placeholder="e.g. gpt-4o"
              />
            </div>
            <div>
              <label className="block text-xs font-semibold text-[rgba(0,0,0,0.6)] mb-1">Target Model</label>
              <input
                type="text"
                required
                value={form.targetModel}
                onChange={e => setForm({ ...form, targetModel: e.target.value })}
                className="w-full bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
                placeholder="e.g. gpt-4o-mini"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-semibold text-[rgba(0,0,0,0.6)] mb-1">Condition Type</label>
              <select
                value={form.conditionType}
                onChange={e => setForm({ ...form, conditionType: e.target.value })}
                className="w-full bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
              >
                <option value="AGENT_NAME">Agent Name</option>
                <option value="MAX_TOKENS">Max Tokens</option>
                <option value="REQUEST_PATH">Request Path</option>
              </select>
            </div>
            <div>
              <label className="block text-xs font-semibold text-[rgba(0,0,0,0.6)] mb-1">Condition Value</label>
              <input
                type="text"
                required
                value={form.conditionValue}
                onChange={e => setForm({ ...form, conditionValue: e.target.value })}
                className="w-full bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
                placeholder="* for all"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-semibold text-[rgba(0,0,0,0.6)] mb-1">Priority (lower = higher priority)</label>
            <input
              type="number"
              value={form.priority}
              onChange={e => setForm({ ...form, priority: parseInt(e.target.value) || 0 })}
              className="w-full bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
            />
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm font-medium text-[rgba(0,0,0,0.7)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-md hover:bg-[rgba(0,0,0,0.06)] transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving}
              className="px-4 py-2 text-sm font-medium text-white bg-[rgba(0,0,0,0.88)] hover:bg-black rounded-md transition-all disabled:opacity-50"
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
            <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-24" />
          ))}
        </div>
        <div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-64" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Stats KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] inline-flex items-center gap-1">Active Rules <InfoTooltip placement="bottom">Number of routing rules currently enabled.</InfoTooltip></span>
            <div className="w-8 h-8 flex items-center justify-center bg-indigo-50 border border-indigo-100 rounded-lg">
              <Icon icon="mdi:directions-fork" width={16} color="#4F46E5" />
            </div>
          </div>
          <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{rules.filter(r => r.enabled).length}</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">{rules.length} total rules</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] inline-flex items-center gap-1">Routed Requests <InfoTooltip placement="bottom">Total requests redirected to cheaper models by routing rules.</InfoTooltip></span>
            <div className="w-8 h-8 flex items-center justify-center bg-indigo-50 border border-indigo-100 rounded-lg">
              <Icon icon="mdi:swap-horizontal" width={16} color="#4F46E5" />
            </div>
          </div>
          <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{stats?.totalRoutedRequests?.toLocaleString() || 0}</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] inline-flex items-center gap-1">Actual Spend <InfoTooltip placement="bottom">Real cost of routed requests using the cheaper target model.</InfoTooltip></span>
            <div className="w-8 h-8 flex items-center justify-center bg-indigo-50 border border-indigo-100 rounded-lg">
              <Icon icon="mdi:currency-usd" width={16} color="#4F46E5" />
            </div>
          </div>
          <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(stats?.actualSpendUsd || 0)}</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] inline-flex items-center gap-1">Est. Savings <InfoTooltip placement="bottom">Estimated savings vs original model pricing.</InfoTooltip></span>
            <div className="w-8 h-8 flex items-center justify-center bg-indigo-50 border border-indigo-100 rounded-lg">
              <Icon icon="mdi:piggy-bank-outline" width={16} color="#4F46E5" />
            </div>
          </div>
          <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(stats?.estimatedSavingsUsd || 0)}</p>
        </div>
      </div>

      {/* Routing Rules Table */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 flex items-center justify-center bg-indigo-50 border border-indigo-100 rounded-lg">
              <Icon icon="mdi:format-list-bulleted" width={16} color="#4F46E5" />
            </div>
            <h3 className="text-sm font-bold text-[rgba(0,0,0,0.7)]">Routing Rules</h3>
            <InfoTooltip placement="right">Rules that automatically redirect requests from expensive models to cheaper alternatives.</InfoTooltip>
            <span className="text-xs text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)] px-2 py-0.5 rounded-full">
              {rules.length} rules
            </span>
          </div>
          <button
            onClick={() => { setEditingRule(null); setIsModalOpen(true); }}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-[rgba(0,0,0,0.88)] hover:bg-black rounded-md transition-all shadow-sm"
          >
            <Icon icon="mdi:plus" width={14} />
            Add Rule
          </button>
        </div>

        {rules.length === 0 ? (
          <div className="p-12 text-center">
            <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:directions-fork" width={28} color="#9ca3af" />
            </div>
            <p className="text-[rgba(0,0,0,0.5)] font-medium">No routing rules configured</p>
            <p className="text-xs text-[rgba(0,0,0,0.4)] mt-1">Create rules to reroute requests from expensive models to cheaper alternatives</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)] text-left">
                  <th className="px-6 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Status</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Rule Name</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Source → Target</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Condition</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Priority</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                {rules.map(rule => (
                  <tr key={rule.id} className="hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                    <td className="px-6 py-3">
                      <button
                        onClick={() => onToggleRule(rule.id, !rule.enabled)}
                        className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
                          rule.enabled ? 'bg-emerald-500' : 'bg-[rgba(0,0,0,0.15)]'
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
                    <td className="px-4 py-3 font-medium text-[rgba(0,0,0,0.88)]">{rule.ruleName}</td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-mono bg-red-50 text-red-700 px-2 py-0.5 rounded">{rule.sourceModel}</span>
                        <Icon icon="mdi:arrow-right" width={12} color="#9ca3af" />
                        <span className="text-xs font-mono bg-emerald-50 text-emerald-700 px-2 py-0.5 rounded">{rule.targetModel}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs font-semibold text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded-full uppercase">
                        {rule.conditionType}
                      </span>
                      <span className="text-xs text-[rgba(0,0,0,0.5)] ml-2">{rule.conditionValue}</span>
                    </td>
                    <td className="px-4 py-3 text-right text-[rgba(0,0,0,0.7)]">{rule.priority}</td>
                    <td className="px-4 py-3 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <button
                          onClick={() => handleEdit(rule)}
                          className="text-indigo-500 hover:text-indigo-700 transition-colors"
                          title="Edit"
                        >
                          <Icon icon="mdi:pencil-outline" width={14} />
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
                          <Icon icon="mdi:trash-can-outline" width={14} />
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
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
            <div className="w-8 h-8 flex items-center justify-center bg-indigo-50 border border-indigo-100 rounded-lg">
              <Icon icon="mdi:chart-pie" width={16} color="#4F46E5" />
            </div>
            <h3 className="text-sm font-bold text-[rgba(0,0,0,0.7)]">Routing Breakdown</h3>
            <InfoTooltip placement="right">Cost and count breakdown for each source-to-target routing path.</InfoTooltip>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)] text-left">
                  <th className="px-6 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Source → Target</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Routed Count</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Actual Cost</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                {stats.savingsByRule.map((entry, idx) => (
                  <tr key={idx} className="hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                    <td className="px-6 py-3">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-mono bg-red-50 text-red-700 px-2 py-0.5 rounded">{entry.sourceModel}</span>
                        <Icon icon="mdi:arrow-right" width={12} color="#9ca3af" />
                        <span className="text-xs font-mono bg-emerald-50 text-emerald-700 px-2 py-0.5 rounded">{entry.targetModel}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right text-[rgba(0,0,0,0.7)]">{entry.routedCount.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right font-medium text-[rgba(0,0,0,0.88)]">{formatCurrency(entry.actualCost)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Recent Routing History */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
          <div className="w-8 h-8 flex items-center justify-center bg-indigo-50 border border-indigo-100 rounded-lg">
            <Icon icon="mdi:history" width={16} color="#4F46E5" />
          </div>
          <h3 className="text-sm font-bold text-[rgba(0,0,0,0.7)]">Recent Routed Requests</h3>
          <InfoTooltip placement="right">Individual requests that were rerouted by active routing rules.</InfoTooltip>
        </div>

        {historyLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-10" />
            ))}
          </div>
        ) : history.length === 0 ? (
          <div className="p-8 text-center">
            <p className="text-[rgba(0,0,0,0.4)] text-sm">No routed requests yet</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)] text-left">
                  <th className="px-6 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Timestamp</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Original → Routed</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Agent</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Tokens</th>
                  <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Cost</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                {history.map(entry => (
                  <tr key={entry.id} className="hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                    <td className="px-6 py-3 text-[rgba(0,0,0,0.6)] text-xs">
                      {new Date(entry.timestamp).toLocaleString()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-mono bg-red-50 text-red-700 px-2 py-0.5 rounded">{entry.routedFrom}</span>
                        <Icon icon="mdi:arrow-right" width={12} color="#9ca3af" />
                        <span className="text-xs font-mono bg-emerald-50 text-emerald-700 px-2 py-0.5 rounded">{entry.routedTo}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-[rgba(0,0,0,0.7)]">{entry.agentName || '—'}</td>
                    <td className="px-4 py-3 text-right text-[rgba(0,0,0,0.7)]">
                      {(entry.inputTokens + entry.outputTokens).toLocaleString()}
                    </td>
                    <td className="px-4 py-3 text-right font-medium text-[rgba(0,0,0,0.88)]">{formatCurrency(entry.cost)}</td>
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
