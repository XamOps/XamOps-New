import React, { useState, useRef, useEffect } from 'react';
import { useLocation, Link } from 'react-router-dom';
import { useTopbar } from '../../contexts/TopbarContext';
import { useAuth } from '../../contexts/AuthContext';
import { useAccounts } from '../../contexts/AccountContext';
import { useNotifications } from '../../hooks/useNotifications';
import { useBillOpsUserNotifications } from '../../hooks/useBillOpsUserNotifications';
import SuperAdminTopBar from './SuperAdminTopBar';
import LogoutModal from './LogoutModal';
import NotificationDropdown from './NotificationDropdown';
import BillOpsUserNotificationDropdown from '../BillOps/BillOpsUserNotificationDropdown';

const Topbar: React.FC = () => {
    const location = useLocation();
    const { actions, onHelpClick } = useTopbar();
    const { userProfile, logout, hasAnyRole } = useAuth();
    const { selectedAccountIds } = useAccounts();
    const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

    const [isProfileOpen, setIsProfileOpen] = useState(false);
    const [isLogoutModalOpen, setIsLogoutModalOpen] = useState(false);
    const [isNotificationOpen, setIsNotificationOpen] = useState(false);

    const dropdownRef = useRef<HTMLDivElement>(null);
    const notificationRef = useRef<HTMLDivElement>(null);

    // Check user role for notification system
    const isBillOpsAdmin = hasAnyRole('ROLE_BILLOPS_ADMIN') && !hasAnyRole('ROLE_XAMOPS') && !hasAnyRole('ROLE_SUPER_ADMIN');
    const isBillOpsUser = hasAnyRole('ROLE_BILLOPS') && !hasAnyRole('ROLE_XAMOPS') && !hasAnyRole('ROLE_SUPER_ADMIN') && !hasAnyRole('ROLE_BILLOPS_ADMIN');

    // Fetch XamOps notifications (only for XamOps users, not BillOps users)
    const { notifications: xamOpsNotifications, counts: xamOpsCounts, isLoading: xamOpsLoading } = useNotifications({
        selectedAccountId: (isBillOpsAdmin || isBillOpsUser) ? null : selectedAccountId,
    });

    // Fetch BillOps User notifications (only for BillOps users)
    const { notifications: billOpsUserNotifications, counts: billOpsUserCounts, isLoading: billOpsUserLoading } = useBillOpsUserNotifications(
        isBillOpsUser ? selectedAccountId : null
    );

    // Determine which notifications to show
    const notifications = isBillOpsUser ? billOpsUserNotifications : xamOpsNotifications;
    const counts = isBillOpsUser ? billOpsUserCounts : xamOpsCounts;
    const notificationsLoading = isBillOpsUser ? billOpsUserLoading : xamOpsLoading;

    // Close dropdown when clicking outside
    useEffect(() => {
        const handleClickOutside = (event: MouseEvent) => {
            if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
                setIsProfileOpen(false);
            }
            if (notificationRef.current && !notificationRef.current.contains(event.target as Node)) {
                setIsNotificationOpen(false);
            }
        };
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
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

    // Simple breadcrumb logic based on the path
    const getPageTitle = (path: string) => {
        if (path === '/' || path === '/unified-dashboard') return 'Unified Command Center';
        if (path === '/my-profile') return 'Account Settings';
        if (path === '/user-management') return 'User Management';

        const segments = path.split('/').filter(Boolean);
        if (segments.length === 0) return 'Unified Command Center';

        // Convert kebab-case or camelCase to Title Case
        const lastSegment = segments[segments.length - 1];
        return lastSegment
            .split(/[-_]/)
            .map(word => word.charAt(0).toUpperCase() + word.slice(1))
            .join(' ')
            .replace(/([A-Z])/g, ' $1')
            .trim();
    };

    const pageTitle = getPageTitle(location.pathname);
    const displayName = userProfile?.username || userProfile?.email?.split('@')[0] || 'User';

    return (
        <header className="sticky top-0 z-30 w-full">
            <div className="mx-auto flex h-20 items-center justify-between px-8 backdrop-blur-md bg-white/70 border-b border-gray-200/50 shadow-sm transition-all duration-300">
                <div className="flex items-center gap-4">
                    <div className="flex flex-col">
                        <h1 className="text-xl font-bold text-slate-800 tracking-tight">
                            {pageTitle}
                        </h1>
                        <div className="flex items-center text-xs font-medium text-slate-400 gap-2">
                            <span>XamOps</span>
                            <i className="fas fa-chevron-right text-[10px]"></i>
                            <span className="text-indigo-500/80">{pageTitle}</span>
                        </div>
                    </div>
                </div>

                {/* Super Admin Panel */}
                <div className="flex-1 flex justify-center px-4">
                    {hasAnyRole('ROLE_SUPER_ADMIN') && <SuperAdminTopBar />}
                </div>

                <div className="flex items-center gap-6">
                    {/* Dynamic Actions */}
                    <div className="flex items-center gap-4">
                        {actions}
                    </div>

                    <div className="flex items-center gap-3">
                        {/* Notifications - Show for XamOps and BillOps users, but not BillOps Admin (they have their own on dashboard) */}
                        {!isBillOpsAdmin && (
                            <div className="relative" ref={notificationRef}>
                                <button
                                    onClick={toggleNotifications}
                                    className="w-10 h-10 flex items-center justify-center rounded-xl bg-slate-100/50 text-slate-500 hover:bg-indigo-50 hover:text-indigo-600 transition-all relative"
                                >
                                    <i className="fas fa-bell"></i>
                                    {counts.total > 0 && (
                                        <span className="absolute top-2 right-2 w-2 h-2 bg-rose-500 rounded-full border-2 border-white"></span>
                                    )}
                                </button>

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

                        {onHelpClick && (
                            <button
                                onClick={onHelpClick}
                                className="w-10 h-10 flex items-center justify-center rounded-xl bg-slate-100/50 text-slate-500 hover:bg-indigo-50 hover:text-indigo-600 transition-all"
                                aria-label="Open help guide"
                            >
                                <i className="fas fa-question-circle"></i>
                            </button>
                        )}
                    </div>

                    <div className="h-8 w-[1px] bg-slate-200 mx-1"></div>

                    {/* User Profile Dropdown */}
                    <div className="relative" ref={dropdownRef}>
                        <button
                            onClick={toggleProfile}
                            className={`flex items-center gap-3 pl-2 p-1.5 rounded-2xl transition-all duration-200 ${isProfileOpen ? 'bg-indigo-50' : 'hover:bg-slate-50'}`}
                        >
                            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white shadow-lg shadow-indigo-500/20">
                                <i className="fas fa-user text-sm"></i>
                            </div>
                            <div className="hidden lg:flex flex-col items-start pr-2">
                                <span className="text-sm font-semibold text-slate-700">{displayName}</span>
                                <span className="text-[10px] font-medium text-slate-400 uppercase tracking-wider">Member</span>
                            </div>
                            <i className={`fas fa-chevron-down text-[10px] text-slate-400 transition-transform duration-200 ${isProfileOpen ? 'rotate-180' : ''}`}></i>
                        </button>

                        {/* Dropdown Menu */}
                        {isProfileOpen && (
                            <div className="absolute top-full right-0 mt-3 w-56 bg-white rounded-2xl shadow-2xl border border-slate-100 py-2 z-50 animate-in fade-in slide-in-from-top-2 duration-200">
                                <div className="px-4 py-3 border-b border-slate-50 mb-1">
                                    <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">Signed in as</p>
                                    <p className="text-sm font-semibold text-slate-700 truncate">{userProfile?.email || displayName}</p>
                                </div>

                                <Link
                                    to="/my-profile"
                                    onClick={() => setIsProfileOpen(false)}
                                    className="flex items-center gap-3 px-4 py-2.5 text-sm text-slate-600 hover:bg-indigo-50 hover:text-indigo-600 transition-colors"
                                >
                                    <div className="w-7 h-7 rounded-lg bg-slate-100 flex items-center justify-center group-hover:bg-white transition-colors">
                                        <i className="fas fa-user-circle text-xs"></i>
                                    </div>
                                    <span>My Profile</span>
                                </Link>

                                <div className="mx-2 my-1 border-t border-slate-50"></div>

                                <button
                                    onClick={handleLogoutClick}
                                    className="w-full flex items-center gap-3 px-4 py-2.5 text-sm text-rose-600 hover:bg-rose-50 transition-colors"
                                >
                                    <div className="w-7 h-7 rounded-lg bg-rose-100/50 flex items-center justify-center transition-colors">
                                        <i className="fas fa-sign-out-alt text-xs"></i>
                                    </div>
                                    <span>Sign Out</span>
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
