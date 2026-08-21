/**
 * `amountMinor` (integer, smallest currency unit — cents for EUR/USD) → a localized display
 * string. `Intl.NumberFormat` is given the major-unit float only at the very last step, for
 * display only — no arithmetic (totals, comparisons, sorting) ever happens in that
 * representation; the API's minor-unit integers are the only values used for those.
 */
// Fixed rather than `undefined` (system locale) — the design's numbers are always
// comma-thousands/period-decimal (`$1,234.56`, matching the handoff's Geist Mono figures), and
// letting `Intl` pick up whatever locale the deploying machine happens to have would silently
// change that format per-environment rather than per-user preference (which nothing here reads).
const DISPLAY_LOCALE = "en-US";
// `narrowSymbol`, not `symbol`: under the pinned `en-US` locale `symbol` resolves TRY to the
// literal code ("TRY 1,234.56") while USD/EUR/GBP get real symbols, so a mixed-currency list read
// as inconsistent. `narrowSymbol` yields an actual symbol for every currency Ledgerly supports and
// leaves the already-correct ones unchanged.
const CURRENCY_DISPLAY = "narrowSymbol" as const;
const ZERO = BigInt(0);
const HUNDRED = BigInt(100);

export function formatMoney(amountMinor: number | bigint, currency: string): string {
  if (typeof amountMinor === "bigint") {
    const negative = amountMinor < ZERO;
    const absolute = negative ? -amountMinor : amountMinor;
    const major = absolute / HUNDRED;
    const fraction = (absolute % HUNDRED).toString().padStart(2, "0");
    const formatter = new Intl.NumberFormat(DISPLAY_LOCALE, {
      style: "currency",
      currency,
      currencyDisplay: CURRENCY_DISPLAY,
    });
    const majorDisplay = new Intl.NumberFormat(DISPLAY_LOCALE).format(major);
    const display = formatter
      .formatToParts(0)
      .map((part) => {
        if (part.type === "integer") return majorDisplay;
        if (part.type === "fraction") return fraction;
        return part.value;
      })
      .join("");
    return negative ? `-${display}` : display;
  }
  return new Intl.NumberFormat(DISPLAY_LOCALE, {
    style: "currency",
    currency,
    currencyDisplay: CURRENCY_DISPLAY,
  }).format(amountMinor / 100);
}
