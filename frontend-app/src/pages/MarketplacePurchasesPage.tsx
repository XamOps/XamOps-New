/**
 * MarketPlace Purchases Page Component
 *
 * Unified cloud marketplace subscription management for AWS, Azure, and GCP
 *
 * Converted from: frontend-app/billops/marketplace-purchases.html
 *
 * Features:
 * - Coming Soon announcement with professional design
 * - Feature showcase cards
 * - Animated elements
 * - Ready for future data integration
 */

import React from 'react';

const MarketplacePurchasesPage: React.FC = () => {
  return (
    <div className="marketplace-purchases-page">
      {/* Header Bar */}
      <header className="marketplace-header">
        <div className="header-content">
          <div className="header-icon-wrapper">
            <div className="header-icon">
              <i className="fas fa-shopping-cart" />
            </div>
          </div>
          <div className="header-text">
            <h1 className="header-title">MarketPlace Purchases</h1>
            <p className="header-subtitle">
              <i className="fas fa-cloud" />
              Unified cloud marketplace subscription management
            </p>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <div className="marketplace-content">
        <div className="coming-soon-container">
          <div className="coming-soon-card">
            {/* Background Pattern */}
            <div className="background-pattern" />

            {/* Content */}
            <div className="card-content">
              {/* Animated Icon */}
              <div className="icon-container">
                <div className="animated-icon-wrapper">
                  <div className="animated-icon">
                    <i className="fas fa-shopping-cart" />
                  </div>
                  <div className="icon-glow" />
                </div>
              </div>

              {/* Title */}
              <h2 className="coming-soon-title">
                <span className="gradient-text">Coming Soon</span>
              </h2>
              <p className="coming-soon-subtitle">MarketPlace Purchases</p>
              <p className="coming-soon-description">
                Unified view of all your cloud marketplace subscriptions. Track AWS, Azure, and GCP
                marketplace spending in one single dashboard with powerful analytics and insights.
              </p>

              {/* Feature Grid */}
              <div className="features-grid">
                <div className="feature-card border-pink">
                  <div className="feature-content">
                    <div className="feature-icon-wrapper bg-pink">
                      <i className="fas fa-file-invoice-dollar" />
                    </div>
                    <div className="feature-text">
                      <h3 className="feature-title">Consolidated Billing</h3>
                      <p className="feature-description">
                        Merge marketplace invoices from multiple clouds into a single unified view
                      </p>
                    </div>
                  </div>
                </div>

                <div className="feature-card border-rose">
                  <div className="feature-content">
                    <div className="feature-icon-wrapper bg-rose">
                      <i className="fas fa-chart-pie" />
                    </div>
                    <div className="feature-text">
                      <h3 className="feature-title">Spend Analytics</h3>
                      <p className="feature-description">
                        Analyze trends and patterns in your third-party software costs
                      </p>
                    </div>
                  </div>
                </div>

                <div className="feature-card border-purple">
                  <div className="feature-content">
                    <div className="feature-icon-wrapper bg-purple">
                      <i className="fas fa-bell" />
                    </div>
                    <div className="feature-text">
                      <h3 className="feature-title">Smart Alerts</h3>
                      <p className="feature-description">
                        Get notified about subscription renewals and spending anomalies
                      </p>
                    </div>
                  </div>
                </div>

                <div className="feature-card border-indigo">
                  <div className="feature-content">
                    <div className="feature-icon-wrapper bg-indigo">
                      <i className="fas fa-sync-alt" />
                    </div>
                    <div className="feature-text">
                      <h3 className="feature-title">Auto-Sync</h3>
                      <p className="feature-description">
                        Automatically sync subscriptions across AWS, Azure, and GCP marketplaces
                      </p>
                    </div>
                  </div>
                </div>
              </div>

              {/* Launch Badge */}
              <div className="launch-badge">
                <i className="fas fa-rocket" />
                <span className="launch-text">Target Launch</span>
                <span className="launch-date">Q1 2026</span>
              </div>

              {/* Additional Info */}
              <div className="info-cards">
                <div className="info-card">
                  <i className="fas fa-cloud info-icon" />
                  <div>
                    <p className="info-title">Multi-Cloud Support</p>
                    <p className="info-text">AWS, Azure & GCP</p>
                  </div>
                </div>
                <div className="info-card">
                  <i className="fas fa-chart-line info-icon" />
                  <div>
                    <p className="info-title">Real-Time Updates</p>
                    <p className="info-text">Live marketplace data</p>
                  </div>
                </div>
                <div className="info-card">
                  <i className="fas fa-shield-alt info-icon" />
                  <div>
                    <p className="info-title">Secure & Compliant</p>
                    <p className="info-text">Enterprise-grade security</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default MarketplacePurchasesPage;
