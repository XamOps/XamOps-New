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
import { makeXamTabGate } from '../../constants/xamopsTabs';
import clsx from 'clsx';

/** XamSecure nested sidebar entries — mirror the tabs on XamSecurePage. */
const XAMSECURE_SUBTABS: { id: string; label: string; icon: string }[] = [
  { id: 'overview', label: 'Home', icon: 'fas fa-house' },
  { id: 'issues', label: 'Issues', icon: 'fas fa-triangle-exclamation' },
  { id: 'inventory', label: 'Inventory', icon: 'fas fa-boxes-stacked' },
  { id: 'attack-surface', label: 'Attack Surface', icon: 'fas fa-globe' },
  { id: 'vulnerabilities', label: 'Vulnerabilities', icon: 'fas fa-bug' },
  { id: 'posture', label: 'Configurations', icon: 'fas fa-clipboard-check' },
  { id: 'api-security', label: 'APIs', icon: 'fas fa-plug' },
  { id: 'identities', label: 'Identities', icon: 'fas fa-user-shield' },
  { id: 'threats', label: 'Threats', icon: 'fas fa-bolt' },
  { id: 'components', label: 'Components', icon: 'fas fa-cubes' },
];

const SOC2_SUBTABS: { id: string; label: string; icon: string; tooltip: string }[] = [
  { id: 'overview', label: 'Overview', icon: 'fas fa-gauge',
    tooltip: 'Audit readiness at a glance — control status, policies, tasks, personnel, vendors and open risks.' },
  { id: 'frameworks', label: 'Frameworks', icon: 'fas fa-layer-group',
    tooltip: 'Compliance frameworks (SOC 2) and their controls, mapped to Trust Service Criteria with status, policies and tasks.' },
  { id: 'policies', label: 'Policies', icon: 'fas fa-file-lines',
    tooltip: 'Security policy documents and their approval status — the written commitments behind each control.' },
  { id: 'evidence-tasks', label: 'Evidence & Tasks', icon: 'fas fa-clipboard-check',
    tooltip: 'Compliance tasks and the evidence collected for them, each with an owner and status.' },
  { id: 'documents', label: 'Documents', icon: 'fas fa-folder-open',
    tooltip: 'Required and optional evidence documents (policies, reports, diagrams) grouped by area, each tracked to completion.' },
  { id: 'personnel', label: 'Personnel', icon: 'fas fa-users',
    tooltip: 'People in scope for the audit — their roles, departments and access.' },
  { id: 'vendors', label: 'Vendors', icon: 'fas fa-building',
    tooltip: 'Third-party vendors and subprocessors, with inherent and residual risk ratings.' },
  { id: 'risks', label: 'Risks', icon: 'fas fa-triangle-exclamation',
    tooltip: 'Risk register — identified risks with severity, owner and treatment status.' },
  { id: 'integrations', label: 'Integrations', icon: 'fas fa-plug',
    tooltip: 'Connected tools that feed automated evidence into the program.' },
  { id: 'cloud-tests', label: 'Cloud Tests', icon: 'fas fa-cloud',
    tooltip: 'Automated cloud-security checks and their pass/fail findings across your cloud accounts.' },
];

