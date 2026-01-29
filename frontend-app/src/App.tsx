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
import { BrowserRouter as Router, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { AuthProvider } from './contexts/AuthContext';
import { AccountProvider } from './contexts/AccountContext';
import { Sidebar } from './components/Sidebar';
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
import GrafanaDashboardPage from './pages/GrafanaDashboardPage';
import CloudK8sPage from './pages/CloudK8sPage';
import EksDetailsPage from './pages/EksDetailsPage';
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
import AgreementsPage from './pages/AgreementsPage';
import MarketplacePurchasesPage from './pages/MarketplacePurchasesPage';
import WorkspaceLicensesPage from './pages/WorkspaceLicensesPage';
import UserManagementPage from './pages/UserManagementPage';
import MyProfilePage from './pages/MyProfilePage';
import ComplianceOpsPage from './pages/ComplianceOpsPage';


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
import './styles/credits.css';
import './styles/billopsTickets.css';
import './styles/billopsTicketDetail.css';
import './styles/agreements.css';
import './styles/marketplacePurchases.css';
import './styles/workspaceLicenses.css';
import './styles/userManagement.css';
import './styles/myProfile.css';

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

// Authenticated Layout - Just handles the visual wrapper for protected routes
const AuthenticatedLayout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  return (
    <div className="flex h-screen bg-gray-50 overflow-hidden">
      <Sidebar />
      <div className="flex-1 lg:pl-64 flex flex-col h-full overflow-y-auto">
        {children}
      </div>
    </div>
  );
};

// Main App Content (needs to be inside Router to use hooks)
const AppContent: React.FC = () => {
  return (
    <Routes>
      {/* Login Route - No Auth/Account providers */}
      <Route path="/" element={<LoginPage />} />

      {/* Protected Dashboard Route - With Auth/Account providers */}
      <Route
        path="/unified-dashboard"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <UnifiedDashboard />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Live Dashboard Route (converted from dashboard.html) */}
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <Dashboard />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* FinOps Report Route (converted from finops.html) */}
      <Route
        path="/finops"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <FinOpsPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Cost Management Route (converted from cost.html) */}
      <Route
        path="/cost"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <CostPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Waste Management Route (converted from waste.html) */}
      <Route
        path="/waste"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <WastePage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Rightsizing Route (converted from rightsizing.html) */}
      <Route
        path="/rightsizing"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <RightsizingPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Reservation Management Route (converted from reservation.html) */}
      <Route
        path="/reservation"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <ReservationPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* CloudSitter Route (converted from cloudsitter.html) */}
      <Route
        path="/cloudsitter"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <CloudSitterPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Spot Automation Route (converted from spot-automation.html) */}
      <Route
        path="/spot-automation"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <SpotAutomationPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Grafana Dashboard Route (converted from grafana-dashboard.html) */}
      <Route
        path="/grafana-dashboard"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <GrafanaDashboardPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* CloudK8s Route (converted from cloudk8s.html) */}
      <Route
        path="/cloudk8s"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <CloudK8sPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* EKS Details Route (converted from eks-details.html) */}
      <Route
        path="/eks-details"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <EksDetailsPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Security Center Route (converted from security.html) */}
      <Route
        path="/security"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <SecurityPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Performance Insights Route (converted from performance.html) */}
      <Route
        path="/performance"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <PerformancePage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Alerts Route (converted from alerts.html) */}
      <Route
        path="/alerts"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <AlertsPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Support Tickets Route (converted from xamops_tickets.html) */}
      <Route
        path="/tickets"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <TicketsPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Ticket Detail Route (converted from xamops_ticket_detail.html) */}
      <Route
        path="/ticket-detail"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <TicketDetailPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* CloudList Route (converted from cloudlist.html) */}
      <Route
        path="/cloudlist"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <CloudListPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* CloudShell Route (converted from cloudshell.html) */}
      <Route
        path="/cloudshell"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <CloudShellPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Account Manager Route (converted from account-manager.html) */}
      <Route
        path="/account-manager"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <AccountManagerPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Add Account Route (converted from add-account.html) */}
      <Route
        path="/add-account"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <AddAccountPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Add GCP Account Route (converted from add-gcp-account.html) */}
      <Route
        path="/add-gcp-account"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <AddGcpAccountPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* GitHub Config Route (converted from add-github-config.html) */}
      <Route
        path="/add-github-config"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <GitHubConfigPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* DevOps-in-the-Box Route (converted from devops_in_the_box.html) */}
      <Route
        path="/devops-in-the-box"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <DevOpsInTheBoxPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* CICD Pipelines Route (converted from cicd_pipelines.html) */}
      <Route
        path="/cicd-pipelines"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <CICDPipelinesPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* SonarQube Code Quality Route (converted from sonarqube.html) */}
      <Route
        path="/sonarqube"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <SonarQubePage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* AIOps Route (converted from aiops.html) */}
      <Route
        path="/aiops"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <AIOpsPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* DataOps Route (converted from dataops.html) */}
      <Route
        path="/dataops"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <DataOpsPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* BillOps User Dashboard Route (converted from billops/dashboard_user.html) */}
      <Route
        path="/billops-dashboard"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <BillOpsUserDashboardPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* BillOps Billing Route (converted from billops/billing.html) */}
      <Route
        path="/billops-billing"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <BillingPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* BillOps Invoices Route (converted from billops/invoices.html) */}
      <Route
        path="/billops-invoices"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <InvoicesPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* BillOps Credits Route (converted from billops/credits.html) */}
      <Route
        path="/billops-credits"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <CreditsPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* BillOps Tickets Route (converted from billops/tickets.html) */}
      <Route
        path="/billops-tickets"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <BillOpsTicketsPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* BillOps Ticket Detail Route (converted from billops/ticket_detail.html) */}
      <Route
        path="/billops-ticket-detail"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <BillOpsTicketDetailPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* BillOps Agreements Route (converted from billops/agreements.html) */}
      <Route
        path="/billops-agreements"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <AgreementsPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* MarketPlace Purchases Route (converted from billops/marketplace-purchases.html) */}
      <Route
        path="/marketplace-purchases"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <MarketplacePurchasesPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* WorkSpace Licenses Route (converted from billops/workspace-licenses.html) */}
      <Route
        path="/workspace-licenses"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <WorkspaceLicensesPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* User Management Route (converted from Xamops_User_management.html) */}
      <Route
        path="/user-management"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <UserManagementPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* My Profile Route (converted from my-profile.html) */}
      <Route
        path="/my-profile"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <MyProfilePage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* ComplianceOps Route (converted from complianceops.html) */}
      <Route
        path="/complianceops"
        element={
          <ProtectedRoute>
            <AuthenticatedLayout>
              <ComplianceOpsPage />
            </AuthenticatedLayout>
          </ProtectedRoute>
        }
      />

      {/* Catch all - redirect to login */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
};

const App: React.FC = () => {
  return (
    <Router>
      <AuthProvider>
        <AccountProvider>
          <AppContent />
        </AccountProvider>
      </AuthProvider>
    </Router>
  );
};

export default App;
