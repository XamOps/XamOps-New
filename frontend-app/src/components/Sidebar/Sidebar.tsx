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
import { useAuth } from '../../contexts/AuthContext';
import { useAccounts } from '../../contexts/AccountContext';
import AccountSelector from './AccountSelector';
import NavItem from './NavItem';
import NavSection from './NavSection';
import SuperAdminPanel from './SuperAdminPanel';
import apiClient from '../../utils/apiClient';
import clsx from 'clsx';

const Sidebar: React.FC = () => {
  const { hasAnyRole } = useAuth();
  const { } = useAccounts();

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
   * Check if current path matches the nav item
   */
  const isActive = useCallback((path: string): boolean => {
    const currentPath = window.location.pathname;

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
        currentPath === '/gcp_dashboard.html' ||
        currentPath === '/azure_dashboard.html'
      );
    }

    // Special case: Cost Management matches both React route and HTML
    if (path === '/cost') {
      return currentPath === '/cost' || currentPath === '/cost.html';
    }

    // Special case: Waste Management matches both React route and HTML
    if (path === '/waste') {
      return currentPath === '/waste' || currentPath === '/waste.html';
    }

    // Special case: Rightsizing matches both React route and HTML
    if (path === '/rightsizing') {
      return currentPath === '/rightsizing' || currentPath === '/rightsizing.html';
    }

    // Special case: CloudList matches both React route and HTML
    if (path === '/cloudlist') {
      return currentPath === '/cloudlist' || currentPath === '/cloudlist.html';
    }

    // Special case: Reservation matches both React route and HTML
    if (path === '/reservation') {
      return currentPath === '/reservation' || currentPath === '/reservation.html';
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
        currentPath.startsWith('/eks-details')
      );
    }

    // Special case: Security matches both React route and HTML
    if (path === '/security') {
      return currentPath === '/security' || currentPath === '/security.html';
    }

    // Special case: ComplianceOps matches both React route and HTML
    if (path === '/complianceops') {
      return currentPath === '/complianceops' || currentPath === '/complianceops.html';
    }

    // Special case: Performance matches both React route and HTML
    if (path === '/performance') {
      return currentPath === '/performance' || currentPath === '/performance.html';
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

    // Special case: CloudShell matches both React route and HTML
    if (path === '/cloudshell') {
      return currentPath === '/cloudshell' || currentPath === '/cloudshell.html';
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

    // Special case: BillOps section matches any billops path
    if (path === '/billops/') {
      return currentPath.startsWith('/billops/');
    }

    return false;
  }, []);

  /**
   * Handle logout
   */
  const handleLogout = async () => {
    try {
      await apiClient.post('/logout', {});
      window.location.href = '/';
    } catch (err) {
      console.error('Logout failed:', err);
      // Still redirect on error
      window.location.href = '/';
    }
  };

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
          <a href="/" className="flex items-center space-x-3">
            <img src="/icons/XamOps.png" alt="XamOps Logo" className="w-10 h-10" />
            <div>
              <h1 className="text-xl font-bold bg-gradient-to-r from-indigo-600 to-purple-600 bg-clip-text text-transparent">
                XamOps
              </h1>
              <p className="text-xs text-gray-500">Cloud FinOps Platform</p>
            </div>
          </a>
        </div>

        {/* Super Admin Panel */}
        {hasAnyRole('ROLE_SUPER_ADMIN') && (
          <div className="p-4 border-b border-gray-200">
            <SuperAdminPanel />
          </div>
        )}

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
          {/* Unified Dashboard (React - NEW) */}
          {hasAnyRole('ROLE_XAMOPS') && (
            <NavItem
              href="/unified-dashboard"
              icon="fas fa-layer-group"
              label="Unified Dashboard"
              gradientClass="from-indigo-500 to-purple-600"
              isActive={isActive('/unified-dashboard')}
            />
          )}

          {/* Account Manager (React - Converted from account-manager.html) */}
          {hasAnyRole('ROLE_XAMOPS') && (
            <NavItem
              href="/account-manager"
              icon="fas fa-cloud"
              label="Account Manager"
              gradientClass="from-blue-500 to-cyan-600"
              isActive={isActive('/account-manager')}
            />
          )}

          {/* Live Dashboard (React - Converted from dashboard.html) */}
          {hasAnyRole('ROLE_XAMOPS') && (
            <NavItem
              href="/dashboard"
              icon="fas fa-chart-line"
              label="Account Dashboard"
              gradientClass="from-blue-500 to-indigo-600"
              isActive={isActive('/dashboard')}
            />
          )}

          {/* FinOps Section */}
          {hasAnyRole('ROLE_XAMOPS') && (
            <NavSection
              id="finops"
              icon="fas fa-dollar-sign"
              label="FinOps"
              gradientClass="from-green-500 to-teal-600"
              isOpen={openDropdown === 'finops'}
              onToggle={() => toggleDropdown('finops')}
            >
              <NavItem href="/cost" icon="fas fa-dollar-sign" label="Cost Management" isActive={isActive('/cost')} />
              <NavItem href="/reservation" icon="fas fa-file-contract" label="Reservation Management" isActive={isActive('/reservation')} />
              <NavItem href="/finops" icon="fas fa-file-invoice-dollar" label="FinOps Reports" isActive={isActive('/finops')} />
              <NavItem href="/waste" icon="fas fa-recycle" label="Waste Management" isActive={isActive('/waste')} />
              <NavItem href="/rightsizing" icon="fas fa-compress-arrows-alt" label="Rightsizing" isActive={isActive('/rightsizing')} />
              <NavItem href="/cloudsitter" icon="fas fa-clock" label="CloudSitter" isActive={isActive('/cloudsitter')} />
              <NavItem href="/spot-automation" icon="fas fa-bolt" label="Spot Automation" isActive={isActive('/spot-automation')} />
            </NavSection>
          )}

          {/* CloudOps Section */}
          {hasAnyRole('ROLE_XAMOPS') && (
            <NavSection
              id="cloudops"
              icon="fas fa-server"
              label="CloudOps"
              gradientClass="from-indigo-500 to-purple-600"
              isOpen={openDropdown === 'cloudops'}
              onToggle={() => toggleDropdown('cloudops')}
            >
              <NavItem href="/cloudlist" icon="fas fa-list" label="CloudList" isActive={isActive('/cloudlist')} />
              <NavItem href="/cloudshell" icon="fas fa-terminal" label="CloudShell" isActive={isActive('/cloudshell')} />
              <NavItem href="/cloudk8s" icon="fas fa-dharmachakra" label="CloudK8s" isActive={isActive('/cloudk8s')} />
              <NavItem href="/sonarqube" icon="fas fa-chart-line" label="SonarQube" isActive={isActive('/sonarqube')} />
              <NavItem href="/cicd-pipelines" icon="fas fa-cogs" label="CICD Pipelines" isActive={isActive('/cicd-pipelines')} />
              <NavItem href="/devops-in-the-box" icon="fas fa-toolbox" label="DevOps-in-the-Box" isActive={isActive('/devops-in-the-box')} />
              <NavItem href="/grafana-dashboard" icon="fas fa-chart-line" label="Infrastructure Monitoring" isActive={isActive('/grafana-dashboard')} />
              <NavItem href="/tickets" icon="fas fa-ticket-alt" label="Support Tickets" isActive={isActive('/tickets')} />
              <NavItem href="/aiops" icon="fas fa-brain" label="AIOps" isActive={isActive('/aiops')} />
              <NavItem href="/dataops" icon="fas fa-database" label="DataOps" isActive={isActive('/dataops')} />
            </NavSection>
          )}

          {/* SecOps Section */}
          {hasAnyRole('ROLE_XAMOPS') && (
            <NavSection
              id="security"
              icon="fas fa-shield-alt"
              label="SecOps"
              gradientClass="from-red-500 to-orange-600"
              isOpen={openDropdown === 'security'}
              onToggle={() => toggleDropdown('security')}
            >
              <NavItem href="/security" icon="fas fa-shield-alt" label="Security Center" isActive={isActive('/security')} />
              <NavItem href="/complianceops" icon="fas fa-file-contract" label="ComplianceOps" isActive={isActive('/complianceops')} />
              <NavItem href="/performance" icon="fas fa-chart-line" label="Performance Insights" isActive={isActive('/performance')} />
              <NavItem href="/alerts" icon="fas fa-bell" label="Alerts" isActive={isActive('/alerts')} />
              {/* <NavItem href="/security/compliance.html" icon="fas fa-check-circle" label="Compliance" /> */}
              {/* <NavItem href="/security/vulnerabilities.html" icon="fas fa-bug" label="Vulnerabilities" /> */}
              {/* <NavItem href="/security/iam-analysis.html" icon="fas fa-user-shield" label="IAM Analysis" /> */}
              {/* <NavItem href="/security/audit-logs.html" icon="fas fa-clipboard-list" label="Audit Logs" /> */}
            </NavSection>
          )}

          {/* BillOps Section */}
          {hasAnyRole(['ROLE_BILLOPS', 'ROLE_BILLOPS_ADMIN', 'ROLE_XAMOPS']) && (
            <NavSection
              id="billops"
              icon="fas fa-file-invoice-dollar"
              label="BillOps"
              gradientClass="from-cyan-500 to-blue-600"
              isOpen={openDropdown === 'billops'}
              onToggle={() => toggleDropdown('billops')}
            >
              {/* ROLE_XAMOPS users see standard BillOps items */}
              {hasAnyRole('ROLE_XAMOPS') && (
                <>
                  <NavItem href="/billops-dashboard" icon="fas fa-tachometer-alt" label="Dashboard" isActive={isActive('/billops-dashboard')} />
                  <NavItem href="/billops-billing" icon="fas fa-dollar-sign" label="Billing" isActive={isActive('/billops-billing')} />
                  <NavItem href="/billops-invoices" icon="fas fa-file-invoice" label="Invoices" isActive={isActive('/billops-invoices')} />
                  <NavItem href="/billops-credits" icon="fas fa-coins" label="Credits" isActive={isActive('/billops-credits')} />
                  <NavItem href="/billops-tickets" icon="fas fa-ticket-alt" label="Tickets" isActive={isActive('/billops-tickets')} />
                  <NavItem href="/billops-agreements" icon="fas fa-file-contract" label="Agreements" isActive={isActive('/billops-agreements')} />
                </>
              )}
              {/* ROLE_BILLOPS users (non-XAMOPS) see user-specific items */}
              {hasAnyRole('ROLE_BILLOPS') && !hasAnyRole('ROLE_XAMOPS') && (
                <>
                  <NavItem
                    href="/billops-dashboard"
                    icon="fas fa-tachometer-alt"
                    label="My Dashboard"
                    isActive={isActive('/billops-dashboard')}
                  />
                  <NavItem href="/billops/tickets_user.html" icon="fas fa-ticket-alt" label="My Tickets" />
                  <NavItem href="/billops/invoices_user.html" icon="fas fa-file-invoice" label="My Invoices" />
                </>
              )}
              {hasAnyRole('ROLE_BILLOPS_ADMIN') && (
                <>
                  <NavItem
                    href="/billops/dashboard_admin.html"
                    icon="fas fa-chart-bar"
                    label="Admin Dashboard"
                  />
                  <NavItem href="/billops/tickets_admin.html" icon="fas fa-ticket-alt" label="All Tickets" />
                  <NavItem href="/billops/invoices_admin.html" icon="fas fa-file-invoice" label="All Invoices" />
                  <NavItem href="/billops/customers.html" icon="fas fa-users" label="Customers" />
                </>
              )}
            </NavSection>
          )}

          {/* Licenses Section for ROLE_XAMOPS */}
          {hasAnyRole('ROLE_XAMOPS') && (
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

          {/* Settings Section */}
          <NavSection
            id="settings"
            icon="fas fa-cog"
            label="Settings"
            gradientClass="from-gray-500 to-gray-700"
            isOpen={openDropdown === 'settings'}
            onToggle={() => toggleDropdown('settings')}
          >
            {hasAnyRole('ROLE_XAMOPS') && (
              <NavItem href="/user-management" icon="fas fa-users-cog" label="User Management" isActive={isActive('/user-management')} />
            )}
            <NavItem href="/my-profile" icon="fas fa-user" label="My Profile" isActive={isActive('/my-profile')} />
          </NavSection>
        </div>

        {/* Footer - Logout */}
        <div className="p-4 border-t border-gray-200">
          <button
            onClick={handleLogout}
            className="w-full flex items-center px-4 py-3 rounded-lg text-gray-700 hover:bg-red-50 hover:text-red-600 transition-colors"
          >
            <i className="fas fa-sign-out-alt w-5 text-center mr-3" />
            <span className="font-medium">Logout</span>
          </button>
        </div>
      </div>
    </>
  );
};

export default Sidebar;
