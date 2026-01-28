/**
 * EmptyState Component
 * Shows when no accounts are connected
 */

import React from 'react';

const EmptyState: React.FC = () => {
  return (
    <div className="text-center py-32 fade-in">
      <div className="max-w-md mx-auto">
        <div className="w-24 h-24 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-full flex items-center justify-center mx-auto mb-6">
          <i className="fas fa-plus-circle text-white text-3xl"></i>
        </div>
        <h3 className="text-2xl font-bold text-gray-800 mb-4">Get Started with XamOps</h3>
        <p className="text-gray-600 mb-8">
          It looks like you don't have any cloud accounts connected yet. Add your first account to
          begin monitoring your infrastructure.
        </p>
        <a
          href="/account-manager.html"
          className="inline-block bg-gradient-to-r from-indigo-600 to-purple-600 text-white font-bold py-3 px-8 rounded-xl shadow-lg hover:shadow-xl transition-all transform hover:scale-105"
        >
          <i className="fas fa-users-cog mr-2"></i> Go to Account Manager
        </a>
      </div>
    </div>
  );
};

export default EmptyState;
