import React, { useState, useEffect, useCallback, useRef } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpSpotAutomationData, GcpMIG } from '../hooks/useGcpSpotAutomationData';
import SpotAutomationKPIs from '../components/SpotAutomation/SpotAutomationKPIs';
import SpotAutomationChart from '../components/SpotAutomation/SpotAutomationChart';
import ActionsHistory from '../components/ActionsHistory/ActionsHistory';
import apiClient from '../utils/apiClient';
import { useTabState } from '../hooks/useTabState';
import { useTopbar } from '../contexts/TopbarContext';

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

const GCP_MACHINE_FAMILIES: Record<string, string[]> = {
    'E2 (Cost-Optimized)':    ['e2-micro', 'e2-small', 'e2-medium', 'e2-standard-2', 'e2-standard-4', 'e2-standard-8', 'e2-highcpu-4', 'e2-highcpu-8'],
    'N2 (General Purpose)':   ['n2-standard-2', 'n2-standard-4', 'n2-standard-8', 'n2-standard-16', 'n2-highcpu-4', 'n2-highcpu-8', 'n2-highmem-4', 'n2-highmem-8'],
    'N2D (AMD General)':      ['n2d-standard-2', 'n2d-standard-4', 'n2d-standard-8', 'n2d-highcpu-4', 'n2d-highcpu-8'],
    'N1 (First Gen)':         ['n1-standard-1', 'n1-standard-2', 'n1-standard-4', 'n1-standard-8', 'n1-highcpu-4', 'n1-highcpu-8', 'n1-highmem-4', 'n1-highmem-8'],
    'C2 (Compute Optimized)': ['c2-standard-4', 'c2-standard-8', 'c2-standard-16', 'c2-standard-30', 'c2-standard-60'],
    'T2D (Scale-Out)':        ['t2d-standard-1', 't2d-standard-2', 't2d-standard-4', 't2d-standard-8'],
};

