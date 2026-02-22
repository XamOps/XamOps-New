-- Add Spot/On-Demand configuration columns to asg_spot_config table

-- Add spot_percentage column (0-100)
ALTER TABLE asg_spot_config
ADD COLUMN IF NOT EXISTS spot_percentage INTEGER;

-- Add on_demand_count column (fixed number of On-Demand instances)
ALTER TABLE asg_spot_config
ADD COLUMN IF NOT EXISTS on_demand_count INTEGER;

-- Add comments for documentation
COMMENT ON COLUMN asg_spot_config.spot_percentage IS 'Target percentage of Spot instances (0-100). Mutually exclusive with on_demand_count.';
COMMENT ON COLUMN asg_spot_config.on_demand_count IS 'Fixed number of On-Demand instances to maintain. All instances above this count will be Spot. Mutually exclusive with spot_percentage.';
