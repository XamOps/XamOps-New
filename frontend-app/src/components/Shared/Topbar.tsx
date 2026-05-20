import React, { useState, useRef, useEffect } from 'react';
import { useLocation, Link } from 'react-router-dom';
import clsx from 'clsx';
import { useTopbar } from '../../contexts/TopbarContext';
import { useAuth } from '../../contexts/AuthContext';
import { useAccounts } from '../../contexts/AccountContext';
import { useNotifications } from '../../hooks/useNotifications';
import { useBillOpsUserNotifications } from '../../hooks/useBillOpsUserNotifications';
import SuperAdminTopBar from './SuperAdminTopBar';
import LogoutModal from './LogoutModal';
import NotificationDropdown from './NotificationDropdown';
import BillOpsUserNotificationDropdown from '../BillOps/BillOpsUserNotificationDropdown';
import { useTheme } from '../../contexts/ThemeContext';
import { isDemoMode } from '../../demo/demoConfig';
import { TooltipWrapper } from '../ui';

const Topbar: React.FC = () => {
    useLocation(); // subscribe to route changes so Topbar re-renders on navigation
    const { title: contextTitle, subtitle: contextSubtitle, actions, onHelpClick } = useTopbar();
    const { userProfile, logout, hasAnyRole } = useAuth();
    const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
    const { theme, toggleTheme } = useTheme();
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
        provider: selectedAccountDisplay?.provider,
    });

    // Fetch BillOps User notifications (only for BillOps users)
    const { notifications: billOpsUserNotifications, counts: billOpsUserCounts, isLoading: billOpsUserLoading } = useBillOpsUserNotifications(
        isBillOpsUser ? selectedAccountId : null
    );

    // Determine which notifications to show
    const counts = isBillOpsUser ? billOpsUserCounts : xamOpsCounts;

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

    const openDemoTab = async (type: 'billops' | 'xamops') => {
        try {
            const res = await fetch('/api/billops/demo-token', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ type }),
            });
            if (!res.ok) return;
            const { token, destination } = await res.json();
            const url = `${window.location.origin}/${destination}#imp=${token}`;
            // Open via a programmatic <a> click — avoids window.open('', '_blank') which
            // clones the opener's sessionStorage into the new tab, contaminating the admin
            // tab with impersonation keys when the new tab writes back before navigating.
            const a = document.createElement('a');
            a.href = url;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
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

    const pageTitle = contextTitle || getPageTitle(currentPath);
    const displayName = isDemoMode()
        ? (userProfile?.username || 'User')
        : (userProfile?.email?.split('@')[0] || userProfile?.username || 'User');

    // Debug theme
    console.log('🌗 [Topbar] Render - theme:', theme);

    return (
        <header className="sticky top-0 z-40 w-full transition-colors duration-300">
            <div className={clsx(
                'mx-auto flex h-20 items-center justify-between px-8 border-b shadow-sm transition-all duration-300',
                theme === 'dark' ? 'bg-[#0a0f1a] border-slate-800 text-slate-100' : 'bg-white/90 backdrop-blur-md border-gray-200/50 text-slate-800'
            )}>
                <div className="flex items-center gap-4">
                    {pageTitle && (
                        <div className="flex flex-col min-w-0 overflow-hidden">
                            <h1 className={clsx(
                                'text-xl font-bold tracking-tight truncate',
                                theme === 'dark' ? 'text-slate-100' : 'text-slate-800'
                            )}>
                                {pageTitle}
                            </h1>
                            {contextSubtitle && (
                                <p className="text-xs text-slate-400 dark:text-slate-500 truncate mt-0.5">
                                    {contextSubtitle}
                                </p>
                            )}
                        </div>
                    )}
                </div>

                {/* Super Admin Panel */}
                <div className="flex-1 flex justify-center px-4">
                    {hasAnyRole('ROLE_SUPER_ADMIN') && <SuperAdminTopBar />}
                </div>

                <div className="flex items-center gap-6">
                    {/* Dynamic Actions */}
                    <div className="flex items-center gap-4 min-w-0">
                        {actions}
                    </div>

                    <div className="flex items-center gap-3">
                        {/* Notifications - Show for XamOps and BillOps users, but not BillOps Admin (they have their own on dashboard) */}
                        {!isBillOpsAdmin && (
                            <div className="relative" ref={notificationRef}>
                                <TooltipWrapper tip="Notifications">
                                    <button
                                        onClick={toggleNotifications}
                                        aria-label="Notifications"
                                        className={clsx(
                                            'w-10 h-10 flex items-center justify-center rounded-xl transition-all relative focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-slate-900',
                                            theme === 'dark' ? 'bg-slate-800 text-slate-400 hover:bg-slate-700 hover:text-indigo-400' : 'bg-slate-100/50 text-slate-500 hover:bg-indigo-50 hover:text-indigo-600'
                                        )}
                                    >
                                        <i className="fas fa-bell"></i>
                                        {counts.total > 0 && (
                                            <span className={clsx(
                                                'absolute top-2 right-2 w-2 h-2 bg-rose-500 rounded-full border-2',
                                                theme === 'dark' ? 'border-slate-800' : 'border-white'
                                            )}></span>
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

                        {onHelpClick && (
                            <TooltipWrapper tip="Open help guide">
                                <button
                                    onClick={onHelpClick}
                                    className={clsx(
                                        'w-10 h-10 flex items-center justify-center rounded-xl transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-slate-900',
                                        theme === 'dark' ? 'bg-slate-800 text-slate-400 hover:bg-slate-700 hover:text-indigo-400' : 'bg-slate-100/50 text-slate-500 hover:bg-indigo-50 hover:text-indigo-600'
                                    )}
                                    aria-label="Open help guide"
                                >
                                    <i className="fas fa-question-circle"></i>
                                </button>
                            </TooltipWrapper>
                        )}
                    </div>

                    {/* Demo shortcuts — only for BillOps Admin, not in demo mode itself */}
                    {isBillOpsAdmin && !isDemoMode() && (
                        <div className="flex items-center gap-1.5">
                            <button
                                onClick={() => openDemoTab('xamops')}
                                title="Open XamOps Demo"
                                className={clsx(
                                    'flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-semibold transition-all border',
                                    theme === 'dark'
                                        ? 'bg-indigo-900/40 border-indigo-700/50 text-indigo-300 hover:bg-indigo-800/60 hover:text-indigo-200'
                                        : 'bg-indigo-50 border-indigo-200 text-indigo-600 hover:bg-indigo-100'
                                )}
                            >
                                <i className="fas fa-cloud text-[10px]"></i>
                                XamOps
                            </button>
                            <button
                                onClick={() => openDemoTab('billops')}
                                title="Open BillOps Demo"
                                className={clsx(
                                    'flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-semibold transition-all border',
                                    theme === 'dark'
                                        ? 'bg-purple-900/40 border-purple-700/50 text-purple-300 hover:bg-purple-800/60 hover:text-purple-200'
                                        : 'bg-purple-50 border-purple-200 text-purple-600 hover:bg-purple-100'
                                )}
                            >
                                <i className="fas fa-file-invoice-dollar text-[10px]"></i>
                                BillOps
                            </button>
                        </div>
                    )}

                    <div className={clsx(
                        'h-8 w-[1px] mx-1',
                        theme === 'dark' ? 'bg-slate-800' : 'bg-slate-200'
                    )}></div>

                    {/* User Profile Dropdown */}
                    <div className="relative" ref={dropdownRef}>
                        <button
                            onClick={toggleProfile}
                            className={clsx(
                                'flex items-center gap-3 pl-2 p-1.5 rounded-2xl transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-slate-900',
                                isProfileOpen ? (theme === 'dark' ? 'bg-slate-800' : 'bg-indigo-50') : (theme === 'dark' ? 'hover:bg-slate-800/50' : 'hover:bg-slate-50')
                            )}
                        >
                            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white shadow-lg shadow-indigo-500/20">
                                <i className="fas fa-user text-sm"></i>
                            </div>
                            <div className="hidden lg:flex flex-col items-start pr-2">
                                <span className={clsx(
                                    'text-sm font-semibold',
                                    theme === 'dark' ? 'text-slate-200' : 'text-slate-700'
                                )}>{displayName}</span>
                                <span className="text-[10px] font-medium text-slate-400 uppercase tracking-wider">Member</span>
                            </div>
                            <i className={`fas fa-chevron-down text-[10px] text-slate-400 transition-transform duration-200 ${isProfileOpen ? 'rotate-180' : ''}`}></i>
                        </button>

                        {/* Dropdown Menu */}
                        {isProfileOpen && (
                            <div className={clsx(
                                'absolute top-full right-0 mt-3 w-56 rounded-2xl shadow-2xl border py-2 z-50 animate-in fade-in slide-in-from-top-2 duration-200',
                                theme === 'dark' ? 'bg-[#0f172a] border-slate-800 text-slate-300' : 'bg-white border-slate-100 text-slate-600'
                            )}>
                                <div className={clsx(
                                    'px-4 py-3 border-b mb-1',
                                    theme === 'dark' ? 'border-slate-800' : 'border-slate-50'
                                )}>
                                    <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">Signed in as</p>
                                    <p className={clsx(
                                        'text-sm font-semibold truncate',
                                        theme === 'dark' ? 'text-slate-200' : 'text-slate-700'
                                    )}>{userProfile?.email || displayName}</p>
                                </div>

                                <Link
                                    to="/my-profile"
                                    onClick={() => setIsProfileOpen(false)}
                                    className={clsx(
                                        'flex items-center gap-3 px-4 py-2.5 text-sm transition-colors',
                                        theme === 'dark' ? 'hover:bg-slate-800 hover:text-indigo-400' : 'hover:bg-indigo-50 hover:text-indigo-600'
                                    )}
                                >
                                    <div className={clsx(
                                        'w-7 h-7 rounded-lg flex items-center justify-center transition-colors',
                                        theme === 'dark' ? 'bg-slate-800 group-hover:bg-slate-700' : 'bg-slate-100 group-hover:bg-white'
                                    )}>
                                        <i className="fas fa-user-circle text-xs"></i>
                                    </div>
                                    <span>My Profile</span>
                                </Link>

                                {/* Theme Toggle Switch */}
                                <button
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        toggleTheme();
                                    }}
                                    role="switch"
                                    aria-checked={theme === 'dark'}
                                    className={clsx(
                                        'w-full flex items-center justify-between px-4 py-2.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-inset',
                                        theme === 'dark' ? 'hover:bg-slate-800 hover:text-indigo-400' : 'hover:bg-indigo-50 hover:text-indigo-600'
                                    )}
                                >
                                    <div className="flex items-center gap-3">
                                        <div className={clsx(
                                            'w-7 h-7 rounded-lg flex items-center justify-center transition-colors',
                                            theme === 'dark' ? 'bg-slate-800 text-yellow-400' : 'bg-slate-100 text-slate-500'
                                        )}>
                                            <i className={clsx('fas', theme === 'dark' ? 'fa-moon' : 'fa-sun', 'text-xs')}></i>
                                        </div>
                                        <span>Dark Mode</span>
                                    </div>
                                    <div className={clsx(
                                        'w-8 h-4 rounded-full relative transition-colors duration-200',
                                        theme === 'dark' ? 'bg-indigo-600' : 'bg-gray-300'
                                    )}>
                                        <div className={clsx(
                                            'absolute top-0.5 left-0.5 w-3 h-3 bg-white rounded-full transition-transform duration-200',
                                            theme === 'dark' ? 'translate-x-4' : 'translate-x-0'
                                        )}></div>
                                    </div>
                                </button>

                                <div className={clsx(
                                    'mx-2 my-1 border-t',
                                    theme === 'dark' ? 'border-slate-800' : 'border-slate-50'
                                )}></div>

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
