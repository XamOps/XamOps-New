import React, { useState, useEffect } from 'react';
import { useGatewayData } from '../../hooks/useGatewayData';
import { useKeyGroups } from '../../hooks/useKeyGroups';
import { useGroupKeys, type ProviderKey } from '../../hooks/useProviderKeys';
import type { LlmProviderConfig } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';

const PROVIDER_MODELS: Record<string, string[]> = {
  OPENAI:    ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'gpt-3.5-turbo', 'o1', 'o3-mini'],
  ANTHROPIC: ['claude-sonnet-4-5-20250929', 'claude-opus-4-5-20250929', 'claude-3-5-sonnet-20241022', 'claude-3-5-haiku-20241022', 'claude-3-haiku-20240307'],
  GEMINI:    ['gemini-2.0-flash', 'gemini-1.5-pro', 'gemini-1.5-flash'],
  BEDROCK:   ['anthropic.claude-3-5-sonnet-20241022-v2:0', 'amazon.titan-text-express-v1', 'meta.llama3-70b-instruct-v1:0'],
};

// Returns only the models the user enabled for this provider config.
// Falls back to the full PROVIDER_MODELS list when enabledModels is not set (legacy configs).
function getEnabledModels(p: LlmProviderConfig): string[] {
  if (p.enabledModels && p.enabledModels.trim().length > 0) {
    return p.enabledModels.split(',').map(m => m.trim()).filter(Boolean);
  }
  return PROVIDER_MODELS[p.provider?.toUpperCase()] ?? [];
}

function Tooltip({ text, children }: { text: string; children: React.ReactNode }) {
  return (
    <span className="relative group/tip inline-flex items-center">
      {children}
      <span className="pointer-events-none absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-56 rounded-lg bg-gray-900 text-white text-[11px] leading-snug px-3 py-2 shadow-lg opacity-0 group-hover/tip:opacity-100 transition-opacity z-50 text-center">
        {text}
        <span className="absolute top-full left-1/2 -translate-x-1/2 border-4 border-transparent border-t-gray-900" />
      </span>
    </span>
  );
}

function InfoIcon({ tip }: { tip: string }) {
  return (
    <Tooltip text={tip}>
      <i className="fas fa-info-circle text-gray-400 hover:text-indigo-500 text-xs cursor-help ml-1" />
    </Tooltip>
  );
}

function StatusDot({ online }: { online: boolean }) {
  return <span className={`inline-block w-2.5 h-2.5 rounded-full ${online ? 'bg-green-500' : 'bg-red-400'}`} />;
}

function CopyButton({ text, label }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const handle = () => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  return (
    <button onClick={handle} className="flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium text-gray-500 hover:text-indigo-600 bg-gray-100 hover:bg-indigo-50 rounded-lg transition-colors">
      <i className={copied ? 'fas fa-check text-green-500' : 'fas fa-copy'} />
      {label ? (copied ? 'Copied!' : label) : (copied ? 'Copied!' : 'Copy')}
    </button>
  );
}

function CodeBlock({ code }: { code: string }) {
  return (
    <div className="relative group">
      <pre className="bg-gray-900 text-green-400 text-xs rounded-xl p-4 overflow-x-auto leading-relaxed font-mono"><code>{code}</code></pre>
      <div className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity">
        <CopyButton text={code} />
      </div>
    </div>
  );
}

const STRATEGY_OPTIONS = [
  { value: 'ROUND_ROBIN',        label: 'Round Robin',         tip: 'Rotate evenly across all active keys' },
  { value: 'LEAST_REQUESTS',     label: 'Least Requests',      tip: 'Always use the key with fewest in-flight requests' },
  { value: 'RATE_LIMIT_AWARE',   label: 'Rate Limit Aware',    tip: 'Skip keys that are cooling down after a 429' },
];

const KEY_STATUS_BADGE: Record<string, { bg: string; text: string }> = {
  ACTIVE:       { bg: 'bg-emerald-100', text: 'text-emerald-700' },
  RATE_LIMITED: { bg: 'bg-amber-100',   text: 'text-amber-700'   },
  DISABLED:     { bg: 'bg-gray-100',    text: 'text-gray-500'    },
};

const PROVIDER_OPTIONS = ['OPENAI', 'ANTHROPIC', 'GEMINI', 'BEDROCK'];

