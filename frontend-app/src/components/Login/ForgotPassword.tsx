import React, { useState, FormEvent } from 'react';

interface ForgotPasswordProps {
  onBackToLogin: () => void;
}

type Step = 'request' | 'verify' | 'reset';

const ForgotPassword: React.FC<ForgotPasswordProps> = ({ onBackToLogin }) => {
  const [step, setStep] = useState<Step>('request');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [otp, setOtp] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [message, setMessage] = useState<{ text: string; type: 'error' | 'success' } | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  // Step 1: Request OTP
  const handleRequestOtp = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setIsLoading(true);
    setMessage(null);

    try {
      const response = await fetch(
        `/api/auth/forgot-password?username=${encodeURIComponent(username)}&email=${encodeURIComponent(email)}`,
        { method: 'POST' }
      );

      if (!response.ok) {
        throw new Error(await response.text());
      }

      setStep('verify');
    } catch (err) {
      setMessage({
        text: err instanceof Error ? err.message : 'Failed to send code',
        type: 'error',
      });
    } finally {
      setIsLoading(false);
    }
  };

  // Step 2: Verify OTP
  const handleVerifyOtp = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setIsLoading(true);
    setMessage(null);

    try {
      const response = await fetch(
        `/api/auth/verify-otp?username=${encodeURIComponent(username)}&otp=${encodeURIComponent(otp)}`,
        { method: 'POST' }
      );

      if (!response.ok) {
        throw new Error('Invalid Code');
      }

      setStep('reset');
    } catch (err) {
      setMessage({
        text: err instanceof Error ? err.message : 'Verification failed',
        type: 'error',
      });
    } finally {
      setIsLoading(false);
    }
  };

  // Step 3: Reset Password
  const handleResetPassword = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setIsLoading(true);
    setMessage(null);

    if (newPassword !== confirmPassword) {
      setMessage({ text: 'Passwords do not match!', type: 'error' });
      setIsLoading(false);
      return;
    }

    try {
      const response = await fetch(
        `/api/auth/reset-password?username=${encodeURIComponent(username)}&otp=${encodeURIComponent(otp)}&newPassword=${encodeURIComponent(newPassword)}`,
        { method: 'POST' }
      );

      if (!response.ok) {
        throw new Error('Failed to reset password');
      }

      alert('Password updated! Please login.');
      globalThis.location.reload();
    } catch (err) {
      setMessage({
        text: err instanceof Error ? err.message : 'Reset failed',
        type: 'error',
      });
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="form-stack">
      <button type="button" onClick={onBackToLogin} className="back-trigger">
        <i className="fas fa-arrow-left"></i>
        <span>Back to login</span>
      </button>

      <h2 className="card-title">Reset Password</h2>
      <p className="card-subtitle" style={{ marginBottom: '2rem' }}>
        Don't worry, we'll help you recover your account.
      </p>

      {/* Error/Success Message */}
      {message && (
        <div className={message.type === 'error' ? 'error-box' : 'success-box'}>
          {message.type === 'error' ? (
            <>
              <i className="fas fa-exclamation-triangle"></i>
              <div className="error-alert-content">
                <h4 className="error-title">Error</h4>
                <p className="error-msg">{message.text}</p>
              </div>
            </>
          ) : (
            <>
              <i className="fas fa-check-circle"></i> {message.text}
            </>
          )}
        </div>
      )}

      {/* Step 1: Request OTP */}
      {step === 'request' && (
        <form onSubmit={handleRequestOtp} className="form-stack">
          <div className="input-group">
            <label htmlFor="username" className="label-text">Username</label>
            <div className="input-container">
              <i className="fas fa-user input-icon-left"></i>
              <input
                type="text"
                id="username"
                className="input-field"
                placeholder="Enter your username"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </div>
          </div>
          <div className="input-group">
            <label htmlFor="email" className="label-text">Email Address</label>
            <div className="input-container">
              <i className="fas fa-envelope input-icon-left"></i>
              <input
                type="email"
                id="email"
                className="input-field"
                placeholder="Enter your email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </div>
          </div>
          <button type="submit" className="submit-btn" disabled={isLoading}>
            {isLoading ? (
              <div className="loading-ring"></div>
            ) : (
              'Send Verification Code'
            )}
          </button>
        </form>
      )}

      {/* Step 2: Verify OTP */}
      {step === 'verify' && (
        <div className="form-stack">
          {!message && (
            <div className="success-box">
              <i className="fas fa-check-circle"></i> Code sent! Check your email.
            </div>
          )}
          <form onSubmit={handleVerifyOtp} className="form-stack">
            <div className="input-group">
              <label htmlFor="otp" className="label-text">Verification Code</label>
              <div className="input-container">
                <i className="fas fa-key input-icon-left"></i>
                <input
                  type="text"
                  id="otp"
                  className="input-field otp-input"
                  placeholder="Enter 6-digit code"
                  maxLength={6}
                  required
                  value={otp}
                  onChange={(e) => setOtp(e.target.value)}
                />
              </div>
            </div>
            <button type="submit" className="submit-btn" disabled={isLoading}>
              {isLoading ? <div className="loading-ring"></div> : 'Verify Code'}
            </button>
          </form>
        </div>
      )}

      {/* Step 3: Reset Password */}
      {step === 'reset' && (
        <form onSubmit={handleResetPassword} className="form-stack">
          <div className="input-group">
            <label htmlFor="newPassword" className="label-text">New Password</label>
            <div className="input-container">
              <i className="fas fa-lock input-icon-left"></i>
              <input
                type="password"
                id="newPassword"
                className="input-field"
                placeholder="Enter new password"
                required
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
              />
            </div>
          </div>
          <div className="input-group">
            <label htmlFor="confirmPassword" className="label-text">Confirm Password</label>
            <div className="input-container">
              <i className="fas fa-lock input-icon-left"></i>
              <input
                type="password"
                id="confirmPassword"
                className="input-field"
                placeholder="Confirm new password"
                required
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
              />
            </div>
          </div>
          <button type="submit" className="submit-btn" disabled={isLoading}>
            {isLoading ? <div className="loading-ring"></div> : 'Update Password'}
          </button>
        </form>
      )}
    </div>
  );
};

export default ForgotPassword;