const inputCls = 'w-full border border-[rgba(0,0,0,0.15)] rounded-lg px-3 py-2 text-[13px] font-mono focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none bg-white';

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
            setTimeout(() => { onSaved(); onClose(); }, 1200);
        } catch (e: any) {
            setError(e?.message || 'Failed to save config');
        } finally {
            setSaving(false);
        }
    };

    const tabs = [
        { id: 'instances' as const, label: 'Instances',      icon: 'mdi:server-outline' },
        { id: 'rolling'   as const, label: 'Rolling Update', icon: 'mdi:sync' },
        { id: 'types'     as const, label: 'Machine Types',  icon: 'mdi:chip' },
    ];

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
            <div className="bg-white rounded-2xl shadow-2xl w-full max-w-xl mx-4 overflow-hidden flex flex-col max-h-[90vh]">

                {/* Header */}
                <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.08)] flex items-center justify-between bg-[rgba(0,0,0,0.01)] shrink-0">
                    <div>
                        <h2 className="text-[15px] font-bold text-[rgba(0,0,0,0.88)]">MIG Configuration</h2>
                        <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-0.5 font-mono truncate max-w-sm">{mig.name}</p>
                    </div>
                    <button onClick={onClose} className="p-2 hover:bg-[rgba(0,0,0,0.05)] rounded-lg transition-colors">
                        <Icon icon="mdi:close" width={16} color="rgba(0,0,0,0.45)" />
                    </button>
                </div>

                {loading ? (
                    <div className="flex items-center justify-center py-16">
                        <Icon icon="mdi:loading" width={28} className="animate-spin text-indigo-500" />
                    </div>
                ) : config ? (
                    <>
                        {/* Section Tabs */}
                        <div className="flex border-b border-[rgba(0,0,0,0.08)] shrink-0 px-6">
                            {tabs.map(t => (
                                <button key={t.id} onClick={() => setActiveSection(t.id)}
                                    className={`py-3 px-4 text-[12px] font-bold border-b-2 transition-all flex items-center gap-1.5 ${
                                        activeSection === t.id
                                            ? 'border-indigo-600 text-indigo-600'
                                            : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
                                    <Icon icon={t.icon} width={13} />
                                    {t.label}
                                </button>
                            ))}
                        </div>

                        {/* Body */}
                        <div className="px-6 py-5 overflow-y-auto flex-1">

                            {/* ── Instances Tab ─────────────────────────────── */}
                            {activeSection === 'instances' && (
                                <div className="space-y-4">
                                    <div className="flex items-center justify-between p-3 bg-[rgba(0,0,0,0.02)] rounded-xl border border-[rgba(0,0,0,0.06)]">
                                        <div>
                                            <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">Current Mode</p>
                                            <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.75)] mt-0.5 font-mono">
                                                {config.machineType || 'unknown'}
                                            </p>
                                        </div>
                                        <span className={`px-3 py-1 rounded-full text-[11px] font-bold border ${config.isSpotEnabled
                                            ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                                            : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]'}`}>
                                            {config.isSpotEnabled ? '⚡ SPOT' : 'ON-DEMAND'}
                                        </span>
                                    </div>

                                    <div className="p-3 bg-blue-50 border border-blue-200 rounded-xl text-[12px] text-blue-800">
                                        <p className="font-bold mb-1 flex items-center gap-1.5">
                                            <Icon icon="mdi:information-outline" width={13} />
                                            How GCP Spot works (vs AWS)
                                        </p>
                                        <p className="text-blue-700">In GCP a MIG uses a <strong>single instance template</strong> — the entire group is either 100% Spot or 100% On-Demand. Toggle enables/disables Spot for the whole MIG.</p>
                                    </div>

                                    <div>
                                        <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider mb-3">Instance Count (Target Size)</p>
                                        {config.autoscalerEnabled ? (
                                            <div className="p-3 bg-amber-50 rounded-xl border border-amber-100">
                                                <p className="text-[12px] font-bold text-amber-700 mb-1 flex items-center gap-1.5">
                                                    <Icon icon="mdi:robot-outline" width={13} />
                                                    Autoscaler Controls Size
                                                </p>
                                                <p className="text-[12px] text-amber-600">
                                                    Min <strong>{config.autoscalerMinReplicas ?? '?'}</strong> · Max <strong>{config.autoscalerMaxReplicas ?? '?'}</strong> instances
                                                    {config.autoscalerCoolDownPeriodSec ? ` · ${config.autoscalerCoolDownPeriodSec}s cooldown` : ''}
                                                </p>
                                                <p className="text-[11px] text-amber-500 mt-1">Target size is managed automatically — edit autoscaler limits in GCP Console.</p>
                                            </div>
                                        ) : (
                                            <div>
                                                <div className="flex items-center gap-3">
                                                    <input
                                                        type="number" min={0} max={500}
                                                        value={config.targetSize}
                                                        onChange={e => setConfig({ ...config, targetSize: Math.max(0, Number(e.target.value)) })}
                                                        className="w-32 border border-[rgba(0,0,0,0.15)] rounded-lg px-3 py-2 text-[13px] font-mono focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none"
                                                    />
                                                    <div className="flex gap-1">
                                                        {[1,2,3,5,10].map(n => (
                                                            <button key={n} onClick={() => setConfig({ ...config, targetSize: n })}
                                                                className={`px-2.5 py-1.5 rounded-lg text-[12px] font-bold border transition-all ${
                                                                    config.targetSize === n
                                                                        ? 'bg-indigo-600 text-white border-indigo-600'
                                                                        : 'bg-white text-[rgba(0,0,0,0.65)] border-[rgba(0,0,0,0.15)] hover:border-indigo-400'}`}>
                                                                {n}
                                                            </button>
                                                        ))}
                                                    </div>
                                                </div>
                                                <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1.5">Total number of VMs in this MIG</p>
                                            </div>
                                        )}
                                    </div>

                                    <div className="grid grid-cols-3 gap-3">
                                        {[
                                            { label: 'Total', value: mig.instanceCount, cls: 'bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.06)]', vcls: 'text-[rgba(0,0,0,0.88)]', lcls: 'text-[rgba(0,0,0,0.45)]' },
                                            { label: 'Spot', value: mig.spotCount, cls: 'bg-emerald-50 border-emerald-100', vcls: 'text-emerald-700', lcls: 'text-emerald-600' },
                                            { label: 'On-Demand', value: mig.onDemandCount, cls: 'bg-orange-50 border-orange-100', vcls: 'text-orange-700', lcls: 'text-orange-600' },
                                        ].map(c => (
                                            <div key={c.label} className={`p-3 rounded-xl border text-center ${c.cls}`}>
                                                <p className={`text-lg font-bold ${c.vcls}`}>{c.value}</p>
                                                <p className={`text-[10px] uppercase font-bold mt-0.5 ${c.lcls}`}>{c.label}</p>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            )}

                            {/* ── Rolling Update Tab ────────────────────────── */}
                            {activeSection === 'rolling' && (
                                <div className="space-y-4">
                                    <p className="text-[12px] text-[rgba(0,0,0,0.55)]">
                                        Controls how GCP replaces VMs when you toggle Spot. Default settings guarantee zero downtime.
                                    </p>
                                    <div className="grid grid-cols-3 gap-3">
                                        {[
                                            { label: 'Max Surge', key: 'maxSurge' as const, unit: 'VMs', help: 'Extra VMs over target during update' },
                                            { label: 'Max Unavailable', key: 'maxUnavailable' as const, unit: 'VMs', help: '0 = zero downtime guaranteed' },
                                            { label: 'Min Ready', key: 'minReadySeconds' as const, unit: 'sec', help: 'Health wait before removing old VM' },
                                        ].map(f => (
                                            <label key={f.key} className="block">
                                                <span className="text-[12px] text-[rgba(0,0,0,0.55)] font-semibold">{f.label}</span>
                                                <div className="relative mt-1">
                                                    <input type="number" min={0} max={f.key === 'minReadySeconds' ? 300 : 10}
                                                        value={config[f.key]}
                                                        onChange={e => setConfig({ ...config, [f.key]: Number(e.target.value) })}
                                                        className={inputCls}
                                                    />
                                                    <span className="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] text-[rgba(0,0,0,0.35)]">{f.unit}</span>
                                                </div>
                                                <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-1">{f.help}</p>
                                            </label>
                                        ))}
                                    </div>

                                    <div className="p-3 bg-indigo-50 rounded-xl border border-indigo-100 text-[12px] text-indigo-700 font-medium flex items-start gap-2">
                                        <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
                                        {config.maxUnavailable === 0
                                            ? `Zero-downtime: +${config.maxSurge} new VM starts → ${config.minReadySeconds}s health check → old VM removed`
                                            : `Rolling: up to ${config.maxUnavailable} VM${config.maxUnavailable > 1 ? 's' : ''} may be offline during replacement`}
                                    </div>

                                    <div className="p-3 bg-[rgba(0,0,0,0.02)] rounded-xl border border-[rgba(0,0,0,0.06)] text-[12px] text-[rgba(0,0,0,0.65)]">
                                        <p className="font-bold text-[rgba(0,0,0,0.75)] mb-2">Recommended presets</p>
                                        <div className="flex gap-2 flex-wrap">
                                            {[
                                                { label: 'Zero-downtime', surge: 1, unavail: 0, ready: 30 },
                                                { label: 'Fast (brief gap)', surge: 0, unavail: 1, ready: 10 },
                                                { label: 'Aggressive', surge: 2, unavail: 0, ready: 15 },
                                            ].map(p => (
                                                <button key={p.label}
                                                    onClick={() => setConfig({ ...config, maxSurge: p.surge, maxUnavailable: p.unavail, minReadySeconds: p.ready })}
                                                    className="px-3 py-1.5 bg-white border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] font-semibold hover:border-indigo-400 hover:text-indigo-600 transition-all">
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
                                    <div className="p-3 bg-blue-50 border border-blue-200 rounded-xl text-[12px] text-blue-800">
                                        <p className="font-bold mb-1 flex items-center gap-1.5">
                                            <Icon icon="mdi:information-outline" width={13} />
                                            Current Machine Type
                                        </p>
                                        <p className="font-mono text-blue-900 text-[13px]">{config.machineType || 'unknown'}</p>
                                        <p className="text-blue-600 mt-1">To change machine type, create a new instance template in GCP Console and apply it to this MIG.</p>
                                    </div>

                                    <div className="p-3 bg-amber-50 border border-amber-100 rounded-xl text-[12px] text-amber-800">
                                        <p className="font-bold mb-1 flex items-center gap-1.5">
                                            <Icon icon="mdi:lightbulb-outline" width={13} />
                                            Spot Diversification Tip
                                        </p>
                                        <p>For Spot availability, use <strong>E2 or N2D</strong> families — they have the best Spot availability rates on GCP.</p>
                                    </div>

                                    <div className="space-y-3">
                                        {Object.entries(GCP_MACHINE_FAMILIES).map(([family, types]) => (
                                            <div key={family} className="border border-[rgba(0,0,0,0.08)] rounded-xl p-3 bg-[rgba(0,0,0,0.02)]">
                                                <h4 className="text-[12px] font-bold text-[rgba(0,0,0,0.75)] mb-2 flex items-center gap-1.5">
                                                    <Icon icon="mdi:chip" width={13} color="#6366F1" />
                                                    {family}
                                                </h4>
                                                <div className="flex flex-wrap gap-1.5">
                                                    {types.map(t => (
                                                        <span key={t}
                                                            className={`px-2 py-1 rounded-lg text-[11px] font-mono border transition-all ${
                                                                config.machineType === t
                                                                    ? 'bg-indigo-600 text-white border-indigo-600 font-bold'
                                                                    : 'bg-white text-[rgba(0,0,0,0.65)] border-[rgba(0,0,0,0.12)]'}`}>
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
                                <div className="mt-4 p-3 bg-red-50 border border-red-200 rounded-xl text-[12px] text-red-700 font-medium flex items-center gap-2">
                                    <Icon icon="mdi:alert-circle-outline" width={14} />
                                    {error}
                                </div>
                            )}
                            {saveSuccess && (
                                <div className="mt-4 p-3 bg-emerald-50 border border-emerald-200 rounded-xl text-[12px] text-emerald-700 font-bold flex items-center gap-2">
                                    <Icon icon="mdi:check-circle-outline" width={14} />
                                    Configuration saved successfully!
                                </div>
                            )}
                        </div>

                        {/* Footer */}
                        <div className="px-6 py-4 border-t border-[rgba(0,0,0,0.08)] flex justify-end gap-3 bg-[rgba(0,0,0,0.01)] shrink-0">
                            <button onClick={onClose}
                                className="px-4 py-2 text-[13px] font-semibold text-[rgba(0,0,0,0.65)] hover:text-[rgba(0,0,0,0.88)] transition-colors">
                                Cancel
                            </button>
                            <button onClick={handleSave}
                                disabled={saving || saveSuccess || activeSection === 'types'}
                                title={activeSection === 'types' ? 'Machine type changes require GCP Console' : ''}
                                className="flex items-center gap-2 px-5 py-2 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white text-[13px] font-bold rounded-xl transition-all">
                                {saving
                                    ? <><Icon icon="mdi:loading" width={14} className="animate-spin" /> Saving…</>
                                    : 'Save Configuration'}
                            </button>
                        </div>
                    </>
                ) : (
                    <div className="px-6 py-8 text-center text-[13px] text-[rgba(0,0,0,0.45)]">{error || 'Could not load config'}</div>
                )}
            </div>
        </div>
    );
};

// ─── Analytics Tab ────────────────────────────────────────────────────────────

const NUM_STYLE: React.CSSProperties = {
    fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
    fontSize: '28px',
    lineHeight: 1,
    letterSpacing: '0.02em',
};

const CLOUD_SVG = (
    <svg viewBox="0 0 24 24" fill="currentColor" className="absolute bottom-3 right-3 w-12 h-12 text-current opacity-[0.07]">
        <path d="M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z" />
    </svg>
);

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

    const replacements = events.filter(e => e.event_type === 'instance_replacement');
    const interruptions = events.filter(e => e.event_type === 'spot_interruption');
    const failures = events.filter(e => e.event_type === 'launch_failure');
    const successRate = (replacements.length + failures.length) > 0
        ? Math.round((replacements.length / (replacements.length + failures.length)) * 100)
        : 0;

    const byMig: Record<string, { count: number; savings: number }> = {};
    replacements.forEach(e => {
        const key = e.asg_name || e.asgName || 'unknown';
        if (!byMig[key]) byMig[key] = { count: 0, savings: 0 };
        byMig[key].count += 1;
        byMig[key].savings += e.estimated_savings || e.estimatedSavings || 0;
    });

    const kpiCards = [
        { label: 'Spot Replacements', value: replacements.length, icon: 'mdi:swap-horizontal', color: 'emerald' },
        { label: 'Interruptions',     value: interruptions.length, icon: 'mdi:lightning-bolt', color: 'amber' },
        { label: 'Launch Failures',   value: failures.length, icon: 'mdi:alert-outline', color: 'red' },
        { label: 'Success Rate',      value: `${successRate}%`, icon: 'mdi:check-circle-outline', color: 'indigo' },
    ] as const;

    const colorMap: Record<string, { bg: string; border: string; icon: string; text: string }> = {
        emerald: { bg: 'bg-emerald-50', border: 'border-emerald-100', icon: '#059669', text: 'text-emerald-700' },
        amber:   { bg: 'bg-amber-50',   border: 'border-amber-100',   icon: '#D97706', text: 'text-amber-700' },
        red:     { bg: 'bg-red-50',     border: 'border-red-100',     icon: '#DC2626', text: 'text-red-700' },
        indigo:  { bg: 'bg-indigo-50',  border: 'border-indigo-100',  icon: '#4F46E5', text: 'text-indigo-700' },
    };

    const HEAD = 'px-5 py-3 font-semibold text-[11px] uppercase tracking-[0.07em] text-[rgba(0,0,0,0.45)]';
    const COL  = 'px-5 py-3 text-[13px]';

    return (
        <div className="space-y-5">
            <div className="flex items-center justify-between">
                <h3 className="text-[15px] font-bold text-[rgba(0,0,0,0.88)]">Spot Launch Analytics</h3>
                <div className="flex gap-1.5 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)] rounded-xl p-1">
                    {(['last7d', 'last30d'] as const).map(r => (
                        <button key={r} onClick={() => setRange(r)}
                            className={`px-3 py-1.5 text-[12px] font-semibold rounded-lg transition-all ${
                                range === r
                                    ? 'bg-white shadow-sm border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.88)]'
                                    : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
                            {r === 'last7d' ? 'Last 7 days' : 'Last 30 days'}
                        </button>
                    ))}
                </div>
            </div>

            {loading ? (
                <div className="flex items-center justify-center py-16 bg-white border border-[rgba(0,0,0,0.08)] rounded-xl">
                    <Icon icon="mdi:loading" width={28} className="animate-spin text-indigo-500" />
                </div>
            ) : (
                <>
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                        {kpiCards.map(card => {
                            const c = colorMap[card.color];
                            return (
                                <div key={card.label} className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-4 shadow-sm relative overflow-hidden">
                                    {CLOUD_SVG}
                                    <div className={`w-8 h-8 rounded-lg ${c.bg} border ${c.border} flex items-center justify-center mb-3`}>
                                        <Icon icon={card.icon} width={15} color={c.icon} />
                                    </div>
                                    <p style={NUM_STYLE} className="text-[rgba(0,0,0,0.88)]">{card.value}</p>
                                    <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-1 font-semibold">{card.label}</p>
                                </div>
                            );
                        })}
                    </div>

                    {summary && summary.totalEstimatedSavings > 0 && (
                        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-5 shadow-sm flex items-center justify-between">
                            <div>
                                <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">
                                    Total Spot Savings ({range === 'last7d' ? '7d' : '30d'})
                                </p>
                                <p style={NUM_STYLE} className="text-emerald-600 mt-1">
                                    ${summary.totalEstimatedSavings.toFixed(2)}
                                </p>
                            </div>
                            <div className="w-12 h-12 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center">
                                <Icon icon="mdi:piggy-bank-outline" width={22} color="#059669" />
                            </div>
                        </div>
                    )}

                    {Object.keys(byMig).length > 0 && (
                        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)]">
                                <h4 className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">Replacements by MIG</h4>
                            </div>
                            <table className="w-full text-left">
                                <thead>
                                    <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                                        <th className={HEAD}>MIG Name</th>
                                        <th className={`${HEAD} text-center`}>Replacements</th>
                                        <th className={`${HEAD} text-right`}>Savings</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                                    {Object.entries(byMig).map(([name, stats]) => (
                                        <tr key={name} className="hover:bg-[rgba(0,0,0,0.01)] transition-colors">
                                            <td className={`${COL} font-semibold text-[rgba(0,0,0,0.88)] font-mono`}>{name}</td>
                                            <td className={`${COL} text-center`}>
                                                <span className="inline-flex items-center px-2.5 py-0.5 bg-indigo-50 text-indigo-700 rounded-full text-[11px] font-bold border border-indigo-100">
                                                    {stats.count}
                                                </span>
                                            </td>
                                            <td className={`${COL} text-right font-bold text-emerald-600`}>
                                                ${stats.savings.toFixed(4)}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}

                    {interruptions.length > 0 && (
                        <div className="bg-amber-50 border border-amber-200 rounded-xl p-4">
                            <p className="text-[12px] font-bold text-amber-700 uppercase tracking-wider mb-2 flex items-center gap-1.5">
                                <Icon icon="mdi:lightning-bolt" width={13} />
                                Spot Interruption Pattern
                            </p>
                            <p className="text-[13px] text-amber-700">
                                {interruptions.length} interruption{interruptions.length > 1 ? 's' : ''} detected.
                                GCP automatically replaces preempted VMs via the MIG autoscaler.
                            </p>
                        </div>
                    )}

                    {events.length === 0 && (
                        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-12 text-center shadow-sm">
                            <div className="w-14 h-14 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.06)] rounded-xl flex items-center justify-center mx-auto mb-4">
                                <Icon icon="mdi:chart-pie-outline" width={24} color="rgba(0,0,0,0.2)" />
                            </div>
                            <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.55)]">No events in this period</p>
                            <p className="text-[12px] text-[rgba(0,0,0,0.35)] mt-1">Enable AutoSpotting on a MIG to start seeing data here.</p>
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

    const [toast, setToast] = useState<{ msg: string; type: 'success' | 'error' | 'info' } | null>(null);
    const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

    const [liveChanges, setLiveChanges] = useState<LiveChangeEntry[]>([]);
    const [pollingMig, setPollingMig] = useState<string | null>(null);
    const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const prevMigStates = useRef<Map<string, boolean>>(new Map());

    const { setActions, clearActions } = useTopbar();
    useEffect(() => {
        setActions(
            <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-amber-400" />
                <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.65)]">GCP AutoSpotting</span>
                <span className="text-[rgba(0,0,0,0.2)]">|</span>
                <button
                    onClick={refresh}
                    className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.04)] rounded-lg transition-colors"
                >
                    <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
                        <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
                    </svg>
                    Refresh
                </button>
            </div>
        );
        return () => clearActions();
    }, [refresh]);

    const showToast = useCallback((msg: string, type: 'success' | 'error' | 'info' = 'success') => {
        if (toastTimer.current) clearTimeout(toastTimer.current);
        setToast({ msg, type });
        toastTimer.current = setTimeout(() => setToast(null), 4000);
    }, []);

    const snapshotStates = useCallback((currentMigs: GcpMIG[]) => {
        const map = new Map<string, boolean>();
        currentMigs.forEach(m => map.set(`${m.name}:${m.region}`, m.isEnabled));
        prevMigStates.current = map;
    }, []);

    const detectChanges = useCallback((updatedMigs: GcpMIG[]) => {
        const changes: LiveChangeEntry[] = [];
        updatedMigs.forEach(m => {
            const key = `${m.name}:${m.region}`;
            const prev = prevMigStates.current.get(key);
            if (prev !== undefined && prev !== m.isEnabled) {
                changes.push({ migName: m.name, region: m.region, oldState: prev, newState: m.isEnabled, detectedAt: new Date().toLocaleTimeString() });
            }
        });
        if (changes.length > 0) setLiveChanges(prev => [...changes, ...prev].slice(0, 10));
    }, []);

    const startPolling = useCallback((migKey: string) => {
        if (pollingRef.current) clearInterval(pollingRef.current);
        setPollingMig(migKey);
        let attempts = 0;
        pollingRef.current = setInterval(async () => {
            attempts++;
            try { await refresh(); } catch (_) {}
            if (attempts >= 8) {
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

    useEffect(() => {
        if (migs.length > 0) detectChanges(migs);
    }, [migs, detectChanges]);

    const handleToggle = async (mig: GcpMIG) => {
        snapshotStates(migs);
        const nextState = !mig.isEnabled;
        try {
            await toggleMIG(mig);
            showToast(`AutoSpotting ${nextState ? 'enabled' : 'disabled'} for ${mig.name}. Rolling update started.`, 'success');
            startPolling(`${mig.name}:${mig.region}`);
        } catch (err: any) {
            showToast(err?.message || 'Toggle failed', 'error');
        }
    };

    const enabledMIGsCount = migs.filter(m => m.isEnabled).length;

    // ── Guards ────────────────────────────────────────────────────────────────

    if (accountsLoading) {
        return (
            <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
                <div className="grid grid-cols-4 gap-4">
                    {[...Array(4)].map((_, i) => (
                        <div key={i} className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl h-28 animate-pulse" />
                    ))}
                </div>
                <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl h-64 animate-pulse" />
            </main>
        );
    }

    if (!selectedAccountId) {
        return (
            <main className="flex-1 px-6 pt-4 pb-6 flex items-center justify-center min-h-[60vh]">
                <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-10 max-w-md text-center shadow-sm">
                    <div className="w-14 h-14 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.06)] rounded-xl flex items-center justify-center mx-auto mb-5">
                        <Icon icon="mdi:google-cloud" width={26} color="rgba(0,0,0,0.25)" />
                    </div>
                    <h2 className="text-[17px] font-bold text-[rgba(0,0,0,0.88)] mb-2">No Account Selected</h2>
                    <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Select a GCP account from the account selector.</p>
                </div>
            </main>
        );
    }

    if (!isGcp) {
        return (
            <main className="flex-1 px-6 pt-4 pb-6 flex items-center justify-center min-h-[60vh]">
                <div className="bg-white border border-amber-100 rounded-xl p-10 max-w-md text-center shadow-sm">
                    <div className="w-14 h-14 bg-amber-50 border border-amber-100 rounded-xl flex items-center justify-center mx-auto mb-5">
                        <Icon icon="mdi:alert-outline" width={26} color="#D97706" />
                    </div>
                    <h2 className="text-[17px] font-bold text-[rgba(0,0,0,0.88)] mb-2">GCP Account Required</h2>
                    <p className="text-[13px] text-[rgba(0,0,0,0.55)]">Select a GCP account to use Spot Automation.</p>
                </div>
            </main>
        );
    }

    const HEAD = 'px-5 py-3 font-semibold text-[11px] uppercase tracking-[0.07em] text-[rgba(0,0,0,0.45)]';
    const COL  = 'px-5 py-4 text-[13px]';

    // ── Render ─────────────────────────────────────────────────────────────────

    return (
        <div className="flex-1 flex flex-col relative">

            {configMig && (
                <ConfigModal
                    mig={configMig}
                    accountId={selectedAccountId}
                    onClose={() => setConfigMig(null)}
                    onSaved={() => { refresh(); showToast('Config saved', 'success'); }}
                />
            )}

            {toast && (
                <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5 rounded-2xl shadow-xl text-[13px] font-semibold transition-all ${
                    toast.type === 'success' ? 'bg-emerald-600 text-white'
                    : toast.type === 'error' ? 'bg-red-600 text-white'
                    : 'bg-[rgba(0,0,0,0.85)] text-white'}`}>
                    <Icon icon={
                        toast.type === 'success' ? 'mdi:check-circle-outline'
                        : toast.type === 'error' ? 'mdi:alert-circle-outline'
                        : 'mdi:information-outline'
                    } width={16} />
                    {toast.msg}
                    <button onClick={() => setToast(null)} className="ml-2 opacity-70 hover:opacity-100">
                        <Icon icon="mdi:close" width={14} />
                    </button>
                </div>
            )}

            {/* Tab Navigation */}
            <div className="bg-white border-b border-[rgba(0,0,0,0.08)] px-6 sticky top-0 z-30">
                <nav className="flex space-x-6">
                    {([
                        { id: 'dashboard', label: 'Dashboard',        icon: 'mdi:view-dashboard-outline' },
                        { id: 'analytics', label: 'Launch Analytics', icon: 'mdi:chart-line' },
                        { id: 'history',   label: 'Actions History',  icon: 'mdi:history' },
                    ] as const).map(tab => (
                        <button key={tab.id} onClick={() => setActiveTab(tab.id)}
                            className={`py-4 px-1 border-b-2 font-semibold text-[13px] transition-all flex items-center gap-2 ${
                                activeTab === tab.id
                                    ? 'border-indigo-600 text-indigo-600'
                                    : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)] hover:border-[rgba(0,0,0,0.15)]'}`}>
                            <Icon icon={tab.icon} width={14} />
                            {tab.label}
                        </button>
                    ))}
                </nav>
            </div>

            {/* Main Content */}
            <main className="flex-1 px-6 pt-4 pb-6">
            <AnimatePresence mode="wait">

                {/* ── Dashboard Tab ──────────────────────────────────────────── */}
                {activeTab === 'dashboard' && (
                    <motion.div key="dashboard" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }} className="space-y-5">
                        <SpotAutomationKPIs
                            costs={costs}
                            savings={savings}
                            enabledASGsCount={enabledMIGsCount}
                            totalASGsCount={migs.length}
                            enabledRegionsCount={enabledLocationsCount}
                        />

                        <div className="flex items-center justify-between text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)] px-1">
                            <span>
                                Total Instances: <span className="text-[rgba(0,0,0,0.75)]">{totalInstances}</span>
                                {pollingMig && (
                                    <span className="ml-3 text-indigo-500 normal-case">
                                        <Icon icon="mdi:loading" width={12} className="animate-spin inline mr-1" />
                                        Checking MIG state…
                                    </span>
                                )}
                            </span>
                            {error && (
                                <span className="text-red-600 flex items-center gap-1">
                                    <Icon icon="mdi:alert-circle-outline" width={12} />
                                    {(error as any).message}
                                </span>
                            )}
                        </div>

                        {liveChanges.length > 0 && (
                            <div className="bg-white border border-indigo-100 rounded-xl p-4 shadow-sm">
                                <div className="flex items-center justify-between mb-3">
                                    <p className="text-[11px] font-semibold text-indigo-700 uppercase tracking-wider flex items-center gap-1.5">
                                        <Icon icon="mdi:lightning-bolt" width={12} />
                                        Live Changes Detected
                                    </p>
                                    <button onClick={() => setLiveChanges([])} className="text-[11px] text-[rgba(0,0,0,0.35)] hover:text-[rgba(0,0,0,0.65)]">
                                        Clear
                                    </button>
                                </div>
                                <div className="space-y-1.5">
                                    {liveChanges.slice(0, 3).map((c, i) => (
                                        <div key={i} className="flex items-center gap-2 text-[12px] text-[rgba(0,0,0,0.65)]">
                                            <span className="font-mono font-semibold">{c.migName}</span>
                                            <span className={`px-2 py-0.5 rounded-full font-bold text-[10px] ${c.oldState ? 'bg-indigo-50 text-indigo-600 border border-indigo-100' : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)]'}`}>
                                                {c.oldState ? 'SPOT' : 'OD'}
                                            </span>
                                            <Icon icon="mdi:arrow-right" width={11} color="rgba(0,0,0,0.25)" />
                                            <span className={`px-2 py-0.5 rounded-full font-bold text-[10px] ${c.newState ? 'bg-emerald-50 text-emerald-700 border border-emerald-100' : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)]'}`}>
                                                {c.newState ? 'SPOT' : 'OD'}
                                            </span>
                                            <span className="text-[rgba(0,0,0,0.35)] ml-1">{c.detectedAt}</span>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        )}

                        <SpotAutomationChart
                            historyData={historyData}
                            historySummary={historySummary}
                            timeRange="last7d"
                            isLoading={isLoadingHistory}
                            onTimeRangeChange={(range) => fetchHistoryData(range)}
                            onRefresh={() => fetchHistoryData()}
                        />

                        {/* MIGs Table */}
                        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
                            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between bg-[rgba(0,0,0,0.01)]">
                                <div>
                                    <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Managed Instance Groups</h3>
                                    <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">Configure AutoSpotting for your GCP MIGs — zero-downtime rolling updates</p>
                                </div>
                                <button onClick={refresh} title="Refresh MIG list"
                                    className="p-2 hover:bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)] rounded-lg transition-colors border border-[rgba(0,0,0,0.08)]">
                                    <Icon icon={isLoading ? 'mdi:loading' : 'mdi:refresh'} width={15} className={isLoading ? 'animate-spin' : ''} />
                                </button>
                            </div>

                            <div className="overflow-x-auto">
                                <table className="w-full text-left">
                                    <thead>
                                        <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                                            <th className={HEAD}>Instance Group</th>
                                            <th className={HEAD}>Region / Zone</th>
                                            <th className={HEAD}>Status</th>
                                            <th className={`${HEAD} text-center`}>Instances</th>
                                            <th className={`${HEAD} text-center`}>Spot / OD</th>
                                            <th className={`${HEAD} text-right`}>Actual Savings/hr</th>
                                            <th className={`${HEAD} text-right`}>Potential Savings/hr</th>
                                            <th className={`${HEAD} text-center`}>AutoSpotting</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                                        {migs.length > 0 ? migs.map(mig => {
                                            const migKey = `${mig.name}:${mig.region}`;
                                            const isPollingThis = pollingMig === migKey;

                                            return (
                                                <tr key={migKey} className={`hover:bg-[rgba(0,0,0,0.01)] transition-colors group ${isPollingThis ? 'bg-indigo-50/20' : ''}`}>
                                                    <td className={COL}>
                                                        <div className="flex items-center gap-3">
                                                            <div className="w-8 h-8 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
                                                                <Icon icon="mdi:layers-outline" width={15} color="#4F46E5" />
                                                            </div>
                                                            <div>
                                                                <div className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] group-hover:text-indigo-600 transition-colors">
                                                                    {mig.name}
                                                                </div>
                                                                <div className="text-[10px] text-[rgba(0,0,0,0.35)] font-mono mt-0.5">GOOGLE CLOUD MIG</div>
                                                            </div>
                                                        </div>
                                                    </td>

                                                    <td className={COL}>
                                                        <span className="flex items-center gap-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] bg-blue-50 border border-blue-100 px-2.5 py-1 rounded-full w-fit">
                                                            <Icon icon="mdi:map-marker-outline" width={11} color="#3B82F6" />
                                                            {mig.region}
                                                        </span>
                                                    </td>

                                                    <td className={COL}>
                                                        {mig.isEnabled ? (
                                                            <span className="flex items-center gap-1.5 text-[12px] font-bold text-emerald-600">
                                                                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
                                                                {isPollingThis ? 'Converting…' : 'Active (Spot)'}
                                                            </span>
                                                        ) : (
                                                            <span className="flex items-center gap-1.5 text-[12px] font-bold text-[rgba(0,0,0,0.35)]">
                                                                <span className="w-1.5 h-1.5 rounded-full bg-[rgba(0,0,0,0.2)]" />
                                                                {isPollingThis ? 'Reverting…' : 'On-Demand'}
                                                            </span>
                                                        )}
                                                    </td>

                                                    <td className={`${COL} text-center`}>
                                                        <span className="flex items-center justify-center gap-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] bg-[rgba(0,0,0,0.04)] px-2.5 py-1 rounded-full w-fit mx-auto">
                                                            <Icon icon="mdi:server-outline" width={11} color="rgba(0,0,0,0.35)" />
                                                            {mig.instanceCount}
                                                        </span>
                                                    </td>

                                                    <td className={`${COL} text-center`}>
                                                        <div className="inline-flex items-center gap-1 text-[12px] font-bold">
                                                            <span className="text-emerald-600">{mig.spotCount}</span>
                                                            <span className="text-[rgba(0,0,0,0.2)]">/</span>
                                                            <span className="text-orange-500">{mig.onDemandCount}</span>
                                                        </div>
                                                        <div className="text-[9px] text-[rgba(0,0,0,0.35)] mt-0.5 font-semibold">SPOT / OD</div>
                                                    </td>

                                                    <td className={`${COL} text-right`}>
                                                        {mig.savings > 0
                                                            ? <span className="text-[13px] font-bold text-emerald-600">${mig.savings.toFixed(4)}</span>
                                                            : <span className="text-[rgba(0,0,0,0.2)]">—</span>}
                                                    </td>

                                                    <td className={`${COL} text-right`}>
                                                        <span className="text-[13px] font-bold text-indigo-600">+${mig.potentialSavings.toFixed(4)}</span>
                                                    </td>

                                                    <td className={`${COL}`}>
                                                        <div className="flex flex-col items-center gap-2">
                                                            <button
                                                                onClick={() => handleToggle(mig)}
                                                                disabled={mig.updating}
                                                                title={mig.isEnabled ? 'Disable AutoSpotting' : 'Enable AutoSpotting'}
                                                                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2
                                                                    ${mig.isEnabled ? 'bg-indigo-600' : 'bg-[rgba(0,0,0,0.12)]'}
                                                                    ${mig.updating ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
                                                            >
                                                                <span className="sr-only">Toggle AutoSpotting</span>
                                                                <span className={`${mig.isEnabled ? 'translate-x-6' : 'translate-x-1'} inline-flex h-4 w-4 transform rounded-full bg-white transition-transform ease-in-out duration-200 items-center justify-center`}>
                                                                    {mig.updating && <Icon icon="mdi:loading" width={8} className="animate-spin text-indigo-600" />}
                                                                </span>
                                                            </button>

                                                            <button onClick={() => setConfigMig(mig)}
                                                                className="text-[10px] text-indigo-500 hover:text-indigo-700 flex items-center gap-1 px-2 py-0.5 rounded hover:bg-indigo-50 transition-colors font-bold uppercase tracking-wide">
                                                                <Icon icon="mdi:cog-outline" width={10} />
                                                                Config
                                                            </button>
                                                        </div>
                                                    </td>
                                                </tr>
                                            );
                                        }) : (
                                            <tr>
                                                <td colSpan={8} className="px-6 py-12 text-center">
                                                    {isLoading ? (
                                                        <div className="flex items-center justify-center gap-2 text-[13px] text-[rgba(0,0,0,0.45)]">
                                                            <Icon icon="mdi:loading" width={16} className="animate-spin text-indigo-500" />
                                                            Scanning for Managed Instance Groups…
                                                        </div>
                                                    ) : (
                                                        <div className="flex flex-col items-center gap-2">
                                                            <Icon icon="mdi:alert-outline" width={22} color="rgba(0,0,0,0.2)" />
                                                            <p className="text-[13px] text-[rgba(0,0,0,0.45)]">No Managed Instance Groups found in this project.</p>
                                                        </div>
                                                    )}
                                                </td>
                                            </tr>
                                        )}
                                    </tbody>
                                </table>
                            </div>

                            {migs.length > 0 && (
                                <div className="px-5 py-3 bg-[rgba(0,0,0,0.01)] border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between text-[11px] text-[rgba(0,0,0,0.45)] font-semibold">
                                    <span>
                                        {migs.length} MIG{migs.length !== 1 ? 's' : ''} · {totalInstances} instance{totalInstances !== 1 ? 's' : ''} ·{' '}
                                        <span className="text-emerald-600 font-bold">{enabledMIGsCount} spot-enabled</span>
                                    </span>
                                    <div className="flex items-center gap-4">
                                        <span className="flex items-center gap-1.5">
                                            <span className="w-2 h-2 rounded-full bg-emerald-500" />Spot
                                        </span>
                                        <span className="flex items-center gap-1.5">
                                            <span className="w-2 h-2 rounded-full bg-orange-400" />On-Demand
                                        </span>
                                    </div>
                                </div>
                            )}
                        </div>
                    </motion.div>
                )}

                {activeTab === 'analytics' && (
                    <motion.div key="analytics" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
                        <AnalyticsTab accountId={selectedAccountId} />
                    </motion.div>
                )}

                {activeTab === 'history' && (
                    <motion.div key="history" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
                        <ActionsHistory accountId={selectedAccountId} provider="GCP" />
                    </motion.div>
                )}

            </AnimatePresence>
            </main>
        </div>
    );
};

export default GcpSpotAutomationPage;
