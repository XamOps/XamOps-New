import React, { useState, FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';

interface LoginFormProps {
  onForgotPassword: () => void;
}

interface LoginResponse {
  tenantId?: string;
  redirectUrl?: string;
}

const LoginForm: React.FC<LoginFormProps> = ({ onForgotPassword }) => {
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setIsLoading(true);

    console.log('🔵 Login attempt started');
    console.log('📧 Username:', username);
    console.log('🍪 Cookies before login:', document.cookie);

    // Create FormData exactly as the original code does
    const formData = new URLSearchParams();
    formData.append('username', username);
    formData.append('password', password);
    if (rememberMe) {
      formData.append('rememberMe', 'on');
    }

    try {
      // Use the exact same endpoint and configuration as the original
      const response = await fetch('/login', {
        method: 'POST',
        body: formData,
        credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      });

      console.log('📡 Response status:', response.status);
      console.log('📡 Response URL:', response.url);
      console.log('🍪 Cookies after login:', document.cookie);

      // Check if redirected to login?error (authentication failed)
      if (response.url.includes('/login?error')) {
        throw new Error('Invalid username or password');
      }

      if (response.ok) {
        const contentType = response.headers.get('content-type');
        console.log('✅ Login successful!');
        console.log('📄 Content-Type:', contentType);

        if (contentType && contentType.includes('application/json')) {
          const data: LoginResponse = await response.json();
          console.log('📦 Response data:', data);

          if (data.tenantId) {
            sessionStorage.setItem('selectedTenantId', data.tenantId);
            console.log('💾 Saved tenantId:', data.tenantId);
          }

          // Use navigate instead of window.location to avoid full page reload
          console.log('🔄 Redirecting to /unified-dashboard...');
          // Small delay to ensure cookies are set
          setTimeout(() => {
            navigate('/unified-dashboard', { replace: true });
          }, 200);
        } else {
          // If response is not JSON, navigate to unified dashboard
          console.log('🔄 Redirecting to /unified-dashboard (non-JSON response)...');
          setTimeout(() => {
            navigate('/unified-dashboard', { replace: true });
          }, 200);
        }
      } else {
        throw new Error(`Authentication failed: Status ${response.status}`);
      }
    } catch (err) {
      console.error('❌ Login error:', err);
      setError(err instanceof Error ? err.message : 'Invalid credentials. Please verify and try again.');
      setIsLoading(false);
    }
  };

  return (
    <>
      {/* Error Alert */}
      {error && (
        <div className="error-alert" role="alert">
          <i className="fas fa-exclamation-triangle"></i>
          <div className="error-alert-content">
            <h4>Authentication Failed</h4>
            <p>{error}</p>
          </div>
        </div>
      )}

      {/* Login Form */}
      <form onSubmit={handleSubmit} id="loginForm">
        <div className="input-group">
          <label htmlFor="username" className="input-label">
            Username
          </label>
          <div className="input-wrapper">
            <i className="fas fa-user input-icon"></i>
            <input
              type="text"
              id="username"
              name="username"
              className="form-input"
              placeholder="Enter your username"
              required
              autoFocus
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </div>
        </div>

        <div className="input-group">
          <label htmlFor="password" className="input-label">
            Password
          </label>
          <div className="input-wrapper">
            <i className="fas fa-lock input-icon"></i>
            <input
              type="password"
              id="password"
              name="password"
              className="form-input"
              placeholder="Enter your password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
        </div>

        <div className="form-row">
          <label className="checkbox-label">
            <input
              type="checkbox"
              className="checkbox-input"
              id="rememberMe"
              name="rememberMe"
              checked={rememberMe}
              onChange={(e) => setRememberMe(e.target.checked)}
            />
            <span>Remember me</span>
          </label>
          <button
            type="button"
            onClick={onForgotPassword}
            className="forgot-link"
          >
            Forgot password?
          </button>
        </div>

        <button type="submit" className="btn-primary" disabled={isLoading}>
          {isLoading ? (
            <>
              <span>Authenticating...</span>
              <span className="loading-dots">
                <span></span>
                <span></span>
                <span></span>
              </span>
            </>
          ) : (
            <span>Sign In</span>
          )}
        </button>
      </form>
    </>
  );
};

export default LoginForm;
