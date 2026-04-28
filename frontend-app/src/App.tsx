/**
 * Main App Component
 *
 * This is the root component of the React application.
 * Uses React Router for navigation between pages.
 *
 * Routes:
 * - / (root) - Login page
 * - /unified-dashboard - Unified dashboard (requires auth)
 * - /dashboard - Live dashboard with real-time updates (requires auth)
 * - /finops - FinOps report page (requires auth)
 */

import React, { lazy, Suspense } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, useLocation, Outlet } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from './lib/queryClient';
import clsx from 'clsx';
import { AuthProvider, useAuth } from './contexts/AuthContext';
import { AccountProvider } from './contexts/AccountContext';
import { VPCProvider } from './contexts/VPCContext';
import { EksDashboardCacheProvider } from './contexts/EksDashboardCacheContext';
import { AutoSpottingCacheProvider } from './contexts/AutoSpottingCacheContext';
import { Sidebar } from './components/Sidebar';
import { TopbarProvider } from './contexts/TopbarContext';
import { ThemeProvider, useTheme } from './contexts/ThemeContext';
import Topbar from './components/Shared/Topbar';
import { SupportChatWidget } from './components/SupportChat';
import AdminSupportNotificationListener from './components/Shared/AdminSupportNotificationListener';
import UserSupportNotificationListener from './components/Shared/UserSupportNotificationListener';
import ErrorBoundary from './components/Shared/ErrorBoundary';
import RouteChangeHandler from './components/Shared/RouteChangeHandler';
import { CommandPalette } from './components/Shared/CommandPalette';
import { GooeyToaster } from 'goey-toast';
import 'goey-toast/styles.css';

// ─── Lazy page imports ────────────────────────────────────────────────────────
// Login is kept eager — it's the entry point, always needed immediately.
import LoginPage from './pages/LoginPage';

// Dashboards
const UnifiedDashboard = lazy(() => import('./pages/UnifiedDashboard').then(m => ({ default: m.UnifiedDashboard })));
const Dashboard = lazy(() => import('./pages/Dashboard'));
const GcpDashboard = lazy(() => import('./pages/GcpDashboard'));
const AzureDashboard = lazy(() => import('./pages/AzureDashboard'));

// AWS — FinOps & Cost
const FinOpsPage = lazy(() => import('./pages/FinOpsPage'));
const CostPage = lazy(() => import('./pages/CostPage'));
const WastePage = lazy(() => import('./pages/WastePage'));
const RightsizingPage = lazy(() => import('./pages/RightsizingPage'));
const ReservationPage = lazy(() => import('./pages/ReservationPage'));
const EbsRightsizingPage = lazy(() => import('./pages/EbsRightsizingPage'));
const FlexComputePage = lazy(() => import('./pages/FlexComputePage'));

// AWS — Infrastructure
const CloudSitterPage = lazy(() => import('./pages/CloudSitterPage'));
const SpotAutomationPage = lazy(() => import('./pages/SpotAutomationPage'));
const ASGDetailPage = lazy(() => import('./pages/ASGDetailPage'));
const ASGConfigPage = lazy(() => import('./pages/ASGConfigPage'));
const CloudK8sPage = lazy(() => import('./pages/CloudK8sPage'));
const EksDetailsPage = lazy(() => import('./pages/EksDetailsPage'));
const CloudListPage = lazy(() => import('./pages/CloudListPage'));
const CloudMapPage = lazy(() => import('./pages/CloudMapPage'));
const CloudShellPage = lazy(() => import('./pages/CloudShellPage'));
const ResourceLabelsPage = lazy(() => import('./pages/ResourceLabelsPage'));
const GrafanaDashboardPage = lazy(() => import('./pages/GrafanaDashboardPage'));

// AWS — Security & Monitoring
const SecurityPage = lazy(() => import('./pages/SecurityPage'));
const PerformancePage = lazy(() => import('./pages/PerformancePage'));
const AlertsPage = lazy(() => import('./pages/AlertsPage'));

