// Backend base
const AWS_API_ROOT = 'http://localhost:8080';
const AWS_API_BASE = `${AWS_API_ROOT}/api/aws/calculator`;

// In-memory estimate cart (initialized from localStorage)
let estimateCart = [];

document.addEventListener('DOMContentLoaded', () => {
  loadCart();
  initAwsCalculator();
});

// Generic GET with timeout + errors
async function apiGet(url, params = {}, timeoutMs = 10000) {
  const query = new URLSearchParams(params).toString();
  const fullUrl = query ? `${url}?${query}` : url;

  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const resp = await fetch(fullUrl, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal
    });
    if (!resp.ok) {
      const text = await resp.text();
      throw new Error(`API ${resp.status}: ${text}`);
    }
    return resp.json();
  } finally {
    clearTimeout(t);
  }
}

async function initAwsCalculator() {
  const providerSelect = document.getElementById('cloudProviderSelect');
  const regionSelect = document.getElementById('awsRegionSelect');
  const serviceSelect = document.getElementById('awsServiceSelect');
  const formContainer = document.getElementById('awsServiceFormContainer');
  const pricingResultContainer = document.getElementById('awsPricingResult');

  if (!providerSelect || !regionSelect || !serviceSelect) return;

  if (providerSelect.value === 'AWS') {
    await loadAwsRegions(regionSelect);
    await loadAwsServices(serviceSelect);
  }

  serviceSelect.addEventListener('change', async () => {
    const serviceCode = serviceSelect.value;
    const region = regionSelect.value;
    if (!serviceCode || !region) return;
    await loadServiceForm(serviceCode, region, formContainer, pricingResultContainer);
  });

  renderEstimateCart();
}

async function loadAwsRegions(regionSelect) {
  try {
    const data = await apiGet(`${AWS_API_BASE}/regions`, { accountId: 'default' });
    const regions = data.regions || [];
    
    if (regions.length === 0) throw new Error("Empty regions");

    regionSelect.innerHTML = '';
    regions.forEach(r => {
      const opt = document.createElement('option');
      opt.value = r.code;
      opt.textContent = r.name;
      regionSelect.appendChild(opt);
    });
  } catch (e) {
    // FALLBACK: Manual standard regions so the UI doesn't break
    regionSelect.innerHTML = `
      <option value="us-east-1">US East (N. Virginia)</option>
      <option value="ap-south-1" selected>Asia Pacific (Mumbai)</option>
      <option value="eu-west-1">EU (Ireland)</option>
    `;
  }
}

async function loadAwsServices(serviceSelect) {
  try {
    const data = await apiGet(`${AWS_API_BASE}/services`);
    const services = data.services || [];
    serviceSelect.innerHTML = '<option value="">-- Select Service --</option>';
    services.forEach(svc => {
      // Safety check: Don't render if name is missing or "undefined"
      if (svc.name && svc.name !== "undefined") {
      const opt = document.createElement('option');
      opt.value = svc.code;
      opt.textContent = `${svc.name} (${svc.category})`;
      serviceSelect.appendChild(opt);
      }
    });
  } catch (e) {
    console.error("Error loading services", e);
  }
}

async function loadServiceForm(serviceCode, region, formContainer, pricingResultContainer) {
  formContainer.innerHTML = '<div class="flex justify-center p-8"><i class="fas fa-circle-notch fa-spin text-indigo-500 text-2xl"></i></div>';
  pricingResultContainer.innerHTML = '';

  try {
    const config = await apiGet(`${AWS_API_BASE}/service-config/${serviceCode}`);
    const fields = config.fields || [];
    const formEl = document.createElement('form');
    formEl.id = 'awsServiceConfigForm';
    formEl.className = 'grid grid-cols-1 md:grid-cols-2 gap-4';

    fields.forEach(field => {
      const fieldEl = renderFormField(field); 
      if (field.type === 'checkbox') fieldEl.classList.add('md:col-span-2');
      formEl.appendChild(fieldEl);
    });

    const btnWrapper = document.createElement('div');
    btnWrapper.className = 'md:col-span-2 mt-4';

    const submitBtn = document.createElement('button');
    submitBtn.type = 'submit';
    submitBtn.className = 'w-full bg-indigo-600 text-white py-3 rounded-xl font-bold hover:bg-indigo-700 transition-all shadow-lg shadow-indigo-100 flex items-center justify-center gap-2';
    submitBtn.innerHTML = '<i class="fas fa-calculator"></i> Calculate Estimate';
    
    btnWrapper.appendChild(submitBtn);
    formEl.appendChild(btnWrapper);

    formContainer.innerHTML = '';
    formContainer.appendChild(formEl);

    setupDynamicSelects(formEl, config, region);

    formEl.addEventListener('submit', async (e) => {
      e.preventDefault();
      submitBtn.disabled = true;
      submitBtn.innerHTML = '<i class="fas fa-circle-notch fa-spin"></i> Calculating...';
      const currentRegion = document.getElementById('awsRegionSelect').value;
      await onSubmitServiceForm(config, currentRegion, formEl, pricingResultContainer);
      submitBtn.disabled = false;
      submitBtn.innerHTML = '<i class="fas fa-calculator"></i> Calculate Estimate';
    });
  } catch (e) {
    formContainer.innerHTML = `<div class="p-4 bg-red-50 text-red-600 rounded-xl text-sm">Error: ${e.message}</div>`;
  }
}

