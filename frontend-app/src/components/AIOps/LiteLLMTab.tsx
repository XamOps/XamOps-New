import { useState, useEffect } from 'react';
import { useGatewayData } from '../../hooks/useGatewayData';
import type { LlmProviderConfig } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';

const PROVIDER_MODELS: Record<string, string[]> = {
  OPENAI: ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'gpt-3.5-turbo', 'o1', 'o3-mini'],
  ANTHROPIC: ['claude-sonnet-4-6', 'claude-opus-4-7', 'claude-haiku-4-5', 'claude-3-5-sonnet-20241022', 'claude-3-haiku-20240307'],
  GEMINI: ['gemini-2.0-flash', 'gemini-1.5-pro', 'gemini-1.5-flash'],
  BEDROCK: ['anthropic.claude-3-5-sonnet-20241022-v2:0', 'amazon.titan-text-express-v1', 'meta.llama3-70b-instruct-v1:0'],
};

function StatusDot({ online }: { online: boolean }) {
  return (
    <span className={`inline-block w-2.5 h-2.5 rounded-full ${online ? 'bg-green-500' : 'bg-red-400'}`} />
  );
}

function CopyButton({ text, label }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = () => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  return (
    <button
      onClick={handleCopy}
      className="flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium text-gray-500 hover:text-indigo-600 bg-gray-100 hover:bg-indigo-50 rounded-lg transition-colors"
    >
      <i className={copied ? 'fas fa-check text-green-500' : 'fas fa-copy'} />
      {label ? (copied ? 'Copied!' : label) : (copied ? 'Copied!' : 'Copy')}
    </button>
  );
}

function CodeBlock({ code }: { code: string; language?: string }) {
  return (
    <div className="relative group">
      <pre className="bg-gray-900 text-green-400 text-xs rounded-xl p-4 overflow-x-auto leading-relaxed font-mono">
        <code>{code}</code>
      </pre>
      <div className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity">
        <CopyButton text={code} />
      </div>
    </div>
  );
}

interface Props {
  providers: LlmProviderConfig[];
  serverBaseUrl?: string;
}

