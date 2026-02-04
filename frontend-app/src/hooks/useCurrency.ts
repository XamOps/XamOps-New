import { useState, useCallback, useEffect } from 'react';
import { currencyService } from '../services/currencyService';
import { CurrencyList, ExchangeRateData } from '../types/currency';

export const useCurrency = () => {
    const [currencies, setCurrencies] = useState<CurrencyList | null>(null);
    const [rates, setRates] = useState<ExchangeRateData | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    /**
     * Load the list of all available currencies
     */
    const loadCurrencies = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const list = await currencyService.getCurrencies();
            setCurrencies(list);
        } catch (err) {
            setError('Failed to load currency list');
            console.error(err);
        } finally {
            setLoading(false);
        }
    }, []);

    /**
     * Load exchange rates for a base currency
     */
    const loadRates = useCallback(async (base: string = 'usd', date: string = 'latest') => {
        setLoading(true);
        setError(null);
        try {
            const data = await currencyService.getRates(base, date);
            setRates(data);
        } catch (err) {
            setError(`Failed to load rates for ${base}`);
            console.error(err);
        } finally {
            setLoading(false);
        }
    }, []);

    /**
     * Convert amount from base currency to target using loaded rates
     */
    const convert = useCallback((amount: number, targetCurrency: string): number | null => {
        if (!rates || !rates.rates) return null;

        // Normalize target code
        const target = targetCurrency.toLowerCase();

        // Check if target is same as base
        if (target === rates.base) return amount;

        const rate = rates.rates[target];
        if (rate === undefined) return null;

        return amount * rate;
    }, [rates]);

    // Optionally load currencies on mount (can be removed if lazy loading preferred)
    // useEffect(() => {
    //   loadCurrencies();
    // }, [loadCurrencies]);

    return {
        currencies,
        rates,
        loading,
        error,
        loadCurrencies,
        loadRates,
        convert
    };
};
