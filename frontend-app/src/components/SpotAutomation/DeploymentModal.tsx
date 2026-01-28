import React, { useState } from 'react';

interface DeploymentModalProps {
  isOpen: boolean;
  onClose: () => void;
  onGenerateUrl: () => Promise<string>;
}

const DeploymentModal: React.FC<DeploymentModalProps> = ({ isOpen, onClose, onGenerateUrl }) => {
  const [generatedUrl, setGeneratedUrl] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);
  const [error, setError] = useState('');

  const handleGenerateUrl = async () => {
    setIsGenerating(true);
    setError('');
    setGeneratedUrl('');

    try {
      const url = await onGenerateUrl();
      setGeneratedUrl(url);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to generate URL');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleLaunchStack = () => {
    if (!generatedUrl) {
      setError('Please generate a deployment URL first');
      return;
    }

    window.open(generatedUrl, '_blank');
  };

  const handleCopyUrl = () => {
    navigator.clipboard.writeText(generatedUrl);
  };

  const handleClose = () => {
    setGeneratedUrl('');
    setError('');
    onClose();
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto" onClick={handleClose}>
      <div className="flex items-center justify-center min-h-screen px-4 pt-4 pb-20 text-center sm:block sm:p-0">
        {/* Backdrop */}
        <div className="fixed inset-0 transition-opacity bg-gray-900 bg-opacity-75"></div>

        <span className="hidden sm:inline-block sm:align-middle sm:h-screen">&#8203;</span>

        {/* Modal */}
        <div
          onClick={(e) => e.stopPropagation()}
          className="inline-block align-bottom bg-white rounded-xl text-left overflow-hidden shadow-2xl transform transition-all sm:my-8 sm:align-middle sm:max-w-2xl sm:w-full"
        >
          {/* Header */}
          <div className="bg-gradient-to-r from-orange-500 to-orange-600 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center space-x-3">
                <i className="fab fa-aws text-white text-3xl"></i>
                <h3 className="text-2xl font-bold text-white">Deploy AutoSpotting to AWS</h3>
              </div>
              <button onClick={handleClose} className="text-white hover:text-gray-200 transition-colors">
                <i className="fas fa-times text-2xl"></i>
              </button>
            </div>
          </div>

          {/* Body */}
          <div className="px-6 py-6 space-y-6">
            {/* Info Section */}
            <div className="p-4 bg-blue-50 border-2 border-blue-200 rounded-xl">
              <div className="flex items-start space-x-3">
                <i className="fas fa-info-circle text-blue-600 mt-1 text-xl"></i>
                <div className="text-sm text-gray-800">
                  <p className="font-bold text-blue-900 mb-3">What this deployment does:</p>
                  <ul className="list-disc list-inside space-y-2 text-gray-700">
                    <li>Creates an IAM role for AutoSpotting to manage your resources</li>
                    <li>Sets up EventBridge rules to forward AutoScaling events to XamOps</li>
                    <li>
                      Automatically deploys to <strong>all AWS regions</strong> using StackSets
                    </li>
                    <li>Enables automated Spot instance management across your account</li>
                  </ul>
                </div>
              </div>
            </div>

            {/* Configuration */}
            <div className="p-4 bg-gray-50 border-2 border-gray-200 rounded-xl">
              <h4 className="text-sm font-bold text-gray-800 mb-4 flex items-center">
                <i className="fas fa-cog text-gray-600 mr-2"></i>
                Deployment Configuration
              </h4>
              <div className="space-y-3 text-sm text-gray-700">
                <div className="flex items-center justify-between">
                  <span className="flex items-center">
                    <i className="fas fa-globe text-blue-500 mr-2 w-5"></i>
                    Multi-Region Deployment:
                  </span>
                  <span className="font-bold text-green-600">Enabled (All Regions)</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="flex items-center">
                    <i className="fas fa-layer-group text-purple-500 mr-2 w-5"></i>
                    Stack Name:
                  </span>
                  <span className="font-mono text-xs bg-gray-200 px-2 py-1 rounded">
                    XamOps-AutoSpotting-Integration
                  </span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="flex items-center">
                    <i className="fas fa-building text-orange-500 mr-2 w-5"></i>
                    Main Account ID:
                  </span>
                  <span className="font-mono text-xs bg-gray-200 px-2 py-1 rounded">982534352845</span>
                </div>
              </div>
            </div>

            {/* Warning */}
            <div className="p-4 bg-yellow-50 border-2 border-yellow-200 rounded-xl">
              <div className="flex items-start space-x-3">
                <i className="fas fa-exclamation-triangle text-yellow-600 mt-1 text-xl"></i>
                <div className="text-sm text-gray-800">
                  <p className="font-bold text-yellow-900 mb-2">Before you proceed:</p>
                  <ul className="list-disc list-inside space-y-1 text-gray-700">
                    <li>
                      Ensure you have <strong>AdministratorAccess</strong> in your AWS account
                    </li>
                    <li>The stack will create IAM roles and EventBridge rules</li>
                    <li>You can customize regions later in the CloudFormation console if needed</li>
                  </ul>
                </div>
              </div>
            </div>

            {/* Generated URL */}
            {generatedUrl && (
              <div className="p-4 bg-green-50 border-2 border-green-200 rounded-xl">
                <label className="block text-sm font-bold text-gray-800 mb-3 flex items-center">
                  <i className="fas fa-link text-green-600 mr-2"></i>
                  CloudFormation URL Generated
                </label>
                <div className="flex items-center space-x-2">
                  <input
                    type="text"
                    value={generatedUrl}
                    readOnly
                    className="flex-1 px-4 py-2 text-xs bg-white border-2 border-green-300 rounded-lg font-mono text-gray-700"
                  />
                  <button
                    onClick={handleCopyUrl}
                    className="px-4 py-2 bg-green-600 hover:bg-green-700 text-white rounded-lg text-sm transition-colors"
                  >
                    <i className="fas fa-copy"></i>
                  </button>
                </div>
              </div>
            )}

            {/* Error */}
            {error && (
              <div className="p-4 bg-red-50 border-2 border-red-200 rounded-xl">
                <div className="flex items-start space-x-3">
                  <i className="fas fa-exclamation-triangle text-red-600 mt-1"></i>
                  <p className="text-sm text-red-700 font-medium">{error}</p>
                </div>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="bg-gray-50 px-6 py-4 flex justify-between items-center border-t-2 border-gray-200">
            <button
              onClick={handleClose}
              className="px-5 py-2 text-gray-700 hover:text-gray-900 font-semibold transition-colors"
            >
              Cancel
            </button>
            <div className="flex space-x-3">
              <button
                onClick={handleGenerateUrl}
                disabled={isGenerating}
                className="px-6 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center space-x-2 shadow-lg hover:shadow-xl"
              >
                <i className={`fas ${isGenerating ? 'fa-spinner fa-spin' : 'fa-cog'}`}></i>
                <span>{isGenerating ? 'Generating...' : 'Generate URL'}</span>
              </button>
              <button
                onClick={handleLaunchStack}
                disabled={!generatedUrl}
                className="px-6 py-2 bg-gradient-to-r from-orange-500 to-orange-600 hover:from-orange-600 hover:to-orange-700 text-white rounded-lg font-bold transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center space-x-2 shadow-lg hover:shadow-xl"
              >
                <i className="fab fa-aws"></i>
                <span>Launch Stack</span>
                <i className="fas fa-external-link-alt text-xs"></i>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default DeploymentModal;
