import React from 'react';
import clsx from 'clsx';
import { DialogRoot, DialogPanel } from '../ui';

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

    return (
        <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-sm">
            <DialogPanel onClose={onClose} hideCloseButton>
                <div className="flex flex-col items-center text-center py-2">
                    <div className={clsx("w-20 h-20 rounded-full flex items-center justify-center mb-6 shadow-inner border", config.bg, config.border)}>
                        <div className={clsx("w-14 h-14 rounded-full bg-gradient-to-br flex items-center justify-center text-white shadow-lg", config.gradient, config.shadow)}>
                            <i className={clsx("fas text-2xl", config.icon)} />
                        </div>
                    </div>

                    <h3 className="text-2xl font-extrabold text-slate-900 mb-2">{title}</h3>
                    <p className="text-slate-600 mb-8 leading-relaxed">{message}</p>

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
            </DialogPanel>
        </DialogRoot>
    );
};

export default NotificationModal;
