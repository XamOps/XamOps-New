import React, { useState } from 'react';
import { Icon } from '@iconify/react';

interface CloudiDRAuthModalProps {
  onAuthenticate: (email: string, password: string) => Promise<boolean>;
  error: string | null;
}

const inputCls = 'w-full px-4 py-3 border border-[rgba(0,0,0,0.15)] rounded-xl text-[13px] text-[rgba(0,0,0,0.88)] focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none transition-all';

const CloudiDRAuthModal: React.FC<CloudiDRAuthModalProps> = ({ onAuthenticate, error }) => {
  const [email, setEmail]           = useState('');
  const [password, setPassword]     = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email || !password) return;
    setIsSubmitting(true);
    await onAuthenticate(email, password);
    setIsSubmitting(false);
  };

  return (
    <div className="flex-1 flex items-center justify-center min-h-[60vh] px-6 pt-4 pb-6">
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-sm p-10 max-w-md w-full">
        {/* Icon */}
        <div className="text-center mb-8">
          <div className="w-16 h-16 bg-indigo-600 rounded-2xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:cloud-lock-outline" width={32} color="#fff" />
          </div>
          <h2 className="text-[20px] font-bold text-[rgba(0,0,0,0.88)]">Connect to CloudiDR</h2>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mt-2">
            Sign in with your CloudiDR account to access FlexCompute capacity management
          </p>
        </div>

        {/* Error */}
        {error && (
          <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-xl text-[13px] text-red-700 flex items-center gap-2">
            <Icon icon="mdi:alert-circle-outline" width={16} />
            {error}
          </div>
        )}

        {/* Form */}
        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <label htmlFor="cloudidr-email" className="block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] mb-1.5">
              Email Address
            </label>
            <input
              id="cloudidr-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="your@email.com"
              className={inputCls}
              required
            />
          </div>

          <div>
            <label htmlFor="cloudidr-password" className="block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] mb-1.5">
              Password
            </label>
            <input
              id="cloudidr-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Enter your CloudiDR password"
              className={inputCls}
              required
            />
          </div>

          <button
            type="submit"
            disabled={isSubmitting || !email || !password}
            className="w-full flex items-center justify-center gap-2 py-3 px-4 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-xl transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isSubmitting
              ? <><Icon icon="mdi:loading" width={16} className="animate-spin" />Connecting...</>
              : <><Icon icon="mdi:login-variant" width={16} />Connect to CloudiDR</>
            }
          </button>
        </form>

        <p className="text-[11px] text-[rgba(0,0,0,0.3)] text-center mt-6">
          Your credentials are encrypted and stored securely.
        </p>
      </div>
    </div>
  );
};

export default CloudiDRAuthModal;
