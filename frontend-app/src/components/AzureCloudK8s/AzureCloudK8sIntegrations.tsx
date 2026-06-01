import React from 'react';
import { Icon } from '@iconify/react';

const INTEGRATIONS = [
  {
    icon: 'mdi:chart-areaspline',
    iconColor: '#0078D4',
    iconBg: 'bg-blue-50 border-blue-100',
    title: 'Azure Monitor for Containers',
    description: 'Full-stack monitoring for AKS with Container Insights, live metrics, and log analytics integration.',
    hoverBorder: 'hover:border-blue-200',
  },
  {
    icon: 'mdi:shield-check-outline',
    iconColor: '#7C3AED',
    iconBg: 'bg-purple-50 border-purple-100',
    title: 'Microsoft Defender for Containers',
    description: 'Advanced threat protection for AKS workloads, runtime security, and vulnerability assessment.',
    hoverBorder: 'hover:border-purple-200',
  },
  {
    icon: 'mdi:lightning-bolt',
    iconColor: '#EA580C',
    iconBg: 'bg-orange-50 border-orange-100',
    title: 'KEDA — Event-driven Autoscaling',
    description: 'Scale your AKS workloads based on events from Azure Service Bus, Event Hubs, and 60+ other sources.',
    hoverBorder: 'hover:border-orange-200',
  },
] as const;

const AzureCloudK8sIntegrations: React.FC = () => {
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm p-5">
      <h2 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] mb-1 flex items-center gap-2">
        <Icon icon="mdi:puzzle-outline" width={15} color="#6366F1" />
        Available Integrations
      </h2>
      <p className="text-[12px] text-[rgba(0,0,0,0.45)] mb-4">
        Enhance your AKS clusters with additional monitoring and security capabilities.
      </p>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {INTEGRATIONS.map(item => (
          <div key={item.title}
            className={`border border-dashed border-[rgba(0,0,0,0.10)] ${item.hoverBorder} rounded-xl p-4 relative transition-all hover:shadow-sm`}>
            <div className="absolute top-0 right-0 bg-[rgba(0,0,0,0.75)] text-white text-[9px] font-bold px-2.5 py-1 rounded-bl-lg rounded-tr-xl">
              COMING SOON
            </div>
            <div className={`w-9 h-9 ${item.iconBg} border rounded-lg flex items-center justify-center mb-3`}>
              <Icon icon={item.icon} width={16} color={item.iconColor} />
            </div>
            <h3 className="font-semibold text-[rgba(0,0,0,0.88)] text-[13px] mb-1">{item.title}</h3>
            <p className="text-[12px] text-[rgba(0,0,0,0.45)] mb-4 leading-relaxed">{item.description}</p>
            <button disabled
              className="w-full px-3 py-2 text-[12px] font-semibold rounded-lg bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.35)] cursor-not-allowed border border-[rgba(0,0,0,0.07)] flex items-center justify-center gap-2">
              <Icon icon="mdi:clock-outline" width={12} />
              Coming Soon
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};

export default AzureCloudK8sIntegrations;
