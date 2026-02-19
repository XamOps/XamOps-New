import React, { useState, useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface SpotAutomationHeaderProps {
  // Props removed - keeping interface for future use
}

const SpotAutomationHeader: React.FC<SpotAutomationHeaderProps> = () => {
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
            aria-label="How AutoSpotting works"
          >
            <i className="fas fa-question-circle"></i>
          </button>

          {showTooltip && (
            <div className="absolute right-0 top-full mt-2 z-50 w-80 bg-slate-900 text-white rounded-xl shadow-2xl p-5 animate-in fade-in slide-in-from-top-2 duration-200">
              <div className="flex items-center gap-2 mb-3">
                <i className="fas fa-lightbulb text-yellow-400"></i>
                <div className="font-bold text-sm text-slate-200">How AutoSpotting Works</div>
              </div>

              <div className="space-y-3 text-xs leading-relaxed text-slate-300">
                <p>
                  <strong className="text-white">Save up to 90% on EC2 costs!</strong> AutoSpotting automatically
                  replaces your expensive on-demand instances with much cheaper Spot instances.
                </p>

                <div className="bg-slate-800/50 rounded-lg p-3 space-y-2">
                  <div className="flex items-start gap-2">
                    <i className="fas fa-check-circle text-green-400 mt-0.5"></i>
                    <span><strong>Automatic:</strong> Just toggle on any ASG and we handle the rest</span>
                  </div>
                  <div className="flex items-start gap-2">
                    <i className="fas fa-check-circle text-green-400 mt-0.5"></i>
                    <span><strong>Safe:</strong> Your apps keep running smoothly during the switch</span>
                  </div>
                  <div className="flex items-start gap-2">
                    <i className="fas fa-check-circle text-green-400 mt-0.5"></i>
                    <span><strong>Smart:</strong> Uses backup instance types when needed</span>
                  </div>
                </div>

                <div className="bg-blue-900/30 border border-blue-700/30 rounded-lg p-3 mt-3">
                  <div className="flex items-center gap-2 mb-2">
                    <i className="fas fa-shield-alt text-blue-400"></i>
                    <strong className="text-blue-200 text-[11px]">How does switching work safely?</strong>
                  </div>
                  <div className="text-[10px] text-slate-400 space-y-1.5">
                    <p>
                      <strong className="text-slate-300">1. Gradual rollout:</strong> AWS replaces instances one by one, not all at once
                    </p>
                    <p>
                      <strong className="text-slate-300">2. Health checks first:</strong> New Spot instances must pass health checks before old ones are removed
                    </p>
                    <p>
                      <strong className="text-slate-300">3. Zero downtime:</strong> Your app stays online the entire time - users won't notice anything!
                    </p>
                  </div>
                </div>

                <div className="bg-amber-900/20 border border-amber-700/30 rounded-lg p-3 mt-3">
                  <div className="flex items-center gap-2 mb-2">
                    <i className="fas fa-info-circle text-amber-400"></i>
                    <strong className="text-amber-200 text-[11px]">What types of apps work best?</strong>
                  </div>
                  <div className="text-[10px] text-slate-400 space-y-1.5">
                    <p>
                      <strong className="text-green-300">✓ Stateless apps (Recommended):</strong> Web servers, API servers, microservices - perfect fit!
                    </p>
                    <p>
                      <strong className="text-yellow-300">⚠ Stateful apps:</strong> Use with EBS volumes or external storage (databases, file servers)
                    </p>
                  </div>
                </div>

                <p className="text-slate-400 text-[10px] pt-2 border-t border-slate-700">
                  💡 Tip: Click the <strong>Config</strong> button to set backup instance types for better availability
                </p>
              </div>
            </div>
          )}
        </div>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, showTooltip]);

  return null;
};

export default SpotAutomationHeader;
