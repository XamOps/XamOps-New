package com.xammer.cloud.domain.calculator;

import java.util.List;

/**
 * Domain model representing a form field in the calculator service configuration.
 * Supports various field types: select, select-dynamic, number, checkbox.
 */
public class FormField {
    private String name;
    private String type;
    private String label;
    private boolean required;
    private Object defaultValue;
    private List<String> options;
    private String apiEndpoint;
    private String dependsOn;
    private Number min;
    private Number max;
    private Number step;
    private String help;

    // Static factory methods for different field types
    public static FormField select(String name, String label, boolean required) {
        FormField field = new FormField();
        field.name = name;
        field.type = "select";
        field.label = label;
        field.required = required;
        return field;
    }

    public static FormField selectDynamic(String name, String label, boolean required) {
        FormField field = new FormField();
        field.name = name;
        field.type = "select-dynamic";
        field.label = label;
        field.required = required;
        return field;
    }

    public static FormField number(String name, String label, boolean required) {
        FormField field = new FormField();
        field.name = name;
        field.type = "number";
        field.label = label;
        field.required = required;
        return field;
    }

    public static FormField checkbox(String name, String label) {
        FormField field = new FormField();
        field.name = name;
        field.type = "checkbox";
        field.label = label;
        field.required = false;
        return field;
    }

    // Fluent builder methods
    public FormField options(List<String> options) {
        this.options = options;
        return this;
    }

    public FormField apiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
        return this;
    }

    public FormField dependsOn(String dependsOn) {
        this.dependsOn = dependsOn;
        return this;
    }

    public FormField defaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public FormField min(Number min) {
        this.min = min;
        return this;
    }

    public FormField max(Number max) {
        this.max = max;
        return this;
    }

    public FormField step(Number step) {
        this.step = step;
        return this;
    }

    public FormField help(String help) {
        this.help = help;
        return this;
    }

    // Getters
    public String getName() { return name; }
    public String getType() { return type; }
    public String getLabel() { return label; }
    public boolean isRequired() { return required; }
    public Object getDefaultValue() { return defaultValue; }
    public List<String> getOptions() { return options; }
    public String getApiEndpoint() { return apiEndpoint; }
    public String getDependsOn() { return dependsOn; }
    public Number getMin() { return min; }
    public Number getMax() { return max; }
    public Number getStep() { return step; }
    public String getHelp() { return help; }
}
