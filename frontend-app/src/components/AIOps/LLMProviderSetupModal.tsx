import React, { useState, useCallback } from 'react';
import { Icon } from '@iconify/react';
import { DialogRoot, DialogPanel } from '../ui/Dialog';
import type { LlmProviderConfig, LlmProvider } from '../../types/aiops';
import { LLM_PROVIDERS } from '../../types/aiops';

interface LLMProviderSetupModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (config: LlmProviderConfig) => Promise<LlmProviderConfig>;
  onUpdate?: (id: number, config: LlmProviderConfig) => Promise<LlmProviderConfig>;
  editProvider?: LlmProviderConfig | null;
  existingProviders: LlmProviderConfig[];
}

type Step = 'select' | 'success';

const PROVIDER_MODELS: Record<string, string[]> = {
  OPENAI:    ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'gpt-3.5-turbo', 'o1', 'o3-mini'],
  ANTHROPIC: ['claude-sonnet-4-5-20250929', 'claude-opus-4-5-20250929', 'claude-3-5-sonnet-20241022', 'claude-3-5-haiku-20241022', 'claude-3-haiku-20240307'],
  GEMINI:    ['gemini-2.0-flash', 'gemini-1.5-pro', 'gemini-1.5-flash'],
  BEDROCK:   ['anthropic.claude-3-5-sonnet-20241022-v2:0', 'anthropic.claude-3-haiku-20240307-v1:0', 'amazon.titan-text-express-v1', 'meta.llama3-70b-instruct-v1:0'],
};

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button onClick={() => { navigator.clipboard.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 2000); }}
      className={`px-2.5 py-1 text-xs font-medium rounded transition-colors ${copied ? 'bg-green-600 text-white' : 'bg-indigo-600 text-white hover:bg-indigo-700'}`}>
      {copied ? 'Copied!' : 'Copy'}
    </button>
  );
}

