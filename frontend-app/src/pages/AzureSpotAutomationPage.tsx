import React, { useState, useEffect } from 'react';
import { gooeyToast } from 'goey-toast';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureSpotAutomationData, AzureVMSS } from '../hooks/useAzureSpotAutomationData';
import { DashboardSkeleton } from '../components/Shared/Skeleton';
import SpotAutomationKPIs from '../components/SpotAutomation/SpotAutomationKPIs';
import SpotAutomationChart from '../components/SpotAutomation/SpotAutomationChart';
import ActionsHistory from '../components/ActionsHistory/ActionsHistory';
import { AriaSlider, AriaTabGroup, DialogRoot, DialogPanel } from '../components/ui';
import { useTabState } from '../hooks/useTabState';

type Tab = 'dashboard' | 'history' | 'analytics' | 'settings';

// ─── Instance Family Definitions ────────────────────────────────────────────────

const INSTANCE_FAMILIES: { label: string; key: string; skus: string[] }[] = [
    { label: 'D v5',  key: 'Dv5',  skus: ['Standard_D2s_v5', 'Standard_D4s_v5', 'Standard_D8s_v5'] },
    { label: 'D v3',  key: 'Dv3',  skus: ['Standard_D2s_v3', 'Standard_D4s_v3', 'Standard_D8s_v3'] },
    { label: 'E v5',  key: 'Ev5',  skus: ['Standard_E2s_v5', 'Standard_E4s_v5', 'Standard_E8s_v5'] },
    { label: 'E v3',  key: 'Ev3',  skus: ['Standard_E2s_v3', 'Standard_E4s_v3', 'Standard_E8s_v3'] },
    { label: 'F v2',  key: 'Fv2',  skus: ['Standard_F2s_v2', 'Standard_F4s_v2', 'Standard_F8s_v2'] },
    { label: 'B',     key: 'B',    skus: ['Standard_B1s', 'Standard_B2s', 'Standard_B4ms'] },
    { label: 'NC',    key: 'NC',   skus: ['Standard_NC6', 'Standard_NC12', 'Standard_NC24'] },
];

/** Given a comma-separated SKU string, return which family keys are fully or partially represented */
function skusToFamilyKeys(skuStr: string): Set<string> {
    const skus = skuStr.split(',').map(s => s.trim()).filter(Boolean);
    const active = new Set<string>();
    for (const fam of INSTANCE_FAMILIES) {
        if (fam.skus.some(s => skus.includes(s))) active.add(fam.key);
    }
    return active;
}

/** Toggle a family in/out of the SKU string */
function toggleFamily(skuStr: string, family: typeof INSTANCE_FAMILIES[number], add: boolean): string {
    const current = skuStr.split(',').map(s => s.trim()).filter(Boolean);
    if (add) {
        const merged = [...new Set([...current, ...family.skus])];
        return merged.join(',');
    } else {
        const filtered = current.filter(s => !family.skus.includes(s));
        return filtered.join(',');
    }
}

// ─── VMSS Config Modal ──────────────────────────────────────────────────────────

interface VmssConfigModalProps {
    vmss: AzureVMSS;
    subscriptionId: string;
    onClose: () => void;
    getVmssSpotConfig: (rg: string, name: string) => Promise<any>;
    saveVmssSpotConfig: (rg: string, name: string, pct: number | null, od: number | null) => Promise<any>;
    getVmssSkuConfig: (rg: string, name: string) => Promise<any>;
    saveVmssSkuConfig: (rg: string, name: string, skus: string, strategy: string) => Promise<any>;
}

