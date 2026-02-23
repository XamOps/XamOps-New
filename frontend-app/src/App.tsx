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

import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, useLocation, Outlet } from 'react-router-dom';
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
import LoginPage from './pages/LoginPage';
import { UnifiedDashboard } from './pages/UnifiedDashboard';
import Dashboard from './pages/Dashboard';
import FinOpsPage from './pages/FinOpsPage';
import CostPage from './pages/CostPage';
import WastePage from './pages/WastePage';
import RightsizingPage from './pages/RightsizingPage';
import ReservationPage from './pages/ReservationPage';
import CloudSitterPage from './pages/CloudSitterPage';
import SpotAutomationPage from './pages/SpotAutomationPage';
import ASGDetailPage from './pages/ASGDetailPage';
import ASGConfigPage from './pages/ASGConfigPage';
import GrafanaDashboardPage from './pages/GrafanaDashboardPage';
import CloudK8sPage from './pages/CloudK8sPage';
import EksDetailsPage from './pages/EksDetailsPage';
import GkeDetailsPage from './pages/GkeDetailsPage';
import AksDetailsPage from './pages/AksDetailsPage';
import SecurityPage from './pages/SecurityPage';
import PerformancePage from './pages/PerformancePage';
import AlertsPage from './pages/AlertsPage';
import TicketsPage from './pages/TicketsPage';
import TicketDetailPage from './pages/TicketDetailPage';
import CloudListPage from './pages/CloudListPage';
import CloudShellPage from './pages/CloudShellPage';
import AccountManagerPage from './pages/AccountManagerPage';
import AddAccountPage from './pages/AddAccountPage';
import AddGcpAccountPage from './pages/AddGcpAccountPage';
import GitHubConfigPage from './pages/GitHubConfigPage';
import DevOpsInTheBoxPage from './pages/DevOpsInTheBoxPage';
import CICDPipelinesPage from './pages/CICDPipelinesPage';
import SonarQubePage from './pages/SonarQubePage';
import AIOpsPage from './pages/AIOpsPage';
import DataOpsPage from './pages/DataOpsPage';
import BillOpsUserDashboardPage from './pages/BillOpsUserDashboardPage';
import BillingPage from './pages/BillingPage';
import InvoicesPage from './pages/InvoicesPage';
import CreditsPage from './pages/CreditsPage';
import BillOpsTicketsPage from './pages/BillOpsTicketsPage';
import BillOpsTicketDetailPage from './pages/BillOpsTicketDetailPage';
import BillOpsSupportDashboardPage from './pages/BillOpsSupportDashboardPage';
import BillOpsAdminDashboardPage from './pages/BillOpsAdminDashboardPage';
import AutoSpottingAdminDashboardPage from './pages/AutoSpottingAdminDashboardPage';
import BillOpsAdminInvoicesPage from './pages/BillOpsAdminInvoicesPage';
import BillOpsAdminCreditsPage from './pages/BillOpsAdminCreditsPage';
import BillOpsAdminTicketsPage from './pages/BillOpsAdminTicketsPage';
import AgreementsPage from './pages/AgreementsPage';
import BillOpsAdminAgreementsPage from './pages/BillOpsAdminAgreementsPage';
import MarketplacePurchasesPage from './pages/MarketplacePurchasesPage';
import WorkspaceLicensesPage from './pages/WorkspaceLicensesPage';
import UserManagementPage from './pages/UserManagementPage';
import MyProfilePage from './pages/MyProfilePage';
import ComplianceOpsPage from './pages/ComplianceOpsPage';
import BillOpsAdminUserManagerPage from './pages/BillOpsAdminUserManagerPage';
import ClientDetailsPage from './pages/ClientDetailsPage';
import GcpDashboard from './pages/GcpDashboard';
import GcpFinOpsPage from './pages/GcpFinOpsPage';
import GcpCostPage from './pages/GcpCostPage';
import GcpWastePage from './pages/GcpWastePage';
import GcpRightsizingPage from './pages/GcpRightsizingPage';
import AzureRightsizingPage from './pages/AzureRightsizingPage';
import AzureWastePage from './pages/AzureWastePage';
import GcpReservationsPage from './pages/GcpReservationsPage';
import AzureReservationsPage from './pages/AzureReservationsPage';
import GcpCloudK8sPage from './pages/GcpCloudK8sPage';
import GcpSecurityPage from './pages/GcpSecurityPage';
import AzureSecurityPage from './pages/AzureSecurityPage';
import GcpPerformancePage from './pages/GcpPerformancePage';
import AzurePerformancePage from './pages/AzurePerformancePage';
import GcpAlertsPage from './pages/GcpAlertsPage';
import AzureAlertsPage from './pages/AzureAlertsPage';
import GcpBillingPage from './pages/GcpBillingPage';
import GcpSpotAutomationPage from './pages/GcpSpotAutomationPage';
import CalculatorPage from './pages/CalculatorPage';
import GcpCloudListPage from './pages/GcpCloudListPage';
import CloudMapPage from './pages/CloudMapPage';
import GcpCloudMapPage from './pages/GcpCloudMapPage';
import AzureDashboard from './pages/AzureDashboard';
import AzureCloudList from './pages/AzureCloudList';
import AzureFinOpsPage from './pages/AzureFinOpsPage';
import AzureCostPage from './pages/AzureCostPage';
import FlexComputePage from './pages/FlexComputePage';
import AzureCloudK8sPage from './pages/AzureCloudK8sPage';
import { SupportChatWidget } from './components/SupportChat';
import AdminSupportNotificationListener from './components/Shared/AdminSupportNotificationListener';
import UserSupportNotificationListener from './components/Shared/UserSupportNotificationListener';
import ErrorBoundary from './components/Shared/ErrorBoundary';
import RouteChangeHandler from './components/Shared/RouteChangeHandler';


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

