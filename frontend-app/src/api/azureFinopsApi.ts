import {
  AzureFinOpsReportData,
  AzureReportSchedule,
  AzureNewBudget,
  AzureBudget,
  AzureNewSchedule
} from '../types/azureFinops';

/**
 * Fetch Azure FinOps report data for a subscription
 */
export const fetchAzureFinOpsReport = async (
  subscriptionId: string,
  forceRefresh: boolean = false
): Promise<AzureFinOpsReportData> => {
  try {
    const endpoint = `/api/azure/finops-report?subscriptionId=${subscriptionId}&forceRefresh=${forceRefresh}`;

    console.log(`🔷 Fetching Azure FinOps report from: ${endpoint}`);

    const response = await fetch(endpoint, {
      method: 'GET',
      credentials: 'include',
      headers: {
        'Accept': 'application/json',
      },
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const data = await response.json();
    console.log('🔷 Azure FinOps report data received:', data);

    return data;
  } catch (error) {
    console.error('❌ Failed to fetch Azure FinOps report:', error);
    throw error;
  }
};

/**
 * Fetch Azure FinOps report schedules for a subscription
 */
export const fetchAzureFinOpsSchedules = async (
  subscriptionId: string
): Promise<AzureReportSchedule[]> => {
  try {
    const endpoint = `/api/azure/finops-schedules?subscriptionId=${subscriptionId}`;

    console.log(`🔷 Fetching Azure FinOps schedules from: ${endpoint}`);

    const response = await fetch(endpoint, {
      method: 'GET',
      credentials: 'include',
      headers: {
        'Accept': 'application/json',
      },
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const data = await response.json();
    console.log('🔷 Azure FinOps schedules received:', data);

    return data;
  } catch (error) {
    console.error('❌ Failed to fetch Azure FinOps schedules:', error);
    throw error;
  }
};

/**
 * Create a new Azure budget
 */
export const createAzureBudget = async (
  subscriptionId: string,
  budget: AzureNewBudget
): Promise<AzureBudget> => {
  try {
    const endpoint = `/api/azure/finops/budgets?subscriptionId=${subscriptionId}`;

    console.log(`🔷 Creating Azure budget at: ${endpoint}`, budget);

    const response = await fetch(endpoint, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
      body: JSON.stringify(budget),
    });

    if (!response.ok) {
      // The controller returns {"error": "..."} naming the remediation — a rejected name or limit as
      // 400, a missing 'Cost Management Contributor' assignment as 403. A bare status threw that away.
      let message = `Failed to create budget (HTTP ${response.status})`;
      try {
        const body = await response.json();
        if (body?.error) message = body.error;
      } catch {
        // Non-JSON body; keep the status-based message.
      }
      throw new Error(message);
    }

    const data = await response.json();
    console.log('🔷 Azure budget created:', data);

    return data;
  } catch (error) {
    console.error('❌ Failed to create Azure budget:', error);
    throw error;
  }
};

/**
 * Delete an Azure budget
 */
export const deleteAzureBudget = async (
  subscriptionId: string,
  budgetName: string
): Promise<void> => {
  try {
    const endpoint = `/api/azure/finops/budgets?subscriptionId=${subscriptionId}&budgetName=${encodeURIComponent(budgetName)}`;

    console.log(`🔷 Deleting Azure budget at: ${endpoint}`);

    const response = await fetch(endpoint, {
      method: 'DELETE',
      credentials: 'include',
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    console.log('🔷 Azure budget deleted successfully');
  } catch (error) {
    console.error('❌ Failed to delete Azure budget:', error);
    throw error;
  }
};

/**
 * Create a new Azure FinOps report schedule
 */
export const createAzureFinOpsSchedule = async (
  schedule: AzureNewSchedule
): Promise<AzureReportSchedule> => {
  try {
    const endpoint = '/api/xamops/finops-schedules';

    console.log(`🔷 Creating Azure FinOps schedule at: ${endpoint}`, schedule);

    const response = await fetch(endpoint, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
      body: JSON.stringify(schedule),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(errorText || 'Failed to create schedule');
    }

    const data = await response.json();
    console.log('🔷 Azure FinOps schedule created:', data);

    return data;
  } catch (error) {
    console.error('❌ Failed to create Azure FinOps schedule:', error);
    throw error;
  }
};

/**
 * Delete an Azure FinOps report schedule
 */
export const deleteAzureFinOpsSchedule = async (
  scheduleId: number
): Promise<void> => {
  try {
    const endpoint = `/api/xamops/finops-schedules/${scheduleId}`;

    console.log(`🔷 Deleting Azure FinOps schedule at: ${endpoint}`);

    const response = await fetch(endpoint, {
      method: 'DELETE',
      credentials: 'include',
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    console.log('🔷 Azure FinOps schedule deleted successfully');
  } catch (error) {
    console.error('❌ Failed to delete Azure FinOps schedule:', error);
    throw error;
  }
};
