import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import { DialogRoot, DialogPanel } from '../ui';
import { useAgentInstallConfig, type AgentInstallConfig } from '../../hooks/useAgentInstallConfig';
import { substituteTokens } from '../K8sGuides/guideVars';

/** A cluster the user can pick from when the guide is opened without one selected. */
export interface GuideClusterOption {
  name: string;
  resourceGroup: string;
}

interface AzureCloudK8sHelpModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Prefills the Cluster Name field — passed from the details page. */
  clusterName?: string;
  /** Prefills the Resource Group field. */
  resourceGroup?: string;
  /** Prefills the Subscription ID field. */
  subscriptionId?: string;
  /** When supplied, Cluster Name becomes a picker that fills the resource group too. */
  clusters?: GuideClusterOption[];
}

/**
 * The three placeholders that appear across the guide's commands. Every occurrence is
 * substituted from a single set of inputs so the user fills each value once instead of
 * hand-editing it in Steps 1.1, 1.5, 2.2, 3.2 and 4.3 separately — which is how the
 * wrong cluster name ends up in a ConfigMap and two clusters collide on one
 * cluster_config_id.
 */
export const PLACEHOLDERS = {
  clusterName: '<CLUSTER_NAME>',
  resourceGroup: '<RESOURCE_GROUP>',
  subscriptionId: '<AZURE_SUBSCRIPTION_ID>',
} as const;

/**
 * Agent image tag shown in this guide. Declared once so the Quick Install summary and the Part 3
 * manifest cannot disagree — they did before, when the manifest said v2.0.0 while a newer image
 * was already published, and there was no way to tell from the UI.
 *
 * Keep in step with AGENT_IMAGE in xamops-agent/scripts/install-aks.sh.
 */
export const AGENT_IMAGE_TAG = 'v2.1.0';

export interface GuideVars {
  clusterName: string;
  resourceGroup: string;
  subscriptionId: string;
}

const GuideVarsContext = React.createContext<GuideVars>({
  clusterName: '',
  resourceGroup: '',
  subscriptionId: '',
});

/**
 * Replaces filled-in placeholders. Blank values are left as the literal token on purpose:
 * an unmistakable `<CLUSTER_NAME>` in the copied command is far better than a silently
 * broken one with an empty string where the name should be.
 */
export function substituteVars(code: string, vars: GuideVars): string {
  // Delegates to the shared implementation so the AKS and EKS guides cannot drift on
  // trimming, blank handling, or escaping. Only the token mapping is provider-specific.
  return substituteTokens(code, {
    [PLACEHOLDERS.clusterName]: vars.clusterName,
    [PLACEHOLDERS.resourceGroup]: vars.resourceGroup,
    [PLACEHOLDERS.subscriptionId]: vars.subscriptionId,
  });
}

const CodeBlock: React.FC<{ code: string; title?: string }> = ({ code, title }) => {
  const [copied, setCopied] = useState(false);
  const vars = React.useContext(GuideVarsContext);
  // Substituted centrally here so no command block can be missed, and so Copy always
  // yields exactly what is displayed.
  const resolved = substituteVars(code, vars);
  const handleCopy = async () => {
    try { await navigator.clipboard.writeText(resolved); setCopied(true); setTimeout(() => setCopied(false), 2000); }
    catch {}
  };
  return (
    <div className="relative group my-3">
      {title && (
        <div className="bg-[#1a1d27] text-[rgba(255,255,255,0.5)] px-4 py-2 text-[11px] font-medium rounded-t-xl border-b border-[rgba(255,255,255,0.06)]">
          {title}
        </div>
      )}
      <div className={`bg-[#1a1d27] text-[#e2e8f0] p-4 font-mono text-[12px] overflow-x-auto ${title ? 'rounded-b-xl' : 'rounded-xl'}`}>
        <button onClick={handleCopy}
          className="absolute top-2.5 right-2.5 flex items-center gap-1.5 px-2.5 py-1 bg-[rgba(255,255,255,0.1)] hover:bg-[rgba(255,255,255,0.18)] text-white text-[11px] font-semibold rounded-lg border border-[rgba(255,255,255,0.12)] transition-colors opacity-0 group-hover:opacity-100">
          <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={12} className={copied ? 'text-emerald-400' : ''} />
          {copied ? 'Copied!' : 'Copy'}
        </button>
        <pre className="whitespace-pre leading-relaxed">{resolved}</pre>
      </div>
    </div>
  );
};

const CollapsibleSection: React.FC<{ title: string; icon: string; iconColor?: string; defaultOpen?: boolean; children: React.ReactNode }> = ({
  title, icon, iconColor = '#6366F1', defaultOpen = false, children,
}) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);
  return (
    <div className="border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
      <button onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center justify-between p-4 bg-[rgba(0,0,0,0.02)] hover:bg-[rgba(0,0,0,0.04)] transition-colors text-left">
        <div className="flex items-center gap-3">
          <Icon icon={icon} width={15} color={iconColor} />
          <span className="font-semibold text-[rgba(0,0,0,0.75)] text-[13px]">{title}</span>
        </div>
        <Icon icon="mdi:chevron-down" width={16} color="rgba(0,0,0,0.35)" className={`transition-transform ${isOpen ? 'rotate-180' : ''}`} />
      </button>
      {isOpen && <div className="p-4 space-y-3 bg-white">{children}</div>}
    </div>
  );
};

/**
 * Reports only the variables this step needs that are still empty. Once they're filled from
 * the bar at the top the note disappears, rather than telling the user to replace values
 * that are already substituted in the block below.
 */
const VarNote: React.FC<{ vars: string[] }> = ({ vars }) => {
  const current = React.useContext(GuideVarsContext);
  const valueFor = (token: string) =>
    token === PLACEHOLDERS.clusterName ? current.clusterName
      : token === PLACEHOLDERS.resourceGroup ? current.resourceGroup
      : token === PLACEHOLDERS.subscriptionId ? current.subscriptionId
      : '';
  const missing = vars.filter(v => !valueFor(v).trim());

  if (missing.length === 0) {
    return (
      <div className="bg-emerald-50 border border-emerald-200 rounded-lg p-3 mb-3 flex items-start gap-2">
        <Icon icon="mdi:check-circle-outline" width={14} color="#059669" className="mt-0.5 flex-shrink-0" />
        <p className="text-[12px] text-emerald-800">
          Values filled in — the commands below are ready to copy as-is.
        </p>
      </div>
    );
  }

  return (
    <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mb-3 flex items-start gap-2">
      <Icon icon="mdi:alert-outline" width={14} color="#D97706" className="mt-0.5 flex-shrink-0" />
      <p className="text-[12px] text-amber-800">
        <strong>Still needed:</strong> {missing.join(', ')} — fill these in at the top of this guide.
      </p>
    </div>
  );
};

