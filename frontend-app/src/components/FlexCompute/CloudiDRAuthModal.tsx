/**
 * CloudiDR Auth Modal Component
 *
 * Modal for authenticating with CloudiDR (email/password sign-in)
 */

import React, { useState } from 'react';

interface CloudiDRAuthModalProps {
  onAuthenticate: (email: string, password: string) => Promise<boolean>;
  error: string | null;
}

const CloudiDRAuthModal: React.FC<CloudiDRAuthModalProps> = ({ onAuthenticate, error }) => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email || !password) return;

    setIsSubmitting(true);
    await onAuthenticate(email, password);
    setIsSubmitting(false);
  };

  return (
    <div className="flex-1 flex items-center justify-center min-h-[60vh] p-8">
      <div className="bg-white rounded-3xl shadow-xl border border-gray-200 p-10 max-w-md w-full">
        {/* Logo/Icon */}
        <div className="text-center mb-8">
          <div className="w-20 h-20 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-2xl flex items-center justify-center mx-auto mb-4 shadow-lg">
            <i className="fas fa-cloud text-white text-3xl"></i>
          </div>
          <h2 className="text-2xl font-bold text-gray-900">Connect to CloudiDR</h2>
          <p className="text-sm text-gray-500 mt-2">
            Sign in with your CloudiDR account to access FlexCompute capacity management
          </p>
        </div>

        {/* Error Message */}
        {error && (
          <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-xl text-sm text-red-700 flex items-center gap-2">
            <i className="fas fa-exclamation-circle"></i>
            {error}
          </div>
        )}

        {/* Form */}
        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <label htmlFor="cloudidr-email" className="block text-sm font-medium text-gray-700 mb-1.5">
              Email Address
            </label>
            <input
              id="cloudidr-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="your@email.com"
              className="w-full px-4 py-3 border border-gray-300 rounded-xl text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all"
              required
            />
          </div>

          <div>
            <label htmlFor="cloudidr-password" className="block text-sm font-medium text-gray-700 mb-1.5">
              Password
            </label>
            <input
              id="cloudidr-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Enter your CloudiDR password"
              className="w-full px-4 py-3 border border-gray-300 rounded-xl text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all"
              required
            />
          </div>

          <button
            type="submit"
            disabled={isSubmitting || !email || !password}
            className="w-full py-3 px-4 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-semibold rounded-xl hover:from-blue-700 hover:to-indigo-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed shadow-lg shadow-blue-500/25"
          >
            {isSubmitting ? (
              <>
                <i className="fas fa-spinner fa-spin mr-2"></i>
                Connecting...
              </>
            ) : (
              <>
                <i className="fas fa-sign-in-alt mr-2"></i>
                Connect to CloudiDR
              </>
            )}
          </button>
        </form>

        <p className="text-xs text-gray-400 text-center mt-6">
          Your credentials are encrypted and stored securely.
        </p>
      </div>
    </div>
  );
};

export default CloudiDRAuthModal;
