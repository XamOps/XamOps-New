/**
 * InventorySection Component
 *
 * Converted from: dashboard.html (resource inventory section)
 *
 * Displays 12 AWS resource type cards with counts and navigation links.
 * All logic preserved from original.
 */

import { useNavigate } from 'react-router-dom';
import type { ResourceInventory } from '../../types/liveDashboard';

interface InventorySectionProps {
  inventory: ResourceInventory;
  isLoading: boolean;
  getLink: (path: string) => string;
}

interface ResourceCardProps {
  name: string;
  count: number;
  icon: string;
  service: string;
  color: string;
  getLink: (path: string) => string;
}

const ResourceCard: React.FC<ResourceCardProps> = ({ name, count, icon, service, color }) => {
  const navigate = useNavigate();

  const handleClick = () => {
    // Navigate to the React cloudlist route with service parameter
    navigate(`/cloudlist?service=${service}`);
  };

  return (
    <button
      onClick={handleClick}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') handleClick(); }}
      aria-label={`View ${name} resources`}
      className="group p-4 rounded-xl border border-gray-100 bg-white hover:border-purple-200 hover:shadow-lg hover:shadow-purple-500/10 cursor-pointer transition-all duration-300 flex items-center justify-between active:scale-[0.98] w-full text-left"
    >
      <div className="flex items-center space-x-3">
        <div className={`w-12 h-12 ${color} rounded-xl flex items-center justify-center shadow-inner group-hover:scale-110 transition-transform duration-300`}>
          <img src={icon} alt={name} className="w-7 h-7 object-contain drop-shadow-sm" />
        </div>
        <div>
          <p className="text-xs font-black text-gray-400 uppercase tracking-widest leading-tight">{name}</p>
          <div className="flex items-center mt-1">
            <span className="text-[10px] text-purple-600 font-bold uppercase tracking-tight">Active Count</span>
            <svg className="w-2.5 h-2.5 ml-1 text-purple-400 opacity-0 group-hover:opacity-100 transition-opacity" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M13 7l5 5m0 0l-5 5m5-5H6" />
            </svg>
          </div>
        </div>
      </div>
      <div className="text-right ml-4">
        <p className="text-3xl font-black text-gray-900 tracking-tight">{count}</p>
      </div>
    </button>
  );
};

const InventorySection: React.FC<InventorySectionProps> = ({ inventory, isLoading, getLink }) => {
  if (!inventory) return null;

  const allResourceItems = [
    { name: 'EC2 Instances', count: inventory.ec2Count, icon: '/icons/ec2-instances.png', service: 'EC2', color: 'bg-orange-100' },
    { name: 'S3 Buckets', count: inventory.s3Count, icon: '/icons/s3-bucket.png', service: 'S3', color: 'bg-green-100' },
    { name: 'RDS Databases', count: inventory.rdsCount, icon: '/icons/rds-instance.png', service: 'RDS', color: 'bg-blue-100' },
    { name: 'VPC Networks', count: inventory.vpcCount, icon: '/icons/vpc.png', service: 'VPC', color: 'bg-purple-100' },
    { name: 'Lambda Functions', count: inventory.lambdaCount, icon: '/icons/aws-lambda.png', service: 'Lambda', color: 'bg-yellow-100' },
    { name: 'EBS Volumes', count: inventory.ebsCount, icon: '/icons/aws-ebs.png', service: 'EBS', color: 'bg-pink-100' },
    { name: 'Route53 Zones', count: inventory.route53Count, icon: '/icons/aws-route53.png', service: 'Route53', color: 'bg-indigo-100' },
    { name: 'Load Balancers', count: inventory.loadBalancerCount, icon: '/icons/load-balanceraws.png', service: 'ELB', color: 'bg-red-100' },
    { name: 'ECS Clusters', count: inventory.ecsCount, icon: '/icons/aws-ecs.png', service: 'ECS', color: 'bg-teal-100' },
    { name: 'EKS Clusters', count: inventory.eksCount, icon: '/icons/aws-eks.png', service: 'EKS', color: 'bg-cyan-100' },
    { name: 'Lightsail', count: inventory.lightsailCount, icon: '/icons/lightsail.png', service: 'Lightsail', color: 'bg-amber-100' },
    { name: 'Amplify Apps', count: inventory.amplifyCount, icon: '/icons/amplify.png', service: 'Amplify', color: 'bg-lime-100' },
  ];

  const resourceItems = allResourceItems.filter(item => item.count > 0);

  return (
    <div className="lg:col-span-2 glass-card p-6 rounded-2xl border border-gray-200 shadow-xl shadow-purple-500/5 relative overflow-hidden h-full">
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-purple-50 rounded-xl flex items-center justify-center border border-purple-100 shadow-sm">
            <svg className="w-6 h-6 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 012 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
            </svg>
          </div>
          <div>
            <h3 className="text-xl font-black text-gray-900 tracking-tight uppercase px-1">Resource Inventory</h3>
            <p className="text-[10px] text-gray-400 font-bold uppercase tracking-widest flex items-center gap-1.5 px-1 mt-0.5">
              <span className="w-1.5 h-1.5 bg-purple-400 rounded-full animate-pulse"></span>&nbsp;Live Infrastructure
            </p>
          </div>
        </div>
        <div className="text-sm text-gray-500 bg-white/50 backdrop-blur-sm p-2 rounded-lg border border-gray-100 shadow-sm">
          <svg className={`w-4 h-4 text-purple-500 ${isLoading ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
        </div>
      </div>

      {/* Inventory Skeleton */}
      {isLoading && (
        <div className="space-y-4 py-2 px-1">
          {[1, 2, 3, 4, 5].map((i) => (
            <div key={i} className="h-16 w-full skeleton rounded-xl"></div>
          ))}
        </div>
      )}

      {/* Inventory Grid */}
      {!isLoading && (
        <div
          className="flex flex-col gap-4 overflow-y-auto pb-2 custom-scrollbar fade-in px-1"
          style={{ maxHeight: '420px' }}
        >
          {resourceItems.length > 0 ? (
            resourceItems.map(resource => (
              <ResourceCard key={resource.service} {...resource} getLink={getLink} />
            ))
          ) : (
            <div className="col-span-full py-12 text-center">
              <div className="w-16 h-16 bg-gray-50 rounded-full flex items-center justify-center mx-auto mb-4 border border-gray-100">
                <svg className="w-8 h-8 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
                </svg>
              </div>
              <p className="text-gray-500 font-bold text-xs uppercase tracking-widest">No Active Resources</p>
              <p className="text-[10px] text-gray-400 mt-1">Found in this account</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default InventorySection;
