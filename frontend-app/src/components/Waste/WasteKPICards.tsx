/**
 * WasteKPICards Component
 *
 * KPI cards showing potential savings, wasted resources count, and highest waste service
 */

import React from 'react';
import type { WastedResource, HighestWasteService } from '../../types/waste';

interface WasteKPICardsProps {
  wastedResources: WastedResource[];
  isLoading: boolean;
}

const getServiceIcon = (serviceType: string): string => {
  const iconMap: Record<string, string> = {
    'EC2 Instance': 'ec2-instances.png',
    'EBS Volume': 'aws-ebs.png',
    'RDS Instance': 'rds-instance.png',
    'Lambda': 'aws-lambda.png',
    'S3 Bucket': 's3-bucket.png',
    'Load Balancer': 'load-balanceraws.png',
    'Auto Scaling Group': 'autoscaling.png',
    'VPC': 'vpc.png',
    'Security Group': 'security-group.png',
    'Route 53 Zone': 'aws-route53.png',
    'ElastiCache Cluster': 'elasticache-cluster.png',
    'DynamoDB Table': 'dynamodb-table.png',
    'ECR Repository': 'ecr-repository.png',
    'CloudTrail': 'cloudtrail.png',
    'Certificate Manager': 'certificate-manager.png',
    'CloudWatch Log Group': 'cloudwatch-log-group.png',
    'SNS Topic': 'sns-topic.png',
    'SQS Queue': 'sqs-queue.png',
    'Elastic IP': 'elastic-ip.png',
    'Snapshot': 'snapshot.png',
    'AMI': 'ami.png',
    'ENI': 'eni.png',
    'EKS Cluster': 'aws-eks.png',
    'ECS Cluster': 'aws-ecs.png',
    'DB Snapshot': 'rds-instance.png',
    'Log Group': 'cloudwatch-log-group.png',
  };
  return iconMap[serviceType] || 'default-icon.png';
};

const WasteKPICards: React.FC<WasteKPICardsProps> = ({ wastedResources, isLoading }) => {
  // Calculate potential savings
  const potentialSavings = wastedResources.reduce((sum, r) => sum + r.monthlySavings, 0);

  // Calculate resource count
  const resourceCount = wastedResources.length;

  // Calculate highest waste service
  const getHighestWasteService = (): HighestWasteService | null => {
    if (wastedResources.length === 0) return null;

    const serviceGroups = wastedResources.reduce((acc, resource) => {
      const serviceType = resource.resourceType || 'Other';
      if (!acc[serviceType]) {
        acc[serviceType] = {
          serviceType: serviceType,
          totalCost: 0,
          count: 0,
        };
      }
      acc[serviceType].totalCost += resource.monthlySavings || 0;
      acc[serviceType].count += 1;
      return acc;
    }, {} as Record<string, HighestWasteService>);

    const services = Object.values(serviceGroups);
    if (services.length === 0) return null;

    return services.reduce((max, service) => (service.totalCost > max.totalCost ? service : max));
  };

  const highestWasteService = getHighestWasteService();

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
      {/* Potential Savings Card */}
      <div className="kpi-card p-6 fade-in">
        <h3 className="text-gray-600 text-xs font-medium uppercase tracking-wide mb-2">
          Potential Savings
        </h3>
        <p className="text-4xl font-bold text-red-600 mb-1">
          {isLoading ? '...' : `$${potentialSavings.toFixed(2)}`}
        </p>
        <p className="text-xs text-gray-500">per month</p>
      </div>

      {/* Wasted Resources Card */}
      <div className="kpi-card p-6 fade-in">
        <h3 className="text-gray-600 text-xs font-medium uppercase tracking-wide mb-2">
          Wasted Resources
        </h3>
        <p className="text-4xl font-bold text-red-600 mb-1">
          {isLoading ? '...' : resourceCount}
        </p>
        <p className="text-xs text-gray-500">items found</p>
      </div>

      {/* Highest Waste Cost Card */}
      <div className="kpi-card p-6 fade-in">
        <h3 className="text-gray-600 text-xs font-medium uppercase tracking-wide mb-3">
          Highest Waste Cost
        </h3>
        {!isLoading && highestWasteService ? (
          <div>
            <div className="flex items-center space-x-3 mb-3">
              <div className="w-12 h-12 flex items-center justify-center rounded-lg bg-gradient-to-br from-gray-50 to-gray-100 shadow-sm">
                <img
                  src={`/icons/${getServiceIcon(highestWasteService.serviceType)}`}
                  alt={highestWasteService.serviceType}
                  className="w-10 h-10 rounded-lg object-cover"
                  onError={(e) => {
                    (e.target as HTMLImageElement).src = '/icons/default-icon.png';
                  }}
                />
              </div>
              <div className="flex-1">
                <p className="text-sm font-bold text-gray-800">
                  {highestWasteService.serviceType}
                </p>
                <p className="text-xs text-gray-500">
                  {highestWasteService.count} resource{highestWasteService.count !== 1 ? 's' : ''}
                </p>
              </div>
            </div>
            <div className="flex items-baseline space-x-1">
              <span className="text-2xl font-bold text-red-600">$</span>
              <span className="text-3xl font-bold text-red-600">
                {highestWasteService.totalCost.toFixed(2)}
              </span>
              <span className="text-xs text-gray-500">/month</span>
            </div>
          </div>
        ) : (
          <div className="text-center py-4">
            <p className="text-2xl text-gray-400">...</p>
          </div>
        )}
      </div>
    </div>
  );
};

export default WasteKPICards;
