/**
 * Toast Notification Component
 *
 * Success toast for schedule creation
 */

import React from 'react';

interface ToastProps {
  show: boolean;
  title: string;
  message: string;
  onClose: () => void;
}

const Toast: React.FC<ToastProps> = ({ show, title, message, onClose }) => {
  if (!show) return null;

  return (
    <div
      className={`fixed top-20 right-20 bg-gradient-to-r from-green-500 to-green-600 text-white px-6 py-4 rounded-lg shadow-xl z-50 flex items-center transition-all duration-300 ${
        show ? 'translate-x-0 opacity-100' : 'translate-x-full opacity-0'
      }`}
    >
      <i className="fas fa-check-circle mr-3 text-2xl"></i>
      <div>
        <span className="font-bold">{title}</span>
        <span className="block text-sm">{message}</span>
      </div>
      <button onClick={onClose} className="ml-4 text-lg hover:text-gray-200">
        &times;
      </button>
    </div>
  );
};

export default Toast;
