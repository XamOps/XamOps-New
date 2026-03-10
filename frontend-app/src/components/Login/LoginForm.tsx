import React, { useState, useEffect, FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../contexts/AuthContext';
import { useAccounts } from '../../contexts/AccountContext';

interface LoginFormProps {
  onForgotPassword: () => void;
}

interface LoginResponse {
  tenantId?: string;
  redirectUrl?: string;
}

type LoginStep = 'credentials' | 'otp';

const LoginForm: React.FC<LoginFormProps> = ({ onForgotPassword }) => {
  const navigate = useNavigate();
  const { fetchUserRoles } = useAuth();
  const { fetchAccounts } = useAccounts();

  const [loginStep, setLoginStep] = useState<LoginStep>('credentials');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [otp, setOtp] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Auto-dismiss error after 8 seconds
  useEffect(() => {
    if (error) {
      const timer = setTimeout(() => setError(null), 8000);
      return () => clearTimeout(timer);
    }
  }, [error]);

  const togglePasswordVisibility = () => {
    setShowPassword(!showPassword);
  };

  // Step 1: Validate email + password, send OTP
  const handleCredentialsSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setIsLoading(true);

    try {
      const response = await fetch('/api/auth/initiate-login', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email.trim().toLowerCase(), password }),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.error || 'Authentication failed. Please check your credentials.');
      }

      // OTP sent — move to OTP step
      setLoginStep('otp');
      setOtp('');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  // Step 2: Submit OTP and complete login
  const handleOtpSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setIsLoading(true);

    try {
      const response = await fetch('/api/auth/verify-login-otp', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email.trim().toLowerCase(), otp: otp.trim() }),
      });

      const data: LoginResponse = await response.json();

      if (!response.ok) {
        throw new Error((data as any).error || 'Invalid or expired verification code.');
      }

      // Authenticated — clear stale session data
      sessionStorage.clear();

      if (data.tenantId) {
        sessionStorage.setItem('selectedTenantId', data.tenantId);
      }

      console.log('Login successful, fetching user roles and accounts...');
      await Promise.all([fetchUserRoles(), fetchAccounts()]);

      setTimeout(() => {
        navigate('/unified-dashboard', { replace: true });
      }, 200);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Verification failed. Please try again.');
      setIsLoading(false);
    }
  };

  return (
    <>
      {/* Error Alert */}
      {error && (
        <div className="error-box" role="alert" aria-live="assertive">
          <i className="fas fa-exclamation-triangle"></i>
          <div className="error-alert-content">
            <h4 className="error-title">Access Denied</h4>
            <p className="error-msg">{error}</p>
          </div>
          <button
            type="button"
            className="error-dismiss-btn"
            onClick={() => setError(null)}
            aria-label="Dismiss error"
          >
            <i className="fas fa-times"></i>
          </button>
        </div>
      )}

      {/* Step 1: Email + Password */}
      {loginStep === 'credentials' && (
        <form onSubmit={handleCredentialsSubmit} id="loginForm" className="form-stack">
          <div className="input-group">
            <label htmlFor="email" className="label-text">
              Email Address
            </label>
            <div className="input-container">
              <i className="fas fa-envelope input-icon-left"></i>
              <input
                type="email"
                id="email"
                name="email"
                className="input-field"
                placeholder="name@company.com"
                required
                autoFocus
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </div>
          </div>

          <div className="input-group">
            <div className="label-row">
              <label htmlFor="password" className="label-text">
                Security Password
              </label>
              <button
                type="button"
                onClick={onForgotPassword}
                className="text-link"
                style={{ fontSize: '0.8125rem' }}
              >
                Recover Password
              </button>
            </div>
            <div className="input-container">
              <i className="fas fa-key-skeleton input-icon-left"></i>
              <input
                type={showPassword ? 'text' : 'password'}
                id="password"
                name="password"
                className="input-field pr-12"
                placeholder="••••••••"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
              <button
                type="button"
                className="password-toggle-btn"
                onClick={togglePasswordVisibility}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                <i className={`fas ${showPassword ? 'fa-eye-slash' : 'fa-eye'}`}></i>
              </button>
            </div>
          </div>

          <div className="options-row mb-2">
            <label className="checkbox-wrap">
              <input
                type="checkbox"
                className="hidden-checkbox"
                id="rememberMe"
                name="rememberMe"
                checked={rememberMe}
                onChange={(e) => setRememberMe(e.target.checked)}
              />
              <span>Stay signed in for smoother access</span>
            </label>
          </div>

          <button type="submit" className="submit-btn shimmer-effect" disabled={isLoading}>
            {isLoading ? (
              <>
                <div className="loading-ring"></div>
                <span>Sending Verification Code...</span>
              </>
            ) : (
              <>
                <span>Send Verification Code</span>
                <i className="fas fa-paper-plane ml-2"></i>
              </>
            )}
          </button>
        </form>
      )}

      {/* Step 2: OTP Verification */}
      {loginStep === 'otp' && (
        <div className="form-stack">
          <div className="success-box" style={{ marginBottom: '1.5rem' }}>
            <i className="fas fa-envelope-circle-check"></i>
            <span>
              Verification code sent to <strong>{email}</strong>. Check your inbox.
            </span>
          </div>

          <form onSubmit={handleOtpSubmit} className="form-stack">
            <div className="input-group">
              <label htmlFor="otp" className="label-text">
                Verification Code
              </label>
              <div className="input-container">
                <i className="fas fa-shield-check input-icon-left"></i>
                <input
                  type="text"
                  id="otp"
                  name="otp"
                  className="input-field otp-input"
                  placeholder="Enter 6-digit code"
                  maxLength={6}
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  required
                  autoFocus
                  value={otp}
                  onChange={(e) => setOtp(e.target.value.replace(/\D/g, ''))}
                />
              </div>
            </div>

            <button type="submit" className="submit-btn shimmer-effect" disabled={isLoading}>
              {isLoading ? (
                <>
                  <div className="loading-ring"></div>
                  <span>Verifying Identity...</span>
                </>
              ) : (
                <>
                  <span>Authorize & Sign In</span>
                  <i className="fas fa-shield-check ml-2"></i>
                </>
              )}
            </button>
          </form>

          <button
            type="button"
            className="back-trigger"
            style={{ marginTop: '1rem' }}
            onClick={() => {
              setLoginStep('credentials');
              setOtp('');
              setError(null);
            }}
          >
            <i className="fas fa-arrow-left"></i>
            <span>Back to login</span>
          </button>
        </div>
      )}
    </>
  );
};

export default LoginForm;