// GCP
const GcpFinOpsPage = lazy(() => import('./pages/GcpFinOpsPage'));
const GcpCostPage = lazy(() => import('./pages/GcpCostPage'));
const GcpWastePage = lazy(() => import('./pages/GcpWastePage'));
const GcpRightsizingPage = lazy(() => import('./pages/GcpRightsizingPage'));
const GcpReservationsPage = lazy(() => import('./pages/GcpReservationsPage'));
const GcpCloudK8sPage = lazy(() => import('./pages/GcpCloudK8sPage'));
const GkeDetailsPage = lazy(() => import('./pages/GkeDetailsPage'));
const GcpCloudListPage = lazy(() => import('./pages/GcpCloudListPage'));
const GcpCloudMapPage = lazy(() => import('./pages/GcpCloudMapPage'));
const GcpSecurityPage = lazy(() => import('./pages/GcpSecurityPage'));
const GcpPerformancePage = lazy(() => import('./pages/GcpPerformancePage'));
const GcpAlertsPage = lazy(() => import('./pages/GcpAlertsPage'));
const GcpBillingPage = lazy(() => import('./pages/GcpBillingPage'));
const GcpSpotAutomationPage = lazy(() => import('./pages/GcpSpotAutomationPage'));
const GcpDataOpsPage = lazy(() => import('./pages/GcpDataOpsPage'));
const GcpDiskRightsizingPage = lazy(() => import('./pages/GcpDiskRightsizingPage'));
const AzureDataOpsPage = lazy(() => import('./pages/AzureDataOpsPage'));
const AzureDiskRightsizingPage = lazy(() => import('./pages/AzureDiskRightsizingPage'));

// Azure
const AzureFinOpsPage = lazy(() => import('./pages/AzureFinOpsPage'));
const AzureCostPage = lazy(() => import('./pages/AzureCostPage'));
const AzureWastePage = lazy(() => import('./pages/AzureWastePage'));
const AzureRightsizingPage = lazy(() => import('./pages/AzureRightsizingPage'));
const AzureReservationsPage = lazy(() => import('./pages/AzureReservationsPage'));
const AzureCloudK8sPage = lazy(() => import('./pages/AzureCloudK8sPage'));
const AksDetailsPage = lazy(() => import('./pages/AksDetailsPage'));
const AzureCloudList = lazy(() => import('./pages/AzureCloudList'));
const AzureSecurityPage = lazy(() => import('./pages/AzureSecurityPage'));
const AzurePerformancePage = lazy(() => import('./pages/AzurePerformancePage'));
const AzureAlertsPage = lazy(() => import('./pages/AzureAlertsPage'));
const AzureSpotAutomationPage = lazy(() => import('./pages/AzureSpotAutomationPage'));

// DevOps & Tools
const AccountManagerPage = lazy(() => import('./pages/AccountManagerPage'));
const AddAccountPage = lazy(() => import('./pages/AddAccountPage'));
const AddGcpAccountPage = lazy(() => import('./pages/AddGcpAccountPage'));
const GitHubConfigPage = lazy(() => import('./pages/GitHubConfigPage'));
const DevOpsInTheBoxPage = lazy(() => import('./pages/DevOpsInTheBoxPage'));
const CICDPipelinesPage = lazy(() => import('./pages/CICDPipelinesPage'));
const SonarQubePage = lazy(() => import('./pages/SonarQubePage'));
const AIOpsPage = lazy(() => import('./pages/AIOpsPage'));
const DataOpsPage = lazy(() => import('./pages/DataOpsPage'));
const ObservabilityPage = lazy(() => import('./pages/ObservabilityPage'));
const SreInvestigationsPage = lazy(() => import('./pages/SreInvestigationsPage'));
const SreInvestigationDetailPage = lazy(() => import('./pages/SreInvestigationDetailPage'));
const ComplianceOpsPage = lazy(() => import('./pages/ComplianceOpsPage'));
const CalculatorPage = lazy(() => import('./pages/CalculatorPage'));

// BillOps — User
const BillOpsUserDashboardPage = lazy(() => import('./pages/BillOpsUserDashboardPage'));
const BillingPage = lazy(() => import('./pages/BillingPage'));
const InvoicesPage = lazy(() => import('./pages/InvoicesPage'));
const CreditsPage = lazy(() => import('./pages/CreditsPage'));
const BillOpsTicketsPage = lazy(() => import('./pages/BillOpsTicketsPage'));
const BillOpsTicketDetailPage = lazy(() => import('./pages/BillOpsTicketDetailPage'));
const AgreementsPage = lazy(() => import('./pages/AgreementsPage'));
const MarketplacePurchasesPage = lazy(() => import('./pages/MarketplacePurchasesPage'));
const WorkspaceLicensesPage = lazy(() => import('./pages/WorkspaceLicensesPage'));

