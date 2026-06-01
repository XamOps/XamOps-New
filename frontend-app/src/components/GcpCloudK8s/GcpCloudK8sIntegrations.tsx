import React from 'react';
import { Icon } from '@iconify/react';

const INTEGRATIONS = [
  {
    icon: 'mdi:git',
    iconColor: '#3B82F6',
    iconBg: 'bg-blue-50 border-blue-100',
    title: 'Config Sync',
    description: 'GitOps for GKE. Automatically sync your cluster configurations from Git repositories.',
    hoverBorder: 'hover:border-blue-200',
  },
  {
    icon: 'mdi:chart-areaspline',
    iconColor: '#16A34A',
    iconBg: 'bg-emerald-50 border-emerald-100',
    title: 'Cloud Operations',
    description: 'Integrated monitoring, logging, and tracing powered by Google Cloud Operations Suite.',
    hoverBorder: 'hover:border-emerald-200',
  },
  {
    icon: 'mdi:robot-outline',
    iconColor: '#7C3AED',
    iconBg: 'bg-purple-50 border-purple-100',
    title: 'GKE Autopilot',
    description: 'Hands-off cluster management with optimized configuration and automatic scaling.',
    hoverBorder: 'hover:border-purple-200',
  },
] as const;

const GcpCloudK8sIntegrations: React.FC = () => {
  return (
    <div>
      <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] mb-4 flex items-center gap-2">
        <Icon icon="mdi:puzzle-outline" width={15} color="#6366F1" />
        Available Integrations
      </h3>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {INTEGRATIONS.map(item => (
          <div key={item.title}
            className={`bg-white border border-dashed border-[rgba(0,0,0,0.12)] ${item.hoverBorder} rounded-xl p-5 relative transition-all hover:shadow-sm`}>
            <div className="absolute top-0 right-0 bg-[rgba(0,0,0,0.75)] text-white text-[9px] font-bold px-2.5 py-1 rounded-bl-lg rounded-tr-xl">
              COMING SOON
            </div>
            <div className={`w-10 h-10 ${item.iconBg} border rounded-lg flex items-center justify-center mb-4`}>
              <Icon icon={item.icon} width={18} color={item.iconColor} />
            </div>
            <h4 className="font-bold text-[rgba(0,0,0,0.88)] text-[14px] mb-1.5">{item.title}</h4>
            <p className="text-[12px] text-[rgba(0,0,0,0.45)] mb-5 leading-relaxed">{item.description}</p>
            <button disabled
              className="w-full py-2 rounded-lg bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.35)] text-[12px] font-semibold cursor-not-allowed border border-[rgba(0,0,0,0.07)] flex items-center justify-center gap-2">
              <Icon icon="mdi:clock-outline" width={13} />
              Integration Pending
            </button>
          </div>
        ))}
      </div>

      <div className="mt-4 p-4 bg-blue-50 border border-blue-100 rounded-xl flex items-start gap-3">
        <Icon icon="mdi:information-outline" width={15} color="#3B82F6" className="mt-0.5 flex-shrink-0" />
        <div>
          <h5 className="font-bold text-[rgba(0,0,0,0.75)] text-[13px] mb-1">More Integrations Coming Soon</h5>
          <p className="text-[12px] text-[rgba(0,0,0,0.55)] leading-relaxed">
            We're working on Cloud Build, Artifact Registry, Binary Authorization, and Policy Controller. Stay tuned!
          </p>
        </div>
      </div>
    </div>
  );
};

export default GcpCloudK8sIntegrations;
