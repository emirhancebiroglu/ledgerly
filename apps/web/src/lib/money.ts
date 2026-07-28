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

export function formatMoney(amountMinor: number, currency: string): string {
  return new Intl.NumberFormat(DISPLAY_LOCALE, {
    style: "currency",
    currency,
    currencyDisplay: "symbol",
  }).format(amountMinor / 100);
}
