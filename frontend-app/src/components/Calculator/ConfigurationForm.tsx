/**
 * Configuration Form Component
 * Renders dynamic form fields based on selected service
 */

import React, { useState, useEffect } from 'react';
import type { ServiceConfig, FormField, FormValues, DynamicSelectOption } from '../../types/calculator';

interface ConfigurationFormProps {
  serviceConfig: ServiceConfig | null;
  region: string;
  loading: boolean;
  onSubmit: (values: FormValues) => void;
  onLoadDynamicOptions: (
    apiEndpoint: string,
    region: string,
    dependencies: Record<string, any>
  ) => Promise<DynamicSelectOption[]>;
}

export const ConfigurationForm: React.FC<ConfigurationFormProps> = ({
  serviceConfig,
  region,
  loading,
  onSubmit,
  onLoadDynamicOptions,
}) => {
  const [formValues, setFormValues] = useState<FormValues>({});
  const [dynamicOptions, setDynamicOptions] = useState<Record<string, DynamicSelectOption[]>>({});
  const [submitting, setSubmitting] = useState(false);

  // Reset form when service config changes
  useEffect(() => {
    if (serviceConfig) {
      const initialValues: FormValues = {};
      serviceConfig.fields.forEach((field) => {
        if (field.defaultValue !== undefined) {
          initialValues[field.name] = field.defaultValue;
        }
      });
      setFormValues(initialValues);
      setDynamicOptions({});
    }
  }, [serviceConfig]);

  // Load dynamic options for a field
  const loadOptionsForField = async (field: FormField) => {
    if (!field.apiEndpoint) return;

    const dependencies: Record<string, any> = {};
    if (field.dependsOn) {
      field.dependsOn.split(',').forEach((dep) => {
        dependencies[dep] = formValues[dep];
      });
    }

    const options = await onLoadDynamicOptions(field.apiEndpoint, region, dependencies);
    setDynamicOptions((prev) => ({
      ...prev,
      [field.name]: options,
    }));
  };

  // Handle input change
  const handleInputChange = (name: string, value: any, _field: FormField) => {
    setFormValues((prev) => ({
      ...prev,
      [name]: value,
    }));

    // Reload dependent dynamic selects
    if (serviceConfig) {
      serviceConfig.fields
        .filter((f) => f.type === 'select-dynamic' && f.dependsOn?.includes(name))
        .forEach((dependentField) => {
          loadOptionsForField(dependentField);
        });
    }
  };

  // Handle form submission
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    await onSubmit(formValues);
    setSubmitting(false);
  };

  // Render form field
  const renderField = (field: FormField) => {
    const commonClasses =
      'w-full bg-gray-50 border-0 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all text-gray-700';

    if (field.type === 'select') {
      const selectOptions = field.options || [];

      return (
        <div key={field.name} className="mb-4">
          <label
            htmlFor={field.name}
            className="block text-xs font-bold text-gray-400 uppercase tracking-widest mb-2"
          >
            {field.label}
            {field.required && ' *'}
          </label>
          <select
            id={field.name}
            name={field.name}
            className={commonClasses}
            value={formValues[field.name] as string || ''}
            onChange={(e) => handleInputChange(field.name, e.target.value, field)}
            required={field.required}
          >
            <option value="">-- Select --</option>
            {selectOptions.map((opt) => (
              <option key={opt} value={opt}>
                {opt}
              </option>
            ))}
          </select>
          {field.help && (
            <p className="mt-1.5 text-[11px] text-gray-400 leading-relaxed">{field.help}</p>
          )}
        </div>
      );
    }

    if (field.type === 'select-dynamic') {
      const dynamicOpts = dynamicOptions[field.name] || [];

      return (
        <div key={field.name} className="mb-4">
          <label
            htmlFor={field.name}
            className="block text-xs font-bold text-gray-400 uppercase tracking-widest mb-2"
          >
            {field.label}
            {field.required && ' *'}
          </label>
          <select
            id={field.name}
            name={field.name}
            className={commonClasses}
            value={formValues[field.name] as string || ''}
            onChange={(e) => handleInputChange(field.name, e.target.value, field)}
            onFocus={() => {
              if (dynamicOpts.length === 0) {
                loadOptionsForField(field);
              }
            }}
            required={field.required}
          >
            <option value="">-- Select --</option>
            {dynamicOpts.map((opt) => {
              const value = opt.instanceType || opt.code || '';
              const label = opt.description ? `${value} (${opt.description})` : value;
              return (
                <option key={value} value={value}>
                  {label}
                </option>
              );
            })}
          </select>
          {field.help && (
            <p className="mt-1.5 text-[11px] text-gray-400 leading-relaxed">{field.help}</p>
          )}
        </div>
      );
    }

    if (field.type === 'number') {
      return (
        <div key={field.name} className="mb-4">
          <label
            htmlFor={field.name}
            className="block text-xs font-bold text-gray-400 uppercase tracking-widest mb-2"
          >
            {field.label}
            {field.required && ' *'}
          </label>
          <input
            type="number"
            id={field.name}
            name={field.name}
            className={commonClasses}
            value={formValues[field.name] as number || ''}
            onChange={(e) => handleInputChange(field.name, e.target.valueAsNumber || null, field)}
            min={field.min}
            max={field.max}
            step={field.step}
            required={field.required}
          />
          {field.help && (
            <p className="mt-1.5 text-[11px] text-gray-400 leading-relaxed">{field.help}</p>
          )}
        </div>
      );
    }

    if (field.type === 'checkbox') {
      return (
        <div key={field.name} className="mb-4">
          <div className="flex items-center gap-3 py-2">
            <input
              type="checkbox"
              id={field.name}
              name={field.name}
              className="w-5 h-5 text-indigo-600 border-gray-300 rounded focus:ring-indigo-500 bg-gray-50 transition-all"
              checked={formValues[field.name] as boolean || false}
              onChange={(e) => handleInputChange(field.name, e.target.checked, field)}
            />
            <label htmlFor={field.name} className="text-sm font-medium text-gray-700 cursor-pointer">
              {field.label}
            </label>
          </div>
          {field.help && (
            <p className="mt-1 text-[11px] text-gray-400 leading-relaxed">{field.help}</p>
          )}
        </div>
      );
    }

    // Default text input
    return (
      <div key={field.name} className="mb-4">
        <label
          htmlFor={field.name}
          className="block text-xs font-bold text-gray-400 uppercase tracking-widest mb-2"
        >
          {field.label}
          {field.required && ' *'}
        </label>
        <input
          type="text"
          id={field.name}
          name={field.name}
          className={commonClasses}
          value={formValues[field.name] as string || ''}
          onChange={(e) => handleInputChange(field.name, e.target.value, field)}
          required={field.required}
        />
        {field.help && (
          <p className="mt-1.5 text-[11px] text-gray-400 leading-relaxed">{field.help}</p>
        )}
      </div>
    );
  };

  if (!serviceConfig) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 min-h-[300px]">
        <h2 className="text-lg font-semibold text-gray-800 mb-6 flex items-center">
          <span className="w-8 h-8 bg-indigo-50 text-indigo-600 rounded-lg flex items-center justify-center mr-3">
            <i className="fas fa-edit text-sm"></i>
          </span>
          Configuration
        </h2>
        <div className="flex flex-col items-center justify-center py-16 text-gray-400">
          <div className="w-16 h-16 bg-gray-50 rounded-full flex items-center justify-center mb-4">
            <i className="fas fa-mouse-pointer text-2xl"></i>
          </div>
          <p className="font-medium text-gray-600">Select a service to begin</p>
          <p className="text-xs">Dynamic fields will load based on your selection.</p>
        </div>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 min-h-[300px]">
        <h2 className="text-lg font-semibold text-gray-800 mb-6 flex items-center">
          <span className="w-8 h-8 bg-indigo-50 text-indigo-600 rounded-lg flex items-center justify-center mr-3">
            <i className="fas fa-edit text-sm"></i>
          </span>
          Configuration
        </h2>
        <div className="flex justify-center p-8">
          <i className="fas fa-circle-notch fa-spin text-indigo-500 text-2xl"></i>
        </div>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 min-h-[300px]">
      <h2 className="text-lg font-semibold text-gray-800 mb-6 flex items-center">
        <span className="w-8 h-8 bg-indigo-50 text-indigo-600 rounded-lg flex items-center justify-center mr-3">
          <i className="fas fa-edit text-sm"></i>
        </span>
        Configuration
      </h2>
      <form onSubmit={handleSubmit} className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {serviceConfig.fields.map((field) => {
          const fieldElement = renderField(field);
          if (field.type === 'checkbox') {
            return (
              <div key={field.name} className="md:col-span-2">
                {fieldElement}
              </div>
            );
          }
          return fieldElement;
        })}

        <div className="md:col-span-2 mt-4">
          <button
            type="submit"
            disabled={submitting}
            className="w-full bg-indigo-600 text-white py-3 rounded-xl font-bold hover:bg-indigo-700 transition-all shadow-lg shadow-indigo-100 flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? (
              <>
                <i className="fas fa-circle-notch fa-spin"></i> Calculating...
              </>
            ) : (
              <>
                <i className="fas fa-calculator"></i> Calculate Estimate
              </>
            )}
          </button>
        </div>
      </form>
    </div>
  );
};
