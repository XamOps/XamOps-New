import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import '../styles/login.css';

const TrialExpiredPage: React.FC = () => {
  const navigate = useNavigate();
  const [sending, setSending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const contactSales = async () => {
    setSending(true);
    setMessage(null);
    try {
      const res = await fetch('/api/auth/contact-sales', { method: 'POST', credentials: 'include' });
      const data = await res.json();
      setMessage(res.ok ? (data.message || 'Our team has been notified.') : (data.error || 'Please try again.'));
    } catch {
      setMessage('Could not reach the server. Please try again.');
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="login-container">
      <div className="bg-blur-circle circle-1"></div>
      <div className="bg-blur-circle circle-2"></div>
      <div className="grid-overlay"></div>

      <div className="form-section" style={{ margin: '0 auto' }}>
        <div className="login-card-transparent" style={{ textAlign: 'center' }}>
          <div className="brand-header">
            <span className="welcome-label">Trial ended</span>
            <h1 className="brand-title">Your free trial has expired</h1>
            <p className="brand-subtitle">
              Thanks for trying XamOps. To keep your access, contact our team to extend your trial or move to a paid plan.
            </p>
          </div>

          {message && (
            <div className="success-box" style={{ marginBottom: '1rem' }}>
              <i className="fas fa-circle-check"></i>
              <span>{message}</span>
            </div>
          )}

          <div className="form-stack">
            <button type="button" className="submit-btn shimmer-effect" onClick={contactSales} disabled={sending}>
              {sending ? (
                <><div className="loading-ring"></div><span>Notifying our team...</span></>
              ) : (
                <><span>Contact Sales</span><i className="fas fa-headset ml-2"></i></>
              )}
            </button>

            <button type="button" className="google-signin-btn" onClick={() => navigate('/feedback')}>
              <i className="fas fa-comment-dots"></i>
              <span>Share feedback</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default TrialExpiredPage;
