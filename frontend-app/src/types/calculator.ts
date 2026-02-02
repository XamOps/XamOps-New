/**
 * TypeScript types for the Cloud Pricing Calculator
 */

export interface Region {
  code: string;
  name: string;
}

export interface Service {
  code: string;
  name: string;
  category: string;
}

export interface FormField {
  name: string;
  label: string;
  type: 'text' | 'number' | 'select' | 'select-dynamic' | 'checkbox';
  required?: boolean;
  defaultValue?: any;
  min?: number;
  max?: number;
  step?: number;
  options?: string[];
  help?: string;
  apiEndpoint?: string;
  dependsOn?: string;
}

export interface ServiceConfig {
  serviceCode: string;
  serviceName: string;
  fields: FormField[];
  pricingEndpoint: string;
}

export interface PricingModel {
  name: string;
  monthlyCost?: number;
  monthlyTotal?: number;
  savings: string;
}

export interface ComputeResult {
  details?: string;
  instanceType?: string;
  monthlyCost: number;
}

export interface StorageResult {
  sizeGB?: number;
  gb?: number;
  monthlyTotal?: number;
  monthlyCost?: number;
}

export interface RequestsResult {
  monthlyTotal?: number;
  monthlyCost?: number;
}

export interface PricingResult {
  totalMonthly: number;
  compute?: ComputeResult;
  storage?: StorageResult;
  requests?: RequestsResult;
  pricingModels?: PricingModel[];
}

export interface EstimateCartItem {
  id: number;
  serviceName: string;
  region: string;
  monthlyCost: number;
  configSummary: string;
}

export interface DynamicSelectOption {
  instanceType?: string;
  code?: string;
  description?: string;
}

export interface FormValues {
  [key: string]: string | number | boolean | null;
}
