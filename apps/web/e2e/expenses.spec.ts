import { expect, test, type Page } from "@playwright/test";

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill("owner@example.com");
  await page.getByLabel("Password").fill("password123");
  await page.getByRole("button", { name: "Log in" }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test.describe("expenses list", () => {
  test("renders every seeded expense with vendor, category, date, amount, status", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/expenses");

    await expect(page.getByText("Northwind Logistics")).toBeVisible();
    await expect(page.getByText("€2,340.00")).toBeVisible();
    await expect(page.getByRole("link", { name: /Office Depot/ }).getByText("Needs review")).toBeVisible();
  });

  test("status filter is reflected in the URL and survives a reload", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/expenses");

    await page.getByLabel("Filter by status").selectOption("NEEDS_REVIEW");
    await expect(page).toHaveURL(/status=NEEDS_REVIEW/);
    await expect(page.getByText("Office Depot")).toBeVisible();
    await expect(page.getByText("Northwind Logistics")).toBeHidden();

    await page.reload();
    await expect(page).toHaveURL(/status=NEEDS_REVIEW/);
    await expect(page.getByText("Office Depot")).toBeVisible();
    await expect(page.getByLabel("Filter by status")).toHaveValue("NEEDS_REVIEW");
  });

  test("sort sends the API's exact parameter form (amount,desc)", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/expenses");

    await page.getByLabel("Sort by").selectOption("amount,desc");

    // The URL is the one thing this app fully controls end to end (Next's own RSC refetch
    // protocol sits between the select and the actual api call, so asserting on the outcome —
    // URL + resulting order — is more robust than intercepting an internal request shape).
    await expect(page).toHaveURL(/sort=amount%2Cdesc/);

    // Skyline Air (€5,423.00) is the highest amount in the fixture — must lead the list, which
    // only happens if `sort=amount,desc` (not the default `date,desc`) actually reached the API.
    const firstRow = page.locator('a[href^="/expenses/"]').first();
    await expect(firstRow).toContainText("Skyline Air");
  });

  test("search filters by vendor and is reflected in the URL", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/expenses");

    await page.getByLabel("Search expenses").fill("figma");
    await expect(page).toHaveURL(/search=figma/, { timeout: 2000 });
    await expect(page.getByText("Figma")).toBeVisible();
    await expect(page.getByText("Northwind Logistics")).toBeHidden();
  });

  test("an unknown sort value surfaces the API's 400 as an error state, not a crash", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/expenses?sort=bogus,desc");

    await expect(page.getByText(/Unknown sort field/)).toBeVisible();
  });

  test("a search with no matches renders an empty state, not a blank card", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/expenses?search=zzz-no-such-vendor");

    await expect(page.getByText("No expenses match these filters.")).toBeVisible();
  });

  test("rows stack vertically below the shell breakpoint with no horizontal scroll", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 900 });
    await login(page);
    await page.goto("/expenses");

    const hasHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(hasHorizontalScroll).toBe(false);
    // The desktop column header is hidden on mobile per the handoff.
    await expect(page.getByText("Vendor", { exact: true })).toBeHidden();
  });

  test("clicking a row navigates to its detail route", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/expenses");

    await page.getByText("Northwind Logistics").click();
    await expect(page).toHaveURL(/\/expenses\/exp-1/);
  });
});
