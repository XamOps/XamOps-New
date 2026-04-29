import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpSpotAutomationData, GcpMIG } from '../hooks/useGcpSpotAutomationData';
import { DashboardSkeleton } from '../components/Shared/Skeleton';
import SpotAutomationKPIs from '../components/SpotAutomation/SpotAutomationKPIs';
import SpotAutomationChart from '../components/SpotAutomation/SpotAutomationChart';
import ActionsHistory from '../components/ActionsHistory/ActionsHistory';
import apiClient from '../utils/apiClient';
import { useTabState } from '../hooks/useTabState';

// ─── Types ────────────────────────────────────────────────────────────────────

interface MigConfig {
    migName: string;
    region: string;
    isSpotEnabled: boolean;
    targetSize: number;
    maxSurge: number;
    maxUnavailable: number;
    minReadySeconds: number;
    autoscalerEnabled: boolean;
    autoscalerMinReplicas?: number;
    autoscalerMaxReplicas?: number;
    autoscalerCoolDownPeriodSec?: number;
    machineType?: string;
    updatePolicySummary?: string;
}

interface LiveChangeEntry {
    migName: string;
    region: string;
    oldState: boolean;
    newState: boolean;
    detectedAt: string;
}

// ─── Config Modal ─────────────────────────────────────────────────────────────

interface ConfigModalProps {
    mig: GcpMIG;
    accountId: string;
    onClose: () => void;
    onSaved: () => void;
}

// GCP machine type families for Spot diversification (equivalent to AWS instance types)
const GCP_MACHINE_FAMILIES: Record<string, string[]> = {
    'E2 (Cost-Optimized)':   ['e2-micro', 'e2-small', 'e2-medium', 'e2-standard-2', 'e2-standard-4', 'e2-standard-8', 'e2-highcpu-4', 'e2-highcpu-8'],
    'N2 (General Purpose)':  ['n2-standard-2', 'n2-standard-4', 'n2-standard-8', 'n2-standard-16', 'n2-highcpu-4', 'n2-highcpu-8', 'n2-highmem-4', 'n2-highmem-8'],
    'N2D (AMD General)':     ['n2d-standard-2', 'n2d-standard-4', 'n2d-standard-8', 'n2d-highcpu-4', 'n2d-highcpu-8'],
    'N1 (First Gen)':        ['n1-standard-1', 'n1-standard-2', 'n1-standard-4', 'n1-standard-8', 'n1-highcpu-4', 'n1-highcpu-8', 'n1-highmem-4', 'n1-highmem-8'],
    'C2 (Compute Optimized)':['c2-standard-4', 'c2-standard-8', 'c2-standard-16', 'c2-standard-30', 'c2-standard-60'],
    'T2D (Scale-Out)':       ['t2d-standard-1', 't2d-standard-2', 't2d-standard-4', 't2d-standard-8'],
};

