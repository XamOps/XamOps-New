import React from 'react';
import { useLocation } from 'react-router-dom';
import { useTopbar } from '../../contexts/TopbarContext';

const Topbar: React.FC = () => {
    const location = useLocation();
    const { actions } = useTopbar();

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

                <div className="flex items-center gap-6">
                    {/* Dynamic Actions */}
                    <div className="flex items-center gap-4">
                        {actions}
                    </div>

                    <div className="flex items-center gap-3">
                        <button className="w-10 h-10 flex items-center justify-center rounded-xl bg-slate-100/50 text-slate-500 hover:bg-indigo-50 hover:text-indigo-600 transition-all relative">
                            <i className="fas fa-bell"></i>
                            <span className="absolute top-2 right-2 w-2 h-2 bg-rose-500 rounded-full border-2 border-white"></span>
                        </button>
                        <button className="w-10 h-10 flex items-center justify-center rounded-xl bg-slate-100/50 text-slate-500 hover:bg-indigo-50 hover:text-indigo-600 transition-all">
                            <i className="fas fa-question-circle"></i>
                        </button>
                    </div>

                    <div className="h-8 w-[1px] bg-slate-200 mx-1"></div>

                    {/* User Quick Info */}
                    <div className="flex items-center gap-3 pl-2">
                        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white shadow-lg shadow-indigo-500/20">
                            <i className="fas fa-user text-sm"></i>
                        </div>
                    </div>
                </div>
            </div>
        </header>
    );
};

export default Topbar;
