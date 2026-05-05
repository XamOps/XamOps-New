import React from 'react';
import { motion } from 'framer-motion';
import { Check, MapPin } from 'lucide-react';
import { cn } from '../../lib/utils';
import { Card, CardContent } from '../ui/card';
import type { Provider } from '../../types/addAccount';

interface ConnectionStepsProps {
  provider: Provider;
  currentStep?: number;
}

interface Step {
  title: string;
  description: string;
  icon: string;
}

const stepsConfig: Record<string, Step[]> = {
  aws: [
    { title: 'Configure Access', description: 'Set account name and permissions', icon: 'fas fa-cog' },
    { title: 'Launch Stack', description: 'Deploy CloudFormation template', icon: 'fas fa-rocket' },
    { title: 'Deploy in AWS', description: 'Create IAM role in your account', icon: 'fab fa-aws' },
    { title: 'Verify Connection', description: 'Complete setup and connect', icon: 'fas fa-check-circle' },
  ],
  gcp: [
    { title: 'Create Service Account', description: 'Run script in Cloud Shell', icon: 'fas fa-terminal' },
    { title: 'Enable Billing Export', description: 'Configure BigQuery export', icon: 'fas fa-file-invoice-dollar' },
    { title: 'Grant Permissions', description: 'Authorize service account', icon: 'fas fa-user-shield' },
    { title: 'Enter Details', description: 'Paste credentials & connect', icon: 'fas fa-check-circle' },
  ],
  azure: [
    { title: 'Select Azure', description: 'Choose Microsoft Azure', icon: 'fab fa-microsoft' },
    { title: 'Run Setup Script', description: 'Execute in Azure Cloud Shell', icon: 'fas fa-terminal' },
    { title: 'Enter Credentials', description: 'Paste JSON and connect', icon: 'fas fa-key' },
  ],
  jenkins: [
    { title: 'Server Details', description: 'Enter Jenkins URL', icon: 'fas fa-server' },
    { title: 'Authentication', description: 'Provide API credentials', icon: 'fas fa-lock' },
  ],
  github: [
    { title: 'Create PAT', description: 'Generate a Personal Access Token', icon: 'fas fa-key' },
    { title: 'Enter Details', description: 'Repo owner, name & token', icon: 'fab fa-github' },
    { title: 'Connect', description: 'Verify and save', icon: 'fas fa-check-circle' },
  ],
  default: [
    { title: 'Select Provider', description: 'Choose a cloud or service', icon: 'fas fa-cloud' },
    { title: 'Configure', description: 'Enter connection details', icon: 'fas fa-sliders-h' },
    { title: 'Connect', description: 'Verify and save', icon: 'fas fa-plug' },
  ],
};

const sidebarVariants = {
  hidden: { opacity: 0, x: -20 },
  visible: { opacity: 1, x: 0, transition: { duration: 0.4, ease: 'easeOut' as const } },
};

const stepVariants = {
  hidden: { opacity: 0, x: -10 },
  visible: (i: number) => ({
    opacity: 1, x: 0,
    transition: { delay: i * 0.08 + 0.15, duration: 0.3, ease: 'easeOut' as const },
  }),
};

const ConnectionSteps: React.FC<ConnectionStepsProps> = ({ provider, currentStep = 0 }) => {
  const steps = stepsConfig[provider] || stepsConfig.default;

  return (
    <motion.div variants={sidebarVariants} initial="hidden" animate="visible">
      <Card className="shadow-sm border-slate-200 overflow-hidden">
        <div className="h-1 w-full bg-gradient-to-r from-indigo-500 to-violet-500" />
        <CardContent className="p-5">
          {/* Header */}
          <div className="flex items-center gap-2 mb-5">
            <MapPin className="w-4 h-4 text-indigo-500" />
            <h3 className="text-xs font-bold text-slate-900 uppercase tracking-widest">Setup Guide</h3>
          </div>

          {/* Steps */}
          <div className="relative">
            {steps.map((step, index) => {
              const isActive = index === currentStep;
              const isCompleted = index < currentStep;
              const isLast = index === steps.length - 1;

              return (
                <motion.div
                  key={index}
                  className="flex gap-3 relative"
                  custom={index}
                  variants={stepVariants}
                  initial="hidden"
                  animate="visible"
                >
                  {/* Left: circle + connector */}
                  <div className="flex flex-col items-center flex-shrink-0">
                    {/* Step circle */}
                    <div className={cn(
                      'w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold transition-all duration-300 flex-shrink-0 z-10',
                      isCompleted
                        ? 'bg-gradient-to-br from-emerald-400 to-emerald-500 text-white shadow-md shadow-emerald-500/25'
                        : isActive
                          ? 'bg-gradient-to-br from-indigo-500 to-violet-500 text-white shadow-md shadow-indigo-500/30 ring-4 ring-indigo-500/15'
                          : 'bg-slate-100 text-slate-400 border-2 border-slate-200'
                    )}>
                      {isCompleted ? (
                        <Check className="w-3.5 h-3.5" strokeWidth={2.5} />
                      ) : (
                        <span>{index + 1}</span>
                      )}
                    </div>

                    {/* Connector line */}
                    {!isLast && (
                      <div className={cn(
                        'w-0.5 flex-1 mt-1 mb-1 min-h-[20px] rounded-full transition-all duration-500',
                        isCompleted ? 'bg-gradient-to-b from-emerald-400 to-emerald-300' : 'bg-slate-100'
                      )} />
                    )}
                  </div>

                  {/* Right: content */}
                  <div className={cn('pb-4 min-w-0', isLast && 'pb-0')}>
                    <p className={cn(
                      'text-sm font-semibold leading-tight mb-0.5 transition-colors duration-200',
                      isActive ? 'text-slate-900' : isCompleted ? 'text-emerald-700' : 'text-slate-400'
                    )}>
                      {step.title}
                    </p>
                    <p className={cn(
                      'text-xs leading-snug transition-colors duration-200',
                      isActive ? 'text-slate-500' : isCompleted ? 'text-emerald-600/70' : 'text-slate-300'
                    )}>
                      {step.description}
                    </p>
                  </div>
                </motion.div>
              );
            })}
          </div>

          {/* Progress footer */}
          <div className="mt-5 pt-4 border-t border-slate-100">
            <div className="flex items-center justify-between text-xs text-slate-400 mb-2">
              <span>Progress</span>
              <span className="font-semibold text-slate-600">{Math.round((currentStep / steps.length) * 100)}%</span>
            </div>
            <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden">
              <motion.div
                className="h-full bg-gradient-to-r from-indigo-500 to-violet-500 rounded-full"
                initial={{ width: 0 }}
                animate={{ width: `${(currentStep / steps.length) * 100}%` }}
                transition={{ duration: 0.5, ease: 'easeOut' }}
              />
            </div>
          </div>
        </CardContent>
      </Card>
    </motion.div>
  );
};

export default ConnectionSteps;