/** Three inputs at the top of the guide that drive every command block below. */
const VariableBar: React.FC<{
  vars: GuideVars;
  onChange: (next: GuideVars) => void;
  clusters?: GuideClusterOption[];
  /** Set when the backend URL can't be reached from inside a cluster (local dev). */
  tunnel?: { url: string; onChange: (next: string) => void; backendUrl: string };
}> = ({ vars, onChange, clusters, tunnel }) => {
  const allSet = vars.clusterName.trim() && vars.resourceGroup.trim() && vars.subscriptionId.trim();

  const inputClass =
    'w-full px-2.5 py-1.5 text-[12px] font-mono border rounded-lg outline-none transition-colors focus:border-indigo-500';

  const fieldBorder = (value: string) =>
    value.trim() ? 'border-[rgba(0,0,0,0.12)] bg-white' : 'border-amber-300 bg-amber-50';

  return (
    <div className={`rounded-xl border p-4 mb-4 ${allSet ? 'border-emerald-200 bg-emerald-50/40' : 'border-amber-200 bg-amber-50/50'}`}>
      <div className="flex items-center gap-2 mb-3">
        <Icon icon={allSet ? 'mdi:check-circle-outline' : 'mdi:pencil-outline'} width={14}
              color={allSet ? '#059669' : '#D97706'} />
        <span className="text-[12px] font-bold text-[rgba(0,0,0,0.75)]">
          {allSet ? 'Your cluster details' : 'Enter your cluster details'}
        </span>
        <span className="text-[11px] text-[rgba(0,0,0,0.45)]">
          — applied to every command in this guide
        </span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        <div>
          <label htmlFor="guide-cluster-name" className="block text-[11px] font-semibold text-[rgba(0,0,0,0.55)] mb-1">
            Cluster Name
          </label>
          {clusters && clusters.length > 0 ? (
            <select
              id="guide-cluster-name"
              className={`${inputClass} ${fieldBorder(vars.clusterName)}`}
              value={vars.clusterName}
              onChange={e => {
                const picked = clusters.find(c => c.name === e.target.value);
                // Setting both together prevents pairing a cluster with the wrong group.
                onChange({
                  ...vars,
                  clusterName: e.target.value,
                  resourceGroup: picked ? picked.resourceGroup : vars.resourceGroup,
                });
              }}
            >
              <option value="">Select a cluster…</option>
              {clusters.map(c => (
                <option key={c.name} value={c.name}>{c.name}</option>
              ))}
            </select>
          ) : (
            <input
              id="guide-cluster-name"
              type="text"
              className={`${inputClass} ${fieldBorder(vars.clusterName)}`}
              placeholder="my-aks-cluster"
              value={vars.clusterName}
              onChange={e => onChange({ ...vars, clusterName: e.target.value })}
            />
          )}
        </div>

        <div>
          <label htmlFor="guide-resource-group" className="block text-[11px] font-semibold text-[rgba(0,0,0,0.55)] mb-1">
            Resource Group
          </label>
          <input
            id="guide-resource-group"
            type="text"
            className={`${inputClass} ${fieldBorder(vars.resourceGroup)}`}
            placeholder="my-resource-group"
            value={vars.resourceGroup}
            onChange={e => onChange({ ...vars, resourceGroup: e.target.value })}
          />
        </div>

        <div>
          <label htmlFor="guide-subscription-id" className="block text-[11px] font-semibold text-[rgba(0,0,0,0.55)] mb-1">
            Azure Subscription ID
          </label>
          <input
            id="guide-subscription-id"
            type="text"
            className={`${inputClass} ${fieldBorder(vars.subscriptionId)}`}
            placeholder="00000000-0000-0000-0000-000000000000"
            value={vars.subscriptionId}
            onChange={e => onChange({ ...vars, subscriptionId: e.target.value })}
          />
        </div>
      </div>

      {!allSet && (
        <p className="text-[11px] text-amber-800 mt-2.5">
          Empty fields stay as <code className="bg-amber-100 px-1 rounded">&lt;PLACEHOLDER&gt;</code> tokens in the
          commands, so nothing silently runs with a missing value.
        </p>
      )}

      {tunnel && (
        <div className="mt-3 pt-3 border-t border-[rgba(0,0,0,0.08)]">
          <label htmlFor="guide-tunnel-url" className="block text-[11px] font-semibold text-[rgba(0,0,0,0.55)] mb-1">
            Public tunnel URL
            <span className="ml-1 font-normal text-[rgba(0,0,0,0.4)]">
              — required: this backend is at {tunnel.backendUrl}, which a cluster cannot reach
            </span>
          </label>
          <input
            id="guide-tunnel-url"
            type="text"
            className={`w-full px-2.5 py-1.5 text-[12px] font-mono border rounded-lg outline-none transition-colors focus:border-indigo-500 ${
              tunnel.url.trim() ? 'border-[rgba(0,0,0,0.12)] bg-white' : 'border-red-300 bg-red-50'}`}
            placeholder="https://your-subdomain.ngrok-free.dev"
            value={tunnel.url}
            onChange={e => tunnel.onChange(e.target.value)}
          />
          <p className="text-[11px] text-[rgba(0,0,0,0.5)] mt-1.5">
            Start one with <code className="bg-[rgba(0,0,0,0.05)] px-1 rounded">ngrok http 8080</code> or{' '}
            <code className="bg-[rgba(0,0,0,0.05)] px-1 rounded">cloudflared tunnel --url http://localhost:8080</code>,
            then paste the public HTTPS URL here. It replaces{' '}
            <code className="bg-[rgba(0,0,0,0.05)] px-1 rounded">XAMOPS_SERVER_URL</code> in Step 3.2.
          </p>
          {!tunnel.url.trim() && (
            <p className="text-[11px] text-red-700 mt-1.5">
              Leaving this empty keeps <code className="bg-red-100 px-1 rounded">{tunnel.backendUrl}</code> in the
              manifest. Port 8080 is also the agent's own health port, so the agent ends up POSTing to itself and
              reports a misleading <strong>HTTP 404</strong> rather than a connection error.
            </p>
          )}
        </div>
      )}
    </div>
  );
};

