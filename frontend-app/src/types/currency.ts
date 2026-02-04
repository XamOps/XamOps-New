export interface CurrencyList {
    [code: string]: string;
}

export interface ExchangeRatesResponse {
    date: string;
    [baseCurrency: string]: any; // The key is dynamic (e.g., "eur", "usd"), value is map of rates
}

export interface CurrencyRates {
    [currencyCode: string]: number;
}

// Normalized response for easier consumption
export interface ExchangeRateData {
    date: string;
    base: string;
    rates: CurrencyRates;
}
