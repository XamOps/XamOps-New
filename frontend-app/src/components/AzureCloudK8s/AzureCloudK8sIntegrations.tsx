import React from 'react';

const integrations = [
  {
    icon: 'fa-chart-area',
    iconBg: 'bg-blue-100',
    iconColor: 'text-blue-600',
    title: 'Azure Monitor for Containers',
    description: 'Full-stack monitoring for AKS with Container Insights, live metrics, and log analytics integration.',
  },
  {
    icon: 'fa-shield-alt',
    iconBg: 'bg-purple-100',
    iconColor: 'text-purple-600',
    title: 'Microsoft Defender for Containers',
    description: 'Advanced threat protection for AKS workloads, runtime security, and vulnerability assessment.',
  },
  {
    icon: 'fa-bolt',
    iconBg: 'bg-orange-100',
    iconColor: 'text-orange-600',
    title: 'KEDA — Event-driven Autoscaling',
    description: 'Scale your AKS workloads based on events from Azure Service Bus, Event Hubs, and 60+ other sources.',
  },
];

const AzureCloudK8sIntegrations: React.FC = () => {
  return (
    <div className="glass-card rounded-2xl p-6 md:p-8 fade-in">
      <h2 className="text-xl font-bold text-gray-800 mb-2">Available Integrations</h2>
      <p className="text-sm text-gray-500 mb-6">
        Enhance your AKS clusters with additional monitoring and security capabilities.
      </p>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {integrations.map((integration) => (
          <div
            key={integration.title}
            className="border border-gray-200 rounded-xl p-5 hover:border-blue-300 hover:shadow-sm transition-all"
          >
            <div className={`w-10 h-10 ${integration.iconBg} rounded-xl flex items-center justify-center mb-3`}>
              <i className={`fas ${integration.icon} ${integration.iconColor}`}></i>
            </div>
            <h3 className="font-semibold text-gray-800 text-sm mb-1">{integration.title}</h3>
            <p className="text-xs text-gray-500 mb-4 leading-relaxed">{integration.description}</p>
            <button
              disabled
              className="w-full px-3 py-2 text-xs font-semibold rounded-lg bg-gray-100 text-gray-400 cursor-not-allowed"
            >
              <i className="fas fa-clock mr-1"></i>
              Coming Soon
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};

export default AzureCloudK8sIntegrations;
