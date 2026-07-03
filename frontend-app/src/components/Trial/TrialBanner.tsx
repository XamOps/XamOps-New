import React, { useEffect, useState } from 'react';

interface TrialStatus {
  planType: string;
  trialStatus: string;
  daysRemaining: number;
  trialEndAt?: string;
}

const DISMISS_KEY = 'trialBannerDismissedOn';

/**
 * Daily trial countdown banner for self-signup (TRIAL) clients.
 * Shows at most once per calendar day (dismissal stored in localStorage).
 * Provides a Contact Sales action.
 */
const TrialBanner: React.FC = () => {
  const [status, setStatus] = useState<TrialStatus | null>(null);
  const [visible, setVisible] = useState(false);
  const [salesMsg, setSalesMsg] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const res = await fetch('/api/auth/trial/status', { credentials: 'include' });
        if (!res.ok) return;
        const data: TrialStatus = await res.json();
        if (!active) return;
        setStatus(data);

        const isTrial = data.planType === 'TRIAL' && data.trialStatus === 'ACTIVE';
        const today = new Date().toISOString().slice(0, 10);
        const dismissedOn = localStorage.getItem(DISMISS_KEY);
        if (isTrial && dismissedOn !== today) {
          setVisible(true);
        }
      } catch {
        /* non-fatal */
      }
    })();
    return () => { active = false; };
  }, []);

  const dismiss = () => {
    localStorage.setItem(DISMISS_KEY, new Date().toISOString().slice(0, 10));
    setVisible(false);
  };

  const contactSales = async () => {
    setSalesMsg(null);
    try {
      const res = await fetch('/api/auth/contact-sales', { method: 'POST', credentials: 'include' });
      const data = await res.json();
      setSalesMsg(res.ok ? (data.message || 'Our team has been notified.') : (data.error || 'Please try again.'));
    } catch {
      setSalesMsg('Could not reach the server. Please try again.');
    }
  };

  if (!visible || !status) return null;

  const days = status.daysRemaining;
  const urgent = days <= 5;

  return (
    <div
      role="status"
      style={{
        display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap',
        padding: '0.625rem 1rem', margin: '0.5rem 1rem 0',
        borderRadius: '10px', fontSize: '0.875rem',
        background: urgent ? '#fef2f2' : '#eff6ff',
        border: `1px solid ${urgent ? '#fecaca' : '#bfdbfe'}`,
        color: urgent ? '#991b1b' : '#1e40af',
      }}
    >
      <i className={`fas ${urgent ? 'fa-triangle-exclamation' : 'fa-circle-info'}`}></i>
      <span style={{ fontWeight: 600 }}>
        {days > 0
          ? `${days} day${days === 1 ? '' : 's'} left in your free trial.`
          : 'Your free trial ends today.'}
      </span>
      {salesMsg ? (
        <span style={{ fontWeight: 500 }}>{salesMsg}</span>
      ) : (
        <button
          type="button" onClick={contactSales}
          style={{
            background: urgent ? '#991b1b' : '#1e40af', color: '#fff', border: 'none',
            borderRadius: '6px', padding: '0.3rem 0.75rem', cursor: 'pointer', fontWeight: 600, fontSize: '0.8125rem',
          }}
        >
          Contact Sales
        </button>
      )}
      <button
        type="button" onClick={dismiss} aria-label="Dismiss"
        style={{ marginLeft: 'auto', background: 'transparent', border: 'none', cursor: 'pointer', color: 'inherit', opacity: 0.7 }}
      >
        <i className="fas fa-times"></i>
      </button>
    </div>
  );
};

export default TrialBanner;
