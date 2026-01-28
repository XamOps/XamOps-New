/**
 * LoginPage Component
 *
 * Converted from: index.html
 *
 * What changed:
 * - Migrated from vanilla HTML/JS to React with TypeScript
 * - Split UI into reusable components (LoginForm, ForgotPassword, etc.)
 * - Added proper type safety for all state and API calls
 * - Used React hooks (useState, useCallback) for state management
 * - Modern design with improved UX (you allowed design changes for index.html)
 *
 * What stayed the same:
 * - All API endpoints remain identical (/login, /api/auth/*)
 * - Same HTTP methods and request formats (URLSearchParams for login, query params for password reset)
 * - Same response handling and redirects
 * - Same credentials handling (cookies, sessionStorage for tenantId)
 * - All business logic preserved (3-step password reset flow, form validation)
 */

import React, { useState, useCallback, useEffect } from 'react';
import LoginForm from '../components/Login/LoginForm';
import ForgotPassword from '../components/Login/ForgotPassword';
import CloudProviderIcons from '../components/Login/CloudProviderIcons';
import '../styles/login.css';

const LoginPage: React.FC = () => {
  const [showForgotPassword, setShowForgotPassword] = useState(false);

  // Disabled: This was causing a redirect loop
  // The ProtectedRoute component handles authentication checks
  // If user is already logged in and tries to access /, they can just click "Unified Dashboard" link

  const handleToggleForgotPassword = useCallback(() => {
    setShowForgotPassword(true);
  }, []);

  const handleBackToLogin = useCallback(() => {
    setShowForgotPassword(false);
  }, []);

  return (
    <div className="login-container">
      {/* Left Section - Visual with Cloud Provider Icons */}
      <div className="left-section">
        <div className="grid-background"></div>
        <div className="shape shape-1"></div>
        <div className="shape shape-2"></div>
        <div className="shape shape-3"></div>
        <CloudProviderIcons />
      </div>

      {/* Right Section - Form */}
      <div className="right-section">
        <div className="form-container">
          {!showForgotPassword ? (
            <>
              {/* Logo Section */}
              <div className="logo-section">
                <h1 className="brand-title">
                  <span className="welcome-text">Welcome to</span>
                  <br />
                  <span className="brand-gradient">XamOps</span>
                  <sup className="beta-badge">beta</sup>
                </h1>
                <p className="brand-subtitle">Cloud FinOps & DevOps Platform</p>
              </div>

              {/* Login Form */}
              <LoginForm onForgotPassword={handleToggleForgotPassword} />
            </>
          ) : (
            /* Forgot Password Flow */
            <ForgotPassword onBackToLogin={handleBackToLogin} />
          )}

          {/* Footer */}
          <div className="form-footer">
            <p className="footer-text">
              Already logged in? <a href="/unified-dashboard" className="forgot-link">Go to Dashboard →</a>
            </p>
            <p className="footer-text">Secure • Compliant • Enterprise Ready</p>
            <p className="footer-text footer-copyright">
              © 2025 Xammer. All rights reserved.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
