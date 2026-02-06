/**
 * Sidebar Component
 *
 * Main navigation sidebar with account selection, role-based menu items, and responsive design
 *
 * Converted from: _sidebar.html
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Split into reusable sub-components (AccountSelector, NavItem, NavSection, etc.)
 * - Used Context API for global state (accounts, auth, roles)
 * - Added proper type safety
 * - Used React hooks for state management
 *
 * What stayed the same:
 * - All API endpoints remain identical
 * - Same navigation logic and provider-aware routing
 * - Same role-based access control
 * - Same multi-account mode functionality
 * - Same super admin features (tenant switching, impersonation)
 * - Same visual design and animations
 * - All business logic preserved exactly
 */

import React, { useState, useCallback } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../../contexts/AuthContext';
import { useAccounts } from '../../contexts/AccountContext';
import AccountSelector from './AccountSelector';
import NavItem from './NavItem';
import NavSection from './NavSection';
import clsx from 'clsx';

const Sidebar: React.FC = () => {
  const location = useLocation();
  const { hasAnyRole } = useAuth();
  const { selectedAccountDisplay, accounts, selectedAccountIds } = useAccounts();

  // Dropdown state - only one can be open at a time
  const [openDropdown, setOpenDropdown] = useState<string | null>(null);
  const [accountSelectorOpen, setAccountSelectorOpen] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  /**
   * Toggle dropdown (only one can be open at a time)
   */
  const toggleDropdown = useCallback((dropdownId: string) => {
    setOpenDropdown(prev => (prev === dropdownId ? null : dropdownId));
  }, []);

  /**
   * Close account selector
   */
  const closeAccountSelector = useCallback(() => {
    setAccountSelectorOpen(false);
  }, []);

  /**
   * Toggle mobile menu
   */
  const toggleMobileMenu = useCallback(() => {
    setMobileMenuOpen(prev => !prev);
  }, []);

  /**
   * Close mobile menu
   */
  const closeMobileMenu = useCallback(() => {
    setMobileMenuOpen(false);
  }, []);

  /**
   * Get billing URL based on selected account provider
   */
  const getBillingUrl = useCallback((): string => {
    // Get the first selected account to determine provider
    if (selectedAccountIds.length > 0) {
      const selectedAccount = accounts.find(acc => acc.id === selectedAccountIds[0]);
      if (selectedAccount) {
        switch (selectedAccount.provider) {
          case 'GCP':
            return '/billops-gcp-billing';
          case 'Azure':
            return '/billops/azure-billing.html';
          case 'AWS':
          default:
            return '/billops-billing';
        }
      }
    }
    // Default to AWS billing
    return '/billops-billing';
  }, [selectedAccountIds, accounts]);

  /**
   * Check if current path matches the nav item
   */
  const isActive = useCallback((path: string): boolean => {
    const currentPath = location.pathname;

    // Special case: Root path matches both / and /index.html
    if (path === '/') {
      return currentPath === '/' || currentPath === '/index.html';
    }

    if (currentPath === path) return true;

    // Special case: Dashboard matches all provider dashboards (React route)
    if (path === '/dashboard') {
      return (
        currentPath === '/dashboard' ||
        currentPath === '/dashboard.html' ||
        currentPath === '/gcp-dashboard' ||
        currentPath === '/gcp_dashboard.html' ||
        currentPath === '/azure-dashboard' ||
        currentPath === '/azure_dashboard.html'
      );
    }

    // Special case: Cost Management matches both React route and HTML
    if (path === '/cost') {
      return currentPath === '/cost' || currentPath === '/cost.html';
    }

    // Special case: GCP Cost Management matches both React route and HTML
    if (path === '/gcp-cost') {
      return currentPath === '/gcp-cost' || currentPath === '/gcp_cost.html';
    }

    // Special case: Waste Management matches both React route and HTML
    if (path === '/waste') {
      return currentPath === '/waste' || currentPath === '/waste.html';
    }

    // Special case: GCP Waste Management matches both React route and HTML
    if (path === '/gcp-waste') {
      return currentPath === '/gcp-waste' || currentPath === '/gcp_waste.html';
    }

    // Special case: Rightsizing matches both React route and HTML
    if (path === '/rightsizing') {
      return currentPath === '/rightsizing' || currentPath === '/rightsizing.html';
    }

    // Special case: GCP Rightsizing matches both React route and HTML
    if (path === '/gcp-rightsizing') {
      return currentPath === '/gcp-rightsizing' || currentPath === '/gcp_rightsizing.html';
    }


    // Special case: Azure CloudList matches both React route and HTML
    if (path === '/azure-cloudlist') {
      return currentPath === '/azure-cloudlist' || currentPath === '/azure_cloudlist.html';
    }

    // Special case: Reservation matches both React route and HTML
    if (path === '/reservation') {
      return currentPath === '/reservation' || currentPath === '/reservation.html';
    }

    // Special case: GCP Reservations matches both React route and HTML
    if (path === '/gcp-reservations') {
      return currentPath === '/gcp-reservations' || currentPath === '/gcp_reservations.html';
    }

    // Special case: CloudSitter matches both React route and HTML
    if (path === '/cloudsitter') {
      return currentPath === '/cloudsitter' || currentPath === '/cloudsitter.html';
    }

    // Special case: Spot Automation matches both React route and HTML
    if (path === '/spot-automation') {
      return currentPath === '/spot-automation' || currentPath === '/spot-automation.html';
    }

    // Special case: Grafana Dashboard matches both React route and HTML
    if (path === '/grafana-dashboard') {
      return currentPath === '/grafana-dashboard' || currentPath === '/grafana-dashboard.html';
    }

    // Special case: CloudK8s matches both React route and HTML, plus Details page
    if (path === '/cloudk8s') {
      return (
        currentPath === '/cloudk8s' ||
        currentPath === '/cloudk8s.html' ||
        currentPath === '/gcp-cloudk8s' ||
        currentPath === '/gcp_cloudk8s.html' ||
        currentPath === '/azure-cloudk8s' ||
        currentPath.startsWith('/eks-details') ||
        currentPath.startsWith('/gke-details') ||
        currentPath.startsWith('/aks-details')
      );
    }

    // Special case: Security matches both React route and HTML
    if (path === '/security') {
      return currentPath === '/security' || currentPath === '/security.html';
    }

    // Special case: GCP Security matches both React route and HTML
    if (path === '/gcp-security') {
      return currentPath === '/gcp-security' || currentPath === '/gcp_security.html';
    }

    // Special case: ComplianceOps matches both React route and HTML
    if (path === '/complianceops') {
      return currentPath === '/complianceops' || currentPath === '/complianceops.html';
    }

    // Special case: FinOps matches both React route and HTML
    if (path === '/finops') {
      return currentPath === '/finops' || currentPath === '/finops.html';
    }

    // Special case: GCP FinOps matches both React route and HTML
    if (path === '/gcp-finops') {
      return currentPath === '/gcp-finops' || currentPath === '/gcp_finops.html';
    }

    // Special case: Azure FinOps matches both React route and HTML
    if (path === '/azure-finops') {
      return currentPath === '/azure-finops' || currentPath === '/azure-finops-report.html';
    }

    // Special case: Performance matches both React route and HTML
    if (path === '/performance') {
      return currentPath === '/performance' || currentPath === '/performance.html' || currentPath === '/gcp-performance' || currentPath === '/gcp_performance.html';
    }

    // Special case: GCP Performance matches both React route and HTML
    if (path === '/gcp-performance') {
      return currentPath === '/gcp-performance' || currentPath === '/gcp_performance.html';
    }

    // Special case: Alerts matches both React route and HTML
    if (path === '/alerts') {
      return currentPath === '/alerts' || currentPath === '/alerts.html';
    }

    // Special case: Tickets matches both React route and HTML
    if (path === '/tickets') {
      return (
        currentPath === '/tickets' ||
        currentPath === '/xamops_tickets.html' ||
        currentPath === '/ticket-detail' ||
        currentPath.startsWith('/xamops_ticket_detail')
      );
    }


    // Special case: Account Manager matches both React route and HTML
    if (path === '/account-manager') {
      return currentPath === '/account-manager' || currentPath === '/account-manager.html';
    }

    // Special case: Add Account matches both React route and HTML
    if (path === '/add-account') {
      return currentPath === '/add-account' || currentPath === '/add-account.html';
    }

    // Special case: DevOps-in-the-Box matches both React route and HTML
    if (path === '/devops-in-the-box') {
      return currentPath === '/devops-in-the-box' || currentPath === '/devops_in_the_box.html';
    }

    // Special case: CICD Pipelines matches both React route and HTML
    if (path === '/cicd-pipelines') {
      return currentPath === '/cicd-pipelines' || currentPath === '/cicd_pipelines.html';
    }

    // Special case: SonarQube matches both React route and HTML
    if (path === '/sonarqube') {
      return currentPath === '/sonarqube' || currentPath === '/sonarqube.html';
    }

    // Special case: AIOps matches both React route and HTML
    if (path === '/aiops') {
      return currentPath === '/aiops' || currentPath === '/aiops.html';
    }

    // Special case: DataOps matches both React route and HTML
    if (path === '/dataops') {
      return currentPath === '/dataops' || currentPath === '/dataops.html';
    }

    // Special case: BillOps Dashboard matches both React route and HTML
    if (path === '/billops-dashboard') {
      return currentPath === '/billops-dashboard' || currentPath === '/billops/dashboard_user.html';
    }

    // Special case: BillOps billing matches React route and HTML variants
    if (path === '/billops-billing') {
      return currentPath === '/billops-billing' ||
        currentPath === '/billops-gcp-billing' ||
        currentPath === '/billops/billing.html' ||
        currentPath === '/billops/gcp-billing.html' ||
        currentPath === '/billops/azure-billing.html';
    }

    // Special case: BillOps invoices matches React route and HTML variants
    if (path === '/billops-invoices') {
      return currentPath === '/billops-invoices' ||
        currentPath === '/billops/invoices.html' ||
        currentPath === '/billops/invoices_user.html' ||
        currentPath === '/billops/invoices_admin.html';
    }

    // Special case: BillOps credits matches React route and HTML variants
    if (path === '/billops-credits') {
      return currentPath === '/billops-credits' ||
        currentPath === '/billops/credits.html';
    }

    // Special case: BillOps tickets matches React route and HTML variants
    if (path === '/billops-tickets') {
      return currentPath === '/billops-tickets' ||
        currentPath === '/billops-ticket-detail' ||
        currentPath === '/billops/tickets.html' ||
        currentPath === '/billops/ticket_detail.html';
    }

    // Special case: BillOps agreements matches React route and HTML variants
    if (path === '/billops-agreements') {
      return currentPath === '/billops-agreements' ||
        currentPath === '/billops/agreements.html';
    }

    // Special case: MarketPlace Purchases matches React route and HTML variants
    if (path === '/marketplace-purchases') {
      return currentPath === '/marketplace-purchases' ||
        currentPath === '/billops/marketplace-purchases.html';
    }

    // Special case: WorkSpace Licenses matches React route and HTML variants
    if (path === '/workspace-licenses') {
      return currentPath === '/workspace-licenses' ||
        currentPath === '/billops/workspace-licenses.html';
    }

    // Special case: User Management matches React route and HTML variants
    if (path === '/user-management') {
      return currentPath === '/user-management' ||
        currentPath === '/user-management.html' ||
        currentPath === '/Xamops_User_management.html';
    }

    // Special case: My Profile matches React route and HTML variants
    if (path === '/my-profile') {
      return currentPath === '/my-profile' ||
        currentPath === '/profile.html' ||
        currentPath === '/my-profile.html';
    }

    // Special case: BillOps Support Dashboard matches React route and HTML variants
    if (path === '/billops-support-dashboard') {
      return currentPath === '/billops-support-dashboard' ||
        currentPath === '/billops/support-dashboard.html';
    }

    // Special case: BillOps Admin Dashboard matches React route and HTML variants
    if (path === '/billops-admin-dashboard') {
      return currentPath === '/billops-admin-dashboard' ||
        currentPath === '/billops/dashboard_admin.html';
    }

    // Special case: BillOps Admin Invoices matches React route and HTML variants
    if (path === '/billops-admin-invoices') {
      return currentPath === '/billops-admin-invoices' ||
        currentPath === '/billops/admin_invoices.html' ||
        currentPath === '/billops/invoices_admin.html' ||
        currentPath.startsWith('/billops-admin-invoice-detail');
    }

    // Special case: BillOps Admin Credits matches React route and HTML variants
    if (path === '/billops-admin-credits') {
      return currentPath === '/billops-admin-credits' ||
        currentPath === '/billops/admin_credits.html';
    }

    // Special case: BillOps Admin Agreements matches React route and HTML variants
    if (path === '/billops-admin-agreements') {
      return currentPath === '/billops-admin-agreements' ||
        currentPath === '/billops/admin_agreements.html';
    }

    // Special case: BillOps Admin Tickets matches React route and HTML variants
    if (path === '/billops-admin-tickets') {
      return currentPath === '/billops-admin-tickets' ||
        currentPath === '/billops-ticket-detail' ||
        currentPath === '/billops/admin_tickets.html' ||
        currentPath === '/billops/tickets_admin.html' ||
        currentPath === '/billops/ticket_detail.html';
    }

    // Special case: BillOps Admin User Manager matches React route and HTML variants
    if (path === '/billops-admin-user-manager') {
      return currentPath === '/billops-admin-user-manager' ||
        currentPath === '/user-manager.html';
    }

    // Special case: BillOps section matches any billops path
    if (path === '/billops/') {
      return currentPath.startsWith('/billops/');
    }

    return false;
  }, [location.pathname]);


  return (
    <>
      {/* Mobile Hamburger Button */}
      <button
        onClick={toggleMobileMenu}
        className="lg:hidden fixed top-4 left-4 z-50 p-2 bg-white rounded-lg shadow-lg border border-gray-200 hover:bg-gray-50 transition-colors"
        aria-label="Toggle sidebar menu"
      >
        <svg className="w-6 h-6 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>

      {/* Mobile Overlay */}
      {mobileMenuOpen && (
        <div
          className="lg:hidden fixed inset-0 bg-black/50 z-20"
          onClick={closeMobileMenu}
        />
      )}

      {/* Sidebar */}
      <div
        id="tour-sidebar"
        className={clsx(
          'w-64 fixed top-0 left-0 h-screen flex flex-col bg-white shadow-xl z-30 transform transition-transform duration-300 ease-in-out',
          mobileMenuOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
        )}
      >
        {/* Header - Logo */}
        <div className="p-4 border-b border-gray-200">
          <Link to="/unified-dashboard" className="flex items-center space-x-3">
            <img src="/icons/XamOps.png" alt="XamOps Logo" className="w-10 h-10" />
            <div>
              <h1 className="text-xl font-bold bg-gradient-to-r from-indigo-600 to-purple-600 bg-clip-text text-transparent">
                XamOps
              </h1>
              <p className="text-xs text-gray-500">Cloud FinOps Platform</p>
            </div>
          </Link>
        </div>


        {/* Account Selector */}
        <div className="p-4 border-b border-gray-200">
          <AccountSelector
            isOpen={accountSelectorOpen}
            onToggle={() => setAccountSelectorOpen(prev => !prev)}
            onClose={closeAccountSelector}
          />
        </div>

        {/* Navigation Menu */}
        <div className="flex-1 overflow-y-auto sidebar-scroll p-4 space-y-2">

          {/* Direct BillOps tabs for ROLE_BILLOPS users (non-XAMOPS) */}
          {/* These appear right after account selector, not nested in any module */}
          {hasAnyRole('ROLE_BILLOPS') && !hasAnyRole('ROLE_XAMOPS') && (
            <>
              <NavItem
                href="/billops-dashboard"
                icon="fas fa-tachometer-alt"
                label="Dashboard"
                gradientClass="from-cyan-500 to-blue-600"
                isActive={isActive('/billops-dashboard')}
              />
              <NavItem
                href={getBillingUrl()}
                icon="fas fa-dollar-sign"
                label="Billing"
                gradientClass="from-green-500 to-teal-600"
                isActive={isActive('/billops-billing')}
              />
              <NavItem
                href="/billops-invoices"
                icon="fas fa-file-invoice"
                label="Invoices"
                gradientClass="from-purple-500 to-pink-600"
                isActive={isActive('/billops-invoices')}
              />
              <NavItem
                href="/billops-credits"
                icon="fas fa-coins"
                label="Credits"
                gradientClass="from-yellow-500 to-orange-600"
                isActive={isActive('/billops-credits')}
              />
              <NavItem
                href="/billops-tickets"
                icon="fas fa-ticket-alt"
                label="Tickets"
                gradientClass="from-red-500 to-pink-600"
                isActive={isActive('/billops-tickets')}
              />
              <NavItem
                href="/billops-agreements"
                icon="fas fa-file-contract"
                label="Agreements"
                gradientClass="from-indigo-500 to-purple-600"
                isActive={isActive('/billops-agreements')}
              />
            </>
          )}

          {/* Direct BillOps Admin tabs for ROLE_BILLOPS_ADMIN users */}
          {/* These appear right after account selector for admin role */}
          {hasAnyRole('ROLE_BILLOPS_ADMIN') && (
            <>
              <NavItem
                href="/billops-admin-dashboard"
                icon="fas fa-chart-bar"
                label="Admin Dashboard"
                gradientClass="from-cyan-500 to-blue-600"
                isActive={isActive('/billops-admin-dashboard')}
              />
              <NavItem
                href="/billops-support-dashboard"
                icon="fas fa-headset"
                label="Support Inbox"
                gradientClass="from-indigo-500 to-purple-600"
                isActive={isActive('/billops-support-dashboard')}
              />
              <NavItem
                href="/billops-admin-tickets"
                icon="fas fa-ticket-alt"
                label="All Tickets"
                gradientClass="from-red-500 to-pink-600"
                isActive={isActive('/billops-admin-tickets')}
              />
              <NavItem
                href="/billops-admin-invoices"
                icon="fas fa-file-invoice"
                label="All Invoices"
                gradientClass="from-purple-500 to-pink-600"
                isActive={isActive('/billops-admin-invoices')}
              />
              <NavItem
                href="/billops-admin-credits"
                icon="fas fa-coins"
                label="Credit Requests"
                gradientClass="from-yellow-500 to-orange-600"
                isActive={isActive('/billops-admin-credits')}
              />
              <NavItem
                href="/billops-admin-agreements"
                icon="fas fa-file-signature"
                label="Agreement Admin"
                gradientClass="from-blue-500 to-indigo-600"
                isActive={isActive('/billops-admin-agreements')}
              />
              <NavItem
                href="/billops-client-details"
                icon="fas fa-users-cog"
                label="Client Details"
                gradientClass="from-green-500 to-teal-600"
                isActive={isActive('/billops-client-details')}
              />
            </>
          )}

          {/* Live Dashboard (React - Converted from dashboard.html) */}
          {/* Provider-aware routing: GCP → /gcp-dashboard, Azure → /azure-dashboard, AWS → /dashboard */}
          {(hasAnyRole('ROLE_XAMOPS') || hasAnyRole('ROLE_SUPER_ADMIN')) && (
            <NavItem
              href={
                selectedAccountDisplay.provider === 'GCP' ? '/gcp-dashboard' :
                  selectedAccountDisplay.provider === 'Azure' ? '/azure-dashboard' :
                    '/dashboard'
              }
              icon="fas fa-chart-line"
              label="Account Dashboard"
              gradientClass="from-blue-500 to-indigo-600"
              isActive={isActive('/dashboard')}
            />
          )}

          {/* FinOps Section */}
          {(hasAnyRole('ROLE_XAMOPS') || hasAnyRole('ROLE_SUPER_ADMIN')) && (
            <NavSection
              id="finops"
              icon="fas fa-dollar-sign"
              label="FinOps"
              gradientClass="from-green-500 to-teal-600"
              isOpen={openDropdown === 'finops'}
              onToggle={() => toggleDropdown('finops')}
            >
              <NavItem
                href={
                  selectedAccountDisplay.provider === 'GCP' ? '/gcp-cost' :
                    selectedAccountDisplay.provider === 'Azure' ? '/cost' :
                      '/cost'
                }
                icon="fas fa-dollar-sign"
                label="Cost Management"
                isActive={isActive('/cost') || isActive('/gcp-cost')}
              />
              <NavItem
                href={
                  selectedAccountDisplay.provider === 'GCP' ? '/gcp-reservations' :
                    selectedAccountDisplay.provider === 'Azure' ? '/reservation' :
                      '/reservation'
                }
                icon="fas fa-file-contract"
                label="Reservation Management"
                isActive={isActive('/reservation') || isActive('/gcp-reservations')}
              />
              <NavItem
                href={
                  selectedAccountDisplay.provider === 'GCP' ? '/gcp-finops' :
                    selectedAccountDisplay.provider === 'Azure' ? '/azure-finops' :
                      '/finops'
                }
                icon="fas fa-file-invoice-dollar"
                label="FinOps Reports"
                isActive={isActive('/finops') || isActive('/gcp-finops') || isActive('/azure-finops')}
              />
              <NavItem
                href={
                  selectedAccountDisplay.provider === 'GCP' ? '/gcp-waste' :
                    selectedAccountDisplay.provider === 'Azure' ? '/waste' :
                      '/waste'
                }
                icon="fas fa-recycle"
                label="Waste Management"
                isActive={isActive('/waste') || isActive('/gcp-waste')}
              />
              <NavItem
                href={
                  selectedAccountDisplay.provider === 'GCP' ? '/gcp-rightsizing' :
                    selectedAccountDisplay.provider === 'Azure' ? '/rightsizing' :
                      '/rightsizing'
                }
                icon="fas fa-compress-arrows-alt"
                label="Rightsizing"
                isActive={isActive('/rightsizing') || isActive('/gcp-rightsizing')}
              />
              {selectedAccountDisplay.provider === 'AWS' && (
                <NavItem href="/cloudsitter" icon="fas fa-clock" label="CloudSitter" isActive={isActive('/cloudsitter')} />
              )}
              {selectedAccountDisplay.provider === 'AWS' && (
                <NavItem href="/spot-automation" icon="fas fa-bolt" label="Spot Automation" isActive={isActive('/spot-automation')} />
              )}
            </NavSection>
          )}

          {/* CloudOps Section */}
          {(hasAnyRole('ROLE_XAMOPS') || hasAnyRole('ROLE_SUPER_ADMIN')) && (
            <NavSection
              id="cloudops"
              icon="fas fa-server"
              label="CloudOps"
              gradientClass="from-indigo-500 to-purple-600"
              isOpen={openDropdown === 'cloudops'}
              onToggle={() => toggleDropdown('cloudops')}
            >
              <NavItem href="/tickets" icon="fas fa-ticket-alt" label="Support Tickets" isActive={isActive('/tickets')} />
              <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-cloudk8s' : selectedAccountDisplay.provider === 'Azure' ? '/azure-cloudk8s' : '/cloudk8s'} icon="fas fa-dharmachakra" label="CloudK8s" isActive={isActive('/cloudk8s')} />
              <NavItem href="/sonarqube" icon="fas fa-chart-line" label="SonarQube" isActive={isActive('/sonarqube')} />
              <NavItem href="/cicd-pipelines" icon="fas fa-cogs" label="CICD Pipelines" isActive={isActive('/cicd-pipelines')} />
              <NavItem href="/devops-in-the-box" icon="fas fa-toolbox" label="DevOps-in-the-Box" isActive={isActive('/devops-in-the-box')} />
              <NavItem href="/grafana-dashboard" icon="fas fa-chart-line" label="Grafana" isActive={isActive('/grafana-dashboard')} />
              <NavItem href="/aiops" icon="fas fa-brain" label="AIOps" isActive={isActive('/aiops')} />
              <NavItem href="/dataops" icon="fas fa-database" label="DataOps" isActive={isActive('/dataops')} />
            </NavSection>
          )}

          {/* SecOps Section */}
          {(hasAnyRole('ROLE_XAMOPS') || hasAnyRole('ROLE_SUPER_ADMIN')) && (
            <NavSection
              id="security"
              icon="fas fa-shield-alt"
              label="SecOps"
              gradientClass="from-red-500 to-orange-600"
              isOpen={openDropdown === 'security'}
              onToggle={() => toggleDropdown('security')}
            >
              <NavItem
                href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-security' : '/security'}
                icon="fas fa-shield-alt"
                label="Security Center"
                isActive={isActive('/security') || isActive('/gcp-security')}
              />
              <NavItem href="/complianceops" icon="fas fa-file-contract" label="ComplianceOps" isActive={isActive('/complianceops')} />
              <NavItem
                href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-performance' : '/performance'}
                icon="fas fa-chart-line"
                label="Performance Insights"
                isActive={isActive('/performance') || isActive('/gcp-performance')}
              />
              <NavItem
                href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-alerts' : '/alerts'}
                icon="fas fa-bell"
                label="Alerts"
                isActive={isActive('/alerts') || isActive('/gcp-alerts')}
              />
              {/* <NavItem href="/security/compliance.html" icon="fas fa-check-circle" label="Compliance" /> */}
              {/* <NavItem href="/security/vulnerabilities.html" icon="fas fa-bug" label="Vulnerabilities" /> */}
              {/* <NavItem href="/security/iam-analysis.html" icon="fas fa-user-shield" label="IAM Analysis" /> */}
              {/* <NavItem href="/security/audit-logs.html" icon="fas fa-clipboard-list" label="Audit Logs" /> */}
            </NavSection>
          )}


          {/* Licenses Section for ROLE_XAMOPS */}
          {(hasAnyRole('ROLE_XAMOPS') || hasAnyRole('ROLE_SUPER_ADMIN')) && (
            <NavSection
              id="licenses"
              icon="fas fa-key"
              label="Licenses"
              gradientClass="from-cyan-500 to-blue-600"
              isOpen={openDropdown === 'licenses'}
              onToggle={() => toggleDropdown('licenses')}
            >
              <NavItem href="/marketplace-purchases" icon="fas fa-store" label="MarketOps" isActive={isActive('/marketplace-purchases')} />
              <NavItem href="/workspace-licenses" icon="fas fa-key" label="Licenses & Tools" isActive={isActive('/workspace-licenses')} />
            </NavSection>
          )}

        </div>

        {/* Footer - BillOps, Settings & Logout */}
        <div className="p-4 border-t border-gray-200 space-y-2">
          {/* BillOps Section moved to footer */}
          {/* Only show for ROLE_XAMOPS and ROLE_SUPER_ADMIN users (Admin items moved to top) */}
          {(hasAnyRole('ROLE_XAMOPS') || hasAnyRole('ROLE_SUPER_ADMIN')) && (
            <NavSection
              id="billops"
              icon="fas fa-file-invoice-dollar"
              label="BillOps"
              gradientClass="from-cyan-500 to-blue-600"
              isOpen={openDropdown === 'billops'}
              onToggle={() => toggleDropdown('billops')}
              direction="up"
            >
              <NavItem href="/billops-dashboard" icon="fas fa-tachometer-alt" label="Dashboard" isActive={isActive('/billops-dashboard')} />
              <NavItem href={getBillingUrl()} icon="fas fa-dollar-sign" label="Billing" isActive={isActive('/billops-billing')} />
              <NavItem href="/billops-invoices" icon="fas fa-file-invoice" label="Invoices" isActive={isActive('/billops-invoices')} />
              <NavItem href="/billops-credits" icon="fas fa-coins" label="Credits" isActive={isActive('/billops-credits')} />
              <NavItem href="/billops-tickets" icon="fas fa-ticket-alt" label="Tickets" isActive={isActive('/billops-tickets')} />
              <NavItem href="/billops-agreements" icon="fas fa-file-contract" label="Agreements" isActive={isActive('/billops-agreements')} />
            </NavSection>
          )}

          {/* Settings Section moved to footer */}
          <NavSection
            id="settings"
            icon="fas fa-cog"
            label="Settings"
            gradientClass="from-gray-500 to-gray-700"
            isOpen={openDropdown === 'settings'}
            onToggle={() => toggleDropdown('settings')}
            direction="up"
          >
            {hasAnyRole(['ROLE_XAMOPS', 'ROLE_BILLOPS', 'ROLE_SUPER_ADMIN']) && (
              <NavItem href="/account-manager" icon="fas fa-cloud" label="Account Management" isActive={isActive('/account-manager')} />
            )}
            {hasAnyRole(['ROLE_XAMOPS', 'ROLE_BILLOPS', 'ROLE_SUPER_ADMIN']) && (
              <NavItem href="/user-management" icon="fas fa-users-cog" label="User Management" isActive={isActive('/user-management')} />
            )}
            {(hasAnyRole('ROLE_BILLOPS_ADMIN') || hasAnyRole('ROLE_SUPER_ADMIN')) && (
              <NavItem href="/billops-admin-user-manager" icon="fas fa-user-cog" label="User Manager" isActive={isActive('/billops-admin-user-manager')} />
            )}
          </NavSection>

        </div>
      </div>
    </>
  );
};

export default Sidebar;
