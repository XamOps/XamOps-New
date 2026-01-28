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
    <div
      onClick={handleClick}
      className="resource-card p-4 rounded-lg border border-gray-200 flex items-center justify-between cursor-pointer hover:shadow-md transition-all"
    >
      <div className="flex items-center space-x-3">
        <div className={`resource-icon w-10 h-10 ${color} rounded-lg flex items-center justify-center`}>
          <img src={icon} alt={name} className="w-6 h-6" />
        </div>
        <div>
          <p className="text-sm font-semibold text-gray-800">{name}</p>
          <p className="text-xs text-gray-500">Click to view</p>
        </div>
      </div>
      <div className="text-right">
        <p className="text-2xl font-bold text-gray-800">{count}</p>
        <p className="text-xs text-gray-500">resources</p>
      </div>
    </div>
  );
};

const InventorySection: React.FC<InventorySectionProps> = ({ inventory, isLoading, getLink }) => {
  const resourceItems = [
    { name: 'EC2 Instances', count: inventory.ec2Count, icon: '/icons/ec2.png', service: 'EC2', color: 'bg-orange-100' },
    { name: 'S3 Buckets', count: inventory.s3Count, icon: '/icons/s3.png', service: 'S3', color: 'bg-green-100' },
    { name: 'RDS Databases', count: inventory.rdsCount, icon: '/icons/rds.png', service: 'RDS', color: 'bg-blue-100' },
    { name: 'VPC Networks', count: inventory.vpcCount, icon: '/icons/vpc.png', service: 'VPC', color: 'bg-purple-100' },
    { name: 'Lambda Functions', count: inventory.lambdaCount, icon: '/icons/lambda.png', service: 'Lambda', color: 'bg-yellow-100' },
    { name: 'EBS Volumes', count: inventory.ebsCount, icon: '/icons/ebs.png', service: 'EBS', color: 'bg-pink-100' },
    { name: 'Route53 Zones', count: inventory.route53Count, icon: '/icons/route53.png', service: 'Route53', color: 'bg-indigo-100' },
    { name: 'Load Balancers', count: inventory.loadBalancerCount, icon: '/icons/elb.png', service: 'ELB', color: 'bg-red-100' },
    { name: 'ECS Clusters', count: inventory.ecsCount, icon: '/icons/ecs.png', service: 'ECS', color: 'bg-teal-100' },
    { name: 'EKS Clusters', count: inventory.eksCount, icon: '/icons/eks.png', service: 'EKS', color: 'bg-cyan-100' },
    { name: 'Lightsail', count: inventory.lightsailCount, icon: '/icons/lightsail.png', service: 'Lightsail', color: 'bg-amber-100' },
    { name: 'Amplify Apps', count: inventory.amplifyCount, icon: '/icons/amplify.png', service: 'Amplify', color: 'bg-lime-100' },
  ];

  return (
    <div className="lg:col-span-2 chart-container p-6 rounded-xl relative">
      <div className="flex items-center justify-between mb-6">
        <h3 className="text-lg font-bold text-gray-800 flex items-center">
          <i className="fas fa-arrows-alt widget-handle mr-3 text-gray-400"></i>
          <i className="fas fa-server mr-2 text-purple-500"></i>
          Resource Inventory
        </h3>
        <div className="text-sm text-gray-500">
          <i className={`fas fa-sync-alt ${isLoading ? 'fa-spin' : ''}`}></i>
        </div>
      </div>

      {/* Inventory Skeleton */}
      {isLoading && (
        <div className="absolute inset-x-0 bottom-0 top-16 z-10 p-6 space-y-4 bg-white/80 backdrop-blur-sm rounded-b-xl">
          {[1, 2, 3, 4, 5].map(i => (
            <div key={i} className="h-12 w-full skeleton rounded-lg"></div>
          ))}
        </div>
      )}

      {/* Inventory Grid */}
      {!isLoading && (
        <div
          className="grid grid-cols-1 gap-3 custom-scrollbar fade-in"
          style={{ maxHeight: '400px', overflowY: 'auto' }}
        >
          {resourceItems.map(resource => (
            <ResourceCard key={resource.service} {...resource} getLink={getLink} />
          ))}
        </div>
      )}
    </div>
  );
};

export default InventorySection;
