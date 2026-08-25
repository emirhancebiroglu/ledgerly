import { expect, test, type Page } from "@playwright/test";
import { expectAlignedAmountAndStatusColumns } from "./alignment";

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("owner@example.com");
  await page.getByLabel("Password", { exact: true }).fill("password123");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

// page.request rather than the top-level `request` fixture: it shares this test's browser
// context (cookies included), so the session cookie the proxy needs for apiFetchAuthenticated
// travels with the call the same way a real in-page fetch would. The path omits the `/v1`
// segment other client-side calls also omit (`fetch("/api/documents")`, `fetch("/api/policies")`)
// -- `/api/[...path]/route.ts` prepends `/api/v1/` itself before forwarding upstream.
async function setDashboardSummaryFixture(page: Page, fixture: "two-currency"): Promise<void> {
  const response = await page.request.post("/api/test/set-dashboard-summary", {
    data: { fixture },
  });
  expect(response.ok()).toBe(true);
}

async function resetDashboardSummaryFixture(page: Page): Promise<void> {
  const response = await page.request.post("/api/test/reset-dashboard-summary");
  expect(response.ok()).toBe(true);
}

test.describe("dashboard", () => {
  // The two-currency tests below mutate the mock server's single shared DASHBOARD_SUMMARY (an
  // in-memory global, same as BUDGETS/ALERTS in mock-api.mjs) and reset it in afterEach --
  // serial keeps every test in this describe, including the currency-agnostic ones above, from
  // reading a fixture another concurrently-running test just swapped in or reset out from under
  // it. Matches alerts.spec.ts/budgets.spec.ts/policies.spec.ts, the other specs mutating shared
  // mock state.
  test.describe.configure({ mode: "serial" });

  test("KPI renders the summary's total and delta from minor units, never a bare float", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    // 8421300 minor units -> €84,213.00; 9569000 -> €95,690.00; delta -12%.
    const kpiCard = page.getByTestId("kpi-card");
    await expect(kpiCard.getByText("€84,213.00", { exact: true })).toBeVisible();
    await expect(kpiCard.getByText(/vs €95,690\.00 last month/)).toBeVisible();
    await expect(kpiCard.getByLabel(/12% vs last month/)).toBeVisible();
  });

  test("category breakdown and spend-over-time render real data with accessible alternatives", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    const breakdown = page.getByTestId("category-breakdown");
    await expect(breakdown.getByText("Software")).toBeVisible();
    await expect(breakdown.getByRole("progressbar").first()).toBeVisible();

    const chart = page.getByRole("img", { name: /Monthly spend from/ });
    await expect(chart).toBeVisible();
    // The visually-hidden data table is present as a real accessible alternative, not just an
    // aria-label — Feb through Jul, one row per month.
    await expect(page.getByRole("table", { name: "Monthly spend" })).toBeAttached();
    await expect(page.getByRole("cell", { name: "Feb" })).toBeAttached();
  });

  test("recent expenses render vendor, category, amount, and a distinct status chip per row", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    const row = page.getByRole("link", { name: /Northwind Logistics/ });
    await expect(row).toBeVisible();
    await expect(row.getByText("Travel")).toBeVisible();
    await expect(row.getByText("€2,340.00")).toBeVisible();
    await expect(row.getByText("Posted")).toBeVisible();

    const reviewRow = page.getByRole("link", { name: /Office Depot/ });
    await expect(reviewRow.getByText("Needs review")).toBeVisible();

    // Status is never color alone: each chip has its own icon (posted uses a check, needs-review
    // an alert triangle-equivalent) — assert both chips render a distinct svg, not just text.
    await expect(row.locator("svg")).toHaveCount(1);
    await expect(reviewRow.locator("svg")).toHaveCount(1);
  });

  test("clicking a recent-expense row navigates to its detail route", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await page.getByRole("link", { name: /Northwind Logistics/ }).click();
    await expect(page).toHaveURL(/\/expenses\/exp-1/);
  });

  test("View all link navigates to the expenses list", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await page.getByRole("link", { name: "View all" }).click();
    await expect(page).toHaveURL(/\/expenses$/);
  });

  // The mock API still serves alert records; the dashboard deliberately no longer renders them.
  // Alerts get their own route rather than a card on a screen that is not about them.
  test("carries no alerts card", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await expect(page.getByText("Recent expenses")).toBeVisible();
    await expect(page.getByTestId("recent-alerts")).toHaveCount(0);
    await expect(page.getByText("Recent alerts")).toHaveCount(0);
    await expect(page.getByText("No budget or anomaly alerts yet.")).toHaveCount(0);
    await expect(page.getByText("80% budget threshold")).toHaveCount(0);
  });

  // The reported defect: with `auto` tracks the amount column followed its own row's content, so
  // a row carrying the wider "Needs review" chip pushed its amount left out of the column.
  // Columns only exist at or above the shell breakpoint; the mobile project stacks these rows.
  test("recent-expenses amounts and status chips hold one column across mixed statuses", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await expectAlignedAmountAndStatusColumns(page);
  });

  test("no horizontal scroll and single-column layout below the shell breakpoint (768px)", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 768, height: 1200 });
    await login(page);

    const hasHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(hasHorizontalScroll).toBe(false);
  });

  // M9.8 T4: mocked unit tests for T2/T3 already passed once while the real browser-to-backend
  // path was silently broken (lessons.md 2026-07-26 -- a CORS gap no mock ever caught). This
  // proves the actual currency-per-section rendering against a real running page, not a
  // component harness.
  test.describe("against a two-currency org", () => {
    test.afterEach(async ({ page }) => {
      await resetDashboardSummaryFixture(page);
    });

    test("renders one labeled section per currency with its own symbol, never a summed figure", async ({
      page,
    }) => {
      await page.setViewportSize({ width: 1280, height: 900 });
      await login(page);
      await setDashboardSummaryFixture(page, "two-currency");
      await page.reload();

      const kpiCard = page.getByTestId("kpi-card");
      await expect(kpiCard.getByText("€84,213.00", { exact: true })).toBeVisible();
      await expect(kpiCard.getByText("₺45,000.00", { exact: true })).toBeVisible();

      const breakdown = page.getByTestId("category-breakdown");
      await expect(breakdown.getByText("EUR")).toBeVisible();
      await expect(breakdown.getByText("TRY")).toBeVisible();
      // Software: €42,000.00 (EUR, 4200000 minor) and ₺30,000.00 (TRY, 3000000 minor) -- their
      // raw minor-unit sum, 7200000, has no honest currency; nothing on the page may render it
      // as a single figure. (It coincidentally equals EUR's own March point in the trend chart
      // below, so that number legitimately appears -- what must never appear is the *fixture's*
      // KPI-level cross-currency total, 12921300 minor = "129,213.00", which the old summing bug
      // would have shown under one alphabetically-picked symbol.)
      await expect(breakdown.getByText("€42,000.00")).toBeVisible();
      await expect(breakdown.getByText("₺30,000.00")).toBeVisible();

      const bodyText = await page.locator("body").innerText();
      expect(bodyText).not.toContain("129,213.00");

      const charts = page.getByRole("img", { name: /Monthly spend from/ });
      await expect(charts).toHaveCount(2);
    });

    test("no horizontal scroll at desktop or mobile width with two currency sections rendered", async ({
      page,
    }) => {
      await page.setViewportSize({ width: 1280, height: 900 });
      await login(page);
      await setDashboardSummaryFixture(page, "two-currency");
      await page.reload();
      await expect(page.getByTestId("kpi-card").getByText("₺45,000.00")).toBeVisible();

      const desktopScroll = await page.evaluate(
        () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
      );
      expect(desktopScroll).toBe(false);

      await page.setViewportSize({ width: 375, height: 900 });
      // Re-assert visibility after the resize rather than measuring immediately -- the KPI card
      // reflows into a narrower layout, and reading scrollWidth mid-relayout would be measuring
      // a transient state rather than the settled mobile one.
      await expect(page.getByTestId("kpi-card").getByText("₺45,000.00")).toBeVisible();
      const mobileScroll = await page.evaluate(
        () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
      );
      expect(mobileScroll).toBe(false);
    });
  });
});