/** Host without scheme/port, for prose like "pushes outbound to <host>:443". */
const hostOf = (url: string) => url.replace(/^[a-zA-Z]+:\/\//, '').replace(/\/.*$/, '');

const AzureCloudK8sHelpModal: React.FC<AzureCloudK8sHelpModalProps> = ({
  isOpen, onClose, clusterName, resourceGroup, subscriptionId, clusters,
}) => {
  // Quick Install is the default: it is the correct choice for almost everyone, and it is also
  // what an existing cluster needs in order to converge onto the current stack. The manual
  // parts stay available for change-control review and for debugging a failed run.
  const [activeTab, setActiveTab] = useState<'quick' | 'overview' | 'part1' | 'part2' | 'part3' | 'part4'>('quick');

  const [vars, setVars] = useState<GuideVars>({
    clusterName: clusterName ?? '',
    resourceGroup: resourceGroup ?? '',
    subscriptionId: subscriptionId ?? '',
  });

  // Re-seed when the caller's context changes (e.g. navigating between clusters without
  // unmounting), but never overwrite something the user has typed.
  React.useEffect(() => {
    setVars(prev => ({
      clusterName: prev.clusterName || clusterName || '',
      resourceGroup: prev.resourceGroup || resourceGroup || '',
      subscriptionId: prev.subscriptionId || subscriptionId || '',
    }));
  }, [clusterName, resourceGroup, subscriptionId]);

  // Resolves XAMOPS_SERVER_URL and the API key from the running backend profile, so the
  // manifest below targets whichever environment the user is actually logged into.
  const { config, usedFallback } = useAgentInstallConfig(isOpen);

  // Local-dev only: the agent runs in Azure and cannot reach a loopback backend, so the
  // user supplies a public tunnel URL that replaces XAMOPS_SERVER_URL in the manifest.
  const [tunnelUrl, setTunnelUrl] = useState('');

  /**
   * What the manifest should actually say. A supplied tunnel URL wins over the backend's
   * own (unreachable) address; otherwise the profile value is used unchanged.
   */
  const effectiveConfig: AgentInstallConfig = React.useMemo(() => {
    const trimmed = tunnelUrl.trim().replace(/\/+$/, '');
    if (!config.reachableFromCluster && trimmed) {
      return { ...config, serverUrl: trimmed, reachableFromCluster: true };
    }
    return config;
  }, [config, tunnelUrl]);

  // `manual` marks the tabs that Quick Install replaces. They are separated in the tab strip so
  // the two routes do not read as one sequence to work through.
  const tabs = [
    { id: 'quick',    label: 'Quick Install',            icon: 'mdi:flash-outline',         manual: false },
    { id: 'overview', label: 'Overview',                 icon: 'mdi:information-outline',   manual: false },
    { id: 'part1',    label: 'Part 1: Monitoring Stack',  icon: 'mdi:chart-line',            manual: true },
    { id: 'part2',    label: 'Part 2: Security Tools',    icon: 'mdi:shield-check-outline',  manual: true },
    { id: 'part3',    label: 'Part 3: XamOps Agent',      icon: 'mdi:robot-outline',         manual: true },
    { id: 'part4',    label: 'Part 4: Verification',      icon: 'mdi:check-circle-outline',  manual: true },
  ] as const;

  const firstManualTabId = tabs.find(t => t.manual)?.id;

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-6xl">
      <DialogPanel
        title={
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-indigo-600 rounded-xl flex items-center justify-center">
              <Icon icon="mdi:book-open-outline" width={20} color="white" />
            </div>
            <div>
              <div className="font-bold text-[15px] text-[rgba(0,0,0,0.88)]">AKS Setup Guide — XamOps Agent</div>
              <div className="text-[12px] text-[rgba(0,0,0,0.45)]">In-cluster monitoring · Push-only · No inbound connections required</div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={
          <button onClick={onClose}
            className="px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-xl transition-colors">
            Close Guide
          </button>
        }
      >
        {/* Tab Navigation */}
        <div className="flex border-b border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] overflow-x-auto -mx-6 px-6 mb-4">
          {tabs.map(tab => (
            <React.Fragment key={tab.id}>
              {tab.id === firstManualTabId && (
                <div className="flex items-center gap-2 pl-3 pr-1 self-center" aria-hidden="true">
                  <span className="h-4 w-px bg-[rgba(0,0,0,0.12)]" />
                  <span className="text-[10px] font-bold uppercase tracking-wide text-[rgba(0,0,0,0.3)]">
                    Manual steps
                  </span>
                </div>
              )}
              <button onClick={() => setActiveTab(tab.id)}
                className={`flex items-center gap-2 px-4 py-3 text-[12px] font-semibold whitespace-nowrap transition-colors border-b-2 ${
                  activeTab === tab.id
                    ? 'text-indigo-600 border-indigo-600 bg-white'
                    : 'text-[rgba(0,0,0,0.45)] border-transparent hover:text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.02)]'}`}>
                <Icon icon={tab.icon} width={13} />
                {tab.label}
              </button>
            </React.Fragment>
          ))}
        </div>

        {/*
          * The config hook falls back to production values when it cannot reach the backend,
          * because a guide rendering `undefined` is unusable. But falling back silently on a
          * local or UAT instance recreates the exact bug this guide was fixed to avoid: a user
          * copies a manifest that points their cluster at production. So say so.
          */}
        {usedFallback && (
          <div className="p-3 mb-4 bg-red-50 border border-red-200 rounded-xl text-[12px] text-red-800 flex items-start gap-2">
            <Icon icon="mdi:alert-circle-outline" width={14} className="mt-0.5 flex-shrink-0" />
            <div>
              <strong>Could not read this environment&rsquo;s settings, so the commands below show
              production values.</strong> If you are not intending to onboard into production, do not run
              them — a cluster set up this way will report into{' '}
              <code className="bg-red-100 px-1 rounded">{config.serverUrl}</code>. Sign in again, or
              check that this backend exposes{' '}
              <code className="bg-red-100 px-1 rounded">/api/xamops/agent-install/config</code>.
            </div>
          </div>
        )}

        <GuideVarsContext.Provider value={vars}>
          <VariableBar
            vars={vars}
            onChange={setVars}
            clusters={clusters}
            tunnel={config.reachableFromCluster ? undefined : {
              url: tunnelUrl,
              onChange: setTunnelUrl,
              backendUrl: config.serverUrl,
            }}
          />

          <div className="overflow-y-auto max-h-[52vh]" style={{ overscrollBehavior: 'contain' }}>
            {activeTab === 'quick'    && <QuickInstallTab config={effectiveConfig} vars={vars} />}
            {activeTab === 'overview' && <OverviewTab serverUrl={effectiveConfig.serverUrl} />}
            {activeTab === 'part1'    && <Part1Tab />}
            {activeTab === 'part2'    && <Part2Tab />}
            {activeTab === 'part3'    && <Part3Tab config={effectiveConfig} />}
            {activeTab === 'part4'    && <Part4Tab />}
          </div>
        </GuideVarsContext.Provider>
      </DialogPanel>
    </DialogRoot>
  );
};

/**
 * The one-command path.
 *
 * Everything the installer needs is passed as flags rather than being baked into the script,
 * so the script itself contains no secrets and can be served unauthenticated. That matters:
 * the command has to run in Azure Cloud Shell, which has no XamOps session to authenticate a
 * download with.
 */
const QuickInstallTab: React.FC<{ config: AgentInstallConfig; vars: GuideVars }> = ({ config, vars }) => {
  const scriptUrl = `${config.serverUrl}/api/v1/agent/install/aks.sh`;

  const cluster = vars.clusterName.trim() || PLACEHOLDERS.clusterName;
  const rg = vars.resourceGroup.trim() || PLACEHOLDERS.resourceGroup;

  const oneLiner = [
    `curl -sSL ${scriptUrl} | bash -s -- \\`,
    `  --cluster ${cluster} \\`,
    `  --resource-group ${rg} \\`,
    `  --server-url ${config.serverUrl} \\`,
    `  --api-key ${config.apiKey}`,
  ].join('\n');

  const allClusters = [
    `curl -sSL ${scriptUrl} | bash -s -- \\`,
    `  --all \\`,
    `  --server-url ${config.serverUrl} \\`,
    `  --api-key ${config.apiKey}`,
  ].join('\n');

  const inspectFirst = [
    `# For environments where piping to bash is not permitted.`,
    `curl -sSL -o xamops-install-aks.sh ${scriptUrl}`,
    `less xamops-install-aks.sh          # review it`,
    `chmod +x xamops-install-aks.sh`,
    `./xamops-install-aks.sh --cluster ${cluster} --resource-group ${rg} \\`,
    `  --server-url ${config.serverUrl} --api-key ${config.apiKey}`,
  ].join('\n');

  return (
    <div className="space-y-4">
      <div className="p-4 bg-gradient-to-r from-indigo-600 to-violet-600 rounded-xl text-white">
        <h3 className="text-[14px] font-bold mb-1 flex items-center gap-2">
          <Icon icon="mdi:flash-outline" width={16} /> One command, per cluster
        </h3>
        <p className="text-[12px] text-indigo-100">
          Runs every step in Parts 1&ndash;3 and then performs the Part 4 checks.{' '}
          <strong className="text-white">This is the whole setup — there is nothing to run afterwards.</strong>{' '}
          Safe to run as many times as you like: re-running is how you update a cluster, not
          something to avoid.
        </p>
      </div>

      <div className="p-3 bg-emerald-50 border border-emerald-200 rounded-xl text-[12px] text-emerald-800 flex items-start gap-2">
        <Icon icon="mdi:refresh" width={14} className="mt-0.5 flex-shrink-0" />
        <div>
          <strong>Already ran an earlier version of this guide? Nothing to undo.</strong> Just run the
          command below. Helm upgrades each release in place and the agent rolls over to the current
          image, so an old cluster converges onto the current stack. The installer also clears the few
          leftovers that cannot fix themselves — the unpublished <code className="bg-emerald-100 px-1 rounded">cost-exporter</code>,
          a Falco release stranded by the old <code className="bg-emerald-100 px-1 rounded">driver.kind=ebpf</code> value,
          and a stale <code className="bg-emerald-100 px-1 rounded">PROMETHEUS_URL</code> pointing at port 9090.
        </div>
      </div>

      <CollapsibleSection title="Run for one cluster" icon="mdi:cube-outline" iconColor="#4F46E5" defaultOpen>
        <VarNote vars={[PLACEHOLDERS.clusterName, PLACEHOLDERS.resourceGroup]} />
        <CodeBlock code={oneLiner} />
        <p className="text-[11px] text-[rgba(0,0,0,0.5)] mt-1">
          Paste into Azure Cloud Shell. Takes roughly 5&ndash;10 minutes; most of it is waiting for
          Prometheus to bind its volume and for the scanners to start.
        </p>
      </CollapsibleSection>

      <CollapsibleSection title="Run for every cluster in the subscription" icon="mdi:apps" iconColor="#0891B2">
        <CodeBlock code={allClusters} />
        <div className="p-3 bg-blue-50 border border-blue-100 rounded-xl text-[12px] text-blue-800 flex items-start gap-2">
          <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
          <div>
            Discovers the clusters itself, so there is nothing to fill in above. Clusters whose
            provisioning state is not <code className="bg-blue-100 px-1 rounded">Succeeded</code> are skipped
            with a reason, and a failure on one cluster does not stop the others — the summary at the
            end reports per cluster.
          </div>
        </div>
      </CollapsibleSection>

      <CollapsibleSection title="Preview without changing anything" icon="mdi:eye-outline" iconColor="#D97706">
        <CodeBlock code={`${oneLiner} \\\n  --dry-run`} />
        <p className="text-[11px] text-[rgba(0,0,0,0.5)] mt-1">
          Prints every command and every manifest, and touches nothing. Useful for a change request.
        </p>
      </CollapsibleSection>

      <CollapsibleSection title="Download and inspect before running" icon="mdi:shield-lock-outline" iconColor="#DC2626">
        <CodeBlock code={inspectFirst} />
      </CollapsibleSection>

      <div className="p-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.08)] rounded-xl text-[12px] text-[rgba(0,0,0,0.6)]">
        <div className="font-semibold text-[rgba(0,0,0,0.75)] mb-1.5 flex items-center gap-2">
          <Icon icon="mdi:format-list-checks" width={14} /> What it installs
        </div>
        <ul className="space-y-1 mb-2">
          {[
            ['Step 1.1', 'AKS credentials, and a check that kubectl is pointed at the intended cluster'],
            ['Step 1.2', 'monitoring, falco and opencost namespaces'],
            ['Step 1.3', 'Prometheus, with node-exporter and kube-state-metrics'],
            ['Step 1.4', 'Falco with FalcoSidekick'],
            ['Step 1.5', 'OpenCost'],
            ['Step 2.1', 'Trivy Operator'],
            ['Step 2.2', 'Kubescape, plus a first scan so results appear today rather than tomorrow'],
            ['Step 3.1', 'read-only RBAC for the agent'],
            ['Step 3.2', 'agent ConfigMap and Secret for this environment'],
            ['Step 3.3', `the agent itself (${AGENT_IMAGE_TAG})`],
            ['Part 4', 'verification of all of the above'],
          ].map(([step, what]) => (
            <li key={step} className="flex items-start gap-2">
              <Icon icon="mdi:check" width={12} color="#16A34A" className="mt-1 flex-shrink-0" />
              <span><code className="bg-[rgba(0,0,0,0.05)] px-1 rounded">{step}</code> — {what}</span>
            </li>
          ))}
        </ul>
        Run with <code className="bg-[rgba(0,0,0,0.05)] px-1 rounded">--dry-run</code> to see each command
        filled in with your values, or open the manual tabs to read them one step at a time.
      </div>

      <div className="p-3 bg-amber-50 border border-amber-200 rounded-xl text-[12px] text-amber-800 flex items-start gap-2">
        <Icon icon="mdi:alert-outline" width={14} className="mt-0.5 flex-shrink-0" />
        <div>
          Requires <code className="bg-amber-100 px-1 rounded">az</code>,{' '}
          <code className="bg-amber-100 px-1 rounded">kubectl</code> and{' '}
          <code className="bg-amber-100 px-1 rounded">helm</code> 3.12+, and an account with
          cluster-admin. Azure Cloud Shell has all three. The installer checks before it starts and
          stops with a clear message rather than failing halfway.
        </div>
      </div>
    </div>
  );
};

/**
 * Shown at the top of every manual Part.
 *
 * Without it the tab strip reads "Quick Install · Overview · Part 1 · Part 2 …", which looks like
 * a sequence to work through rather than two alternative routes. The first question a user asked
 * on seeing the new tab was whether the Parts still had to be run afterwards.
 */
const ManualStepsNotice: React.FC<{ part: string }> = ({ part }) => (
  <div className="p-3 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.1)] rounded-xl text-[12px] text-[rgba(0,0,0,0.6)] flex items-start gap-2">
    <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
    <div>
      <strong className="text-[rgba(0,0,0,0.75)]">Reference — Quick Install already performs {part}.</strong>{' '}
      Run these commands only if you would rather install by hand, or if you are reviewing exactly
      what the installer does. Doing both is harmless but unnecessary.
    </div>
  </div>
);

const OverviewTab: React.FC<{ serverUrl: string }> = ({ serverUrl }) => (
  <div className="space-y-5">
    <div className="p-5 bg-blue-50 border border-blue-100 rounded-xl">
      <h3 className="text-[14px] font-bold text-blue-800 mb-2 flex items-center gap-2">
        <Icon icon="mdi:shield-check-outline" width={15} color="#1D4ED8" />
        Push-Only Architecture — Zero Inbound Required
      </h3>
      <p className="text-[13px] text-blue-700">
        The XamOps agent runs inside your AKS cluster and pushes data outbound to{' '}
        <code className="bg-blue-100 px-1 rounded">{hostOf(serverUrl)}</code>.
        Your cluster never receives inbound connections from XamOps. Works for private AKS clusters,
        VNet-isolated environments, and any Azure network security posture.
      </p>
    </div>

    <div className="bg-[#1a1d27] text-[#86efac] p-5 rounded-xl font-mono text-[12px] overflow-x-auto">
      <pre>{`YOUR AKS CLUSTER
├── monitoring namespace
│   ├── prometheus-server        ← scrapes all in-cluster metrics
│   ├── kube-state-metrics       ← kube_* metrics
│   ├── node-exporter (DaemonSet)← node_cpu, node_memory
│   ├── falco + falcosidekick    ← security events → Prometheus
│   └── opencost                 ← cost allocation
├── xamops-system namespace
│   └── xamops-agent             ← reads K8s API + queries Prometheus
│       │                           PUSH → ${hostOf(serverUrl)}
│       └── collectors: k8s-resources, prometheus-metrics, trivy, kubescape, opencost`}</pre>
    </div>

    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-5">
      <h4 className="font-bold text-[rgba(0,0,0,0.75)] mb-4 flex items-center gap-2 text-[13px]">
        <Icon icon="mdi:sync" width={14} color="#6366F1" /> How Data Flows
      </h4>
      <div className="space-y-3">
        {[
          { n: '1', color: 'bg-blue-500',   text: 'Prometheus scrapes KSM, node-exporter, cadvisor, Falco, and OpenCost — all inside the cluster.' },
          { n: '2', color: 'bg-indigo-500', text: 'XamOps Agent queries Prometheus in-cluster and reads the Kubernetes API (nodes, pods, deployments, HPAs, PVCs, events…).' },
          { n: '3', color: 'bg-purple-500', text: `Agent POSTs a JSON payload to ${hostOf(serverUrl)}/api/v1/agent/ingest (HTTPS, outbound).` },
          { n: '4', color: 'bg-emerald-500',text: 'XamOps backend stores data keyed by cluster. Dashboard reads from cache — no direct cluster access needed.' },
        ].map(({ n, color, text }) => (
          <div key={n} className="flex items-start gap-3">
            <div className={`${color} text-white rounded-full w-6 h-6 flex items-center justify-center text-[11px] font-bold flex-shrink-0 mt-0.5`}>{n}</div>
            <p className="text-[13px] text-[rgba(0,0,0,0.65)]">{text}</p>
          </div>
        ))}
      </div>
    </div>

    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
      {[
        { part: 'Part 1', color: 'blue',   icon: 'mdi:chart-line',             title: 'Monitoring Stack',  desc: 'Prometheus + KSM + node-exporter + Falco + OpenCost' },
        { part: 'Part 2', color: 'purple', icon: 'mdi:shield-check-outline',   title: 'Security Tools',    desc: 'Trivy (image vuln scanning) + Kubescape (compliance)' },
        { part: 'Part 3', color: 'emerald',icon: 'mdi:robot-outline',           title: 'XamOps Agent',      desc: 'Read-only observability agent — pushes cluster data to XamOps every 5 min' },
        { part: 'Part 4', color: 'indigo', icon: 'mdi:check-circle-outline',   title: 'Verification',      desc: 'Confirm all pods are running and data appears in XamOps dashboard' },
      ].map(({ part, icon, title, desc }) => (
        <div key={part} className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-4">
          <p className="text-[11px] font-bold text-[rgba(0,0,0,0.35)] mb-1">{part}</p>
          <div className="flex items-center gap-1.5 mb-1">
            <Icon icon={icon} width={13} color="#6366F1" />
            <p className="font-semibold text-[rgba(0,0,0,0.75)] text-[13px]">{title}</p>
          </div>
          <p className="text-[11px] text-[rgba(0,0,0,0.45)]">{desc}</p>
        </div>
      ))}
    </div>
  </div>
);

const Part1Tab: React.FC = () => (
  <div className="space-y-4">
    <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Part 1: In-Cluster Monitoring Stack</h3>
    <ManualStepsNotice part="these steps" />
    <div className="p-3 bg-blue-50 border border-blue-100 rounded-xl text-[12px] text-blue-800 flex items-start gap-2">
      <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
      All monitoring tools run in the <code className="bg-blue-100 px-1 rounded">monitoring</code> namespace. Prometheus scrapes KSM, node-exporter, Falco, and OpenCost.
    </div>

    <CollapsibleSection title="Step 1.1: Get AKS Credentials" icon="mdi:key-outline" iconColor="#0891B2">
      <VarNote vars={['<RESOURCE_GROUP>', '<CLUSTER_NAME>']} />
      <CodeBlock code={`az aks get-credentials \\
  --resource-group <RESOURCE_GROUP> \\
  --name <CLUSTER_NAME>

kubectl get nodes`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.2: Create Namespaces" icon="mdi:folder-outline">
      <CodeBlock code={`kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace falco      --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace opencost   --dry-run=client -o yaml | kubectl apply -f -`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.3: Install Prometheus" icon="mdi:fire" iconColor="#EA580C">
      <CodeBlock code={`helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update`} />
      <CodeBlock title="prometheus-values.yaml" code={`cat > prometheus-values.yaml << 'EOF'
server:
  persistentVolume:
    enabled: true
    size: 20Gi
  retention: "15d"
alertmanager:
  enabled: false
pushgateway:
  enabled: false
nodeExporter:
  enabled: true
kube-state-metrics:
  enabled: true
extraScrapeConfigs: |
  - job_name: falco-sidekick
    static_configs:
      - targets: ['falco-falcosidekick.falco.svc.cluster.local:2801']
  - job_name: opencost
    static_configs:
      - targets: ['opencost.opencost.svc.cluster.local:9003']
EOF`} />
      <CodeBlock code={`helm upgrade --install prometheus prometheus-community/prometheus \\
  --namespace monitoring \\
  --values prometheus-values.yaml \\
  --set server.persistentVolume.storageClass=managed-csi`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.4: Install Falco + FalcoSidekick" icon="mdi:eye-outline" iconColor="#7C3AED">
      <CodeBlock code={`helm repo add falcosecurity https://falcosecurity.github.io/charts
helm repo update

helm upgrade --install falco falcosecurity/falco \\
  --namespace falco \\
  --set driver.kind=modern_ebpf \\
  --set falco.json_output=true \\
  --set falcosidekick.enabled=true \\
  --set falcosidekick.config.prometheus.enabled=true \\
  --set falcosidekick.service.port=2801 \\
  --set falcosidekick.replicaCount=1 \\
  --set 'tolerations[0].operator=Exists'`} />
      <div className="p-3 bg-purple-50 border border-purple-100 rounded-xl text-[12px] text-purple-800 flex items-start gap-2">
        <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
        <div>
          <strong>Driver:</strong> Falco 0.44 removed the legacy eBPF probe, so <code className="bg-purple-100 px-1 rounded">driver.kind=ebpf</code> is
          rejected at chart render time. Use <code className="bg-purple-100 px-1 rounded">modern_ebpf</code> (CO-RE, needs kernel 5.8+) — AKS Ubuntu
          node images qualify. Fall back to <code className="bg-purple-100 px-1 rounded">kmod</code> only on kernels older than 5.8.
          <br />
          <strong>Single sidekick replica</strong> is deliberate: the scrape config above targets the Service by DNS, so multiple replicas would
          split counter metrics across pods and make{' '}
          <code className="bg-purple-100 px-1 rounded">falcosecurity_falcosidekick_falco_events_total</code> jump around.
        </div>
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 1.5: Install OpenCost" icon="mdi:cash-multiple" iconColor="#D97706">
      <VarNote vars={['<AZURE_SUBSCRIPTION_ID>']} />
      <CodeBlock title="opencost-values.yaml" code={`cat > opencost-values.yaml << 'EOF'
opencost:
  prometheus:
    internal:
      enabled: true
      serviceName: prometheus-server
      namespaceName: monitoring
      port: 80
  cloudCost:
    enabled: true
  exporter:
    azure:
      subscriptionId: "<AZURE_SUBSCRIPTION_ID>"
EOF`} />
      <CodeBlock code={`helm repo add opencost https://opencost.github.io/opencost-helm-chart
helm repo update
helm upgrade --install opencost opencost/opencost \\
  --namespace opencost \\
  --values opencost-values.yaml`} />
    </CollapsibleSection>
  </div>
);

const Part2Tab: React.FC = () => (
  <div className="space-y-4">
    <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Part 2: Security Tools</h3>
    <ManualStepsNotice part="these steps" />
    <div className="p-3 bg-purple-50 border border-purple-100 rounded-xl text-[12px] text-purple-800 flex items-start gap-2">
      <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
      Trivy creates <code className="bg-purple-100 px-1 rounded">VulnerabilityReport</code> CRDs. Kubescape creates <code className="bg-purple-100 px-1 rounded">WorkloadConfigurationScanSummary</code> CRDs. The XamOps Agent reads these and pushes them to the dashboard.
    </div>

    <CollapsibleSection title="Step 2.1: Install Trivy Operator" icon="mdi:bug-outline" iconColor="#DC2626">
      <CodeBlock code={`helm repo add aquasecurity https://aquasecurity.github.io/helm-charts/
helm repo update

helm upgrade --install trivy-operator aquasecurity/trivy-operator \\
  --namespace trivy-system --create-namespace \\
  --set trivy.ignoreUnfixed=false \\
  --set 'trivy.severity=UNKNOWN\\,LOW\\,MEDIUM\\,HIGH\\,CRITICAL' \\
  --set operator.vulnerabilityScanner.enabled=true \\
  --set operator.configAuditScannerEnabled=true

# Verify (wait 5–10 min for initial scan)
kubectl get vulnerabilityreports -A --no-headers | head -20`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 2.2: Install Kubescape" icon="mdi:clipboard-check-outline" iconColor="#3B82F6">
      <VarNote vars={['<CLUSTER_NAME>']} />
      <CodeBlock code={`helm repo add kubescape https://kubescape.github.io/helm-charts/
helm repo update

helm upgrade --install kubescape kubescape/kubescape-operator \\
  --namespace kubescape --create-namespace \\
  --set clusterName=<CLUSTER_NAME> \\
  --set capabilities.continuousScan=enable \\
  --set storage.enabled=true

# Do NOT set --set account=<uuid>: an account makes kubescape ship results to
# Kubescape Cloud instead of writing them to in-cluster storage, which is where
# the XamOps agent reads them from.

# Configuration scans run on a daily schedule. To get results now rather than
# waiting for it, trigger one:
kubectl -n kubescape create job --from=cronjob/kubescape-scheduler ks-initial-scan
kubectl -n kubescape wait --for=condition=complete job/ks-initial-scan --timeout=300s

# Verify (wait 3–5 min)
kubectl get pods -n kubescape

# Counting rows is NOT enough: kubescape creates one summary per workload as soon
# as it sees it, with 'controls: null' until scan results are written. Check that
# controls are actually populated.
kubectl get workloadconfigurationscansummaries -A -o json \\
  | jq '{summaries: (.items | length), withControls: ([.items[] | select(.spec.controls != null)] | length)}'`} />
      <div className="p-3 bg-amber-50 border border-amber-200 rounded-xl text-[12px] text-amber-800 flex items-start gap-2">
        <Icon icon="mdi:alert-outline" width={14} className="mt-0.5 flex-shrink-0" />
        <div>
          <strong>If <code className="bg-amber-100 px-1 rounded">withControls</code> is 0</strong> while{' '}
          <code className="bg-amber-100 px-1 rounded">summaries</code> is high, the scan is running but its
          results are not reaching in-cluster storage. Confirm with{' '}
          <code className="bg-amber-100 px-1 rounded">kubectl -n kubescape logs deploy/kubescape --tail=60</code> —
          a findings table there alongside empty summaries means the storage write path, not the scan, is at
          fault. XamOps reports this as <em>&ldquo;No Scan Results Yet&rdquo;</em> rather than as compliance.
          <br />
          Do <strong>not</strong> set <code className="bg-amber-100 px-1 rounded">KS_KEEP_LOCAL=true</code> to
          work around it: that keeps results in a file inside the pod and guarantees the summaries stay empty.
        </div>
      </div>
    </CollapsibleSection>
  </div>
);

const Part3Tab: React.FC<{ config: AgentInstallConfig }> = ({ config }) => (
  <div className="space-y-4">
    <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Part 3: XamOps Observability Agent</h3>
    <ManualStepsNotice part="these steps" />
    <div className="p-3 bg-emerald-50 border border-emerald-100 rounded-xl text-[12px] text-emerald-800 flex items-start gap-2">
      <Icon icon="mdi:robot-outline" width={14} className="mt-0.5 flex-shrink-0" />
      Same agent binary as EKS and GKE. Set <code className="bg-emerald-100 px-1 rounded">CLOUD_PROVIDER: "AZURE"</code> and <code className="bg-emerald-100 px-1 rounded">COLLECTOR_KARPENTER: "false"</code> (Karpenter is AWS-only).
    </div>

    <div className="p-3 bg-slate-50 border border-slate-200 rounded-xl text-[12px] text-slate-700 flex items-start gap-2">
      <Icon icon="mdi:server-network" width={14} className="mt-0.5 flex-shrink-0" />
      <div>
        This manifest targets <code className="bg-slate-200 px-1 rounded">{config.serverUrl}</code> — the
        environment you are currently signed in to. Data pushed here is only visible in this
        environment; each one has its own database.
      </div>
    </div>

    {!config.reachableFromCluster && (
      <div className="p-3 bg-amber-50 border border-amber-200 rounded-xl text-[12px] text-amber-800 flex items-start gap-2">
        <Icon icon="mdi:alert-outline" width={14} className="mt-0.5 flex-shrink-0" />
        <div>
          <strong>This URL is not reachable from inside a cluster.</strong>{' '}
          <code className="bg-amber-100 px-1 rounded">{config.serverUrl}</code> resolves to the machine
          running the agent, not to your development machine, so the agent will never connect.
          Expose your local backend with a tunnel and substitute the public URL below:
          <div className="mt-2 font-mono text-[11px] bg-amber-100 rounded p-2">
            cloudflared tunnel --url http://localhost:8080
          </div>
        </div>
      </div>
    )}

    <CollapsibleSection title="Step 3.1: Create RBAC (read-only)" icon="mdi:key-outline" iconColor="#D97706">
      <CodeBlock title="xamops-agent-rbac.yaml" code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: Namespace
metadata:
  name: xamops-system
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: xamops-agent-sa
  namespace: xamops-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: xamops-agent-role
rules:
  - apiGroups: [""]
    resources: [pods, nodes, namespaces, services, endpoints, persistentvolumes,
                persistentvolumeclaims, resourcequotas, limitranges, serviceaccounts,
                events, configmaps]
    verbs: [get, list, watch]
  - apiGroups: [apps]
    resources: [deployments, daemonsets, statefulsets, replicasets]
    verbs: [get, list, watch]
  - apiGroups: [batch]
    resources: [jobs, cronjobs]
    verbs: [get, list, watch]
  - apiGroups: [networking.k8s.io]
    resources: [ingresses, networkpolicies]
    verbs: [get, list, watch]
  - apiGroups: [autoscaling]
    resources: [horizontalpodautoscalers]
    verbs: [get, list, watch]
  - apiGroups: [autoscaling.k8s.io]
    resources: [verticalpodautoscalers]
    verbs: [get, list, watch]
  - apiGroups: [metrics.k8s.io]
    resources: [nodes, pods]
    verbs: [get, list]
  - apiGroups: [storage.k8s.io]
    resources: [storageclasses]
    verbs: [get, list, watch]
  - apiGroups: [apiextensions.k8s.io]
    resources: [customresourcedefinitions]
    verbs: [get, list]
  - apiGroups: [aquasecurity.github.io]
    resources: [vulnerabilityreports, configauditreports, exposedsecretreports,
                rbacassessmentreports, infraassessmentreports, clustercompliancereports,
                clusterconfigauditreports, clusterinfraassessmentreports,
                clusterrbacassessmentreports]
    verbs: [get, list, watch]
  - apiGroups: [spdx.softwarecomposition.kubescape.io]
    resources: [configurationscansummaries, vulnerabilitymanifestsummaries,
                workloadconfigurationscans, workloadconfigurationscansummaries,
                vulnerabilitymanifests, applicationprofiles, applicationactivities,
                networkneighborhoods, generatednetworkpolicies, knownservers,
                openvulnerabilityexchangecontainers, seccompprofiles]
    verbs: [get, list, watch]
  - apiGroups: [kubescape.io]
    resources: [workloadconfigurationscans, workloadconfigurationscansummaries]
    verbs: [get, list, watch]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: xamops-agent-binding
subjects:
  - kind: ServiceAccount
    name: xamops-agent-sa
    namespace: xamops-system
roleRef:
  kind: ClusterRole
  name: xamops-agent-role
  apiGroup: rbac.authorization.k8s.io
EOF`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.2: Create ConfigMap and Secret" icon="mdi:cog-outline" iconColor="#3B82F6">
      <VarNote vars={['<CLUSTER_NAME>', '<AZURE_SUBSCRIPTION_ID>']} />
      <CodeBlock title="xamops-agent-config.yaml" code={`kubectl apply -f - << 'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: xamops-agent-config
  namespace: xamops-system
data:
  COLLECT_INTERVAL: "300"
  PUSH_INTERVAL: "300"
  HEARTBEAT_INTERVAL: "60"
  XAMOPS_SERVER_URL: "${config.serverUrl}"
  CLUSTER_NAME: "<CLUSTER_NAME>"
  CLOUD_ACCOUNT_ID: "<AZURE_SUBSCRIPTION_ID>"
  CLOUD_PROVIDER: "AZURE"
  COLLECTOR_K8S_RESOURCES: "true"
  COLLECTOR_PROMETHEUS_METRICS: "true"
  # The prometheus-community chart's server Service listens on 80, not 9090.
  PROMETHEUS_URL: "http://prometheus-server.monitoring.svc.cluster.local:80"
  COLLECTOR_TRIVY: "true"
  COLLECTOR_KUBESCAPE: "true"
  COLLECTOR_OPENCOST: "true"
  COLLECTOR_KARPENTER: "false"
---
apiVersion: v1
kind: Secret
metadata:
  name: xamops-agent-secret
  namespace: xamops-system
type: Opaque
stringData:
  XAMOPS_API_KEY: "${config.apiKey}"
EOF`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 3.3: Deploy XamOps Agent" icon="mdi:rocket-launch-outline" iconColor="#16A34A">
      <CodeBlock title="xamops-agent-deployment.yaml" code={`kubectl apply -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: xamops-agent
  namespace: xamops-system
spec:
  replicas: 1
  selector:
    matchLabels:
      app: xamops-agent
  template:
    metadata:
      labels:
        app: xamops-agent
    spec:
      serviceAccountName: xamops-agent-sa
      containers:
        - name: xamops-agent
          image: docker.io/xamops/agent:${AGENT_IMAGE_TAG}
          imagePullPolicy: Always
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 500m
              memory: 256Mi
          envFrom:
            - configMapRef:
                name: xamops-agent-config
            - secretRef:
                name: xamops-agent-secret
EOF`} />
      <CodeBlock code={`kubectl get pods -n xamops-system
kubectl logs -n xamops-system -l app=xamops-agent --tail=50`} />
    </CollapsibleSection>
  </div>
);

const Part4Tab: React.FC = () => (
  <div className="space-y-4">
    <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Part 4: Verification</h3>
    <ManualStepsNotice part="these checks" />

    <CollapsibleSection title="Step 4.1: Verify All Pods Are Running" icon="mdi:check-circle-outline" iconColor="#16A34A">
      <CodeBlock code={`kubectl get pods -n monitoring
kubectl get pods -n falco
kubectl get pods -n opencost
kubectl get pods -n trivy-system
kubectl get pods -n kubescape
kubectl get pods -n xamops-system`} />
    </CollapsibleSection>

    <CollapsibleSection title="Step 4.2: Verify Prometheus Has Data" icon="mdi:fire" iconColor="#EA580C">
      <CodeBlock code={`kubectl port-forward -n monitoring svc/prometheus-server 9090:80`} />
      <p className="text-[12px] text-[rgba(0,0,0,0.55)] mt-2">Open <code>http://localhost:9090</code> and query:</p>
      <div className="space-y-1.5 mt-2">
        {[
          'node_cpu_seconds_total',
          'container_cpu_usage_seconds_total',
          'kube_node_status_capacity',
          'falcosecurity_falcosidekick_falco_events_total',
        ].map(m => (
          <div key={m} className="flex items-center gap-2 bg-[rgba(0,0,0,0.02)] rounded-lg p-2 text-[12px]">
            <Icon icon="mdi:check" width={12} color="#16A34A" />
            <code className="text-orange-700">{m}</code>
          </div>
        ))}
      </div>
    </CollapsibleSection>

    <CollapsibleSection title="Step 4.3: Verify Agent Is Pushing Data" icon="mdi:robot-outline" iconColor="#3B82F6">
      <CodeBlock code={`kubectl logs -n xamops-system -l app=xamops-agent --tail=100 | grep -i "push\\|ingest\\|success"
# Expected: INFO: Successfully processed N items from agent for cluster: <CLUSTER_NAME>`} />
    </CollapsibleSection>

    <div className="p-4 bg-indigo-50 border border-indigo-100 rounded-xl">
      <h5 className="font-semibold text-indigo-800 mb-3 flex items-center gap-2 text-[13px]">
        <Icon icon="mdi:view-dashboard-outline" width={14} color="#4F46E5" />
        After agent pushes, check these dashboard sections:
      </h5>
      <ul className="space-y-1.5 text-[12px] text-indigo-700">
        {['Cluster Overview — Node count, pod count, CPU/memory %',
          'Nodes tab — Per-node utilization, agent pool info',
          'Pods tab — Pod list with status, restarts, resource usage',
          'Security tab — Trivy CVEs + Kubescape compliance + Falco events',
          'Cost tab — Azure VM node cost, namespace cost allocation (OpenCost)'].map(item => (
          <li key={item} className="flex items-start gap-2">
            <Icon icon="mdi:check-circle-outline" width={13} color="#4F46E5" className="mt-0.5 flex-shrink-0" />
            {item}
          </li>
        ))}
      </ul>
      <p className="text-[11px] text-indigo-500 mt-3 flex items-center gap-1">
        <Icon icon="mdi:clock-outline" width={12} />
        First data appears within ~5 min. Cost data may take 15–20 min.
      </p>
    </div>
  </div>
);

export default AzureCloudK8sHelpModal;
