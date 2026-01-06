// Basic AJAX helper
async function apiGet(url, params = {}) {
    const query = new URLSearchParams(params).toString();
    const fullUrl = query ? `${url}?${query}` : url;

    const response = await fetch(fullUrl, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error(`API error ${response.status}: ${text}`);
    }

    return response.json();
}

/**
 * Render a field based on FormField JSON
 * Updated to use Tailwind CSS classes matching the XamOps theme
 */
function renderFormField(field) {
    // Wrapper for the field - uses a standard vertical margin
    const wrapper = document.createElement('div');
    wrapper.className = 'mb-4';
    wrapper.dataset.fieldName = field.name;

    // Create Label with bold, small, uppercase gray text for a professional look
    const label = document.createElement('label');
    label.className = 'block text-xs font-bold text-gray-400 uppercase tracking-widest mb-2';
    label.textContent = field.label + (field.required ? ' *' : '');
    label.htmlFor = field.name;
    wrapper.appendChild(label);

    let input;
    // Common classes for all text/select inputs
    const commonInputClasses = 'w-full bg-gray-50 border-0 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all text-gray-700';

    if (field.type === 'select' || field.type === 'select-dynamic') {
        input = document.createElement('select');
        input.className = commonInputClasses;
        
        const optPlaceholder = document.createElement('option');
        optPlaceholder.value = '';
        optPlaceholder.textContent = '-- Select --';
        input.appendChild(optPlaceholder);

        if (field.type === 'select' && Array.isArray(field.options)) {
            field.options.forEach(opt => {
                const o = document.createElement('option');
                o.value = opt;
                o.textContent = opt;
                input.appendChild(o);
            });
        }
    } else if (field.type === 'number') {
        input = document.createElement('input');
        input.type = 'number';
        input.className = commonInputClasses;

        if (field.defaultValue !== undefined) input.value = field.defaultValue;
        if (field.min !== undefined) input.min = field.min;
        if (field.max !== undefined) input.max = field.max;
        if (field.step !== undefined) input.step = field.step;
    } else if (field.type === 'checkbox') {
        // Wrapper for checkbox to align label and box horizontally
        const checkWrapper = document.createElement('div');
        checkWrapper.className = 'flex items-center gap-3 py-2';
        
        input = document.createElement('input');
        input.type = 'checkbox';
        input.className = 'w-5 h-5 text-indigo-600 border-gray-300 rounded focus:ring-indigo-500 bg-gray-50 transition-all';
        
        // Remove standard label and append it after checkbox for logic
        label.className = 'text-sm font-medium text-gray-700 cursor-pointer';
        
        checkWrapper.appendChild(input);
        checkWrapper.appendChild(label);
        
        if (field.defaultValue === true) input.checked = true;
        
        input.name = field.name;
        input.id = field.name;
        
        if (field.help) {
            const helpText = document.createElement('p');
            helpText.className = 'mt-1 text-[11px] text-gray-400 leading-relaxed';
            helpText.textContent = field.help;
            const container = document.createElement('div');
            container.appendChild(checkWrapper);
            container.appendChild(helpText);
            return container;
        }
        
        return checkWrapper;
    } else {
        input = document.createElement('input');
        input.type = 'text';
        input.className = commonInputClasses;
    }

    input.name = field.name;
    input.id = field.name;

    if (field.required) {
        input.required = true;
    }

    wrapper.appendChild(input);

    if (field.help) {
        const small = document.createElement('p');
        small.className = 'mt-1.5 text-[11px] text-gray-400 leading-relaxed';
        small.textContent = field.help;
        wrapper.appendChild(small);
    }

    return wrapper;
}

// Reads a form container and returns { fieldName: value }
function readFormValues(container) {
    const data = {};
    const inputs = container.querySelectorAll('input, select, textarea');

    inputs.forEach(input => {
        if (!input.name) return;

        if (input.type === 'checkbox') {
            data[input.name] = input.checked;
        } else if (input.type === 'number') {
            const val = input.value.trim();
            data[input.name] = val === '' ? null : Number(val);
        } else {
            data[input.name] = input.value.trim();
        }
    });

    return data;
}

// Build query params from form values (remove null/empty)
function buildQueryParams(values) {
    const params = {};
    Object.keys(values).forEach(key => {
        const v = values[key];
        if (v !== null && v !== '' && v !== undefined) {
            params[key] = v;
        }
    });
    return params;
}