/**
 * DevOps-in-the-Box Page
 *
 * Display and manage DevOps automation scripts and infrastructure tools
 *
 * Converted from: devops_in_the_box.html
 *
 * Features:
 * - Browse automation scripts (Terraform, Bash, Python, Ansible)
 * - Search and filter scripts by category
 * - View detailed script information in modal
 * - Copy and download scripts
 * - Real-time backend integration
 */

import React, { useState, useCallback } from 'react';
import { useDevOpsScripts } from '../hooks/useDevOpsScripts';
import { DevOpsScript, ScriptCategory } from '../types/devopsInTheBox';
import Skeleton from '../components/Shared/Skeleton';
import clsx from 'clsx';
import { DialogRoot, DialogPanel } from '../components/ui';

const DevOpsInTheBoxPage: React.FC = () => {
  const {
    filteredScripts,
    isLoading,
    error,
    filters,
    setSearchTerm,
    setFilterCategory,
    refreshScripts,
  } = useDevOpsScripts();

  const [selectedScript, setSelectedScript] = useState<DevOpsScript | null>(null);
  const [copySuccess, setCopySuccess] = useState(false);

  /**
   * Get category color for border
   */
  const getCategoryColor = (category: ScriptCategory): string => {
    const colors = {
      terraform: 'border-purple-500 hover:border-purple-600',
      bash: 'border-green-500 hover:border-green-600',
      python: 'border-blue-500 hover:border-blue-600',
      ansible: 'border-red-500 hover:border-red-600',
    };
    return colors[category] || 'border-gray-500';
  };

  /**
   * Get badge class for category
   */
  const getBadgeClass = (category: ScriptCategory): string => {
    const classes = {
      terraform: 'bg-purple-100 text-purple-700 border-purple-200',
      bash: 'bg-green-100 text-green-700 border-green-200',
      python: 'bg-blue-100 text-blue-700 border-blue-200',
      ansible: 'bg-red-100 text-red-700 border-red-200',
    };
    return classes[category] || 'bg-gray-100 text-gray-700';
  };

  /**
   * Get icon for category
   */
  const getIcon = (category: ScriptCategory): string => {
    const icons = {
      terraform: 'fab fa-terraform',
      bash: 'fas fa-terminal',
      python: 'fab fa-python',
      ansible: 'fas fa-cogs',
    };
    return icons[category] || 'fas fa-file-code';
  };

  /**
   * Get filename based on script category
   */
  const getFileName = (script: DevOpsScript): string => {
    const name = script.name ? script.name.replace(/\s+/g, '-').toLowerCase() : 'script';
    const cat = script.category?.toLowerCase();

    switch (cat) {
      case 'python':
        return `${name}.py`;
      case 'bash':
        return `${name}.sh`;
      case 'terraform':
        return `main.tf`;
      default:
        return `${name}.txt`;
    }
  };

  /**
   * Copy code to clipboard
   */
  const copyCode = useCallback(() => {
    if (!selectedScript?.code) return;

    navigator.clipboard
      .writeText(selectedScript.code)
      .then(() => {
        setCopySuccess(true);
        setTimeout(() => setCopySuccess(false), 2000);
      })
      .catch((err) => {
        console.error('Failed to copy code:', err);
        alert('Failed to copy code to clipboard');
      });
  }, [selectedScript]);

  /**
   * Download script
   */
  const downloadScript = useCallback(() => {
    if (!selectedScript?.code) return;

    const element = document.createElement('a');
    const file = new Blob([selectedScript.code], { type: 'text/plain' });
    element.href = URL.createObjectURL(file);
    element.download = getFileName(selectedScript);
    document.body.appendChild(element);
    element.click();
    document.body.removeChild(element);
  }, [selectedScript]);

  /**
   * Close modal on escape key
   */
  React.useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setSelectedScript(null);
      }
    };

    if (selectedScript) {
      window.addEventListener('keydown', handleEscape);
      return () => window.removeEventListener('keydown', handleEscape);
    }
  }, [selectedScript]);

  return (
    <div className="devops-container min-h-screen bg-gray-50">
      {/* Header Bar */}
      {/* Main Content */}

      {/* Main Content */}
      <div className="p-8">
        {/* Controls */}
        <div className="mb-6 flex flex-col md:flex-row gap-4">
          {/* Search Bar */}
          <div className="flex-1">
            <div className="relative">
              <i className="fas fa-search absolute left-4 top-3.5 text-gray-400"></i>
              <input
                type="text"
                value={filters.searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                placeholder="Search scripts by name or description..."
                className="w-full pl-12 pr-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all shadow-sm"
              />
            </div>
          </div>

          {/* Refresh Button */}
          <button
            onClick={refreshScripts}
            className="flex items-center space-x-2 px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors shadow-md"
          >
            <i className="fas fa-sync-alt"></i>
            <span>Reset Filters</span>
          </button>
        </div>

        {/* Category Filters */}
        <div className="mb-6 flex flex-wrap gap-2">
          <button
            onClick={() => {
              setFilterCategory('all');
              setSearchTerm('');
            }}
            className={clsx(
              'px-5 py-2.5 rounded-lg font-medium transition-all shadow-sm',
              filters.filterCategory === 'all'
                ? 'bg-indigo-600 text-white shadow-md scale-105'
                : 'bg-white text-gray-600 hover:bg-gray-100 border border-gray-200'
            )}
          >
            <i className="fas fa-layer-group mr-2"></i>
            All
          </button>
          <button
            onClick={() => {
              setFilterCategory('terraform');
              setSearchTerm('');
            }}
            className={clsx(
              'px-5 py-2.5 rounded-lg font-medium transition-all shadow-sm',
              filters.filterCategory === 'terraform'
                ? 'bg-purple-600 text-white shadow-md scale-105'
                : 'bg-white text-gray-600 hover:bg-gray-100 border border-gray-200'
            )}
          >
            <i className="fab fa-terraform mr-2"></i>
            Terraform
          </button>
          <button
            onClick={() => {
              setFilterCategory('bash');
              setSearchTerm('');
            }}
            className={clsx(
              'px-5 py-2.5 rounded-lg font-medium transition-all shadow-sm',
              filters.filterCategory === 'bash'
                ? 'bg-green-600 text-white shadow-md scale-105'
                : 'bg-white text-gray-600 hover:bg-gray-100 border border-gray-200'
            )}
          >
            <i className="fas fa-terminal mr-2"></i>
            Bash
          </button>
          <button
            onClick={() => {
              setFilterCategory('python');
              setSearchTerm('');
            }}
            className={clsx(
              'px-5 py-2.5 rounded-lg font-medium transition-all shadow-sm',
              filters.filterCategory === 'python'
                ? 'bg-blue-600 text-white shadow-md scale-105'
                : 'bg-white text-gray-600 hover:bg-gray-100 border border-gray-200'
            )}
          >
            <i className="fab fa-python mr-2"></i>
            Python
          </button>
        </div>

        {/* Error Message */}
        {error && (
          <div className="mb-6 bg-red-50 border border-red-200 text-red-700 px-6 py-4 rounded-lg flex items-start gap-3 shadow-sm">
            <i className="fas fa-exclamation-circle text-red-500 text-xl mt-0.5"></i>
            <div>
              <strong className="font-bold block mb-1">Error</strong>
              <span className="text-sm">{error}</span>
            </div>
          </div>
        )}

        {/* Loading State */}
        {isLoading && (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {[1, 2, 3, 4, 5, 6].map((i) => (
              <div key={i} className="bg-white rounded-xl p-6 border-l-4 border-gray-100 shadow-sm h-48 flex flex-col justify-between">
                <div>
                  <div className="flex justify-between items-start mb-4">
                    <Skeleton variant="text" width="60%" height={24} className="mb-2" />
                    <Skeleton variant="rounded" width={80} height={24} />
                  </div>
                  <Skeleton variant="text" width="90%" height={16} className="mb-1" />
                  <Skeleton variant="text" width="80%" height={16} />
                </div>
                <div className="flex justify-between items-center mt-4 pt-4 border-t border-gray-50">
                  <div className="flex gap-4">
                    <Skeleton variant="text" width={40} height={16} />
                    <Skeleton variant="text" width={80} height={16} />
                  </div>
                  <Skeleton variant="text" width={40} height={16} />
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Scripts Grid */}
        {!isLoading && filteredScripts.length > 0 && (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {filteredScripts.map((script) => (
              <div
                key={script.id}
                onClick={() => setSelectedScript(script)}
                className={clsx(
                  'script-card bg-white rounded-xl p-6 cursor-pointer border-l-4 transition-all duration-300 shadow-sm hover:shadow-xl',
                  getCategoryColor(script.category)
                )}
              >
                <div className="flex items-start justify-between mb-4">
                  <div className="flex-1 pr-2">
                    <h3 className="text-lg font-bold text-gray-900 mb-2 line-clamp-1">{script.name}</h3>
                    <p className="text-sm text-gray-600 line-clamp-2">{script.description}</p>
                  </div>
                  <div
                    className={clsx(
                      'flex items-center px-3 py-1 rounded-full text-xs font-semibold border',
                      getBadgeClass(script.category)
                    )}
                  >
                    <i className={clsx(getIcon(script.category), 'mr-1')}></i>
                    <span className="capitalize">{script.category}</span>
                  </div>
                </div>
                <div className="flex items-center justify-between text-sm text-gray-500">
                  <div className="flex items-center space-x-4">
                    <span className="flex items-center gap-1">
                      <i className="fas fa-download"></i>
                      <span>{script.downloads}</span>
                    </span>
                    <span className="flex items-center gap-1">
                      <i className="far fa-clock"></i>
                      <span>{script.lastUpdated}</span>
                    </span>
                  </div>
                  <span className="text-indigo-600 hover:text-indigo-800 font-medium flex items-center gap-1">
                    View <i className="fas fa-arrow-right"></i>
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* No Results */}
        {!isLoading && filteredScripts.length === 0 && !error && (
          <div className="text-center py-20">
            <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-search text-gray-400 text-3xl"></i>
            </div>
            <h3 className="text-2xl font-bold text-gray-800 mb-2">No Scripts Found</h3>
            <p className="text-gray-600">
              {filters.searchTerm || filters.filterCategory !== 'all'
                ? 'Try adjusting your search or filter to find what you\'re looking for.'
                : 'No scripts available at the moment.'}
            </p>
          </div>
        )}
      </div>

      {/* Script Detail Modal */}
      <DialogRoot isOpen={!!selectedScript} onClose={() => setSelectedScript(null)} maxWidth="max-w-4xl">
        {selectedScript && (
          <DialogPanel
            title={
              <div className="flex items-center space-x-3">
                <i className={clsx(getIcon(selectedScript.category), 'text-2xl text-gray-600')}></i>
                <div className="min-w-0">
                  <div className="text-xl font-bold text-gray-900 truncate">{selectedScript.name}</div>
                  <p className="text-sm text-gray-500 mt-0.5">{selectedScript.description}</p>
                </div>
              </div>
            }
            onClose={() => setSelectedScript(null)}
            footer={
              <div className="flex items-center gap-3 w-full">
                <button
                  onClick={downloadScript}
                  className="flex items-center space-x-2 px-6 py-3 bg-green-600 text-white rounded-lg hover:bg-green-700 transition-colors font-medium shadow-md hover:shadow-lg"
                >
                  <i className="fas fa-download"></i>
                  <span>Download Script</span>
                </button>
                <button
                  onClick={() => setSelectedScript(null)}
                  className="ml-auto px-6 py-3 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors font-medium"
                >
                  Close
                </button>
              </div>
            }
          >
            <div className="overflow-y-auto max-h-[60vh] space-y-6">
              {/* Metadata Grid */}
              <div className="grid grid-cols-2 gap-4">
                <div className="bg-gradient-to-br from-gray-50 to-gray-100 p-4 rounded-lg border border-gray-200">
                  <p className="text-sm text-gray-600 mb-1 font-medium">Category</p>
                  <p className="font-bold text-gray-900 capitalize">{selectedScript.category}</p>
                </div>
                <div className="bg-gradient-to-br from-gray-50 to-gray-100 p-4 rounded-lg border border-gray-200">
                  <p className="text-sm text-gray-600 mb-1 font-medium">Downloads</p>
                  <p className="font-bold text-gray-900">{selectedScript.downloads}</p>
                </div>
                <div className="bg-gradient-to-br from-gray-50 to-gray-100 p-4 rounded-lg border border-gray-200">
                  <p className="text-sm text-gray-600 mb-1 font-medium">Last Updated</p>
                  <p className="font-bold text-gray-900">{selectedScript.lastUpdated}</p>
                </div>
                <div className="bg-gradient-to-br from-gray-50 to-gray-100 p-4 rounded-lg border border-gray-200">
                  <p className="text-sm text-gray-600 mb-1 font-medium">Version</p>
                  <p className="font-bold text-gray-900">{selectedScript.version}</p>
                </div>
              </div>

              {/* Code Block */}
              <div className="bg-gray-900 rounded-lg overflow-hidden shadow-lg border border-gray-700">
                <div className="flex items-center justify-between px-6 py-4 bg-gray-800 border-b border-gray-700">
                  <span className="text-gray-300 text-sm font-mono">{getFileName(selectedScript)}</span>
                  <button
                    onClick={copyCode}
                    className="flex items-center gap-2 text-gray-300 hover:text-white transition-colors px-3 py-1 rounded hover:bg-gray-700"
                  >
                    <i className={clsx('fas', copySuccess ? 'fa-check' : 'fa-copy')}></i>
                    <span className="text-sm">{copySuccess ? 'Copied!' : 'Copy'}</span>
                  </button>
                </div>
                <div className="p-6 overflow-x-auto">
                  <pre className="text-green-400 text-sm font-mono whitespace-pre-wrap break-words">
                    {selectedScript.code}
                  </pre>
                </div>
              </div>

              {/* Usage Instructions */}
              <div className="bg-gradient-to-br from-blue-50 to-indigo-50 border border-blue-200 rounded-lg p-6 shadow-sm">
                <h3 className="font-bold text-gray-900 mb-3 flex items-center text-lg">
                  <i className="fas fa-info-circle text-blue-600 mr-2"></i>
                  Usage Instructions
                </h3>
                <div
                  className="text-sm text-gray-700 prose prose-sm max-w-none"
                  dangerouslySetInnerHTML={{ __html: selectedScript.usage }}
                />
              </div>
            </div>
          </DialogPanel>
        )}
      </DialogRoot>
    </div>
  );
};

export default DevOpsInTheBoxPage;
