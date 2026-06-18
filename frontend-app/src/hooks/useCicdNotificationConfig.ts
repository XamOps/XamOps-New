import { useState, useEffect, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import { NotificationConfig } from '../types/cicdPipelines';

interface UseCicdNotificationConfigReturn {
  config: NotificationConfig | null;
  isLoading: boolean;
  isSaving: boolean;
  saveError: string | null;
  fetchConfig: () => Promise<void>;
  saveConfig: (config: NotificationConfig) => Promise<void>;
  testSlack: (webhookUrl: string) => Promise<boolean>;
}

export const useCicdNotificationConfig = (): UseCicdNotificationConfigReturn => {
  const [config, setConfig] = useState<NotificationConfig | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const fetchConfig = useCallback(async () => {
    setIsLoading(true);
    try {
      const data = await apiClient.get('/api/cicd/notification-config');
      setConfig(data as NotificationConfig);
    } catch {
      setConfig({ emailEnabled: true, slackWebhookUrl: null });
    } finally {
      setIsLoading(false);
    }
  }, []);

  const saveConfig = useCallback(async (cfg: NotificationConfig) => {
    setIsSaving(true);
    setSaveError(null);
    try {
      await apiClient.post('/api/cicd/notification-config', cfg);
      setConfig(cfg);
    } catch {
      setSaveError('Failed to save notification settings');
    } finally {
      setIsSaving(false);
    }
  }, []);

  const testSlack = useCallback(async (webhookUrl: string): Promise<boolean> => {
    if (!webhookUrl.startsWith('https://hooks.slack.com/')) return false;
    try {
      await fetch(webhookUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: '[XamOps] Test notification — Slack is connected!' }),
      });
      return true;
    } catch {
      return false;
    }
  }, []);

  useEffect(() => { fetchConfig(); }, [fetchConfig]);

  return { config, isLoading, isSaving, saveError, fetchConfig, saveConfig, testSlack };
};
