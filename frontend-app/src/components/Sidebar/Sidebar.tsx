/**
 * Sidebar Component
 *
 * Main navigation sidebar with account selection, role-based menu items, and responsive design
 *
 * Converted from: _sidebar.html
 */

import React, { useState, useCallback, useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../../contexts/AuthContext';
import { useAccounts } from '../../contexts/AccountContext';
import AccountSelector from './AccountSelector';
import NavItem from './NavItem';
import NavSection from './NavSection';
import { useTheme } from '../../contexts/ThemeContext';
import clsx from 'clsx';

const Sidebar: React.FC = () => {
  const { hasAnyRole } = useAuth();
  const { selectedAccountDisplay, accounts, selectedAccountIds } = useAccounts();
  const { theme } = useTheme();
  const location = useLocation();

  // Fetch billops team profile to get isPrimaryUser + allowedTabs for ROLE_BILLOPS_ADMIN
  // undefined = still loading, null = loaded (primary user / no restrictions), object = loaded sub-user
  const [billopsProfile, setBillopsProfile] = useState<{ isPrimaryUser: boolean; allowedTabs: string[] } | null | undefined>(
    hasAnyRole('ROLE_BILLOPS_ADMIN') ? undefined : null
  );

  useEffect(() => {
    if (hasAnyRole('ROLE_BILLOPS_ADMIN')) {
      fetch('/api/billops/team/profile', { credentials: 'include' })
        .then(r => r.ok ? r.json() : null)
        .then(data => {
          setBillopsProfile(data ? {
            isPrimaryUser: !!data.isPrimaryUser,
            allowedTabs: data.allowedTabs || []
          } : null);
        })
        .catch(() => setBillopsProfile(null));
    }
  }, []);

  // undefined = profile still loading; hide all admin tabs until resolved
  const billopsProfileLoading = billopsProfile === undefined;
  // Only restrict tabs for confirmed BILLOPS_ADMIN sub-users (isPrimaryUser explicitly false)
  const isAdminSubUser = hasAnyRole('ROLE_BILLOPS_ADMIN') && billopsProfile !== null && billopsProfile !== undefined && !billopsProfile.isPrimaryUser;
  const isTabAllowed = (tabKey: string): boolean => {
    if (billopsProfileLoading) return false; // hide everything until we know access
    if (!isAdminSubUser) return true;
    return billopsProfile!.allowedTabs.includes(tabKey);
  };

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

  // Auto-open the section that owns the current route
  useEffect(() => {
    const path = location.pathname;
    const finopsRoutes = ['/cost', '/gcp-cost', '/azure-cost', '/reservation', '/gcp-reservations', '/azure-reservations',
      '/finops', '/gcp-finops', '/azure-finops', '/waste', '/gcp-waste', '/azure-waste',
      '/rightsizing', '/gcp-rightsizing', '/azure-rightsizing', '/ebs-rightsizing',
      '/cloudsitter', '/flex-compute', '/autospotting', '/gcp-autospotting', '/azure-autospotting'];
    const cloudopsRoutes = ['/cloudk8s', '/gcp-cloudk8s', '/azure-cloudk8s',
      '/sonarqube', '/cicd-pipelines', '/devops-in-the-box', '/grafana-dashboard', '/aiops', '/dataops', '/ebs-rightsizing'];
    const ticketsRoutes = ['/tickets', '/billops-tickets'];
    const secopsRoutes = ['/security', '/gcp-security', '/azure-security', '/complianceops',
      '/performance', '/gcp-performance', '/azure-performance', '/alerts', '/gcp-alerts', '/azure-alerts'];
    const licensesRoutes = ['/marketplace-purchases', '/workspace-licenses', '/isv-metering'];
    const observabilityRoutes = ['/observability'];

    if (finopsRoutes.some(r => path.startsWith(r))) setOpenDropdown('finops');
    else if (cloudopsRoutes.some(r => path.startsWith(r))) setOpenDropdown('cloudops');
    else if (ticketsRoutes.some(r => path.startsWith(r))) setOpenDropdown('tickets');
    else if (secopsRoutes.some(r => path.startsWith(r))) setOpenDropdown('security');
    else if (licensesRoutes.some(r => path.startsWith(r))) setOpenDropdown('licenses');
    else if (observabilityRoutes.some(r => path.startsWith(r))) setOpenDropdown('observability');
  }, [location.pathname]);

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
    return '/billops-billing';
  }, [selectedAccountIds, accounts]);

  /**
   * Check if current path matches the nav item
   */
  const isActive = useCallback((path: string) => {
    if (path === '/' && location.pathname === '/') return true;
    if (path !== '/' && location.pathname.startsWith(path)) return true;
    return false;
  }, [location.pathname]);

  console.log('🌗 [Sidebar] Render - theme:', theme);

  return (
    <>
      {/* Mobile Hamburger Button */}
      <button
        onClick={toggleMobileMenu}
        className={clsx(
          "lg:hidden fixed top-4 left-4 z-50 p-2 rounded-lg shadow-lg border transition-colors",
          theme === 'dark' ? 'bg-slate-800 border-slate-700 text-slate-100 hover:bg-slate-700' : 'bg-white border-gray-200 text-gray-700 hover:bg-gray-50'
        )}
        aria-label="Toggle sidebar menu"
      >
        <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>

      {/* Mobile Overlay */}
      {mobileMenuOpen && (
        <div
          className="lg:hidden fixed inset-0 bg-black/50 z-20 backdrop-blur-sm"
          onClick={closeMobileMenu}
        />
      )}

      {/* Sidebar Container */}
      <div
        className={clsx(
          'fixed inset-y-0 left-0 flex flex-col transition-all duration-300 ease-in-out z-40 border-r w-72 shadow-xl',
          mobileMenuOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0',
          theme === 'dark' ? 'bg-slate-900 border-slate-800' : 'bg-white border-gray-200'
        )}
      >
        {/* Sidebar Header */}
        <div className={clsx(
          'p-5 flex items-center h-22 border-b justify-between transition-all duration-300',
          theme === 'dark' ? 'border-slate-800' : 'border-gray-100'
        )}>
          <Link
            to="/unified-dashboard"
            className="flex items-center gap-3 hover:opacity-90 transition-opacity"
          >
            <img
              src="/icons/XamOps.png"
              alt="XamOps Logo"
              className="w-10 h-10 object-contain"
            />
            <div className="flex flex-col">
              <span className={clsx(
                'text-xl font-bold tracking-tight leading-none',
                theme === 'dark' ? 'text-[#8b6dfa]' : 'text-[#704fe6]'
              )}>XamOps</span>
              <span className="text-[10px] text-slate-400 font-medium mt-1">Cloud FinOps Platform</span>
            </div>
          </Link>
        </div>

        {/* Account Selection Section — hidden for ROLE_BILLOPS_ADMIN */}
        {!hasAnyRole('ROLE_BILLOPS_ADMIN') && (
          <div className={clsx(
            'p-4 border-b transition-colors duration-300',
            theme === 'dark' ? 'border-slate-800' : 'border-gray-200'
          )}>
            <AccountSelector
              isOpen={accountSelectorOpen}
              onToggle={() => setAccountSelectorOpen(prev => !prev)}
              onClose={closeAccountSelector}
            />
          </div>
        )}

        {/* Navigation Menu */}
        <div className="flex-1 overflow-y-auto sidebar-scroll p-4 space-y-2 custom-scrollbar">

          {/* ROLE_BILLOPS (Non-Admin) Specific Items */}
          {hasAnyRole('ROLE_BILLOPS') && !hasAnyRole('ROLE_XAMOPS') && (
            <>
              <NavItem href="/billops-dashboard" icon="fas fa-tachometer-alt" label="Dashboard" isActive={isActive('/billops-dashboard')} />
              <NavItem href={getBillingUrl()} icon="fas fa-dollar-sign" label="Billing" isActive={isActive('/billops-billing')} />
              <NavItem href="/billops-invoices" icon="fas fa-file-invoice" label="Invoices" isActive={isActive('/billops-invoices')} />
              <NavItem href="/billops-credits" icon="fas fa-coins" label="Credits" isActive={isActive('/billops-credits')} />
              <NavItem href="/billops-tickets" icon="fas fa-ticket-alt" label="Tickets" isActive={isActive('/billops-tickets')} />
              <NavItem href="/billops-agreements" icon="fas fa-file-contract" label="Agreements" isActive={isActive('/billops-agreements')} />
            </>
          )}

          {/* ROLE_BILLOPS_ADMIN Specific Items */}
          {hasAnyRole('ROLE_BILLOPS_ADMIN') && (
            <>
              {billopsProfileLoading ? (
                /* Skeleton nav items while profile loads — prevents flash of all-then-filtered tabs */
                [1, 2, 3].map(i => (
                  <div key={i} className="mx-3 my-1 h-9 rounded-xl bg-white/10 animate-pulse" />
                ))
              ) : (
                <>
                  {isTabAllowed('admin-dashboard') && <NavItem href="/billops-admin-dashboard" icon="fas fa-chart-bar" label="Dashboard" isActive={isActive('/billops-admin-dashboard')} />}
                  {isTabAllowed('support-inbox') && <NavItem href="/billops-support-dashboard" icon="fas fa-headset" label="Chat" isActive={isActive('/billops-support-dashboard')} />}
                  {isTabAllowed('all-tickets') && <NavItem href="/billops-admin-tickets" icon="fas fa-ticket-alt" label="Tickets" isActive={isActive('/billops-admin-tickets')} />}
                  {isTabAllowed('all-invoices') && <NavItem href="/billops-admin-invoices" icon="fas fa-file-invoice" label="Billing" isActive={isActive('/billops-admin-invoices')} />}
                  {isTabAllowed('credit-requests') && <NavItem href="/billops-admin-credits" icon="fas fa-coins" label="Credit" isActive={isActive('/billops-admin-credits')} />}
                  {isTabAllowed('client-details') && <NavItem href="/billops-client-details" icon="fas fa-users-cog" label="Accounts" isActive={isActive('/billops-client-details')} />}
                  {isTabAllowed('agreement-admin') && <NavItem href="/billops-admin-agreements" icon="fas fa-file-signature" label="Agreement" isActive={isActive('/billops-admin-agreements')} />}
                  {isTabAllowed('autospotting-admin') && <NavItem href="/autospotting-admin" icon="fas fa-magic" label="AutoSpotting" isActive={isActive('/autospotting-admin')} />}
                  {isTabAllowed('historical-billing') && <NavItem href="/billops-admin-historical-billing" icon="fas fa-history" label="Historical Billing" isActive={isActive('/billops-admin-historical-billing')} />}
                </>
              )}
            </>
          )}

          {/* Standard Cloud Ops / FinOps Sections */}
          {(hasAnyRole('ROLE_XAMOPS') || hasAnyRole('ROLE_SUPER_ADMIN')) && (
            <>
              <NavItem
                href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-dashboard' : selectedAccountDisplay.provider === 'Azure' ? '/azure-dashboard' : '/dashboard'}
                icon="fas fa-chart-line"
                label="Account Dashboard"
                gradientClass="from-blue-500 to-indigo-600"
                isActive={isActive('/dashboard')}
              />

              <NavSection
                icon="fas fa-dollar-sign"
                label="FinOps"
                gradientClass="from-green-500 to-teal-600"
                isOpen={openDropdown === 'finops'}
                onToggle={() => toggleDropdown('finops')}
              >
                <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-cost' : selectedAccountDisplay.provider === 'Azure' ? '/azure-cost' : '/cost'} icon="fas fa-dollar-sign" label="Cost Management" isActive={isActive('/cost') || isActive('/gcp-cost') || isActive('/azure-cost')} />
                <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-reservations' : selectedAccountDisplay.provider === 'Azure' ? '/azure-reservations' : '/reservation'} icon="fas fa-file-contract" label="Reservation Management" isActive={isActive('/reservation') || isActive('/gcp-reservations') || isActive('/azure-reservations')} />
                <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-finops' : selectedAccountDisplay.provider === 'Azure' ? '/azure-finops' : '/finops'} icon="fas fa-file-invoice-dollar" label="FinOps Reports" isActive={isActive('/finops') || isActive('/gcp-finops') || isActive('/azure-finops')} />
                <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-waste' : selectedAccountDisplay.provider === 'Azure' ? '/azure-waste' : '/waste'} icon="fas fa-recycle" label="Waste Management" isActive={isActive('/waste') || isActive('/gcp-waste') || isActive('/azure-waste')} />
                <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-rightsizing' : selectedAccountDisplay.provider === 'Azure' ? '/azure-rightsizing' : '/rightsizing'} icon="fas fa-compress-arrows-alt" label="Rightsizing" isActive={isActive('/rightsizing') || isActive('/gcp-rightsizing') || isActive('/azure-rightsizing')} />
                {selectedAccountDisplay.provider === 'AWS' && (
                  <NavItem href="/ebs-rightsizing" icon="fas fa-hdd" label="Disk Rightsizing" isActive={isActive('/ebs-rightsizing')} />
                )}
                {selectedAccountDisplay.provider === 'AWS' && (
                  <>
                    <NavItem href="/cloudsitter" icon="fas fa-clock" label="CloudSitter" isActive={isActive('/cloudsitter')} />
                    <NavItem href="/flex-compute" icon="fas fa-microchip" label="Flex Compute" isActive={isActive('/flex-compute')} />
                    <NavItem href="/autospotting" icon="fas fa-magic" label="AutoSpotting" isActive={isActive('/autospotting')} />
                  </>
                )}
                {selectedAccountDisplay.provider === 'GCP' && (
                  <NavItem href="/gcp-autospotting" icon="fas fa-magic" label="AutoSpotting" isActive={isActive('/gcp-autospotting')} />
                )}
                {selectedAccountDisplay.provider === 'Azure' && (
                  <NavItem href="/azure-autospotting" icon="fas fa-magic" label="AutoSpotting" isActive={isActive('/azure-autospotting')} />
                )}
              </NavSection>

              <NavSection
                icon="fas fa-server"
                label="CloudOps"
                gradientClass="from-indigo-500 to-purple-600"
                isOpen={openDropdown === 'cloudops'}
                onToggle={() => toggleDropdown('cloudops')}
              >
                <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-cloudk8s' : selectedAccountDisplay.provider === 'Azure' ? '/azure-cloudk8s' : '/cloudk8s'} icon="fas fa-dharmachakra" label="CloudK8s" isActive={isActive('/cloudk8s')} />
                <NavItem href="/sonarqube" icon="fas fa-chart-line" label="Code Quality" isActive={isActive('/sonarqube')} />
                <NavItem href="/cicd-pipelines" icon="fas fa-cogs" label="CICD Pipelines" isActive={isActive('/cicd-pipelines')} />
                <NavItem href="/devops-in-the-box" icon="fas fa-toolbox" label="DevOps-in-the-Box" isActive={isActive('/devops-in-the-box')} />
                <NavItem href="/grafana-dashboard" icon="fas fa-chart-line" label="Grafana" isActive={isActive('/grafana-dashboard')} />
                <NavItem href="/aiops" icon="fas fa-brain" label="AIOps" isActive={isActive('/aiops')} />
                <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-dataops' : '/dataops'} icon="fas fa-database" label="DbOps" isActive={isActive('/dataops') || isActive('/gcp-dataops')} />
              </NavSection>

              <NavSection
                icon="fas fa-shield-alt"
                label="SecOps"
                gradientClass="from-red-500 to-orange-600"
                isOpen={openDropdown === 'security'}
                onToggle={() => toggleDropdown('security')}
              >
                <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-security' : selectedAccountDisplay.provider === 'Azure' ? '/azure-security' : '/security'} icon="fas fa-shield-alt" label="Security Center" isActive={isActive('/security') || isActive('/gcp-security') || isActive('/azure-security')} />
                <NavItem href="/complianceops" icon="fas fa-file-contract" label="ComplianceOps" isActive={isActive('/complianceops')} />
                <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-performance' : selectedAccountDisplay.provider === 'Azure' ? '/azure-performance' : '/performance'} icon="fas fa-chart-line" label="Performance Insights" isActive={isActive('/performance') || isActive('/gcp-performance') || isActive('/azure-performance')} />
                <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-alerts' : selectedAccountDisplay.provider === 'Azure' ? '/azure-alerts' : '/alerts'} icon="fas fa-bell" label="Alerts" isActive={isActive('/alerts') || isActive('/gcp-alerts') || isActive('/azure-alerts')} />
                <NavItem href="/sre/investigations" icon="fas fa-search" label="SRE Investigations" isActive={isActive('/sre/investigations')} />
              </NavSection>

              <NavSection
                icon="fas fa-satellite-dish"
                label="Observability"
                gradientClass="from-violet-500 to-purple-600"
                badge="Beta"
                isOpen={openDropdown === 'observability'}
                onToggle={() => toggleDropdown('observability')}
              >
                <NavItem href="/observability" icon="fas fa-chart-pie" label="Overview" isActive={location.pathname === '/observability'} />
                <NavItem href="/observability/logs" icon="fas fa-list-alt" label="Logs" isActive={isActive('/observability/logs')} />
                <NavItem href="/observability/metrics" icon="fas fa-chart-line" label="Metrics" isActive={isActive('/observability/metrics')} />
                <NavItem href="/observability/traces" icon="fas fa-project-diagram" label="Traces" isActive={isActive('/observability/traces')} />
                <NavItem href="/observability/alerts" icon="fas fa-bell" label="Alerts" isActive={isActive('/observability/alerts')} />
                <NavItem href="/observability/setup" icon="fas fa-cog" label="Setup" isActive={isActive('/observability/setup')} />
              </NavSection>

              <NavSection
                icon="fas fa-key"
                label="Licenses"
                gradientClass="from-cyan-500 to-blue-600"
                isOpen={openDropdown === 'licenses'}
                onToggle={() => toggleDropdown('licenses')}
              >
                <NavItem href="/marketplace-purchases" icon="fas fa-store" label="MarketOps" isActive={isActive('/marketplace-purchases')} />
                <NavItem href="/workspace-licenses" icon="fas fa-key" label="Licenses & Tools" isActive={isActive('/workspace-licenses')} />
              </NavSection>

              <NavSection
                icon="fas fa-ticket-alt"
                label="Tickets"
                gradientClass="from-yellow-500 to-orange-500"
                isOpen={openDropdown === 'tickets'}
                onToggle={() => toggleDropdown('tickets')}
              >
                <NavItem href="/tickets" icon="fas fa-tools" label="Technical" isActive={isActive('/tickets')} />
                <NavItem href="/billops-tickets" icon="fas fa-file-invoice-dollar" label="Account & Billing" isActive={isActive('/billops-tickets')} />
              </NavSection>
            </>
          )}
        </div>

        {/* Sidebar Footer */}
        <div className={clsx(
          'p-4 border-t space-y-2 transition-colors duration-300',
          theme === 'dark' ? 'border-slate-800' : 'border-gray-200'
        )}>
          {(hasAnyRole('ROLE_XAMOPS') || hasAnyRole('ROLE_SUPER_ADMIN')) && (
            <NavSection
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
              <NavItem href="/billops-agreements" icon="fas fa-file-contract" label="Agreements" isActive={isActive('/billops-agreements')} />
            </NavSection>
          )}

          <NavSection
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
            {(hasAnyRole('ROLE_BILLOPS_ADMIN') || hasAnyRole('ROLE_SUPER_ADMIN')) && (!isAdminSubUser || isTabAllowed('user-manager')) && (
              <NavItem href="/billops-admin-user-manager" icon="fas fa-user-cog" label="User Manager" isActive={isActive('/billops-admin-user-manager')} />
            )}
            {hasAnyRole('ROLE_BILLOPS_ADMIN') && !isAdminSubUser && (
              <NavItem href="/my-profile" icon="fas fa-users-gear" label="My Team" isActive={isActive('/my-profile')} />
            )}
          </NavSection>
        </div>
      </div>
    </>
  );
};

export default Sidebar;
