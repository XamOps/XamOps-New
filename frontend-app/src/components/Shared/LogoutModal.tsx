import React, { useEffect } from 'react';
import { createPortal } from 'react-dom';
import clsx from 'clsx';

interface LogoutModalProps {
    isOpen: boolean;
    onClose: () => void;
    onConfirm: () => void;
    displayName: string;
}

const LogoutModal: React.FC<LogoutModalProps> = ({
    isOpen,
    onClose,
    onConfirm,
    displayName,
}) => {
    // Lock body scroll and handle Esc key
    useEffect(() => {
        const handleKeyDown = (e: KeyboardEvent) => {
            if (e.key === 'Escape') onClose();
        };

        if (isOpen) {
            document.body.style.overflow = 'hidden';
            window.addEventListener('keydown', handleKeyDown);
        } else {
            document.body.style.overflow = '';
            window.removeEventListener('keydown', handleKeyDown);
        }
        return () => {
            document.body.style.overflow = '';
            window.removeEventListener('keydown', handleKeyDown);
        };
    }, [isOpen, onClose]);

    if (!isOpen) return null;

    return createPortal(
        <div
            className="fixed inset-0 z-[9999] flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-md animate-in fade-in duration-300"
            onClick={onClose}
            role="dialog"
            aria-modal="true"
        >
            <div
                className={clsx(
                    "w-full max-w-sm rounded-3xl p-8 bg-white/90 backdrop-blur-2xl shadow-2xl border border-white/30 transform transition-all duration-300",
                    "logout-modal-content"
                )}
                onClick={(e) => e.stopPropagation()}
                role="document"
            >
                {/* Header - Glass Icon */}
                <div className="flex flex-col items-center text-center">
                    <div className="w-20 h-20 rounded-full bg-rose-50 flex items-center justify-center mb-6 shadow-inner border border-rose-100">
                        <div className="w-14 h-14 rounded-full bg-gradient-to-br from-rose-500 to-rose-600 flex items-center justify-center text-white shadow-lg shadow-rose-500/30 animate-pulse-slow">
                            <i className="fas fa-sign-out-alt text-2xl" />
                        </div>
                    </div>

                    <h3 className="text-2xl font-extrabold text-slate-900 mb-2">Logout Confirmation</h3>
                    <p className="text-slate-600 mb-8 leading-relaxed">
                        Hey <span className="font-bold text-slate-800">{displayName}</span>, are you sure you want to sign out? You'll need to log back in to access your dashboard.
                    </p>

                    <div className="flex flex-col w-full gap-3">
                        <button
                            onClick={onConfirm}
                            className="w-full py-4 px-6 rounded-2xl bg-gradient-to-r from-rose-500 to-rose-600 text-white font-bold text-lg shadow-xl shadow-rose-500/25 hover:shadow-rose-500/40 hover:-translate-y-0.5 transition-all duration-200 active:scale-95"
                        >
                            Confirm Sign Out
                        </button>

                        <button
                            onClick={onClose}
                            className="w-full py-4 px-6 rounded-2xl bg-slate-100 hover:bg-slate-200 text-slate-700 font-bold transition-all duration-200 border border-slate-200 active:scale-95"
                        >
                            Stay Logged In
                        </button>
                    </div>
                </div>

                <style>{`
                    @keyframes modal-backdrop-in {
                        from { opacity: 0; backdrop-filter: blur(0px); }
                        to { opacity: 1; backdrop-filter: blur(12px); }
                    }
                    @keyframes modal-content-in {
                        from { opacity: 0; transform: scale(0.9) translateY(20px); }
                        to { opacity: 1; transform: scale(1) translateY(0); }
                    }
                    @keyframes pulse-slow {
                        0%, 100% { transform: scale(1); opacity: 1; }
                        50% { transform: scale(1.05); opacity: 0.9; }
                    }
                    .animate-in {
                        animation: modal-backdrop-in 0.3s ease-out forwards;
                    }
                    .logout-modal-content {
                        animation: modal-content-in 0.4s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
                    }
                    .animate-pulse-slow {
                        animation: pulse-slow 3s infinite ease-in-out;
                    }
                `}</style>
            </div>
        </div>,
        document.body
    );
};

export default LogoutModal;