function setupDynamicSelects(formEl, config, region) {
  config.fields?.filter(f => f.type === 'select-dynamic').forEach(field => {
    const selectEl = formEl.querySelector(`select[name="${field.name}"]`);
    if (!selectEl) return;

    const loadOptions = async () => {
      const activeRegion = document.getElementById('awsRegionSelect').value || region;
      selectEl.innerHTML = '<option value="">-- Loading --</option>';
      const params = { accountId: 'default', region: activeRegion };
      
      if (field.dependsOn) {
        field.dependsOn.split(',').forEach(dep => {
          const depEl = formEl.querySelector(`[name="${dep}"]`);
          if (depEl) params[dep] = depEl.value;
        });
      }

      try {
        const data = await apiGet(`${AWS_API_ROOT}${field.apiEndpoint}`, params, 20000);
        const items = data.instances || data.data || [];
        selectEl.innerHTML = '<option value="">-- Select --</option>';
        items.forEach(it => {
          const opt = document.createElement('option');
          opt.value = it.instanceType || it.code || it;
          opt.textContent = it.description ? `${opt.value} (${it.description})` : opt.value;
          selectEl.appendChild(opt);
        });
      } catch (e) {
        selectEl.innerHTML = '<option value="">-- Error --</option>';
      }
    };

    // Listen to local form changes (like OS)
    if (field.dependsOn) {
      field.dependsOn.split(',').forEach(dep => {
        const depEl = formEl.querySelector(`[name="${dep}"]`);
        if (depEl) depEl.addEventListener('change', loadOptions);
      });
    }

    // Listen to global region change
    document.getElementById('awsRegionSelect').addEventListener('change', loadOptions);

    selectEl.addEventListener('focus', () => { if(selectEl.options.length <= 1) loadOptions(); }, { once: true });
  });
}

async function onSubmitServiceForm(config, region, formEl, pricingResultContainer) {
  const values = readFormValues(formEl);
  const params = { accountId: 'default', region, ...values };

  try {
    const result = await apiGet(`${AWS_API_ROOT}${config.pricingEndpoint}`, params, 30000);
    renderPricingResult(config, result, pricingResultContainer, region, values);
  } catch (e) {
    pricingResultContainer.innerHTML = `<div class="p-4 bg-red-50 text-red-600 rounded-xl text-sm mt-4">Failed: ${e.message}</div>`;
  }
}