const ConfigModal: React.FC<ConfigModalProps> = ({ mig, accountId, onClose, onSaved }) => {
    const [config, setConfig] = useState<MigConfig | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [saveSuccess, setSaveSuccess] = useState(false);
    const [activeSection, setActiveSection] = useState<'instances' | 'rolling' | 'types'>('instances');

    useEffect(() => {
        const load = async () => {
            try {
                setLoading(true);
                const data = await apiClient.get(
                    `/api/gcp/autospotting/migs/${accountId}/config?region=${encodeURIComponent(mig.region)}&migName=${encodeURIComponent(mig.name)}`
                );
                setConfig(data);
            } catch (e: any) {
                setError(e?.message || 'Failed to load config');
            } finally {
                setLoading(false);
            }
        };
        load();
    }, [accountId, mig.name, mig.region]);

    const handleSave = async () => {
        if (!config) return;
        setSaving(true);
        setError(null);
        try {
            await apiClient.put(
                `/api/gcp/autospotting/migs/${accountId}/config?region=${encodeURIComponent(mig.region)}&migName=${encodeURIComponent(mig.name)}`,
                config
            );
            setSaveSuccess(true);
            setTimeout(() => {
                onSaved();
                onClose();
            }, 1200);
        } catch (e: any) {
            setError(e?.message || 'Failed to save config');
        } finally {
            setSaving(false);
        }
    };

    const tabs = [
        { id: 'instances' as const, label: 'Instances', icon: 'fa-server' },
        { id: 'rolling'   as const, label: 'Rolling Update', icon: 'fa-sync-alt' },
        { id: 'types'     as const, label: 'Machine Types', icon: 'fa-microchip' },
    ];

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
            <div className="bg-white rounded-2xl shadow-2xl w-full max-w-xl mx-4 overflow-hidden flex flex-col max-h-[90vh]">

                {/* Header */}
                <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between bg-gradient-to-r from-indigo-50 to-white shrink-0">
                    <div>
                        <h2 className="text-lg font-bold text-slate-900">MIG Configuration</h2>
                        <p className="text-xs text-slate-500 mt-0.5 font-mono truncate max-w-sm">{mig.name}</p>
                    </div>
                    <button onClick={onClose} className="p-2 hover:bg-slate-100 rounded-full transition-colors">
                        <i className="fas fa-times text-slate-400"></i>
                    </button>
                </div>

                {loading ? (
                    <div className="flex items-center justify-center py-16">
                        <i className="fas fa-circle-notch fa-spin text-indigo-500 text-2xl"></i>
                    </div>
                ) : config ? (
                    <>
                        {/* Section Tabs */}
                        <div className="flex border-b border-slate-100 shrink-0 px-6">
                            {tabs.map(t => (
                                <button key={t.id} onClick={() => setActiveSection(t.id)}
                                    className={`py-3 px-4 text-xs font-bold border-b-2 transition-all flex items-center gap-1.5 ${
                                        activeSection === t.id
                                            ? 'border-indigo-600 text-indigo-600'
                                            : 'border-transparent text-slate-500 hover:text-slate-700'}`}>
                                    <i className={`fas ${t.icon}`}></i>{t.label}
                                </button>
                            ))}
                        </div>

                        {/* Body */}
                        <div className="px-6 py-5 overflow-y-auto flex-1">

                            {/* ── Instances Tab ─────────────────────────────── */}
                            {activeSection === 'instances' && (
                                <div className="space-y-4">
                                    {/* Status row */}
                                    <div className="flex items-center justify-between p-3 bg-slate-50 rounded-xl border border-slate-100">
                                        <div>
                                            <p className="text-xs font-bold text-slate-500 uppercase tracking-wider">Current Mode</p>
                                            <p className="text-sm font-semibold text-slate-700 mt-0.5 font-mono">
                                                {config.machineType || 'unknown'}
                                            </p>
                                        </div>
                                        <span className={`px-3 py-1 rounded-full text-xs font-bold border ${config.isSpotEnabled
                                            ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                                            : 'bg-slate-100 text-slate-500 border-slate-200'}`}>
                                            {config.isSpotEnabled ? '⚡ SPOT' : 'ON-DEMAND'}
                                        </span>
                                    </div>

                                    {/* GCP architecture note */}
                                    <div className="p-3 bg-blue-50 border border-blue-200 rounded-xl text-xs text-blue-800">
                                        <p className="font-bold mb-1"><i className="fas fa-info-circle mr-1.5"></i>How GCP Spot works (vs AWS)</p>
                                        <p className="text-blue-700">In GCP a MIG uses a <strong>single instance template</strong> — the entire group is either 100% Spot or 100% On-Demand. To run a mixed ratio (e.g. 2 OD + rest Spot) you'd use <strong>two separate MIGs</strong> behind a load balancer. Toggle enables/disables Spot for the whole MIG.</p>
                                    </div>

                                    {/* Target size */}
                                    <div>
                                        <p className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">
                                            Instance Count (Target Size)
                                        </p>
                                        {config.autoscalerEnabled ? (
                                            <div className="p-3 bg-amber-50 rounded-xl border border-amber-100">
                                                <p className="text-xs font-bold text-amber-700 mb-1">
                                                    <i className="fas fa-robot mr-1.5"></i>Autoscaler Controls Size
                                                </p>
                                                <p className="text-xs text-amber-600">
                                                    Min <strong>{config.autoscalerMinReplicas ?? '?'}</strong> ·
                                                    Max <strong>{config.autoscalerMaxReplicas ?? '?'}</strong> instances ·
                                                    {config.autoscalerCoolDownPeriodSec ? ` ${config.autoscalerCoolDownPeriodSec}s cooldown` : ''}
                                                </p>
                                                <p className="text-xs text-amber-500 mt-1">Target size is managed automatically — edit autoscaler limits in GCP Console.</p>
                                            </div>
                                        ) : (
                                            <div>
                                                <div className="flex items-center gap-3">
                                                    <input
                                                        type="number" min={0} max={500}
                                                        value={config.targetSize}
                                                        onChange={e => setConfig({ ...config, targetSize: Math.max(0, Number(e.target.value)) })}
                                                        className="w-32 border border-slate-200 rounded-lg px-3 py-2 text-sm font-mono focus:ring-2 focus:ring-indigo-400 focus:border-transparent"
                                                    />
                                                    <div className="flex gap-1">
                                                        {[1,2,3,5,10].map(n => (
                                                            <button key={n} onClick={() => setConfig({ ...config, targetSize: n })}
                                                                className={`px-2.5 py-1.5 rounded-lg text-xs font-bold border transition-all ${
                                                                    config.targetSize === n
                                                                        ? 'bg-indigo-600 text-white border-indigo-600'
                                                                        : 'bg-white text-slate-600 border-slate-200 hover:border-indigo-400'}`}>
                                                                {n}
                                                            </button>
                                                        ))}
                                                    </div>
                                                </div>
                                                <p className="text-[10px] text-slate-400 mt-1.5">Total number of VMs in this MIG</p>
                                            </div>
                                        )}
                                    </div>

                                    {/* Live counts */}
                                    <div className="grid grid-cols-3 gap-3">
                                        <div className="p-3 bg-slate-50 rounded-xl border border-slate-100 text-center">
                                            <p className="text-lg font-bold text-slate-800">{mig.instanceCount}</p>
                                            <p className="text-[10px] text-slate-500 uppercase font-bold mt-0.5">Total</p>
                                        </div>
                                        <div className="p-3 bg-emerald-50 rounded-xl border border-emerald-100 text-center">
                                            <p className="text-lg font-bold text-emerald-700">{mig.spotCount}</p>
                                            <p className="text-[10px] text-emerald-600 uppercase font-bold mt-0.5">Spot</p>
                                        </div>
                                        <div className="p-3 bg-orange-50 rounded-xl border border-orange-100 text-center">
                                            <p className="text-lg font-bold text-orange-700">{mig.onDemandCount}</p>
                                            <p className="text-[10px] text-orange-600 uppercase font-bold mt-0.5">On-Demand</p>
                                        </div>
                                    </div>
                                </div>
                            )}

                            {/* ── Rolling Update Tab ────────────────────────── */}
                            {activeSection === 'rolling' && (
                                <div className="space-y-4">
                                    <p className="text-xs text-slate-500">
                                        Controls how GCP replaces VMs when you toggle Spot. Default settings guarantee zero downtime.
                                    </p>
                                    <div className="grid grid-cols-3 gap-3">
                                        <label className="block">
                                            <span className="text-xs text-slate-500 font-semibold">Max Surge</span>
                                            <div className="relative mt-1">
                                                <input
                                                    type="number" min={0} max={10}
                                                    value={config.maxSurge}
                                                    onChange={e => setConfig({ ...config, maxSurge: Number(e.target.value) })}
                                                    className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm font-mono focus:ring-2 focus:ring-indigo-400 focus:border-transparent"
                                                />
                                                <span className="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] text-slate-400">VMs</span>
                                            </div>
                                            <p className="text-[10px] text-slate-400 mt-1">Extra VMs over target during update</p>
                                        </label>
                                        <label className="block">
                                            <span className="text-xs text-slate-500 font-semibold">Max Unavailable</span>
                                            <div className="relative mt-1">
                                                <input
                                                    type="number" min={0} max={10}
                                                    value={config.maxUnavailable}
                                                    onChange={e => setConfig({ ...config, maxUnavailable: Number(e.target.value) })}
                                                    className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm font-mono focus:ring-2 focus:ring-indigo-400 focus:border-transparent"
                                                />
                                                <span className="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] text-slate-400">VMs</span>
                                            </div>
                                            <p className="text-[10px] text-slate-400 mt-1">0 = zero downtime guaranteed</p>
                                        </label>
                                        <label className="block">
                                            <span className="text-xs text-slate-500 font-semibold">Min Ready</span>
                                            <div className="relative mt-1">
                                                <input
                                                    type="number" min={0} max={300}
                                                    value={config.minReadySeconds}
                                                    onChange={e => setConfig({ ...config, minReadySeconds: Number(e.target.value) })}
                                                    className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm font-mono focus:ring-2 focus:ring-indigo-400 focus:border-transparent"
                                                />
                                                <span className="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] text-slate-400">sec</span>
                                            </div>
                                            <p className="text-[10px] text-slate-400 mt-1">Health wait before removing old VM</p>
                                        </label>
                                    </div>

                                    <div className="p-3 bg-indigo-50 rounded-xl border border-indigo-100 text-xs text-indigo-700 font-medium">
                                        <i className="fas fa-info-circle mr-1.5"></i>
                                        {config.maxUnavailable === 0
                                            ? `Zero-downtime: +${config.maxSurge} new VM starts → ${config.minReadySeconds}s health check → old VM removed`
                                            : `Rolling: up to ${config.maxUnavailable} VM${config.maxUnavailable > 1 ? 's' : ''} may be offline during replacement`}
                                    </div>

                                    <div className="p-3 bg-slate-50 rounded-xl border border-slate-100 text-xs text-slate-600">
                                        <p className="font-bold text-slate-700 mb-1">Recommended presets</p>
                                        <div className="flex gap-2 flex-wrap">
                                            {[
                                                { label: 'Zero-downtime', surge: 1, unavail: 0, ready: 30 },
                                                { label: 'Fast (brief gap)', surge: 0, unavail: 1, ready: 10 },
                                                { label: 'Aggressive', surge: 2, unavail: 0, ready: 15 },
                                            ].map(p => (
                                                <button key={p.label}
                                                    onClick={() => setConfig({ ...config, maxSurge: p.surge, maxUnavailable: p.unavail, minReadySeconds: p.ready })}
                                                    className="px-3 py-1.5 bg-white border border-slate-200 rounded-lg text-xs font-semibold hover:border-indigo-400 hover:text-indigo-600 transition-all">
                                                    {p.label}
                                                </button>
                                            ))}
                                        </div>
                                    </div>
                                </div>
                            )}

                            {/* ── Machine Types Tab ─────────────────────────── */}
                            {activeSection === 'types' && (
                                <div className="space-y-4">
                                    <div className="p-3 bg-blue-50 border border-blue-200 rounded-xl text-xs text-blue-800">
                                        <p className="font-bold mb-1"><i className="fas fa-info-circle mr-1.5"></i>Current Machine Type</p>
                                        <p className="font-mono text-blue-900 text-sm">{config.machineType || 'unknown'}</p>
                                        <p className="text-blue-600 mt-1">To change machine type, create a new instance template in GCP Console and apply it to this MIG. Below are the GCP machine families for reference when creating Spot-optimised instances.</p>
                                    </div>

                                    <div className="p-3 bg-amber-50 border border-amber-100 rounded-xl text-xs text-amber-800">
                                        <p className="font-bold mb-1"><i className="fas fa-lightbulb mr-1.5"></i>Spot Diversification Tip</p>
                                        <p>Unlike AWS (where one ASG can run multiple instance types), GCP MIGs use one template. For Spot availability, use <strong>E2 or N2D</strong> families — they have the best Spot availability rates on GCP.</p>
                                    </div>

                                    <div className="space-y-3">
                                        {Object.entries(GCP_MACHINE_FAMILIES).map(([family, types]) => (
                                            <div key={family} className="border border-slate-200 rounded-xl p-3 bg-slate-50">
                                                <h4 className="text-xs font-bold text-slate-700 mb-2 flex items-center gap-1.5">
                                                    <i className="fas fa-microchip text-indigo-500"></i>{family}
                                                </h4>
                                                <div className="flex flex-wrap gap-1.5">
                                                    {types.map(t => (
                                                        <span key={t}
                                                            className={`px-2 py-1 rounded-lg text-[11px] font-mono border transition-all ${
                                                                config.machineType === t
                                                                    ? 'bg-indigo-600 text-white border-indigo-600 font-bold'
                                                                    : 'bg-white text-slate-600 border-slate-200'}`}>
                                                            {t}
                                                        </span>
                                                    ))}
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            )}

                            {error && (
                                <div className="mt-4 p-3 bg-rose-50 border border-rose-200 rounded-xl text-xs text-rose-700 font-medium">
                                    <i className="fas fa-exclamation-circle mr-1.5"></i>{error}
                                </div>
                            )}
                            {saveSuccess && (
                                <div className="mt-4 p-3 bg-emerald-50 border border-emerald-200 rounded-xl text-xs text-emerald-700 font-bold">
                                    <i className="fas fa-check-circle mr-1.5"></i>Configuration saved successfully!
                                </div>
                            )}
                        </div>

                        {/* Footer */}
                        <div className="px-6 py-4 border-t border-slate-100 flex justify-end gap-3 bg-slate-50 shrink-0">
                            <button onClick={onClose}
                                className="px-4 py-2 text-sm font-semibold text-slate-600 hover:text-slate-800 transition-colors">
                                Cancel
                            </button>
                            <button onClick={handleSave} disabled={saving || saveSuccess || activeSection === 'types'}
                                className="px-5 py-2 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white text-sm font-bold rounded-xl transition-all shadow-sm shadow-indigo-200 flex items-center gap-2"
                                title={activeSection === 'types' ? 'Machine type changes require GCP Console' : ''}>
                                {saving ? <><i className="fas fa-circle-notch fa-spin"></i> Saving…</> : 'Save Configuration'}
                            </button>
                        </div>
                    </>
                ) : (
                    <div className="px-6 py-8 text-center text-sm text-slate-500">{error || 'Could not load config'}</div>
                )}
            </div>
        </div>
    );
};

// ─── Analytics Tab ────────────────────────────────────────────────────────────

interface AnalyticsTabProps {
    accountId: string;
}

const AnalyticsTab: React.FC<AnalyticsTabProps> = ({ accountId }) => {
    const [events, setEvents] = useState<any[]>([]);
    const [summary, setSummary] = useState<any>(null);
    const [loading, setLoading] = useState(true);
    const [range, setRange] = useState<'last7d' | 'last30d'>('last30d');

    useEffect(() => {
        const load = async () => {
            setLoading(true);
            try {
                const end = new Date().toISOString();
                const startDate = new Date();
                startDate.setDate(startDate.getDate() - (range === 'last30d' ? 30 : 7));
                const start = startDate.toISOString();
                const data = await apiClient.get(
                    `/api/gcp/autospotting/events/${accountId}?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`
                );
                setEvents(data.events || []);
                setSummary(data.summary || null);
            } catch (e) {
                console.error('Failed to load analytics:', e);
            } finally {
                setLoading(false);
            }
        };
        if (accountId) load();
    }, [accountId, range]);

    // Aggregate by event type
    const replacements = events.filter(e => e.event_type === 'instance_replacement');
    const interruptions = events.filter(e => e.event_type === 'spot_interruption');
    const failures = events.filter(e => e.event_type === 'launch_failure');
    const successRate = (replacements.length + interruptions.length + failures.length) > 0
        ? Math.round((replacements.length / (replacements.length + failures.length)) * 100) || 0
        : 0;

    // Group replacements by MIG for a breakdown table
    const byMig: Record<string, { count: number; savings: number }> = {};
    replacements.forEach(e => {
        const key = e.asg_name || e.asgName || 'unknown';
        if (!byMig[key]) byMig[key] = { count: 0, savings: 0 };
        byMig[key].count += 1;
        byMig[key].savings += e.estimated_savings || e.estimatedSavings || 0;
    });

    return (
        <div className="space-y-6">
            {/* Range selector */}
            <div className="flex items-center justify-between">
                <h3 className="text-lg font-bold text-slate-900">Spot Launch Analytics</h3>
                <div className="flex gap-2">
                    {(['last7d', 'last30d'] as const).map(r => (
                        <button
                            key={r}
                            onClick={() => setRange(r)}
                            className={`px-3 py-1.5 text-xs font-bold rounded-lg transition-all ${range === r
                                ? 'bg-indigo-600 text-white shadow-sm'
                                : 'bg-white text-slate-600 border border-slate-200 hover:border-indigo-300'
                                }`}
                        >
                            {r === 'last7d' ? 'Last 7 days' : 'Last 30 days'}
                        </button>
                    ))}
                </div>
            </div>

            {loading ? (
                <div className="flex items-center justify-center py-16 bg-white border border-slate-200 rounded-2xl">
                    <i className="fas fa-circle-notch fa-spin text-indigo-500 text-2xl"></i>
                </div>
            ) : (
                <>
                    {/* KPI Cards */}
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                        {[
                            { label: 'Spot Replacements', value: replacements.length, icon: 'fa-exchange-alt', color: 'emerald' },
                            { label: 'Interruptions', value: interruptions.length, icon: 'fa-bolt', color: 'amber' },
                            { label: 'Launch Failures', value: failures.length, icon: 'fa-exclamation-triangle', color: 'rose' },
                            { label: 'Success Rate', value: `${successRate}%`, icon: 'fa-check-circle', color: 'indigo' },
                        ].map(card => (
                            <div key={card.label} className="bg-white border border-slate-200 rounded-2xl p-4 shadow-sm">
                                <div className={`w-9 h-9 rounded-xl bg-${card.color}-50 flex items-center justify-center text-${card.color}-500 mb-3 border border-${card.color}-100`}>
                                    <i className={`fas ${card.icon} text-sm`}></i>
                                </div>
                                <p className="text-2xl font-black text-slate-900">{card.value}</p>
                                <p className="text-xs font-semibold text-slate-500 mt-0.5">{card.label}</p>
                            </div>
                        ))}
                    </div>

                    {/* Total savings */}
                    {summary && summary.totalEstimatedSavings > 0 && (
                        <div className="bg-emerald-50 border border-emerald-200 rounded-2xl p-5 flex items-center justify-between">
                            <div>
                                <p className="text-xs font-bold text-emerald-700 uppercase tracking-wider">Total Spot Savings ({range === 'last7d' ? '7d' : '30d'})</p>
                                <p className="text-3xl font-black text-emerald-700 mt-1">
                                    ₹{summary.totalEstimatedSavings.toFixed(2)}
                                </p>
                            </div>
                            <div className="w-14 h-14 bg-emerald-100 rounded-2xl flex items-center justify-center">
                                <i className="fas fa-piggy-bank text-2xl text-emerald-600"></i>
                            </div>
                        </div>
                    )}

                    {/* Per-MIG breakdown */}
                    {Object.keys(byMig).length > 0 && (
                        <div className="bg-white border border-slate-200 rounded-2xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 border-b border-slate-100">
                                <h4 className="font-bold text-slate-800">Replacements by MIG</h4>
                            </div>
                            <table className="w-full text-left">
                                <thead>
                                    <tr className="bg-slate-50 text-xs uppercase tracking-wider text-slate-500">
                                        <th className="px-5 py-3 font-bold">MIG Name</th>
                                        <th className="px-5 py-3 font-bold text-center">Replacements</th>
                                        <th className="px-5 py-3 font-bold text-right">Savings</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-50">
                                    {Object.entries(byMig).map(([name, stats]) => (
                                        <tr key={name} className="hover:bg-slate-50 transition-colors">
                                            <td className="px-5 py-3 text-sm font-semibold text-slate-800 font-mono">{name}</td>
                                            <td className="px-5 py-3 text-center">
                                                <span className="inline-flex items-center px-2.5 py-0.5 bg-indigo-50 text-indigo-700 rounded-full text-xs font-bold border border-indigo-100">
                                                    {stats.count}
                                                </span>
                                            </td>
                                            <td className="px-5 py-3 text-right text-sm font-bold text-emerald-600">
                                                ₹{stats.savings.toFixed(4)}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}

                    {/* Interruption pattern */}
                    {interruptions.length > 0 && (
                        <div className="bg-amber-50 border border-amber-200 rounded-2xl p-4">
                            <p className="text-xs font-bold text-amber-700 uppercase tracking-wider mb-2">
                                <i className="fas fa-bolt mr-1.5"></i>Spot Interruption Pattern
                            </p>
                            <p className="text-sm text-amber-700">
                                {interruptions.length} interruption{interruptions.length > 1 ? 's' : ''} detected in this period.
                                GCP automatically replaces preempted VMs — your application continues to run via the MIG autoscaler.
                            </p>
                        </div>
                    )}

                    {events.length === 0 && (
                        <div className="bg-white border border-slate-200 rounded-2xl p-12 text-center">
                            <div className="w-16 h-16 bg-slate-50 rounded-2xl flex items-center justify-center mx-auto mb-4">
                                <i className="fas fa-chart-pie text-2xl text-slate-300"></i>
                            </div>
                            <p className="text-slate-500 font-semibold">No events in this period</p>
                            <p className="text-slate-400 text-sm mt-1">Enable AutoSpotting on a MIG to start seeing data here.</p>
                        </div>
                    )}
                </>
            )}
        </div>
    );
};

// ─── Main Page ────────────────────────────────────────────────────────────────

const GcpSpotAutomationPage: React.FC = () => {
    const { selectedAccountDisplay, isLoading: accountsLoading } = useAccounts();
    const selectedAccountId = selectedAccountDisplay.id && selectedAccountDisplay.id !== 'No account selected'
        ? selectedAccountDisplay.id : null;
    const isGcp = selectedAccountDisplay.provider === 'GCP';

    const {
        migs,
        costs,
        savings,
        enabledLocationsCount,
        totalInstances,
        historyData,
        historySummary,
        isLoading,
        isLoadingHistory,
        error,
        refresh,
        fetchHistoryData,
        toggleMIG,
    } = useGcpSpotAutomationData({ selectedAccountId });

    const [activeTab, setActiveTab] = useTabState<'dashboard' | 'analytics' | 'history'>('dashboard');
    const [configMig, setConfigMig] = useState<GcpMIG | null>(null);

    // Toast state
    const [toast, setToast] = useState<{ msg: string; type: 'success' | 'error' | 'info' } | null>(null);
    const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

    // Live change detection: tracks MIG states before toggle and detects after poll
    const [liveChanges, setLiveChanges] = useState<LiveChangeEntry[]>([]);
    const [pollingMig, setPollingMig] = useState<string | null>(null); // key = "name:region"
    const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const prevMigStates = useRef<Map<string, boolean>>(new Map());

    const showToast = useCallback((msg: string, type: 'success' | 'error' | 'info' = 'success') => {
        if (toastTimer.current) clearTimeout(toastTimer.current);
        setToast({ msg, type });
        toastTimer.current = setTimeout(() => setToast(null), 4000);
    }, []);

    // Snapshot current MIG states before toggle
    const snapshotStates = useCallback((currentMigs: GcpMIG[]) => {
        const map = new Map<string, boolean>();
        currentMigs.forEach(m => map.set(`${m.name}:${m.region}`, m.isEnabled));
        prevMigStates.current = map;
    }, []);

    // Compare states after refresh to detect live changes
    const detectChanges = useCallback((updatedMigs: GcpMIG[]) => {
        const changes: LiveChangeEntry[] = [];
        updatedMigs.forEach(m => {
            const key = `${m.name}:${m.region}`;
            const prev = prevMigStates.current.get(key);
            if (prev !== undefined && prev !== m.isEnabled) {
                changes.push({
                    migName: m.name,
                    region: m.region,
                    oldState: prev,
                    newState: m.isEnabled,
                    detectedAt: new Date().toLocaleTimeString(),
                });
            }
        });
        if (changes.length > 0) {
            setLiveChanges(prev => [...changes, ...prev].slice(0, 10));
        }
    }, []);

    // Poll after a toggle to confirm state change on GCP side
    const startPolling = useCallback((migKey: string) => {
        if (pollingRef.current) clearInterval(pollingRef.current);
        setPollingMig(migKey);
        let attempts = 0;
        pollingRef.current = setInterval(async () => {
            attempts++;
            try {
                await refresh();
            } catch (_) {}
            if (attempts >= 8) { // poll for ~40s max
                clearInterval(pollingRef.current!);
                pollingRef.current = null;
                setPollingMig(null);
            }
        }, 5000);
    }, [refresh]);

    useEffect(() => () => {
        if (pollingRef.current) clearInterval(pollingRef.current);
        if (toastTimer.current) clearTimeout(toastTimer.current);
    }, []);

    // Detect live changes whenever migs update
    useEffect(() => {
        if (migs.length > 0) detectChanges(migs);
    }, [migs, detectChanges]);

    const handleToggle = async (mig: GcpMIG) => {
        snapshotStates(migs);
        const nextState = !mig.isEnabled;
        try {
            await toggleMIG(mig);
            showToast(
                `AutoSpotting ${nextState ? 'enabled' : 'disabled'} for ${mig.name}. Rolling update started.`,
                'success'
            );
            startPolling(`${mig.name}:${mig.region}`);
        } catch (err: any) {
            showToast(err?.message || 'Toggle failed', 'error');
        }
    };

    const handleTimeRangeChange = (range: 'last24h' | 'last7d' | 'last30d') => {
        fetchHistoryData(range);
    };

    const enabledMIGsCount = migs.filter(m => m.isEnabled).length;

    // ── Guard: no account / not GCP ───────────────────────────────────────────

    if (accountsLoading) return <DashboardSkeleton />;

    if (!selectedAccountId) {
        return (
            <div className="p-8 flex items-center justify-center min-h-[60vh]">
                <div className="bg-white border border-slate-200 shadow-sm rounded-3xl p-10 max-w-md text-center">
                    <div className="w-16 h-16 bg-slate-50 rounded-2xl flex items-center justify-center text-slate-400 mx-auto mb-6">
                        <i className="fas fa-project-diagram text-2xl"></i>
                    </div>
                    <h2 className="text-2xl font-bold text-slate-900 mb-2">No Account Selected</h2>
                    <p className="text-slate-500 text-sm">Select a GCP account from the account selector.</p>
                </div>
            </div>
        );
    }

    if (!isGcp) {
        return (
            <div className="p-8 flex items-center justify-center min-h-[60vh]">
                <div className="bg-white border border-amber-100 shadow-sm rounded-3xl p-10 max-w-md text-center">
                    <div className="w-16 h-16 bg-amber-50 rounded-2xl flex items-center justify-center text-amber-500 mx-auto mb-6">
                        <i className="fas fa-exclamation-triangle text-2xl"></i>
                    </div>
                    <h2 className="text-2xl font-bold text-slate-900 mb-2">GCP Account Required</h2>
                    <p className="text-slate-600 text-sm">Select a GCP account to use Spot Automation.</p>
                </div>
            </div>
        );
    }

    // ── Render ─────────────────────────────────────────────────────────────────

    return (
        <div className="flex-1 flex flex-col min-h-screen bg-slate-50 relative">

            {/* Config Modal */}
            {configMig && (
                <ConfigModal
                    mig={configMig}
                    accountId={selectedAccountId}
                    onClose={() => setConfigMig(null)}
                    onSaved={() => { refresh(); showToast('Config saved', 'success'); }}
                />
            )}

            {/* Toast */}
            {toast && (
                <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5 rounded-2xl shadow-xl text-sm font-semibold transition-all
                    ${toast.type === 'success' ? 'bg-emerald-600 text-white'
                    : toast.type === 'error' ? 'bg-rose-600 text-white'
                    : 'bg-slate-800 text-white'}`}>
                    <i className={`fas ${toast.type === 'success' ? 'fa-check-circle' : toast.type === 'error' ? 'fa-exclamation-circle' : 'fa-info-circle'}`}></i>
                    {toast.msg}
                    <button onClick={() => setToast(null)} className="ml-2 opacity-70 hover:opacity-100">
                        <i className="fas fa-times text-xs"></i>
                    </button>
                </div>
            )}

            {/* Tab Navigation */}
            <div className="bg-white/80 border-b border-slate-200 px-8 backdrop-blur-md sticky top-0 z-30">
                <nav className="flex space-x-8" aria-label="Tabs">
                    {([
                        { id: 'dashboard', label: 'Dashboard', icon: 'fa-tachometer-alt' },
                        { id: 'analytics', label: 'Launch Analytics', icon: 'fa-chart-line' },
                        { id: 'history', label: 'Actions History', icon: 'fa-history' },
                    ] as const).map(tab => (
                        <button
                            key={tab.id}
                            onClick={() => setActiveTab(tab.id)}
                            className={`py-4 px-1 border-b-2 font-medium text-sm transition-all flex items-center gap-2 ${activeTab === tab.id
                                ? 'border-indigo-600 text-indigo-600'
                                : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'}`}
                        >
                            <i className={`fas ${tab.icon}`}></i>
                            {tab.label}
                        </button>
                    ))}
                </nav>
            </div>

            {/* Main Content */}
            <div className="flex-1 p-8 space-y-6">

                {/* ── Dashboard Tab ──────────────────────────────────────────── */}
                {activeTab === 'dashboard' && (
                    <>
                        {/* KPIs */}
                        <SpotAutomationKPIs
                            costs={costs}
                            savings={savings}
                            enabledASGsCount={enabledMIGsCount}
                            totalASGsCount={migs.length}
                            enabledRegionsCount={enabledLocationsCount}
                        />

                        {/* Summary row */}
                        <div className="flex items-center justify-between text-[11px] font-bold uppercase tracking-wider text-slate-500 px-1">
                            <span>
                                Total Instances: <span className="text-slate-900">{totalInstances}</span>
                                {pollingMig && (
                                    <span className="ml-3 text-indigo-500 normal-case font-semibold">
                                        <i className="fas fa-circle-notch fa-spin mr-1"></i>
                                        Checking MIG state…
                                    </span>
                                )}
                            </span>
                            {error && (
                                <span className="text-rose-600 flex items-center gap-1">
                                    <i className="fas fa-exclamation-circle"></i>{error.message}
                                </span>
                            )}
                        </div>

                        {/* Live Changes Banner */}
                        {liveChanges.length > 0 && (
                            <div className="bg-indigo-50 border border-indigo-200 rounded-2xl p-4">
                                <div className="flex items-center justify-between mb-2">
                                    <p className="text-xs font-bold text-indigo-700 uppercase tracking-wider">
                                        <i className="fas fa-bolt mr-1.5"></i>Live Changes Detected
                                    </p>
                                    <button onClick={() => setLiveChanges([])} className="text-xs text-indigo-400 hover:text-indigo-600">
                                        Clear
                                    </button>
                                </div>
                                <div className="space-y-1">
                                    {liveChanges.slice(0, 3).map((c, i) => (
                                        <div key={i} className="flex items-center gap-2 text-xs text-indigo-700">
                                            <span className="font-mono font-semibold">{c.migName}</span>
                                            <span className={`px-2 py-0.5 rounded-full font-bold text-[10px] ${c.oldState ? 'bg-indigo-100 text-indigo-600' : 'bg-slate-100 text-slate-500'}`}>
                                                {c.oldState ? 'SPOT' : 'OD'}
                                            </span>
                                            <i className="fas fa-arrow-right text-indigo-300"></i>
                                            <span className={`px-2 py-0.5 rounded-full font-bold text-[10px] ${c.newState ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 text-slate-500'}`}>
                                                {c.newState ? 'SPOT' : 'OD'}
                                            </span>
                                            <span className="text-indigo-400 ml-1">{c.detectedAt}</span>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        )}

                        {/* Chart */}
                        <SpotAutomationChart
                            historyData={historyData}
                            historySummary={historySummary}
                            timeRange="last7d"
                            isLoading={isLoadingHistory}
                            onTimeRangeChange={handleTimeRangeChange}
                            onRefresh={() => fetchHistoryData()}
                        />

                        {/* MIGs Table */}
                        <div className="bg-white border border-slate-200 rounded-2xl shadow-sm overflow-hidden">
                            <div className="p-6 border-b border-slate-100 flex items-center justify-between">
                                <div>
                                    <h3 className="text-xl font-bold text-slate-900">Managed Instance Groups</h3>
                                    <p className="text-slate-500 text-sm mt-1">
                                        Configure AutoSpotting for your GCP MIGs — zero-downtime rolling updates
                                    </p>
                                </div>
                                <button
                                    onClick={refresh}
                                    className="p-2 bg-slate-50 hover:bg-slate-100 text-slate-600 rounded-lg transition-colors border border-slate-200"
                                    title="Refresh MIG list"
                                >
                                    <i className={`fas fa-sync-alt ${isLoading ? 'fa-spin' : ''}`}></i>
                                </button>
                            </div>

                            <div className="overflow-x-auto">
                                <table className="w-full text-left">
                                    <thead>
                                        <tr className="bg-slate-50/50 text-slate-500 text-xs uppercase tracking-wider">
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">Instance Group</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">Region / Zone</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">Status</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100 text-center">Instances</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100 text-center">Spot / OD</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100 text-right">Actual Savings/hr</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100 text-right">Potential Savings/hr</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100 text-center">AutoSpotting</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100">
                                        {migs.length > 0 ? migs.map(mig => {
                                            const migKey = `${mig.name}:${mig.region}`;
                                            const isPollingThis = pollingMig === migKey;

                                            return (
                                                <tr key={migKey} className={`hover:bg-slate-50 transition-colors group ${isPollingThis ? 'bg-indigo-50/30' : ''}`}>
                                                    {/* Name */}
                                                    <td className="px-6 py-4">
                                                        <div className="flex items-center gap-3">
                                                            <div className="w-9 h-9 rounded-xl bg-indigo-50 flex items-center justify-center text-indigo-600 border border-indigo-100 shadow-sm">
                                                                <i className="fas fa-layer-group text-sm"></i>
                                                            </div>
                                                            <div>
                                                                <div className="text-sm font-bold text-slate-800 group-hover:text-indigo-600 transition-colors">
                                                                    {mig.name}
                                                                </div>
                                                                <div className="text-[10px] text-slate-400 font-mono mt-0.5">GOOGLE CLOUD MIG</div>
                                                            </div>
                                                        </div>
                                                    </td>

                                                    {/* Region */}
                                                    <td className="px-6 py-4">
                                                        <span className="text-xs font-semibold text-slate-600 bg-white px-2.5 py-1 rounded-full border border-slate-200">
                                                            {mig.region}
                                                        </span>
                                                    </td>

                                                    {/* Status */}
                                                    <td className="px-6 py-4">
                                                        {mig.isEnabled ? (
                                                            <span className="flex items-center gap-1.5 text-xs font-bold text-emerald-600">
                                                                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
                                                                {isPollingThis ? 'Converting…' : 'Active (Spot)'}
                                                            </span>
                                                        ) : (
                                                            <span className="flex items-center gap-1.5 text-xs font-bold text-slate-400">
                                                                <span className="w-2 h-2 rounded-full bg-slate-300"></span>
                                                                {isPollingThis ? 'Reverting…' : 'On-Demand'}
                                                            </span>
                                                        )}
                                                    </td>

                                                    {/* Total instances */}
                                                    <td className="px-6 py-4 text-center">
                                                        <span className="text-sm font-bold text-slate-900">{mig.instanceCount}</span>
                                                    </td>

                                                    {/* Spot / OD split — like AWS column */}
                                                    <td className="px-6 py-4 text-center">
                                                        <div className="inline-flex items-center gap-1 text-xs font-bold">
                                                            <span className="text-emerald-600">{mig.spotCount}</span>
                                                            <span className="text-slate-300">/</span>
                                                            <span className="text-orange-500">{mig.onDemandCount}</span>
                                                        </div>
                                                        <div className="text-[9px] text-slate-400 mt-0.5 font-medium">SPOT / OD</div>
                                                    </td>

                                                    {/* Actual savings */}
                                                    <td className="px-6 py-4 text-right">
                                                        {mig.savings > 0 ? (
                                                            <span className="text-sm font-bold text-emerald-600">
                                                                ₹{mig.savings.toFixed(4)}
                                                            </span>
                                                        ) : (
                                                            <span className="text-xs text-slate-300">—</span>
                                                        )}
                                                    </td>

                                                    {/* Potential savings */}
                                                    <td className="px-6 py-4 text-right">
                                                        <div className="text-sm font-bold text-indigo-600">
                                                            +₹{mig.potentialSavings.toFixed(4)}
                                                        </div>
                                                    </td>

                                                    {/* Toggle + Config */}
                                                    <td className="px-6 py-4">
                                                        <div className="flex flex-col items-center gap-2">
                                                            {/* Toggle */}
                                                            <button
                                                                onClick={() => handleToggle(mig)}
                                                                disabled={mig.updating}
                                                                title={mig.isEnabled ? 'Disable AutoSpotting' : 'Enable AutoSpotting'}
                                                                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2
                                                                    ${mig.isEnabled ? 'bg-indigo-600' : 'bg-slate-200'}
                                                                    ${mig.updating ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
                                                            >
                                                                <span className="sr-only">Toggle AutoSpotting</span>
                                                                <span className={`${mig.isEnabled ? 'translate-x-6' : 'translate-x-1'} inline-flex h-4 w-4 transform rounded-full bg-white transition-transform ease-in-out duration-200 items-center justify-center`}>
                                                                    {mig.updating && <i className="fas fa-circle-notch fa-spin text-[8px] text-indigo-600"></i>}
                                                                </span>
                                                            </button>

                                                            {/* Config button (always visible, like AWS) */}
                                                            <button
                                                                onClick={() => setConfigMig(mig)}
                                                                className="text-[10px] text-purple-500 hover:text-purple-700 flex items-center gap-1 px-2 py-0.5 rounded hover:bg-purple-50 transition-colors font-bold uppercase tracking-wide"
                                                                title="Configure update policy and autoscaler"
                                                            >
                                                                <i className="fas fa-cog text-[9px]"></i>
                                                                Config
                                                            </button>
                                                        </div>
                                                    </td>
                                                </tr>
                                            );
                                        }) : (
                                            <tr>
                                                <td colSpan={8} className="px-6 py-12 text-center text-slate-500 italic">
                                                    {isLoading ? (
                                                        <><i className="fas fa-circle-notch fa-spin mr-2 text-indigo-400"></i>Scanning for Managed Instance Groups…</>
                                                    ) : 'No Managed Instance Groups found in this project.'}
                                                </td>
                                            </tr>
                                        )}
                                    </tbody>
                                </table>
                            </div>

                            {/* Footer summary row */}
                            {migs.length > 0 && (
                                <div className="px-6 py-4 bg-slate-50 border-t border-slate-100 flex items-center justify-between text-xs text-slate-500 font-semibold">
                                    <span>
                                        {migs.length} MIG{migs.length !== 1 ? 's' : ''} ·{' '}
                                        {totalInstances} instance{totalInstances !== 1 ? 's' : ''} ·{' '}
                                        <span className="text-emerald-600 font-bold">{enabledMIGsCount} spot-enabled</span>
                                    </span>
                                    <div className="flex items-center gap-4">
                                        <span className="flex items-center gap-1.5">
                                            <span className="w-2.5 h-2.5 rounded-full bg-emerald-500"></span>Spot
                                        </span>
                                        <span className="flex items-center gap-1.5">
                                            <span className="w-2.5 h-2.5 rounded-full bg-orange-400"></span>On-Demand
                                        </span>
                                    </div>
                                </div>
                            )}
                        </div>
                    </>
                )}

                {/* ── Analytics Tab ──────────────────────────────────────────── */}
                {activeTab === 'analytics' && (
                    <AnalyticsTab accountId={selectedAccountId} />
                )}

                {/* ── History Tab ────────────────────────────────────────────── */}
                {activeTab === 'history' && (
                    <ActionsHistory accountId={selectedAccountId} provider="GCP" />
                )}
            </div>
        </div>
    );
};

export default GcpSpotAutomationPage;
