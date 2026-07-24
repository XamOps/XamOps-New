import React, { useState, useRef, useEffect } from "react";
import { useLocation, Link } from "react-router-dom";
import clsx from "clsx";
import { useTopbar } from "../../contexts/TopbarContext";
import { useAuth } from "../../contexts/AuthContext";
import { useAccounts } from "../../contexts/AccountContext";
import { useNotifications } from "../../hooks/useNotifications";
import { useBillOpsUserNotifications } from "../../hooks/useBillOpsUserNotifications";
import SuperAdminTopBar from "./SuperAdminTopBar";
import LogoutModal from "./LogoutModal";
import NotificationDropdown from "./NotificationDropdown";
import BillOpsUserNotificationDropdown from "../BillOps/BillOpsUserNotificationDropdown";
import { useTheme } from "../../contexts/ThemeContext";
import { isDemoMode } from "../../demo/demoConfig";
import { TooltipWrapper } from "../ui";

const Topbar: React.FC = () => {
  useLocation(); // subscribe to route changes so Topbar re-renders on navigation
  const {
    title: contextTitle,
    subtitle: contextSubtitle,
    actions,
    onHelpClick,
  } = useTopbar();
  const { userProfile, logout, hasAnyRole } = useAuth();
  const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
  const { theme } = useTheme();
  const selectedAccountId =
    selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const [isProfileOpen, setIsProfileOpen] = useState(false);
  const [isLogoutModalOpen, setIsLogoutModalOpen] = useState(false);
  const [isNotificationOpen, setIsNotificationOpen] = useState(false);

  const dropdownRef = useRef<HTMLDivElement>(null);
  const notificationRef = useRef<HTMLDivElement>(null);

  // Check user role for notification system
  const isBillOpsAdmin =
    hasAnyRole("ROLE_BILLOPS_ADMIN") &&
    !hasAnyRole("ROLE_XAMOPS") &&
    !hasAnyRole("ROLE_SUPER_ADMIN");
  const isBillOpsUser =
    hasAnyRole("ROLE_BILLOPS") &&
    !hasAnyRole("ROLE_XAMOPS") &&
    !hasAnyRole("ROLE_SUPER_ADMIN") &&
    !hasAnyRole("ROLE_BILLOPS_ADMIN");

  // Fetch XamOps notifications (only for XamOps users, not BillOps users)
  const {
    notifications: xamOpsNotifications,
    counts: xamOpsCounts,
    isLoading: xamOpsLoading,
  } = useNotifications({
    selectedAccountId:
      isBillOpsAdmin || isBillOpsUser ? null : selectedAccountId,
    provider: selectedAccountDisplay?.provider,
  });

  // Fetch BillOps User notifications (only for BillOps users)
  const {
    notifications: billOpsUserNotifications,
    counts: billOpsUserCounts,
    isLoading: billOpsUserLoading,
  } = useBillOpsUserNotifications(isBillOpsUser ? selectedAccountId : null);

  // Determine which notifications to show
  const counts = isBillOpsUser ? billOpsUserCounts : xamOpsCounts;

  // Close dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(event.target as Node)
      ) {
        setIsProfileOpen(false);
      }
      if (
        notificationRef.current &&
        !notificationRef.current.contains(event.target as Node)
      ) {
        setIsNotificationOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const toggleProfile = () => setIsProfileOpen(!isProfileOpen);
  const toggleNotifications = () => setIsNotificationOpen(!isNotificationOpen);

  const handleLogoutClick = () => {
    setIsProfileOpen(false);
    setIsLogoutModalOpen(true);
  };

  const confirmLogout = async () => {
    await logout();
    setIsLogoutModalOpen(false);
  };

  const openDemoTab = async (type: "billops" | "xamops") => {
    try {
      const res = await fetch("/api/billops/demo-token", {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ type }),
      });
      if (!res.ok) return;
      const { token, destination } = await res.json();
      const url = `${window.location.origin}/${destination}#imp=${token}`;
      // Open via a programmatic <a> click — avoids window.open('', '_blank') which
      // clones the opener's sessionStorage into the new tab, contaminating the admin
      // tab with impersonation keys when the new tab writes back before navigating.
      const a = document.createElement("a");
      a.href = url;
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
    } catch {
      // silently ignore
    }
  };

  // Use window.location.pathname as source of truth — React 19 concurrent
  // rendering can defer re-renders of stable layout components like Topbar,
  // causing useLocation() to return a stale pathname while the URL has already
  // changed. window.location.pathname always reflects the current browser URL.
  const currentPath = window.location.pathname;

  const getPageTitle = (path: string) => {
    if (path === "/" || path === "/unified-dashboard")
      return "Unified Command Center";
    if (path === "/my-profile") return "Account Settings";
    if (path === "/user-management") return "User Management";
    if (path === "/dashboard") return "Dashboard";
    if (path === "/gcp-dashboard") return "GCP Dashboard";
    if (path === "/azure-dashboard") return "Azure Dashboard";
    if (path === "/cost") return "Cost Management";
    if (path === "/gcp-cost") return "GCP Cost Management";
    if (path === "/azure-cost") return "Azure Cost Management";

    const segments = path.split("/").filter(Boolean);
    if (segments.length === 0) return "Unified Command Center";

    // Convert kebab-case or camelCase to Title Case
    const lastSegment = segments[segments.length - 1];
    return lastSegment
      .split(/[-_]/)
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(" ")
      .replace(/([A-Z])/g, " $1")
      .trim();
  };

  const pageTitle = contextTitle || getPageTitle(currentPath);
  const displayName = isDemoMode()
    ? userProfile?.username || "User"
    : userProfile?.email?.split("@")[0] || userProfile?.username || "User";

  // First letter of display name for the avatar initial
  const avatarInitial = displayName.charAt(0).toUpperCase();

  return (
    <header
      className={`sticky top-0 z-40 w-full transition-colors duration-200 ${theme === "dark" ? "bg-[#1f1f1f]" : "bg-white"}`}
    >
      <div className="flex h-12 items-center justify-between px-6">
        {/* Left — page title */}
        <div className="flex items-center gap-4 min-w-0">
          {pageTitle && (
            <div className="flex flex-col min-w-0">
              <h1
                className="text-[24px] font-semibold leading-8 truncate text-nowrap transition-colors duration-200"
                style={{
                  fontFamily: "'Barlow Condensed', 'Inter', sans-serif",
                  letterSpacing: "0px",
                  color:
                    theme === "dark" ? "rgb(255,255,255)" : "rgb(17,24,39)",
                  fontStretch: "100%",
                }}
              >
                {pageTitle}
              </h1>
              {contextSubtitle && (
                <p
                  className={`text-[11px] truncate leading-tight transition-colors duration-200 ${theme === "dark" ? "text-[rgba(255,255,255,0.4)]" : "text-[rgba(0,0,0,0.4)]"}`}
                >
                  {contextSubtitle}
                </p>
              )}
            </div>
          )}
        </div>

        {/* Centre — super admin panel */}
        <div className="flex-1 flex justify-center px-4">
          {hasAnyRole("ROLE_SUPER_ADMIN") && <SuperAdminTopBar />}
        </div>

        {/* Right — actions + notifications + profile */}
        <div className="flex items-center gap-2">
          {/* Dynamic page actions */}
          {actions && (
            <div className="flex items-center gap-2 mr-1">{actions}</div>
          )}

          {/* Demo shortcuts — BillOps Admin only */}
          {isBillOpsAdmin && !isDemoMode() && (
            <div className="flex items-center gap-1 mr-1">
              <button
                onClick={() => openDemoTab("xamops")}
                title="Open XamOps Demo"
                className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[12px] font-medium border transition-colors ${theme === "dark" ? "border-[rgba(255,255,255,0.12)] text-[rgba(255,255,255,0.6)] hover:bg-[rgba(255,255,255,0.08)]" : "border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)]"}`}
              >
                <i className="fas fa-cloud text-[10px]"></i>
                XamOps
              </button>
              <button
                onClick={() => openDemoTab("billops")}
                title="Open BillOps Demo"
                className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[12px] font-medium border transition-colors ${theme === "dark" ? "border-[rgba(255,255,255,0.12)] text-[rgba(255,255,255,0.6)] hover:bg-[rgba(255,255,255,0.08)]" : "border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)]"}`}
              >
                <i className="fas fa-file-invoice-dollar text-[10px]"></i>
                BillOps
              </button>
            </div>
          )}

          {/* Help button */}
          {onHelpClick && (
            <button
              onClick={onHelpClick}
              className={`w-8 h-8 flex items-center justify-center rounded-md transition-colors ${theme === "dark" ? "text-[rgba(255,255,255,0.4)] hover:bg-[rgba(255,255,255,0.08)] hover:text-[rgba(255,255,255,0.8)]" : "text-[rgba(0,0,0,0.4)] hover:bg-[rgba(0,0,0,0.06)] hover:text-[rgba(0,0,0,0.7)]"}`}
              aria-label="Open help guide"
            >
              <i className="fas fa-question-circle text-[13px]"></i>
            </button>
          )}

          {/* Notifications */}
          {!isBillOpsAdmin && (
            <div className="relative" ref={notificationRef}>
              <TooltipWrapper tip="Notifications" placement="bottom">
                <button
                  onClick={toggleNotifications}
                  className={`w-8 h-8 flex items-center justify-center rounded-md transition-colors relative ${theme === "dark" ? "text-[rgba(255,255,255,0.4)] hover:bg-[rgba(255,255,255,0.08)] hover:text-[rgba(255,255,255,0.8)]" : "text-[rgba(0,0,0,0.4)] hover:bg-[rgba(0,0,0,0.06)] hover:text-[rgba(0,0,0,0.7)]"}`}
                  aria-label="Notifications"
                  aria-expanded={isNotificationOpen}
                >
                  <i className="fas fa-bell text-[13px]"></i>
                  {counts.total > 0 && (
                    <span
                      className={`absolute top-1.5 right-1.5 w-1.5 h-1.5 bg-rose-500 rounded-full ${theme === "dark" ? "border border-[#1f1f1f]" : "border border-white"}`}
                    ></span>
                  )}
                </button>
              </TooltipWrapper>

              {isNotificationOpen && (
                <>
                  {isBillOpsUser ? (
                    <BillOpsUserNotificationDropdown
                      notifications={billOpsUserNotifications}
                      counts={billOpsUserCounts}
                      isLoading={billOpsUserLoading}
                      onClose={() => setIsNotificationOpen(false)}
                    />
                  ) : (
                    <NotificationDropdown
                      notifications={xamOpsNotifications}
                      counts={xamOpsCounts}
                      isLoading={xamOpsLoading}
                      onClose={() => setIsNotificationOpen(false)}
                    />
                  )}
                </>
              )}
            </div>
          )}

          {/* Divider */}
          <div
            className={`w-px h-5 mx-1 ${theme === "dark" ? "bg-[rgba(255,255,255,0.12)]" : "bg-[rgba(0,0,0,0.08)]"}`}
          ></div>

          {/* User profile */}
          <div className="relative" ref={dropdownRef}>
            <button
              onClick={toggleProfile}
              className={clsx(
                "flex items-center gap-2 px-2 py-1 rounded-md transition-colors duration-150",
                theme === "dark"
                  ? isProfileOpen
                    ? "bg-[rgba(255,255,255,0.1)]"
                    : "hover:bg-[rgba(255,255,255,0.08)]"
                  : isProfileOpen
                    ? "bg-[rgba(0,0,0,0.06)]"
                    : "hover:bg-[rgba(0,0,0,0.06)]",
              )}
              aria-label="User profile menu"
              aria-expanded={isProfileOpen}
            >
              {/* Letter avatar */}
              <div
                className={`w-7 h-7 rounded-full flex items-center justify-center text-white text-[11px] font-semibold flex-shrink-0 ${theme === "dark" ? "bg-[rgba(255,255,255,0.15)]" : "bg-[rgba(0,0,0,0.88)]"}`}
              >
                {avatarInitial}
              </div>
              <span
                className={`hidden lg:block text-[13px] font-medium max-w-[120px] truncate ${theme === "dark" ? "text-[rgba(255,255,255,0.7)]" : "text-[rgba(0,0,0,0.7)]"}`}
              >
                {displayName}
              </span>
              <i
                className={`fas fa-chevron-down text-[9px] transition-transform duration-150 ${theme === "dark" ? "text-[rgba(255,255,255,0.3)]" : "text-[rgba(0,0,0,0.3)]"} ${isProfileOpen ? "rotate-180" : ""}`}
              ></i>
            </button>

            {/* Dropdown */}
            {isProfileOpen && (
              <div className="absolute top-full right-0 mt-1.5 w-52 rounded-xl border border-[rgba(0,0,0,0.06)] bg-white shadow-lg py-1 z-50">
                {/* User info */}
                <div className="px-3 py-2.5 border-b border-[rgba(0,0,0,0.06)]">
                  <p className="text-[10px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-widest mb-0.5">
                    Signed in as
                  </p>
                  <p className="text-[13px] font-medium text-[rgba(0,0,0,0.88)] truncate">
                    {userProfile?.email || displayName}
                  </p>
                </div>

                <Link
                  to="/my-profile"
                  onClick={() => setIsProfileOpen(false)}
                  className="flex items-center gap-2.5 px-3 py-2 text-[13px] text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
                >
                  <i className="fas fa-user-circle w-4 text-center text-[rgba(0,0,0,0.35)] text-[12px]"></i>
                  My Profile
                </Link>

                <div className="mx-2 my-0.5 border-t border-[rgba(0,0,0,0.05)]"></div>

                <button
                  onClick={handleLogoutClick}
                  className="w-full flex items-center gap-2.5 px-3 py-2 text-[13px] text-rose-600 hover:bg-rose-50 transition-colors"
                >
                  <i className="fas fa-sign-out-alt w-4 text-center text-[12px]"></i>
                  Sign Out
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      <LogoutModal
        isOpen={isLogoutModalOpen}
        onClose={() => setIsLogoutModalOpen(false)}
        onConfirm={confirmLogout}
        displayName={displayName}
      />
    </header>
  );
};

export default Topbar;
