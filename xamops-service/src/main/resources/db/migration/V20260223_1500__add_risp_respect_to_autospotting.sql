-- Add RI/SP Respect Configuration to AutoSpotting Settings
-- This allows users to configure AutoSpotting to respect Reserved Instance and Savings Plan commitments

ALTER TABLE autospotting_settings
ADD COLUMN IF NOT EXISTS respect_ri_sp_commitments BOOLEAN DEFAULT FALSE;

ALTER TABLE autospotting_settings
ADD COLUMN IF NOT EXISTS ri_sp_utilization_threshold INTEGER DEFAULT 90;

COMMENT ON COLUMN autospotting_settings.respect_ri_sp_commitments IS 
'When true, AutoSpotting checks RI/SP utilization before converting instances to Spot. Only converts when utilization >= threshold.';

COMMENT ON COLUMN autospotting_settings.ri_sp_utilization_threshold IS 
'RI/SP utilization threshold percentage (0-100). AutoSpotting only converts to Spot when RI+SP utilization >= this value. Default: 90%';
