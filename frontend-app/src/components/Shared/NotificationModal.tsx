import React, { useEffect } from 'react';
import { createPortal } from 'react-dom';
import clsx from 'clsx';

export type NotificationType = 'success' | 'error' | 'info';

interface NotificationModalProps {
    isOpen: boolean;
    onClose: () => void;
    type: NotificationType;
    title: string;
    message: string;
    confirmLabel?: string;
}

const NotificationModal: React.FC<NotificationModalProps> = ({
    isOpen,
    onClose,
    type,
    title,
    message,
    confirmLabel = 'OK',
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

    const config = {
        success: {
            icon: 'fa-check-circle',
            gradient: 'from-emerald-500 to-teal-600',
            shadow: 'shadow-emerald-500/30',
            bg: 'bg-emerald-50',
            border: 'border-emerald-100',
            btn: 'from-emerald-500 to-teal-600 shadow-emerald-500/25 hover:shadow-emerald-500/40'
        },
        error: {
            icon: 'fa-exclamation-triangle',
            gradient: 'from-orange-500 to-rose-600',
            shadow: 'shadow-orange-500/30',
            bg: 'bg-orange-50',
            border: 'border-orange-100',
            btn: 'from-orange-500 to-rose-600 shadow-orange-500/25 hover:shadow-orange-500/40'
        },
        info: {
            icon: 'fa-info-circle',
            gradient: 'from-blue-500 to-indigo-600',
            shadow: 'shadow-blue-500/30',
            bg: 'bg-blue-50',
            border: 'border-blue-100',
            btn: 'from-blue-500 to-indigo-600 shadow-blue-500/25 hover:shadow-blue-500/40'
        }
    }[type];

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
                    "notification-modal-content"
                )}
                onClick={(e) => e.stopPropagation()}
                role="document"
            >
                <div className="flex flex-col items-center text-center">
                    <div className={clsx("w-20 h-20 rounded-full flex items-center justify-center mb-6 shadow-inner border", config.bg, config.border)}>
                        <div className={clsx("w-14 h-14 rounded-full bg-gradient-to-br flex items-center justify-center text-white shadow-lg animate-pulse-slow", config.gradient, config.shadow)}>
                            <i className={clsx("fas text-2xl", config.icon)} />
                        </div>
                    </div>

                    <h3 className="text-2xl font-extrabold text-slate-900 mb-2">{title}</h3>
                    <p className="text-slate-600 mb-8 leading-relaxed">
                        {message}
                    </p>

                    <div className="flex flex-col w-full">
                        <button
                            onClick={onClose}
                            className={clsx(
                                "w-full py-4 px-6 rounded-2xl bg-gradient-to-r text-white font-bold text-lg shadow-xl hover:-translate-y-0.5 transition-all duration-200 active:scale-95",
                                config.btn
                            )}
                        >
                            {confirmLabel}
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
                    .notification-modal-content {
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

export default NotificationModal;
