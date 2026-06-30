import React from 'react';
import type { Provider } from '../../types/addAccount';

interface ConnectionStepsProps {
  provider: Provider;
  currentStep?: number;
}

interface Step {
  title: string;
}

const stepsConfig: Record<string, Step[]> = {
  aws: [
    { title: 'Configure' },
    { title: 'Launch Stack' },
    { title: 'Deploy' },
    { title: 'Verify' },
  ],
  gcp: [
    { title: 'Service Account' },
    { title: 'Billing Export' },
    { title: 'Permissions' },
    { title: 'Connect' },
  ],
  azure: [
    { title: 'Setup Script' },
    { title: 'Credentials' },
    { title: 'Connect' },
  ],
  jenkins: [
    { title: 'Server URL' },
    { title: 'Connect' },
  ],
  github: [
    { title: 'Create PAT' },
    { title: 'Details' },
    { title: 'Connect' },
  ],
  default: [
    { title: 'Configure' },
    { title: 'Connect' },
  ],
};

const PROVIDER_COLORS: Record<string, string> = {
  aws: '#FF9900',
  gcp: '#4285F4',
  azure: '#0078D4',
  jenkins: '#D33833',
  github: '#181717',
  default: '#000000',
};

const ConnectionSteps: React.FC<ConnectionStepsProps> = ({ provider, currentStep = 0 }) => {
  const steps = stepsConfig[provider] || stepsConfig.default;
  const color = PROVIDER_COLORS[provider] || PROVIDER_COLORS.default;

  return (
    <div className="flex items-center gap-0">
      {steps.map((step, index) => {
        const isActive = index === currentStep;
        const isCompleted = index < currentStep;

        return (
          <React.Fragment key={index}>
            <div className="flex items-center gap-2">
              <div
                className="w-6 h-6 rounded-full flex items-center justify-center text-[10px] font-bold flex-shrink-0"
                style={
                  isCompleted ? { backgroundColor: color, color: 'white' } :
                  isActive ? { border: `1.5px solid ${color}`, color } :
                  { border: '1px solid rgba(0,0,0,0.15)', color: 'rgba(0,0,0,0.3)' }
                }
              >
                {isCompleted ? '✓' : index + 1}
              </div>
              <span className={`text-[11px] font-medium whitespace-nowrap ${
                isActive || isCompleted ? 'text-black' : 'text-[rgba(0,0,0,0.3)]'
              }`}>
                {step.title}
              </span>
            </div>
            {index < steps.length - 1 && (
              <div
                className="flex-1 h-px mx-3 min-w-[24px]"
                style={{ backgroundColor: isCompleted ? color : 'rgba(0,0,0,0.1)' }}
              />
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
};

export default ConnectionSteps;
