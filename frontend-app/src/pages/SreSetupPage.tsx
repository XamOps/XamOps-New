// frontend-app/src/pages/SreSetupPage.tsx
import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { verifyDetectionStream } from '../hooks/useSreInvestigation';
import { DatadogIcon, GrafanaIcon, NewRelicIcon, NothingYetIcon, OpenObserveIcon } from '../components/SRE/SreStackIcons';

type Stack = 'prometheus' | 'datadog' | 'newrelic' | 'openobserve' | 'none';

const stackConfig: Record<Stack, { label: string; icon: React.ReactNode; description: string; effort: string }> = {
  prometheus:   { label: 'Prometheus / Grafana', icon: <GrafanaIcon size={26} />,     description: 'Add OTel Collector alongside existing stack', effort: '~1 hour' },
  datadog:      { label: 'Datadog',              icon: <DatadogIcon size={26} />,     description: 'Fan-out to XamOps via OTel Collector',      effort: '~30 min' },
  newrelic:     { label: 'New Relic',            icon: <NewRelicIcon size={26} />,    description: 'Fan-out to XamOps via OTel Collector',      effort: '~30 min' },
  openobserve:  { label: 'OpenObserve',          icon: <OpenObserveIcon size={26} />, description: 'Already connected — just configure',         effort: '~5 min' },
  none:         { label: 'Nothing yet',          icon: <NothingYetIcon size={26} />,  description: 'Deploy full stack with one Helm command',   effort: '~2 hours' },
};

