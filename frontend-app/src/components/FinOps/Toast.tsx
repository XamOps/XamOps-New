import React from 'react';
import { Icon } from '@iconify/react';

interface ToastProps {
  show: boolean;
  title: string;
  message: string;
  onClose: () => void;
}

const Toast: React.FC<ToastProps> = ({ show, title, message, onClose }) => {
  if (!show) return null;
  return (
    <div className="fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-4 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-lg">
      <div className="w-8 h-8 rounded-lg bg-[#1BE68B]/20 flex items-center justify-center flex-shrink-0">
        <Icon icon="mdi:check-circle" width={18} color="#158045" />
      </div>
      <div>
        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{title}</p>
        <p className="text-[11px] text-[rgba(0,0,0,0.45)]">{message}</p>
      </div>
      <button onClick={onClose} className="ml-2 text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.6)] text-lg leading-none">&times;</button>
    </div>
  );
};

export default Toast;