function renderPricingResult(config, result, container, region, formValues) {
  container.innerHTML = '';
  const total = Number(result.totalMonthly || 0);

  const header = document.createElement('div');
  header.className = 'bg-indigo-600 rounded-2xl p-6 text-white shadow-lg shadow-indigo-100 flex justify-between items-center mb-6';
  header.innerHTML = `
    <div>
      <p class="text-indigo-100 text-xs font-bold uppercase tracking-widest mb-1">Estimated Monthly Cost</p>
      <h3 class="text-sm font-medium">${config.serviceName} in ${region}</h3>
    </div>
    <div class="text-3xl font-bold">$${total.toFixed(2)}<span class="text-lg opacity-70 ml-1">USD</span></div>
  `;
  container.appendChild(header);

  if (result.compute || result.storage || result.requests) {
    const grid = document.createElement('div');
    grid.className = 'grid grid-cols-1 md:grid-cols-2 gap-4 mb-6';
    
    if (result.compute) {
      grid.innerHTML += `
        <div class="bg-white p-4 rounded-xl border border-gray-100 shadow-sm">
          <div class="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2">Compute Units</div>
          <div class="text-sm font-semibold text-gray-800">${result.compute.details || result.compute.instanceType || 'N/A'}</div>
          <div class="text-lg font-bold text-indigo-600 mt-1">$${Number(result.compute.monthlyCost || 0).toFixed(2)}</div>
        </div>`;
    }
    if (result.storage) {
      grid.innerHTML += `
        <div class="bg-white p-4 rounded-xl border border-gray-100 shadow-sm">
          <div class="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2">Storage Usage</div>
          <div class="text-sm font-semibold text-gray-800">${result.storage.sizeGB || result.storage.gb || 0} GB</div>
          <div class="text-lg font-bold text-indigo-600 mt-1">$${Number(result.storage.monthlyTotal || result.storage.monthlyCost || 0).toFixed(2)}</div>
        </div>`;
    }
    if (result.requests) {
      grid.innerHTML += `
        <div class="bg-white p-4 rounded-xl border border-gray-100 shadow-sm md:col-span-2">
          <div class="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2">API Operations</div>
          <div class="text-sm font-semibold text-gray-800">Requests & Data Transfer</div>
          <div class="text-lg font-bold text-indigo-600 mt-1">$${Number(result.requests.monthlyTotal || result.requests.monthlyCost || 0).toFixed(2)}</div>
        </div>`;
    }
    container.appendChild(grid);
  }

  if (result.pricingModels?.length > 0) {
    const tableContainer = document.createElement('div');
    tableContainer.className = 'bg-white rounded-xl border border-gray-100 overflow-hidden mb-6';
    let html = `
      <table class="w-full text-left text-xs">
        <thead class="bg-gray-50 text-gray-400 font-bold uppercase tracking-tighter">
          <tr><th class="px-4 py-3">Pricing Model</th><th class="px-4 py-3">Monthly</th><th class="px-4 py-3 text-right">Savings</th></tr>
        </thead>
        <tbody class="divide-y divide-gray-50">`;
    
    result.pricingModels.forEach(m => {
      html += `<tr>
        <td class="px-4 py-3 font-medium text-gray-700">${m.name}</td>
        <td class="px-4 py-3 font-bold text-gray-900">$${Number(m.monthlyTotal || m.monthlyCost).toFixed(2)}</td>
        <td class="px-4 py-3 text-right text-green-500 font-bold">${m.savings}</td>
      </tr>`;
    });
    tableContainer.innerHTML = html + '</tbody></table>';
    container.appendChild(tableContainer);
  }

  const addBtn = document.createElement('button');
  addBtn.className = 'w-full bg-green-600 text-white py-3.5 rounded-xl font-bold hover:bg-green-700 transition-all shadow-lg shadow-green-100 flex items-center justify-center gap-2';
  addBtn.innerHTML = '<i class="fas fa-cart-plus"></i> Add Estimate to Cart';
  addBtn.onclick = () => {
    estimateCart.push({
      id: Date.now(),
      serviceName: config.serviceName,
      region,
      monthlyCost: total,
      configSummary: buildConfigSummary(formValues)
    });
    saveCart();
    renderEstimateCart();
  };
  container.appendChild(addBtn);
}

function buildConfigSummary(formValues) {
  return Object.entries(formValues)
    .filter(([k, v]) => v && k !== 'region')
    .map(([k, v]) => `${k}=${v}`).join(', ');
}

function renderEstimateCart() {
  const tbody = document.getElementById('estimateCartBody');
  const totalEl = document.getElementById('estimateCartTotal');
  if (!tbody || !totalEl) return;

  tbody.innerHTML = '';
  let total = 0;

  if (estimateCart.length === 0) {
    tbody.innerHTML = '<tr><td colspan="3" class="px-6 py-10 text-center text-gray-400"><i class="fas fa-shopping-basket block text-2xl mb-2"></i> No estimates added yet</td></tr>';
  } else {
    estimateCart.forEach(item => {
      total += item.monthlyCost;
      const tr = document.createElement('tr');
      tr.className = 'hover:bg-gray-50 transition-colors';
      tr.innerHTML = `
        <td class="px-6 py-4">
          <div class="font-semibold text-gray-800">${item.serviceName}</div>
          <div class="text-[10px] text-gray-400 uppercase mt-1">${item.region} | ${item.configSummary}</div>
        </td>
        <td class="px-6 py-4 font-bold text-gray-900">$${item.monthlyCost.toFixed(2)}</td>
        <td class="px-6 py-4 text-right">
          <button class="text-gray-300 hover:text-red-500 transition-colors" onclick="removeFromCart(${item.id})">
            <i class="fas fa-times-circle"></i>
          </button>
        </td>`;
      tbody.appendChild(tr);
    });
  }
  totalEl.textContent = `$${total.toFixed(2)}`;
}

window.removeFromCart = (id) => {
  estimateCart = estimateCart.filter(i => i.id !== id);
  saveCart();
  renderEstimateCart();
};

function saveCart() { localStorage.setItem('xamops_calc_cart', JSON.stringify(estimateCart)); }
function loadCart() { 
  const saved = localStorage.getItem('xamops_calc_cart');
  if (saved) estimateCart = JSON.parse(saved);
}