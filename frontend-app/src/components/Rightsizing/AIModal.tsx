/**
 * AI Modal Component
 *
 * Displays AI-powered recommendations in a modal dialog
 */

import React from 'react';
import { marked } from 'marked';

interface AIModalProps {
  show: boolean;
  isLoading: boolean;
  recommendation: string;
  onClose: () => void;
}

const AIModal: React.FC<AIModalProps> = ({ show, isLoading, recommendation, onClose }) => {
  // Handle copy to clipboard
  const handleCopy = () => {
    const temp = document.createElement('div');
    temp.innerHTML = marked.parse(recommendation) as string;
    const plainText = temp.textContent || temp.innerText || '';

    navigator.clipboard.writeText(plainText).then(() => {
      // Show copied feedback (simple alert for now, could be improved)
      const button = document.querySelector('.copy-button');
      if (button) {
        button.innerHTML = '<i class="fas fa-check mr-2"></i><span>Copied!</span>';
        button.classList.add('bg-green-100', 'text-green-700');
        button.classList.remove('bg-gray-100', 'text-gray-700');
        setTimeout(() => {
          button.innerHTML = '<i class="fas fa-copy"></i><span>Copy</span>';
          button.classList.remove('bg-green-100', 'text-green-700');
          button.classList.add('bg-gray-100', 'text-gray-700');
        }, 2000);
      }
    }).catch((err) => {
      console.error('Failed to copy:', err);
    });
  };

  // Handle escape key
  React.useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && show) {
        onClose();
      }
    };

    window.addEventListener('keydown', handleEscape);
    return () => window.removeEventListener('keydown', handleEscape);
  }, [show, onClose]);

  if (!show) return null;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto bg-black bg-opacity-60 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="relative bg-white rounded-2xl shadow-2xl max-w-3xl w-full max-h-[90vh] overflow-hidden">
        {/* Header */}
        <div className="sticky top-0 z-10 bg-gradient-to-r from-indigo-600 via-purple-600 to-pink-600 px-8 py-6 text-white">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-3">
              <div className="w-12 h-12 bg-white bg-opacity-20 rounded-xl flex items-center justify-center backdrop-blur-sm">
                <i className="fas fa-robot text-2xl"></i>
              </div>
              <div>
                <h2 className="text-2xl font-bold">AI-Powered Analysis</h2>
                <p className="text-sm text-white text-opacity-90">Smart recommendations by XamAi</p>
              </div>
            </div>
            <button
              onClick={onClose}
              className="w-10 h-10 rounded-full bg-white bg-opacity-20 hover:bg-opacity-30 transition-all flex items-center justify-center"
            >
              <i className="fas fa-times text-xl"></i>
            </button>
          </div>
        </div>

        {/* Content */}
        <div className="overflow-y-auto max-h-[calc(90vh-200px)] px-8 py-6">
          {/* Loading State */}
          {isLoading && (
            <div className="flex flex-col items-center justify-center py-12">
              <div className="relative">
                <div className="w-16 h-16 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin"></div>
                <div className="absolute inset-0 flex items-center justify-center">
                  <i className="fas fa-brain text-indigo-600 text-xl animate-pulse"></i>
                </div>
              </div>
              <p className="mt-4 text-gray-600 font-medium">Analyzing with AI...</p>
              <p className="text-sm text-gray-500">This may take a few seconds</p>
            </div>
          )}

          {/* AI Recommendation */}
          {!isLoading && (
            <>
              <div className="bg-gradient-to-br from-blue-50 to-indigo-50 rounded-xl p-6 border border-indigo-100 mb-4">
                <div className="flex items-center space-x-2 mb-4">
                  <div className="w-8 h-8 bg-indigo-600 rounded-lg flex items-center justify-center">
                    <i className="fas fa-sparkles text-white text-sm"></i>
                  </div>
                  <span className="text-sm font-semibold text-indigo-900">AI Recommendation</span>
                </div>
                <div
                  className="ai-content"
                  dangerouslySetInnerHTML={{ __html: marked.parse(recommendation) as string }}
                />
              </div>

              <div className="flex items-center justify-between p-4 bg-gray-50 rounded-xl border border-gray-200">
                <div className="flex items-center space-x-2 text-sm text-gray-600">
                  <i className="fas fa-info-circle text-indigo-600"></i>
                  <span>
                    Powered by <strong>XamAi</strong>
                  </span>
                </div>
                <div className="flex items-center space-x-2 text-sm text-gray-500">
                  <i className="fas fa-clock"></i>
                  <span>{new Date().toLocaleTimeString()}</span>
                </div>
              </div>
            </>
          )}
        </div>

        {/* Footer */}
        <div className="sticky bottom-0 bg-white border-t border-gray-200 px-8 py-4 flex items-center justify-between">
          <button
            onClick={handleCopy}
            className="copy-button flex items-center space-x-2 px-4 py-2 text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg transition-all"
          >
            <i className="fas fa-copy"></i>
            <span>Copy</span>
          </button>
          <button
            onClick={onClose}
            className="px-6 py-2 bg-gradient-to-r from-indigo-600 to-purple-600 text-white rounded-lg hover:from-indigo-700 hover:to-purple-700 transition-all font-medium shadow-lg"
          >
            <i className="fas fa-check mr-2"></i>Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default AIModal;
