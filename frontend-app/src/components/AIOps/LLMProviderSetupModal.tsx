import React, { useState, useCallback } from 'react';
import { DialogRoot, DialogPanel } from '../ui/Dialog';
import type { LlmProviderConfig, LlmProvider } from '../../types/aiops';
import { LLM_PROVIDERS } from '../../types/aiops';

interface LLMProviderSetupModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (config: LlmProviderConfig) => Promise<LlmProviderConfig>;
  existingProviders: LlmProviderConfig[];
}

type Step = 'select' | 'success';

// Curl snippets per provider — CloudIDR style (unified Authorization: Bearer + X-XamOps-Key)
const CURL_SNIPPETS: Record<string, string> = {
  ANTHROPIC: `curl {{PROXY_URL}}/v1/messages \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer YOUR_ANTHROPIC_KEY" \\
  -H "X-XamOps-Key: {{TRACKING_TOKEN}}" \\
  -d '{
    "model": "claude-sonnet-4-20250514",
    "max_tokens": 100,
    "messages": [{"role": "user", "content": "Hello!"}]
  }'`,
  OPENAI: `curl {{PROXY_URL}}/v1/chat/completions \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer YOUR_OPENAI_KEY" \\
  -H "X-XamOps-Key: {{TRACKING_TOKEN}}" \\
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'`,
  GEMINI: `curl {{PROXY_URL}}/v1/models/gemini-2.0-flash:generateContent \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer YOUR_GEMINI_KEY" \\
  -H "X-XamOps-Key: {{TRACKING_TOKEN}}" \\
  -d '{
    "contents": [{"parts": [{"text": "Hello!"}]}]
  }'`,
  BEDROCK: `curl {{PROXY_URL}}/model/anthropic.claude-3-haiku-20240307-v1:0/invoke \\
  -H "Content-Type: application/json" \\
  -H "X-XamOps-Key: {{TRACKING_TOKEN}}" \\
  -d '{
    "messages": [{"role": "user", "content": "Hello!"}],
    "max_tokens": 256,
    "anthropic_version": "bedrock-2023-05-31"
  }'`,
};