// BillOps — Admin
const BillOpsSupportDashboardPage = lazy(() => import('./pages/BillOpsSupportDashboardPage'));
const BillOpsAdminDashboardPage = lazy(() => import('./pages/BillOpsAdminDashboardPage'));
const AutoSpottingAdminDashboardPage = lazy(() => import('./pages/AutoSpottingAdminDashboardPage'));
const BillOpsAdminInvoicesPage = lazy(() => import('./pages/BillOpsAdminInvoicesPage'));
const BillOpsAdminInvoiceDetailPage = lazy(() => import('./pages/BillOpsAdminInvoiceDetailPage'));
const BillOpsAdminCreditsPage = lazy(() => import('./pages/BillOpsAdminCreditsPage'));
const BillOpsAdminTicketsPage = lazy(() => import('./pages/BillOpsAdminTicketsPage'));
const BillOpsAdminAgreementsPage = lazy(() => import('./pages/BillOpsAdminAgreementsPage'));
const BillOpsAdminMarketplaceBillingPage = lazy(() => import('./pages/BillOpsAdminMarketplaceBillingPage'));
const BillOpsAdminUserManagerPage = lazy(() => import('./pages/BillOpsAdminUserManagerPage'));
const ClientDetailsPage = lazy(() => import('./pages/ClientDetailsPage'));

// AccessOps — Identity Management (temporarily hidden)
// const IdentityUsersPage  = lazy(() => import('./pages/identity/IdentityUsersPage'));
// const IdentityGroupsPage = lazy(() => import('./pages/identity/IdentityGroupsPage'));
// const IdentityAuditPage  = lazy(() => import('./pages/identity/IdentityAuditPage'));

// SRE
// const SreInvestigationsPage = lazy(() => import('./pages/SreInvestigationsPage'));
// const SreInvestigationDetailPage = lazy(() => import('./pages/SreInvestigationDetailPage'));

// Settings & Profile
const TicketsPage = lazy(() => import('./pages/TicketsPage'));
const TicketDetailPage = lazy(() => import('./pages/TicketDetailPage'));
const UserManagementPage = lazy(() => import('./pages/UserManagementPage'));
const MyProfilePage = lazy(() => import('./pages/MyProfilePage'));
// ─────────────────────────────────────────────────────────────────────────────


// Import styles
import './styles/sidebar.css';
import './styles/dashboard.css';
import './styles/liveDashboard.css';
import './styles/finops.css';
import './styles/waste.css';
import './styles/rightsizing.css';
import './styles/reservation.css';
import './styles/cloudsitter.css';
import './styles/spotautomation.css';
import './styles/actionsHistory.css';
import './styles/grafana.css';
import './styles/cloudk8s.css';
import './styles/eks-details.css';
import './styles/performance.css';
import './styles/alerts.css';
import './styles/tickets.css';
import './styles/ticket-detail.css';
import './styles/cloudlist.css';
import './styles/cloudshell.css';
import './styles/accountManager.css';
import './styles/addAccount.css';
import './styles/addGcpAccount.css';
import './styles/githubConfig.css';
import './styles/devopsInTheBox.css';
import './styles/cicdPipelines.css';
import './styles/sonarqube.css';
import './styles/aiops.css';
import './styles/dataops.css';
import './styles/billopsUserDashboard.css';
import './styles/billing.css';
import './styles/invoices.css';
import './styles/billopsTickets.css';
import './styles/billopsTicketDetail.css';
import './styles/supportDashboard.css';
import './styles/billopsAdminDashboard.css';
import './styles/billopsAdminInvoices.css';
import './styles/billopsAdminTickets.css';
import './styles/marketplacePurchases.css';
import './styles/workspaceLicenses.css';
import './styles/userManagement.css';
import './styles/myProfile.css';
import './styles/billopsAdminUserManager.css';
import './styles/gcpDashboard.css';
import './styles/gcpFinops.css';
import './styles/gcpCost.css';
import './styles/gcpWaste.css';
import './styles/gcpRightsizing.css';
import './styles/gcpReservations.css';
import './styles/gcpCloudK8s.css';
import './styles/gcpSecurity.css';
import './styles/gcpPerformance.css';
import './styles/gcpAlerts.css';
import './styles/gcpBilling.css';
import './styles/calculator.css';
import './styles/gcpCloudList.css';
import './styles/cloudmap.css';
import './styles/gcpCloudmap.css';
import './styles/azureDashboard.css';
import './styles/azureCloudList.css';
import './styles/azureFinops.css';
import './styles/flexCompute.css';
import './styles/autoscaling.css';
// import './styles/accessops.css';

