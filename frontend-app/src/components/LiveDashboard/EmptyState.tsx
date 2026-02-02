/**
 * EmptyState Component
 * Shows when no accounts are connected
 */

import React from 'react';

import { Link } from 'react-router-dom';

const EmptyState: React.FC = () => {
  return (
    <div className="text-center py-32 fade-in">
      <div className="max-w-md mx-auto">
        <div className="w-24 h-24 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-full flex items-center justify-center mx-auto mb-6 shadow-xl shadow-indigo-500/20">
          <svg className="w-10 h-10 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v3m0 0v3m0-3h3m-3 0H9m12 0a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        </div>
        <h3 className="text-2xl font-bold text-gray-800 mb-4">Get Started with XamOps</h3>
        <p className="text-gray-600 mb-8">
          It looks like you don't have any cloud accounts connected yet. Add your first account to
          begin monitoring your infrastructure.
        </p>
        <Link
          to="/account-manager"
          className="inline-flex items-center bg-gradient-to-r from-indigo-600 to-purple-600 text-white font-bold py-3 px-8 rounded-xl shadow-lg hover:shadow-xl transition-all transform hover:scale-105"
        >
          <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
          </svg>
          Go to Account Management
        </Link>
      </div>
    </div>
  );
};

export default EmptyState;
