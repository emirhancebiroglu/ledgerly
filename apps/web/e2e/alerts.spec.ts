import { expect, test, type Page } from "@playwright/test";

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("owner@example.com");
  await page.getByLabel("Password", { exact: true }).fill("password123");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test.describe("alerts", () => {
  // Read/dismiss mutate the mock's shared in-memory ALERTS array — keep this spec serialized so
  // its assertions cannot race another alerts browser flow.
  test.describe.configure({ mode: "serial" });

  test.beforeEach(async ({ page }) => {
    const apiPort = process.env.E2E_API_PORT ?? "8081";
    const response = await page.request.post(`http://localhost:${apiPort}/api/v1/test/reset-alerts`);
    expect(response.ok()).toBe(true);
  });

  test("renders one card per alert with its type chip and title", async ({ page }) => {
    await login(page);
    await page.goto("/alerts");
    const cards = page.getByTestId("alert-card");
    await expect(cards).toHaveCount(3);
    await expect(cards.filter({ hasText: "Travel nearing its budget" }).getByText("Budget", { exact: true })).toBeVisible();
    await expect(cards.filter({ hasText: "Unusual spending detected" }).getByText("Anomaly", { exact: true })).toBeVisible();
    await expect(cards.filter({ hasText: "Low-confidence categorization needs review" }).getByText("Review", { exact: true })).toBeVisible();
  });

  test("filter tabs narrow the list to one alert type", async ({ page }) => {
    await login(page);
    await page.goto("/alerts");
    await page.getByRole("button", { name: "Budget", exact: true }).click();
    await expect(page.getByTestId("alert-card")).toHaveCount(1);
    await expect(page.getByText("Travel nearing its budget")).toBeVisible();
  });

  test("the empty state appears when a filter yields nothing", async ({ page }) => {
    await login(page);
    await page.goto("/alerts");
    await page.getByRole("button", { name: "Anomaly", exact: true }).click();
    // Dismiss the only anomaly alert so the filter is genuinely empty, not just unfiltered.
    await page.getByRole("button", { name: "Dismiss alert" }).click();
    await expect(page.getByText("Nothing needs your attention")).toBeVisible();
  });

  test("the CTA routes a budget alert to /budgets and an anomaly alert to its expense", async ({ page }) => {
    await login(page);
    await page.goto("/alerts");
    await page
      .getByTestId("alert-card")
      .filter({ hasText: "Travel nearing its budget" })
      .getByRole("link", { name: "Review budget" })
      .click();
    await expect(page).toHaveURL(/\/budgets$/);

    await page.goto("/alerts");
    await page
      .getByTestId("alert-card")
      .filter({ hasText: "Unusual spending detected" })
      .getByRole("link", { name: "Open expense" })
      .click();
    await expect(page).toHaveURL(/\/expenses\/exp-2$/);
  });

  test("dismissing a card removes it and the dismissal persists across reload", async ({ page }) => {
    await login(page);
    await page.goto("/alerts");
    await page
      .getByTestId("alert-card")
      .filter({ hasText: "Low-confidence categorization needs review" })
      .getByRole("button", { name: "Dismiss alert" })
      .click();
    await expect(page.getByText("Low-confidence categorization needs review")).toHaveCount(0);

    await page.reload();
    await expect(page.getByText("Low-confidence categorization needs review")).toHaveCount(0);
    await expect(page.getByTestId("alert-card")).toHaveCount(2);
  });

  test("an unauthenticated request redirects to sign-in", async ({ page, context }) => {
    await context.clearCookies();
    await page.goto("/alerts");
    await expect(page).toHaveURL(/\/login/);
  });
});
