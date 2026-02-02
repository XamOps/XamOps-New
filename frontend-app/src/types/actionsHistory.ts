/**
 * TypeScript interfaces for Actions History
 *
 * Data structures for AutoSpotting events and actions history
 */

export interface ActionsHistoryEvent {
  timestamp: string;
  event_type: string;
  asg_name: string;
  region: string;
  old_instance_id?: string;
  old_instance_type?: string;
  old_lifecycle?: string;
  old_hourly_price?: number;
  new_instance_id?: string;
  new_instance_type?: string;
  new_lifecycle?: string;
  new_hourly_price?: number;
  estimated_savings?: number;
  reason?: string;
}

export interface ActionsHistorySummary {
  total_replacements: number;
  total_interruptions: number;
  total_estimated_savings: number;
  mtbi?: string;
  mtbiColor?: string;
}

export interface ActionsHistoryData {
  events: ActionsHistoryEvent[];
  summary: ActionsHistorySummary;
}

export interface ActionsHistoryFilters {
  startDate: string;
  endDate: string;
  eventType: string;
  asgName: string;
}