/**
 * Dashboard Guard - Redirects ROLE_BILLOPS (non-XAMOPS) users to BillOps dashboard
 * whenever they try to access standard dashboards.
 */
const DashboardGuard: React.FC = () => {
  const { hasAnyRole, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-screen">
        <div className="text-gray-600">Checking permissions...</div>
      </div>
    );
  }

  if (hasAnyRole('ROLE_BILLOPS_ADMIN') && !hasAnyRole('ROLE_SUPER_ADMIN')) {
    console.log('🚫 Restricted Admin role detected, redirecting to BillOps Admin dashboard');
    return <Navigate to="/billops-admin-dashboard" replace />;
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
            <Outlet />
          </div>
        </div>
        {/* Support Chat Widget - Visible on all authenticated pages for ROLE_XAMOPS users */}
        <SupportChatWidget />
        {/* Admin Support Notifications - Background listener for ROLE_BILLOPS_ADMIN */}
        <AdminSupportNotificationListener />
        {/* User Support Notifications - Background listener for ROLE_XAMOPS users */}
        <UserSupportNotificationListener />
      </div>
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
              <Route path="/billops-admin-credits" element={<BillOpsAdminCreditsPage />} />
              <Route path="/billops-admin-tickets" element={<BillOpsAdminTicketsPage />} />
              <Route path="/billops-agreements" element={<AgreementsPage />} />
              <Route path="/billops-admin-agreements" element={<BillOpsAdminAgreementsPage />} />
              <Route path="/marketplace-purchases" element={<MarketplacePurchasesPage />} />
              <Route path="/workspace-licenses" element={<WorkspaceLicensesPage />} />
              <Route path="/user-management" element={<UserManagementPage />} />
              <Route path="/my-profile" element={<MyProfilePage />} />
              <Route path="/complianceops" element={<ComplianceOpsPage />} />
              <Route path="/billops-admin-user-manager" element={<BillOpsAdminUserManagerPage />} />
              <Route path="/gcp-autospotting" element={<GcpSpotAutomationPage />} />
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
  );
};

export default App;