function CopyButton({ text, className = '' }: { text: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = () => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  return (
    <button
      onClick={handleCopy}
      className={`px-2.5 py-1 text-xs font-medium rounded transition-colors ${
        copied
          ? 'bg-green-600 text-white'
          : 'bg-indigo-600 text-white hover:bg-indigo-700'
      } ${className}`}
    >
      {copied ? 'Copied!' : 'Copy'}
    </button>
  );
}

const LLMProviderSetupModal: React.FC<LLMProviderSetupModalProps> = ({
  isOpen,
  onClose,
  onSave,
  existingProviders,
}) => {
  const [step, setStep] = useState<Step>('select');
  const [selectedProvider, setSelectedProvider] = useState<LlmProvider | null>(null);
  const [displayName, setDisplayName] = useState('');
  const [awsRegion, setAwsRegion] = useState('');
  const [awsAccessKey, setAwsAccessKey] = useState('');
  const [awsSecretKey, setAwsSecretKey] = useState('');
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState('');
  const [savedConfig, setSavedConfig] = useState<LlmProviderConfig | null>(null);
  const [activeSnippetProvider, setActiveSnippetProvider] = useState<string>('');

  const resetForm = useCallback(() => {
    setStep('select');
    setSelectedProvider(null);
    setDisplayName('');
    setAwsRegion('');
    setAwsAccessKey('');
    setAwsSecretKey('');
    setIsSaving(false);
    setError('');
    setSavedConfig(null);
    setActiveSnippetProvider('');
  }, []);

  const handleClose = useCallback(() => {
    resetForm();
    onClose();
  }, [resetForm, onClose]);

  const handleSave = useCallback(async () => {
    if (!selectedProvider) {
      setError('Please select a provider');
      return;
    }
    if (selectedProvider === 'BEDROCK') {
      if (!awsRegion.trim()) { setError('AWS Region is required for Bedrock'); return; }
      if (!awsAccessKey.trim()) { setError('AWS Access Key is required for Bedrock'); return; }
      if (!awsSecretKey.trim()) { setError('AWS Secret Key is required for Bedrock'); return; }
    }
    setIsSaving(true);
    setError('');
    try {
      const config: LlmProviderConfig = {
        provider: selectedProvider,
        displayName: displayName || selectedProvider,
        status: 'ACTIVE',
      };
      if (selectedProvider === 'BEDROCK') {
        config.awsRegion = awsRegion.trim();
        config.awsAccessKey = awsAccessKey.trim();
        config.awsSecretKey = awsSecretKey.trim();
      }
      const result = await onSave(config);
      setSavedConfig(result);
      setActiveSnippetProvider(selectedProvider);
      setStep('success');
    } catch (e: any) {
      setError(e.message || 'Failed to save provider');
    } finally {
      setIsSaving(false);
    }
  }, [selectedProvider, displayName, awsRegion, awsAccessKey, awsSecretKey, onSave]);

  const configuredCountByProvider = existingProviders.reduce<Record<string, number>>((acc, p) => {
    acc[p.provider] = (acc[p.provider] || 0) + 1;
    return acc;
  }, {});

  const getCurlSnippet = (provider: string) => {
    if (!savedConfig) return '';
    const snippet = CURL_SNIPPETS[provider] || '';
    return snippet
      .replace(/\{\{PROXY_URL\}\}/g, savedConfig.proxyBaseUrl || '')
      .replace(/\{\{TRACKING_TOKEN\}\}/g, savedConfig.trackingToken || '');
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={handleClose} maxWidth="max-w-2xl">
      <DialogPanel
        title={
          <div className="flex items-center gap-2">
            <i className={`fas ${step === 'success' ? 'fa-check-circle text-green-500' : 'fa-plug text-indigo-500'}`} />
            <span>{step === 'success' ? 'Setup Complete' : 'Connect LLM Provider'}</span>
          </div>
        }
        onClose={handleClose}
        footer={
          <div className="flex items-center justify-between w-full">
            <div className="flex items-center gap-2 text-xs text-gray-400">
              <i className="fas fa-shield-alt" />
              <span>API keys pass through only — never stored</span>
            </div>
            <div className="flex items-center gap-3">
              {step === 'select' ? (
                <>
                  <button
                    onClick={handleClose}
                    className="px-4 py-2 text-sm font-medium text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
                  >
                    Cancel
                  </button>
                  {selectedProvider && (
                    <button
                      onClick={handleSave}
                      disabled={isSaving}
                      className="px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-indigo-500 to-purple-600 rounded-lg hover:from-indigo-600 hover:to-purple-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed shadow-sm"
                    >
                      {isSaving ? (
                        <><i className="fas fa-spinner fa-spin mr-2" />Saving...</>
                      ) : (
                        <><i className="fas fa-save mr-2" />Save Provider</>
                      )}
                    </button>
                  )}
                </>
              ) : (
                <button
                  onClick={handleClose}
                  className="px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-green-500 to-emerald-600 rounded-lg hover:from-green-600 hover:to-emerald-700 transition-all shadow-sm"
                >
                  <i className="fas fa-check mr-2" />Done
                </button>
              )}
            </div>
          </div>
        }
      >
        <div className="space-y-6">
          {step === 'select' && (
            <>
              {/* Step 1: Provider Selection */}
              <div>
                <label className="block text-sm font-semibold text-gray-700 mb-3">
                  Select Provider
                </label>
                <div className="grid grid-cols-2 gap-3">
                  {LLM_PROVIDERS.map(provider => {
                    const keyCount = configuredCountByProvider[provider.key] || 0;
                    const isSelected = selectedProvider === provider.key;

                    return (
                      <button
                        key={provider.key}
                        onClick={() => setSelectedProvider(provider.key)}
                        className={`relative p-4 rounded-xl border-2 text-left transition-all cursor-pointer ${
                          isSelected
                            ? 'border-indigo-500 bg-indigo-50 shadow-md'
                            : 'border-gray-200 hover:border-indigo-300 hover:bg-gray-50'
                        }`}
                      >
                        {keyCount > 0 && (
                          <span className="absolute top-2 right-2 text-xs bg-green-100 text-green-600 px-2 py-0.5 rounded-full font-medium">
                            {keyCount} key{keyCount > 1 ? 's' : ''}
                          </span>
                        )}
                        <div className={`w-10 h-10 rounded-lg ${provider.bgColor} flex items-center justify-center mb-2`}>
                          <i className={`${provider.icon} ${provider.color} text-lg`} />
                        </div>
                        <p className="font-semibold text-gray-900 text-sm">{provider.name}</p>
                        <p className="text-xs text-gray-500 mt-0.5">{provider.description}</p>
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Step 2: Display Name + provider-specific fields */}
              {selectedProvider && (
                <div className="space-y-4 pt-2 border-t border-gray-100">
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">
                      Display Name{' '}
                      {(configuredCountByProvider[selectedProvider] || 0) > 0
                        ? <span className="text-amber-600 text-xs">(recommended — you already have a key for this provider)</span>
                        : <span className="text-gray-400">(optional)</span>
                      }
                    </label>
                    <input
                      type="text"
                      value={displayName}
                      onChange={e => setDisplayName(e.target.value)}
                      placeholder={`e.g., Production ${selectedProvider}`}
                      className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent outline-none"
                    />
                  </div>

                  {/* Bedrock-specific fields */}
                  {selectedProvider === 'BEDROCK' && (
                    <div className="space-y-3 p-4 bg-amber-50 border border-amber-200 rounded-xl">
                      <div className="flex items-center gap-2 text-xs font-semibold text-amber-800 mb-2">
                        <i className="fab fa-aws" />
                        AWS Bedrock Credentials
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-gray-700 mb-1">
                          AWS Region <span className="text-red-500">*</span>
                        </label>
                        <input
                          type="text"
                          value={awsRegion}
                          onChange={e => setAwsRegion(e.target.value)}
                          placeholder="e.g., us-east-1, ap-south-1"
                          className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:ring-2 focus:ring-amber-500 outline-none"
                        />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-gray-700 mb-1">
                          AWS Access Key ID <span className="text-red-500">*</span>
                        </label>
                        <input
                          type="password"
                          value={awsAccessKey}
                          onChange={e => setAwsAccessKey(e.target.value)}
                          placeholder="AKIA..."
                          className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:ring-2 focus:ring-amber-500 outline-none font-mono"
                        />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-gray-700 mb-1">
                          AWS Secret Access Key <span className="text-red-500">*</span>
                        </label>
                        <input
                          type="password"
                          value={awsSecretKey}
                          onChange={e => setAwsSecretKey(e.target.value)}
                          placeholder="wJal..."
                          className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:ring-2 focus:ring-amber-500 outline-none font-mono"
                        />
                      </div>
                      <p className="text-[11px] text-amber-700">
                        <i className="fas fa-shield-alt mr-1" />
                        Credentials are encrypted at rest and used only to sign requests to Bedrock.
                        No Authorization header needed in your curl commands.
                      </p>
                    </div>
                  )}

                  {error && (
                    <div className="flex items-center gap-2 px-4 py-3 rounded-lg bg-red-50 text-red-700 text-sm">
                      <i className="fas fa-exclamation-circle" />
                      <span>{error}</span>
                    </div>
                  )}

                  <div className="flex items-start gap-3 p-4 bg-green-50/50 rounded-xl border border-green-100">
                    <i className="fas fa-lock text-green-500 mt-0.5" />
                    <div className="text-xs text-green-700 space-y-1">
                      {selectedProvider === 'BEDROCK' ? (
                        <>
                          <p className="font-semibold">Credentials stored encrypted</p>
                          <p>Your AWS credentials are encrypted with AES-256 and used to sign Bedrock requests server-side. You won't need to pass any auth headers in your curl commands.</p>
                        </>
                      ) : (
                        <>
                          <p className="font-semibold">No API key needed here</p>
                          <p>Your API key is <strong>never stored</strong> on XamOps servers. After saving, you'll get a tracking key and a curl command to test. Your API key passes through in each request and is only held in memory for ~1-2 seconds.</p>
                        </>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </>
          )}

          {step === 'success' && savedConfig && selectedProvider && (
            <div className="space-y-5">
              {/* Get Started in 3 Steps — CloudIDR style */}
              <div className="bg-gradient-to-br from-indigo-50 to-purple-50 rounded-xl p-5 border border-indigo-100">
                <h3 className="text-sm font-bold text-gray-800 mb-4">Get Started in 3 steps</h3>
                <div className="grid grid-cols-3 gap-4">
                  {/* Step 1 */}
                  <div className="bg-white rounded-lg p-3 border border-gray-100 shadow-sm">
                    <div className="flex items-center gap-2 mb-2">
                      <span className="w-6 h-6 bg-indigo-600 text-white rounded-full flex items-center justify-center text-xs font-bold">1</span>
                      <span className="text-xs font-semibold text-gray-700">Use your XamOps Key</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <code className="text-[10px] text-gray-500 font-mono truncate flex-1">
                        {savedConfig.trackingToken?.substring(0, 20)}...
                      </code>
                      <CopyButton text={savedConfig.trackingToken || ''} />
                    </div>
                  </div>

                  {/* Step 2 */}
                  <div className="bg-white rounded-lg p-3 border border-gray-100 shadow-sm">
                    <div className="flex items-center gap-2 mb-2">
                      <span className="w-6 h-6 bg-indigo-600 text-white rounded-full flex items-center justify-center text-xs font-bold">2</span>
                      <span className="text-xs font-semibold text-gray-700">Modify your LLM API call</span>
                    </div>
                    <p className="text-[10px] text-gray-500">Use our proxy + API key</p>
                  </div>

                  {/* Step 3 */}
                  <div className="bg-white rounded-lg p-3 border border-gray-100 shadow-sm">
                    <div className="flex items-center gap-2 mb-2">
                      <span className="w-6 h-6 bg-indigo-600 text-white rounded-full flex items-center justify-center text-xs font-bold">3</span>
                      <span className="text-xs font-semibold text-gray-700">Send your first request</span>
                    </div>
                    <p className="text-[10px] text-gray-500">Run your app or script; dashboard will refresh with usage.</p>
                  </div>
                </div>
              </div>

              {/* Curl Command */}
              <div>
                <div className="flex items-center justify-between mb-2">
                  <p className="text-xs text-gray-500">Paste this in your terminal to send a test request.</p>
                </div>
                <p className="text-xs text-orange-600 mb-3">
                  <i className="fas fa-info-circle mr-1" />
                  Tip: Use straight double quotes (") in the JSON body to avoid invalid JSON errors.
                </p>

                {/* Provider tabs */}
                <div className="flex items-center gap-2 mb-3 flex-wrap">
                  {(['ANTHROPIC', 'OPENAI', 'GEMINI', 'BEDROCK'] as const).map(p => (
                    <button
                      key={p}
                      onClick={() => setActiveSnippetProvider(p)}
                      className={`px-3 py-1.5 text-xs font-medium rounded-full transition-colors ${
                        activeSnippetProvider === p
                          ? 'bg-indigo-600 text-white'
                          : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                      }`}
                    >
                      {p === 'OPENAI' ? 'OpenAI' : p === 'ANTHROPIC' ? 'Anthropic' : p === 'GEMINI' ? 'Google Gemini' : 'AWS Bedrock'}
                    </button>
                  ))}
                </div>

                {/* Code block */}
                <div className="relative">
                  <pre className="px-4 py-3 text-xs font-mono bg-gray-900 text-gray-100 rounded-lg overflow-x-auto leading-relaxed max-h-64 overflow-y-auto">
                    {getCurlSnippet(activeSnippetProvider)}
                  </pre>
                  <button
                    onClick={() => {
                      navigator.clipboard.writeText(getCurlSnippet(activeSnippetProvider));
                    }}
                    className="absolute top-2 right-2 px-2 py-1 text-xs font-medium text-gray-400 hover:text-white bg-gray-700 hover:bg-gray-600 rounded transition-colors"
                  >
                    <i className="fas fa-copy mr-1" />Copy
                  </button>
                </div>
              </div>

              {/* How it works */}
              <div className="flex items-start gap-3 p-4 bg-blue-50/50 rounded-xl border border-blue-100">
                <i className="fas fa-info-circle text-blue-500 mt-0.5" />
                <div className="text-xs text-blue-700 space-y-1">
                  <p className="font-semibold">How it works</p>
                  {selectedProvider === 'BEDROCK' ? (
                    <>
                      <p>Send requests to the XamOps proxy with just one header:</p>
                      <ul className="list-disc list-inside space-y-0.5 ml-1">
                        <li><code className="bg-blue-100 px-1 rounded">X-XamOps-Key: {savedConfig.trackingToken?.substring(0, 12)}...</code> — your tracking key</li>
                      </ul>
                      <p>XamOps signs the request with your stored AWS credentials using SigV4, forwards to Bedrock, and logs usage. No Authorization header needed.</p>
                    </>
                  ) : (
                    <>
                      <p>Send requests to the XamOps proxy URL with two headers:</p>
                      <ul className="list-disc list-inside space-y-0.5 ml-1">
                        <li><code className="bg-blue-100 px-1 rounded">Authorization: Bearer YOUR_KEY</code> — your provider API key</li>
                        <li><code className="bg-blue-100 px-1 rounded">X-XamOps-Key: {savedConfig.trackingToken?.substring(0, 12)}...</code> — your tracking key</li>
                      </ul>
                      <p>XamOps logs usage metadata (tokens, cost, latency) and forwards your request. Your API key is <strong>never stored</strong>.</p>
                    </>
                  )}
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
