/**
 * EKS Autoscaling Tab — XamOps Automation Agent
 *
 * Full CAST.ai-equivalent autoscaling UI for EKS clusters.
 * Covers: Agent status, Scale-Out (unschedulable pods), Scale-In (Evictor
 * bin-packing), Workload Rightsizer (VPA), Node Templates, Rebalancer,
 * Safety Guards, and a live activity feed.
 *
 * Spot handling is delegated to XamOps Spot Automation.
 */

import React, { useState } from 'react';
import { useEksAutoscaling, WorkloadRecommendation, EvictorCandidate } from '../../../hooks/useEksAutoscaling';
import { AriaSlider } from '../../ui';
import {
  AutoscalerMode,
  ApplyMode,
  NodeTemplate,
  ScaleOutPolicy,
  EvictorPolicy,
  WorkloadRightsizerPolicy,
  RebalancerConfig,
  SafetyGuards,
  AutoscalingEvent,
  NodeOptimizerPolicy,
} from '../../../types/eksTypes';

// ─── Helpers ─────────────────────────────────────────────────────────────────

const timeSince = (iso: string | null): string => {
  if (!iso) return 'Never';
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 60) return `${Math.floor(diff)}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  return `${Math.floor(diff / 86400)}d ago`;
};

const eventIcon = (type: AutoscalingEvent['type']) => {
  const map: Record<string, string> = {
    'scale-out': 'fa-plus-circle',
    'scale-in': 'fa-minus-circle',
    'rebalance': 'fa-sync-alt',
    'workload-resize': 'fa-expand-alt',
    'spot-fallback': 'fa-exclamation-circle',
    'node-replace': 'fa-exchange-alt',
  };
  return map[type] || 'fa-circle';
};

const eventColor = (severity: AutoscalingEvent['severity']) => {
  const map: Record<string, string> = {
    success: 'text-green-600',
    info: 'text-blue-600',
    warning: 'text-amber-600',
  };
  return map[severity] || 'text-gray-500';
};

const eventBg = (severity: AutoscalingEvent['severity']) => {
  const map: Record<string, string> = {
    success: 'bg-green-50 border-green-100',
    info: 'bg-blue-50 border-blue-100',
    warning: 'bg-amber-50 border-amber-100',
  };
  return map[severity] || 'bg-gray-50 border-gray-100';
};

// ─── Sub-section wrapper ──────────────────────────────────────────────────────
const Section: React.FC<{
  icon: string;
  color: string;
  title: string;
  subtitle?: string;
  badge?: React.ReactNode;
  children: React.ReactNode;
}> = ({ icon, color, title, subtitle, badge, children }) => (
  <section className="bg-white rounded-lg border border-[#d0d7de] overflow-hidden">
    <div className="px-6 py-4 border-b border-[#e8ecf0] flex items-center justify-between">
      <div className="flex items-center gap-3">
        <div className={`w-8 h-8 rounded-md ${color} flex items-center justify-center`}>
          <i className={`fas ${icon} text-white text-sm`}></i>
        </div>
        <div>
          <h3 className="text-[13px] font-semibold text-[#1a1f2e]">{title}</h3>
          {subtitle && <p className="text-[11px] text-[#57606a] mt-0.5">{subtitle}</p>}
        </div>
      </div>
      {badge}
    </div>
    <div className="p-6">{children}</div>
  </section>
);

// ─── Toggle Switch ────────────────────────────────────────────────────────────
const Toggle: React.FC<{
  checked: boolean;
  onChange: (v: boolean) => void;
  label: string;
  description?: string;
  disabled?: boolean;
}> = ({ checked, onChange, label, description, disabled }) => (
  <label className={`flex items-start gap-3 cursor-pointer ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`}>
    <div className="relative mt-0.5 shrink-0">
      <input
        type="checkbox"
        className="sr-only"
        checked={checked}
        disabled={disabled}
        onChange={e => onChange(e.target.checked)}
      />
      <div
        onClick={() => !disabled && onChange(!checked)}
        className={`w-10 h-5 rounded-full transition-colors duration-200 ${checked ? 'bg-purple-600' : 'bg-gray-300'}`}
      >
        <div className={`absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform duration-200 ${checked ? 'translate-x-5' : 'translate-x-0'}`} />
      </div>
    </div>
    <div>
      <div className="text-sm font-semibold text-gray-800">{label}</div>
      {description && <div className="text-xs text-gray-500 mt-0.5">{description}</div>}
    </div>
  </label>
);

// ─── Slider with label ────────────────────────────────────────────────────────
const SliderField: React.FC<{
  label: string;
  value: number;
  min: number;
  max: number;
  step?: number;
  unit?: string;
  onChange: (v: number) => void;
  color?: string;
}> = ({ label, value, min, max, step = 1, unit = '', onChange }) => (
  <AriaSlider
    label={label}
    value={value}
    onChange={onChange}
    minValue={min}
    maxValue={max}
    step={step}
    formatValue={(v) => `${v}${unit}`}
    fillColor="rgb(124 58 237)"
  />
);

// ─── Number input field ───────────────────────────────────────────────────────
const NumberField: React.FC<{
  label: string;
  value: number;
  min?: number;
  max?: number;
  unit?: string;
  onChange: (v: number) => void;
}> = ({ label, value, min, max, unit, onChange }) => (
  <div>
    <label className="block text-xs font-semibold text-gray-600 mb-1.5">{label}</label>
    <div className="flex items-center gap-2">
      <input
        type="number"
        min={min}
        max={max}
        value={value}
        onChange={e => onChange(Number(e.target.value))}
        className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm font-semibold text-gray-800 focus:outline-none focus:ring-2 focus:ring-purple-400 focus:border-transparent"
      />
      {unit && <span className="text-xs text-gray-500 shrink-0">{unit}</span>}
    </div>
  </div>
);

// ─── Save Button ──────────────────────────────────────────────────────────────
const SaveButton: React.FC<{ onClick: () => void; saving: boolean; label?: string }> = ({
  onClick, saving, label = 'Save Changes'
}) => (
  <button
    onClick={onClick}
    disabled={saving}
    className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white text-xs font-bold rounded-lg shadow hover:bg-purple-700 transition-all disabled:opacity-50 active:scale-95"
  >
    {saving
      ? <><i className="fas fa-spinner fa-spin"></i> Saving…</>
      : <><i className="fas fa-save"></i> {label}</>
    }
  </button>
);

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN TAB COMPONENT
// ═══════════════════════════════════════════════════════════════════════════════
const EksAutoscalingTab: React.FC<{ eksData: any }> = (_props) => {
  const { clusterName, region, accountId } = (() => {
    const params = new URLSearchParams(window.location.search);
    return {
      clusterName: params.get('name'),
      region: params.get('region') || 'us-east-1',
      accountId: params.get('accountIds'),
    };
  })();

  const {
    autoscalerData,
    rightsizingData,
    evictorCandidates,
    liveRebalanceStatus,
    nodeFleetReport,
    loading,
    isSaving,
    isRebalancing,
    refresh,
    setAutoscalingEnabled,
    saveScaleOutPolicy,
    saveEvictorPolicy,
    saveWorkloadRightsizerPolicy,
    saveSafetyGuards,
    saveNodeTemplate,
    deleteNodeTemplate,
    saveRebalancerConfig,
    saveNodeOptimizerPolicy,
    triggerRebalance,
    toggleAutoscalerMode,
    editingTemplateId,
    setEditingTemplateId,
    saveSuccess,
  } = useEksAutoscaling({ clusterName, region, accountId });

  // Local draft states (each section edits locally, saved on button click)
  const [scaleOut, setScaleOut] = useState<ScaleOutPolicy | null>(null);
  const [evictor, setEvictor] = useState<EvictorPolicy | null>(null);
  const [rightsizer, setRightsizer] = useState<WorkloadRightsizerPolicy | null>(null);
  const [guards, setGuards] = useState<SafetyGuards | null>(null);
  const [rebalancer, setRebalancerDraft] = useState<RebalancerConfig | null>(null);
  const [nodeOpt, setNodeOpt] = useState<NodeOptimizerPolicy | null>(null);
  const [editingTemplate, setEditingTemplate] = useState<NodeTemplate | null>(null);
  const [showNewTemplateForm, setShowNewTemplateForm] = useState(false);

  // Default NodeOptimizerPolicy for clusters without one configured
  const defaultNodeOpt: NodeOptimizerPolicy = {
    enabled: false,
    idleCpuThresholdPct: 10,
    idleMemThresholdPct: 20,
    autoCordonIdleNodes: false,
    autoDrainIdleNodes: false,
    handleSpotInterruptions: false,
    spotRatioTargetPct: 0,
    karpenterEnabled: false,
    maxParallelDrains: 1,
  };

  // Sync drafts from fetched data when loaded
  React.useEffect(() => {
    if (!autoscalerData) return;
    if (!scaleOut) setScaleOut(autoscalerData.scaleOutPolicy);
    if (!evictor) setEvictor(autoscalerData.evictorPolicy);
    if (!rightsizer) setRightsizer(autoscalerData.workloadRightsizerPolicy);
    if (!guards) setGuards(autoscalerData.safetyGuards);
    if (!rebalancer) setRebalancerDraft(autoscalerData.rebalancer);
    if (!nodeOpt) setNodeOpt(autoscalerData.nodeOptimizerPolicy ?? defaultNodeOpt);
  }, [autoscalerData]);

  // ── Loading skeleton ──────────────────────────────────────────────────────
  if (loading) {
    return (
      <div className="space-y-4">
        {[1, 2, 3, 4].map(i => (
          <div key={i} className="h-48 bg-gray-100 rounded-xl animate-pulse" />
        ))}
      </div>
    );
  }

  // ── Not configured — agent not installed yet ──────────────────────────────
  if (!autoscalerData) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <div className="w-16 h-16 rounded-lg bg-[#f6f8fa] border border-[#e8ecf0] flex items-center justify-center mb-5">
          <i className="fas fa-robot text-2xl text-[#8b949e]"></i>
        </div>
        <h3 className="text-xl font-bold text-gray-800 mb-2">XamOps Automation Agent Not Installed</h3>
        <p className="text-sm text-gray-500 max-w-md mb-6">
          This cluster has not been registered with XamOps. Install the agent using the Integration Guide,
          then come back to enable autoscaling.
        </p>
        <button
          onClick={() => document.dispatchEvent(new CustomEvent('open-integration-guide'))}
          className="flex items-center gap-2 px-5 py-2.5 bg-purple-600 text-white text-sm font-bold rounded-xl shadow hover:bg-purple-700 transition-all"
        >
          <i className="fas fa-book-open"></i> Open Integration Guide
        </button>
      </div>
    );
  }

  // ── Autoscaling not enabled — cluster registered but flag not set ─────────
  if (!autoscalerData.autoscalingEnabled) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <div className="w-16 h-16 rounded-lg bg-[#f6f8fa] border border-[#e8ecf0] flex items-center justify-center mb-5">
          <i className="fas fa-robot text-2xl text-[#2f4bde]"></i>
        </div>
        <h3 className="text-xl font-bold text-gray-800 mb-2">Autoscaling is Not Enabled</h3>
        <p className="text-sm text-gray-500 max-w-md mb-2">
          The XamOps Automation Agent is installed but not yet activated for this cluster.
          Click below to enable it — the agent will start syncing policies and metrics immediately.
        </p>
        <p className="text-xs text-gray-400 mb-6">
          Agent version: <span className="font-mono">{autoscalerData.agentStatus?.agentVersion ?? 'unknown'}</span>
          {autoscalerData.agentStatus?.lastSyncAt && (
            <> · Last seen: {autoscalerData.agentStatus.lastSyncAt}</>
          )}
        </p>
        <button
          onClick={() => setAutoscalingEnabled(true)}
          disabled={isSaving}
          className="flex items-center gap-2 px-6 py-3 bg-purple-600 text-white text-sm font-bold rounded-xl shadow hover:bg-purple-700 transition-all disabled:opacity-50 active:scale-95"
        >
          {isSaving
            ? <><i className="fas fa-spinner fa-spin"></i> Enabling…</>
            : <><i className="fas fa-toggle-on"></i> Enable Autoscaling for this Cluster</>
          }
        </button>
      </div>
    );
  }

  // ── Policies still loading from backend ───────────────────────────────────
  if (!scaleOut || !evictor || !rightsizer || !guards || !rebalancer || !nodeOpt) {
    return (
      <div className="space-y-4">
        {[1, 2, 3, 4].map(i => (
          <div key={i} className="h-48 bg-gray-100 rounded-xl animate-pulse" />
        ))}
      </div>
    );
  }

  const { agentStatus, nodeTemplates, recentEvents } = autoscalerData;
  // Guard: kpis may be null if autoscaler hasn't pushed metrics yet
  const kpis = autoscalerData.kpis ?? {
    nodesSavedToday: 0, costSavedThisWeek: 0, pendingPodsResolved: 0,
    activeRebalances: 0, spotRatio: 0, avgNodeUtilizationCpu: 0,
    avgNodeUtilizationMemory: 0, totalManagedNodes: 0
  };

  const allHealthy =
    agentStatus.agentHealthy &&
    agentStatus.clusterControllerHealthy &&
    agentStatus.evictorHealthy &&
    agentStatus.workloadAutoscalerHealthy;

  // ── Utility: blank new template ────────────────────────────────────────────
  const blankTemplate = (): NodeTemplate => ({
    id: `tpl-${Date.now()}`,
    name: '',
    isDefault: false,
    capacityType: 'on-demand',
    useSpotFallback: false,
    fallbackRestoreRateSeconds: 1800,
    minCpuCores: 2,
    maxCpuCores: 16,
    minRamMib: 4096,
    maxRamMib: 32768,
    architectures: ['amd64'],
    spotAutomationEnabled: false,
    customLabels: {},
    customTaints: [],
  });

  return (
    <div className="space-y-6">

      {/* ── Save Success Toast ────────────────────────────────────────────── */}
      {saveSuccess && (
        <div className="fixed top-6 right-6 z-50 bg-[#1a7f37] text-white text-[13px] font-medium px-4 py-2.5 rounded-lg border border-[#1a7f37] flex items-center gap-2 animate-fade-in">
          <i className="fas fa-check-circle"></i>
          {saveSuccess}
        </div>
      )}

      {/* ═══ 1. AGENT STATUS BANNER ═════════════════════════════════════════ */}
      <section className="bg-white rounded-lg border border-[#d0d7de] p-6">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">

          {/* Title + overall health */}
          <div className="flex items-center gap-4">
            <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${allHealthy ? 'bg-[#1a7f37]' : 'bg-[#cf222e]'}`}>
              <i className={`fas fa-robot text-white text-xl`}></i>
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-[14px] font-semibold text-[#1a1f2e]">XamOps Automation Agent</h2>
                <span className={`px-2 py-0.5 rounded-full text-[11px] font-medium ${allHealthy ? 'bg-[#dafbe1] text-[#1a7f37]' : 'bg-[#ffebe9] text-[#cf222e]'}`}>
                  {allHealthy ? 'All Systems Healthy' : 'Degraded'}
                </span>
              </div>
              <p className="text-[11px] text-[#57606a] mt-0.5">
                v{agentStatus.agentVersion} · Last sync {timeSince(agentStatus.lastSyncAt)} · Syncs every 15s
              </p>
            </div>
          </div>

          {/* Mode selector */}
          <div className="flex items-center gap-3">
            <span className="text-xs font-semibold text-gray-500">Mode:</span>
            {(['full', 'scoped', 'disabled'] as AutoscalerMode[]).map(m => (
              <button
                key={m}
                onClick={() => toggleAutoscalerMode(m)}
                className={`px-3 py-1.5 rounded-lg text-xs font-bold capitalize transition-all ${agentStatus.mode === m
                  ? m === 'disabled'
                    ? 'bg-red-100 text-red-700 border border-red-200'
                    : 'bg-purple-100 text-purple-700 border border-purple-200'
                  : 'bg-gray-100 text-gray-500 border border-gray-200 hover:bg-gray-200'
                  }`}
              >
                {m}
              </button>
            ))}
            <button
              onClick={refresh}
              className="ml-2 w-8 h-8 rounded-lg bg-gray-100 hover:bg-gray-200 flex items-center justify-center text-gray-500 transition-all"
              title="Refresh"
            >
              <i className="fas fa-sync-alt text-xs"></i>
            </button>
          </div>
        </div>

        {/* Component status grid */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-5 pt-5 border-t border-[#e8ecf0]">
          {[
            { label: 'Agent', key: 'agentHealthy', icon: 'fa-satellite-dish' },
            { label: 'Cluster Controller', key: 'clusterControllerHealthy', icon: 'fa-cogs' },
            { label: 'Evictor', key: 'evictorHealthy', icon: 'fa-compress-arrows-alt' },
            { label: 'Workload Rightsizer', key: 'workloadAutoscalerHealthy', icon: 'fa-weight' },
          ].map(comp => {
            const healthy = agentStatus[comp.key as keyof typeof agentStatus] as boolean;
            return (
              <div key={comp.key} className={`rounded-md p-3 border ${healthy ? 'bg-[#dafbe1] border-[#aceebb]' : 'bg-[#ffebe9] border-[rgba(255,129,130,0.4)]'}`}>
                <div className="flex items-center gap-2 mb-1">
                  <i className={`fas ${comp.icon} text-xs ${healthy ? 'text-[#1a7f37]' : 'text-[#cf222e]'}`}></i>
                  <span className="text-[11px] font-medium text-[#57606a]">{comp.label}</span>
                </div>
                <div className={`text-[11px] font-semibold ${healthy ? 'text-[#1a7f37]' : 'text-[#cf222e]'}`}>
                  {healthy ? 'Healthy' : 'Offline'}
                </div>
              </div>
            );
          })}
        </div>
      </section>

      {/* ═══ 2. KPI CARDS ════════════════════════════════════════════════════ */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          {
            icon: 'fa-server', color: 'bg-purple-500', label: 'Nodes Saved Today',
            value: kpis.nodesSavedToday, unit: '', suffix: 'nodes'
          },
          {
            icon: 'fa-dollar-sign', color: 'bg-green-500', label: 'Saved This Week',
            value: `$${(kpis.costSavedThisWeek ?? 0).toFixed(0)}`, unit: '', suffix: 'USD'
          },
          {
            icon: 'fa-box-open', color: 'bg-blue-500', label: 'Pending Pods Resolved',
            value: kpis.pendingPodsResolved, unit: '', suffix: 'pods'
          },
          {
            icon: 'fa-bolt', color: 'bg-amber-500', label: 'Spot Ratio',
            value: `${kpis.spotRatio}%`, unit: '', suffix: 'via XamOps Spot'
          },
        ].map(card => (
          <div key={card.label} className="bg-white rounded-lg border border-[#d0d7de] px-5 py-4">
            <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e] mb-1">{card.label}</div>
            <div className="text-[26px] font-semibold leading-none text-[#1a1f2e]">{card.value}</div>
            <div className="text-[11px] text-[#8b949e] mt-1">{card.suffix}</div>
          </div>
        ))}
      </div>

      {/* Utilization mini-bars */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {[
          { label: 'Avg CPU Utilization', value: kpis.avgNodeUtilizationCpu, color: 'bg-purple-500' },
          { label: 'Avg Memory Utilization', value: kpis.avgNodeUtilizationMemory, color: 'bg-blue-500' },
          { label: 'Managed Nodes', value: null, display: `${kpis.totalManagedNodes} nodes`, color: 'bg-gray-700' },
        ].map(m => (
          <div key={m.label} className="bg-white rounded-lg border border-[#d0d7de] p-4">
            <div className="flex justify-between items-center mb-2">
              <span className="text-[11px] font-medium text-[#57606a]">{m.label}</span>
              <span className="text-[11px] font-semibold text-[#1a1f2e]">{m.display ?? `${m.value}%`}</span>
            </div>
            {m.value !== null && (
              <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full transition-all bg-[#2f4bde]"
                  style={{ width: `${m.value}%` }}
                />
              </div>
            )}
          </div>
        ))}
      </div>

      {/* ═══ 3. SCALE-OUT POLICY ════════════════════════════════════════════ */}
      <Section
        icon="fa-arrow-up"
        color="bg-purple-500"
        title="Scale-Out Policy"
        subtitle="Trigger: unschedulable pods — bin-pack pending pods → select cheapest fitting instance → provision node"
        badge={
          <Toggle
            checked={scaleOut.enabled}
            onChange={v => setScaleOut(p => ({ ...p!, enabled: v }))}
            label=""
          />
        }
      >
        <div className="space-y-6">
          {/* Headroom */}
          <div>
            <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-3">
              Headroom (buffer capacity via placeholder pods)
            </h4>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <SliderField
                label="CPU Headroom"
                value={scaleOut.headroomCpuPercent}
                min={0} max={50} unit="%"
                onChange={v => setScaleOut(p => ({ ...p!, headroomCpuPercent: v }))}
              />
              <SliderField
                label="Memory Headroom"
                value={scaleOut.headroomMemoryPercent}
                min={0} max={50} unit="%"
                onChange={v => setScaleOut(p => ({ ...p!, headroomMemoryPercent: v }))}
              />
            </div>
          </div>

          {/* Node constraints */}
          <div>
            <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-3">
              Node Size Constraints
            </h4>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <NumberField label="Min CPU (cores)" value={scaleOut.minCpuCores} min={1} max={scaleOut.maxCpuCores}
                onChange={v => setScaleOut(p => ({ ...p!, minCpuCores: v }))} />
              <NumberField label="Max CPU (cores)" value={scaleOut.maxCpuCores} min={scaleOut.minCpuCores} max={128}
                onChange={v => setScaleOut(p => ({ ...p!, maxCpuCores: v }))} />
              <NumberField label="Min RAM (MiB)" value={scaleOut.minRamMib} min={512}
                onChange={v => setScaleOut(p => ({ ...p!, minRamMib: v }))} unit="MiB" />
              <NumberField label="Max RAM (MiB)" value={scaleOut.maxRamMib} min={scaleOut.minRamMib}
                onChange={v => setScaleOut(p => ({ ...p!, maxRamMib: v }))} unit="MiB" />
            </div>
          </div>

          {/* Node health */}
          <div>
            <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-3">
              Node Health Monitoring
            </h4>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <NumberField
                label="Node Readiness Timeout"
                value={scaleOut.nodeReadinessTimeoutMinutes}
                min={5} max={60} unit="min"
                onChange={v => setScaleOut(p => ({ ...p!, nodeReadinessTimeoutMinutes: v }))}
              />
            </div>
            <p className="text-xs text-gray-400 mt-2">
              <i className="fas fa-info-circle mr-1"></i>
              If a newly provisioned node is NotReady for longer than this timeout, it is automatically replaced.
              Kubelet heartbeat timeout is fixed at 15 minutes.
            </p>
          </div>

          <div className="flex justify-end pt-2 border-t border-gray-100">
            <SaveButton onClick={() => saveScaleOutPolicy(scaleOut)} saving={isSaving} />
          </div>
        </div>
      </Section>

      {/* ═══ 4. EVICTOR (SCALE-IN / BIN-PACKING) ═══════════════════════════ */}
      <Section
        icon="fa-compress-arrows-alt"
        color="bg-indigo-500"
        title="Evictor — Scale-In / Bin-Packing"
        subtitle="Continuously simulates pod redistribution every 60s. Drains under-utilized nodes and deletes them immediately."
        badge={
          <Toggle
            checked={evictor.enabled}
            onChange={v => setEvictor(p => ({ ...p!, enabled: v }))}
            label=""
          />
        }
      >
        <div className="space-y-6">
          {/* Key timing */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <SliderField
              label="Cycle Interval (how often bin-pack simulation runs)"
              value={evictor.nodeCycleIntervalSeconds}
              min={15} max={300} step={15} unit="s"
              onChange={v => setEvictor(p => ({ ...p!, nodeCycleIntervalSeconds: v }))}
            />
            <SliderField
              label="Node Grace Period (min age before Evictor considers a node)"
              value={evictor.nodeGracePeriodMinutes}
              min={1} max={60} unit=" min"
              onChange={v => setEvictor(p => ({ ...p!, nodeGracePeriodMinutes: v }))}
            />
          </div>

          {/* Utilization targets */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <SliderField
              label="Target CPU Utilization (consolidation threshold)"
              value={evictor.targetUtilizationCpu}
              min={50} max={95} unit="%"
              onChange={v => setEvictor(p => ({ ...p!, targetUtilizationCpu: v }))}
            />
            <SliderField
              label="Target Memory Utilization"
              value={evictor.targetUtilizationMemory}
              min={50} max={95} unit="%"
              onChange={v => setEvictor(p => ({ ...p!, targetUtilizationMemory: v }))}
            />
          </div>

          {/* Aggressive mode */}
          <div className="bg-amber-50 border border-amber-200 rounded-lg p-4">
            <Toggle
              checked={evictor.aggressiveMode}
              onChange={v => setEvictor(p => ({ ...p!, aggressiveMode: v }))}
              label="Aggressive Mode"
              description="Allows eviction of single-replica workloads. May cause brief disruption. Not recommended for production."
            />
          </div>

          {/* Pod eligibility checklist info */}
          <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
            <h4 className="text-xs font-bold text-gray-600 mb-3 flex items-center gap-2">
              <i className="fas fa-clipboard-check text-purple-600"></i>
              Evictor Eligibility Rules (enforced automatically)
            </h4>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2 text-xs text-gray-600">
              {[
                { ok: true, text: 'DaemonSet pods — always evictable (reschedule automatically)' },
                { ok: true, text: 'Static pods — always evictable' },
                { ok: false, text: 'Pods with annotation beta.evictor.cast.ai/eviction-disabled' },
                { ok: false, text: 'Nodes with label autoscaling.cast.ai/removal-disabled' },
                { ok: false, text: 'StatefulSet pods (unless CLM or marked disposable)' },
                { ok: false, text: 'Single-replica pods (unless Aggressive Mode enabled)' },
                { ok: true, text: 'PDB checked — disruptions_allowed must be > 0' },
                { ok: true, text: 'Simulation: pod must fit on another node before eviction' },
              ].map((r, i) => (
                <div key={i} className="flex items-start gap-2">
                  <i className={`fas ${r.ok ? 'fa-check-circle text-green-500' : 'fa-times-circle text-red-400'} mt-0.5 shrink-0`}></i>
                  <span>{r.text}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="flex justify-end pt-2 border-t border-gray-100">
            <SaveButton onClick={() => saveEvictorPolicy(evictor)} saving={isSaving} />
          </div>
        </div>
      </Section>

      {/* ═══ 5. WORKLOAD RIGHTSIZER (VPA) ═══════════════════════════════════ */}
      <Section
        icon="fa-weight"
        color="bg-blue-500"
        title="Workload Rightsizer"
        subtitle="VPA-equivalent: right-sizes CPU/memory requests using percentile-based historical metrics. Updates every 30 seconds."
        badge={
          <Toggle
            checked={rightsizer.enabled}
            onChange={v => setRightsizer(p => ({ ...p!, enabled: v }))}
            label=""
          />
        }
      >
        <div className="space-y-6">

          {/* Percentile settings */}
          <div>
            <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-3">
              Recommendation Percentiles
            </h4>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div>
                <SliderField
                  label="CPU Percentile (p80 = handles 80% of load scenarios)"
                  value={rightsizer.cpuPercentile}
                  min={50} max={100} step={5} unit="th"
                  onChange={v => setRightsizer(p => ({ ...p!, cpuPercentile: v }))}
                />
                <p className="text-xs text-gray-400 mt-1">
                  CPU is bursty — p80 avoids over-provisioning for top spikes.
                </p>
              </div>
              <div>
                <SliderField
                  label="Memory Percentile (p100 = always size for peak)"
                  value={rightsizer.memoryPercentile}
                  min={50} max={100} step={5} unit="th"
                  onChange={v => setRightsizer(p => ({ ...p!, memoryPercentile: v }))}
                />
                <p className="text-xs text-gray-400 mt-1">
                  OOM kills are dangerous — default is max (p100) for memory.
                </p>
              </div>
            </div>
          </div>

          {/* Overhead & threshold */}
          <div>
            <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-3">
              Overhead & Apply Threshold
            </h4>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
              <SliderField
                label="CPU Overhead Buffer"
                value={rightsizer.cpuOverheadPercent}
                min={0} max={50} unit="%"
                onChange={v => setRightsizer(p => ({ ...p!, cpuOverheadPercent: v }))}
              />
              <SliderField
                label="Memory Overhead Buffer"
                value={rightsizer.memoryOverheadPercent}
                min={0} max={50} unit="%"
                onChange={v => setRightsizer(p => ({ ...p!, memoryOverheadPercent: v }))}
              />
              <SliderField
                label="Apply Threshold (min delta to trigger change)"
                value={rightsizer.applyThresholdPercent}
                min={1} max={30} unit="%"
                onChange={v => setRightsizer(p => ({ ...p!, applyThresholdPercent: v }))}
              />
            </div>
          </div>

          {/* Lookback period */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div>
              <label className="block text-xs font-semibold text-gray-600 mb-1.5">
                Metric Lookback Period
              </label>
              <select
                value={rightsizer.lookbackHours}
                onChange={e => setRightsizer(p => ({ ...p!, lookbackHours: Number(e.target.value) }))}
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm font-semibold text-gray-800 focus:outline-none focus:ring-2 focus:ring-purple-400"
              >
                <option value={24}>24 hours (minimum confidence)</option>
                <option value={72}>3 days (default)</option>
                <option value={168}>7 days (maximum lookback)</option>
              </select>
              <p className="text-xs text-gray-400 mt-1">Minimum 24h of data required before recommendations apply.</p>
            </div>

            {/* Apply mode */}
            <div>
              <label className="block text-xs font-semibold text-gray-600 mb-1.5">Apply Mode</label>
              <div className="flex gap-3">
                {(['immediate', 'deferred'] as ApplyMode[]).map(mode => (
                  <button
                    key={mode}
                    onClick={() => setRightsizer(p => ({ ...p!, applyMode: mode }))}
                    className={`flex-1 py-2 rounded-lg text-xs font-bold capitalize border transition-all ${rightsizer.applyMode === mode
                      ? 'bg-purple-100 text-purple-700 border-purple-200'
                      : 'bg-gray-50 text-gray-500 border-gray-200 hover:bg-gray-100'
                      }`}
                  >
                    {mode === 'immediate'
                      ? <><i className="fas fa-bolt mr-1"></i>Immediate (pod restart)</>
                      : <><i className="fas fa-clock mr-1"></i>Deferred (natural restart)</>
                    }
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Surge detection + OOM info */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="bg-blue-50 border border-blue-100 rounded-lg p-4">
              <Toggle
                checked={rightsizer.surgeDetection}
                onChange={v => setRightsizer(p => ({ ...p!, surgeDetection: v }))}
                label="Surge Detection"
                description="When CPU spikes above percentile threshold, skip the 30-min cadence and apply immediately."
              />
            </div>
            <div className="bg-orange-50 border border-orange-100 rounded-lg p-4">
              <div className="flex items-start gap-2 text-xs text-orange-700">
                <i className="fas fa-fire-alt mt-0.5 text-orange-500 shrink-0"></i>
                <div>
                  <div className="font-bold mb-1">OOM Kill Detection (always on)</div>
                  When a pod is OOM-killed, memory request is boosted immediately and held for 8 hours to ensure stability before reverting to recommendation.
                </div>
              </div>
            </div>
          </div>

          <div className="flex justify-end pt-2 border-t border-gray-100">
            <SaveButton onClick={() => saveWorkloadRightsizerPolicy(rightsizer)} saving={isSaving} />
          </div>
        </div>
      </Section>

      {/* ═══ 6. NODE TEMPLATES ══════════════════════════════════════════════ */}
      <Section
        icon="fa-layer-group"
        color="bg-teal-500"
        title="Node Templates"
        subtitle="Define pools of node types for different workload classes. XamOps Spot Automation handles spot nodes."
      >
        <div className="space-y-4">
          {nodeTemplates.map(tpl => {
            const isEditing = editingTemplateId === tpl.id;

            return (
              <div
                key={tpl.id}
                className={`border rounded-xl transition-all ${isEditing ? 'border-purple-300 bg-purple-50' : 'border-gray-200 bg-gray-50'}`}
              >
                {/* Template header */}
                <div
                  className="flex items-center justify-between p-4 cursor-pointer"
                  onClick={() => {
                    if (isEditing) {
                      setEditingTemplateId(null);
                      setEditingTemplate(null);
                    } else {
                      setEditingTemplateId(tpl.id);
                      setEditingTemplate({ ...tpl });
                    }
                  }}
                >
                  <div className="flex items-center gap-3">
                    <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${tpl.capacityType === 'spot' ? 'bg-amber-100' : 'bg-blue-100'}`}>
                      <i className={`fas ${tpl.capacityType === 'spot' ? 'fa-bolt text-amber-600' : 'fa-server text-blue-600'} text-sm`}></i>
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-bold text-gray-800">{tpl.name}</span>
                        {tpl.isDefault && (
                          <span className="px-2 py-0.5 bg-purple-100 text-purple-700 text-[10px] font-bold rounded-full">DEFAULT</span>
                        )}
                        {tpl.spotAutomationEnabled && (
                          <span className="px-2 py-0.5 bg-amber-100 text-amber-700 text-[10px] font-bold rounded-full">
                            <i className="fas fa-bolt mr-1"></i>XamOps Spot
                          </span>
                        )}
                      </div>
                      <div className="text-xs text-gray-500">
                        {tpl.capacityType} · {tpl.minCpuCores}–{tpl.maxCpuCores} vCPU · {((tpl.minRamMib ?? 0) / 1024).toFixed(0)}–{((tpl.maxRamMib ?? 0) / 1024).toFixed(0)} GB RAM
                        · {tpl.architectures.join(', ')}
                      </div>
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={e => { e.stopPropagation(); deleteNodeTemplate(tpl.id); }}
                      className="w-8 h-8 rounded-lg bg-red-50 hover:bg-red-100 flex items-center justify-center text-red-500 transition-all"
                      title="Delete template"
                    >
                      <i className="fas fa-trash text-xs"></i>
                    </button>
                    <i className={`fas ${isEditing ? 'fa-chevron-up' : 'fa-chevron-down'} text-gray-400 text-xs`}></i>
                  </div>
                </div>

                {/* Expanded edit form */}
                {isEditing && editingTemplate && (
                  <div className="border-t border-purple-200 p-4 space-y-4">
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                      <NumberField label="Min CPU (cores)" value={editingTemplate.minCpuCores} min={1}
                        onChange={v => setEditingTemplate(p => p ? { ...p, minCpuCores: v } : p)} />
                      <NumberField label="Max CPU (cores)" value={editingTemplate.maxCpuCores} min={1}
                        onChange={v => setEditingTemplate(p => p ? { ...p, maxCpuCores: v } : p)} />
                      <NumberField label="Min RAM (MiB)" value={editingTemplate.minRamMib} min={512} unit="MiB"
                        onChange={v => setEditingTemplate(p => p ? { ...p, minRamMib: v } : p)} />
                      <NumberField label="Max RAM (MiB)" value={editingTemplate.maxRamMib} min={512} unit="MiB"
                        onChange={v => setEditingTemplate(p => p ? { ...p, maxRamMib: v } : p)} />
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                      <div>
                        <label className="block text-xs font-semibold text-gray-600 mb-1.5">Capacity Type</label>
                        <select
                          value={editingTemplate.capacityType}
                          onChange={e => setEditingTemplate(p => p ? { ...p, capacityType: e.target.value as any } : p)}
                          className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm font-semibold text-gray-800 focus:outline-none focus:ring-2 focus:ring-purple-400"
                        >
                          <option value="on-demand">On-Demand</option>
                          <option value="spot">Spot (via XamOps)</option>
                          <option value="mixed">Mixed</option>
                        </select>
                      </div>

                      <div className="flex flex-col gap-2 pt-4">
                        <Toggle
                          checked={editingTemplate.spotAutomationEnabled}
                          onChange={v => setEditingTemplate(p => p ? { ...p, spotAutomationEnabled: v } : p)}
                          label="XamOps Spot Automation"
                          description="Use XamOps spot pool for this template"
                        />
                      </div>

                      <div className="flex flex-col gap-2 pt-4">
                        <Toggle
                          checked={editingTemplate.useSpotFallback}
                          onChange={v => setEditingTemplate(p => p ? { ...p, useSpotFallback: v } : p)}
                          label="Spot Fallback to On-Demand"
                          description="Provision on-demand if spot is unavailable"
                        />
                      </div>
                    </div>

                    {editingTemplate.useSpotFallback && (
                      <SliderField
                        label="Fallback Restore Rate (time before retrying spot after fallback)"
                        value={editingTemplate.fallbackRestoreRateSeconds}
                        min={300} max={7200} step={300} unit="s"
                        onChange={v => setEditingTemplate(p => p ? { ...p, fallbackRestoreRateSeconds: v } : p)}
                      />
                    )}

                    <div className="flex justify-end gap-3 pt-2 border-t border-purple-200">
                      <button
                        onClick={() => { setEditingTemplateId(null); setEditingTemplate(null); }}
                        className="px-4 py-2 bg-gray-100 text-gray-600 text-xs font-bold rounded-lg hover:bg-gray-200 transition-all"
                      >
                        Cancel
                      </button>
                      <SaveButton onClick={() => saveNodeTemplate(editingTemplate)} saving={isSaving} label="Save Template" />
                    </div>
                  </div>
                )}
              </div>
            );
          })}

          {/* Add new template */}
          {showNewTemplateForm ? (
            <div className="border border-dashed border-purple-300 rounded-xl bg-purple-50 p-4 space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-semibold text-gray-600 mb-1.5">Template Name</label>
                  <input
                    type="text"
                    placeholder="e.g. Memory Optimized"
                    value={editingTemplate?.name || ''}
                    onChange={e => setEditingTemplate(p => p ? { ...p, name: e.target.value } : blankTemplate())}
                    className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-400"
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-600 mb-1.5">Capacity Type</label>
                  <select
                    value={editingTemplate?.capacityType || 'on-demand'}
                    onChange={e => setEditingTemplate(p => p ? { ...p, capacityType: e.target.value as any } : blankTemplate())}
                    className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm font-semibold text-gray-800 focus:outline-none focus:ring-2 focus:ring-purple-400"
                  >
                    <option value="on-demand">On-Demand</option>
                    <option value="spot">Spot (via XamOps)</option>
                    <option value="mixed">Mixed</option>
                  </select>
                </div>
              </div>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <NumberField label="Min CPU" value={editingTemplate?.minCpuCores || 2} min={1}
                  onChange={v => setEditingTemplate(p => p ? { ...p, minCpuCores: v } : blankTemplate())} />
                <NumberField label="Max CPU" value={editingTemplate?.maxCpuCores || 16} min={1}
                  onChange={v => setEditingTemplate(p => p ? { ...p, maxCpuCores: v } : blankTemplate())} />
                <NumberField label="Min RAM (MiB)" value={editingTemplate?.minRamMib || 4096} min={512} unit="MiB"
                  onChange={v => setEditingTemplate(p => p ? { ...p, minRamMib: v } : blankTemplate())} />
                <NumberField label="Max RAM (MiB)" value={editingTemplate?.maxRamMib || 32768} min={512} unit="MiB"
                  onChange={v => setEditingTemplate(p => p ? { ...p, maxRamMib: v } : blankTemplate())} />
              </div>
              <div className="flex justify-end gap-3 pt-2 border-t border-purple-200">
                <button
                  onClick={() => { setShowNewTemplateForm(false); setEditingTemplate(null); }}
                  className="px-4 py-2 bg-gray-100 text-gray-600 text-xs font-bold rounded-lg hover:bg-gray-200"
                >Cancel</button>
                <SaveButton
                  onClick={() => {
                    if (editingTemplate) {
                      saveNodeTemplate(editingTemplate);
                      setShowNewTemplateForm(false);
                    }
                  }}
                  saving={isSaving}
                  label="Add Template"
                />
              </div>
            </div>
          ) : (
            <button
              onClick={() => { setShowNewTemplateForm(true); setEditingTemplate(blankTemplate()); }}
              className="w-full py-3 border-2 border-dashed border-gray-200 rounded-xl text-sm font-semibold text-gray-400 hover:border-purple-300 hover:text-purple-600 hover:bg-purple-50 transition-all flex items-center justify-center gap-2"
            >
              <i className="fas fa-plus"></i> Add Node Template
            </button>
          )}
        </div>
      </Section>

      {/* ═══ 7. REBALANCER ══════════════════════════════════════════════════ */}
      <Section
        icon="fa-sync-alt"
        color="bg-cyan-500"
        title="Rebalancer"
        subtitle="Solves cluster fragmentation by re-bin-packing all workloads. Provisions optimal nodes first, then drains old ones."
        badge={
          <div className="flex items-center gap-3">
            <span className={`px-2.5 py-1 rounded-full text-xs font-bold ${rebalancer.status === 'idle'
              ? 'bg-gray-100 text-gray-600'
              : rebalancer.status === 'running'
                ? 'bg-blue-100 text-blue-700'
                : rebalancer.status === 'completed'
                  ? 'bg-green-100 text-green-700'
                  : 'bg-red-100 text-red-700'
              }`}>
              {rebalancer.status.toUpperCase()}
            </span>
          </div>
        }
      >
        <div className="space-y-6">

          {/* Last rebalance stats */}
          {rebalancer.lastRebalanceAt && (
            <div className="grid grid-cols-3 gap-4 p-4 bg-gray-50 border border-gray-200 rounded-xl">
              <div>
                <div className="text-xs text-gray-500 mb-1">Last Rebalance</div>
                <div className="text-sm font-bold text-gray-800">{timeSince(rebalancer.lastRebalanceAt)}</div>
              </div>
              <div>
                <div className="text-xs text-gray-500 mb-1">Savings Achieved</div>
                <div className="text-sm font-bold text-green-700">{rebalancer.lastRebalanceSavingsPercent}%</div>
              </div>
              <div>
                <div className="text-xs text-gray-500 mb-1">Duration</div>
                <div className="text-sm font-bold text-gray-800">{rebalancer.lastRebalanceDuration} min</div>
              </div>
            </div>
          )}

          {/* Config */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="space-y-4">
              <Toggle
                checked={rebalancer.enabled}
                onChange={v => setRebalancerDraft(p => ({ ...p!, enabled: v }))}
                label="Enable Rebalancer"
                description="Allow XamOps agent to run rebalancing operations"
              />
              <Toggle
                checked={rebalancer.gracefulDraining}
                onChange={v => setRebalancerDraft(p => ({ ...p!, gracefulDraining: v }))}
                label="Graceful Draining"
                description="Failed drains are annotated and left cordoned (not force-deleted)"
              />
              <Toggle
                checked={rebalancer.scheduleEnabled}
                onChange={v => setRebalancerDraft(p => ({ ...p!, scheduleEnabled: v }))}
                label="Scheduled Rebalancing"
                description="Auto-trigger rebalance on a cron schedule"
              />
            </div>
            <div className="space-y-4">
              <SliderField
                label="Per-Node Drain Timeout"
                value={rebalancer.drainTimeoutMinutes}
                min={5} max={60} unit=" min"
                onChange={v => setRebalancerDraft(p => ({ ...p!, drainTimeoutMinutes: v }))}
              />
              {rebalancer.scheduleEnabled && (
                <div>
                  <label className="block text-xs font-semibold text-gray-600 mb-1.5">Schedule (cron)</label>
                  <input
                    type="text"
                    value={rebalancer.scheduleCron}
                    onChange={e => setRebalancerDraft(p => ({ ...p!, scheduleCron: e.target.value }))}
                    placeholder="0 2 * * 0"
                    className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-purple-400"
                  />
                  <p className="text-xs text-gray-400 mt-1">Example: <code>0 2 * * 0</code> = every Sunday at 2am</p>
                </div>
              )}
            </div>
          </div>

          {/* Rebalancer info box */}
          <div className="bg-cyan-50 border border-cyan-100 rounded-lg p-4 text-xs text-cyan-800">
            <div className="flex items-start gap-2">
              <i className="fas fa-info-circle text-cyan-500 mt-0.5 shrink-0"></i>
              <div>
                <span className="font-bold">Execution order: </span>
                Provision new optimal nodes first → drain pods from old nodes gracefully → delete old nodes.
                Hard 80-min timeout for entire operation. Rebalancing aborts if minimum savings target cannot be met.
              </div>
            </div>
          </div>

          <div className="flex items-center justify-between pt-2 border-t border-gray-100">
            <button
              onClick={triggerRebalance}
              disabled={isRebalancing || rebalancer.status === 'running'}
              className="flex items-center gap-2 px-5 py-2.5 bg-cyan-600 text-white text-xs font-bold rounded-lg shadow hover:bg-cyan-700 transition-all disabled:opacity-50 active:scale-95"
            >
              <i className={`fas fa-sync-alt ${isRebalancing ? 'animate-spin' : ''}`}></i>
              {isRebalancing ? 'Rebalancing…' : 'Trigger Rebalance Now'}
            </button>
            <SaveButton onClick={() => saveRebalancerConfig(rebalancer)} saving={isSaving} />
          </div>
        </div>
      </Section>

      {/* ═══ 8. SAFETY GUARDS ═══════════════════════════════════════════════ */}
      <Section
        icon="fa-shield-alt"
        color="bg-green-600"
        title="Safety Guards"
        subtitle="Protections enforced before any scale-in or rebalance action. These prevent accidental workload disruption."
      >
        <div className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {[
              {
                key: 'respectPodDisruptionBudgets',
                label: 'Respect PodDisruptionBudgets',
                desc: 'Never evict pods that would violate their PDB. Required for HA workloads.',
                danger: false,
              },
              {
                key: 'protectSingleReplicas',
                label: 'Protect Single-Replica Workloads',
                desc: 'Skip eviction of deployments with only 1 replica (unless Aggressive Mode on Evictor).',
                danger: false,
              },
              {
                key: 'protectStatefulSets',
                label: 'Protect StatefulSets',
                desc: 'StatefulSet pods are never bin-packed unless Container Live Migration is available.',
                danger: false,
              },
              {
                key: 'protectCriticalNamespaces',
                label: 'Protect Critical Namespaces',
                desc: 'Nodes running pods from critical namespaces are excluded from scale-in.',
                danger: false,
              },
            ].map(guard => (
              <div key={guard.key} className="bg-gray-50 border border-gray-200 rounded-lg p-4">
                <Toggle
                  checked={guards[guard.key as keyof SafetyGuards] as boolean}
                  onChange={v => setGuards(p => ({ ...p!, [guard.key]: v }))}
                  label={guard.label}
                  description={guard.desc}
                />
              </div>
            ))}
          </div>

          {guards.protectCriticalNamespaces && (
            <div>
              <label className="block text-xs font-semibold text-gray-600 mb-1.5">
                Critical Namespaces (comma-separated)
              </label>
              <input
                type="text"
                value={guards.criticalNamespaces.join(', ')}
                onChange={e => setGuards(p => ({
                  ...p!,
                  criticalNamespaces: e.target.value.split(',').map(s => s.trim()).filter(Boolean)
                }))}
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-purple-400"
                placeholder="kube-system, monitoring, ingress-nginx"
              />
            </div>
          )}

          <div className="flex justify-end pt-2 border-t border-gray-100">
            <SaveButton onClick={() => saveSafetyGuards(guards)} saving={isSaving} />
          </div>
        </div>
      </Section>

      {/* ═══ 9. NODE OPTIMIZER ══════════════════════════════════════════════ */}
      <Section
        icon="fa-microchip"
        color="bg-slate-600"
        title="Node Optimizer"
        subtitle="Spot/on-demand ratio management, idle node detection, and spot interruption handling"
        badge={
          <Toggle
            checked={nodeOpt.enabled}
            onChange={v => setNodeOpt(p => ({ ...p!, enabled: v }))}
            label=""
          />
        }
      >
        <div className="space-y-6">

          {/* ── Fleet Overview ─────────────────────────────────────────────── */}
          {(() => {
            const fleet = nodeFleetReport ?? autoscalerData.nodeFleetReport;
            const total = fleet?.totalNodes ?? 0;
            const spot = fleet?.spotNodes ?? 0;
            const onDemand = fleet?.onDemandNodes ?? 0;
            const idle = fleet?.idleNodes?.length ?? 0;
            const currentSpotPct = total > 0 ? Math.round((spot / total) * 100) : 0;
            return (
              <div>
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-3">Current Node Fleet</h4>
                <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                  {[
                    { label: 'Total Nodes', value: total, icon: 'fa-server', color: 'text-slate-700' },
                    { label: 'On-Demand', value: onDemand, icon: 'fa-cloud', color: 'text-blue-700' },
                    { label: 'Spot', value: spot, icon: 'fa-bolt', color: 'text-amber-600' },
                    { label: 'Idle / Flagged', value: idle, icon: 'fa-bed', color: idle > 0 ? 'text-red-500' : 'text-gray-500' },
                  ].map(stat => (
                    <div key={stat.label} className="bg-gray-50 border border-gray-200 rounded-xl p-3">
                      <div className="flex items-center gap-2 mb-1">
                        <i className={`fas ${stat.icon} ${stat.color} text-sm`}></i>
                        <span className="text-[10px] text-gray-500 font-semibold uppercase tracking-wide">{stat.label}</span>
                      </div>
                      <div className={`text-xl font-bold ${stat.color}`}>{stat.value}</div>
                    </div>
                  ))}
                </div>
                {total > 0 && (
                  <div className="mt-3">
                    <div className="flex justify-between text-xs text-gray-500 mb-1">
                      <span>Current spot ratio</span>
                      <span className="font-bold text-amber-600">{currentSpotPct}%</span>
                    </div>
                    <div className="h-2.5 bg-gray-200 rounded-full overflow-hidden">
                      <div
                        className="h-full bg-amber-400 rounded-full transition-all"
                        style={{ width: `${currentSpotPct}%` }}
                      />
                    </div>
                  </div>
                )}
              </div>
            );
          })()}

          {/* ── Spot Ratio Target ──────────────────────────────────────────── */}
          <div>
            <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-3">
              Spot Ratio Target
            </h4>
            <SliderField
              label={`Target Spot Ratio${nodeOpt.spotRatioTargetPct === 0 ? ' (disabled)' : ` — maintain ${nodeOpt.spotRatioTargetPct}% spot nodes`}`}
              value={nodeOpt.spotRatioTargetPct}
              min={0} max={100} step={10} unit="%"
              onChange={v => setNodeOpt(p => ({ ...p!, spotRatioTargetPct: v }))}
            />
            {nodeOpt.spotRatioTargetPct > 0 && (() => {
              const fleet = nodeFleetReport ?? autoscalerData.nodeFleetReport;
              const total = fleet?.totalNodes ?? 0;
              const currentSpot = fleet?.spotNodes ?? 0;
              const targetSpot = total > 0 ? Math.round(total * nodeOpt.spotRatioTargetPct / 100) : 0;
              const delta = targetSpot - currentSpot;
              return (
                <div className="mt-2 p-3 bg-amber-50 border border-amber-200 rounded-lg text-xs text-amber-800">
                  <i className="fas fa-info-circle mr-1.5 text-amber-500"></i>
                  {total === 0
                    ? 'Fleet data not available yet — agent will calculate when first report arrives.'
                    : delta > 0
                    ? `Agent will drain ${delta} on-demand node(s) (least-loaded first) to reach ${nodeOpt.spotRatioTargetPct}% spot. Up to ${nodeOpt.maxParallelDrains} node(s) drained per cycle. Replacement spot nodes provisioned by Karpenter/Cluster Autoscaler.`
                    : delta < 0
                    ? `Currently above target (${currentSpot}/${total} spot). No action needed — natural termination will reduce spot count.`
                    : `Fleet is already at target (${currentSpot}/${total} spot nodes).`
                  }
                </div>
              );
            })()}
            {nodeOpt.spotRatioTargetPct === 0 && (
              <p className="text-xs text-gray-400 mt-1">Set to &gt; 0% to enable automatic spot ratio management.</p>
            )}
          </div>

          {/* ── Settings row ─────────────────────────────────────────────── */}
          <div>
            <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-3">Settings</h4>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
                <Toggle
                  checked={nodeOpt.karpenterEnabled}
                  onChange={v => setNodeOpt(p => ({ ...p!, karpenterEnabled: v }))}
                  label="Karpenter Installed"
                  description="Enables NodePool CRD patching for spot ratio intent annotation"
                />
              </div>
              <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
                <Toggle
                  checked={nodeOpt.handleSpotInterruptions}
                  onChange={v => setNodeOpt(p => ({ ...p!, handleSpotInterruptions: v }))}
                  label="Handle Spot Interruptions"
                  description="Cordon + drain node within AWS 2-minute termination window"
                />
              </div>
              <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
                <Toggle
                  checked={nodeOpt.autoCordonIdleNodes}
                  onChange={v => setNodeOpt(p => ({ ...p!, autoCordonIdleNodes: v }))}
                  label="Auto-Cordon Idle Nodes"
                  description="Cordon nodes below CPU/memory thresholds (prevents new scheduling)"
                />
              </div>
              <div className="bg-amber-50 border border-amber-200 rounded-lg p-4">
                <Toggle
                  checked={nodeOpt.autoDrainIdleNodes}
                  onChange={v => setNodeOpt(p => ({ ...p!, autoDrainIdleNodes: v }))}
                  label="Auto-Drain Idle Nodes"
                  description="PDB-aware safe drain of idle nodes — evict pods with replacement waiting"
                />
              </div>
            </div>
          </div>

          {/* ── Thresholds + parallel drains ───────────────────────────────── */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <SliderField
              label="CPU Idle Threshold"
              value={nodeOpt.idleCpuThresholdPct}
              min={1} max={30} unit="%"
              onChange={v => setNodeOpt(p => ({ ...p!, idleCpuThresholdPct: v }))}
            />
            <SliderField
              label="Memory Idle Threshold"
              value={nodeOpt.idleMemThresholdPct}
              min={1} max={40} unit="%"
              onChange={v => setNodeOpt(p => ({ ...p!, idleMemThresholdPct: v }))}
            />
            <NumberField
              label="Max Parallel Drains"
              value={nodeOpt.maxParallelDrains}
              min={1} max={5}
              onChange={v => setNodeOpt(p => ({ ...p!, maxParallelDrains: v }))}
            />
          </div>

          {/* ── Spot Candidates Table ─────────────────────────────────────── */}
          {(() => {
            const fleet = nodeFleetReport ?? autoscalerData.nodeFleetReport;
            if (!fleet || fleet.spotCandidates.length === 0) return null;
            return (
              <div>
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-3 flex items-center gap-2">
                  <i className="fas fa-bolt text-amber-500"></i>
                  Spot Conversion Candidates ({fleet.spotCandidates.length} on-demand nodes)
                  <span className="text-[10px] font-normal text-gray-400 normal-case">
                    — avg {fleet.estimatedSavingsPct.toFixed(0)}% savings potential
                  </span>
                </h4>
                <div className="overflow-x-auto rounded-xl border border-gray-200">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="bg-gray-50 border-b border-gray-200">
                        <th className="text-left px-3 py-2.5 font-semibold text-gray-600">Node</th>
                        <th className="text-left px-3 py-2.5 font-semibold text-gray-600">Instance Type</th>
                        <th className="text-right px-3 py-2.5 font-semibold text-gray-600">CPU Util</th>
                        <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Mem Util</th>
                        <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Est. Savings</th>
                      </tr>
                    </thead>
                    <tbody>
                      {fleet.spotCandidates.map((c, i) => (
                        <tr key={i} className="border-b border-gray-100 hover:bg-amber-50/30 transition-colors">
                          <td className="px-3 py-2.5 font-mono text-[11px] text-gray-800 font-semibold">{c.nodeName}</td>
                          <td className="px-3 py-2.5 text-gray-600">{c.instanceType || '—'}</td>
                          <td className="px-3 py-2.5 text-right">
                            <div className="flex items-center justify-end gap-2">
                              <div className="w-12 h-1.5 bg-gray-200 rounded-full overflow-hidden">
                                <div className="h-full bg-blue-400 rounded-full" style={{ width: `${c.cpuUsagePct}%` }} />
                              </div>
                              <span className="font-bold text-gray-700">{c.cpuUsagePct.toFixed(0)}%</span>
                            </div>
                          </td>
                          <td className="px-3 py-2.5 text-right">
                            <div className="flex items-center justify-end gap-2">
                              <div className="w-12 h-1.5 bg-gray-200 rounded-full overflow-hidden">
                                <div className="h-full bg-purple-400 rounded-full" style={{ width: `${c.memUsagePct}%` }} />
                              </div>
                              <span className="font-bold text-gray-700">{c.memUsagePct.toFixed(0)}%</span>
                            </div>
                          </td>
                          <td className="px-3 py-2.5 text-right">
                            <span className="font-bold text-green-600">~{c.savingsPct.toFixed(0)}%</span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            );
          })()}

          {/* ── Idle Nodes Table ───────────────────────────────────────────── */}
          {(() => {
            const fleet = nodeFleetReport ?? autoscalerData.nodeFleetReport;
            if (!fleet || fleet.idleNodes.length === 0) return null;
            return (
              <div>
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-3 flex items-center gap-2">
                  <i className="fas fa-bed text-red-400"></i>
                  Idle Nodes ({fleet.idleNodes.length} detected)
                </h4>
                <div className="overflow-x-auto rounded-xl border border-gray-200">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="bg-gray-50 border-b border-gray-200">
                        <th className="text-left px-3 py-2.5 font-semibold text-gray-600">Node</th>
                        <th className="text-left px-3 py-2.5 font-semibold text-gray-600">Type</th>
                        <th className="text-right px-3 py-2.5 font-semibold text-gray-600">CPU%</th>
                        <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Mem%</th>
                        <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Pods</th>
                        <th className="text-center px-3 py-2.5 font-semibold text-gray-600">Drain?</th>
                      </tr>
                    </thead>
                    <tbody>
                      {fleet.idleNodes.map((node, i) => (
                        <tr key={i} className={`border-b border-gray-100 hover:bg-gray-50 transition-colors ${node.recommendDrain ? 'bg-red-50/20' : ''}`}>
                          <td className="px-3 py-2.5 font-mono text-[11px] text-gray-800 font-semibold">{node.nodeName}</td>
                          <td className="px-3 py-2.5">
                            <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${node.capacityType === 'spot' ? 'bg-amber-100 text-amber-700' : 'bg-blue-100 text-blue-700'}`}>
                              {node.capacityType}
                            </span>
                          </td>
                          <td className="px-3 py-2.5 text-right font-bold text-red-500">{node.cpuUsagePct.toFixed(1)}%</td>
                          <td className="px-3 py-2.5 text-right font-bold text-red-500">{node.memUsagePct.toFixed(1)}%</td>
                          <td className="px-3 py-2.5 text-right text-gray-700 font-bold">{node.podCount}</td>
                          <td className="px-3 py-2.5 text-center">
                            {node.recommendDrain
                              ? <span className="px-2 py-0.5 bg-red-100 text-red-700 rounded-full text-[10px] font-bold"><i className="fas fa-exclamation-triangle mr-1"></i>Yes</span>
                              : <span className="px-2 py-0.5 bg-gray-100 text-gray-500 rounded-full text-[10px]">No</span>
                            }
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            );
          })()}

          <div className="flex justify-end pt-2 border-t border-gray-100">
            <SaveButton onClick={() => saveNodeOptimizerPolicy(nodeOpt)} saving={isSaving} />
          </div>
        </div>
      </Section>

      {/* ═══ 10. SPOT AUTOMATION BRIDGE ══════════════════════════════════════ */}
      <div className="bg-[#fff8c5] border border-[#d4a72c] rounded-lg p-5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-md bg-[#9a6700] flex items-center justify-center">
              <i className="fas fa-bolt text-white text-sm"></i>
            </div>
            <div>
              <h3 className="text-[13px] font-semibold text-[#9a6700]">XamOps Spot Automation — Integrated</h3>
              <p className="text-[11px] text-[#9a6700]/80 mt-0.5">
                Spot instance lifecycle (interruption, diversity, fallback) is handled by the XamOps Spot
                Automation engine. Node templates marked with "XamOps Spot" above are managed there.
              </p>
            </div>
          </div>
          <a
            href="/spot-automation"
            className="flex items-center gap-2 px-3 py-1.5 bg-[#9a6700] text-white text-[12px] font-medium rounded-md hover:bg-[#7a5200] transition-all shrink-0"
          >
            <i className="fas fa-external-link-alt"></i>
            Open Spot Automation
          </a>
        </div>
        <div className="mt-4 grid grid-cols-2 md:grid-cols-4 gap-3 text-xs text-amber-800">
          {[
            { icon: 'fa-random', text: 'Spot Diversity across instance families' },
            { icon: 'fa-shield-alt', text: 'Interruption prediction & proactive drain' },
            { icon: 'fa-parachute-box', text: 'On-demand fallback with auto spot-restore' },
            { icon: 'fa-ban', text: 'Instance greylisting after interruption signals' },
          ].map((item, i) => (
            <div key={i} className="flex items-start gap-2 bg-white/60 rounded-lg p-2 border border-amber-100">
              <i className={`fas ${item.icon} text-amber-500 mt-0.5 shrink-0`}></i>
              <span>{item.text}</span>
            </div>
          ))}
        </div>
      </div>

      {/* ═══ 10. WORKLOAD RIGHTSIZING RECOMMENDATIONS ═══════════════════════ */}
      <Section
        icon="fa-weight"
        color="bg-indigo-600"
        title="Workload Rightsizing Recommendations"
        subtitle="Live analysis from xamops-automation — Prometheus p80 CPU / p100 memory over the lookback window"
        badge={
          rightsizingData && rightsizingData.workloadsWithRecommendations > 0 ? (
            <span className="px-2.5 py-1 rounded-full text-xs font-bold bg-indigo-100 text-indigo-700">
              {rightsizingData.workloadsWithRecommendations} workload{rightsizingData.workloadsWithRecommendations !== 1 ? 's' : ''} to resize
            </span>
          ) : undefined
        }
      >
        {!rightsizingData ? (
          <div className="text-center py-8 text-gray-400">
            <i className="fas fa-satellite-dish text-3xl mb-2 block"></i>
            <p className="text-sm">Waiting for xamops-automation agent data…</p>
            <p className="text-xs mt-1 text-gray-300">Agent must be deployed with <code className="bg-gray-100 px-1 rounded">ENABLE_RIGHTSIZER=true</code></p>
          </div>
        ) : rightsizingData.recommendations.length === 0 ? (
          <div className="text-center py-8 text-gray-400">
            <i className="fas fa-check-circle text-3xl mb-2 block text-green-400"></i>
            <p className="text-sm font-semibold text-green-700">All workloads are right-sized</p>
            <p className="text-xs mt-1">Last analyzed: {rightsizingData.lastAnalyzedAt ? timeSince(rightsizingData.lastAnalyzedAt) : 'N/A'}</p>
          </div>
        ) : (
          <div className="space-y-4">
            {/* Summary stats */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              {[
                { label: 'Total Workloads', value: rightsizingData.totalWorkloads, icon: 'fa-cubes', color: 'text-gray-700' },
                { label: 'With Recommendations', value: rightsizingData.workloadsWithRecommendations, icon: 'fa-exclamation-circle', color: 'text-indigo-600' },
                { label: 'Est. Savings', value: `${(rightsizingData.totalEstimatedSavingsPercent ?? 0).toFixed(1)}%`, icon: 'fa-piggy-bank', color: 'text-green-600' },
                { label: 'Last Analyzed', value: rightsizingData.lastAnalyzedAt ? timeSince(rightsizingData.lastAnalyzedAt) : 'N/A', icon: 'fa-clock', color: 'text-gray-500' },
              ].map((stat, i) => (
                <div key={i} className="bg-gray-50 border border-gray-200 rounded-xl p-3 flex items-center gap-3">
                  <i className={`fas ${stat.icon} ${stat.color} text-lg`}></i>
                  <div>
                    <div className="text-[10px] text-gray-500 uppercase tracking-wide">{stat.label}</div>
                    <div className={`text-sm font-bold ${stat.color}`}>{stat.value}</div>
                  </div>
                </div>
              ))}
            </div>

            {/* Recommendations table */}
            <div className="overflow-x-auto rounded-xl border border-gray-200">
              <table className="w-full text-xs">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200">
                    <th className="text-left px-3 py-2.5 font-semibold text-gray-600">Workload</th>
                    <th className="text-left px-3 py-2.5 font-semibold text-gray-600">Container</th>
                    <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Current CPU</th>
                    <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Rec. CPU</th>
                    <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Current Mem</th>
                    <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Rec. Mem</th>
                    <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Savings</th>
                    <th className="text-center px-3 py-2.5 font-semibold text-gray-600">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {rightsizingData.recommendations.map((rec: WorkloadRecommendation, i: number) => (
                    <tr key={i} className="border-b border-gray-100 hover:bg-gray-50 transition-colors">
                      <td className="px-3 py-2.5">
                        <div className="font-semibold text-gray-800">{rec.workloadName}</div>
                        <div className="text-[10px] text-gray-400">{rec.namespace} · {rec.workloadKind}</div>
                      </td>
                      <td className="px-3 py-2.5 text-gray-600 font-mono">{rec.containerName}</td>
                      <td className="px-3 py-2.5 text-right font-mono text-gray-600">{rec.currentCpuRequest}</td>
                      <td className="px-3 py-2.5 text-right font-mono font-semibold text-indigo-700">{rec.recommendedCpu}</td>
                      <td className="px-3 py-2.5 text-right font-mono text-gray-600">{rec.currentMemRequest}</td>
                      <td className="px-3 py-2.5 text-right font-mono font-semibold text-indigo-700">{rec.recommendedMem}</td>
                      <td className="px-3 py-2.5 text-right">
                        <span className={`font-bold ${rec.estimatedSavingsPercent > 0 ? 'text-green-600' : 'text-amber-600'}`}>
                          {(rec.estimatedSavingsPercent ?? 0) > 0 ? '-' : '+'}{Math.abs(rec.estimatedSavingsPercent ?? 0).toFixed(1)}%
                        </span>
                      </td>
                      <td className="px-3 py-2.5 text-center">
                        {rec.applied ? (
                          <span className="px-2 py-0.5 bg-green-100 text-green-700 rounded-full text-[10px] font-bold">Applied</span>
                        ) : (
                          <span className="px-2 py-0.5 bg-amber-100 text-amber-700 rounded-full text-[10px] font-bold">Pending</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </Section>

      {/* ═══ 11. EVICTOR CANDIDATES ══════════════════════════════════════════ */}
      <Section
        icon="fa-compress-arrows-alt"
        color="bg-red-600"
        title="Evictor — Node Candidates"
        subtitle="Nodes evaluated by bin-packing simulation. Nodes marked CanEvict will be drained next cycle (if not dry-run)."
        badge={
          evictorCandidates.filter((n: EvictorCandidate) => n.canEvict).length > 0 ? (
            <span className="px-2.5 py-1 rounded-full text-xs font-bold bg-red-100 text-red-700">
              {evictorCandidates.filter((n: EvictorCandidate) => n.canEvict).length} evictable
            </span>
          ) : undefined
        }
      >
        {evictorCandidates.length === 0 ? (
          <div className="text-center py-8 text-gray-400">
            <i className="fas fa-satellite-dish text-3xl mb-2 block"></i>
            <p className="text-sm">No evictor data yet.</p>
            <p className="text-xs mt-1 text-gray-300">Agent must be deployed with <code className="bg-gray-100 px-1 rounded">ENABLE_EVICTOR=true</code></p>
          </div>
        ) : (
          <div className="overflow-x-auto rounded-xl border border-gray-200">
            <table className="w-full text-xs">
              <thead>
                <tr className="bg-gray-50 border-b border-gray-200">
                  <th className="text-left px-3 py-2.5 font-semibold text-gray-600">Node</th>
                  <th className="text-right px-3 py-2.5 font-semibold text-gray-600">CPU Util</th>
                  <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Mem Util</th>
                  <th className="text-right px-3 py-2.5 font-semibold text-gray-600">Pods</th>
                  <th className="text-center px-3 py-2.5 font-semibold text-gray-600">Can Evict</th>
                  <th className="text-left px-3 py-2.5 font-semibold text-gray-600">Skip Reason</th>
                </tr>
              </thead>
              <tbody>
                {evictorCandidates.map((node: EvictorCandidate, i: number) => (
                  <tr key={i} className={`border-b border-gray-100 hover:bg-gray-50 transition-colors ${node.canEvict ? 'bg-red-50/30' : ''}`}>
                    <td className="px-3 py-2.5 font-mono font-semibold text-gray-800 text-[11px]">{node.nodeName}</td>
                    <td className="px-3 py-2.5 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <div className="w-16 h-1.5 bg-gray-200 rounded-full overflow-hidden">
                          <div
                            className={`h-full rounded-full ${node.utilizationCpu > 70 ? 'bg-green-500' : node.utilizationCpu > 40 ? 'bg-amber-400' : 'bg-red-400'}`}
                            style={{ width: `${node.utilizationCpu}%` }}
                          />
                        </div>
                        <span className="font-bold text-gray-700">{(node.utilizationCpu ?? 0).toFixed(0)}%</span>
                      </div>
                    </td>
                    <td className="px-3 py-2.5 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <div className="w-16 h-1.5 bg-gray-200 rounded-full overflow-hidden">
                          <div
                            className={`h-full rounded-full ${node.utilizationMem > 70 ? 'bg-green-500' : node.utilizationMem > 40 ? 'bg-amber-400' : 'bg-red-400'}`}
                            style={{ width: `${node.utilizationMem}%` }}
                          />
                        </div>
                        <span className="font-bold text-gray-700">{(node.utilizationMem ?? 0).toFixed(0)}%</span>
                      </div>
                    </td>
                    <td className="px-3 py-2.5 text-right font-bold text-gray-700">{node.podCount}</td>
                    <td className="px-3 py-2.5 text-center">
                      {node.canEvict ? (
                        <span className="px-2 py-0.5 bg-red-100 text-red-700 rounded-full text-[10px] font-bold">
                          <i className="fas fa-exclamation-triangle mr-1"></i>YES
                        </span>
                      ) : (
                        <span className="px-2 py-0.5 bg-green-100 text-green-700 rounded-full text-[10px] font-bold">
                          <i className="fas fa-shield-alt mr-1"></i>Protected
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2.5 text-gray-400 italic text-[10px]">
                      {node.skipReason || '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      {/* ═══ 12. LIVE REBALANCE STATUS ═══════════════════════════════════════ */}
      {liveRebalanceStatus && (
        <Section
          icon="fa-sync-alt"
          color="bg-cyan-600"
          title="Live Rebalance Status"
          subtitle="Real-time rebalance progress from the xamops-automation agent"
          badge={
            <span className={`px-2.5 py-1 rounded-full text-xs font-bold ${
              liveRebalanceStatus.status === 'running'
                ? 'bg-blue-100 text-blue-700 animate-pulse'
                : liveRebalanceStatus.status === 'completed'
                ? 'bg-green-100 text-green-700'
                : liveRebalanceStatus.status === 'failed'
                ? 'bg-red-100 text-red-700'
                : 'bg-gray-100 text-gray-600'
            }`}>
              {liveRebalanceStatus.status.toUpperCase()}
            </span>
          }
        >
          <div className="space-y-4">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              {[
                {
                  label: 'Status',
                  value: liveRebalanceStatus.status,
                  icon: 'fa-circle',
                  color: liveRebalanceStatus.status === 'running' ? 'text-blue-600' : liveRebalanceStatus.status === 'completed' ? 'text-green-600' : 'text-gray-500',
                },
                {
                  label: 'Nodes Drained',
                  value: liveRebalanceStatus.nodesDrained?.length ?? 0,
                  icon: 'fa-server',
                  color: 'text-gray-700',
                },
                {
                  label: 'Duration',
                  value: liveRebalanceStatus.durationSeconds > 0 ? `${Math.round(liveRebalanceStatus.durationSeconds / 60)} min` : '—',
                  icon: 'fa-hourglass-half',
                  color: 'text-gray-700',
                },
                {
                  label: 'Started',
                  value: liveRebalanceStatus.startedAt ? timeSince(liveRebalanceStatus.startedAt) : '—',
                  icon: 'fa-clock',
                  color: 'text-gray-500',
                },
              ].map((stat, i) => (
                <div key={i} className="bg-gray-50 border border-gray-200 rounded-xl p-3 flex items-center gap-3">
                  <i className={`fas ${stat.icon} ${stat.color} text-lg`}></i>
                  <div>
                    <div className="text-[10px] text-gray-500 uppercase tracking-wide">{stat.label}</div>
                    <div className={`text-sm font-bold ${stat.color} capitalize`}>{String(stat.value)}</div>
                  </div>
                </div>
              ))}
            </div>

            {liveRebalanceStatus.nodesDrained && liveRebalanceStatus.nodesDrained.length > 0 && (
              <div>
                <div className="text-xs font-semibold text-gray-600 mb-2">Drained Nodes</div>
                <div className="flex flex-wrap gap-2">
                  {liveRebalanceStatus.nodesDrained.map((n: string, i: number) => (
                    <span key={i} className="px-2.5 py-1 bg-cyan-50 border border-cyan-200 text-cyan-800 rounded-lg font-mono text-[11px]">
                      <i className="fas fa-server mr-1.5 text-cyan-400"></i>{n}
                    </span>
                  ))}
                </div>
              </div>
            )}

            {liveRebalanceStatus.errorMessage && (
              <div className="p-3 bg-red-50 border border-red-200 rounded-xl text-xs text-red-700 flex items-start gap-2">
                <i className="fas fa-exclamation-circle mt-0.5 shrink-0"></i>
                <span>{liveRebalanceStatus.errorMessage}</span>
              </div>
            )}
          </div>
        </Section>
      )}

      {/* ═══ 13. ACTIVITY FEED ══════════════════════════════════════════════ */}
      <Section
        icon="fa-stream"
        color="bg-gray-700"
        title="Autoscaler Activity Feed"
        subtitle="Real-time log of all scale-out, scale-in, rebalance, and workload resize events"
      >
        <div className="space-y-3">
          {recentEvents.length === 0 ? (
            <div className="text-center py-8 text-gray-400">
              <i className="fas fa-inbox text-3xl mb-2"></i>
              <p className="text-sm">No events yet. The agent will log activity here.</p>
            </div>
          ) : (
            recentEvents.map(evt => (
              <div
                key={evt.id}
                className={`flex items-start gap-4 p-4 rounded-xl border ${eventBg(evt.severity)}`}
              >
                <div className={`w-8 h-8 rounded-lg flex items-center justify-center shrink-0 ${evt.severity === 'success' ? 'bg-green-100' : evt.severity === 'warning' ? 'bg-amber-100' : 'bg-blue-100'}`}>
                  <i className={`fas ${eventIcon(evt.type)} text-sm ${eventColor(evt.severity)}`}></i>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-semibold text-gray-800">{evt.message}</span>
                    <span className="text-xs text-gray-400 shrink-0">{timeSince(evt.timestamp)}</span>
                  </div>
                  {evt.details && (
                    <p className="text-xs text-gray-500 mt-1">{evt.details}</p>
                  )}
                  <div className="flex items-center gap-3 mt-1.5">
                    <span className={`text-[10px] font-bold uppercase px-2 py-0.5 rounded-full ${evt.severity === 'success' ? 'bg-green-100 text-green-700' : evt.severity === 'warning' ? 'bg-amber-100 text-amber-700' : 'bg-blue-100 text-blue-700'}`}>
                      {evt.type.replace('-', ' ')}
                    </span>
                    {evt.nodeCount !== undefined && (
                      <span className="text-[10px] text-gray-500">
                        {evt.nodeCount > 0 ? `+${evt.nodeCount}` : evt.nodeCount} node{Math.abs(evt.nodeCount) !== 1 ? 's' : ''}
                      </span>
                    )}
                    {evt.savingsDelta !== undefined && (
                      <span className="text-[10px] text-green-600 font-semibold">
                        ${(evt.savingsDelta ?? 0).toFixed(2)} saved
                      </span>
                    )}
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      </Section>

    </div>
  );
};

export default EksAutoscalingTab;
