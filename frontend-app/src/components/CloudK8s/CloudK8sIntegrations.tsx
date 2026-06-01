import React from 'react';
import { Icon } from '@iconify/react';

const INTEGRATIONS = [
  {
    icon: 'mdi:source-branch',
    iconColor: '#EA580C',
    iconBg: 'bg-orange-50 border-orange-100',
    title: 'ArgoCD GitOps',
    description: 'Automated continuous delivery. Sync your cluster state directly from your Git repositories.',
    hoverBorder: 'hover:border-orange-200',
  },
  {
    icon: 'mdi:chart-areaspline',
    iconColor: '#16A34A',
    iconBg: 'bg-emerald-50 border-emerald-100',
    title: 'Prometheus Metrics',
    description: 'Advanced metrics collection and alerting. Monitor cluster performance with custom queries.',
    hoverBorder: 'hover:border-emerald-200',
  },
  {
    icon: 'mdi:lan-connect',
    iconColor: '#7C3AED',
    iconBg: 'bg-purple-50 border-purple-100',
    title: 'Service Mesh',
    description: 'Istio service mesh integration. Advanced traffic management, security, and observability.',
    hoverBorder: 'hover:border-purple-200',
  },
] as const;

const CloudK8sIntegrations: React.FC = () => {
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
    </div>
  );
};

export default CloudK8sIntegrations;
