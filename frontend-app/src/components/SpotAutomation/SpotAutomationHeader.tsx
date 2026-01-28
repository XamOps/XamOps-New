import React, { useState } from 'react';

interface SpotAutomationHeaderProps {
  onSyncAccount: () => void;
  onDeployToAWS: () => void;
}

const SpotAutomationHeader: React.FC<SpotAutomationHeaderProps> = ({
  onSyncAccount,
  onDeployToAWS,
}) => {
  const [showTooltip, setShowTooltip] = useState(false);

  return (
    <header className="bg-white border-b border-gray-200 px-8 py-6 flex justify-between items-center flex-shrink-0 shadow-sm">
      <div className="flex items-center gap-4">
        <div>
          <h1 className="text-3xl font-bold bg-gradient-to-r from-indigo-600 to-purple-600 bg-clip-text text-transparent">
            Spot Automation
          </h1>
          <p className="text-sm text-gray-600 mt-1">
            Automated Spot instance management & cost optimization across all regions
          </p>
        </div>

        {/* Setup Guide Icon */}
        <div className="relative">
          <button
            onMouseEnter={() => setShowTooltip(true)}
            onMouseLeave={() => setShowTooltip(false)}
            className="text-blue-500 hover:text-blue-600 transition-colors"
            aria-label="Setup guide"
          >
            <i className="fas fa-question-circle text-2xl"></i>
          </button>

          {showTooltip && (
            <div className="absolute left-0 top-full mt-2 z-50 w-96 bg-gray-900 text-white rounded-lg shadow-2xl p-4">
              <div className="font-semibold mb-3 text-sm">Setup Steps:</div>
              <ol className="list-decimal list-inside space-y-2 text-xs leading-relaxed">
                <li>
                  <strong>Click "Deploy to AWS"</strong> button to open CloudFormation template
                </li>
                <li>
                  <strong>Deploy the stack</strong> in your AWS account using the CloudFormation console
                </li>
                <li>
                  <strong>Wait for deployment</strong> to complete (status: CREATE_COMPLETE)
                </li>
                <li>
                  <strong>Click "Sync Account"</strong> button above
                </li>
                <li>
                  <strong>Enter your AWS Account ID</strong> and submit
                </li>
              </ol>
              <div className="mt-3 pt-3 border-t border-gray-700 text-xs">
                <i className="fas fa-info-circle mr-1"></i>
                The stack sets up IAM roles and permissions for AutoSpotting
              </div>
              {/* Arrow */}
              <div className="absolute -top-2 left-4 w-4 h-4 bg-gray-900 transform rotate-45"></div>
            </div>
          )}
        </div>
      </div>

      <div className="flex space-x-3">
        <button
          onClick={onSyncAccount}
          className="bg-gradient-to-r from-blue-500 to-blue-600 hover:from-blue-600 hover:to-blue-700 text-white px-6 py-3 rounded-lg flex items-center space-x-2 shadow-lg hover:shadow-xl transition-all duration-200 transform hover:scale-105"
        >
          <i className="fas fa-sync text-lg"></i>
          <span className="font-semibold">Sync Account</span>
        </button>

        <button
          onClick={onDeployToAWS}
          className="bg-gradient-to-r from-orange-500 to-orange-600 hover:from-orange-600 hover:to-orange-700 text-white px-6 py-3 rounded-lg flex items-center space-x-2 shadow-lg hover:shadow-xl transition-all duration-200 transform hover:scale-105"
        >
          <i className="fab fa-aws text-lg"></i>
          <span className="font-semibold">Deploy to AWS</span>
        </button>
      </div>
    </header>
  );
};

export default SpotAutomationHeader;
