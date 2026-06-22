import React, { useState } from 'react';
import { NotificationConfig } from '../../types/cicdPipelines';

interface Props {
  config: NotificationConfig | null;
  isLoading: boolean;
  isSaving: boolean;
  saveError: string | null;
  onSave: (config: NotificationConfig) => Promise<void>;
  onTestSlack: (url: string) => Promise<boolean>;
}

const CicdNotificationSettings: React.FC<Props> = ({
  config, isLoading, isSaving, saveError, onSave, onTestSlack,
}) => {
  const [emailEnabled, setEmailEnabled] = useState(config?.emailEnabled ?? true);
  const [slackUrl, setSlackUrl] = useState(config?.slackWebhookUrl ?? '');
  const [slackTestResult, setSlackTestResult] = useState<'success' | 'fail' | null>(null);
  const [testing, setTesting] = useState(false);

  React.useEffect(() => {
    if (config) {
      setEmailEnabled(config.emailEnabled);
      setSlackUrl(config.slackWebhookUrl ?? '');
    }
  }, [config]);

  const handleSave = () => {
    onSave({ emailEnabled, slackWebhookUrl: slackUrl || null });
  };

  const handleTestSlack = async () => {
    if (!slackUrl) return;
    setTesting(true);
    setSlackTestResult(null);
    const ok = await onTestSlack(slackUrl);
    setSlackTestResult(ok ? 'success' : 'fail');
    setTesting(false);
  };

  if (isLoading) {
    return <div className="p-4 h-32 bg-[rgba(0,0,0,0.04)] rounded animate-pulse" />;
  }

  return (
    <div className="p-4 space-y-5">
      <h3 className="text-[13px] font-semibold text-[rgba(0,0,0,0.75)]">Failure Notifications</h3>

      <div className="flex items-center justify-between">
        <div>
          <p className="text-[13px] text-[rgba(0,0,0,0.75)]">Email alerts</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Send an email when a pipeline fails</p>
        </div>
        <button
          onClick={() => setEmailEnabled(e => !e)}
          className={`relative w-10 h-6 rounded-full transition-colors ${emailEnabled ? 'bg-blue-500' : 'bg-gray-200'}`}
        >
          <span className={`absolute top-1 w-4 h-4 bg-white rounded-full shadow transition-transform ${emailEnabled ? 'translate-x-5' : 'translate-x-1'}`} />
        </button>
      </div>

      <div className="space-y-2">
        <label className="text-[13px] text-[rgba(0,0,0,0.75)]">Slack webhook URL</label>
        <div className="flex gap-2">
          <input
            type="url"
            value={slackUrl}
            onChange={e => { setSlackUrl(e.target.value); setSlackTestResult(null); }}
            placeholder="https://hooks.slack.com/services/..."
            className="flex-1 text-[12px] px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded focus:outline-none focus:border-blue-400"
          />
          <button
            onClick={handleTestSlack}
            disabled={!slackUrl || testing}
            className="text-[12px] px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded hover:bg-gray-50 disabled:opacity-40"
          >
            {testing ? 'Testing…' : 'Test'}
          </button>
        </div>
        {slackTestResult === 'success' && <p className="text-[11px] text-green-600">Slack connected!</p>}
        {slackTestResult === 'fail' && <p className="text-[11px] text-red-600">Could not reach webhook URL</p>}
      </div>

      {saveError && <p className="text-[11px] text-red-600">{saveError}</p>}

      <button
        onClick={handleSave}
        disabled={isSaving}
        className="text-[12px] px-4 py-2 bg-[rgba(0,0,0,0.85)] text-white rounded hover:bg-black disabled:opacity-40"
      >
        {isSaving ? 'Saving…' : 'Save settings'}
      </button>
    </div>
  );
};

export default CicdNotificationSettings;
