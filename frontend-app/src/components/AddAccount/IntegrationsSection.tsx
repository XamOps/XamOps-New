/**
 * Integrations Section for Add Account Page
 *
 * Vantage-style "Apps" section where users can self-connect
 * messaging platforms (Slack, Google Chat, Teams).
 */

import React, { useState, useEffect, useCallback } from 'react';
import { Icon } from '@iconify/react';

interface ConnectionStatus {
  connected: boolean;
  workspaceId?: string | null;
  spaceName?: string | null;
  updatedAt?: string | null;
  alreadyConnected?: boolean;
  message?: string;
  error?: string;
}

const IntegrationsSection: React.FC = () => {
  const [slackStatus, setSlackStatus] = useState<ConnectionStatus>({ connected: false });
  const [gchatStatus, setGchatStatus] = useState<ConnectionStatus>({ connected: false });
  const [loading, setLoading] = useState(true);
  const [connecting, setConnecting] = useState(false);
  const [connectingGchat, setConnectingGchat] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load connection statuses
  const loadStatus = useCallback(async () => {
    try {
      const [slackRes, gchatRes] = await Promise.all([
        fetch('/api/billops/slack/oauth/my-status', { credentials: 'include' }),
        fetch('/api/billops/user/integrations/gchat/status', { credentials: 'include' }),
      ]);
      if (slackRes.ok) setSlackStatus(await slackRes.json());
      if (gchatRes.ok) setGchatStatus(await gchatRes.json());
    } catch (err) {
      console.error('Failed to load integration status:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadStatus();
  }, [loadStatus]);

  // Handle Connect Slack
  const handleConnectSlack = async () => {
    setConnecting(true);
    setError(null);
    try {
      const res = await fetch('/api/billops/slack/oauth/connect', { credentials: 'include' });
      const data = await res.json();

      if (data.alreadyConnected) {
        setSlackStatus({ connected: true, workspaceId: data.workspaceId });
        return;
      }
      if (data.error) {
        setError(data.error);
        return;
      }
      if (data.installUrl) {
        // Redirect to Slack OAuth
        window.location.href = data.installUrl;
      }
    } catch (err) {
      setError('Failed to initiate Slack connection');
    } finally {
      setConnecting(false);
    }
  };

  // Handle Disconnect Slack
  const handleDisconnectSlack = async () => {
    if (!confirm('Disconnect Slack? You will no longer receive support messages in Slack.')) return;
    setDisconnecting(true);
    try {
      const res = await fetch('/api/billops/slack/oauth/disconnect', {
        method: 'DELETE',
        credentials: 'include',
      });
      if (res.ok) {
        setSlackStatus({ connected: false });
      }
    } catch (err) {
      console.error('Failed to disconnect:', err);
    } finally {
      setDisconnecting(false);
    }
  };

  // Handle Connect Google Chat
  const handleConnectGChat = async () => {
    setConnectingGchat(true);
    setError(null);
    try {
      const res = await fetch('/api/billops/user/integrations/gchat/connect', {
        method: 'POST',
        credentials: 'include',
      });
      const data = await res.json();
      if (data.connected) {
        setGchatStatus({ connected: true, spaceName: data.spaceName });
        if (!data.alreadyConnected) {
          alert(data.message || 'Google Chat connected! Check your email for the space invitation.');
        }
      } else if (data.error) {
        setError(data.error);
      }
    } catch (err) {
      setError('Failed to connect Google Chat');
    } finally {
      setConnectingGchat(false);
    }
  };

  // Handle Disconnect Google Chat
  const handleDisconnectGChat = async () => {
    if (!confirm('Disconnect Google Chat? You will no longer receive support messages there.')) return;
    try {
      const res = await fetch('/api/billops/user/integrations/gchat/disconnect', {
        method: 'DELETE',
        credentials: 'include',
      });
      if (res.ok) {
        setGchatStatus({ connected: false });
      }
    } catch (err) {
      console.error('Failed to disconnect GChat:', err);
    }
  };

  if (loading) {
    return (
      <div className="mt-10">
        <h2 className="text-[18px] font-bold text-black mb-1">Apps</h2>
        <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-5">Connect apps to enhance your experience.</p>
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
          {[1, 2, 3].map(i => (
            <div key={i} className="h-[100px] rounded-xl border border-[rgba(0,0,0,0.06)] animate-pulse bg-[rgba(0,0,0,0.02)]" />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="mt-10">
      <h2 className="text-[18px] font-bold text-black mb-1">Apps</h2>
      <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-5">Connect apps to receive support messages and notifications.</p>

      {error && (
        <div className="mb-4 px-4 py-3 rounded-lg bg-red-50 border border-red-100 text-[13px] text-red-700 flex items-center gap-2">
          <Icon icon="mdi:alert-circle-outline" width={16} />
          {error}
        </div>
      )}

      {/* Connected apps table (like Vantage) */}
      {(slackStatus.connected || gchatStatus.connected) && (
        <div className="mb-6 border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
          <div className="grid grid-cols-[1fr_120px_80px] items-center px-4 py-2.5 border-b border-[rgba(0,0,0,0.06)] text-[11px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.4)]">
            <span>Provider Name</span>
            <span>Connection Status</span>
            <span>Type</span>
          </div>
          {slackStatus.connected && (
            <div className="grid grid-cols-[1fr_120px_80px] items-center px-4 py-3 border-b border-[rgba(0,0,0,0.04)]">
              <div className="flex items-center gap-3">
                <Icon icon="mdi:slack" width={20} className="text-[#4A154B]" />
                <span className="text-[13px] font-medium text-black">Slack</span>
                <button
                  onClick={handleDisconnectSlack}
                  disabled={disconnecting}
                  className="ml-2 text-[rgba(0,0,0,0.3)] hover:text-red-500 transition-colors"
                  title="Disconnect"
                >
                  <Icon icon="mdi:pencil-outline" width={14} />
                </button>
              </div>
              <div className="flex items-center gap-1.5">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                <span className="text-[12px] font-medium text-emerald-600">Stable</span>
              </div>
              <span className="text-[12px] text-[rgba(0,0,0,0.5)]">App</span>
            </div>
          )}
          {gchatStatus.connected && (
            <div className="grid grid-cols-[1fr_120px_80px] items-center px-4 py-3">
              <div className="flex items-center gap-3">
                <img src="/icons/gchat-icon.png" alt="Google Chat" className="w-5 h-5 object-contain" />
                <span className="text-[13px] font-medium text-black">Google Chat</span>
                <button
                  onClick={handleDisconnectGChat}
                  className="ml-2 text-[rgba(0,0,0,0.3)] hover:text-red-500 transition-colors"
                  title="Disconnect"
                >
                  <Icon icon="mdi:pencil-outline" width={14} />
                </button>
              </div>
              <div className="flex items-center gap-1.5">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                <span className="text-[12px] font-medium text-emerald-600">Stable</span>
              </div>
              <span className="text-[12px] text-[rgba(0,0,0,0.5)]">App</span>
            </div>
          )}
        </div>
      )}

      {/* App cards grid */}
      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
        {/* Slack */}
        <button
          onClick={slackStatus.connected ? undefined : handleConnectSlack}
          disabled={connecting || slackStatus.connected}
          className={`relative flex flex-col items-center justify-center gap-2 p-5 rounded-xl border transition-all ${
            slackStatus.connected
              ? 'border-emerald-200 bg-emerald-50/30 cursor-default'
              : 'border-[rgba(0,0,0,0.08)] hover:border-[rgba(0,0,0,0.2)] hover:shadow-sm cursor-pointer'
          }`}
        >
          {slackStatus.connected && (
            <span className="absolute top-2 right-2 w-2 h-2 rounded-full bg-emerald-500" />
          )}
          <Icon icon="mdi:slack" width={28} className="text-[#4A154B]" />
          <span className="text-[12px] font-medium text-[rgba(0,0,0,0.8)]">
            {connecting ? 'Connecting...' : slackStatus.connected ? 'Connected' : 'Slack'}
          </span>
        </button>

        {/* Google Chat */}
        <button
          onClick={gchatStatus.connected ? undefined : handleConnectGChat}
          disabled={connectingGchat || gchatStatus.connected}
          className={`relative flex flex-col items-center justify-center gap-2 p-5 rounded-xl border transition-all ${
            gchatStatus.connected
              ? 'border-emerald-200 bg-emerald-50/30 cursor-default'
              : 'border-[rgba(0,0,0,0.08)] hover:border-[rgba(0,0,0,0.2)] hover:shadow-sm cursor-pointer'
          }`}
        >
          {gchatStatus.connected && (
            <span className="absolute top-2 right-2 w-2 h-2 rounded-full bg-emerald-500" />
          )}
          <img src="/icons/gchat-icon.png" alt="Google Chat" className="w-7 h-7 object-contain" />
          <span className="text-[12px] font-medium text-[rgba(0,0,0,0.8)]">
            {connectingGchat ? 'Connecting...' : gchatStatus.connected ? 'Connected' : 'Google Chat'}
          </span>
        </button>

        {/* Teams */}
        <div className="flex flex-col items-center justify-center gap-2 p-5 rounded-xl border border-[rgba(0,0,0,0.08)] opacity-50 cursor-not-allowed">
          <Icon icon="mdi:microsoft-teams" width={28} className="text-[#464EB8]" />
          <span className="text-[12px] font-medium text-[rgba(0,0,0,0.5)]">Teams</span>
          <span className="text-[9px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.04)] px-1.5 py-0.5 rounded">Coming Soon</span>
        </div>
      </div>
    </div>
  );
};

export default IntegrationsSection;