const LLMProviderSetupModal: React.FC<LLMProviderSetupModalProps> = ({
  isOpen, onClose, onSave, onUpdate, editProvider, existingProviders,
}) => {
  const isEditing = !!editProvider;

  const [step, setStep] = useState<Step>('select');
  const [selectedProvider, setSelectedProvider] = useState<LlmProvider | null>(null);
  const [displayName, setDisplayName] = useState('');
  const [teamName, setTeamName] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [selectedModels, setSelectedModels] = useState<string[]>([]);
  const [awsRegion, setAwsRegion] = useState('');
  const [awsAccessKey, setAwsAccessKey] = useState('');
  const [awsSecretKey, setAwsSecretKey] = useState('');
  const [isSaving, setIsSaving] = useState(false);
  const [isTesting, setIsTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
  const [error, setError] = useState('');
  const [savedConfig, setSavedConfig] = useState<LlmProviderConfig | null>(null);

  // Pre-fill form when editing an existing provider
  React.useEffect(() => {
    if (editProvider && isOpen) {
      setSelectedProvider(editProvider.provider as LlmProvider);
      setDisplayName(editProvider.displayName ?? '');
      setTeamName(editProvider.assignedTeam ?? '');
      setAwsRegion(editProvider.awsRegion ?? '');
      setApiKey(''); // never pre-fill key — it's write-only
      const models = editProvider.enabledModels
        ? editProvider.enabledModels.split(',').map(m => m.trim()).filter(Boolean)
        : (PROVIDER_MODELS[editProvider.provider as string] ?? []);
      setSelectedModels(models);
      setStep('select');
      setError('');
      setTestResult(null);
    }
  }, [editProvider, isOpen]);

  const resetForm = useCallback(() => {
    setStep('select');
    setSelectedProvider(null);
    setDisplayName('');
    setTeamName('');
    setApiKey('');
    setSelectedModels([]);
    setAwsRegion('');
    setAwsAccessKey('');
    setAwsSecretKey('');
    setIsSaving(false);
    setIsTesting(false);
    setTestResult(null);
    setError('');
    setSavedConfig(null);
  }, []);

  const handleClose = useCallback(() => { resetForm(); onClose(); }, [resetForm, onClose]);

  const handleProviderSelect = (provider: LlmProvider) => {
    setSelectedProvider(provider);
    setApiKey('');
    setError('');
    setTestResult(null);
    // Select all models by default
    setSelectedModels(PROVIDER_MODELS[provider] ?? []);
  };

  const toggleModel = (model: string) => {
    setSelectedModels(prev =>
      prev.includes(model) ? prev.filter(m => m !== model) : [...prev, model]
    );
  };

  const handleTestConnection = async () => {
    if (!selectedProvider) return;
    if (selectedProvider !== 'BEDROCK' && !apiKey.trim()) {
      setError('Enter an API key first to test connection');
      return;
    }
    setIsTesting(true);
    setTestResult(null);
    setError('');
    try {
      const resp = await fetch('/api/xamops/aiops/providers/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          provider: selectedProvider,
          apiKey: apiKey.trim() || undefined,
          awsRegion: awsRegion.trim() || undefined,
          awsAccessKey: awsAccessKey.trim() || undefined,
          awsSecretKey: awsSecretKey.trim() || undefined,
        }),
      });
      const data = await resp.json();
      setTestResult({ success: data.success, message: data.message ?? (data.success ? 'Connection successful' : 'Connection failed') });
    } catch (e: any) {
      setTestResult({ success: false, message: e.message ?? 'Request failed' });
    } finally {
      setIsTesting(false);
    }
  };

  const handleSave = useCallback(async () => {
    if (!selectedProvider) { setError('Please select a provider'); return; }
    if (!isEditing) {
      if (selectedProvider === 'BEDROCK') {
        if (!awsRegion.trim()) { setError('AWS Region is required'); return; }
        if (!awsAccessKey.trim()) { setError('AWS Access Key is required'); return; }
        if (!awsSecretKey.trim()) { setError('AWS Secret Key is required'); return; }
      } else {
        if (!apiKey.trim()) { setError('API key is required'); return; }
      }
    }
    setIsSaving(true);
    setError('');
    try {
      const config: LlmProviderConfig = {
        provider: selectedProvider,
        displayName: displayName || selectedProvider,
        status: 'ACTIVE',
        assignedTeam: teamName.trim() || undefined,
        apiKey: apiKey.trim() || undefined,
        enabledModels: selectedModels.length > 0 ? selectedModels.join(',') : undefined,
      };
      if (selectedProvider === 'BEDROCK') {
        config.awsRegion = awsRegion.trim();
        config.awsAccessKey = awsAccessKey.trim();
        config.awsSecretKey = awsSecretKey.trim();
      }
      const result = isEditing && onUpdate && editProvider?.id
        ? await onUpdate(editProvider.id, config)
        : await onSave(config);
      setSavedConfig(result);
      setStep('success');
    } catch (e: any) {
      setError(e.message || 'Failed to save provider');
    } finally {
      setIsSaving(false);
    }
  }, [selectedProvider, displayName, apiKey, teamName, awsRegion, awsAccessKey, awsSecretKey, selectedModels, isEditing, onSave, onUpdate, editProvider]);

  const configuredCountByProvider = existingProviders.reduce<Record<string, number>>((acc, p) => {
    acc[p.provider] = (acc[p.provider] || 0) + 1;
    return acc;
  }, {});

  const models = selectedProvider ? (PROVIDER_MODELS[selectedProvider] ?? []) : [];
  const allSelected = models.length > 0 && selectedModels.length === models.length;

  return (
    <DialogRoot isOpen={isOpen} onClose={handleClose} maxWidth="max-w-2xl">
      <DialogPanel
        title={
          <div className="flex items-center gap-2">
            <Icon icon={step === 'success' ? 'mdi:check-circle-outline' : isEditing ? 'mdi:pencil-outline' : 'mdi:power-plug-outline'} width={18} color={step === 'success' ? '#16a34a' : '#4F46E5'} />
            <span>{step === 'success' ? (isEditing ? 'Provider Updated' : 'Setup Complete') : isEditing ? `Edit Provider — ${editProvider?.displayName ?? editProvider?.provider}` : 'Connect LLM Provider'}</span>
          </div>
        }
        onClose={handleClose}
        footer={
          <div className="flex items-center justify-between w-full">
            <div className="flex items-center gap-2 text-xs text-[rgba(0,0,0,0.4)]">
              <Icon icon="mdi:shield-outline" width={14} color="rgba(0,0,0,0.4)" />
              <span>API keys are encrypted at rest (AES-256) — never exposed</span>
            </div>
            <div className="flex items-center gap-3">
              {step === 'select' ? (
                <>
                  <button onClick={handleClose}
                    className="px-4 py-2 text-sm font-medium text-[rgba(0,0,0,0.7)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-md hover:bg-[rgba(0,0,0,0.06)] transition-colors">
                    Cancel
                  </button>
                  {selectedProvider && (
                    <>
                      <button onClick={handleTestConnection} disabled={isTesting}
                        className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-indigo-700 bg-indigo-50 border border-indigo-200 rounded-md hover:bg-indigo-100 transition-colors disabled:opacity-50">
                        {isTesting ? <Icon icon="mdi:loading" width={14} className="animate-spin" /> : <Icon icon="mdi:power-plug-outline" width={14} />}
                        {isTesting ? 'Testing…' : 'Test Connection'}
                      </button>
                      <button onClick={handleSave} disabled={isSaving}
                        className="px-4 py-2 text-sm font-medium text-white bg-[rgba(0,0,0,0.88)] hover:bg-black rounded-md transition-all disabled:opacity-50 shadow-sm">
                        {isSaving ? <><Icon icon="mdi:loading" width={14} className="animate-spin inline mr-2" />Saving…</> : isEditing ? <><Icon icon="mdi:check" width={14} className="inline mr-2" />Update Provider</> : <><Icon icon="mdi:content-save-outline" width={14} className="inline mr-2" />Save Provider</>}
                      </button>
                    </>
                  )}
                </>
              ) : (
                <button onClick={handleClose}
                  className="px-4 py-2 text-sm font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded-md transition-all shadow-sm">
                  <Icon icon="mdi:check" width={14} className="inline mr-2" />Done
                </button>
              )}
            </div>
          </div>
        }
      >
        <div className="space-y-5">
          {step === 'select' && (
            <>
              {/* Provider selection */}
              <div>
                <label className="block text-sm font-semibold text-[rgba(0,0,0,0.7)] mb-3">Select Provider</label>
                <div className="grid grid-cols-2 gap-3">
                  {LLM_PROVIDERS.map(provider => {
                    const keyCount = configuredCountByProvider[provider.key] || 0;
                    const isSelected = selectedProvider === provider.key;
                    return (
                      <button key={provider.key}
                        onClick={() => handleProviderSelect(provider.key)}
                        className={`relative p-4 rounded-xl border-2 text-left transition-all cursor-pointer ${isSelected ? 'border-indigo-500 bg-indigo-50 shadow-md' : 'border-[rgba(0,0,0,0.12)] hover:border-indigo-300 hover:bg-[rgba(0,0,0,0.02)]'}`}>
                        {keyCount > 0 && (
                          <span className="absolute top-2 right-2 text-xs bg-green-100 text-green-600 px-2 py-0.5 rounded-full font-medium">
                            {keyCount} key{keyCount > 1 ? 's' : ''}
                          </span>
                        )}
                        <div className={`w-10 h-10 rounded-lg ${provider.bgColor} flex items-center justify-center mb-2`}>
                          <Icon icon={provider.icon} width={22} height={22} />
                        </div>
                        <p className="font-semibold text-[rgba(0,0,0,0.88)] text-sm">{provider.name}</p>
                        <p className="text-xs text-[rgba(0,0,0,0.5)] mt-0.5">{provider.description}</p>
                      </button>
                    );
                  })}
                </div>
              </div>

              {selectedProvider && (
                <div className="space-y-4 pt-2 border-t border-[rgba(0,0,0,0.06)]">
                  {/* Display Name */}
                  <div>
                    <label className="block text-sm font-medium text-[rgba(0,0,0,0.7)] mb-1">
                      Display Name{' '}
                      {(configuredCountByProvider[selectedProvider] || 0) > 0
                        ? <span className="text-amber-600 text-xs">(recommended — you already have a key for this provider)</span>
                        : <span className="text-[rgba(0,0,0,0.4)]">(optional)</span>}
                    </label>
                    <input type="text" value={displayName} onChange={e => setDisplayName(e.target.value)}
                      placeholder={`e.g., Production ${selectedProvider}`}
                      className="w-full px-3 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)]" />
                  </div>

                  {/* Team */}
                  <div>
                    <label className="block text-sm font-medium text-[rgba(0,0,0,0.7)] mb-1">
                      Assign to Team <span className="text-[rgba(0,0,0,0.4)]">(optional)</span>
                    </label>
                    <div className="relative">
                      <Icon icon="mdi:account-group-outline" width={14} color="rgba(0,0,0,0.4)" className="absolute left-3 top-1/2 -translate-y-1/2" />
                      <input type="text" value={teamName} onChange={e => setTeamName(e.target.value)}
                        placeholder="e.g., backend-team, data-science"
                        className="w-full pl-8 pr-3 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)]" />
                    </div>
                  </div>

                  {/* API Key */}
                  {selectedProvider !== 'BEDROCK' && (
                    <div>
                      <label className="block text-sm font-medium text-[rgba(0,0,0,0.7)] mb-1">
                        API Key <span className="text-red-500">*</span>
                      </label>
                      <input type="password" value={apiKey}
                        onChange={e => { setApiKey(e.target.value); setError(''); setTestResult(null); }}
                        placeholder={selectedProvider === 'OPENAI' ? 'sk-...' : selectedProvider === 'ANTHROPIC' ? 'sk-ant-...' : 'AIza...'}
                        className="w-full px-3 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)] font-mono" />
                      <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">
                        <Icon icon="mdi:lock-outline" width={12} color="#818cf8" className="inline mr-1" />
                        Stored encrypted (AES-256). Only the last 4 characters are shown after saving.
                      </p>
                    </div>
                  )}

                  {/* Bedrock fields */}
                  {selectedProvider === 'BEDROCK' && (
                    <div className="space-y-3 p-4 bg-amber-50 border border-amber-200 rounded-xl">
                      <div className="flex items-center gap-2 text-xs font-semibold text-amber-800 mb-2">
                        <Icon icon="mdi:cloud-outline" width={14} color="#D97706" className="inline mr-1" /> AWS Bedrock Credentials
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-[rgba(0,0,0,0.7)] mb-1">AWS Region <span className="text-red-500">*</span></label>
                        <input type="text" value={awsRegion} onChange={e => { setAwsRegion(e.target.value); setError(''); }}
                          placeholder="e.g., us-east-1" className="w-full px-3 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)]" />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-[rgba(0,0,0,0.7)] mb-1">AWS Access Key ID <span className="text-red-500">*</span></label>
                        <input type="password" value={awsAccessKey} onChange={e => { setAwsAccessKey(e.target.value); setError(''); }}
                          placeholder="AKIA..." className="w-full px-3 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)] font-mono" />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-[rgba(0,0,0,0.7)] mb-1">AWS Secret Access Key <span className="text-red-500">*</span></label>
                        <input type="password" value={awsSecretKey} onChange={e => { setAwsSecretKey(e.target.value); setError(''); }}
                          placeholder="wJal..." className="w-full px-3 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)] font-mono" />
                      </div>
                    </div>
                  )}

                  {/* Model selection */}
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <label className="block text-sm font-medium text-[rgba(0,0,0,0.7)]">
                        Models <span className="text-[rgba(0,0,0,0.4)] text-xs">(select which to enable)</span>
                      </label>
                      <button onClick={() => setSelectedModels(allSelected ? [] : models)}
                        className="text-xs text-indigo-600 hover:text-indigo-800 font-medium">
                        {allSelected ? 'Deselect all' : 'Select all'}
                      </button>
                    </div>
                    <div className="grid grid-cols-1 gap-1.5">
                      {models.map(model => (
                        <label key={model}
                          className={`flex items-center gap-3 px-3 py-2 rounded-lg border cursor-pointer transition-all ${
                            selectedModels.includes(model)
                              ? 'border-indigo-300 bg-indigo-50'
                              : 'border-[rgba(0,0,0,0.08)] bg-white hover:border-[rgba(0,0,0,0.15)]'
                          }`}>
                          <input type="checkbox" checked={selectedModels.includes(model)}
                            onChange={() => toggleModel(model)}
                            className="accent-indigo-600 w-4 h-4 flex-shrink-0" />
                          <span className="text-xs font-mono text-[rgba(0,0,0,0.7)]">{model}</span>
                        </label>
                      ))}
                    </div>
                    {selectedModels.length === 0 && (
                      <p className="text-xs text-amber-600 mt-1.5">
                        <Icon icon="mdi:alert-outline" width={12} color="#D97706" className="inline mr-1" />
                        Select at least one model
                      </p>
                    )}
                  </div>

                  {/* Test result */}
                  {testResult && (
                    <div className={`px-4 py-3 rounded-lg text-sm ${testResult.success ? 'bg-green-50 border border-green-200 text-green-700' : 'bg-red-50 border border-red-200 text-red-700'}`}>
                      <div className="flex items-center gap-2 font-semibold">
                        {testResult.success ? <Icon icon="mdi:check-circle-outline" width={16} color="#16a34a" className="flex-shrink-0" /> : <Icon icon="mdi:close-circle-outline" width={16} color="#dc2626" className="flex-shrink-0" />}
                        <span>{testResult.success ? 'Connection successful' : 'Connection failed'}</span>
                      </div>
                      {!testResult.success && (
                        <p className="mt-1.5 ml-5 text-xs leading-relaxed opacity-90">{testResult.message}</p>
                      )}
                    </div>
                  )}

                  {/* Error */}
                  {error && (
                    <div className="flex items-center gap-2 px-4 py-3 rounded-lg bg-red-50 text-red-700 text-sm">
                      <Icon icon="mdi:alert-circle-outline" width={16} color="#dc2626" />
                      <span>{error}</span>
                    </div>
                  )}

                  <div className="flex items-start gap-3 p-4 bg-green-50/50 rounded-xl border border-green-100">
                    <Icon icon="mdi:lock-outline" width={16} color="#16a34a" className="mt-0.5" />
                    <div className="text-xs text-green-700 space-y-1">
                      <p className="font-semibold">Credentials stored encrypted</p>
                      <p>Your key is encrypted with AES-256. XamOps uses it automatically — you never need to pass it in your app.</p>
                    </div>
                  </div>
                </div>
              )}
            </>
          )}

          {step === 'success' && savedConfig && selectedProvider && (
            <div className="space-y-5">
              <div className="flex items-center gap-3 p-4 bg-green-50 border border-green-200 rounded-xl">
                <Icon icon="mdi:check-circle-outline" width={20} color="#16a34a" />
                <div>
                  <p className="text-sm font-bold text-green-800">
                    {savedConfig.displayName ?? selectedProvider} connected successfully!
                  </p>
                  <p className="text-xs text-green-600 mt-0.5">
                    {selectedModels.length} model{selectedModels.length !== 1 ? 's' : ''} enabled — ready to use via Master Key.
                  </p>
                </div>
              </div>

              <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.08)] rounded-xl px-4 py-3 flex items-center gap-3">
                <Icon icon="mdi:fingerprint" width={18} color="#818cf8" />
                <div className="flex-1 min-w-0">
                  <p className="text-[10px] text-[rgba(0,0,0,0.5)] font-semibold uppercase tracking-wide mb-0.5">Tracking Key (X-XamOps-Key)</p>
                  <code className="text-sm text-[rgba(0,0,0,0.88)] font-mono font-bold truncate block">{savedConfig.trackingToken}</code>
                </div>
                <CopyButton text={savedConfig.trackingToken ?? ''} />
              </div>

              <div className="flex items-start gap-3 p-4 bg-blue-50/50 rounded-xl border border-blue-100">
                <Icon icon="mdi:information-outline" width={16} color="#3b82f6" className="mt-0.5" />
                <div className="text-xs text-blue-700 space-y-1.5">
                  <p className="font-semibold">Ready to use — no extra steps needed</p>
                  <p>Your API key is encrypted and stored. Use your <strong>Master Key</strong> from the AI Gateway tab to call any connected provider.</p>
                  <div className="mt-2 bg-blue-100 rounded-lg px-3 py-2 font-mono text-[11px]">
                    curl -X POST "https://api.xamops.com/api/llm-proxy/chat/completions" \<br />
                    &nbsp;&nbsp;-H "X-XamOps-Key: YOUR_MASTER_KEY" \<br />
                    &nbsp;&nbsp;-d '{`{"model": "${selectedModels[0] ?? 'gpt-4o'}", "messages": [...]}`}'
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default LLMProviderSetupModal;
