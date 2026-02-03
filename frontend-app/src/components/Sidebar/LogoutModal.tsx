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
                    "w-full max-w-sm rounded-3xl p-8 bg-white/80 backdrop-blur-2xl shadow-2xl border border-white/30 transform transition-all duration-300 animate-in zoom-in-95 slide-in-from-bottom-10",
                )}
                onClick={(e) => e.stopPropagation()}
                role="document"
            >
                {/* Header - Glass Icon */}
                <div className="flex flex-col items-center text-center">
                    <div className="w-20 h-20 rounded-full bg-red-100/50 flex items-center justify-center mb-6 shadow-inner border border-red-200/50">
                        <div className="w-14 h-14 rounded-full bg-gradient-to-br from-red-500 to-red-600 flex items-center justify-center text-white shadow-lg shadow-red-500/30">
                            <i className="fas fa-sign-out-alt text-2xl" />
                        </div>
                    </div>

                    <h3 className="text-2xl font-extrabold text-slate-900 mb-2">Logout</h3>
                    <p className="text-slate-600 mb-8 leading-relaxed">
                        Hey <span className="font-bold text-slate-800">{displayName}</span>, are you sure you want to sign out of your account?
                    </p>

                    <div className="flex flex-col w-full gap-3">
                        <button
                            onClick={onConfirm}
                            className="w-full py-4 px-6 rounded-2xl bg-gradient-to-r from-red-500 to-red-600 text-white font-bold text-lg shadow-xl shadow-red-500/25 hover:shadow-red-500/40 hover:-translate-y-0.5 transition-all duration-200 active:scale-95"
                        >
                            Sign Out
                        </button>

                        <button
                            onClick={onClose}
                            className="w-full py-4 px-6 rounded-2xl bg-slate-100 hover:bg-slate-200 text-slate-700 font-bold transition-all duration-200 border border-slate-200"
                        >
                            Wait, stay logged in
                        </button>
                    </div>
                </div>
            </div>
        </div>,
        document.body
    );
};

export default LogoutModal;
