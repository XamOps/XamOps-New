import React, { useState, FormEvent } from 'react';
import '../styles/login.css';

const FeedbackPage: React.FC = () => {
  const [message, setMessage] = useState('');
  const [rating, setRating] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    if (!message.trim()) {
      setError('Please enter your feedback.');
      return;
    }
    setSubmitting(true);
    try {
      const res = await fetch('/api/auth/feedback', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: message.trim(), rating: rating != null ? String(rating) : '' }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'Could not submit feedback.');
      setDone(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="login-container">
      <div className="bg-blur-circle circle-1"></div>
      <div className="bg-blur-circle circle-2"></div>
      <div className="grid-overlay"></div>

      <div className="form-section" style={{ margin: '0 auto' }}>
        <div className="login-card-transparent">
          <div className="brand-header">
            <span className="welcome-label">We're listening</span>
            <h1 className="brand-title">Share your feedback</h1>
            <p className="brand-subtitle">Tell us how your XamOps trial went — it helps us improve.</p>
          </div>

          {done ? (
            <div className="success-box">
              <i className="fas fa-circle-check"></i>
              <span>Thank you for your feedback!</span>
            </div>
          ) : (
            <form onSubmit={submit} className="form-stack">
              {error && (
                <div className="error-box" role="alert">
                  <i className="fas fa-exclamation-triangle"></i>
                  <div className="error-alert-content"><p className="error-msg">{error}</p></div>
                </div>
              )}

              <div className="input-group">
                <label className="label-text">How would you rate your experience?</label>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  {[1, 2, 3, 4, 5].map((n) => (
                    <button
                      type="button" key={n}
                      onClick={() => setRating(n)}
                      className="rating-star"
                      aria-label={`${n} star${n > 1 ? 's' : ''}`}
                      style={{
                        background: 'transparent', border: 'none', cursor: 'pointer',
                        fontSize: '1.5rem', color: rating != null && n <= rating ? '#fbbf24' : 'rgba(255,255,255,0.3)',
                      }}
                    >
                      <i className="fas fa-star"></i>
                    </button>
                  ))}
                </div>
              </div>

              <div className="input-group">
                <label htmlFor="fb" className="label-text">Your feedback *</label>
                <textarea
                  id="fb" className="input-field" rows={5} required
                  placeholder="What did you like? What could be better?"
                  value={message} onChange={(e) => setMessage(e.target.value)}
                  style={{ resize: 'vertical', paddingLeft: '1rem' }}
                />
              </div>

              <button type="submit" className="submit-btn shimmer-effect" disabled={submitting}>
                {submitting ? (
                  <><div className="loading-ring"></div><span>Sending...</span></>
                ) : (
                  <><span>Submit feedback</span><i className="fas fa-paper-plane ml-2"></i></>
                )}
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  );
};

export default FeedbackPage;
