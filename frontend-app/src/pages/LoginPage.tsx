import React, { useState, useCallback } from 'react';
import LoginForm from '../components/Login/LoginForm';
import ForgotPassword from '../components/Login/ForgotPassword';
import '../styles/login.css';

const LoginPage: React.FC = () => {
  const [showForgotPassword, setShowForgotPassword] = useState(false);

  const handleToggleForgotPassword = useCallback(() => {
    setShowForgotPassword(true);
  }, []);

  const handleBackToLogin = useCallback(() => {
    setShowForgotPassword(false);
  }, []);

  return (
    <div className="login-container">
      {/* Dynamic Background */}
      <div className="bg-blur-circle circle-1"></div>
      <div className="bg-blur-circle circle-2"></div>
      <div className="grid-overlay"></div>

      {/* Left Section - Branding & Value Props */}
      <div className="visual-section">
        <div className="brand-hero">
          <div className="hero-tag">
            <i className="fas fa-sparkles mr-2"></i>
            Next-Gen Cloud Management
          </div>
          <h1 className="hero-title">
            Optimize your <br />
            <span className="hero-gradient">Cloud Operations</span>
          </h1>
          <p className="hero-subtitle">
            Enterprise-grade FinOps and DevOps platform. <br />
            Manage AWS, Azure, and GCP with precision and ease.
          </p>

          <div className="feature-glass-grid">
            <div className="feature-pill">
              <div className="pill-icon">
                <i className="fas fa-chart-line text-white"></i>
              </div>
              <span className="pill-text">Cost Analytics</span>
            </div>
            <div className="feature-pill">
              <div className="pill-icon">
                <i className="fas fa-bolt text-white"></i>
              </div>
              <span className="pill-text">Smart Automation</span>
            </div>
            <div className="feature-pill">
              <div className="pill-icon">
                <i className="fas fa-shield-alt text-white"></i>
              </div>
              <span className="pill-text">Security Ops</span>
            </div>
            <div className="feature-pill">
              <div className="pill-icon">
                <i className="fas fa-sync text-white"></i>
              </div>
              <span className="pill-text">CI/CD Control</span>
            </div>
          </div>
        </div>
      </div>

      {/* Right Section - Login/Forgot Password Card */}
      <div className="form-section">
        <div className="login-card-transparent">
          {!showForgotPassword ? (
            <>
              <div className="brand-header">
                <span className="welcome-label">Welcome to</span>
                <h1 className="brand-title">
                  XamOps <span className="beta-badge">BETA</span>
                </h1>
                <p className="brand-subtitle">Cloud FinOps & DevOps Platform</p>
                <p className="form-instruction">Please enter your details to sign in</p>
              </div>

              <LoginForm onForgotPassword={handleToggleForgotPassword} />
            </>
          ) : (
            <ForgotPassword onBackToLogin={handleBackToLogin} />
          )}

          <div className="footer-note">
            <p className="footer-text">
              Already logged in? <a href="/unified-dashboard" className="text-link">Go to Dashboard →</a>
            </p>
            <p className="footer-text mt-2">
              Secure • Compliant • <span className="footer-highlight">Enterprise Ready</span>
            </p>
            <p className="footer-text mt-1 opacity-50">
              © 2025 Xammer. All rights reserved.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