// Helper function to check authentication
// Note: Since cookies might be HttpOnly, we check by making an API call
const checkAuth = async (): Promise<boolean> => {
    try {
        console.log('🔐 Checking auth... Cookies:', document.cookie);
        const response = await fetch('/api/xamops/user/profile', {
            credentials: 'include',
            headers: {
                'Accept': 'application/json'
            }
        });
        console.log('🔐 Auth check response:', response.status, response.statusText);
        console.log('🔐 Auth check URL:', response.url);

        if (response.ok) {
            const data = await response.json();
            console.log('🔐 Auth check data:', data);
        }

        return response.ok;
    } catch (error) {
        console.error('❌ Auth check failed:', error);
        return false;
    }
};

// Protected Route wrapper
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    const location = useLocation();
    const [isAuthenticated, setIsAuthenticated] = React.useState<boolean | null>(null);

    React.useEffect(() => {
        console.log('🔐 ProtectedRoute: Checking auth for path:', location.pathname);

        checkAuth().then(authenticated => {
            console.log('🔐 ProtectedRoute: isAuthenticated =', authenticated);
            setIsAuthenticated(authenticated);
        }).catch(() => {
            console.log('🔐 ProtectedRoute: Auth check error');
            setIsAuthenticated(false);
        });
    }, []); // Only check on mount, not on every path change

    // Show loading while checking auth
    if (isAuthenticated === null) {
        return (
            <div className="flex items-center justify-center h-screen">
                <div className="text-gray-600">Loading...</div>
            </div>
        );
    }

    if (!isAuthenticated) {
        console.log('Not authenticated, redirecting to login');
        return <Navigate to="/" replace state={{ from: location }} />;
    }

    return <>{children}</>;
};

/** Tab key → React route mapping for BillOps admin tabs */
const BILLOPS_ADMIN_TAB_ROUTES: Record<string, string> = {
    'admin-dashboard': '/billops-admin-dashboard',
    'support-inbox': '/billops-support-dashboard',
    'all-tickets': '/billops-admin-tickets',
    'all-invoices': '/billops-admin-invoices',
    'credit-requests': '/billops-admin-credits',
    'agreement-admin': '/billops-admin-agreements',
    'client-details': '/billops-client-details',
    'autospotting-admin': '/autospotting-admin',
    'historical-billing': '/billops-admin-historical-billing',
};
const BILLOPS_ADMIN_TAB_ORDER = Object.keys(BILLOPS_ADMIN_TAB_ROUTES);

/**
 * Dashboard Guard - Redirects ROLE_BILLOPS (non-XAMOPS) users to BillOps dashboard
 * whenever they try to access standard dashboards.
 * For ROLE_BILLOPS_ADMIN sub-users, redirects to their first allowed tab instead
 * of always landing on the admin dashboard they may not have access to.
 */