const VmssConfigModal: React.FC<VmssConfigModalProps> = ({
    vmss, onClose,
    getVmssSpotConfig, saveVmssSpotConfig,
    getVmssSkuConfig, saveVmssSkuConfig,
}) => {
    const [spotPct, setSpotPct] = useState<string>('');
    const [odCount, setOdCount] = useState<string>('');
    const [allowedSkus, setAllowedSkus] = useState('');
    const [strategy, setStrategy] = useState('mixed');
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);

    useEffect(() => {
        (async () => {
            try {
                const [spotCfg, skuCfg] = await Promise.all([
                    getVmssSpotConfig(vmss.resourceGroup, vmss.name),
                    getVmssSkuConfig(vmss.resourceGroup, vmss.name),
                ]);
                if (spotCfg?.spot_percentage != null) setSpotPct(String(spotCfg.spot_percentage));
                if (spotCfg?.on_demand_count != null) setOdCount(String(spotCfg.on_demand_count));
                if (skuCfg?.allowed_vm_skus) setAllowedSkus(skuCfg.allowed_vm_skus);
                if (skuCfg?.spot_allocation_strategy) setStrategy(skuCfg.spot_allocation_strategy);
            } catch { /* use defaults */ }
        })();
    }, [vmss.name, vmss.resourceGroup, getVmssSpotConfig, getVmssSkuConfig]);

    const handleSave = async () => {
        setSaving(true);
        try {
            await Promise.all([
                saveVmssSpotConfig(
                    vmss.resourceGroup, vmss.name,
                    spotPct !== '' ? parseInt(spotPct, 10) : null,
                    odCount !== '' ? parseInt(odCount, 10) : null
                ),
                saveVmssSkuConfig(vmss.resourceGroup, vmss.name, allowedSkus, strategy),
            ]);
            setSaved(true);
            gooeyToast.success(`VMSS config saved for ${vmss.name}`);
            setTimeout(() => { setSaved(false); onClose(); }, 900);
        } catch (err) {
            gooeyToast.error('Failed to save VMSS config', {
                description: err instanceof Error ? err.message : 'Unknown error',
            });
        } finally {
            setSaving(false);
        }
    };

    return (
        <DialogRoot isOpen={true} onClose={onClose} maxWidth="max-w-lg">
          <DialogPanel
            title={
              <div>
                <div className="text-base font-black uppercase tracking-tight">{vmss.name}</div>
                <div className="text-sm text-gray-500 mt-0.5">VMSS Spot Configuration</div>
              </div>
            }
            onClose={onClose}
            footer={
              <>
                <button onClick={onClose} className="px-4 py-2 text-sm text-slate-600 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors font-semibold">Cancel</button>
                <button onClick={handleSave} disabled={saving} className="px-4 py-2 text-sm text-white bg-blue-600 hover:bg-blue-700 rounded-lg transition-colors font-semibold flex items-center gap-2 disabled:opacity-60">
                  {saving ? <i className="fas fa-circle-notch fa-spin"></i> : saved ? <i className="fas fa-check"></i> : null}
                  {saved ? 'Saved!' : 'Save Config'}
                </button>
              </>
            }
          >
                <div className="space-y-5">
                    {/* Spot Ratio */}
                    <div>
                        <h4 className="text-xs font-black uppercase tracking-widest text-slate-500 mb-3">
                            Spot Ratio
                        </h4>
                        <div className="grid grid-cols-2 gap-3">
                            <div>
                                <label className="text-xs text-slate-600 font-semibold mb-1 block">
                                    Spot % (0–100)
                                </label>
                                <input
                                    type="number" min="0" max="100"
                                    value={spotPct}
                                    onChange={e => setSpotPct(e.target.value)}
                                    placeholder="e.g. 80"
                                    className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                                />
                                <p className="text-[10px] text-slate-400 mt-1">% of instances to convert to Spot</p>
                            </div>
                            <div>
                                <label className="text-xs text-slate-600 font-semibold mb-1 block">
                                    Min Regular VMs
                                </label>
                                <input
                                    type="number" min="0"
                                    value={odCount}
                                    onChange={e => setOdCount(e.target.value)}
                                    placeholder="e.g. 1"
                                    className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                                />
                                <p className="text-[10px] text-slate-400 mt-1">Always keep this many Regular VMs</p>
                            </div>
                        </div>
                    </div>

                    {/* VM SKU Diversification */}
                    <div>
                        <h4 className="text-xs font-black uppercase tracking-widest text-slate-500 mb-3">
                            VM SKU Diversification
                        </h4>

                        {/* Instance Family Picker */}
                        <div className="mb-3">
                            <label className="text-xs text-slate-600 font-semibold mb-2 block">
                                Instance Family
                                <span className="ml-1.5 text-[10px] font-normal text-slate-400">
                                    — select fallback families when primary SKU has no Spot capacity
                                </span>
                            </label>
                            <div className="flex flex-wrap gap-2">
                                {INSTANCE_FAMILIES.map(fam => {
                                    const active = skusToFamilyKeys(allowedSkus).has(fam.key);
                                    return (
                                        <button
                                            key={fam.key}
                                            type="button"
                                            onClick={() => setAllowedSkus(toggleFamily(allowedSkus, fam, !active))}
                                            className={`px-3 py-1.5 rounded-full text-xs font-semibold border transition-all ${
                                                active
                                                    ? 'bg-blue-600 text-white border-blue-600 shadow-sm'
                                                    : 'bg-white text-slate-600 border-slate-300 hover:border-blue-400 hover:text-blue-600'
                                            }`}
                                        >
                                            {active && <i className="fas fa-check mr-1 text-[10px]"></i>}
                                            {fam.label}
                                        </button>
                                    );
                                })}
                            </div>
                            {allowedSkus && (
                                <p className="text-[10px] text-blue-600 mt-1.5 font-mono truncate">
                                    {allowedSkus}
                                </p>
                            )}
                        </div>

                        {/* Allocation Strategy */}
                        <div>
                            <label className="text-xs text-slate-600 font-semibold mb-1 block">
                                Allocation Strategy
                            </label>
                            <select
                                value={strategy}
                                onChange={e => setStrategy(e.target.value)}
                                className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                            >
                                <option value="mixed">Mixed (Spot + Regular)</option>
                                <option value="spot-only">Spot Only</option>
                                <option value="capacity-optimized">Capacity Optimized</option>
                            </select>
                        </div>
                    </div>
                </div>
          </DialogPanel>
        </DialogRoot>
    );
};

// ─── Main Page ─────────────────────────────────────────────────────────────────

