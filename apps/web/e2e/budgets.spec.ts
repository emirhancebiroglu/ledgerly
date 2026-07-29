import { expect, test, type Page } from "@playwright/test";

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("owner@example.com");
  await page.getByLabel("Password", { exact: true }).fill("password123");
  await page.getByRole("button", { name: "Log in" }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test.describe("budgets", () => {
  // The create/failure case deliberately writes the mock's shared in-memory collection. Keep
  // this spec serialized so its assertions cannot race another budget browser flow.
  test.describe.configure({ mode: "serial" });

  test.beforeEach(async ({ page }) => {
    const apiPort = process.env.E2E_API_PORT ?? "8081";
    const response = await page.request.post(`http://localhost:${apiPort}/api/v1/test/reset-budgets`);
    expect(response.ok()).toBe(true);
  });
  test("renders exact minor-unit amounts and status text/icon, including the 80% warning", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/budgets");
    const cards = page.getByTestId("budget-card");
    await expect(cards).toHaveCount(4);
    await expect(cards.filter({ hasText: "Software" }).getByText("€0.00")).toBeVisible();
    const onTrack = cards.filter({ hasText: "Travel" });
    await expect(onTrack.getByText("€7,900.00")).toBeVisible();
    const warning = cards.filter({ hasText: "Office supplies" });
    await expect(warning.getByText("80% reached")).toBeVisible();
    await expect(warning.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "84");
    await expect(warning.locator("svg").first()).toBeVisible();
    await expect(cards.filter({ hasText: "2026-08" }).getByText("Limit exceeded")).toBeVisible();
  });

  test("creates a budget and keeps a server validation failure visible", async ({ page }) => {
    await login(page);
    await page.goto("/budgets");
    await page.getByRole("button", { name: "New budget" }).click();
    await page.getByLabel("Category").selectOption("cat-3");
    await page.getByLabel("Month", { exact: true }).fill("2026-08");
    await page.getByLabel("Monthly limit (minor units)").fill("250000");
    await page.getByLabel("Currency").fill("EUR");
    await page.getByRole("button", { name: "Save budget" }).click();
    const created = page.getByTestId("budget-card").filter({ hasText: "Office supplies2026-08" });
    await expect(created).toBeVisible();

    await page.getByRole("button", { name: "New budget" }).click();
    await page.getByLabel("Category").selectOption("cat-3");
    await page.getByLabel("Month", { exact: true }).fill("2026-08");
    await page.getByLabel("Monthly limit (minor units)").fill("250000");
    await page.getByRole("button", { name: "Save budget" }).click();
    await expect(page.getByText("A budget already exists for this category, period and currency")).toBeVisible();
    await expect(created).toBeVisible();
  });

  test("palette includes Budgets, Alerts remains disabled, and no horizontal scroll at 859/860px", async ({ page }) => {
    await login(page);
    for (const width of [859, 860]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto("/budgets");
      const hasHorizontalScroll = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
      expect(hasHorizontalScroll).toBe(false);
    }
    await page.getByRole("button", { name: "Search or jump to..." }).click();
    await page.getByRole("option", { name: "Go to Budgets" }).click();
    await expect(page).toHaveURL(/\/budgets$/);
    const alerts = page.getByText("Alerts", { exact: true });
    await expect(alerts.locator("xpath=ancestor-or-self::*[@aria-disabled='true']")).toHaveCount(1);
  });
});