const DashboardGuard: React.FC = () => {
    const { hasAnyRole, isLoading } = useAuth();
    const [billopsRedirect, setBillopsRedirect] = React.useState<string | null>(null);

    React.useEffect(() => {
        if (isLoading || (!hasAnyRole('ROLE_BILLOPS_ADMIN') && !hasAnyRole('ROLE_BILLOPS_ADMIN_SUB')) || hasAnyRole('ROLE_SUPER_ADMIN')) return;

        fetch('/api/billops/team/profile', { credentials: 'include' })
            .then(r => r.ok ? r.json() : null)
            .then(data => {
                if (!data || data.isPrimaryUser) {
                    setBillopsRedirect('/billops-admin-dashboard');
                    return;
                }
                // Sub-user: redirect to their first allowed tab
                const allowedTabs: string[] = data.allowedTabs || [];
                const firstTab = BILLOPS_ADMIN_TAB_ORDER.find(t => allowedTabs.includes(t));
                setBillopsRedirect(firstTab ? BILLOPS_ADMIN_TAB_ROUTES[firstTab] : '/billops-admin-dashboard');
            })
            .catch(() => setBillopsRedirect('/billops-admin-dashboard'));
    }, [isLoading]);

    if (isLoading) {
        return (
            <div className="flex items-center justify-center h-screen">
                <div className="text-gray-600">Checking permissions...</div>
            </div>
        );
    }

    if ((hasAnyRole('ROLE_BILLOPS_ADMIN') || hasAnyRole('ROLE_BILLOPS_ADMIN_SUB')) && !hasAnyRole('ROLE_SUPER_ADMIN')) {
        if (!billopsRedirect) {
            // Profile fetch still in-flight — show spinner to avoid flash to wrong page
            return (
                <div className="flex items-center justify-center h-screen">
                    <div className="text-gray-600">Checking permissions...</div>
                </div>
            );
        }
        console.log('🚫 BILLOPS_ADMIN redirecting to:', billopsRedirect);
        return <Navigate to={billopsRedirect} replace />;
    }

    if (hasAnyRole('ROLE_BILLOPS') && !hasAnyRole('ROLE_XAMOPS') && !hasAnyRole('ROLE_SUPER_ADMIN')) {
        console.log('🚫 Restricted role detected, redirecting to BillOps dashboard');
        return <Navigate to="/billops-dashboard" replace />;
    }

    return <Outlet />;
};

// Authenticated Layout - Just handles the visual wrapper for protected routes
const AuthenticatedLayout: React.FC = () => {
    const location = useLocation();
    const mainContentRef = React.useRef<HTMLDivElement>(null);
    const { theme } = useTheme();

    // Scroll to top when route changes
    React.useEffect(() => {
        console.log('🔄 [App] Route changed to:', location.pathname);
        if (mainContentRef.current) {
            mainContentRef.current.scrollTo(0, 0);
        }
    }, [location.pathname]);

    // Debug theme
    console.log('🌗 [App] Render - theme:', theme);

    return (
        <ErrorBoundary>
            <div className={clsx(
                'flex h-screen overflow-hidden',
                theme === 'dark' ? 'bg-[#020617] text-slate-100' : 'bg-gray-50 text-gray-900'
            )}>
                <Sidebar />
                <div
                    id="main-content"
                    ref={mainContentRef}
                    className={clsx(
                        'flex-1 flex flex-col h-full overflow-y-auto relative scroll-smooth',
                        'lg:pl-72',
                        theme === 'dark' ? 'bg-[#020617]' : 'bg-gray-50'
                    )}
                    style={{ WebkitOverflowScrolling: 'touch' }}
                >
                    <Topbar />
                    <div className="flex-1 pt-6">
                        <Suspense fallback={
                            <div className="flex items-center justify-center h-full min-h-[60vh]">
                                <div className="flex flex-col items-center gap-3">
                                    <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
                                    <span className="text-sm text-gray-400">Loading...</span>
                                </div>
                            </div>
                        }>
                            <Outlet />
                        </Suspense>
                    </div>
                </div>
                {/* Global Command Palette — open with Cmd+K / Ctrl+K */}
                <CommandPalette />
                {/* Support Chat Widget - Visible on all authenticated pages for ROLE_XAMOPS users */}
                <SupportChatWidget />
                {/* Admin Support Notifications - Background listener for ROLE_BILLOPS_ADMIN */}
                <AdminSupportNotificationListener />
                {/* User Support Notifications - Background listener for ROLE_XAMOPS users */}
                <UserSupportNotificationListener />
            </div>
            <GooeyToaster position="bottom-right" />
        </ErrorBoundary>
    );
};