const Sidebar: React.FC = () => {
  const { hasAnyRole } = useAuth();
  const { selectedAccountDisplay, accounts, selectedAccountIds } = useAccounts();
  const { theme } = useTheme();
  const location = useLocation();

  // Fetch billops team profile to get isPrimaryUser + allowedTabs for ROLE_BILLOPS_ADMIN / ROLE_BILLOPS_ADMIN_SUB
  // undefined = still loading, null = loaded (primary user / no restrictions), object = loaded sub-user
  const isBillopsAdminAny = hasAnyRole('ROLE_BILLOPS_ADMIN') || hasAnyRole('ROLE_BILLOPS_ADMIN_SUB');
  // Always start as undefined (loading) — isBillopsAdminAny is false at mount while roles are still
  // being fetched, so we cannot use it as the initial value or the profile would never be fetched.
  const [billopsProfile, setBillopsProfile] = useState<{ isPrimaryUser: boolean; allowedTabs: string[] } | null | undefined>(undefined);

  useEffect(() => {
    if (isBillopsAdminAny) {
      setBillopsProfile(undefined); // show skeleton while fetching
      fetch('/api/billops/team/profile', { credentials: 'include' })
        .then(r => r.ok ? r.json() : null)
        .then(data => {
          setBillopsProfile(data ? {
            isPrimaryUser: !!data.isPrimaryUser,
            allowedTabs: data.allowedTabs || []
          } : null);
        })
        .catch(() => setBillopsProfile(null));
    } else {
      // Roles not yet loaded or user is not a billops admin — keep undefined until roles resolve.
      // Once isBillopsAdminAny becomes true this effect re-runs and fetches the profile.
    }
  }, [isBillopsAdminAny]);

  // undefined = profile still loading; hide all admin tabs until resolved
  const billopsProfileLoading = billopsProfile === undefined;
  // Only restrict tabs for confirmed BILLOPS_ADMIN sub-users (isPrimaryUser explicitly false)
  // ROLE_BILLOPS_ADMIN_SUB users are always treated as primary (full admin nav, data scoped at backend)
  const isAdminSubUser = hasAnyRole('ROLE_BILLOPS_ADMIN') && !hasAnyRole('ROLE_BILLOPS_ADMIN_SUB') && billopsProfile !== null && billopsProfile !== undefined && !billopsProfile.isPrimaryUser;
  const isTabAllowed = (tabKey: string): boolean => {
    if (billopsProfileLoading) return false; // hide everything until we know access
    if (!isAdminSubUser) return true;
    return billopsProfile!.allowedTabs.includes(tabKey);
  };

  // ── XamOps sidebar module/tab access ──
  // Fetches the current XamOps user's allowedTabs. Empty/undefined = no restriction (full sidebar);
  // a non-empty list restricts the sidebar to exactly those tab keys. Applies to both primary
  // XamOps users (restricted at creation via the BillOps admin manager) and XamOps sub-users.
  const isXamops = hasAnyRole('ROLE_XAMOPS');
  const [xamAllowedTabs, setXamAllowedTabs] = useState<string[] | undefined>(undefined);
  useEffect(() => {
    if (isXamops) {
      fetch('/api/xamops/team/profile', { credentials: 'include' })
        .then(r => (r.ok ? r.json() : null))
        .then(data => setXamAllowedTabs(data ? (data.allowedTabs || []) : []))
        .catch(() => setXamAllowedTabs([]));
    }
  }, [isXamops]);
  // Optimistic while loading (xamAllowedTabs === undefined → not restricted → show all).
  const xamGate = makeXamTabGate(isXamops ? xamAllowedTabs : null);
  const isXamTabAllowed = xamGate.isTabAllowed;
  const isXamModuleAllowed = xamGate.isModuleAllowed;

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
      '/rightsizing', '/gcp-rightsizing', '/azure-rightsizing', '/ebs-rightsizing', '/gcp-disk-rightsizing',
      '/cloudsitter', '/gcp-cloudsitter', '/azure-cloudsitter', '/flex-compute', '/autospotting', '/gcp-autospotting', '/azure-autospotting'];
    const cloudopsRoutes = ['/cloudk8s', '/gcp-cloudk8s', '/azure-cloudk8s',
      '/sonarqube', '/cicd-pipelines', '/devops-in-the-box', '/aiops', '/dataops', '/gcp-dataops', '/azure-dataops', '/ebs-rightsizing'];
    const ticketsRoutes = ['/tickets', '/billops-tickets'];
    const secopsRoutes = ['/xamsecure', '/security', '/gcp-security', '/azure-security', '/war', '/gcp-war', '/complianceops', '/soc2-program',
      '/performance', '/gcp-performance', '/azure-performance', '/alerts', '/gcp-alerts', '/azure-alerts'];
    const licensesRoutes = ['/marketplace-purchases', '/workspace-licenses', '/isv-metering'];
    const observabilityRoutes = ['/observability', '/grafana-dashboard'];
    const sreRoutes = ['/sre'];
    const mediaopsRoutes = ['/media-ops'];
    // const accessopsRoutes = ['/identity/users', '/identity/groups', '/identity/audit'];

    if (finopsRoutes.some(r => path.startsWith(r))) setOpenDropdown('finops');
    else if (cloudopsRoutes.some(r => path.startsWith(r))) setOpenDropdown('cloudops');
    else if (ticketsRoutes.some(r => path.startsWith(r))) setOpenDropdown('tickets');
    else if (secopsRoutes.some(r => path.startsWith(r))) setOpenDropdown('security');
    else if (licensesRoutes.some(r => path.startsWith(r))) setOpenDropdown('licenses');
    else if (observabilityRoutes.some(r => path.startsWith(r))) setOpenDropdown('observability');
    else if (sreRoutes.some(r => path.startsWith(r))) setOpenDropdown('sre');
    else if (mediaopsRoutes.some(r => path.startsWith(r))) setOpenDropdown('mediaops');
    // else if (accessopsRoutes.some(r => path.startsWith(r))) setOpenDropdown('accessops');
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
            return '/billops-billing';
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

  return (
    <>
      {/* Mobile Hamburger Button */}
      <button
        onClick={toggleMobileMenu}
        className="lg:hidden fixed top-3 left-3 z-50 p-2 rounded-md border border-[rgba(0,0,0,0.06)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.06)] transition-colors"
        aria-label="Toggle sidebar menu"
      >
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>

      {/* Mobile Overlay */}
      {mobileMenuOpen && (
        <div
          className="lg:hidden fixed inset-0 bg-black/20 z-20"
          onClick={closeMobileMenu}
        />
      )}

      {/* Sidebar Container — 220px */}
      <div
        className={clsx(
          'fixed inset-y-0 left-0 flex flex-col transition-[transform,background-color] duration-200 ease-in-out z-40 w-[220px] border-r',
          theme === 'dark' ? 'bg-[#0d0d0f] border-[rgba(255,255,255,0.07)]' : 'bg-[#fafafa] border-[rgba(0,0,0,0.06)]',
          mobileMenuOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
        )}
      >
        {/* Header — 48px, logo + wordmark */}
        <div className="h-12 flex items-center px-3 flex-shrink-0">
          <Link
            to="/unified-dashboard"
            className="flex items-center gap-2.5 hover:opacity-80 transition-opacity"
          >
            <img
              src="/icons/XamOps.png"
              alt="XamOps Logo"
              className="w-6 h-6 object-contain flex-shrink-0"
            />
            <span
              className="text-[18px] font-bold leading-7 text-nowrap"
              style={{ fontFamily: "'Barlow Condensed', 'Inter', sans-serif", letterSpacing: '0px', color: theme === 'dark' ? 'rgb(255,255,255)' : 'rgb(17,24,39)', fontStretch: '100%' }}
            >
              XamOps
            </span>
          </Link>
        </div>

        {/* Account Selector */}
        {!isBillopsAdminAny && (
          <div className="px-2 py-2">
            <AccountSelector
              isOpen={accountSelectorOpen}
              onToggle={() => setAccountSelectorOpen(prev => !prev)}
              onClose={closeAccountSelector}
            />
          </div>
        )}

        {/* Navigation Menu */}
        <div className="flex-1 overflow-y-auto sidebar-scroll px-2 py-2 space-y-0.5">

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

          {/* ROLE_BILLOPS_ADMIN / ROLE_BILLOPS_ADMIN_SUB Specific Items */}
          {isBillopsAdminAny && (
            <>
              {billopsProfileLoading ? (
                [1, 2, 3].map(i => (
                  <div key={i} className={`mx-2 my-0.5 h-[33px] rounded-md animate-pulse ${theme === 'dark' ? 'bg-[rgba(255,255,255,0.06)]' : 'bg-[rgba(0,0,0,0.04)]'}`} />
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
                  {isTabAllowed('client-intake') && <NavItem href="/billops-client-intake" icon="fas fa-clipboard-list" label="Client Intake" isActive={isActive('/billops-client-intake')} />}
                </>
              )}
            </>
          )}

          {/* Standard Cloud Ops / FinOps Sections */}
          {(hasAnyRole('ROLE_XAMOPS') || hasAnyRole('ROLE_SUPER_ADMIN')) && (
            <>
              {isXamTabAllowed('account-dashboard') && (
                <NavItem
                  href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-dashboard' : selectedAccountDisplay.provider === 'Azure' ? '/azure-dashboard' : '/dashboard'}
                  icon="fas fa-chart-line"
                  label="Account Dashboard"
                  gradientClass="from-blue-500 to-indigo-600"
                  isActive={isActive('/dashboard')}
                />
              )}

              {isXamModuleAllowed('finops') && (
              <NavSection
                icon="fas fa-dollar-sign"
                label="FinOps"
                gradientClass="from-green-500 to-teal-600"
                isOpen={openDropdown === 'finops'}
                onToggle={() => toggleDropdown('finops')}
              >
                {isXamTabAllowed('finops-cost') && <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-cost' : selectedAccountDisplay.provider === 'Azure' ? '/azure-cost' : '/cost'} icon="fas fa-dollar-sign" label="Cost Management" isActive={isActive('/cost') || isActive('/gcp-cost') || isActive('/azure-cost')} />}
                {isXamTabAllowed('finops-reservations') && <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-reservations' : selectedAccountDisplay.provider === 'Azure' ? '/azure-reservations' : '/reservation'} icon="fas fa-file-contract" label="Reservations" isActive={isActive('/reservation') || isActive('/gcp-reservations') || isActive('/azure-reservations')} />}
                {isXamTabAllowed('finops-reports') && <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-finops' : selectedAccountDisplay.provider === 'Azure' ? '/azure-finops' : '/finops'} icon="fas fa-file-invoice-dollar" label="FinOps Reports" isActive={isActive('/finops') || isActive('/gcp-finops') || isActive('/azure-finops')} />}
                {isXamTabAllowed('finops-waste') && <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-waste' : selectedAccountDisplay.provider === 'Azure' ? '/azure-waste' : '/waste'} icon="fas fa-recycle" label="Waste Management" isActive={isActive('/waste') || isActive('/gcp-waste') || isActive('/azure-waste')} />}
                {isXamTabAllowed('finops-rightsizing') && <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-rightsizing' : selectedAccountDisplay.provider === 'Azure' ? '/azure-rightsizing' : '/rightsizing'} icon="fas fa-compress-arrows-alt" label="Rightsizing" isActive={isActive('/rightsizing') || isActive('/gcp-rightsizing') || isActive('/azure-rightsizing')} />}
                {isXamTabAllowed('finops-disk-rightsizing') && selectedAccountDisplay.provider === 'AWS' && (
                  <NavItem href="/ebs-rightsizing" icon="fas fa-hdd" label="Disk Rightsizing" isActive={isActive('/ebs-rightsizing')} />
                )}
                {isXamTabAllowed('finops-disk-rightsizing') && selectedAccountDisplay.provider === 'GCP' && (
                  <NavItem href="/gcp-disk-rightsizing" icon="fas fa-hdd" label="Disk Rightsizing" isActive={isActive('/gcp-disk-rightsizing')} />
                )}
                {selectedAccountDisplay.provider === 'AWS' && (
                  <>
                    {isXamTabAllowed('finops-cloudsitter') && <NavItem href="/cloudsitter" icon="fas fa-clock" label="CloudSitter" isActive={isActive('/cloudsitter')} />}
                    {isXamTabAllowed('finops-flex-compute') && <NavItem href="/flex-compute" icon="fas fa-microchip" label="Flex Compute" isActive={isActive('/flex-compute')} />}
                    {isXamTabAllowed('finops-autospotting') && <NavItem href="/autospotting" icon="fas fa-magic" label="AutoSpotting" isActive={isActive('/autospotting')} />}
                  </>
                )}
                {selectedAccountDisplay.provider === 'GCP' && (
                  <>
                    {isXamTabAllowed('finops-cloudsitter') && <NavItem href="/gcp-cloudsitter" icon="fas fa-clock" label="CloudSitter" isActive={isActive('/gcp-cloudsitter')} />}
                    {isXamTabAllowed('finops-autospotting') && <NavItem href="/gcp-autospotting" icon="fas fa-magic" label="AutoSpotting" isActive={isActive('/gcp-autospotting')} />}
                  </>
                )}
                {selectedAccountDisplay.provider === 'Azure' && (
                  <>
                    {isXamTabAllowed('finops-cloudsitter') && <NavItem href="/azure-cloudsitter" icon="fas fa-clock" label="CloudSitter" isActive={isActive('/azure-cloudsitter')} />}
                    {isXamTabAllowed('finops-autospotting') && <NavItem href="/azure-autospotting" icon="fas fa-magic" label="AutoSpotting" isActive={isActive('/azure-autospotting')} />}
                  </>
                )}
              </NavSection>
              )}

              {isXamModuleAllowed('cloudops') && (
              <NavSection
                icon="fas fa-server"
                label="CloudOps"
                gradientClass="from-indigo-500 to-purple-600"
                isOpen={openDropdown === 'cloudops'}
                onToggle={() => toggleDropdown('cloudops')}
              >
                {isXamTabAllowed('cloudops-cloudk8s') && <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-cloudk8s' : selectedAccountDisplay.provider === 'Azure' ? '/azure-cloudk8s' : '/cloudk8s'} icon="fas fa-dharmachakra" label="CloudK8s" isActive={isActive('/cloudk8s')} />}
                {isXamTabAllowed('cloudops-code-quality') && <NavItem href="/sonarqube" icon="fas fa-chart-line" label="Code Quality" isActive={isActive('/sonarqube')} />}
                {isXamTabAllowed('cloudops-cicd') && <NavItem href="/cicd-pipelines" icon="fas fa-cogs" label="CICD Pipelines" isActive={isActive('/cicd-pipelines')} />}
                {isXamTabAllowed('cloudops-devops-box') && <NavItem href="/devops-in-the-box" icon="fas fa-toolbox" label="DevOps-in-the-Box" isActive={isActive('/devops-in-the-box')} />}
                {isXamTabAllowed('cloudops-aiops') && <NavItem href="/aiops" icon="fas fa-brain" label="AIOps" isActive={isActive('/aiops')} />}
                {isXamTabAllowed('cloudops-dbops') && <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-dataops' : selectedAccountDisplay.provider === 'Azure' ? '/azure-dataops' : '/dataops'} icon="fas fa-database" label="DbOps" isActive={isActive('/dataops') || isActive('/gcp-dataops') || isActive('/azure-dataops')} />}
              </NavSection>
              )}

              {isXamModuleAllowed('secops') && (
              <NavSection
                icon="fas fa-shield-alt"
                label="SecOps"
                gradientClass="from-red-500 to-orange-600"
                isOpen={openDropdown === 'security'}
                onToggle={() => toggleDropdown('security')}
              >
                {isXamTabAllowed('secops-security-center') && <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-security' : selectedAccountDisplay.provider === 'Azure' ? '/azure-security' : '/security'} icon="fas fa-shield-alt" label="Security Center" isActive={isActive('/security') || isActive('/gcp-security') || isActive('/azure-security')} />}
                {isXamTabAllowed('secops-war') && <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-war' : '/war'} icon="fas fa-crosshairs" label="WAR" isActive={isActive('/war') || isActive('/gcp-war')} tooltip="Workload Attack-surface Review — infrastructure exposure to attackers and crypto-miners" />}
                {isXamTabAllowed('secops-xamsecure') && (
                  <>
                    <NavItem href="/xamsecure/overview" icon="fas fa-user-shield" label="XamSecure" isActive={isActive('/xamsecure')} />
                    {location.pathname.startsWith('/xamsecure') && (
                      <div className={clsx(
                        'ml-3 pl-3 border-l space-y-0.5',
                        theme === 'dark' ? 'border-[rgba(255,255,255,0.08)]' : 'border-[rgba(0,0,0,0.08)]'
                      )}>
                        {XAMSECURE_SUBTABS.map((s) => (
                          <NavItem
                            key={s.id}
                            href={`/xamsecure/${s.id}`}
                            icon={s.icon}
                            label={s.label}
                            isActive={location.pathname === `/xamsecure/${s.id}`}
                          />
                        ))}
                      </div>
                    )}
                  </>
                )}
                {isXamTabAllowed('secops-compliance') && <NavItem href="/complianceops" icon="fas fa-file-contract" label="ComplianceOps" isActive={isActive('/complianceops')} />}
                {isXamTabAllowed('secops-compliance') && (
                  <>
                    <NavItem href="/soc2-program/overview" icon="fas fa-clipboard-check" label="Program" isActive={isActive('/soc2-program')} />
                    {location.pathname.startsWith('/soc2-program') && (
                      <div className={clsx(
                        'ml-3 pl-3 border-l space-y-0.5',
                        theme === 'dark' ? 'border-[rgba(255,255,255,0.08)]' : 'border-[rgba(0,0,0,0.08)]'
                      )}>
                        {SOC2_SUBTABS.map((s) => (
                          <NavItem
                            key={s.id}
                            href={`/soc2-program/${s.id}`}
                            icon={s.icon}
                            label={s.label}
                            tooltip={s.tooltip}
                            isActive={location.pathname === `/soc2-program/${s.id}`}
                          />
                        ))}
                      </div>
                    )}
                  </>
                )}
                {isXamTabAllowed('secops-performance') &&<NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-performance' : selectedAccountDisplay.provider === 'Azure' ? '/azure-performance' : '/performance'} icon="fas fa-chart-line" label="Performance Insights" isActive={isActive('/performance') || isActive('/gcp-performance') || isActive('/azure-performance')} />}
                {isXamTabAllowed('secops-alerts') && <NavItem href={selectedAccountDisplay.provider === 'GCP' ? '/gcp-alerts' : selectedAccountDisplay.provider === 'Azure' ? '/azure-alerts' : '/alerts'} icon="fas fa-bell" label="Alerts" isActive={isActive('/alerts') || isActive('/gcp-alerts') || isActive('/azure-alerts')} />}
              </NavSection>
              )}

              {isXamModuleAllowed('observability') && (
              <NavSection
                icon="fas fa-satellite-dish"
                label="Observability"
                gradientClass="from-violet-500 to-purple-600"
                badge="Beta"
                isOpen={openDropdown === 'observability'}
                onToggle={() => toggleDropdown('observability')}
              >
                {isXamTabAllowed('obs-overview') && <NavItem href="/observability" icon="fas fa-chart-pie" label="Overview" isActive={location.pathname === '/observability'} />}
                {isXamTabAllowed('obs-logs') && <NavItem href="/observability/logs" icon="fas fa-list-alt" label="Logs" isActive={isActive('/observability/logs')} />}
                {isXamTabAllowed('obs-metrics') && <NavItem href="/observability/metrics" icon="fas fa-chart-line" label="Metrics" isActive={isActive('/observability/metrics')} />}
                {isXamTabAllowed('obs-traces') && <NavItem href="/observability/traces" icon="fas fa-project-diagram" label="Traces" isActive={isActive('/observability/traces')} />}
                {isXamTabAllowed('obs-alerts') && <NavItem href="/observability/alerts" icon="fas fa-bell" label="Alerts" isActive={isActive('/observability/alerts')} />}
                {isXamTabAllowed('obs-setup') && <NavItem href="/observability/setup" icon="fas fa-cog" label="Setup" isActive={isActive('/observability/setup')} />}
                {isXamTabAllowed('obs-grafana') && <NavItem href="/grafana-dashboard" icon="fas fa-chart-line" label="Grafana" isActive={isActive('/grafana-dashboard')} />}
              </NavSection>
              )}

              {isXamModuleAllowed('sre') && (
              <NavSection
                icon="fas fa-stethoscope"
                label="SRE"
                gradientClass="from-indigo-500 to-violet-600"
                badge="Coming Soon"
                isOpen={openDropdown === 'sre'}
                onToggle={() => toggleDropdown('sre')}
              >
                {isXamTabAllowed('sre-dashboard') && <NavItem href="/sre" icon="fas fa-chart-pie" label="Dashboard" isActive={location.pathname === '/sre'} />}
                {isXamTabAllowed('sre-investigations') && <NavItem href="/sre/investigations" icon="fas fa-search" label="Investigations" isActive={isActive('/sre/investigations')} />}
                {isXamTabAllowed('sre-setup') && <NavItem href="/sre/setup" icon="fas fa-cog" label="Setup" isActive={isActive('/sre/setup')} />}
              </NavSection>
              )}

              {isXamModuleAllowed('mediaops') && (
              <NavSection
                icon="fas fa-film"
                label="MediaOps"
                gradientClass="from-pink-500 to-rose-600"
                badge="Beta"
                isOpen={openDropdown === 'mediaops'}
                onToggle={() => toggleDropdown('mediaops')}
              >
                {isXamTabAllowed('mediaops-overview') && <NavItem href="/media-ops" icon="fas fa-chart-pie" label="Overview" isActive={location.pathname === '/media-ops'} />}
                {isXamTabAllowed('mediaops-transcode') && <NavItem href="/media-ops/transcode" icon="fas fa-exchange-alt" label="Transcoding Jobs" isActive={isActive('/media-ops/transcode')} />}
                {isXamTabAllowed('mediaops-streams') && <NavItem href="/media-ops/streams" icon="fas fa-broadcast-tower" label="Live Streams" isActive={isActive('/media-ops/streams')} />}
                {isXamTabAllowed('mediaops-storage') && <NavItem href="/media-ops/storage" icon="fas fa-database" label="Media Storage" isActive={isActive('/media-ops/storage')} />}
                {isXamTabAllowed('mediaops-cost-savings') && <NavItem href="/media-ops/cost-savings" icon="fas fa-piggy-bank" label="Cost Savings" isActive={isActive('/media-ops/cost-savings')} />}
              </NavSection>
              )}

              {isXamModuleAllowed('licenses') && (
              <NavSection
                icon="fas fa-key"
                label="Licenses"
                gradientClass="from-cyan-500 to-blue-600"
                isOpen={openDropdown === 'licenses'}
                onToggle={() => toggleDropdown('licenses')}
              >
                {isXamTabAllowed('licenses-marketops') && <NavItem href="/marketplace-purchases" icon="fas fa-store" label="MarketOps" isActive={isActive('/marketplace-purchases')} />}
                {isXamTabAllowed('licenses-tools') && <NavItem href="/workspace-licenses" icon="fas fa-key" label="Licenses & Tools" isActive={isActive('/workspace-licenses')} />}
              </NavSection>
              )}

              {/* AccessOps — temporarily hidden
              <NavSection
                icon="fas fa-id-card"
                label="AccessOps"
                gradientClass="from-violet-500 to-indigo-600"
                isOpen={openDropdown === 'accessops'}
                onToggle={() => toggleDropdown('accessops')}
              >
                <NavItem href="/identity/users"  icon="fas fa-users"          label="Users"      isActive={isActive('/identity/users')} />
                <NavItem href="/identity/groups" icon="fas fa-layer-group"    label="Groups"     isActive={isActive('/identity/groups')} />
                <NavItem href="/identity/audit"  icon="fas fa-clipboard-list" label="Audit Log"  isActive={isActive('/identity/audit')} />
              </NavSection>
              */}

              {isXamModuleAllowed('tickets') && (
              <NavSection
                icon="fas fa-ticket-alt"
                label="Tickets"
                gradientClass="from-yellow-500 to-orange-500"
                isOpen={openDropdown === 'tickets'}
                onToggle={() => toggleDropdown('tickets')}
              >
                {isXamTabAllowed('xam-tickets-technical') && <NavItem href="/tickets" icon="fas fa-tools" label="Technical" isActive={isActive('/tickets')} />}
                {isXamTabAllowed('xam-tickets-account-billing') && <NavItem href="/billops-tickets" icon="fas fa-file-invoice-dollar" label="Account & Billing" isActive={isActive('/billops-tickets')} />}
              </NavSection>
              )}
            </>
          )}
        </div>

        {/* Sidebar Footer */}
        <div className={`px-2 py-2 space-y-0.5 border-t ${theme === 'dark' ? 'border-[rgba(255,255,255,0.08)]' : 'border-[rgba(0,0,0,0.06)]'}`}>
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
            {(isBillopsAdminAny || hasAnyRole('ROLE_SUPER_ADMIN')) && (!isAdminSubUser || isTabAllowed('user-manager')) && (
              <NavItem href="/billops-admin-user-manager" icon="fas fa-user-cog" label="User Manager" isActive={isActive('/billops-admin-user-manager')} />
            )}
            {isBillopsAdminAny && !isAdminSubUser && (
              <NavItem href="/my-profile" icon="fas fa-users-gear" label="My Team" isActive={isActive('/my-profile')} />
            )}
          </NavSection>
        </div>
      </div>
    </>
  );
};

export default Sidebar;