const SreSetupPage: React.FC = () => {
  const navigate = useNavigate();
  const [step, setStep] = useState(1);
  const [stack, setStack] = useState<Stack | null>(null);
  const [verified, setVerified] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [verifyError, setVerifyError] = useState('');

  const apiKey = 'xamops_key_' + Math.random().toString(36).slice(2, 10);

  const handleVerify = async () => {
    setVerifying(true);
    setVerifyError('');
    try {
      const result = await verifyDetectionStream('default', 'health-check');
      setVerified(result.connected);
      if (!result.connected) {
        setVerifyError('No signals detected in the last 24h. Make sure your agent is sending data.');
      }
    } catch {
      setVerified(false);
      setVerifyError('Could not reach the verification endpoint. Check your network connection.');
    } finally {
      setVerifying(false);
    }
  };

  return (
    <div className="flex flex-col h-full overflow-y-auto bg-gray-50">
      <div className="max-w-2xl w-full mx-auto px-6 py-12 space-y-8">

        {/* Steps indicator */}
        <div className="flex items-center gap-0">
          {[1, 2, 3, 4].map((s, i) => (
            <React.Fragment key={s}>
              <div className={`flex items-center gap-2 ${step >= s ? 'text-indigo-600' : 'text-gray-300'}`}>
                <div className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold border-2 transition-all ${
                  step > s ? 'bg-indigo-600 border-indigo-600 text-white' :
                  step === s ? 'border-indigo-600 text-indigo-600' :
                  'border-gray-200 text-gray-300'
                }`}>
                  {step > s ? '✓' : s}
                </div>
                <span className="text-xs font-medium hidden sm:block">
                  {s === 1 ? 'Choose stack' : s === 2 ? 'Connect' : s === 3 ? 'Verify' : 'Done'}
                </span>
              </div>
              {i < 3 && <div className={`flex-1 h-0.5 mx-3 ${step > s ? 'bg-indigo-400' : 'bg-gray-200'}`} />}
            </React.Fragment>
          ))}
        </div>

        {/* Step 1 — Choose stack */}
        {step === 1 && (
          <div className="space-y-5">
            <div>
              <h2 className="text-xl font-bold text-gray-900">What observability stack are you using?</h2>
              <p className="text-sm text-gray-500 mt-1">We'll generate the right integration config for you.</p>
            </div>
            <div className="space-y-2.5">
              {(Object.entries(stackConfig) as [Stack, typeof stackConfig[Stack]][]).map(([key, cfg]) => (
                <button
                  key={key}
                  onClick={() => setStack(key)}
                  className={`w-full flex items-center gap-4 p-4 rounded-xl border-2 text-left transition-all ${
                    stack === key
                      ? 'border-indigo-500 bg-indigo-50'
                      : 'border-gray-200 bg-white hover:border-gray-300'
                  }`}
                >
                  <span className="flex items-center justify-center w-8 h-8 shrink-0">{cfg.icon}</span>
                  <div className="flex-1">
                    <p className="text-sm font-semibold text-gray-900">{cfg.label}</p>
                    <p className="text-xs text-gray-500 mt-0.5">{cfg.description}</p>
                  </div>
                  <span className="text-xs text-gray-400 shrink-0">{cfg.effort}</span>
                </button>
              ))}
            </div>
            <button
              disabled={!stack}
              onClick={() => setStep(2)}
              className="w-full py-3 bg-indigo-600 text-white font-medium rounded-xl hover:bg-indigo-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              Continue
            </button>
          </div>
        )}

        {/* Step 2 — Connect */}
        {step === 2 && stack && (
          <div className="space-y-5">
            <div>
              <h2 className="text-xl font-bold text-gray-900">Connect {stackConfig[stack].label}</h2>
              <p className="text-sm text-gray-500 mt-1">Add these credentials to your collector config.</p>
            </div>

            <div className="bg-white border border-gray-200 rounded-2xl p-5 space-y-3 shadow-sm">
              <p className="text-xs font-medium text-gray-500 uppercase tracking-wide">Your API Key</p>
              <div className="flex items-center gap-2">
                <code className="flex-1 font-mono text-sm bg-gray-50 border border-gray-200 rounded-lg px-3 py-2 text-gray-800 truncate">{apiKey}</code>
                <button
                  onClick={() => navigator.clipboard.writeText(apiKey)}
                  className="px-3 py-2 text-xs font-medium text-gray-600 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors shrink-0"
                >
                  Copy
                </button>
              </div>
            </div>

            {/* Stack-specific config */}
            <div className="bg-gray-900 rounded-2xl p-5 overflow-auto">
              <p className="text-xs font-medium text-gray-400 mb-3">
                {stack === 'prometheus' ? 'otel-collector-config.yaml' :
                 stack === 'datadog' || stack === 'newrelic' ? 'otel-collector-config.yaml' :
                 stack === 'openobserve' ? 'OpenObserve Settings → Forwarding' :
                 'Deploy command'}
              </p>
              <pre className="text-xs text-green-400 leading-relaxed font-mono">
                {stack === 'prometheus' && `exporters:
  otlphttp:
    endpoint: "https://ingest.xamops.io/v1"
    headers:
      Authorization: "Bearer ${apiKey}"

service:
  pipelines:
    metrics:
      exporters: [prometheus_existing, otlphttp]`}
                {(stack === 'datadog' || stack === 'newrelic') && `exporters:
  # keep sending to ${stack === 'datadog' ? 'Datadog' : 'New Relic'} (unchanged)
  ${stack}:
    api:
      key: \${EXISTING_API_KEY}
  # also send to XamOps
  otlphttp:
    endpoint: "https://ingest.xamops.io/v1"
    headers:
      Authorization: "Bearer ${apiKey}"

service:
  pipelines:
    metrics:
      exporters: [${stack}, otlphttp]  # fan-out`}
                {stack === 'openobserve' && `# In OpenObserve → Settings → Forwarding:
Endpoint: https://ingest.xamops.io/v1
Authorization: Bearer ${apiKey}`}
                {stack === 'none' && `helm install xamops-sre ./charts/xamops-sre \\
  --set global.apiKey=${apiKey} \\
  --set global.ingestEndpoint=https://ingest.xamops.io`}
              </pre>
            </div>

            <div className="flex gap-3">
              <button onClick={() => setStep(1)} className="px-4 py-3 border border-gray-200 text-gray-600 text-sm font-medium rounded-xl hover:bg-gray-50 transition-colors">
                Back
              </button>
              <button onClick={() => setStep(3)} className="flex-1 py-3 bg-indigo-600 text-white font-medium rounded-xl hover:bg-indigo-700 transition-colors">
                I've added the config
              </button>
            </div>
          </div>
        )}

        {/* Step 3 — Verify */}
        {step === 3 && (
          <div className="space-y-5">
            <div>
              <h2 className="text-xl font-bold text-gray-900">Verify connection</h2>
              <p className="text-sm text-gray-500 mt-1">Send some traffic to your services, then verify we're receiving signals.</p>
            </div>

            <div className={`bg-white border-2 rounded-2xl p-6 text-center transition-all ${
              verified ? 'border-emerald-300' : 'border-gray-200'
            }`}>
              {!verified && !verifying && (
                <div className="space-y-3">
                  <div className="w-12 h-12 rounded-full bg-gray-100 flex items-center justify-center mx-auto">
                    <svg className="w-6 h-6 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8.111 16.404a5.5 5.5 0 017.778 0M12 20h.01m-7.08-7.071c3.904-3.905 10.236-3.905 14.141 0M1.394 9.393c5.857-5.857 15.355-5.857 21.213 0" />
                    </svg>
                  </div>
                  <p className="text-sm text-gray-600">Click below to check if we're receiving signals from your stack.</p>
                  <button onClick={handleVerify} className="px-6 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors">
                    Check Connection
                  </button>
                  {verifyError && (
                    <p className="text-xs text-red-500 mt-2">{verifyError}</p>
                  )}
                </div>
              )}
              {verifying && (
                <div className="space-y-3">
                  <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin mx-auto" />
                  <p className="text-sm text-gray-500">Listening for signals…</p>
                </div>
              )}
              {verified && (
                <div className="space-y-3">
                  <div className="w-12 h-12 rounded-full bg-emerald-100 flex items-center justify-center mx-auto">
                    <svg className="w-6 h-6 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                  </div>
                  <p className="text-sm font-semibold text-emerald-700">Connection verified!</p>
                  <p className="text-xs text-gray-500">Signals are flowing. Your services are now being monitored.</p>
                </div>
              )}
            </div>

            <div className="flex gap-3">
              <button onClick={() => setStep(2)} className="px-4 py-3 border border-gray-200 text-gray-600 text-sm font-medium rounded-xl hover:bg-gray-50 transition-colors">
                Back
              </button>
              <button
                disabled={!verified}
                onClick={() => setStep(4)}
                className="flex-1 py-3 bg-indigo-600 text-white font-medium rounded-xl hover:bg-indigo-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              >
                Continue
              </button>
            </div>
          </div>
        )}

        {/* Step 4 — Done */}
        {step === 4 && (
          <div className="space-y-6 text-center">
            <div className="w-16 h-16 rounded-2xl bg-indigo-100 flex items-center justify-center mx-auto">
              <svg className="w-8 h-8 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
              </svg>
            </div>
            <div>
              <h2 className="text-xl font-bold text-gray-900">You're all set!</h2>
              <p className="text-sm text-gray-500 mt-2 max-w-sm mx-auto">
                XamOps SRE is now monitoring your services. Investigations will start automatically when anomalies are detected.
              </p>
            </div>
            <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-left">
              <p className="text-xs font-semibold text-amber-700 mb-1.5">Expect realistic results within:</p>
              <ul className="space-y-1">
                {['First anomaly detection: ~1 hour after traffic flows', 'Baseline established: 24–48 hours', 'Full accuracy: 2–4 weeks'].map(line => (
                  <li key={line} className="flex items-center gap-2 text-xs text-amber-600">
                    <span className="w-1 h-1 rounded-full bg-amber-400" />
                    {line}
                  </li>
                ))}
              </ul>
            </div>
            <div className="flex gap-3">
              <button
                onClick={() => navigate('/sre')}
                className="flex-1 py-3 bg-indigo-600 text-white font-medium rounded-xl hover:bg-indigo-700 transition-colors"
              >
                Go to SRE Dashboard
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default SreSetupPage;
