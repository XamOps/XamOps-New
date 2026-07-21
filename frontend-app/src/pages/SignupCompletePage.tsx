import React, { useEffect, useMemo, useState, FormEvent } from 'react';

interface CompleteResponse {
  redirectUrl?: string;
  tenantId?: string;
  error?: string;
}

/** Decodes a JWT payload (no verification — display only) to pre-fill the form. */
function decodeToken(token: string): { email?: string; name?: string; provider?: string } {
  try {
    const payload = token.split('.')[1];
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(json);
  } catch {
    return {};
  }
}

const inputClass =
  'w-full px-3 py-2.5 text-sm border border-gray-200 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all';
const labelClass = 'block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5';

const SignupCompletePage: React.FC = () => {
  const token = useMemo(() => new URLSearchParams(window.location.search).get('token') || '', []);
  const claims = useMemo(() => decodeToken(token), [token]);

  const [fullName, setFullName] = useState(claims.name || '');
  const [companyName, setCompanyName] = useState('');
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Route to the provider-specific complete-signup endpoint. Defaults to Google
  // for tokens minted before the `provider` claim existed (backward-compatible).
  const provider = claims.provider === 'microsoft' ? 'microsoft' : 'google';
  const providerLabel = provider === 'microsoft' ? 'Microsoft' : 'Google';

  useEffect(() => {
    if (!token) setError(`Your signup session is missing. Please sign in with ${providerLabel} again.`);
  }, [token, providerLabel]);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    if (!companyName.trim()) {
      setError('Company name is required.');
      return;
    }
    setIsLoading(true);
    try {
      const response = await fetch(`/api/auth/${provider}/complete-signup`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, companyName: companyName.trim(), fullName: fullName.trim(), phone, address }),
      });
      const data: CompleteResponse = await response.json();
      if (!response.ok) {
        throw new Error(data.error || 'Could not complete signup. Please try again.');
      }
      sessionStorage.clear();
      if (data.tenantId) sessionStorage.setItem('selectedTenantId', data.tenantId);
      let redirectPath = '/unified-dashboard';
      if (data.redirectUrl) {
        try { redirectPath = new URL(data.redirectUrl).pathname; }
        catch { redirectPath = data.redirectUrl.startsWith('/') ? data.redirectUrl : `/${data.redirectUrl}`; }
      }
      window.location.href = redirectPath;
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred. Please try again.');
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen w-full bg-gray-50 dark:bg-gray-950 flex items-center justify-center p-4">
      <div className="bg-white dark:bg-gray-900 rounded-2xl shadow-2xl w-full max-w-lg flex flex-col max-h-[92vh] overflow-hidden">

        {/* Header — matches OnboardingModal */}
        <div className="px-6 py-5 bg-gradient-to-r from-indigo-600 to-indigo-700 flex-shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 bg-white/20 rounded-xl flex items-center justify-center flex-shrink-0">
              <i className="fas fa-building text-white text-sm" />
            </div>
            <div>
              <h2 className="text-white font-bold text-base leading-tight">Complete your account</h2>
              <p className="text-indigo-200 text-xs mt-0.5">Tell us about your organization to start your free trial</p>
            </div>
          </div>
        </div>

        {/* Body */}
        <form onSubmit={handleSubmit} className="flex-1 overflow-y-auto flex flex-col">
          <div className="px-6 py-5 space-y-4">
            <div>
              <h3 className="text-sm font-semibold text-gray-900 dark:text-white">Organization details</h3>
              <p className="text-xs text-gray-400 mt-0.5">This starts your 14-day free trial</p>
            </div>

            {error && (
              <div className="flex items-center gap-2 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
                <i className="fas fa-exclamation-circle text-red-500 text-xs flex-shrink-0" />
                <p className="text-red-700 dark:text-red-400 text-xs">{error}</p>
              </div>
            )}

            {/* Email (verified, read-only) */}
            {claims.email && (
              <div>
                <label className={labelClass}>Email Address</label>
                <div className="relative">
                  <input type="email" value={claims.email} readOnly disabled className={`${inputClass} pr-9 opacity-80 cursor-not-allowed`} />
                  <i className="fas fa-circle-check text-green-500 text-xs absolute right-3 top-1/2 -translate-y-1/2" title={`Verified by ${providerLabel}`} />
                </div>
              </div>
            )}

            {/* Company Name */}
            <div>
              <label className={labelClass}>
                Company Name <span className="text-red-500">*</span>
              </label>
              <input
                type="text" value={companyName} autoFocus
                onChange={(e) => setCompanyName(e.target.value)}
                placeholder="Acme Corp" className={inputClass}
              />
            </div>

            {/* Full Name */}
            <div>
              <label className={labelClass}>Full Name</label>
              <input
                type="text" value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                placeholder="John Smith" className={inputClass}
              />
            </div>

            {/* Phone */}
            <div>
              <label className={labelClass}>
                Phone Number <span className="ml-1.5 text-[10px] font-normal text-gray-400">(optional)</span>
              </label>
              <input
                type="tel" value={phone}
                onChange={(e) => setPhone(e.target.value)}
                placeholder="+1 (555) 000-0000" className={inputClass}
              />
            </div>

            {/* Address */}
            <div>
              <label className={labelClass}>
                Business Address <span className="ml-1.5 text-[10px] font-normal text-gray-400">(optional)</span>
              </label>
              <input
                type="text" value={address}
                onChange={(e) => setAddress(e.target.value)}
                placeholder="123 Main St, City, Country" className={inputClass}
              />
            </div>
          </div>

          {/* Footer — matches OnboardingModal */}
          <div className="px-6 py-4 border-t border-gray-100 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/30 flex items-center justify-end flex-shrink-0 mt-auto">
            <button
              type="submit"
              disabled={isLoading || !token}
              className="flex items-center gap-2 px-6 py-2.5 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-60 text-white font-semibold rounded-xl transition-all text-sm shadow-md shadow-indigo-200"
            >
              {isLoading ? (
                <><i className="fas fa-spinner animate-spin text-xs" /> Creating your account...</>
              ) : (
                <><i className="fas fa-arrow-right text-xs" /> Start free trial</>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default SignupCompletePage;
