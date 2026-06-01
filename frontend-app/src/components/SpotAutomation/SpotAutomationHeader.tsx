import React, { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useTopbar } from '../../contexts/TopbarContext';

const SpotAutomationHeader: React.FC = () => {
  const { setActions, clearActions } = useTopbar();
  const [showTooltip, setShowTooltip] = useState(false);

  useEffect(() => {
    const CF_TEMPLATE_URL = 'https://xamops-cloudformation-templates.s3.us-east-1.amazonaws.com/autospotting-customer.yaml';
    const cfConsoleUrl = `https://console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks/create/review?templateURL=${encodeURIComponent(CF_TEMPLATE_URL)}&stackName=autospotting-customer`;

    setActions(
      <div className="flex items-center gap-2">
        <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
        <span className="text-[11px] font-medium uppercase tracking-wide text-[rgba(0,0,0,0.4)]">AutoSpotting</span>
        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>

        <button
          onClick={() => window.open(cfConsoleUrl, '_blank', 'noopener,noreferrer')}
          className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
          title="Deploy AutoSpotting CloudFormation Stack"
        >
          <Icon icon="mdi:aws" width={13} color="#FF9900" />
          Deploy CF Stack
        </button>

        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>

        <div className="relative">
          <button
            onMouseEnter={() => setShowTooltip(true)}
            onMouseLeave={() => setShowTooltip(false)}
            className="flex items-center gap-1 text-[11px] font-semibold text-[rgba(0,0,0,0.4)] hover:text-indigo-600 transition-colors"
            aria-label="How AutoSpotting works"
          >
            <Icon icon="mdi:help-circle-outline" width={13} />
            How it works
          </button>

          {showTooltip && (
            <div className="absolute right-0 top-full mt-2 z-50 w-80 bg-[rgba(0,0,0,0.88)] text-white rounded-xl shadow-2xl p-5">
              <div className="flex items-center gap-2 mb-3">
                <Icon icon="mdi:lightbulb-outline" width={14} color="#FBBF24" />
                <div className="font-bold text-[13px] text-white">How AutoSpotting Works</div>
              </div>

              <div className="space-y-3 text-[11px] leading-relaxed text-[rgba(255,255,255,0.7)]">
                <p>
                  <strong className="text-white">Save up to 90% on EC2 costs!</strong> AutoSpotting automatically
                  replaces your expensive on-demand instances with much cheaper Spot instances.
                </p>

                <div className="bg-white/5 rounded-lg p-3 space-y-2">
                  {[
                    { text: <><strong>Automatic:</strong> Just toggle on any ASG and we handle the rest</> },
                    { text: <><strong>Safe:</strong> Your apps keep running smoothly during the switch</> },
                    { text: <><strong>Smart:</strong> Uses backup instance types when needed</> },
                  ].map((item, i) => (
                    <div key={i} className="flex items-start gap-2">
                      <Icon icon="mdi:check-circle" width={12} color="#4ADE80" className="mt-0.5 flex-shrink-0" />
                      <span>{item.text}</span>
                    </div>
                  ))}
                </div>

                <div className="bg-blue-900/30 border border-blue-700/30 rounded-lg p-3">
                  <div className="flex items-center gap-2 mb-2">
                    <Icon icon="mdi:shield-check-outline" width={12} color="#93C5FD" />
                    <strong className="text-blue-200 text-[10px]">How does switching work safely?</strong>
                  </div>
                  <div className="text-[10px] space-y-1.5 text-[rgba(255,255,255,0.5)]">
                    <p><strong className="text-[rgba(255,255,255,0.7)]">1. Gradual rollout:</strong> AWS replaces instances one by one</p>
                    <p><strong className="text-[rgba(255,255,255,0.7)]">2. Health checks first:</strong> New Spot instances must pass before old ones are removed</p>
                    <p><strong className="text-[rgba(255,255,255,0.7)]">3. Zero downtime:</strong> Your app stays online the entire time</p>
                  </div>
                </div>

                <div className="bg-amber-900/20 border border-amber-700/30 rounded-lg p-3">
                  <div className="flex items-center gap-2 mb-2">
                    <Icon icon="mdi:information-outline" width={12} color="#FCD34D" />
                    <strong className="text-amber-200 text-[10px]">What types of apps work best?</strong>
                  </div>
                  <div className="text-[10px] space-y-1.5 text-[rgba(255,255,255,0.5)]">
                    <p><strong className="text-green-300">✓ Stateless apps (Recommended):</strong> Web servers, APIs, microservices</p>
                    <p><strong className="text-yellow-300">⚠ Stateful apps:</strong> Use with EBS volumes or external storage</p>
                  </div>
                </div>

                <p className="text-[10px] text-[rgba(255,255,255,0.35)] pt-2 border-t border-white/10">
                  Tip: Click the <strong className="text-[rgba(255,255,255,0.55)]">Config</strong> button to set backup instance types for better availability
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
