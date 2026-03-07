import React from 'react';
import { DialogRoot, DialogPanel } from '../ui';

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
}) => (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-sm">
        <DialogPanel onClose={onClose} hideCloseButton>
            <div className="flex flex-col items-center text-center py-2">
                <div className="w-20 h-20 rounded-full bg-rose-50 flex items-center justify-center mb-6 shadow-inner border border-rose-100">
                    <div className="w-14 h-14 rounded-full bg-gradient-to-br from-rose-500 to-rose-600 flex items-center justify-center text-white shadow-lg shadow-rose-500/30">
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
        </DialogPanel>
    </DialogRoot>
);

export default LogoutModal;
