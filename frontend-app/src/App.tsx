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
      <div className="flex-1 lg:pl-64 flex flex-col h-full overflow-hidden">
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
