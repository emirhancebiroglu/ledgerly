// Fixed rather than the system locale — same reasoning as money.ts's DISPLAY_LOCALE: dates are
// meant to render the same regardless of which machine renders them.
const DISPLAY_LOCALE = "en-US";

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(DISPLAY_LOCALE, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(DISPLAY_LOCALE, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}