export default function LiteLLMTab({ providers, serverBaseUrl = 'https://api.xamops.com' }: Props) {
  const { status, loading: statusLoading, syncing, syncGateway } = useGatewayData();
  const [activeSection, setActiveSection] = useState<'overview' | 'connect' | 'test'>('overview');
  const [selectedProvider, setSelectedProvider] = useState<LlmProviderConfig | null>(null);
  const [selectedModel, setSelectedModel] = useState('');
  const [testPrompt, setTestPrompt] = useState('Say hello in one sentence.');
  const [testResult, setTestResult] = useState<{ response?: string; latency?: number; error?: string } | null>(null);
  const [testing, setTesting] = useState(false);

  const activeProviders = providers.filter(p => p.status === 'ACTIVE');

  useEffect(() => {
    if (selectedProvider) {
      const models = PROVIDER_MODELS[selectedProvider.provider?.toUpperCase()] ?? [];
      setSelectedModel(models[0] ?? '');
    }
  }, [selectedProvider]);

  useEffect(() => {
    if (activeProviders.length > 0 && !selectedProvider) {
      setSelectedProvider(activeProviders[0]);
    }
  }, [activeProviders.length]);

  const proxyEndpoint = `${serverBaseUrl}/api/llm-proxy`;

  const curlExample = selectedProvider
    ? `curl -X POST "${proxyEndpoint}/chat/completions" \\
  -H "Content-Type: application/json" \\
  -H "X-XamOps-Key: ${selectedProvider.trackingToken ?? 'YOUR_XAMOPS_KEY'}" \\
  -d '{
    "model": "${selectedModel || 'gpt-4o'}",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'`
    : `curl -X POST "${proxyEndpoint}/chat/completions" \\
  -H "Content-Type: application/json" \\
  -H "X-XamOps-Key: YOUR_XAMOPS_KEY" \\
  -d '{"model": "gpt-4o", "messages": [{"role": "user", "content": "Hello!"}]}'`;

  const pythonExample = selectedProvider
    ? `from openai import OpenAI

client = OpenAI(
    api_key="${selectedProvider.trackingToken ?? 'YOUR_XAMOPS_KEY'}",
    base_url="${proxyEndpoint}"
)

response = client.chat.completions.create(
    model="${selectedModel || 'gpt-4o'}",
    messages=[{"role": "user", "content": "Hello!"}]
)
print(response.choices[0].message.content)`
    : `from openai import OpenAI

client = OpenAI(
    api_key="YOUR_XAMOPS_KEY",
    base_url="${proxyEndpoint}"
)

response = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "Hello!"}]
)
print(response.choices[0].message.content)`;

  const handleTest = async () => {
    if (!selectedProvider || !selectedModel) return;
    setTesting(true);
    setTestResult(null);
    const start = Date.now();
    try {
      const resp = await fetch(`/api/llm-proxy/chat/completions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-XamOps-Key': selectedProvider.trackingToken ?? '',
        },
        body: JSON.stringify({
          model: selectedModel,
          messages: [{ role: 'user', content: testPrompt }],
          max_tokens: 200,
        }),
      });
      const data = await resp.json();
      const latency = Date.now() - start;
      if (!resp.ok) {
        setTestResult({ error: data.error?.message ?? data.error ?? JSON.stringify(data) });
      } else {
        const content = data.choices?.[0]?.message?.content ?? JSON.stringify(data);
        setTestResult({ response: content, latency });
      }
    } catch (e: any) {
      setTestResult({ error: e.message ?? 'Request failed' });
    } finally {
      setTesting(false);
    }
  };

  const step1Done = activeProviders.length > 0;
  const step2Done = step1Done && !!selectedProvider?.trackingToken;
  const step3Done = step2Done && (status?.online ?? false);
  const allDone = step1Done && step2Done && step3Done;

  const steps = [
    {
      num: 1,
      done: step1Done,
      active: !step1Done,
      title: 'Add an AI Provider',
      desc: 'Go to the API Keys tab and connect OpenAI, Anthropic, Gemini, or Bedrock.',
      action: { label: 'Go to API Keys →', tab: 'api-keys' },
      icon: 'fas fa-plug',
    },
    {
      num: 2,
      done: step2Done,
      active: step1Done && !step2Done,
      title: 'Copy Your Tracking Key',
      desc: step1Done
        ? `Your key: ${selectedProvider?.trackingToken ?? '—'}`
        : 'Available after adding a provider.',
      copyText: selectedProvider?.trackingToken ?? null,
      icon: 'fas fa-key',
    },
    {
      num: 3,
      done: step3Done,
      active: step2Done && !step3Done,
      title: 'Point Your App to the Gateway',
      desc: `Use the endpoint below instead of calling OpenAI/Anthropic directly. Pass your key via X-XamOps-Key header.`,
      copyText: proxyEndpoint,
      icon: 'fas fa-paper-plane',
    },
    {
      num: 4,
      done: allDone,
      active: step3Done,
      title: 'Monitor in the Dashboard',
      desc: 'Every request is logged — check spend, tokens, and latency on the LLM Dashboard tab.',
      action: { label: 'Open Dashboard →', tab: 'dashboard' },
      icon: 'fas fa-chart-bar',
    },
  ];

  return (
    <div className="space-y-5">

      {/* Header + Gateway Status */}
      <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
        <div className="flex items-center justify-between flex-wrap gap-4">
          <div>
            <h2 className="text-lg font-bold text-gray-800 flex items-center gap-2">
              <i className="fas fa-network-wired text-indigo-500" />
              AI Gateway
            </h2>
            <p className="text-sm text-gray-500 mt-0.5">
              Unified proxy endpoint for all your AI providers — one key, full observability.
            </p>
          </div>
          <div className="flex items-center gap-3">
            {statusLoading ? (
              <span className="text-xs text-gray-400 flex items-center gap-1.5">
                <i className="fas fa-circle-notch fa-spin" /> Checking…
              </span>
            ) : (
              <div className="flex items-center gap-2 px-3 py-1.5 bg-gray-50 border border-gray-200 rounded-lg text-sm">
                <StatusDot online={status?.online ?? false} />
                <span className={`font-medium ${status?.online ? 'text-green-700' : 'text-red-600'}`}>
                  {status?.online ? 'Gateway Online' : 'Gateway Offline'}
                </span>
              </div>
            )}
            <button
              onClick={syncGateway}
              disabled={syncing}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium bg-indigo-50 hover:bg-indigo-100 text-indigo-700 rounded-lg transition-colors disabled:opacity-50"
            >
              <i className={`fas fa-sync-alt text-xs ${syncing ? 'fa-spin' : ''}`} />
              {syncing ? 'Syncing…' : 'Sync Config'}
            </button>
          </div>
        </div>

        {/* Proxy endpoint URL */}
        <div className="mt-4 flex items-center gap-2 bg-gray-50 border border-gray-200 rounded-xl px-4 py-2.5">
          <i className="fas fa-link text-gray-400 text-xs" />
          <code className="text-sm text-gray-700 font-mono flex-1 truncate">{proxyEndpoint}</code>
          <CopyButton text={proxyEndpoint} label="Copy URL" />
        </div>
      </div>

      {/* ── Get Started Checklist ── always visible, collapses when all done ── */}
      <div className={`rounded-2xl border shadow-sm p-5 ${allDone ? 'bg-green-50 border-green-200' : 'bg-gradient-to-br from-indigo-50 to-purple-50 border-indigo-200'}`}>
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <i className={`fas ${allDone ? 'fa-check-circle text-green-500' : 'fa-rocket text-indigo-500'}`} />
            <h3 className={`text-sm font-bold ${allDone ? 'text-green-800' : 'text-indigo-800'}`}>
              {allDone ? 'Gateway is live — you\'re all set!' : 'Get Started — 4 steps to go live'}
            </h3>
          </div>
          {/* progress pills */}
          <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${allDone ? 'bg-green-100 text-green-700' : 'bg-indigo-100 text-indigo-700'}`}>
            {steps.filter(s => s.done).length} / {steps.length} done
          </span>
        </div>

        <div className="space-y-3">
          {steps.map((step) => (
            <div
              key={step.num}
              className={`flex gap-3 p-3.5 rounded-xl border transition-all ${
                step.done
                  ? 'bg-white/60 border-green-200'
                  : step.active
                  ? 'bg-white border-indigo-300 shadow-sm'
                  : 'bg-white/40 border-gray-200 opacity-50'
              }`}
            >
              {/* Step indicator */}
              <div className={`flex-shrink-0 w-8 h-8 rounded-full flex items-center justify-center ${
                step.done ? 'bg-green-100' : step.active ? 'bg-indigo-100' : 'bg-gray-100'
              }`}>
                {step.done
                  ? <i className="fas fa-check text-green-600 text-xs" />
                  : <i className={`${step.icon} text-xs ${step.active ? 'text-indigo-600' : 'text-gray-400'}`} />
                }
              </div>

              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <p className={`text-xs font-semibold ${step.done ? 'text-green-700 line-through' : step.active ? 'text-gray-800' : 'text-gray-500'}`}>
                    Step {step.num}: {step.title}
                  </p>
                  {step.active && !step.done && (
                    <span className="text-[10px] font-bold text-white bg-indigo-500 px-2 py-0.5 rounded-full uppercase tracking-wide">
                      Do this now
                    </span>
                  )}
                </div>
                <p className={`text-xs mt-0.5 font-mono ${step.copyText && !step.done ? 'text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded mt-1 inline-block' : 'text-gray-500'}`}>
                  {step.desc}
                </p>
              </div>

              {/* Action */}
              <div className="flex-shrink-0 flex items-center">
                {!step.done && step.copyText && (
                  <CopyButton text={step.copyText} label="Copy" />
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Active Providers */}
      {activeProviders.length > 0 && (
        <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
          <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
            <i className="fas fa-plug text-indigo-400" /> Connected Providers
            <span className="ml-auto text-xs text-gray-400 font-normal">Select to generate code</span>
          </h3>
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
            {activeProviders.map(p => (
              <div
                key={p.id}
                onClick={() => setSelectedProvider(p)}
                className={`flex items-center gap-2.5 p-3 border rounded-xl cursor-pointer transition-all ${
                  selectedProvider?.id === p.id
                    ? 'border-indigo-400 bg-indigo-50 shadow-sm'
                    : 'border-gray-100 hover:border-indigo-200 hover:bg-gray-50'
                }`}
              >
                <span
                  className="w-2.5 h-2.5 rounded-full flex-shrink-0"
                  style={{ backgroundColor: PROVIDER_COLORS[p.provider] ?? '#94a3b8' }}
                />
                <div className="min-w-0">
                  <p className="text-xs font-semibold text-gray-800 truncate">{p.displayName ?? p.provider}</p>
                  <p className="text-[10px] text-gray-400 truncate">{p.provider}</p>
                </div>
                {selectedProvider?.id === p.id && (
                  <i className="fas fa-check-circle text-indigo-500 text-xs ml-auto flex-shrink-0" />
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Section Tabs */}
      <div className="flex gap-1 bg-gray-100 rounded-xl p-1 w-fit">
        {(['overview', 'connect', 'test'] as const).map(s => (
          <button
            key={s}
            onClick={() => setActiveSection(s)}
            className={`px-4 py-1.5 text-sm font-medium rounded-lg transition-all capitalize ${
              activeSection === s
                ? 'bg-white text-indigo-700 shadow-sm'
                : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            {s === 'overview' ? 'Overview' : s === 'connect' ? 'How to Connect' : 'Test Gateway'}
          </button>
        ))}
      </div>

      {/* ── Overview ── */}
      {activeSection === 'overview' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          {/* How it works */}
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-4 flex items-center gap-2">
              <i className="fas fa-map-signs text-indigo-400" /> How It Works
            </h3>
            <div className="space-y-3">
              {[
                { step: '1', icon: 'fas fa-key', title: 'Add Provider', desc: 'Connect OpenAI, Anthropic, Gemini or Bedrock via API Keys tab.' },
                { step: '2', icon: 'fas fa-link', title: 'Get Your Key', desc: 'Each provider gets a unique XamOps tracking key automatically.' },
                { step: '3', icon: 'fas fa-paper-plane', title: 'Send Requests', desc: 'Point your app to the gateway URL. All traffic is tracked.' },
                { step: '4', icon: 'fas fa-chart-bar', title: 'Monitor Usage', desc: 'View spend, tokens and latency in real time on the dashboard.' },
              ].map(item => (
                <div key={item.step} className="flex gap-3">
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

          {/* Key capabilities */}
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-4 flex items-center gap-2">
              <i className="fas fa-star text-indigo-400" /> Capabilities
            </h3>
            <div className="grid grid-cols-2 gap-3">
              {[
                { icon: 'fas fa-random', label: 'Load Balancing', desc: 'Distribute across providers' },
                { icon: 'fas fa-shield-alt', label: 'Auto Fallback', desc: 'Retry on provider failure' },
                { icon: 'fas fa-dollar-sign', label: 'Cost Tracking', desc: 'Per-request spend logging' },
                { icon: 'fas fa-users', label: 'Team Budgets', desc: 'Spend limits per team' },
                { icon: 'fas fa-tachometer-alt', label: 'Rate Limiting', desc: 'Control request volume' },
                { icon: 'fas fa-plug', label: 'OpenAI Compatible', desc: 'Drop-in replacement' },
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
          {/* Provider + Model selector */}
          {activeProviders.length > 0 && (
            <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
              <h3 className="text-sm font-semibold text-gray-700 mb-3">Generate code for your provider</h3>
              <div className="flex flex-wrap gap-3">
                <div>
                  <label className="block text-xs text-gray-500 mb-1">Provider</label>
                  <select
                    value={selectedProvider?.id ?? ''}
                    onChange={e => {
                      const p = activeProviders.find(x => String(x.id) === e.target.value);
                      setSelectedProvider(p ?? null);
                    }}
                    className="text-sm border border-gray-200 rounded-lg px-3 py-1.5 bg-white text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-300"
                  >
                    {activeProviders.map(p => (
                      <option key={p.id} value={p.id}>{p.displayName ?? p.provider}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-xs text-gray-500 mb-1">Model</label>
                  <select
                    value={selectedModel}
                    onChange={e => setSelectedModel(e.target.value)}
                    className="text-sm border border-gray-200 rounded-lg px-3 py-1.5 bg-white text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-300"
                  >
                    {(PROVIDER_MODELS[selectedProvider?.provider?.toUpperCase() ?? ''] ?? []).map(m => (
                      <option key={m} value={m}>{m}</option>
                    ))}
                  </select>
                </div>
              </div>
            </div>
          )}

          {/* cURL */}
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <i className="fas fa-terminal text-gray-500" /> cURL
            </h3>
            <CodeBlock code={curlExample} />
          </div>

          {/* Python */}
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <i className="fab fa-python text-yellow-500" /> Python (OpenAI SDK)
            </h3>
            <CodeBlock code={pythonExample} language="python" />
          </div>

          {/* Node.js */}
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <i className="fab fa-node-js text-green-600" /> Node.js (OpenAI SDK)
            </h3>
            <CodeBlock
              language="js"
              code={`import OpenAI from 'openai';

const client = new OpenAI({
  apiKey: '${selectedProvider?.trackingToken ?? 'YOUR_XAMOPS_KEY'}',
  baseURL: '${proxyEndpoint}',
});

const response = await client.chat.completions.create({
  model: '${selectedModel || 'gpt-4o'}',
  messages: [{ role: 'user', content: 'Hello!' }],
});
console.log(response.choices[0].message.content);`}
            />
          </div>
        </div>
      )}

      {/* ── Test Gateway ── */}
      {activeSection === 'test' && (
        <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-5 space-y-4">
          <h3 className="text-sm font-semibold text-gray-700 flex items-center gap-2">
            <i className="fas fa-flask text-indigo-400" /> Test Gateway
          </h3>

          {activeProviders.length === 0 ? (
            <p className="text-sm text-gray-500">Add a provider first to test the gateway.</p>
          ) : (
            <>
              <div className="flex flex-wrap gap-3">
                <div>
                  <label className="block text-xs text-gray-500 mb-1">Provider</label>
                  <select
                    value={selectedProvider?.id ?? ''}
                    onChange={e => {
                      const p = activeProviders.find(x => String(x.id) === e.target.value);
                      setSelectedProvider(p ?? null);
                    }}
                    className="text-sm border border-gray-200 rounded-lg px-3 py-1.5 bg-white text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-300"
                  >
                    {activeProviders.map(p => (
                      <option key={p.id} value={p.id}>{p.displayName ?? p.provider}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-xs text-gray-500 mb-1">Model</label>
                  <select
                    value={selectedModel}
                    onChange={e => setSelectedModel(e.target.value)}
                    className="text-sm border border-gray-200 rounded-lg px-3 py-1.5 bg-white text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-300"
                  >
                    {(PROVIDER_MODELS[selectedProvider?.provider?.toUpperCase() ?? ''] ?? []).map(m => (
                      <option key={m} value={m}>{m}</option>
                    ))}
                  </select>
                </div>
              </div>

              <div>
                <label className="block text-xs text-gray-500 mb-1">Prompt</label>
                <textarea
                  value={testPrompt}
                  onChange={e => setTestPrompt(e.target.value)}
                  rows={2}
                  className="w-full text-sm border border-gray-200 rounded-xl px-3 py-2 text-gray-700 resize-none focus:outline-none focus:ring-2 focus:ring-indigo-300"
                />
              </div>

              <button
                onClick={handleTest}
                disabled={testing || !selectedProvider || !selectedModel}
                className="flex items-center gap-2 px-5 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold rounded-xl transition-all disabled:opacity-50"
              >
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
                        {testResult.latency != null && (
                          <span className="ml-2 text-[10px] text-green-600 font-normal">{testResult.latency}ms</span>
                        )}
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
