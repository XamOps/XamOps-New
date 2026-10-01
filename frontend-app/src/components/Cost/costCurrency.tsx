import React from 'react';

/**
 * The billing currency for a cost section, and one way to render money in it.
 *
 * <p>Every section on the Cost pages renders currency, and until these pages served more than one
 * cloud each of them hardcoded a symbol: `'$'` in two places and the literal string `'USD '` in a
 * third. That was harmless while every Cost and Usage Report billed in dollars. It stopped being
 * harmless when the same components began rendering a GCP project, because a billing account is
 * set to whatever currency the customer signed up in — and a rupee figure shown with a dollar sign
 * is not a cosmetic bug. It understates the number by roughly ninety-fold to anyone reading
 * quickly, which on a cost page is the whole point of the number.
 *
 * <p>A context rather than a prop because the sections that need it render money several
 * components deep — a resource row inside a service group inside the section — and none of those
 * rows otherwise needs anything from the top. Threading a formatter through them would add a prop
 * to every intermediate component that does not use it.
 *
 * <p>Defaults to USD so a caller that has not been updated keeps its previous behaviour.
 */
export const CostCurrencyContext = React.createContext('USD');

/**
 * Formats an amount in the section's currency, resolving the symbol from the ISO code.
 *
 * <p>`Intl.NumberFormat` rather than a symbol lookup table: it knows every code, places the symbol
 * where the locale expects it, and handles negatives — which matters here because credits and
 * refunds are negative and appear on an invoice view.
 *
 * <p>An unrecognised code falls back to printing the code beside the number. That is uglier than a
 * symbol and far better than the alternative, which is a constructor throw on every render taking
 * the whole section down over a formatting detail.
 */
export const useMoney = (): ((value: number) => string) =>
  useMoneyIn(React.useContext(CostCurrencyContext));

/**
 * The same formatter for a currency passed directly, where a section renders all its own money and
 * needs no context. Keeps one definition of how an amount is written.
 */
export const useMoneyIn = (currency: string): ((value: number) => string) => {
  return React.useMemo(() => {
    try {
      const formatter = new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency,
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      });
      return (value: number) => formatter.format(value);
    } catch {
      return (value: number) =>
        `${currency} ${value.toLocaleString('en-US', {
          minimumFractionDigits: 2,
          maximumFractionDigits: 2,
        })}`;
    }
  }, [currency]);
};