// Main App Content (needs to be inside Router to use hooks)
const AppContent: React.FC = () => {
    return (
        <ThemeProvider>
            <TopbarProvider>
                <Routes>
                    {/* Login Route - No Auth/Account providers */}
                    <Route path="/" element={<LoginPage />} />

                    {/* Protected Routes Wrapper */}
                    <Route
                        element={
                            <ProtectedRoute>
                                <AuthenticatedLayout />
                            </ProtectedRoute>
                        }
                    >
                        {/* Route Change Handler - Forces remounts */}
                        <Route element={<RouteChangeHandler />}>
                            <Route element={<DashboardGuard />}>
                                <Route path="/unified-dashboard" element={<UnifiedDashboard />} />
                                <Route path="/dashboard" element={<Dashboard />} />
                                <Route path="/gcp-dashboard" element={<GcpDashboard />} />
                                <Route path="/azure-dashboard" element={<AzureDashboard />} />
                            </Route>

                            {/* Core FinOps & CloudOps Routes */}
                            <Route path="/azure-finops" element={<AzureFinOpsPage />} />
                            <Route path="/finops" element={<FinOpsPage />} />
                            <Route path="/gcp-finops" element={<GcpFinOpsPage />} />
                            <Route path="/cost" element={<CostPage />} />
                            <Route path="/gcp-cost" element={<GcpCostPage />} />
                            <Route path="/azure-cost" element={<AzureCostPage />} />
                            <Route path="/waste" element={<WastePage />} />
                            <Route path="/gcp-waste" element={<GcpWastePage />} />
                            <Route path="/azure-waste" element={<AzureWastePage />} />
                            <Route path="/rightsizing" element={<RightsizingPage />} />
                            <Route path="/gcp-rightsizing" element={<GcpRightsizingPage />} />
                            <Route path="/azure-rightsizing" element={<AzureRightsizingPage />} />
                            <Route path="/reservation" element={<ReservationPage />} />
                            <Route path="/gcp-reservations" element={<GcpReservationsPage />} />
                            <Route path="/azure-reservations" element={<AzureReservationsPage />} />
                            <Route path="/cloudsitter" element={<CloudSitterPage />} />
                            <Route path="/spot-automation" element={<SpotAutomationPage />} />
                            <Route path="/autospotting" element={<SpotAutomationPage />} />
                            <Route path="/asg-detail" element={<ASGDetailPage />} />
                            <Route path="/asg-config" element={<ASGConfigPage />} />
                            <Route path="/flex-compute" element={<FlexComputePage />} />
                            <Route path="/ebs-rightsizing" element={<EbsRightsizingPage />} />
                            <Route path="/gcp-disk-rightsizing" element={<GcpDiskRightsizingPage />} />
                            <Route path="/azure-disk-rightsizing" element={<AzureDiskRightsizingPage />} />
                            <Route path="/grafana-dashboard" element={<GrafanaDashboardPage />} />
                            <Route path="/cloudk8s" element={<CloudK8sPage />} />
                            <Route path="/gcp-cloudk8s" element={<GcpCloudK8sPage />} />
                            <Route path="/azure-cloudk8s" element={<AzureCloudK8sPage />} />
                            <Route path="/eks-details" element={<EksDetailsPage />} />
                            <Route path="/gke-details" element={<GkeDetailsPage />} />
                            <Route path="/aks-details" element={<AksDetailsPage />} />
                            <Route path="/security" element={<SecurityPage />} />
                            <Route path="/gcp-security" element={<GcpSecurityPage />} />
                            <Route path="/azure-security" element={<AzureSecurityPage />} />
                            <Route path="/performance" element={<PerformancePage />} />
                            <Route path="/gcp-performance" element={<GcpPerformancePage />} />
                            <Route path="/azure-performance" element={<AzurePerformancePage />} />
                            <Route path="/alerts" element={<AlertsPage />} />
                            <Route path="/gcp-alerts" element={<GcpAlertsPage />} />
                            <Route path="/azure-alerts" element={<AzureAlertsPage />} />
                            <Route path="/tickets" element={<TicketsPage />} />
                            <Route path="/ticket-detail" element={<TicketDetailPage />} />
                            <Route path="/cloudlist" element={<CloudListPage />} />
                            <Route path="/resource-labels" element={<ResourceLabelsPage />} />
                            <Route path="/cloudshell" element={<CloudShellPage />} />
                            <Route path="/account-manager" element={<AccountManagerPage />} />
                            <Route path="/cloudmap" element={<CloudMapPage />} />
                            <Route path="/gcp-cloudmap" element={<GcpCloudMapPage />} />
                            <Route path="/add-account" element={<AddAccountPage />} />
                            <Route path="/add-gcp-account" element={<AddGcpAccountPage />} />
                            <Route path="/add-github-config" element={<GitHubConfigPage />} />
                            <Route path="/devops-in-the-box" element={<DevOpsInTheBoxPage />} />
                            <Route path="/cicd-pipelines" element={<CICDPipelinesPage />} />
                            <Route path="/sonarqube" element={<SonarQubePage />} />
                            <Route path="/aiops" element={<AIOpsPage />} />
                            <Route path="/dataops" element={<DataOpsPage />} />
                            <Route path="/gcp-dataops" element={<GcpDataOpsPage />} />
                            <Route path="/azure-dataops" element={<AzureDataOpsPage />} />
                            <Route path="/observability/*" element={<ObservabilityPage />} />
                            <Route path="/sre/investigations" element={<SreInvestigationsPage />} />
                            <Route path="/sre/investigations/:id" element={<SreInvestigationDetailPage />} />

                            {/* BillOps Routes */}
                            <Route path="/billops-dashboard" element={<BillOpsUserDashboardPage />} />
                            <Route path="/billops-billing" element={<BillingPage />} />
                            <Route path="/billops-invoices" element={<InvoicesPage />} />
                            <Route path="/billops-gcp-billing" element={<GcpBillingPage />} />
                            <Route path="/billops-client-details" element={<ClientDetailsPage />} />
                            <Route path="/billops-credits" element={<CreditsPage />} />
                            <Route path="/billops-tickets" element={<BillOpsTicketsPage />} />
                            <Route path="/billops-ticket-detail" element={<BillOpsTicketDetailPage />} />
                            <Route path="/billops-support-dashboard" element={<BillOpsSupportDashboardPage />} />
                            <Route path="/billops-admin-dashboard" element={<BillOpsAdminDashboardPage />} />
                            <Route path="/autospotting-admin" element={<AutoSpottingAdminDashboardPage />} />
                            <Route path="/billops-admin-invoices" element={<BillOpsAdminInvoicesPage />} />
                            <Route path="/billops-admin-invoice-detail" element={<BillOpsAdminInvoiceDetailPage />} />
                            <Route path="/billops-admin-credits" element={<BillOpsAdminCreditsPage />} />
                            <Route path="/billops-admin-tickets" element={<BillOpsAdminTicketsPage />} />
                            <Route path="/billops-agreements" element={<AgreementsPage />} />
                            <Route path="/billops-admin-agreements" element={<BillOpsAdminAgreementsPage />} />
                            <Route path="/billops-admin-historical-billing" element={<BillOpsAdminMarketplaceBillingPage />} />
                            <Route path="/marketplace-purchases" element={<MarketplacePurchasesPage />} />
                            <Route path="/workspace-licenses" element={<WorkspaceLicensesPage />} />
                            <Route path="/user-management" element={<UserManagementPage />} />
                            <Route path="/my-profile" element={<MyProfilePage />} />
                            <Route path="/complianceops" element={<ComplianceOpsPage />} />
                            <Route path="/billops-admin-user-manager" element={<BillOpsAdminUserManagerPage />} />
                            <Route path="/gcp-autospotting" element={<GcpSpotAutomationPage />} />
                            <Route path="/azure-autospotting" element={<AzureSpotAutomationPage />} />
                            <Route path="/calculator" element={<CalculatorPage />} />
                            <Route path="/gcp-cloudlist" element={<GcpCloudListPage />} />
                            <Route path="/azure-cloudlist" element={<AzureCloudList />} />
                        </Route> {/* Close RouteChangeHandler */}

                        {/* Catch all - redirect to login */}
                        <Route path="*" element={<Navigate to="/" replace />} />
                    </Route> {/* Close ProtectedRoute wrapper */}
                </Routes>
            </TopbarProvider>
        </ThemeProvider>
    );
};

const App: React.FC = () => {
    return (
        <QueryClientProvider client={queryClient}>
            <Router>
                <AuthProvider>
                    <AccountProvider>
                        <VPCProvider>
                            <EksDashboardCacheProvider>
                                <AutoSpottingCacheProvider>
                                    <AppContent />
                                </AutoSpottingCacheProvider>
                            </EksDashboardCacheProvider>
                        </VPCProvider>
                    </AccountProvider>
                </AuthProvider>
            </Router>
        </QueryClientProvider>
    );
};

export default App;