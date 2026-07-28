import { expect, test, type Page } from "@playwright/test";

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill("owner@example.com");
  await page.getByLabel("Password").fill("password123");
  await page.getByRole("button", { name: "Log in" }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test.describe("app shell", () => {
  test("desktop: sidebar is visible, no hamburger, no horizontal scroll at 1280px", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await expect(page.getByRole("navigation", { name: "Main" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Open navigation" })).toBeHidden();

    const hasHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(hasHorizontalScroll).toBe(false);
  });

  test("mobile: sidebar is hidden, drawer opens via hamburger, no horizontal scroll at 360px", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await login(page);

    await expect(page.getByRole("navigation", { name: "Main" })).toBeHidden();

    const hasHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(hasHorizontalScroll).toBe(false);

    const hamburger = page.getByRole("button", { name: "Open navigation" });
    await expect(hamburger).toBeVisible();
    await hamburger.click();

    const drawerNav = page.getByRole("navigation", { name: "Main" });
    await expect(drawerNav).toBeVisible();

    // Clicking a nav item inside the drawer navigates and closes it.
    await page.getByRole("link", { name: "Expenses" }).click();
    await expect(page).toHaveURL(/\/expenses/);
    await expect(drawerNav).toBeHidden();
  });

  test("no horizontal scroll at the tablet width (768px)", async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 1024 });
    await login(page);

    const hasHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(hasHorizontalScroll).toBe(false);
  });

  test("Ctrl+K and Cmd+K both open the command palette, Escape closes it, focus returns", async ({
    page,
    browserName,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    const trigger = page.getByRole("button", { name: "Search or jump to..." });
    await trigger.focus();

    await page.keyboard.press(browserName === "webkit" ? "Meta+k" : "Control+k");
    const palette = page.getByRole("dialog");
    await expect(palette).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(palette).toBeHidden();
    await expect(trigger).toBeFocused();
  });

  test("a click on a quick-jump row navigates and closes the palette", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await page.getByRole("button", { name: "Search or jump to..." }).click();
    await page.getByRole("option", { name: "Go to Review queue" }).click();

    await expect(page).toHaveURL(/\/review/);
    await expect(page.getByRole("dialog")).toBeHidden();
  });

  test("disabled nav items (Budgets, Alerts, Policies) are not focusable links", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    for (const label of ["Budgets", "Alerts", "Policies"]) {
      const item = page.getByText(label, { exact: true });
      await expect(item).toBeVisible();
      const tagName = await item.evaluate((el) => el.closest("a") !== null);
      expect(tagName).toBe(false);
    }
  });

  test("review queue nav item shows the count from the dashboard summary", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await expect(page.getByRole("navigation", { name: "Main" }).getByText("3")).toBeVisible();
  });
});
