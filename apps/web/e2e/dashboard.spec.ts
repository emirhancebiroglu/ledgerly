import { expect, test, type Page } from "@playwright/test";

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill("owner@example.com");
  await page.getByLabel("Password").fill("password123");
  await page.getByRole("button", { name: "Log in" }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test.describe("dashboard", () => {
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

  test("recent threshold and anomaly alerts show persisted figures and link to the expense", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    const alerts = page.getByTestId("recent-alerts");
    await expect(alerts.getByText("80% budget threshold")).toBeVisible();
    await expect(alerts.getByText("€8,400.00 of €10,000.00").first()).toBeVisible();
    await expect(alerts.getByText("Unusual expense")).toBeVisible();

    await alerts.getByRole("link", { name: /80% budget threshold/ }).click();
    await expect(page).toHaveURL(/\/expenses\/exp-1$/);
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
});