function GroupKeyPool({ groupId, strategy: initialStrategy, onStrategyChange }: {
  groupId: number;
  strategy: string;
  onStrategyChange: () => void;
}) {
  const { keys, loading, fetchKeys, addKey, rotateKey, deleteKey, updateKeyStatus, updateStrategy } = useGroupKeys(groupId);
  const [expanded, setExpanded] = useState(false);
  const [addingKey, setAddingKey] = useState(false);
  const [newKeyValue, setNewKeyValue] = useState('');
  const [newKeyProvider, setNewKeyProvider] = useState(PROVIDER_OPTIONS[0]);
  const [saving, setSaving] = useState(false);
  const [rotatingId, setRotatingId] = useState<number | null>(null);
  const [rotateValue, setRotateValue] = useState('');
  const [strategy, setStrategy] = useState(initialStrategy || 'ROUND_ROBIN');
  const [strategyChanged, setStrategyChanged] = useState(false);

  useEffect(() => {
    if (expanded && keys.length === 0 && !loading) fetchKeys();
  }, [expanded]);

  const handleAddKey = async () => {
    if (!newKeyValue.trim()) return;
    setSaving(true);
    try {
      await addKey(newKeyProvider, newKeyValue.trim());
      setNewKeyValue('');
      setAddingKey(false);
    } finally {
      setSaving(false);
    }
  };

  const handleRotate = async (keyId: number) => {
    if (!rotateValue.trim()) return;
    setSaving(true);
    try {
      await rotateKey(keyId, rotateValue.trim());
      setRotatingId(null);
      setRotateValue('');
    } finally {
      setSaving(false);
    }
  };

  const handleStrategyChange = async (val: string) => {
    setStrategy(val);
    setStrategyChanged(true);
    await updateStrategy(val);
    onStrategyChange();
    setTimeout(() => setStrategyChanged(false), 1500);
  };

  return (
    <div className="mt-4 border border-indigo-100 rounded-xl bg-indigo-50/40">
      <Tooltip text="The Key Pool stores your real provider API keys encrypted. XamOps uses them automatically — callers never need to send their own API key. Add 2+ keys for the same provider to enable load balancing.">
        <button
          onClick={() => setExpanded(e => !e)}
          className="w-full flex items-center justify-between px-4 py-2.5 text-xs font-semibold text-indigo-700 hover:bg-indigo-50 rounded-xl transition-colors"
        >
          <span className="flex items-center gap-2">
            <i className="fas fa-layer-group text-indigo-400" />
            Key Pool
            {!loading && keys.length > 0 && (
              <span className={`px-1.5 py-0.5 rounded-full text-[10px] ${keys.length > 1 ? 'bg-emerald-200 text-emerald-800' : 'bg-indigo-200 text-indigo-800'}`}>
                {keys.length} key{keys.length !== 1 ? 's' : ''}{keys.length > 1 ? ' · LB active' : ''}
              </span>
            )}
            {!loading && keys.length === 0 && (
              <span className="text-[10px] text-amber-600 font-normal">· no keys yet · load balancing inactive</span>
            )}
          </span>
          <span className="flex items-center gap-2">
            <span className="text-[10px] font-normal text-indigo-400">{strategy.replace(/_/g, ' ')}</span>
            <i className={`fas fa-chevron-${expanded ? 'up' : 'down'} text-indigo-400 text-[10px]`} />
          </span>
        </button>
      </Tooltip>

      {expanded && (
        <div className="px-4 pb-4 space-y-3">

          {/* Strategy selector */}
          <div className="flex items-center gap-2 flex-wrap pt-1">
            <span className="text-[11px] text-gray-500 font-medium flex items-center gap-1">
              Load Balance:
              <InfoIcon tip="How XamOps picks a key when multiple keys are in the pool for the same provider. Has no effect with 0 or 1 key." />
            </span>
            {STRATEGY_OPTIONS.map(opt => (
              <Tooltip key={opt.value} text={opt.tip}>
                <button
                  onClick={() => handleStrategyChange(opt.value)}
                  className={`text-[11px] px-2.5 py-1 rounded-lg border font-medium transition-all ${
                    strategy === opt.value
                      ? 'bg-indigo-600 text-white border-indigo-600'
                      : 'bg-white text-gray-600 border-gray-200 hover:border-indigo-300'
                  }`}>
                  {opt.label}
                </button>
              </Tooltip>
            ))}
            {strategyChanged && (
              <span className="text-[11px] text-emerald-600"><i className="fas fa-check mr-1" />Saved</span>
            )}
          </div>

          {/* Keys list */}
          {loading ? (
            <p className="text-xs text-gray-400">Loading keys…</p>
          ) : keys.length === 0 ? (
            <div className="bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
              <p className="text-xs text-amber-700 font-medium">No keys in pool yet — load balancing is inactive.</p>
              <p className="text-[11px] text-amber-600 mt-0.5">
                Add 2+ keys for the same provider below. XamOps will auto-select using the strategy above.
                Without pool keys, callers must send their own <code className="bg-amber-100 px-1 rounded">Authorization: Bearer</code> header.
              </p>
            </div>
          ) : (
            <div className="space-y-2">
              {keys.map((k: ProviderKey) => {
                const badge = KEY_STATUS_BADGE[k.status] ?? KEY_STATUS_BADGE.DISABLED;
                const statusTip = k.status === 'ACTIVE'
                  ? 'Key is active and will be selected by the load balancer'
                  : k.status === 'RATE_LIMITED'
                  ? 'Key hit a 429 rate limit — temporarily skipped. Will auto-recover in ~60s'
                  : 'Key is manually disabled — not used by the load balancer';
                return (
                  <div key={k.id} className="bg-white border border-gray-200 rounded-xl p-3">
                    {rotatingId === k.id ? (
                      <div className="flex items-center gap-2">
                        <input type="password" value={rotateValue} onChange={e => setRotateValue(e.target.value)}
                          placeholder="New API key…"
                          className="flex-1 text-xs border border-gray-200 rounded-lg px-2 py-1 font-mono focus:outline-none focus:ring-2 focus:ring-indigo-300" />
                        <button onClick={() => handleRotate(k.id)} disabled={saving}
                          className="text-xs px-2.5 py-1 bg-amber-500 text-white rounded-lg hover:bg-amber-600 disabled:opacity-50">
                          {saving ? '…' : 'Save'}
                        </button>
                        <button onClick={() => { setRotatingId(null); setRotateValue(''); }}
                          className="text-xs px-2 py-1 text-gray-500 hover:text-gray-700">Cancel</button>
                      </div>
                    ) : (
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-2 min-w-0 flex-wrap">
                          <Tooltip text={`This key is used for ${k.provider} requests`}>
                            <span className="text-[10px] font-bold text-white bg-indigo-500 px-2 py-0.5 rounded cursor-help">{k.provider}</span>
                          </Tooltip>
                          <Tooltip text="Last 4 characters of the stored key — full key is encrypted and never shown">
                            <code className="text-xs font-mono text-gray-700 bg-gray-100 px-2 py-0.5 rounded cursor-help">{k.keyHint}</code>
                          </Tooltip>
                          <Tooltip text={statusTip}>
                            <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full cursor-help ${badge.bg} ${badge.text}`}>{k.status}</span>
                          </Tooltip>
                          <Tooltip text="Total requests made using this key · estimated cost">
                            <span className="text-[10px] text-gray-400 cursor-help">
                              {k.totalRequests.toLocaleString()} req · ${Number(k.totalCost).toFixed(4)}
                            </span>
                          </Tooltip>
                        </div>
                        <div className="flex items-center gap-1 flex-shrink-0">
                          {k.status === 'ACTIVE' ? (
                            <Tooltip text="Temporarily disable this key — it will be skipped by the load balancer but not deleted">
                              <button onClick={() => updateKeyStatus(k.id, 'DISABLED')}
                                className="text-[10px] px-2 py-1 text-gray-500 hover:text-amber-600 border border-gray-200 rounded hover:border-amber-300 transition-colors">
                                Disable
                              </button>
                            </Tooltip>
                          ) : (
                            <Tooltip text="Re-enable this key so the load balancer can use it again">
                              <button onClick={() => updateKeyStatus(k.id, 'ACTIVE')}
                                className="text-[10px] px-2 py-1 text-emerald-600 hover:text-emerald-800 border border-emerald-200 rounded hover:border-emerald-400 transition-colors">
                                Enable
                              </button>
                            </Tooltip>
                          )}
                          <Tooltip text="Replace this key with a new one — useful when a key is expired or compromised">
                            <button onClick={() => { setRotatingId(k.id); setRotateValue(''); }}
                              className="text-[10px] px-2 py-1 text-amber-600 hover:text-amber-800 border border-amber-200 rounded hover:border-amber-400 transition-colors">
                              Rotate
                            </button>
                          </Tooltip>
                          <Tooltip text="Permanently delete this key from the pool">
                            <button onClick={() => deleteKey(k.id)}
                              className="text-[10px] px-2 py-1 text-red-500 hover:text-red-700 border border-red-200 rounded hover:border-red-400 transition-colors">
                              <i className="fas fa-trash-alt" />
                            </button>
                          </Tooltip>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}

          {/* Add key */}
          {addingKey ? (
            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <Tooltip text="Select which provider this API key belongs to. Must match a provider in this group. Add multiple keys for the same provider to enable load balancing.">
                  <select value={newKeyProvider} onChange={e => setNewKeyProvider(e.target.value)}
                    className="text-xs border border-gray-200 rounded-lg px-2 py-1.5 bg-white text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-300 cursor-help">
                    {PROVIDER_OPTIONS.map(p => <option key={p} value={p}>{p}</option>)}
                  </select>
                </Tooltip>
                <input type="password" value={newKeyValue} onChange={e => setNewKeyValue(e.target.value)}
                  placeholder="Paste API key… (e.g. sk-... or AIza...)"
                  className="flex-1 text-xs border border-gray-200 rounded-lg px-2 py-1.5 font-mono focus:outline-none focus:ring-2 focus:ring-indigo-300" />
                <button onClick={handleAddKey} disabled={saving || !newKeyValue.trim()}
                  className="text-xs px-3 py-1.5 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:opacity-50">
                  {saving ? '…' : 'Add'}
                </button>
                <button onClick={() => { setAddingKey(false); setNewKeyValue(''); }}
                  className="text-xs px-2 py-1 text-gray-500 hover:text-gray-700">Cancel</button>
              </div>
              <p className="text-[11px] text-gray-400">
                <i className="fas fa-lock mr-1 text-indigo-400" />
                Stored encrypted (AES-256). Only the last 4 characters are shown after saving.
                XamOps picks keys using the strategy above — callers don't need to send their own API key.
              </p>
            </div>
          ) : (
            <Tooltip text="Add a real provider API key to the pool. XamOps stores it encrypted and uses it automatically. Add 2+ keys for the same provider to activate load balancing.">
              <button onClick={() => setAddingKey(true)}
                className="flex items-center gap-1.5 text-xs font-medium text-indigo-600 hover:text-indigo-800 transition-colors">
                <i className="fas fa-plus-circle" /> Add key to pool
              </button>
            </Tooltip>
          )}
        </div>
      )}
    </div>
  );
}

interface Props {
  providers: LlmProviderConfig[];
  serverBaseUrl?: string;
  onEditProvider?: (provider: LlmProviderConfig) => void;
}

export default function LiteLLMTab({ providers, serverBaseUrl = 'https://api.xamops.com', onEditProvider }: Props) {
  const { status, masterKey, loading: statusLoading, syncing, syncGateway } = useGatewayData();
  const { groups, loading: groupsLoading, createGroup, updateGroup, deleteGroup } = useKeyGroups();

  const [activeSection, setActiveSection] = useState<'overview' | 'connect' | 'groups' | 'test'>('overview');
  const [selectedModel, setSelectedModel] = useState('claude-sonnet-4-5-20250929');
  const [testPrompt, setTestPrompt] = useState('Say hello in one sentence.');

  // How to Connect selectors
  const [connectProvider, setConnectProvider] = useState<string>('');
  const [connectModel, setConnectModel] = useState<string>('');
  const [testResult, setTestResult] = useState<{ response?: string; latency?: number; error?: string } | null>(null);
  const [testing, setTesting] = useState(false);

  // Test Gateway key mode
  const [testKeyMode, setTestKeyMode] = useState<'master' | 'group'>('master');
  const [testGroupKey, setTestGroupKey] = useState<string>('');
  const [testGroupName, setTestGroupName] = useState<string>('');

  // Key Group form state
  const [showGroupForm, setShowGroupForm] = useState(false);
  const [editingGroup, setEditingGroup] = useState<number | null>(null);
  const [groupName, setGroupName] = useState('');
  const [groupDesc, setGroupDesc] = useState('');
  const [groupProviderIds, setGroupProviderIds] = useState<number[]>([]);
  const [savingGroup, setSavingGroup] = useState(false);

  const activeProviders = providers.filter(p => p.status === 'ACTIVE');
  const availableModels = activeProviders.flatMap(p => getEnabledModels(p));

  useEffect(() => {
    if (availableModels.length > 0 && !availableModels.includes(selectedModel)) {
      setSelectedModel(availableModels[0]);
    }
    if (activeProviders.length > 0 && !connectProvider) {
      const first = activeProviders[0];
      const provKey = first.provider?.toUpperCase() ?? '';
      setConnectProvider(provKey);
      setConnectModel(getEnabledModels(first)[0] ?? '');
    }
  }, [activeProviders.length]);

  const proxyEndpoint = `${serverBaseUrl}/api/llm-proxy`;
  const displayKey = masterKey ?? 'YOUR_MASTER_KEY';

  // ── Get Started steps ──────────────────────────────────────────
  const step1Done = activeProviders.length > 0;
  const step2Done = step1Done && !!masterKey;
  const step3Done = step2Done && (status?.online ?? false);
  const allDone = step1Done && step2Done && step3Done;

  const steps = [
    {
      num: 1, done: step1Done, active: !step1Done,
      icon: 'fas fa-plug',
      title: 'Add an AI Provider',
      desc: 'Go to the API Keys tab → connect OpenAI, Anthropic, Gemini or Bedrock.',
      copyText: null as string | null,
    },
    {
      num: 2, done: step2Done, active: step1Done && !step2Done,
      icon: 'fas fa-key',
      title: 'Copy Your Master Key',
      desc: masterKey ? masterKey : 'Auto-generated when you add your first provider.',
      copyText: masterKey,
    },
    {
      num: 3, done: step3Done, active: step2Done && !step3Done,
      icon: 'fas fa-paper-plane',
      title: 'Point Your App to the Gateway',
      desc: proxyEndpoint,
      copyText: proxyEndpoint,
    },
    {
      num: 4, done: allDone, active: step3Done,
      icon: 'fas fa-chart-bar',
      title: 'Monitor in the Dashboard',
      desc: 'Every request is logged — check spend, tokens and latency on the LLM Dashboard.',
      copyText: null,
    },
  ];

  // ── Code examples — driven by connectProvider / connectModel ─────
  const snippetModel = connectModel || 'YOUR_MODEL_NAME';

  const curlExample =
`curl -X POST "${proxyEndpoint}/chat/completions" \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer YOUR_API_KEY" \\
  -H "X-XamOps-Key: ${displayKey}" \\
  -d '{
    "model": "${snippetModel}",
    "messages": [{"role": "user", "content": "Your message here"}]
  }'`;

  const pythonExample =
`from openai import OpenAI

client = OpenAI(
    api_key="YOUR_API_KEY",
    base_url="${proxyEndpoint}"
)

response = client.chat.completions.create(
    model="${snippetModel}",
    messages=[{"role": "user", "content": "Hello!"}]
)
print(response.choices[0].message.content)`;

  const nodeExample =
`import OpenAI from 'openai';

const client = new OpenAI({
  apiKey: 'YOUR_API_KEY',
  baseURL: '${proxyEndpoint}',
});

const response = await client.chat.completions.create({
  model: '${snippetModel}',
  messages: [{ role: 'user', content: 'Hello!' }],
});
console.log(response.choices[0].message.content)`;

  // ── Test handler ──────────────────────────────────────────────
  const handleTest = async () => {
    const activeKey = testKeyMode === 'master' ? masterKey : testGroupKey;
    if (!activeKey || !selectedModel) return;
    setTesting(true);
    setTestResult(null);
    const start = Date.now();
    try {
      const resp = await fetch('/api/llm-proxy/chat/completions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-XamOps-Key': activeKey },
        body: JSON.stringify({ model: selectedModel, messages: [{ role: 'user', content: testPrompt }], max_tokens: 200 }),
      });
      const data = await resp.json();
      if (!resp.ok) {
        setTestResult({ error: data.error?.message ?? data.error ?? JSON.stringify(data) });
      } else {
        setTestResult({ response: data.choices?.[0]?.message?.content ?? JSON.stringify(data), latency: Date.now() - start });
      }
    } catch (e: any) {
      setTestResult({ error: e.message ?? 'Request failed' });
    } finally {
      setTesting(false);
    }
  };

  // ── Key Group form handlers ────────────────────────────────────
  const openNewGroup = () => {
    setEditingGroup(null);
    setGroupName('');
    setGroupDesc('');
    setGroupProviderIds([]);
    setShowGroupForm(true);
  };

  const openEditGroup = (g: typeof groups[0]) => {
    setEditingGroup(g.id);
    setGroupName(g.name);
    setGroupDesc(g.description ?? '');
    setGroupProviderIds(g.providerIds ?? []);
    setShowGroupForm(true);
  };

  const cancelGroupForm = () => {
    setShowGroupForm(false);
    setEditingGroup(null);
  };

  const saveGroup = async () => {
    if (!groupName.trim()) return;
    setSavingGroup(true);
    try {
      const payload = { name: groupName.trim(), description: groupDesc, providerIds: groupProviderIds };
      if (editingGroup != null) {
        await updateGroup(editingGroup, payload);
      } else {
        await createGroup(payload);
      }
      setShowGroupForm(false);
      setEditingGroup(null);
    } finally {
      setSavingGroup(false);
    }
  };

  const toggleGroupProvider = (id: number) => {
    setGroupProviderIds(prev =>
      prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]
    );
  };

  return (
    <div className="space-y-5">

      {/* ── Header + Status ── */}
      <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
        <div className="flex items-center justify-between flex-wrap gap-4">
          <div>
            <h2 className="text-lg font-bold text-gray-800 flex items-center gap-2">
              <i className="fas fa-network-wired text-indigo-500" /> AI Gateway
              <InfoIcon tip="A single proxy that routes your requests to OpenAI, Anthropic, Gemini or Bedrock — and logs every token and dollar spent." />
            </h2>
            <p className="text-sm text-gray-500 mt-0.5">
              One master key for all your AI providers — full cost visibility and control.
            </p>
          </div>
          <div className="flex items-center gap-3">
            {statusLoading ? (
              <span className="text-xs text-gray-400 flex items-center gap-1.5"><i className="fas fa-circle-notch fa-spin" /> Checking…</span>
            ) : (
              <Tooltip text={status?.online ? 'Gateway is reachable and processing requests.' : 'Gateway is offline. Check your server or sync config.'}>
                <div className="flex items-center gap-2 px-3 py-1.5 bg-gray-50 border border-gray-200 rounded-lg text-sm cursor-help">
                  <StatusDot online={status?.online ?? false} />
                  <span className={`font-medium ${status?.online ? 'text-green-700' : 'text-red-600'}`}>
                    {status?.online ? 'Gateway Online' : 'Gateway Offline'}
                  </span>
                </div>
              </Tooltip>
            )}
            <Tooltip text="Pushes your latest provider config to the gateway so new providers are active immediately.">
              <button onClick={syncGateway} disabled={syncing}
                className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium bg-indigo-50 hover:bg-indigo-100 text-indigo-700 rounded-lg transition-colors disabled:opacity-50">
                <i className={`fas fa-sync-alt text-xs ${syncing ? 'fa-spin' : ''}`} />
                {syncing ? 'Syncing…' : 'Sync Config'}
              </button>
            </Tooltip>
          </div>
        </div>

        {/* Master Key */}
        {masterKey ? (
          <div className="mt-4 space-y-2">
            <div className="flex items-center gap-2 bg-violet-50 border-2 border-violet-300 rounded-xl px-4 py-3">
              <div className="flex-shrink-0 w-8 h-8 bg-violet-100 rounded-lg flex items-center justify-center">
                <i className="fas fa-key text-violet-600 text-sm" />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                  <span className="text-[10px] font-bold text-white bg-violet-600 px-2 py-0.5 rounded uppercase tracking-wide">
                    Master Key
                  </span>
                  <span className="text-[10px] text-violet-500 font-medium">Access ALL providers · no load balancing</span>
                  <InfoIcon tip="The master key routes to every connected provider. The model name in your request decides which provider is used. Does NOT support load balancing — use a Group Key with Key Pool for that." />
                </div>
                <code className="text-sm text-violet-900 font-mono font-bold truncate block">{masterKey}</code>
              </div>
              <CopyButton text={masterKey} label="Copy" />
            </div>
            {/* Quick comparison hint */}
            <div className="flex items-start gap-2 px-1">
              <i className="fas fa-info-circle text-gray-400 text-xs mt-0.5 flex-shrink-0" />
              <p className="text-[11px] text-gray-400">
                <strong className="text-violet-600">Master Key</strong> = access all providers, no restrictions, no load balancing. &nbsp;
                <strong className="text-indigo-600">Group Key</strong> (<code className="bg-gray-100 px-1 rounded">sk-xamops-grp-...</code>) = restricted to selected providers, supports load balancing via Key Pool.
                Find Group Keys in the <strong>Key Groups</strong> tab.
              </p>
            </div>
          </div>
        ) : (
          <div className="mt-4 flex items-center gap-2 bg-gray-50 border border-gray-200 rounded-xl px-4 py-2.5">
            <i className="fas fa-link text-gray-400 text-xs" />
            <code className="text-sm text-gray-700 font-mono flex-1 truncate">{proxyEndpoint}</code>
            <CopyButton text={proxyEndpoint} label="Copy URL" />
          </div>
        )}
      </div>

      {/* ── Get Started Checklist ── */}
      <div className={`rounded-2xl border shadow-sm p-5 ${allDone ? 'bg-green-50 border-green-200' : 'bg-gradient-to-br from-indigo-50 to-purple-50 border-indigo-200'}`}>
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <i className={`fas ${allDone ? 'fa-check-circle text-green-500' : 'fa-rocket text-indigo-500'}`} />
            <h3 className={`text-sm font-bold ${allDone ? 'text-green-800' : 'text-indigo-800'}`}>
              {allDone ? "Gateway is live — you're all set!" : 'Get Started — 4 steps to go live'}
            </h3>
          </div>
          <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${allDone ? 'bg-green-100 text-green-700' : 'bg-indigo-100 text-indigo-700'}`}>
            {steps.filter(s => s.done).length} / {steps.length} done
          </span>
        </div>
        <div className="space-y-3">
          {steps.map(step => (
            <div key={step.num} className={`flex gap-3 p-3.5 rounded-xl border transition-all ${
              step.done ? 'bg-white/60 border-green-200' : step.active ? 'bg-white border-indigo-300 shadow-sm' : 'bg-white/40 border-gray-200 opacity-50'
            }`}>
              <div className={`flex-shrink-0 w-8 h-8 rounded-full flex items-center justify-center ${step.done ? 'bg-green-100' : step.active ? 'bg-indigo-100' : 'bg-gray-100'}`}>
                {step.done
                  ? <i className="fas fa-check text-green-600 text-xs" />
                  : <i className={`${step.icon} text-xs ${step.active ? 'text-indigo-600' : 'text-gray-400'}`} />}
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <p className={`text-xs font-semibold ${step.done ? 'text-green-700 line-through' : step.active ? 'text-gray-800' : 'text-gray-500'}`}>
                    Step {step.num}: {step.title}
                  </p>
                  {step.active && !step.done && (
                    <span className="text-[10px] font-bold text-white bg-indigo-500 px-2 py-0.5 rounded-full uppercase tracking-wide">Do this now</span>
                  )}
                </div>
                <p className={`text-xs mt-1 font-mono ${step.copyText && !step.done ? 'text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded inline-block truncate max-w-xs' : 'text-gray-500'}`}>
                  {step.desc}
                </p>
              </div>
              <div className="flex-shrink-0 flex items-center">
                {!step.done && step.copyText && <CopyButton text={step.copyText} label="Copy" />}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* ── Connected Providers ── */}
      {activeProviders.length > 0 && (
        <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
          <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
            <i className="fas fa-plug text-indigo-400" /> Connected Providers
            <span className="ml-auto text-xs font-normal text-gray-400">All accessible via one master key</span>
          </h3>
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
            {activeProviders.map(p => (
              <div key={p.id} className="flex items-center gap-2.5 p-3 border border-gray-100 rounded-xl bg-gray-50 group">
                <span className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ backgroundColor: PROVIDER_COLORS[p.provider] ?? '#94a3b8' }} />
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-semibold text-gray-800 truncate">{p.displayName ?? p.provider}</p>
                  <p className="text-[10px] text-gray-400 truncate">{getEnabledModels(p).length} models</p>
                </div>
                {onEditProvider && (
                  <button
                    onClick={() => onEditProvider(p)}
                    className="opacity-0 group-hover:opacity-100 transition-opacity text-gray-400 hover:text-indigo-600 flex-shrink-0"
                    title="Edit provider">
                    <i className="fas fa-edit text-xs" />
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── Section Tabs ── */}
      <div className="flex gap-1 bg-gray-100 rounded-xl p-1 w-fit">
        {(['overview', 'connect', 'groups', 'test'] as const).map(s => {
          const tabTips: Record<string, string> = {
            overview: 'Setup checklist — add providers, copy your master key, point your app to the gateway.',
            connect: 'Ready-to-run code snippets (cURL, Python, Node.js) for your app. Select a provider to get the exact command.',
            groups: 'Create subset keys that only route to specific providers — e.g. a key just for Claude + OpenAI, another just for Gemini.',
            test: 'Send a live test request from here to verify your gateway is working end-to-end.',
          };
          return (
            <Tooltip key={s} text={tabTips[s]}>
              <button onClick={() => setActiveSection(s)}
                className={`px-4 py-1.5 text-sm font-medium rounded-lg transition-all ${activeSection === s ? 'bg-white text-indigo-700 shadow-sm' : 'text-gray-500 hover:text-gray-700'}`}>
                {s === 'overview' ? 'Overview' : s === 'connect' ? 'How to Connect' : s === 'groups' ? (
                  <span className="flex items-center gap-1.5">
                    Key Groups
                    {groups.length > 0 && <span className="text-[10px] bg-indigo-100 text-indigo-600 font-semibold px-1.5 py-0.5 rounded-full">{groups.length}</span>}
                  </span>
                ) : 'Test Gateway'}
              </button>
            </Tooltip>
          );
        })}
      </div>

      {/* ── Overview ── */}
      {activeSection === 'overview' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-4 flex items-center gap-2">
              <i className="fas fa-map-signs text-indigo-400" /> How It Works
            </h3>
            <div className="space-y-3">
              {[
                { icon: 'fas fa-plug',         title: 'Add Providers',       desc: 'Connect OpenAI, Anthropic, Gemini or Bedrock via API Keys tab.' },
                { icon: 'fas fa-key',           title: 'Get One Master Key',  desc: 'XamOps gives you one key that works for all connected providers.' },
                { icon: 'fas fa-layer-group',   title: 'Create Key Groups',   desc: 'Group specific providers together and get a separate key per group.' },
                { icon: 'fas fa-chart-bar',     title: 'Monitor Everything',  desc: 'Spend, tokens, latency per model and team — all in the dashboard.' },
              ].map(item => (
                <div key={item.title} className="flex gap-3">
                  <div className="flex-shrink-0 w-8 h-8 bg-indigo-50 rounded-full flex items-center justify-center">
                    <i className={`${item.icon} text-indigo-600 text-xs`} />
                  </div>
                  <div>
                    <p className="text-xs font-semibold text-gray-800">{item.title}</p>
                    <p className="text-xs text-gray-500">{item.desc}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-4 flex items-center gap-2">
              <i className="fas fa-star text-indigo-400" /> Capabilities
            </h3>
            <div className="grid grid-cols-2 gap-3">
              {[
                { icon: 'fas fa-random',        label: 'Load Balancing',    desc: 'Distribute across providers' },
                { icon: 'fas fa-shield-alt',    label: 'Auto Fallback',     desc: 'Retry on provider failure' },
                { icon: 'fas fa-dollar-sign',   label: 'Cost Tracking',     desc: 'Per-request spend logging' },
                { icon: 'fas fa-users',         label: 'Team Budgets',      desc: 'Spend limits per team' },
                { icon: 'fas fa-tachometer-alt',label: 'Rate Limiting',     desc: 'Control request volume' },
                { icon: 'fas fa-plug',          label: 'OpenAI Compatible', desc: 'Drop-in replacement' },
              ].map(f => (
                <div key={f.label} className="flex gap-2 p-2.5 bg-indigo-50/60 rounded-xl border border-indigo-100">
                  <i className={`${f.icon} text-indigo-500 text-xs mt-0.5`} />
                  <div>
                    <p className="text-xs font-semibold text-gray-800">{f.label}</p>
                    <p className="text-[10px] text-gray-500">{f.desc}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* ── How to Connect ── */}
      {activeSection === 'connect' && (
        <div className="space-y-5">
          {/* Provider selector — one click, one snippet */}
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center">
              Choose provider
              <InfoIcon tip="Select a provider to generate a ready-to-run code snippet for that provider. The snippet uses your master key and the provider's default model." />
            </h3>
            <div className="flex flex-wrap gap-2">
              {activeProviders.length === 0 && (
                <p className="text-sm text-gray-400">Add a provider first.</p>
              )}
              {activeProviders.map(p => {
                const prov = p.provider?.toUpperCase() ?? '';
                const isActive = connectProvider === prov;
                return (
                  <button
                    key={p.id}
                    onClick={() => { setConnectProvider(prov); setConnectModel(getEnabledModels(p)[0] ?? ''); }}
                    className={`px-4 py-2 rounded-xl text-sm font-semibold border transition-all ${
                      isActive
                        ? 'bg-indigo-600 text-white border-indigo-600'
                        : 'bg-white text-gray-600 border-gray-200 hover:border-indigo-400 hover:text-indigo-600'
                    }`}
                  >
                    {p.displayName ?? p.provider}
                  </button>
                );
              })}
            </div>
            {connectModel && (
              <p className="text-xs text-gray-400 mt-2">Using model: <span className="font-mono text-gray-600">{connectModel}</span></p>
            )}
          </div>

          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <i className="fas fa-terminal text-gray-500" /> cURL
              <InfoIcon tip="Copy this command and run it in your terminal to test the gateway. Replace YOUR_API_KEY with your real provider API key." />
            </h3>
            <CodeBlock code={curlExample} />
            {connectProvider && (() => {
              const cp = activeProviders.find(p => p.provider?.toUpperCase() === connectProvider);
              const cpModels = cp ? getEnabledModels(cp) : (PROVIDER_MODELS[connectProvider] ?? []);
              return cpModels.length > 1 ? (
                <div className="mt-3 p-3 bg-blue-50 border border-blue-100 rounded-xl">
                  <p className="text-xs font-semibold text-blue-700 mb-1.5">
                    <i className="fas fa-info-circle mr-1" />
                    To use a different model — click any model below. Same key, same endpoint.
                  </p>
                  <div className="flex flex-wrap gap-1.5">
                    {cpModels.map(m => (
                      <Tooltip key={m} text={`Click to update the snippet to use ${m}`}>
                        <button
                          onClick={() => setConnectModel(m)}
                          className={`text-[11px] font-mono px-2 py-0.5 rounded-md border transition-all ${
                            connectModel === m
                              ? 'bg-blue-600 text-white border-blue-600'
                              : 'bg-white text-blue-700 border-blue-200 hover:border-blue-400'
                          }`}
                        >
                          {m}
                        </button>
                      </Tooltip>
                    ))}
                  </div>
                </div>
              ) : null;
            })()}
          </div>

          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <i className="fab fa-python text-yellow-500" /> Python (OpenAI SDK)
              <InfoIcon tip="Uses the OpenAI Python SDK pointed at the XamOps gateway. Works for all providers — just change the model name." />
            </h3>
            <CodeBlock code={pythonExample} />
          </div>

          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <i className="fab fa-node-js text-green-600" /> Node.js (OpenAI SDK)
              <InfoIcon tip="Uses the OpenAI Node.js SDK pointed at the XamOps gateway. Works for all providers — just change the model name." />
            </h3>
            <CodeBlock code={nodeExample} />
          </div>
        </div>
      )}

      {/* ── Key Groups ── */}
      {activeSection === 'groups' && (
        <div className="space-y-4">
          {/* Info banner */}
          <div className="bg-amber-50 border border-amber-200 rounded-2xl p-4 flex gap-3">
            <i className="fas fa-layer-group text-amber-500 mt-0.5 flex-shrink-0" />
            <div className="space-y-2">
              <p className="text-sm font-semibold text-amber-800">What are Key Groups?</p>
              <p className="text-xs text-amber-700">
                Group specific providers together and get a <strong>separate key per group</strong>.
                Each group key only routes to the providers you choose — other providers are blocked.
              </p>
              {/* When to use grouping */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mt-2">
                <div className="bg-white/70 border border-amber-200 rounded-xl p-3">
                  <p className="text-xs font-semibold text-amber-800 flex items-center gap-1.5 mb-1">
                    <i className="fas fa-check-circle text-emerald-500" /> Use grouping when:
                  </p>
                  <ul className="text-xs text-amber-700 space-y-1 list-none">
                    <li>• Different teams need isolated access (backend vs frontend)</li>
                    <li>• You want to restrict prod to only Claude, dev to only Gemini</li>
                    <li>• You have <strong>multiple API keys for the same provider</strong> and want load balancing across them</li>
                    <li>• You want to track spend per team separately</li>
                  </ul>
                </div>
                <div className="bg-white/70 border border-amber-200 rounded-xl p-3">
                  <p className="text-xs font-semibold text-amber-800 flex items-center gap-1.5 mb-1">
                    <i className="fas fa-times-circle text-red-400" /> Skip grouping when:
                  </p>
                  <ul className="text-xs text-amber-700 space-y-1 list-none">
                    <li>• You only have one API key per provider</li>
                    <li>• All teams can access all providers</li>
                    <li>• You just need the master key for everything</li>
                  </ul>
                </div>
              </div>
              {/* Same provider grouping tip */}
              <div className="bg-indigo-50 border border-indigo-200 rounded-xl p-3 mt-1">
                <p className="text-xs font-semibold text-indigo-800 flex items-center gap-1.5 mb-1">
                  <i className="fas fa-lightbulb text-indigo-500" /> Load balancing across same provider
                </p>
                <p className="text-xs text-indigo-700">
                  If you have <strong>3 OpenAI API keys</strong> (from different accounts), create a group with OpenAI,
                  then add all 3 keys in the <strong>Key Pool</strong>. XamOps will automatically rotate across them
                  using Round Robin, Least Requests, or Rate Limit Aware strategy.
                  Load balancing only works when you add 2+ keys to the Key Pool — <strong>the group members alone do not enable load balancing</strong>.
                </p>
              </div>
            </div>
          </div>

          {/* Create button */}
          {!showGroupForm && (
            <div className="flex items-center justify-between">
              <p className="text-sm text-gray-500">
                {groupsLoading ? 'Loading…' : groups.length === 0 ? 'No key groups yet.' : `${groups.length} group${groups.length > 1 ? 's' : ''}`}
              </p>
              <Tooltip text="Create a new group key that restricts access to specific providers. You can have unlimited groups — one per team, environment, or use case.">
                <button onClick={openNewGroup} disabled={activeProviders.length === 0}
                  className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold rounded-xl transition-all disabled:opacity-40">
                  <i className="fas fa-plus text-xs" /> Create Key Group
                </button>
              </Tooltip>
            </div>
          )}

          {/* Create / Edit form */}
          {showGroupForm && (
            <div className="bg-white border border-indigo-200 rounded-2xl shadow-sm p-5 space-y-4">
              <h3 className="text-sm font-bold text-gray-800 flex items-center gap-2">
                {editingGroup != null ? 'Edit Key Group' : 'New Key Group'}
                <InfoIcon tip="Each group gets its own unique sk-xamops-grp-... key automatically. Share that key with the team that should have access." />
              </h3>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    Group Name <span className="text-red-500">*</span>
                    <InfoIcon tip='Give it a name that reflects the team or environment. e.g. "Production", "Dev Team", "Analytics Pipeline"' />
                  </label>
                  <input value={groupName} onChange={e => setGroupName(e.target.value)} placeholder="e.g. Production App"
                    className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-indigo-300" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">Description (optional)</label>
                  <input value={groupDesc} onChange={e => setGroupDesc(e.target.value)} placeholder="e.g. For the prod backend service"
                    className="w-full text-sm border border-gray-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-indigo-300" />
                </div>
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-600 mb-2 flex items-center">
                  Select Providers to include in this group
                  <InfoIcon tip="Only these providers will be accessible via this group key. If a request comes in for a provider not in this group, it will be blocked. You can select multiple providers." />
                </label>
                <p className="text-[11px] text-gray-400 mb-2">
                  <i className="fas fa-info-circle mr-1 text-indigo-400" />
                  Tip: Select the same provider type multiple times if you have multiple accounts — then add each API key to the Key Pool for load balancing.
                </p>
                {activeProviders.length === 0 ? (
                  <p className="text-xs text-gray-400">Add a provider first from the API Keys tab.</p>
                ) : (
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                    {activeProviders.map(p => {
                      const selected = groupProviderIds.includes(p.id!);
                      return (
                        <Tooltip key={p.id} text={`${selected ? 'Remove' : 'Add'} ${p.displayName ?? p.provider} — requests for ${p.provider} models will ${selected ? 'no longer' : ''} route through this group`}>
                          <button onClick={() => toggleGroupProvider(p.id!)}
                            className={`flex items-center gap-2.5 p-3 rounded-xl border text-left transition-all w-full ${
                              selected ? 'border-indigo-400 bg-indigo-50' : 'border-gray-200 bg-gray-50 hover:border-indigo-200'
                            }`}>
                            <span className="w-2.5 h-2.5 rounded-full flex-shrink-0"
                              style={{ backgroundColor: PROVIDER_COLORS[p.provider] ?? '#94a3b8' }} />
                            <div className="min-w-0 flex-1">
                              <p className="text-xs font-semibold text-gray-800 truncate">{p.displayName ?? p.provider}</p>
                              <p className="text-[10px] text-gray-400">{p.provider}</p>
                            </div>
                            {selected && <i className="fas fa-check text-indigo-500 text-xs flex-shrink-0" />}
                          </button>
                        </Tooltip>
                      );
                    })}
                  </div>
                )}
              </div>

              <div className="flex gap-3 pt-1">
                <button onClick={saveGroup} disabled={savingGroup || !groupName.trim() || groupProviderIds.length === 0}
                  className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold rounded-xl transition-all disabled:opacity-40">
                  <i className={`fas ${savingGroup ? 'fa-circle-notch fa-spin' : 'fa-save'} text-xs`} />
                  {savingGroup ? 'Saving…' : editingGroup != null ? 'Save Changes' : 'Create Group'}
                </button>
                <button onClick={cancelGroupForm}
                  className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800 border border-gray-200 rounded-xl transition-all">
                  Cancel
                </button>
              </div>
            </div>
          )}

          {/* Groups list */}
          {!showGroupForm && groups.length > 0 && (
            <div className="space-y-3">
              {groups.map(g => (
                <div key={g.id} className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
                  <div className="flex items-start justify-between gap-4 flex-wrap">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <h4 className="text-sm font-bold text-gray-800">{g.name}</h4>
                        {g.description && (
                          <span className="text-xs text-gray-400">{g.description}</span>
                        )}
                        {g.keyCount > 0 && (
                          <Tooltip text={`${g.keyCount} API key${g.keyCount > 1 ? 's' : ''} stored in the key pool — load balancing is ${g.keyCount > 1 ? 'active' : 'inactive (need 2+ keys)'}`}>
                            <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full cursor-help ${g.keyCount > 1 ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-700'}`}>
                              <i className={`fas ${g.keyCount > 1 ? 'fa-random' : 'fa-key'} mr-1`} />
                              {g.keyCount} key{g.keyCount > 1 ? 's' : ''} in pool {g.keyCount > 1 ? '· LB active' : '· add 1 more for LB'}
                            </span>
                          </Tooltip>
                        )}
                      </div>
                      {/* Group Key */}
                      <Tooltip text="This is a Group Key — different from your Master Key. It only routes to the providers in this group. Use it as the X-XamOps-Key header. Supports load balancing via the Key Pool below.">
                        <div className="mt-2 flex items-center gap-2 bg-indigo-50 border-2 border-indigo-200 rounded-xl px-3 py-2 w-fit cursor-help">
                          <div className="flex-shrink-0 flex flex-col items-start gap-0.5">
                            <span className="text-[9px] font-bold text-white bg-indigo-500 px-1.5 py-0.5 rounded uppercase tracking-wide leading-none">
                              Group Key
                            </span>
                          </div>
                          <i className="fas fa-key text-indigo-400 text-[10px]" />
                          <code className="text-xs text-indigo-800 font-mono font-semibold">{g.groupKey}</code>
                          <CopyButton text={g.groupKey} />
                        </div>
                      </Tooltip>
                      <p className="text-[10px] text-gray-400 mt-1 ml-1">
                        <i className="fas fa-ban text-red-300 mr-1" />Not the same as your <span className="text-violet-600 font-semibold">Master Key</span> — restricted to providers in this group only.
                      </p>
                      {/* Providers in group */}
                      <div className="mt-3 flex flex-wrap gap-2 items-center">
                        <span className="text-[10px] text-gray-400 font-medium uppercase tracking-wide">Providers:</span>
                        {g.providerNames.map((name, i) => (
                          <Tooltip key={i} text={`Requests for ${name} models will route through this group. Add ${name} API keys to the Key Pool below to enable load balancing.`}>
                            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-gray-100 text-gray-700 text-xs rounded-full font-medium cursor-help">
                              <span className="w-2 h-2 rounded-full bg-indigo-400" />
                              {name}
                            </span>
                          </Tooltip>
                        ))}
                        {g.providerNames.length === 0 && (
                          <span className="text-xs text-gray-400 italic">No providers — edit to add some</span>
                        )}
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <Tooltip text="Edit this group — change name, description or providers">
                        <button onClick={() => openEditGroup(g)}
                          className="p-2 text-gray-400 hover:text-indigo-600 hover:bg-indigo-50 rounded-lg transition-colors">
                          <i className="fas fa-pen text-xs" />
                        </button>
                      </Tooltip>
                      <Tooltip text="Delete this group and its group key permanently. Keys in the pool will also be deleted.">
                        <button onClick={() => deleteGroup(g.id)}
                          className="p-2 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors">
                          <i className="fas fa-trash text-xs" />
                        </button>
                      </Tooltip>
                    </div>
                  </div>

                  {/* Key Pool */}
                  <GroupKeyPool
                    groupId={g.id}
                    strategy={g.loadBalanceStrategy || 'ROUND_ROBIN'}
                    onStrategyChange={() => {}}
                  />

                  {/* Usage example — one snippet per provider in the group */}
                  <details className="mt-3">
                    <summary className="text-xs text-indigo-600 cursor-pointer hover:text-indigo-800 font-medium">
                      Show usage example
                    </summary>
                    <div className="mt-2 space-y-3">
                      {g.providerNames.map((providerName, i) => {
                        const provConfig = activeProviders.find(p => (p.displayName ?? p.provider) === providerName);
                        const provKey = provConfig?.provider?.toUpperCase() ?? '';
                        const model = provConfig ? (getEnabledModels(provConfig)[0] ?? 'YOUR_MODEL') : (PROVIDER_MODELS[provKey]?.[0] ?? 'YOUR_MODEL');
                        const providerLabel: Record<string, string> = {
                          OPENAI: 'OPENAI_API_KEY',
                          ANTHROPIC: 'ANTHROPIC_API_KEY',
                          GEMINI: 'GEMINI_API_KEY',
                          BEDROCK: 'AWS_ACCESS_KEY',
                        };
                        const keyPlaceholder = providerLabel[provKey] ?? 'YOUR_API_KEY';
                        return (
                          <div key={i}>
                            <p className="text-[11px] text-gray-500 font-semibold mb-1 uppercase tracking-wide">{providerName}</p>
                            <CodeBlock code={
`curl -X POST "${proxyEndpoint}/chat/completions" \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer YOUR_${keyPlaceholder}" \\
  -H "X-XamOps-Key: ${g.groupKey}" \\
  -d '{"model": "${model}", "messages": [{"role": "user", "content": "Hello!"}]}'`
                            } />
                          </div>
                        );
                      })}
                    </div>
                  </details>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ── Test Gateway ── */}
      {activeSection === 'test' && (
        <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5 space-y-4">
          <h3 className="text-sm font-semibold text-gray-700 flex items-center gap-2">
            <i className="fas fa-flask text-indigo-400" /> Test Gateway
            <InfoIcon tip="Send a live request through the gateway. Choose Master Key to test all providers, or pick a Group Key to test only that group's providers." />
          </h3>

          {!masterKey || activeProviders.length === 0 ? (
            <p className="text-sm text-gray-500">Add a provider first — your master key will appear here.</p>
          ) : (
            <>
              {/* Key selector — Master vs Group */}
              <div>
                <label className="block text-xs text-gray-500 mb-2 flex items-center gap-1">
                  Test with key
                  <InfoIcon tip="Master Key tests all providers. Group Key tests only the providers in that group — use this to verify group isolation and load balancing." />
                </label>
                <div className="flex flex-wrap gap-2">
                  {/* Master Key option */}
                  <Tooltip text="Use your Master Key — routes to all providers based on model name">
                    <button
                      onClick={() => { setTestKeyMode('master'); setSelectedModel(availableModels[0] ?? ''); }}
                      className={`flex items-center gap-2 px-3 py-2 rounded-xl border-2 text-xs font-semibold transition-all ${
                        testKeyMode === 'master'
                          ? 'border-violet-500 bg-violet-50 text-violet-700'
                          : 'border-gray-200 bg-white text-gray-500 hover:border-violet-300'
                      }`}>
                      <span className="text-[9px] font-bold text-white bg-violet-500 px-1.5 py-0.5 rounded uppercase">Master</span>
                      <code className="font-mono">{masterKey?.slice(0, 18)}…</code>
                    </button>
                  </Tooltip>
                  {/* Group Key options */}
                  {groups.map(g => (
                    <Tooltip key={g.id} text={`Test using group key for "${g.name}" — only routes to: ${g.providerNames.join(', ')}`}>
                      <button
                        onClick={() => {
                          setTestKeyMode('group');
                          setTestGroupKey(g.groupKey);
                          setTestGroupName(g.name);
                          // Auto-select first model from this group's providers
                          const firstGroupProvider = activeProviders.find(p => g.providerIds.includes(p.id!));
                          if (firstGroupProvider) {
                            const firstModel = getEnabledModels(firstGroupProvider)[0];
                            if (firstModel) setSelectedModel(firstModel);
                          }
                        }}
                        className={`flex items-center gap-2 px-3 py-2 rounded-xl border-2 text-xs font-semibold transition-all ${
                          testKeyMode === 'group' && testGroupKey === g.groupKey
                            ? 'border-indigo-500 bg-indigo-50 text-indigo-700'
                            : 'border-gray-200 bg-white text-gray-500 hover:border-indigo-300'
                        }`}>
                        <span className="text-[9px] font-bold text-white bg-indigo-500 px-1.5 py-0.5 rounded uppercase">Group</span>
                        <span>{g.name}</span>
                        <code className="font-mono text-[10px] text-gray-400">{g.groupKey.slice(0, 16)}…</code>
                      </button>
                    </Tooltip>
                  ))}
                </div>
                {/* Active key display */}
                <div className={`mt-2 flex items-center gap-2 px-3 py-2 rounded-lg text-xs ${testKeyMode === 'master' ? 'bg-violet-50 border border-violet-200' : 'bg-indigo-50 border border-indigo-200'}`}>
                  <i className={`fas fa-key text-xs ${testKeyMode === 'master' ? 'text-violet-400' : 'text-indigo-400'}`} />
                  <span className={`font-semibold ${testKeyMode === 'master' ? 'text-violet-600' : 'text-indigo-600'}`}>
                    {testKeyMode === 'master' ? 'Master Key' : `Group: ${testGroupName}`}
                  </span>
                  <code className={`font-mono ${testKeyMode === 'master' ? 'text-violet-700' : 'text-indigo-700'}`}>
                    {testKeyMode === 'master' ? masterKey : testGroupKey}
                  </code>
                </div>
              </div>

              {/* Model selector */}
              <div>
                <label className="block text-xs text-gray-500 mb-1 flex items-center gap-1">
                  Model
                  <InfoIcon tip="Model name determines the provider. claude-* → Anthropic, gpt-* → OpenAI, gemini-* → Gemini. For Group Key, only models from providers in that group are shown." />
                </label>
                <select value={selectedModel} onChange={e => setSelectedModel(e.target.value)}
                  className="text-sm border border-gray-200 rounded-lg px-3 py-1.5 bg-white text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-300">
                  {(() => {
                    let providersToShow = activeProviders;
                    if (testKeyMode === 'group' && testGroupKey) {
                      const activeGroup = groups.find(g => g.groupKey === testGroupKey);
                      if (activeGroup) {
                        providersToShow = activeProviders.filter(p =>
                          activeGroup.providerIds.includes(p.id!)
                        );
                      }
                    }
                    return providersToShow.map(p => (
                      <optgroup key={p.id} label={p.displayName ?? p.provider}>
                        {getEnabledModels(p).map(m => (
                          <option key={`${p.id}-${m}`} value={m}>{m}</option>
                        ))}
                      </optgroup>
                    ));
                  })()}
                </select>
              </div>

              {/* Prompt */}
              <div>
                <label className="block text-xs text-gray-500 mb-1">Prompt</label>
                <textarea value={testPrompt} onChange={e => setTestPrompt(e.target.value)} rows={2}
                  className="w-full text-sm border border-gray-200 rounded-xl px-3 py-2 text-gray-700 resize-none focus:outline-none focus:ring-2 focus:ring-indigo-300" />
              </div>

              <button onClick={handleTest} disabled={testing || !selectedModel || (testKeyMode === 'group' && !testGroupKey)}
                className="flex items-center gap-2 px-5 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold rounded-xl transition-all disabled:opacity-50">
                <i className={`fas ${testing ? 'fa-circle-notch fa-spin' : 'fa-paper-plane'} text-xs`} />
                {testing ? 'Sending…' : 'Send Request'}
              </button>

              {testResult && (
                <div className={`rounded-xl p-4 border ${testResult.error ? 'bg-red-50 border-red-200' : 'bg-green-50 border-green-200'}`}>
                  {testResult.error ? (
                    <>
                      <p className="text-xs font-semibold text-red-700 mb-1 flex items-center gap-1.5">
                        <i className="fas fa-times-circle" /> Error
                      </p>
                      <p className="text-xs text-red-600 font-mono">{testResult.error}</p>
                    </>
                  ) : (
                    <>
                      <p className="text-xs font-semibold text-green-700 mb-1 flex items-center gap-1.5">
                        <i className="fas fa-check-circle" /> Success
                        {testResult.latency != null && <span className="ml-2 text-[10px] text-green-600 font-normal">{testResult.latency}ms</span>}
                        <span className="ml-2 text-[10px] text-green-500 font-normal">
                          via {testKeyMode === 'master' ? 'Master Key' : `Group: ${testGroupName}`}
                        </span>
                      </p>
                      <p className="text-sm text-gray-700">{testResult.response}</p>
                    </>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}
