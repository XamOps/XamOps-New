import React, { useState, useCallback, useRef, useEffect } from 'react';
import LoginForm from '../components/Login/LoginForm';
import ForgotPassword from '../components/Login/ForgotPassword';
import '../styles/login.css';

const LoginPage: React.FC = () => {
  const [showForgotPassword, setShowForgotPassword] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const frameRef = useRef<number>(0);

  const handleToggleForgotPassword = useCallback(() => {
    setShowForgotPassword(true);
  }, []);

  const handleBackToLogin = useCallback(() => {
    setShowForgotPassword(false);
  }, []);

  // Drives the cursor-revealed grid; pure CSS reads these variables,
  // so React never re-renders on mouse move.
  const handleMouseMove = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    const { clientX, clientY } = event;
    cancelAnimationFrame(frameRef.current);
    frameRef.current = requestAnimationFrame(() => {
      const container = containerRef.current;
      if (!container) return;
      container.style.setProperty('--mx', `${clientX}px`);
      container.style.setProperty('--my', `${clientY}px`);
    });
  }, []);

  useEffect(() => () => cancelAnimationFrame(frameRef.current), []);

  return (
    <div className="login-container" ref={containerRef} onMouseMove={handleMouseMove}>
      {/* Quiet grid, gently revealed around the cursor */}
      <div className="bg-scene" aria-hidden="true">
        <div className="bg-grid" />
        <div className="bg-spotlight" />
      </div>

      <div className="auth-stage">
        <div className="split-card">
          {/* Left — brand panel */}
          <aside className="brand-panel">
            <div className="panel-brand">
              <span className="lp-wordmark">XamOps</span>
            </div>

            <h2 className="panel-headline">
              Cut <strong>Cloud Costs</strong>
              <br />
              Without Any Hassle
            </h2>

            <div className="panel-visual" aria-hidden="true">
              {/* Dashboard composite — window with live bars, stat card, savings chip */}
              <div className="lp-composite">
                <div className="lp-window">
                  <div className="lp-window-bar">
                    <span className="lp-dot lp-dot-r" />
                    <span className="lp-dot lp-dot-y" />
                    <span className="lp-dot lp-dot-g" />
                    <em className="lp-title-rotator">
                      <span className="lp-title-track">
                        <span>Cost Explorer</span>
                        <span>Spend Forecast</span>
                        <span>Security Findings</span>
                        <span>Cost Explorer</span>
                      </span>
                    </em>
                  </div>

                  {/* Scenes crossfade every 4s: bars → line → donut */}
                  <div className="lp-scenes">
                    <div className="lp-scene">
                      <div className="lp-bars">
                        <span style={{ ['--h' as string]: '92%' }} />
                        <span style={{ ['--h' as string]: '78%' }} />
                        <span style={{ ['--h' as string]: '86%' }} />
                        <span style={{ ['--h' as string]: '64%' }} />
                        <span style={{ ['--h' as string]: '71%' }} />
                        <span style={{ ['--h' as string]: '52%' }} />
                        <span style={{ ['--h' as string]: '58%' }} />
                        <span style={{ ['--h' as string]: '42%' }} />
                        <span style={{ ['--h' as string]: '47%' }} />
                        <span style={{ ['--h' as string]: '34%' }} />
                        <span style={{ ['--h' as string]: '38%' }} />
                        <span style={{ ['--h' as string]: '28%' }} />
                      </div>
                    </div>

                    <div className="lp-scene lp-scene-line">
                      <svg viewBox="0 0 240 100" preserveAspectRatio="none">
                        <line x1="0" y1="33" x2="240" y2="33" className="lp-svg-grid" />
                        <line x1="0" y1="66" x2="240" y2="66" className="lp-svg-grid" />
                        <path
                          className="lp-svg-area"
                          d="M0,72 C30,62 45,42 70,50 C95,58 110,32 135,36 C160,40 175,20 200,24 C220,27 232,16 240,14 L240,100 L0,100 Z"
                        />
                        <path
                          className="lp-svg-line"
                          d="M0,72 C30,62 45,42 70,50 C95,58 110,32 135,36 C160,40 175,20 200,24 C220,27 232,16 240,14"
                        />
                      </svg>
                    </div>

                    <div className="lp-scene lp-scene-donut">
                      <div className="lp-donut">
                        <div className="lp-donut-hole">
                          <b>350</b>
                          <small>findings</small>
                        </div>
                      </div>
                      <ul className="lp-donut-legend">
                        <li><span className="lp-lg-dot" style={{ background: '#ef4444' }} />Critical <b>253</b></li>
                        <li><span className="lp-lg-dot" style={{ background: '#f97316' }} />High <b>17</b></li>
                        <li><span className="lp-lg-dot" style={{ background: '#f59e0b' }} />Medium <b>45</b></li>
                        <li><span className="lp-lg-dot" style={{ background: '#22c55e' }} />Low <b>35</b></li>
                      </ul>
                    </div>
                  </div>

                  <div className="lp-window-foot">
                    <span>AWS · Azure · GCP</span>
                    <span className="lp-foot-trend">↓ spend</span>
                  </div>
                </div>

                <div className="lp-stat-card">
                  <div className="lp-stat-top">
                    <span className="lp-stat-value">32%</span>
                    <span className="lp-stat-arrow">↗</span>
                  </div>
                  <div className="lp-stat-spark">
                    <span /><span /><span /><span /><span /><span />
                  </div>
                  <div className="lp-stat-label">
                    Monthly savings <b>$4.2K</b>
                  </div>
                </div>

                <div className="lp-save-chip">
                  <img src="/icons/aws-icon.png" alt="" />
                  <div className="lp-chip-text">
                    <b>EC2 Rightsizing</b>
                    <small>27 instances</small>
                  </div>
                  <span className="lp-chip-badge">–$178/mo</span>
                </div>
              </div>
            </div>

            <div className="panel-floor" />
          </aside>

          {/* Right — form panel */}
          <section className="form-panel">
            <div className="form-brand">
              <span className="lp-wordmark lp-wordmark-sm">XamOps</span>
            </div>

            {!showForgotPassword ? (
              <>
                <div className="brand-header">
                  <h1 className="brand-title">Hey! Welcome</h1>
                  <p className="brand-subtitle">
                    Sign in to keep your cloud spend in check.
                  </p>
                </div>

                <LoginForm onForgotPassword={handleToggleForgotPassword} />
              </>
            ) : (
              <ForgotPassword onBackToLogin={handleBackToLogin} />
            )}

            <div className="footer-note">
              <p className="footer-text">
                Already logged in?{' '}
                <a href="/unified-dashboard" className="text-link">Go to Dashboard →</a>
              </p>
              <p className="footer-text mt-2 opacity-50">© 2025 Xammer. All rights reserved.</p>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
