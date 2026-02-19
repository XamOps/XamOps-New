import React, { useState, useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface SpotAutomationHeaderProps {
  onDeployToAWS: () => void;
  disabled?: boolean;
}

const SpotAutomationHeader: React.FC<SpotAutomationHeaderProps> = ({
  onDeployToAWS,
  disabled = false,
}) => {
  const { setActions, clearActions } = useTopbar();
  const [showTooltip, setShowTooltip] = useState(false);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        {/* Help Icon */}
        <div className="relative">
          <button
            onMouseEnter={() => setShowTooltip(true)}
            onMouseLeave={() => setShowTooltip(false)}
            className="w-8 h-8 flex items-center justify-center text-slate-400 hover:text-indigo-600 transition-colors bg-slate-50 hover:bg-indigo-50 rounded-full"
            aria-label="Setup guide"
          >
            <i className="fas fa-question-circle"></i>
          </button>

          {showTooltip && (
            <div className="absolute right-0 top-full mt-2 z-50 w-72 bg-slate-900 text-white rounded-xl shadow-2xl p-4 animate-in fade-in slide-in-from-top-2 duration-200">
              <div className="font-bold mb-3 text-xs uppercase tracking-wider text-slate-400">Setup Guide</div>
              <ol className="list-decimal list-inside space-y-2 text-xs leading-relaxed text-slate-300">
                <li>Click <strong>Deploy to AWS</strong> to open CloudFormation</li>
                <li><strong>Deploy stack</strong> in your AWS account</li>
                <li>Wait for <strong>CREATE_COMPLETE</strong> status</li>
                <li>AutoSpotting will start receiving events automatically</li>
              </ol>
              <div className="mt-3 pt-3 border-t border-slate-700/50 text-[10px] text-slate-500">
                <i className="fas fa-info-circle mr-1"></i>
                Sets up EventBridge rules and IAM roles
              </div>
            </div>
          )}
        </div>

        <div className="h-6 w-px bg-slate-200 mx-1"></div>

        <button
          onClick={onDeployToAWS}
          disabled={disabled}
          className={`flex items-center gap-2 px-4 py-2 text-xs font-bold rounded-xl transition-all ${
            disabled
              ? 'bg-gray-300 text-gray-500 cursor-not-allowed'
              : 'bg-gradient-to-r from-orange-500 to-orange-600 text-white shadow-lg shadow-orange-200 hover:shadow-orange-300 active:scale-95'
          }`}
        >
          <i className={`fab fa-aws text-sm ${disabled ? 'text-gray-500' : 'text-white'}`}></i>
          <span>Deploy to AWS</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, onDeployToAWS, showTooltip, disabled]);

  return null;
};

export default SpotAutomationHeader;
