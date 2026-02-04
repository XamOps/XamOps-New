import { CurrencyList, ExchangeRateData, ExchangeRatesResponse } from '../types/currency';

const API_VERSION = 'v1';
const PRIMARY_BASE_URL = 'https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@';
const FALLBACK_BASE_URL_TEMPLATE = 'https://{date}.currency-api.pages.dev/';

/**
 * Service to handle currency conversion API requests
 * Supports primary CDN and fallback to Cloudflare Pages
 */
export const currencyService = {

    /**
     * Get list of available currencies
     */
    async getCurrencies(date: string = 'latest'): Promise<CurrencyList> {
        const endpoint = 'currencies.json';
        return this.fetchWithFallback<CurrencyList>(date, endpoint);
    },

    /**
     * Get exchange rates for a specific base currency
     * @param baseCurrency e.g. 'eur', 'usd', 'btc'
     * @param date 'latest' or 'YYYY-MM-DD'
     * @param minified Use minified JSON response
     */
    async getRates(
        baseCurrency: string,
        date: string = 'latest',
        minified: boolean = true
    ): Promise<ExchangeRateData> {
        const cleanBase = baseCurrency.toLowerCase();
        // Endpoint format: currencies/{currencyCode}.json (or .min.json)
        const extension = minified ? '.min.json' : '.json';
        const endpoint = `currencies/${cleanBase}${extension}`;

        const response = await this.fetchWithFallback<ExchangeRatesResponse>(date, endpoint);

        // Normalize response
        // The API returns { date: "...", "eur": { "usd": 1.1, ... } }
        if (response && response[cleanBase]) {
            return {
                date: response.date,
                base: cleanBase,
                rates: response[cleanBase]
            };
        }

        throw new Error(`Invalid response format for base currency: ${baseCurrency}`);
    },

    /**
     * Fetch data with fallback mechanism
     */
    async fetchWithFallback<T>(date: string, endpointPartial: string): Promise<T> {
        // 1. Try Primary URL (jsdelivr)
        // Format: https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@{date}/{apiVersion}/{endpoint}
        const primaryUrl = `${PRIMARY_BASE_URL}${date}/${API_VERSION}/${endpointPartial}`;

        try {
            console.log(`[CurrencyService] Fetching from primary: ${primaryUrl}`);
            const response = await fetch(primaryUrl);
            if (!response.ok) throw new Error(`Primary failed with status: ${response.status}`);
            return await response.json();
        } catch (primaryError) {
            console.warn(`[CurrencyService] Primary URL failed, trying fallback. Error:`, primaryError);

            // 2. Try Fallback URL (Cloudflare Pages)
            // Format: https://{date}.currency-api.pages.dev/{apiVersion}/{endpoint}
            // Note: If date is 'latest', subdomain is 'latest'. If '2024-03-06', subdomain is '2024-03-06'.
            const fallbackBase = FALLBACK_BASE_URL_TEMPLATE.replace('{date}', date);
            const fallbackUrl = `${fallbackBase}${API_VERSION}/${endpointPartial}`;

            try {
                console.log(`[CurrencyService] Fetching from fallback: ${fallbackUrl}`);
                const response = await fetch(fallbackUrl);
                if (!response.ok) throw new Error(`Fallback failed with status: ${response.status}`);
                return await response.json();
            } catch (fallbackError) {
                console.error(`[CurrencyService] All providers failed.`, fallbackError);
                throw new Error('Failed to fetch currency data from all providers');
            }
        }
    }
};
