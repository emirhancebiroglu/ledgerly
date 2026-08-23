import { formatMoney } from "@/lib/money";
import { formatDateTime } from "@/lib/date";
import type { Alert } from "@/lib/alerts";

/** Mirrors `categoryNameLookup` in `@/lib/categories`, duplicated rather than imported: that
 * module also exports `listCategories`, which calls the server-only `apiFetchAuthenticated` —
 * importing it from this client-rendered card would pull server-only code into the client
 * bundle. The category list itself is passed down as plain data (a client component prop), not a
 * server-built lookup function, which cannot cross that boundary either. */
export function categoryNameLookup(
  categories: { id: string; name: string }[],
): (categoryId: string | null) => string {
  const byId = new Map(categories.map((c) => [c.id, c.name]));
  return (categoryId) => (categoryId ? (byId.get(categoryId) ?? "Uncategorized") : "Uncategorized");
}

/**
 * Assembles the alert's descriptive sentence in the browser. The API deliberately never embeds a
 * formatted amount in `title` — money display is a browser-only concern (`formatMoney`), so this
 * is the one place a `BUDGET_THRESHOLD` alert's exact spent/limit figures and an `ANOMALY_HIGH`
 * alert's persisted `explanation` become the sentence shown on the card.
 */
export function alertBody(
  alert: Alert,
  categoryName: (categoryId: string | null) => string,
): string {
  switch (alert.alertType) {
    case "BUDGET_THRESHOLD": {
      const category = categoryName(alert.categoryId);
      const spent = alert.spentMinor != null ? formatMoney(alert.spentMinor, alert.currency) : null;
      const limit = alert.limitMinor != null ? formatMoney(alert.limitMinor, alert.currency) : null;
      const percent = alert.thresholdPercent != null ? `${alert.thresholdPercent}%` : null;
      if (spent && limit && percent) {
        return `${category} is at ${percent} of its ${limit} monthly limit (${spent} spent).`;
      }
      return `${category} has crossed its budget threshold.`;
    }
    case "ANOMALY_HIGH":
      return alert.explanation ?? "This expense is well outside this category's typical spend.";
    case "LOW_CONFIDENCE": {
      const confidence =
        alert.categorizationConfidence != null
          ? `${Math.round(alert.categorizationConfidence * 100)}%`
          : null;
      return confidence
        ? `Categorized at ${confidence} confidence, below the review threshold.`
        : "Categorization confidence was below the review threshold.";
    }
    case "DUPLICATE_SUSPECTED": {
      const match = alert.matchedExpense;
      const trigger = alert.triggeringExpense;
      const verb = alert.duplicateTier === "CONFIRMED" ? "carries the same invoice number as" : "closely matches";
      const newEntry = trigger ? `This entry for ${formatMoney(trigger.amountMinor, trigger.currency)}` : "This entry";
      if (!match) {
        return `${newEntry} ${verb} an earlier entry that is no longer available to compare.`;
      }
      const earlierEntry = `an earlier entry for ${formatMoney(match.amountMinor, match.currency)}, posted ${formatDateTime(match.createdAt)}`;
      return `${newEntry} ${verb} ${earlierEntry}.`;
    }
    default:
      return "";
  }
}
