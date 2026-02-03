import React, { useState, FormEvent } from 'react';
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

const LoginForm: React.FC<LoginFormProps> = ({ onForgotPassword }) => {
  const navigate = useNavigate();
  const { fetchUserRoles } = useAuth();
  const { fetchAccounts } = useAccounts();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const togglePasswordVisibility = () => {
    setShowPassword(!showPassword);
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setIsLoading(true);

    const formData = new URLSearchParams();
    formData.append('username', username);
    formData.append('password', password);
    if (rememberMe) {
      formData.append('rememberMe', 'on');
    }

    try {
      const response = await fetch('/login', {
        method: 'POST',
        body: formData,
        credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      });

      if (response.url.includes('/login?error')) {
        throw new Error('Invalid username or password');
      }

      if (response.ok) {
        const contentType = response.headers.get('content-type');
        if (contentType?.includes('application/json')) {
          const data: LoginResponse = await response.json();
          if (data.tenantId) {
            sessionStorage.setItem('selectedTenantId', data.tenantId);
          }
        }

        // Trigger data fetching immediately after login
        console.log('🔄 Login successful, fetching user roles and accounts...');
        await Promise.all([
          fetchUserRoles(),
          fetchAccounts()
        ]);

        setTimeout(() => {
          navigate('/unified-dashboard', { replace: true });
        }, 200);
      } else {
        throw new Error(`Authentication failed: Status ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Invalid credentials. Please verify and try again.');
      setIsLoading(false);
    }
  };

  return (
    <>
      {/* Error Alert */}
      {error && (
        <div className="error-box" role="alert">
          <i className="fas fa-exclamation-triangle"></i>
          <div className="error-alert-content">
            <h4 className="error-title">Access Denied</h4>
            <p className="error-msg">{error}</p>
          </div>
        </div>
      )}

      {/* Login Form */}
      <form onSubmit={handleSubmit} id="loginForm" className="form-stack">
        <div className="input-group">
          <label htmlFor="username" className="label-text">
            Username or Email
          </label>
          <div className="input-container">
            <i className="fas fa-user-circle input-icon-left"></i>
            <input
              type="text"
              id="username"
              name="username"
              className="input-field"
              placeholder="name@company.com"
              required
              autoFocus
              value={username}
              onChange={(e) => setUsername(e.target.value)}
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
              aria-label={showPassword ? "Hide password" : "Show password"}
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
    </>
  );
};

export default LoginForm;
