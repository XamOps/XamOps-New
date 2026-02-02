/**
 * GCP Toast Component
 *
 * Toast notification for success messages
 */

import React, { useEffect } from 'react';

interface GcpToastProps {
  show: boolean;
  title: string;
  message: string;
  onClose: () => void;
}

const GcpToast: React.FC<GcpToastProps> = ({ show, title, message, onClose }) => {
  useEffect(() => {
    if (show) {
      const timer = setTimeout(() => {
        onClose();
      }, 4000);

      return () => clearTimeout(timer);
    }
  }, [show, onClose]);

  if (!show) return null;

  return (
    <div className={`toast-notification ${show ? 'show' : ''}`}>
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

export default GcpToast;