const AzureSpotAutomationPage: React.FC = () => {
    const { selectedAccountDisplay, isLoading: accountsLoading } = useAccounts();
    const selectedAccountId =
        selectedAccountDisplay.id && selectedAccountDisplay.id !== 'No account selected'
            ? selectedAccountDisplay.id
            : null;
    const isAzure = selectedAccountDisplay.provider === 'Azure';

    const {
        vmssList,
        costs,
        savings,
        enabledRegionsCount,
        totalInstances,
        historyData,
        historySummary,
        isLoading,
        isLoadingHistory,
        error,
        refresh,
        fetchHistoryData,
        toggleVMSS,
        launchAnalytics,
        isLoadingAnalytics,
        fetchLaunchAnalytics,
        settings,
        fetchSettings,
        saveRispSettings,
        getVmssSpotConfig,
        saveVmssSpotConfig,
        getVmssSkuConfig,
        saveVmssSkuConfig,
        syncEvents,
    } = useAzureSpotAutomationData({ selectedAccountId });

    const [activeTab, setActiveTab] = useTabState<Tab>('dashboard');
    const [configVmss, setConfigVmss] = useState<AzureVMSS | null>(null);

    // Settings local state
    const [rispEnabled, setRispEnabled] = useState(false);
    const [rispThreshold, setRispThreshold] = useState(90);
    const [savingSettings, setSavingSettings] = useState(false);
    const [settingsSaved, setSettingsSaved] = useState(false);
    const [syncing, setSyncing] = useState(false);
    const [syncResult, setSyncResult] = useState<string | null>(null);

    // Analytics range
    const [analyticsRange, setAnalyticsRange] = useState<string>('last7d');

    useEffect(() => {
        if (activeTab === 'analytics' && selectedAccountId) {
            fetchLaunchAnalytics(analyticsRange);
        }
    }, [activeTab, analyticsRange, selectedAccountId, fetchLaunchAnalytics]);

    useEffect(() => {
        if (activeTab === 'settings' && selectedAccountId) {
            fetchSettings();
        }
    }, [activeTab, selectedAccountId, fetchSettings]);

    useEffect(() => {
        if (settings) {
            setRispEnabled(settings.respectRiSpCommitments ?? false);
            setRispThreshold(settings.riSpUtilizationThreshold ?? 90);
        }
    }, [settings]);

    if (accountsLoading) {
        return <DashboardSkeleton />;
    }

    if (!selectedAccountId) {
        return (
            <div className="p-8 flex flex-col items-center justify-center min-h-[60vh]">
                <div className="bg-white border border-slate-200 shadow-sm rounded-3xl p-10 max-w-md text-center">
                    <div className="w-16 h-16 bg-slate-50 rounded-2xl flex items-center justify-center text-slate-400 mx-auto mb-6">
                        <i className="fas fa-cloud text-2xl"></i>
                    </div>
                    <h2 className="text-2xl font-bold text-slate-900 mb-2">No Account Selected</h2>
                    <p className="text-slate-500 text-sm">
                        Please select an Azure account from the account selector to view Spot Automation details.
                    </p>
                </div>
            </div>
        );
    }

    if (!isAzure) {
        return (
            <div className="p-8 flex flex-col items-center justify-center min-h-[60vh]">
                <div className="bg-white border border-amber-100 shadow-sm rounded-3xl p-10 max-w-md text-center">
                    <div className="w-16 h-16 bg-amber-50 rounded-2xl flex items-center justify-center text-amber-500 mx-auto mb-6">
                        <i className="fas fa-exclamation-triangle text-2xl"></i>
                    </div>
                    <h2 className="text-2xl font-bold text-slate-900 mb-2">Azure Account Required</h2>
                    <p className="text-slate-600 text-sm">
                        This page is specifically for Microsoft Azure. Please select an Azure account from the account selector to continue.
                    </p>
                </div>
            </div>
        );
    }

    const handleToggle = async (vmss: AzureVMSS) => {
        if (vmss.aksManaged) return; // AKS-managed VMSS cannot be toggled
        try {
            await toggleVMSS(vmss);
            gooeyToast.success(`AutoSpotting ${!vmss.isEnabled ? 'enabled' : 'disabled'} for ${vmss.name}`);
        } catch (err) {
            gooeyToast.error(`Failed to toggle ${vmss.name}`, {
                description: err instanceof Error ? err.message : 'Unknown error',
            });
        }
    };

    const handleTimeRangeChange = (range: 'last24h' | 'last7d' | 'last30d') => {
        fetchHistoryData(range);
    };

    const handleSaveSettings = async () => {
        setSavingSettings(true);
        try {
            await saveRispSettings(rispEnabled, rispThreshold);
            setSettingsSaved(true);
            setTimeout(() => setSettingsSaved(false), 2000);
            gooeyToast.success('Settings saved successfully');
        } catch (err) {
            gooeyToast.error('Failed to save settings', {
                description: err instanceof Error ? err.message : 'Unknown error',
            });
        } finally {
            setSavingSettings(false);
        }
    };

    const handleSync = async () => {
        setSyncing(true);
        setSyncResult(null);
        try {
            const result = await syncEvents();
            const msg = `Sync complete. ${result?.backfilled_count ?? 0} records backfilled across ${result?.total_vmss_scanned ?? 0} VMSS.`;
            setSyncResult(msg);
            gooeyToast.success('Sync complete', { description: msg });
        } catch (err) {
            setSyncResult('Sync failed. Please try again.');
            gooeyToast.error('Sync failed', { description: 'Please try again.' });
        } finally {
            setSyncing(false);
        }
    };

    const enabledVmssCount = vmssList.filter(v => v.isEnabled).length;

    const tabs = [
        { id: 'dashboard',  label: 'Dashboard',        icon: 'fas fa-tachometer-alt' },
        { id: 'history',    label: 'Actions History',   icon: 'fas fa-history' },
        { id: 'analytics',  label: 'Launch Analytics',  icon: 'fas fa-chart-bar' },
        { id: 'settings',   label: 'Settings',          icon: 'fas fa-cog' },
    ];

    return (
        <div className="flex-1 flex flex-col min-h-screen bg-slate-50 relative">
            {/* Config Modal */}
            {configVmss && selectedAccountId && (
                <VmssConfigModal
                    vmss={configVmss}
                    subscriptionId={selectedAccountId}
                    onClose={() => setConfigVmss(null)}
                    getVmssSpotConfig={getVmssSpotConfig}
                    saveVmssSpotConfig={saveVmssSpotConfig}
                    getVmssSkuConfig={getVmssSkuConfig}
                    saveVmssSkuConfig={saveVmssSkuConfig}
                />
            )}

            {/* Tab Navigation */}
            <div className="bg-white/80 border-b border-slate-200 px-8 sticky top-0 z-40 backdrop-blur-md">
                <AriaTabGroup
                    tabs={tabs}
                    selectedKey={activeTab}
                    onSelectionChange={(key) => setActiveTab(key as Tab)}
                />
            </div>

            {/* Main Content */}
            <div className="flex-1 p-8 space-y-8">

                {/* ─── DASHBOARD TAB ─── */}
                {activeTab === 'dashboard' && (
                    <>
                        {/* KPI Grid */}
                        <SpotAutomationKPIs
                            costs={costs}
                            savings={savings}
                            enabledASGsCount={enabledVmssCount}
                            totalASGsCount={vmssList.length}
                            enabledRegionsCount={enabledRegionsCount}
                            currency="INR"
                        />

                        {/* Summary Info */}
                        <div className="flex items-center justify-between text-[11px] font-bold uppercase tracking-wider text-slate-500 mb-2 px-2">
                            <span>
                                Total Instances: <span className="text-slate-900">{totalInstances}</span>
                            </span>
                            {error && (
                                <span className="text-rose-600 font-bold flex items-center gap-1">
                                    <i className="fas fa-exclamation-circle"></i> {error.message}
                                </span>
                            )}
                        </div>

                        {/* Chart */}
                        <SpotAutomationChart
                            historyData={historyData}
                            historySummary={historySummary}
                            timeRange="last7d"
                            isLoading={isLoadingHistory}
                            onTimeRangeChange={handleTimeRangeChange}
                            onRefresh={() => fetchHistoryData()}
                            currency="INR"
                        />

                        {/* VMSS Table */}
                        <div className="bg-white border border-slate-200 rounded-2xl shadow-sm overflow-hidden">
                            <div className="p-6 border-b border-slate-100 flex items-center justify-between">
                                <div>
                                    <h3 className="text-xl font-bold text-slate-900">
                                        Virtual Machine Scale Sets
                                    </h3>
                                    <p className="text-slate-500 text-sm mt-1">
                                        Configure AutoSpotting for your Azure VMSS. The full staged-replacement control loop runs asynchronously in the background.
                                    </p>
                                </div>
                                <button
                                    onClick={refresh}
                                    className="p-2 bg-slate-50 hover:bg-slate-100 text-slate-600 rounded-lg transition-colors border border-slate-200"
                                    aria-label="Refresh VMSS list"
                                >
                                    <i className={`fas fa-sync-alt ${isLoading ? 'fa-spin' : ''}`}></i>
                                </button>
                            </div>

                            <div className="overflow-x-auto">
                                <table className="w-full text-left">
                                    <thead>
                                        <tr className="bg-slate-50/50 text-slate-500 text-xs uppercase tracking-wider">
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">VMSS Name</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">Resource Group</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">Region</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">VM SKU</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100">Status</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100 text-center">Instances</th>
                                            <th className="px-6 py-4 font-bold border-b border-slate-100 text-right">Potential Savings</th>
                                            <th className="px-6 py-4 text-center border-b border-slate-100">Spot Control</th>
                                            <th className="px-6 py-4 text-center border-b border-slate-100">Config</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100">
                                        {vmssList.length > 0 ? (
                                            vmssList.map((vmss) => (
                                                <tr
                                                    key={`${vmss.resourceGroup}/${vmss.name}`}
                                                    className="hover:bg-slate-50 transition-colors group"
                                                >
                                                    <td className="px-6 py-4">
                                                        <div className="flex items-center gap-3">
                                                            <div className="w-9 h-9 rounded-xl bg-blue-50 flex items-center justify-center text-blue-600 border border-blue-100 shadow-sm">
                                                                <i className="fas fa-layer-group"></i>
                                                            </div>
                                                            <div>
                                                                <div className="text-sm font-bold text-slate-800 group-hover:text-blue-600 transition-colors">
                                                                    {vmss.name}
                                                                </div>
                                                                <div className="flex items-center gap-1.5 mt-0.5">
                                                                    <span className="text-[10px] text-slate-400 font-mono">AZURE VMSS</span>
                                                                    {vmss.aksManaged && (
                                                                        <span className="text-[9px] font-bold bg-blue-100 text-blue-700 px-1.5 py-0.5 rounded-full border border-blue-200">AKS</span>
                                                                    )}
                                                                </div>
                                                            </div>
                                                        </div>
                                                    </td>
                                                    <td className="px-6 py-4">
                                                        <span className="text-xs font-semibold text-slate-600 bg-white px-2.5 py-1 rounded-full border border-slate-200">
                                                            {vmss.resourceGroup}
                                                        </span>
                                                    </td>
                                                    <td className="px-6 py-4">
                                                        <span className="text-xs font-semibold text-slate-600 bg-white px-2.5 py-1 rounded-full border border-slate-200">
                                                            {vmss.region}
                                                        </span>
                                                    </td>
                                                    <td className="px-6 py-4 text-xs font-mono text-slate-500">
                                                        {vmss.vmSku}
                                                    </td>
                                                    <td className="px-6 py-4">
                                                        {vmss.isEnabled ? (
                                                            <span className="flex items-center gap-1.5 text-xs font-bold text-emerald-600">
                                                                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
                                                                Spot Active
                                                            </span>
                                                        ) : (
                                                            <span className="flex items-center gap-1.5 text-xs font-bold text-slate-400">
                                                                <span className="w-2 h-2 rounded-full bg-slate-300"></span>
                                                                Regular
                                                            </span>
                                                        )}
                                                    </td>
                                                    <td className="px-6 py-4 text-center">
                                                        <div className="inline-flex flex-col items-center">
                                                            <span className="text-sm font-bold text-slate-900">
                                                                {vmss.instanceCount}
                                                            </span>
                                                            <div className="flex gap-1 mt-1">
                                                                {vmss.spotCount > 0 && (
                                                                    <span className="text-[10px] text-emerald-600 font-bold">
                                                                        {vmss.spotCount}S
                                                                    </span>
                                                                )}
                                                                {vmss.onDemandCount > 0 && (
                                                                    <span className="text-[10px] text-slate-400 font-bold">
                                                                        {vmss.onDemandCount}R
                                                                    </span>
                                                                )}
                                                            </div>
                                                        </div>
                                                    </td>
                                                    <td className="px-6 py-4 text-right">
                                                        {vmss.aksManaged ? (
                                                            <span className="inline-flex items-center gap-1 text-[10px] font-bold text-slate-400 bg-slate-100 border border-slate-200 px-2 py-1 rounded-full">
                                                                <i className="fas fa-ban text-[9px]"></i>
                                                                N/A
                                                            </span>
                                                        ) : (
                                                            <div className="text-sm font-bold text-emerald-600">
                                                                +₹{vmss.potentialSavings.toFixed(2)}
                                                                <span className="text-[10px] text-slate-400 ml-1 font-medium">
                                                                    /hr
                                                                </span>
                                                            </div>
                                                        )}
                                                    </td>
                                                    <td className="px-6 py-4">
                                                        <div className="flex justify-center">
                                                            {vmss.aksManaged ? (
                                                                <div className="relative group/aks">
                                                                    <div
                                                                        className="flex items-center gap-1.5 text-[10px] font-bold text-slate-400 bg-slate-50 border border-slate-200 rounded-lg px-2.5 py-1.5 cursor-not-allowed select-none"
                                                                        aria-disabled="true"
                                                                    >
                                                                        <i className="fas fa-lock text-[9px]"></i>
                                                                        AKS Managed
                                                                    </div>
                                                                    <div className="absolute bottom-full mb-2 left-1/2 -translate-x-1/2 w-64 bg-slate-900 text-white text-[10px] rounded-lg px-3 py-2 shadow-lg hidden group-hover/aks:block z-10 pointer-events-none leading-relaxed">
                                                                        <div className="flex items-start gap-1.5">
                                                                            <i className="fas fa-info-circle text-blue-400 mt-0.5 shrink-0"></i>
                                                                            <span>This is an <strong className="text-white">AKS-managed node pool</strong>. AutoSpotting cannot convert it — AKS controls its lifecycle. To use Spot VMs, create a dedicated Spot node pool in the AKS console.</span>
                                                                        </div>
                                                                    </div>
                                                                </div>
                                                            ) : (
                                                                <button
                                                                    onClick={() => handleToggle(vmss)}
                                                                    disabled={vmss.updating}
                                                                    title={
                                                                        vmss.isEnabled
                                                                            ? 'Disable Spot for this VMSS'
                                                                            : 'Enable Spot — starts staged replacement loop'
                                                                    }
                                                                    className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 ${
                                                                        vmss.isEnabled ? 'bg-blue-600' : 'bg-slate-200'
                                                                    } ${vmss.updating ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
                                                                >
                                                                    <span className="sr-only">Toggle AutoSpotting</span>
                                                                    <span
                                                                        className={`${
                                                                            vmss.isEnabled ? 'translate-x-6' : 'translate-x-1'
                                                                        } inline-block h-4 w-4 transform rounded-full bg-white transition-transform ease-in-out duration-200 flex items-center justify-center`}
                                                                    >
                                                                        {vmss.updating && (
                                                                            <i className="fas fa-circle-notch fa-spin text-[8px] text-blue-600"></i>
                                                                        )}
                                                                    </span>
                                                                </button>
                                                            )}
                                                        </div>
                                                    </td>
                                                    <td className="px-6 py-4 text-center">
                                                        <button
                                                            onClick={() => setConfigVmss(vmss)}
                                                            className="p-1.5 text-slate-400 hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
                                                            title="Configure Spot settings"
                                                        >
                                                            <i className="fas fa-sliders-h text-sm"></i>
                                                        </button>
                                                    </td>
                                                </tr>
                                            ))
                                        ) : (
                                            <tr>
                                                <td colSpan={9} className="px-6 py-12 text-center text-slate-500 italic">
                                                    {isLoading
                                                        ? 'Scanning for Virtual Machine Scale Sets...'
                                                        : 'No VMSS found in this subscription.'}
                                                </td>
                                            </tr>
                                        )}
                                    </tbody>
                                </table>
                            </div>
                        </div>

                        {/* Info Banner */}
                        <div className="bg-blue-50 border border-blue-200 rounded-xl p-4 flex items-start gap-3">
                            <svg className="w-5 h-5 text-blue-500 mt-0.5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                            </svg>
                            <div className="text-sm text-blue-700">
                                <p className="font-bold mb-1">Full Control Loop Active</p>
                                <p className="text-xs text-blue-600">
                                    When you enable Spot for a VMSS, the AutoSpotting control loop runs asynchronously:
                                    it validates candidacy (≥2 instances, health probe present), updates the VMSS profile
                                    to Spot priority, then performs a staged replacement — adding one Spot VM, waiting for
                                    it to become healthy, then removing one Regular VM — until the target ratio is achieved.
                                    Azure Spot VMs typically save 60–80% over on-demand pricing.
                                </p>
                            </div>
                        </div>
                    </>
                )}

                {/* ─── HISTORY TAB ─── */}
                {activeTab === 'history' && (
                    <ActionsHistory accountId={selectedAccountId} provider="AZURE" />
                )}

                {/* ─── LAUNCH ANALYTICS TAB ─── */}
                {activeTab === 'analytics' && (
                    <div className="space-y-6">
                        {/* Range selector */}
                        <div className="flex items-center justify-between">
                            <h2 className="text-xl font-black text-slate-900 uppercase tracking-tight">
                                Launch Analytics
                            </h2>
                            <div className="flex gap-2">
                                {(['last24h', 'last7d', 'last30d', 'last90d'] as const).map(r => (
                                    <button
                                        key={r}
                                        onClick={() => setAnalyticsRange(r)}
                                        className={`px-3 py-1.5 text-xs font-bold rounded-lg transition-colors ${
                                            analyticsRange === r
                                                ? 'bg-blue-600 text-white'
                                                : 'bg-white text-slate-600 border border-slate-200 hover:bg-slate-50'
                                        }`}
                                    >
                                        {r === 'last24h' ? '24h' : r === 'last7d' ? '7d' : r === 'last30d' ? '30d' : '90d'}
                                    </button>
                                ))}
                            </div>
                        </div>

                        {isLoadingAnalytics ? (
                            <div className="grid grid-cols-4 gap-4">
                                {[...Array(4)].map((_, i) => (
                                    <div key={i} className="bg-white border border-slate-200 rounded-2xl p-6 animate-pulse">
                                        <div className="h-4 bg-slate-100 rounded mb-3 w-3/4"></div>
                                        <div className="h-8 bg-slate-100 rounded w-1/2"></div>
                                    </div>
                                ))}
                            </div>
                        ) : launchAnalytics ? (
                            <>
                                {/* Summary KPIs */}
                                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                                    {[
                                        { label: 'Total Attempts',  value: launchAnalytics.total_attempts ?? 0,     color: 'text-slate-900' },
                                        { label: 'Successes',       value: launchAnalytics.total_successes ?? 0,    color: 'text-emerald-600' },
                                        { label: 'Failures',        value: launchAnalytics.total_failures ?? 0,     color: 'text-rose-600' },
                                        { label: 'Success Rate',    value: `${((launchAnalytics.success_rate ?? 0)).toFixed(1)}%`, color: 'text-blue-600' },
                                    ].map(kpi => (
                                        <div key={kpi.label} className="bg-white border border-slate-200 rounded-2xl p-6 shadow-sm">
                                            <p className="text-xs font-black uppercase tracking-widest text-slate-500 mb-2">{kpi.label}</p>
                                            <p className={`text-3xl font-black ${kpi.color}`}>{kpi.value}</p>
                                        </div>
                                    ))}
                                </div>

                                <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                                    {/* By VM SKU */}
                                    <div className="bg-white border border-slate-200 rounded-2xl shadow-sm overflow-hidden">
                                        <div className="px-6 py-4 border-b border-slate-100">
                                            <h3 className="font-black text-slate-900 uppercase text-sm tracking-tight">By VM SKU</h3>
                                        </div>
                                        <div className="overflow-x-auto">
                                            <table className="w-full text-sm">
                                                <thead>
                                                    <tr className="text-xs font-black uppercase tracking-wider text-slate-500 bg-slate-50/50">
                                                        <th className="px-4 py-3 text-left">SKU</th>
                                                        <th className="px-4 py-3 text-center">Attempts</th>
                                                        <th className="px-4 py-3 text-center">Success Rate</th>
                                                    </tr>
                                                </thead>
                                                <tbody className="divide-y divide-slate-100">
                                                    {Object.entries(launchAnalytics.by_instance_type ?? {}).map(([sku, stats]: [string, any]) => (
                                                        <tr key={sku} className="hover:bg-slate-50">
                                                            <td className="px-4 py-3 font-mono text-xs text-slate-700">{sku}</td>
                                                            <td className="px-4 py-3 text-center text-slate-600">{stats.attempts}</td>
                                                            <td className="px-4 py-3 text-center">
                                                                <span className={`font-bold ${stats.success_rate >= 80 ? 'text-emerald-600' : stats.success_rate >= 50 ? 'text-amber-600' : 'text-rose-600'}`}>
                                                                    {stats.success_rate?.toFixed(1)}%
                                                                </span>
                                                            </td>
                                                        </tr>
                                                    ))}
                                                    {Object.keys(launchAnalytics.by_instance_type ?? {}).length === 0 && (
                                                        <tr><td colSpan={3} className="px-4 py-8 text-center text-slate-400 italic">No data available</td></tr>
                                                    )}
                                                </tbody>
                                            </table>
                                        </div>
                                    </div>

                                    {/* By Region */}
                                    <div className="bg-white border border-slate-200 rounded-2xl shadow-sm overflow-hidden">
                                        <div className="px-6 py-4 border-b border-slate-100">
                                            <h3 className="font-black text-slate-900 uppercase text-sm tracking-tight">By Region</h3>
                                        </div>
                                        <div className="overflow-x-auto">
                                            <table className="w-full text-sm">
                                                <thead>
                                                    <tr className="text-xs font-black uppercase tracking-wider text-slate-500 bg-slate-50/50">
                                                        <th className="px-4 py-3 text-left">Region</th>
                                                        <th className="px-4 py-3 text-center">Attempts</th>
                                                        <th className="px-4 py-3 text-center">Success Rate</th>
                                                    </tr>
                                                </thead>
                                                <tbody className="divide-y divide-slate-100">
                                                    {Object.entries(launchAnalytics.by_region ?? {}).map(([region, stats]: [string, any]) => (
                                                        <tr key={region} className="hover:bg-slate-50">
                                                            <td className="px-4 py-3 text-xs text-slate-700 font-medium">{region}</td>
                                                            <td className="px-4 py-3 text-center text-slate-600">{stats.attempts}</td>
                                                            <td className="px-4 py-3 text-center">
                                                                <span className={`font-bold ${stats.success_rate >= 80 ? 'text-emerald-600' : stats.success_rate >= 50 ? 'text-amber-600' : 'text-rose-600'}`}>
                                                                    {stats.success_rate?.toFixed(1)}%
                                                                </span>
                                                            </td>
                                                        </tr>
                                                    ))}
                                                    {Object.keys(launchAnalytics.by_region ?? {}).length === 0 && (
                                                        <tr><td colSpan={3} className="px-4 py-8 text-center text-slate-400 italic">No data available</td></tr>
                                                    )}
                                                </tbody>
                                            </table>
                                        </div>
                                    </div>
                                </div>

                                {/* Top Failure Reasons */}
                                {launchAnalytics.top_failure_reasons?.length > 0 && (
                                    <div className="bg-white border border-slate-200 rounded-2xl shadow-sm overflow-hidden">
                                        <div className="px-6 py-4 border-b border-slate-100">
                                            <h3 className="font-black text-slate-900 uppercase text-sm tracking-tight">Top Failure Reasons</h3>
                                        </div>
                                        <div className="p-6 space-y-3">
                                            {launchAnalytics.top_failure_reasons.map((fr: any, idx: number) => (
                                                <div key={idx} className="flex items-center gap-4">
                                                    <div className="flex-1">
                                                        <div className="flex justify-between text-xs mb-1">
                                                            <span className="text-slate-700 font-medium truncate max-w-[70%]">{fr.reason}</span>
                                                            <span className="text-rose-600 font-bold">{fr.count} ({fr.percentage?.toFixed(1)}%)</span>
                                                        </div>
                                                        <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden">
                                                            <div
                                                                className="h-full bg-rose-400 rounded-full"
                                                                style={{ width: `${Math.min(fr.percentage ?? 0, 100)}%` }}
                                                            />
                                                        </div>
                                                    </div>
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                )}

                                {/* Recommended VM SKUs */}
                                {launchAnalytics.recommended_types?.length > 0 && (
                                    <div className="bg-white border border-slate-200 rounded-2xl shadow-sm overflow-hidden">
                                        <div className="px-6 py-4 border-b border-slate-100">
                                            <h3 className="font-black text-slate-900 uppercase text-sm tracking-tight">Recommended VM SKUs</h3>
                                        </div>
                                        <div className="overflow-x-auto">
                                            <table className="w-full text-sm">
                                                <thead>
                                                    <tr className="text-xs font-black uppercase tracking-wider text-slate-500 bg-slate-50/50">
                                                        <th className="px-4 py-3 text-left">VM SKU</th>
                                                        <th className="px-4 py-3 text-center">Launches</th>
                                                        <th className="px-4 py-3 text-center">Success Rate</th>
                                                        <th className="px-4 py-3 text-center">Avg Launch Time</th>
                                                        <th className="px-4 py-3 text-center">Score</th>
                                                    </tr>
                                                </thead>
                                                <tbody className="divide-y divide-slate-100">
                                                    {launchAnalytics.recommended_types.map((rt: any, idx: number) => (
                                                        <tr key={idx} className="hover:bg-slate-50">
                                                            <td className="px-4 py-3 font-mono text-xs font-bold text-slate-800">
                                                                {idx === 0 && <span className="mr-1.5 text-amber-500">★</span>}
                                                                {rt.instance_type}
                                                            </td>
                                                            <td className="px-4 py-3 text-center text-slate-600">{rt.total_launches}</td>
                                                            <td className="px-4 py-3 text-center font-bold text-emerald-600">{rt.success_rate?.toFixed(1)}%</td>
                                                            <td className="px-4 py-3 text-center text-slate-500">{rt.avg_launch_time}</td>
                                                            <td className="px-4 py-3 text-center text-blue-600 font-bold">{rt.score?.toFixed(1)}</td>
                                                        </tr>
                                                    ))}
                                                </tbody>
                                            </table>
                                        </div>
                                    </div>
                                )}
                            </>
                        ) : (
                            <div className="bg-white border border-slate-200 rounded-2xl p-12 text-center text-slate-400 italic">
                                No analytics data yet. Enable AutoSpotting on a VMSS to start collecting launch data.
                            </div>
                        )}
                    </div>
                )}

                {/* ─── SETTINGS TAB ─── */}
                {activeTab === 'settings' && (
                    <div className="space-y-6 max-w-2xl">
                        <h2 className="text-xl font-black text-slate-900 uppercase tracking-tight">Settings</h2>

                        {/* Reservation Respect */}
                        <div className="bg-white border border-slate-200 rounded-2xl shadow-sm p-6 space-y-5">
                            <div>
                                <h3 className="text-base font-black text-slate-900 mb-1">Azure Reservation Respect</h3>
                                <p className="text-sm text-slate-500">
                                    When enabled, AutoSpotting checks Azure Reserved Instance utilization before converting VMs to Spot.
                                    Only converts when utilization is at or above the threshold.
                                </p>
                            </div>

                            <div className="flex items-center justify-between py-3 border-t border-slate-100">
                                <div>
                                    <p className="text-sm font-bold text-slate-800">Enable Reservation-Aware Conversion</p>
                                    <p className="text-xs text-slate-500 mt-0.5">
                                        Respect Azure Reserved Instance commitments before Spot migration
                                    </p>
                                </div>
                                <button
                                    onClick={() => setRispEnabled(v => !v)}
                                    className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 ${
                                        rispEnabled ? 'bg-blue-600' : 'bg-slate-200'
                                    }`}
                                >
                                    <span
                                        className={`${rispEnabled ? 'translate-x-6' : 'translate-x-1'} inline-block h-4 w-4 transform rounded-full bg-white transition-transform ease-in-out duration-200`}
                                    />
                                </button>
                            </div>

                            {rispEnabled && (
                                <div className="pt-2 border-t border-slate-100">
                                    <AriaSlider
                                        label="Utilization Threshold"
                                        value={rispThreshold}
                                        onChange={setRispThreshold}
                                        minValue={0}
                                        maxValue={100}
                                        step={5}
                                        formatValue={(v) => `${v}%`}
                                        description={`Only convert to Spot when Reserved Instance utilization ≥ ${rispThreshold}%`}
                                    />
                                </div>
                            )}

                            <div className="pt-2 flex justify-end">
                                <button
                                    onClick={handleSaveSettings}
                                    disabled={savingSettings}
                                    className="px-5 py-2 text-sm text-white bg-blue-600 hover:bg-blue-700 rounded-lg transition-colors font-bold flex items-center gap-2 disabled:opacity-60"
                                >
                                    {savingSettings ? <i className="fas fa-circle-notch fa-spin"></i>
                                        : settingsSaved ? <i className="fas fa-check"></i> : null}
                                    {settingsSaved ? 'Saved!' : 'Save Settings'}
                                </button>
                            </div>
                        </div>

                        {/* Data Sync */}
                        <div className="bg-white border border-slate-200 rounded-2xl shadow-sm p-6 space-y-4">
                            <div>
                                <h3 className="text-base font-black text-slate-900 mb-1">Data Sync & Backfill</h3>
                                <p className="text-sm text-slate-500">
                                    Scan all VMSS in the subscription and backfill event records for any Spot-enabled VMSS
                                    that are missing history. Useful after initial onboarding.
                                </p>
                            </div>

                            <div className="flex items-center gap-4">
                                <button
                                    onClick={handleSync}
                                    disabled={syncing}
                                    className="px-5 py-2 text-sm text-white bg-gray-600 hover:bg-gray-700 rounded-lg transition-colors font-bold flex items-center gap-2 disabled:opacity-60"
                                >
                                    {syncing
                                        ? <><i className="fas fa-circle-notch fa-spin"></i> Syncing...</>
                                        : <><i className="fas fa-sync-alt"></i> Sync Events</>
                                    }
                                </button>
                                {syncResult && (
                                    <span className="text-sm text-slate-600">{syncResult}</span>
                                )}
                            </div>
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
};

export default AzureSpotAutomationPage;
