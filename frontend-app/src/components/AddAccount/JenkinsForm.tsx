import React from 'react';
import { Icon } from '@iconify/react';
import type { JenkinsDetails } from '../../types/addAccount';

interface JenkinsFormProps {
  jenkinsDetails: JenkinsDetails;
  isLoading: boolean;
  onJenkinsDetailsChange: (details: Partial<JenkinsDetails>) => void;
  onSubmit: () => void;
}

const inputCls = 'w-full px-3.5 py-2.5 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5 focus:border-[rgba(0,0,0,0.25)] transition-colors placeholder:text-[rgba(0,0,0,0.3)]';
const labelCls = 'block text-[13px] font-medium text-[rgba(0,0,0,0.7)] mb-1.5';
const hintCls = 'text-[11px] text-[rgba(0,0,0,0.4)] mt-1';

const JenkinsForm: React.FC<JenkinsFormProps> = ({
  jenkinsDetails, isLoading, onJenkinsDetailsChange, onSubmit,
}) => {
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit();
  };

  return (
    <div>
      {/* Header */}
      <div className="flex items-center gap-3 mb-6">
        <div className="w-10 h-10 rounded-xl bg-[#D33833]/10 border border-[#D33833]/20 flex items-center justify-center">
          <Icon icon="simple-icons:jenkins" width={20} className="text-[#D33833]" />
        </div>
        <div>
          <h2 className="text-[16px] font-bold text-black">Connect Jenkins Server</h2>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Enter your Jenkins server URL and API credentials.</p>
        </div>
      </div>

      <form onSubmit={handleSubmit} className="space-y-5">
        {/* URL */}
        <div>
          <label htmlFor="jenkinsUrl" className={labelCls}>Jenkins URL</label>
          <input
            type="url"
            id="jenkinsUrl"
            placeholder="https://jenkins.yourcompany.com"
            value={jenkinsDetails.url}
            onChange={(e) => onJenkinsDetailsChange({ url: e.target.value })}
            className={inputCls}
            required
          />
          <p className={hintCls}>Full URL including protocol (http/https) and port if applicable</p>
        </div>

        {/* Username */}
        <div>
          <label htmlFor="jenkinsUsername" className={labelCls}>Username</label>
          <input
            id="jenkinsUsername"
            type="text"
            placeholder="admin"
            value={jenkinsDetails.username}
            onChange={(e) => onJenkinsDetailsChange({ username: e.target.value })}
            className={inputCls}
            required
          />
          <p className={hintCls}>Your Jenkins username</p>
        </div>

        {/* API Token */}
        <div>
          <label htmlFor="jenkinsToken" className={labelCls}>API Token</label>
          <input
            type="password"
            id="jenkinsToken"
            placeholder="Enter your User API Token"
            value={jenkinsDetails.token}
            onChange={(e) => onJenkinsDetailsChange({ token: e.target.value })}
            className={inputCls}
            required
          />
          <p className={hintCls}>Generate at: User Profile → Configure → API Token</p>
        </div>

        {/* Submit */}
        <button
          type="submit"
          disabled={isLoading}
          className="w-full py-3 bg-[#D33833] text-white text-[13px] font-semibold rounded-lg hover:bg-[#B52E2A] transition-colors disabled:opacity-40 flex items-center justify-center gap-2 shadow-sm shadow-[#D33833]/20"
        >
          {isLoading ? (
            <><div className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />Connecting...</>
          ) : (
            <>Connect Jenkins <Icon icon="mdi:connection" width={16} /></>
          )}
        </button>
      </form>
    </div>
  );
};

export default JenkinsForm;
